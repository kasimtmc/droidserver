@file:OptIn(ExperimentalSerializationApi::class)

package dev.droidserver.core.packages

import dev.droidserver.core.config.ConfigMigrations
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipInputStream

/** Paketin kimliği (`package.json`). */
@Serializable
data class PackageManifest(
    val name: String,
    val version: String,
    val title: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val description: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val publisher: String? = null,
    /** Gereken konfigürasyon sürümü (bu sürümden eski uygulamaya kurulmaz). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val minConfigVersion: Int = 1,
)

/** Kurulu paket ve getirdikleri (güncelleme ve kaldırma için); `server.json`'da `packages`. */
@Serializable
data class InstalledPackage(
    val name: String,
    val version: String,
    val title: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val publisher: String? = null,
    /** İmzalıysa yayımcı anahtarının parmak izi (SHA-256, ilk 16 bayt). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val key: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val collections: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val views: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val automations: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val forms: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val pages: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val dashboards: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val scripts: List<String> = emptyList(),
)

/** İmza durumu. */
sealed interface PackageSignature {
    data object Unsigned : PackageSignature
    data class Signed(val fingerprint: String) : PackageSignature
    data class Invalid(val reason: String) : PackageSignature
}

/**
 * Okunmuş paket: `package.json`, `config.json` (koleksiyonlar, görünümler, otomasyonlar, formlar, sayfalar, panolar;
 * `ServerConfig`'in bu alanları), `scripts/<ad>.js`, `sample/<koleksiyon>.json` (örnek kayıtlar; `"@ref:koleksiyon:sıra"`)
 * ve isteğe bağlı `signature.json`.
 */
class PackageFile(
    val manifest: PackageManifest,
    val config: ServerConfig,
    val scripts: Map<String, String>,
    val samples: List<Pair<String, List<JsonObject>>>,
    val signature: PackageSignature,
)

/** Kurulum planı: ne eklenecek, güncellenecek, kaldırılacak; çakışmalar ve engeller. */
data class PackagePlan(
    val manifest: PackageManifest,
    val previous: InstalledPackage?,
    val signature: PackageSignature,
    val adds: List<String>,
    val updates: List<String>,
    val removes: List<String>,
    val conflicts: List<String>,
    val changes: List<String>,
    val errors: List<String>,
) {
    val blocked: Boolean get() = conflicts.isNotEmpty() || errors.isNotEmpty() || signature is PackageSignature.Invalid

    fun toJson(): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(manifest.name))
        put("version", JsonPrimitive(manifest.version))
        put("title", JsonPrimitive(manifest.title))
        manifest.description?.let { put("description", JsonPrimitive(it)) }
        manifest.publisher?.let { put("publisher", JsonPrimitive(it)) }
        previous?.let { put("previous", JsonPrimitive(it.version)) }
        put("signature", buildJsonObject {
            when (signature) {
                PackageSignature.Unsigned -> put("status", JsonPrimitive("unsigned"))
                is PackageSignature.Signed -> { put("status", JsonPrimitive("signed")); put("fingerprint", JsonPrimitive(signature.fingerprint)) }
                is PackageSignature.Invalid -> { put("status", JsonPrimitive("invalid")); put("reason", JsonPrimitive(signature.reason)) }
            }
        })
        fun list(key: String, items: List<String>) = put(key, JsonArray(items.map(::JsonPrimitive)))
        list("adds", adds)
        list("updates", updates)
        list("removes", removes)
        list("conflicts", conflicts)
        list("changes", changes)
        list("errors", errors)
        put("blocked", JsonPrimitive(blocked))
    }
}

/**
 * Eklenti ve şablon paketleri. Paket bir zip'tir (kökte ya da tek bir üst klasörde; Git arşivleri böyledir).
 * İmza: `signature.json` = `{publicKey (X.509, base64), signature (base64)}`, ECDSA P-256 / SHA-256 ile diğer
 * dosyaların özet listesinin (ada göre sıralı `ad\tsha256\n` satırları) imzası. Android 8'de de çalışsın diye Ed25519
 * değil.
 */
