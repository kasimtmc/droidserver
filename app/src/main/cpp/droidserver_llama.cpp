// Telefonda yerel YZ: llama.cpp'nin küçük bir JNI sarmalayıcısı (sohbet tamamlama ve gömme). Bir tutamaç = bir model +
// bir bağlam; aynı tutamaçta çağrılar Kotlin tarafında sıraya girer. Metinler UTF-8 bayt dizisi olarak taşınır
// (JNI'ın "değiştirilmiş UTF-8"i 4 baytlık karakterleri bozar).

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

// Masaüstü JVM'de de derlenir (sarmalayıcının testi için).
#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "droidserver-llama", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) std::fprintf(stderr, __VA_ARGS__)
#endif

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    bool embeddings = false;
    std::atomic<bool> cancel{false};
};

std::once_flag backend_once;

std::string bytes(JNIEnv * env, jbyteArray a) {
    if (a == nullptr) return {};
    const jsize n = env->GetArrayLength(a);
    std::string s(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(a, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return a;
}

void throw_io(JNIEnv * env, const std::string & msg) {
    jclass c = env->FindClass("java/io/IOException");
    if (c != nullptr) env->ThrowNew(c, msg.c_str());
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    int n = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, add_special, true);
    std::vector<llama_token> out(static_cast<size_t>(std::max(n, 0)));
    if (n > 0 && llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), out.data(), n, add_special, true) < 0) out.clear();
    return out;
}

std::string piece(const llama_vocab * vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(vocab, t, big.data(), -n, 0, false);
        return n > 0 ? big.substr(0, static_cast<size_t>(n)) : std::string();
    }
    return std::string(buf, static_cast<size_t>(n));
}