object Packages {
    const val MAX_BYTES = 20L * 1024 * 1024
    const val MAX_ENTRIES = 500
    val NAME = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    private val SCRIPT = Regex("^[A-Za-z0-9_-]{1,60}\\.js$")
    private val SAMPLE = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}\\.json$")
    private val json = Json { ignoreUnknownKeys = true }

    /** Zip'i okur ve imzayı doğrular. Biçim hatasında [IllegalArgumentException]. */
    fun read(zip: InputStream): PackageFile {
        val files = unzip(zip)
        val manifestText = files["package.json"] ?: throw IllegalArgumentException(tr("Pakette package.json yok"))
        val manifest = runCatching { json.decodeFromString(PackageManifest.serializer(), manifestText.decodeToString()) }
            .getOrElse { throw IllegalArgumentException(tr("package.json okunamadı: {0}", it.message)) }
        if (!NAME.matches(manifest.name)) throw IllegalArgumentException(tr("Paket adı küçük harf, rakam ve - olmalı: '{0}'", manifest.name))
        if (manifest.version.isBlank() || manifest.title.isBlank()) throw IllegalArgumentException(tr("package.json: sürüm ve başlık gerekli"))
        val config = files["config.json"]?.let { text ->
            runCatching { ServerConfig.fromJson(text.decodeToString()) }.getOrElse { throw IllegalArgumentException(tr("config.json okunamadı: {0}", it.message)) }
        } ?: ServerConfig()
        val scripts = files.filterKeys { it.startsWith("scripts/") }.mapKeys { it.key.removePrefix("scripts/") }.onEach { (n, _) ->
            if (!SCRIPT.matches(n)) throw IllegalArgumentException(tr("Script adı geçersiz: {0}", n))
        }.mapValues { it.value.decodeToString() }
        val samples = files.filterKeys { it.startsWith("sample/") }.map { (path, bytes) ->
            val n = path.removePrefix("sample/")
            if (!SAMPLE.matches(n)) throw IllegalArgumentException(tr("Örnek veri dosyası adı geçersiz: {0}", n))
            n.removeSuffix(".json") to runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject } }
                .getOrElse { throw IllegalArgumentException(tr("{0}: kayıt dizisi bekleniyor", path)) }
        }
        return PackageFile(manifest, config, scripts, samples, verify(files))
    }

    /** Dosyalar (yol → içerik); tek üst klasör atılır. Boyut ve öğe sınırı, yol denetimi. */
    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val raw = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (raw.size >= MAX_ENTRIES) throw IllegalArgumentException(tr("Pakette en çok {0} dosya olabilir", MAX_ENTRIES))
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                if (name.startsWith("/") || name.split('/').any { it == ".." }) throw IllegalArgumentException(tr("Pakette geçersiz yol: {0}", name))
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw IllegalArgumentException(tr("Paket en çok {0} MB olabilir", MAX_BYTES / 1024 / 1024))
                    out.write(buf, 0, n)
                }
                raw[name] = out.toByteArray()
            }
        }
        val top = raw.keys.map { it.substringBefore('/', "") }.distinct()
        val stripped = if ("package.json" !in raw && top.size == 1 && top[0].isNotEmpty()) raw.mapKeys { it.key.substringAfter('/') } else raw
        return stripped
    }

    /** İmzalanan özet listesi: `signature.json` dışındaki dosyalar. */
    private fun digestList(files: Map<String, ByteArray>): ByteArray =
        files.filterKeys { it != "signature.json" }.toSortedMap().entries.joinToString("") { (n, b) -> "$n\t${hex(sha256(b))}\n" }.toByteArray()

    private fun verify(files: Map<String, ByteArray>): PackageSignature {
        val text = files["signature.json"] ?: return PackageSignature.Unsigned
        return try {
            val o = Json.parseToJsonElement(text.decodeToString()).jsonObject
            val keyBytes = Base64.getDecoder().decode((o["publicKey"] as JsonPrimitive).content)
            val sig = Base64.getDecoder().decode((o["signature"] as JsonPrimitive).content)
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
            val ok = Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(digestList(files)) }.verify(sig)
            if (ok) PackageSignature.Signed(fingerprint(keyBytes)) else PackageSignature.Invalid(tr("imza paketin içeriğiyle uyuşmuyor (paket değiştirilmiş)"))
        } catch (e: Exception) {
            PackageSignature.Invalid(tr("imza okunamadı: {0}", e.message))
        }
    }

    /** Yayımcılar için: [files]'ı (package.json, config.json…) imzalar ve `signature.json` içeriğini döndürür. */
    fun sign(files: Map<String, ByteArray>, key: PrivateKey, publicKey: ByteArray): String {
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(digestList(files)) }.sign()
        return buildJsonObject {
            put("algorithm", JsonPrimitive("ECDSA-P256-SHA256"))
            put("publicKey", JsonPrimitive(Base64.getEncoder().encodeToString(publicKey)))
            put("signature", JsonPrimitive(Base64.getEncoder().encodeToString(sig)))
        }.toString()
    }

    fun fingerprint(publicKey: ByteArray): String = sha256(publicKey).take(16).joinToString(":") { "%02x".format(it) }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private class Kind(val label: () -> String, val ofConfig: (ServerConfig) -> List<String>, val ofInstalled: (InstalledPackage) -> List<String>)

    private val KINDS = listOf(
        Kind({ tr("koleksiyon") }, { c -> c.collections.map { it.name } }, { it.collections }),
        Kind({ tr("görünüm") }, { c -> c.views.map { it.name } }, { it.views }),
        Kind({ tr("otomasyon") }, { c -> c.automations.map { it.name } }, { it.automations }),
        Kind({ tr("form") }, { c -> c.forms.map { it.name } }, { it.forms }),
        Kind({ tr("sayfa") }, { c -> c.pages.map { it.name } }, { it.pages }),
        Kind({ tr("pano") }, { c -> c.dashboards.map { it.name } }, { it.dashboards }),
    )

    /** [existingScripts]: script klasöründeki dosya adları. */
    fun plan(config: ServerConfig, pkg: PackageFile, existingScripts: Set<String>): PackagePlan {
        val m = pkg.manifest
        val previous = config.packages.firstOrNull { it.name == m.name }
        val owned = config.packages.filter { it.name != m.name }
        val adds = mutableListOf<String>()
        val updates = mutableListOf<String>()
        val removes = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        for (k in KINDS) {
            val mine = previous?.let(k.ofInstalled).orEmpty().toSet()
            val present = k.ofConfig(config).toSet()
            val incoming = k.ofConfig(pkg.config)
            for (n in incoming) {
                when {
                    n in mine -> updates += "${k.label()} $n"
                    n in present -> conflicts += tr("{0} '{1}' zaten var{2}", k.label(), n, owned.firstOrNull { n in k.ofInstalled(it) }?.let { tr(" ({0} paketinin)", it.name) }.orEmpty())
                    else -> adds += "${k.label()} $n"
                }
            }
            (mine - incoming.toSet()).forEach { removes += "${k.label()} $it" }
        }
        val mineScripts = previous?.scripts.orEmpty().toSet()
        for (s in pkg.scripts.keys) {
            when {
                s in mineScripts -> updates += "script $s"
                s in existingScripts -> conflicts += tr("script '{0}' zaten var", s)
                else -> adds += "script $s"
            }
        }
        (mineScripts - pkg.scripts.keys).forEach { removes += "script $it" }
        val changes = buildList {
            if (pkg.scripts.isNotEmpty() && !config.scripts.enabled) add(tr("Script'ler açılacak"))
            if (needsUsers(pkg) && !config.auth.users.enabled) add(tr("Kullanıcı hesapları açılacak"))
            if (pkg.samples.isNotEmpty()) add(tr("Boş koleksiyonlara örnek kayıtlar eklenecek ({0})", pkg.samples.joinToString { it.first }))
            if (previous != null && previous.version == m.version) add(tr("Aynı sürüm yeniden kurulacak"))
            if (previous != null && compareVersions(m.version, previous.version) < 0) add(tr("Eski sürüme dönülecek ({0} → {1})", previous.version, m.version))
            if (pkg.signature == PackageSignature.Unsigned) add(tr("Paket imzasız: yayımcısı doğrulanamıyor; yalnızca güvendiğiniz kaynaktan kurun"))
            if (previous?.key != null && pkg.signature is PackageSignature.Signed && previous.key != pkg.signature.fingerprint) {
                add(tr("Dikkat: yayımcı anahtarı önceki sürümden farklı ({0})", pkg.signature.fingerprint))
            }
        }
        val errors = buildList {
            if (m.minConfigVersion > ConfigMigrations.CURRENT) add(tr("Paket daha yeni bir DroidServer istiyor (konfigürasyon v{0})", m.minConfigVersion))
            if (conflicts.isEmpty()) addAll(ConfigValidator.validate(apply(config, pkg)))
        }
        return PackagePlan(m, previous, pkg.signature, adds, updates, removes, conflicts, changes, errors)
    }

    /** Kullanıcılara bağlı ilişki veya kullanıcıya göre kural varsa hesaplar gerekir. */
    private fun needsUsers(pkg: PackageFile) = pkg.config.collections.any { c ->
        c.ownerField != null || c.fields.any { it.collection == dev.droidserver.core.config.SystemCollections.USERS }
    }

    /** Paketi konfigürasyona katar (önceki sürümün getirdikleri önce çıkarılır) ve kaydını yazar. */
    fun apply(config: ServerConfig, pkg: PackageFile): ServerConfig {
        val m = pkg.manifest
        val base = config.packages.firstOrNull { it.name == m.name }?.let { strip(config, it) } ?: config
        val p = pkg.config
        var out = base.copy(
            collections = base.collections + p.collections,
            views = base.views + p.views,
            automations = base.automations + p.automations,
            forms = base.forms + p.forms,
            pages = base.pages + p.pages,
            dashboards = base.dashboards + p.dashboards,
            packages = base.packages + InstalledPackage(
                m.name, m.version, m.title, m.publisher, (pkg.signature as? PackageSignature.Signed)?.fingerprint,
                p.collections.map { it.name }, p.views.map { it.name }, p.automations.map { it.name }, p.forms.map { it.name },
                p.pages.map { it.name }, p.dashboards.map { it.name }, pkg.scripts.keys.sorted(),
            ),
        )
        if (pkg.scripts.isNotEmpty() && !out.scripts.enabled) out = out.copy(scripts = out.scripts.copy(enabled = true))
        if (needsUsers(pkg) && !out.auth.users.enabled) out = out.copy(auth = out.auth.copy(users = out.auth.users.copy(enabled = true)))
        return out
    }

    /**
     * Paketi kaldırır: getirdiği tanımlar ve kaydı çıkar. Koleksiyonların tabloları ve verileri veritabanında kalır
     * (şema planı "kullanılmıyor" der); script dosyalarını çağıran siler.
     */
    fun remove(config: ServerConfig, name: String): ServerConfig {
        val p = config.packages.firstOrNull { it.name == name } ?: throw IllegalArgumentException(tr("'{0}' paketi kurulu değil", name))
        return strip(config, p).copy(packages = config.packages.filter { it.name != name })
    }

    private fun strip(c: ServerConfig, p: InstalledPackage) = c.copy(
        collections = c.collections.filter { it.name !in p.collections },
        views = c.views.filter { it.name !in p.views },
        automations = c.automations.filter { it.name !in p.automations },
        forms = c.forms.filter { it.name !in p.forms },
        pages = c.pages.filter { it.name !in p.pages },
        dashboards = c.dashboards.filter { it.name !in p.dashboards },
        packages = c.packages.filter { it.name != p.name },
    )

    /** `1.2.10` > `1.2.9`; sayısal olmayan parçalar metin olarak. */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.', '-')
        val y = b.split('.', '-')
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i) ?: return -1
            val q = y.getOrNull(i) ?: return 1
            val c = (p.toIntOrNull() ?: Int.MAX_VALUE).compareTo(q.toIntOrNull() ?: Int.MAX_VALUE).takeIf { it != 0 } ?: p.compareTo(q)
            if (c != 0) return c
        }
        return 0
    }
}