/** Sohbet şablonu: modelin kendi şablonu; tanınmıyorsa ChatML. */
std::string apply_template(const llama_model * model, const std::vector<llama_chat_message> & msgs) {
    const char * tmpl = llama_model_chat_template(model, nullptr);
    size_t total = 0;
    for (auto & m : msgs) total += std::strlen(m.content) + 32;
    std::vector<char> buf(total * 2 + 256);
    for (const char * t : {tmpl, "chatml"}) {
        if (t == nullptr) continue;
        int n = llama_chat_apply_template(t, msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
        if (n > static_cast<int>(buf.size())) {
            buf.resize(static_cast<size_t>(n));
            n = llama_chat_apply_template(t, msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
        }
        if (n >= 0) return std::string(buf.data(), static_cast<size_t>(n));
    }
    return {};
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_plyg_droidserver_app_ai_LlamaNative_load(JNIEnv * env, jobject, jstring path, jint n_ctx, jint n_threads, jboolean embeddings) {
    std::call_once(backend_once, [] { llama_backend_init(); });
    const char * p = env->GetStringUTFChars(path, nullptr);
    llama_model_params mp = llama_model_default_params();
    llama_model * model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (model == nullptr) {
        throw_io(env, "model could not be loaded");
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = static_cast<uint32_t>(embeddings ? n_ctx : 512);
    cp.n_ubatch = cp.n_batch;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.embeddings = embeddings;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        llama_model_free(model);
        throw_io(env, "context could not be created (not enough memory?)");
        return 0;
    }
    auto * e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->embeddings = embeddings;
    LOGI("loaded: ctx=%d threads=%d embeddings=%d", n_ctx, n_threads, embeddings ? 1 : 0);
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT void JNICALL
Java_com_plyg_droidserver_app_ai_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr) return;
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

extern "C" JNIEXPORT void JNICALL
Java_com_plyg_droidserver_app_ai_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e != nullptr) e->cancel = true;
}

/**
 * Sohbeti modelin şablonuyla tamamlar. [roles] ve [contents] aynı uzunlukta (system/user/assistant). Sonuç dizisi:
 * 0: üretilen metin (UTF-8), 1: girdi token sayısı ve çıktı token sayısı ve bitiş ("stop"/"length"/"cancel") metni.
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_plyg_droidserver_app_ai_LlamaNative_complete(JNIEnv * env, jobject, jlong handle, jobjectArray roles, jobjectArray contents, jint max_tokens, jfloat temperature) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr || e->embeddings) { throw_io(env, "not a chat model"); return nullptr; }
    e->cancel = false;
    const jsize n_msg = env->GetArrayLength(roles);
    std::vector<std::string> r(static_cast<size_t>(n_msg)), c(static_cast<size_t>(n_msg));
    std::vector<llama_chat_message> msgs;
    for (jsize i = 0; i < n_msg; i++) {
        r[i] = bytes(env, static_cast<jbyteArray>(env->GetObjectArrayElement(roles, i)));
        c[i] = bytes(env, static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i)));
    }
    for (jsize i = 0; i < n_msg; i++) msgs.push_back({r[i].c_str(), c[i].c_str()});
    const llama_vocab * vocab = llama_model_get_vocab(e->model);
    std::string prompt = apply_template(e->model, msgs);
    std::vector<llama_token> tokens = tokenize(vocab, prompt, true);
    const int n_ctx = static_cast<int>(llama_n_ctx(e->ctx));
    const int budget = std::max(16, std::min(static_cast<int>(max_tokens), n_ctx / 2));
    // Uzun girdi: baştan kırpılır (son iletiler kalır).
    if (static_cast<int>(tokens.size()) > n_ctx - budget) tokens.erase(tokens.begin(), tokens.end() - (n_ctx - budget));
    llama_memory_clear(llama_get_memory(e->ctx), true);
    for (size_t i = 0; i < tokens.size(); i += 512) {
        const int n = static_cast<int>(std::min<size_t>(512, tokens.size() - i));
        if (llama_decode(e->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) { throw_io(env, "decode failed"); return nullptr; }
    }
    auto sp = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    std::string out;
    std::string reason = "length";
    int produced = 0;
    for (; produced < budget; produced++) {
        if (e->cancel) { reason = "cancel"; break; }
        llama_token t = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) { reason = "stop"; break; }
        out += piece(vocab, t);
        if (llama_decode(e->ctx, llama_batch_get_one(&t, 1)) != 0) { reason = "length"; break; }
    }
    llama_sampler_free(smpl);
    jobjectArray result = env->NewObjectArray(2, env->FindClass("[B"), nullptr);
    env->SetObjectArrayElement(result, 0, to_bytes(env, out));
    env->SetObjectArrayElement(result, 1, to_bytes(env, std::to_string(tokens.size()) + " " + std::to_string(produced) + " " + reason));
    return result;
}

/** Metnin gömme vektörü (modelin havuzlamasıyla, L2 normalleştirilmiş). */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_plyg_droidserver_app_ai_LlamaNative_embed(JNIEnv * env, jobject, jlong handle, jbyteArray text) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr || !e->embeddings) { throw_io(env, "not an embedding model"); return nullptr; }
    const llama_vocab * vocab = llama_model_get_vocab(e->model);
    std::vector<llama_token> tokens = tokenize(vocab, bytes(env, text), true);
    const int n_ctx = static_cast<int>(llama_n_ctx(e->ctx));
    if (static_cast<int>(tokens.size()) > n_ctx) tokens.resize(static_cast<size_t>(n_ctx));
    if (tokens.empty()) { throw_io(env, "empty text"); return nullptr; }
    llama_memory_clear(llama_get_memory(e->ctx), true);
    llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = static_cast<llama_pos>(i);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 1;
    }
    batch.n_tokens = static_cast<int32_t>(tokens.size());
    const int rc = llama_decode(e->ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) { throw_io(env, "embedding failed"); return nullptr; }
    const int n_embd = llama_model_n_embd_out(e->model);
    const float * v = llama_pooling_type(e->ctx) == LLAMA_POOLING_TYPE_NONE
        ? llama_get_embeddings_ith(e->ctx, static_cast<int32_t>(tokens.size()) - 1)
        : llama_get_embeddings_seq(e->ctx, 0);
    if (v == nullptr) { throw_io(env, "no embedding"); return nullptr; }
    double norm = 0;
    for (int i = 0; i < n_embd; i++) norm += static_cast<double>(v[i]) * v[i];
    const float k = norm > 0 ? static_cast<float>(1.0 / std::sqrt(norm)) : 1.0f;
    std::vector<float> out(static_cast<size_t>(n_embd));
    for (int i = 0; i < n_embd; i++) out[static_cast<size_t>(i)] = v[i] * k;
    jfloatArray a = env->NewFloatArray(n_embd);
    env->SetFloatArrayRegion(a, 0, n_embd, out.data());
    return a;
}
