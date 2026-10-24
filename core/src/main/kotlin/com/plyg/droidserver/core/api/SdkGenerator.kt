package com.plyg.droidserver.core.api

import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.ConfigMigrations
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.ServerConfig

/**
 * Konfigürasyondan tipli istemci (tek dosya): TypeScript (bağımlılıksız, `fetch`), Kotlin (JVM/Android;
 * `HttpURLConnection` + kotlinx.serialization), Dart (`dart:io`; Dart VM ve Flutter) ve Swift (async/await; Apple ve Linux).
 * Koleksiyon tipleri, CRUD, filtre, dosya yükleme, giriş/yenileme/iki adımlı doğrulama, takımlar, yedek adresler ve canlı
 * abonelik. Yollar ve sistem alanı adları uzman modu ayarlarına göredir; kapalı uçların yöntemi yoktur. Dart ve Swift'in her
 * sunucu için aynı olan kısmı kaynaklarda (`sdk/base.dart`, `sdk/base.swift`).
 */
object SdkGenerator {
    enum class Lang(val key: String, val fileName: String, val mime: String, val title: String) {
        TS("ts", "droidserver.ts", "text/plain", "TypeScript"),
        KOTLIN("kotlin", "DroidServerClient.kt", "text/plain", "Kotlin"),
        DART("dart", "droidserver.dart", "text/plain", "Dart"),
        SWIFT("swift", "DroidServer.swift", "text/plain", "Swift"),
        ;

        companion object {
            fun of(key: String): Lang? = entries.firstOrNull { it.key == key.lowercase() }
        }
    }

    fun generate(config: ServerConfig, lang: Lang, kotlinPackage: String = "droidserver.client"): String = when (lang) {
        Lang.TS -> typescript(config)
        Lang.KOTLIN -> kotlin(config, kotlinPackage)
        Lang.DART -> dart(config)
        Lang.SWIFT -> swift(config)
    }

    /** `todo_items` → `TodoItems`. */
    fun typeName(collection: String): String =
        collection.split('_').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }.ifEmpty { "Record" }

    /** `todo_items` → `todoItems`. */
    private fun memberName(collection: String): String = typeName(collection).replaceFirstChar(Char::lowercaseChar)

    private fun header(config: ServerConfig, comment: String) = """
        |$comment DroidServer istemcisi: "${config.name}" sunucusu için üretildi (konfigürasyon v${ConfigMigrations.CURRENT}).
        |$comment Konfigürasyon değişince yeniden indirin: GET <API>/_sdk/{ts|kotlin|dart|swift} (yönetici) veya uygulama/panel.
        |
    """.trimMargin()

    /** Koleksiyonun API'de görünen alanları: (API adı, alan; sistem alanında null, tipi). */
    private data class Prop(val name: String, val field: FieldConfig?, val type: FieldType, val system: Boolean)

    private fun props(c: CollectionConfig): List<Prop> {
        val n = ApiNames.of(c)
        val sys = listOfNotNull(n.api("id")?.let { Prop(it, null, FieldType.INTEGER, true) })
        val fields = c.fields.map { Prop(it.name, it, it.type, false) }
        val times = listOf("created_at", "updated_at").mapNotNull { k -> n.api(k)?.let { Prop(it, null, FieldType.DATETIME, true) } }
        return sys + fields + times
    }

    private fun writable(c: CollectionConfig) = c.fields.filter { it.computed == null && it.type != FieldType.FILE }

    private fun idName(c: CollectionConfig) = ApiNames.of(c).api("id")

    // ---- TypeScript ----

    private fun tsType(f: FieldConfig?, type: FieldType): String = when (type) {
        FieldType.TEXT, FieldType.DATETIME -> "string"
        FieldType.INTEGER, FieldType.REAL, FieldType.RELATION -> "number"
        FieldType.BOOLEAN -> "boolean"
        FieldType.JSON -> "unknown"
        FieldType.GEO -> "GeoPoint"
        FieldType.FILE -> if (f?.isMultiple == true) "string[]" else "string"
        FieldType.SELECT -> f?.options?.takeIf { it.isNotEmpty() }?.joinToString(" | ") { tsStr(it) } ?: "string"
        FieldType.MULTISELECT -> "Array<" + (f?.options?.takeIf { it.isNotEmpty() }?.joinToString(" | ") { tsStr(it) } ?: "string") + ">"
    }

    private fun tsStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun typescript(config: ServerConfig): String {
        val api = ApiPaths(config)
        val users = config.auth.users
        fun p(e: Endpoint) = api.path(e)?.let(::tsStr) ?: "null"
        return buildString {
            append(header(config, "//"))
            append(TS_BASE)
            for (c in config.collections) {
                val t = typeName(c.name)
                appendLine()
                appendLine("/** `${c.name}` kaydı. */")
                appendLine("export interface $t {")
                for (pr in props(c)) {
                    val optional = !pr.system && (pr.field?.required != true || pr.field.visibility != null)
                    appendLine("  ${tsStr(pr.name)}${if (optional) "?" else ""}: ${tsType(pr.field, pr.type)}${if (optional) " | null" else ""};")
                }
                appendLine("  expand?: Record<string, unknown>;")
                appendLine("}")
                appendLine()
                appendLine("/** `${c.name}` oluşturma/güncelleme gövdesi (dosya ve hesaplanan alanlar hariç; dosyalar `files` ile). */")
                appendLine("export interface ${t}Input {")
                for (f in writable(c)) {
                    val required = f.required && f.default == null
                    appendLine("  ${tsStr(f.name)}${if (required) "" else "?"}: ${tsType(f, f.type)}${if (required) "" else " | null"};")
                }
                appendLine("}")
            }
            appendLine()
            appendLine("export class DroidServer extends DroidServerBase {")
            for (c in config.collections) {
                val t = typeName(c.name)
                val paths = CollectionEndpoint.entries.filter { it in TS_COLLECTION_ENDPOINTS }
                    .joinToString(", ") { e -> "${e.key}: ${api.path(c, e)?.let(::tsStr) ?: "null"}" } +
                    ", sync: ${api.path(c, CollectionEndpoint.SYNC)?.let(::tsStr) ?: "null"}"
                appendLine("  readonly ${memberName(c.name)} = new Collection<$t, ${t}Input>(this, ${tsStr(c.name)}, ${idName(c)?.let(::tsStr) ?: "null"}, { $paths });")
            }
            if (users.enabled) {
                appendLine("  readonly auth = new Auth(this, {")
                appendLine("    signup: ${p(Endpoint.AUTH_SIGNUP)}, login: ${p(Endpoint.AUTH_LOGIN)}, refresh: ${p(Endpoint.AUTH_REFRESH)}, logout: ${p(Endpoint.AUTH_LOGOUT)},")
                appendLine("    me: ${p(Endpoint.AUTH_ME)}, verify2fa: ${p(Endpoint.AUTH_2FA_VERIFY)}, requestLoginCode: ${p(Endpoint.AUTH_REQUEST_LOGIN_CODE)}, loginCode: ${p(Endpoint.AUTH_LOGIN_CODE)},")
                appendLine("    requestSmsCode: ${p(Endpoint.AUTH_REQUEST_SMS_CODE)}, smsCode: ${p(Endpoint.AUTH_SMS_CODE)}, usage: ${p(Endpoint.AUTH_USAGE)},")
                appendLine("    passkeyRegisterOptions: ${p(Endpoint.AUTH_PASSKEY_REGISTER_OPTIONS)}, passkeyRegister: ${p(Endpoint.AUTH_PASSKEY_REGISTER)}, passkeys: ${p(Endpoint.AUTH_PASSKEYS)},")
                appendLine("    passkeyDelete: ${p(Endpoint.AUTH_PASSKEY_DELETE)}, passkeyLoginOptions: ${p(Endpoint.AUTH_PASSKEY_LOGIN_OPTIONS)}, passkeyLogin: ${p(Endpoint.AUTH_PASSKEY_LOGIN)},")
                appendLine("    sessions: ${p(Endpoint.AUTH_SESSIONS)}, closeSessions: ${p(Endpoint.AUTH_SESSIONS_CLOSE)}, closeSession: ${p(Endpoint.AUTH_SESSION_CLOSE)},")
                appendLine("  });")
                if (users.teams.enabled) {
                    appendLine("  readonly teams = new Teams(this, {")
                    appendLine("    list: ${p(Endpoint.TEAMS)}, create: ${p(Endpoint.TEAMS_CREATE)}, get: ${p(Endpoint.TEAM)}, update: ${p(Endpoint.TEAM_UPDATE)}, delete: ${p(Endpoint.TEAM_DELETE)},")
                    appendLine("    addMember: ${p(Endpoint.TEAM_ADD_MEMBER)}, updateMember: ${p(Endpoint.TEAM_UPDATE_MEMBER)}, removeMember: ${p(Endpoint.TEAM_REMOVE_MEMBER)},")
                    appendLine("  });")
                }
                if (config.payments.enabled && config.payments.plans.isNotEmpty()) {
                    appendLine("  readonly payments = new Payments(this, { plans: ${p(Endpoint.PAYMENT_PLANS)}, subscribe: ${p(Endpoint.PAYMENT_SUBSCRIBE)}, subscriptions: ${p(Endpoint.PAYMENT_SUBSCRIPTIONS)}, cancel: ${p(Endpoint.PAYMENT_SUBSCRIPTION_CANCEL)} });")
                }
            }
            appendLine("  protected readonly realtimePath: string | null = ${p(Endpoint.REALTIME)};")
            appendLine("  protected readonly refreshPath: string | null = ${if (users.enabled) p(Endpoint.AUTH_REFRESH) else "null"};")
            appendLine("  protected readonly filesPath: string | null = ${p(Endpoint.FILES)};")
            appendLine()
            appendLine("  /** QR / bağlantı metninden (`droidserver://connect?url=…&token=…` veya düz adres): JWT token, değilse API anahtarı olur. */")
            appendLine("  static fromLink(link: string, options: Options = {}): DroidServer {")
            appendLine("    const l = parseLink(link);")
            appendLine("    const auth = l.token ? (l.token.split(\".\").length === 3 ? { token: l.token } : { apiKey: l.token }) : {};")
            appendLine("    return new DroidServer(l.url, { ...options, ...auth });")
            appendLine("  }")
            appendLine("}")
            append(resource("offline.ts"))
        }
    }

    private val TS_COLLECTION_ENDPOINTS = setOf(
        CollectionEndpoint.LIST, CollectionEndpoint.VIEW, CollectionEndpoint.CREATE, CollectionEndpoint.UPDATE, CollectionEndpoint.DELETE,
    )

    private val TS_BASE = """
        |// Kullanım:
        |//   const ds = new DroidServer("https://telefon.example.com/api", { apiKey: "…" });   // veya { token } / auth.login
        |//   const page = await ds.todos.list({ filter: { done: false, qty: { gt: 0 } }, sort: "-created_at" });
        |//   const todo = await ds.todos.create({ title: "Süt al" });
        |//   const stop = ds.subscribe(["todos"], (e) => console.log(e.action, e.record));
        |
        |export interface Page<T> { items: T[]; page: number; perPage: number; totalItems: number; totalPages: number }
        |
        |/** Konum alanı; filtre: `{ konum: { near: "41.01,28.97,5" } }` (km, yakından uzağa) veya `{ box: "güney,batı,kuzey,doğu" }`. */
        |export interface GeoPoint { lat: number; lng: number; accuracy?: number }
        |
        |export class DroidServerError extends Error {
        |  readonly status: number;
        |  readonly details: Record<string, string>;
        |  constructor(status: number, message: string, details: Record<string, string> = {}) {
        |    super(message);
        |    this.status = status;
        |    this.details = details;
        |  }
        |}
        |
        |type Scalar = string | number | boolean;
        |/** Alan = değer (eşittir) veya işleçler: { gt: 1 }, { in: ["a", "b"] }, { null: true }, { like: "süt" }. */
        |type Elem<V> = V extends Array<infer E> ? E : V;
        |export type Filter<T> = { [K in keyof T]?: Elem<NonNullable<T[K]>> | Partial<Record<"eq" | "ne" | "gt" | "gte" | "lt" | "lte" | "like", Scalar>> | { in: Scalar[] } | { null: boolean } | { near: string } | { box: string } };
        |
        |export interface ListOptions<T> {
        |  page?: number; perPage?: number; sort?: string; q?: string;
        |  fields?: string[]; expand?: string[]; filter?: Filter<T>;
        |}
        |
        |/** Dosya alanları: alan adı → dosya(lar). */
        |export type Files = Record<string, Blob | Blob[]>;
        |
        |export interface Tokens { token: string; refreshToken: string; expiresIn: number; user: Record<string, unknown>; twoFactorSetupRequired?: boolean }
        |export interface TwoFactorChallenge { twoFactorRequired: true; challenge: string; expiresIn: number }
        |export type LoginResult = Tokens | TwoFactorChallenge;
        |
        |export interface RealtimeEvent { action: "create" | "update" | "delete"; collection: string; record: Record<string, unknown> }
        |
        |export interface Options {
        |  apiKey?: string; token?: string; refreshToken?: string;
        |  /** Token'lar değişince (giriş, yenileme, çıkış): saklamak için. */
        |  onTokens?: (tokens: { token: string; refreshToken: string } | null) => void;
        |  fetch?: typeof fetch;
        |  /** Yedek sunucu adresleri (canlı kopya): bağlantı kurulamazsa veya sunucu 503 (salt okunur) dönerse sıradaki denenir. */
        |  fallbackUrls?: string[];
        |  /** Okumaların gideceği kopyalar (sırayla; ulaşılamazsa ana adres). Bir yazmadan sonra `readYourWritesMs` (5000) boyunca okumalar ana sunucudan. */
        |  readUrls?: string[];
        |  readYourWritesMs?: number;
        |}
        |
        |export abstract class DroidServerBase {
        |  apiKey?: string; token?: string; refreshToken?: string;
        |  protected abstract readonly realtimePath: string | null;
        |  protected abstract readonly refreshPath: string | null;
        |  protected abstract readonly filesPath: string | null;
        |  private readonly f: typeof fetch;
        |  private readonly urls: string[];
        |  private current = 0;
        |  private readonly reads: string[];
        |  private nextRead = 0;
        |  private primaryUntil = 0;
        |  private readonly options: Options;
        |
        |  constructor(baseUrl: string, options: Options = {}) {
        |    this.urls = [baseUrl, ...(options.fallbackUrls ?? [])].map((u) => u.replace(/\/+$/, ""));
        |    this.reads = (options.readUrls ?? []).map((u) => u.replace(/\/+$/, ""));
        |    this.options = options;
        |    this.apiKey = options.apiKey; this.token = options.token; this.refreshToken = options.refreshToken;
        |    this.f = options.fetch ?? ((...a) => fetch(...a));
        |  }
        |
        |  setTokens(t: { token: string; refreshToken: string } | null) {
        |    this.token = t?.token; this.refreshToken = t?.refreshToken;
        |    this.options.onTokens?.(t);
        |  }
        |
        |  /** Şu an kullanılan sunucu adresi (yedek adreslerden biri olabilir). */
        |  get baseUrl(): string { return this.urls[this.current]; }
        |
        |  /** Sırayla adresleri dener: bağlantı kurulamazsa veya 503 dönerse (salt okunur kopya) sıradaki; çalışan adres hatırlanır. */
        |  private async send(pathQs: string, init: RequestInit): Promise<Response> {
        |    // Okuma kopyalara (sırayla); yazmadan sonra kısa süre ana sunucudan ("kendi yazdığını oku").
        |    if (init.method === "GET" && this.reads.length && Date.now() >= this.primaryUntil) {
        |      for (let n = 0; n < this.reads.length; n++) {
        |        const u = this.reads[this.nextRead++ % this.reads.length];
        |        try {
        |          const res = await this.f(u + pathQs, init);
        |          if (res.status !== 503) return res;
        |        } catch { /* sıradaki */ }
        |      }
        |    }
        |    if (init.method !== "GET") this.primaryUntil = Date.now() + (this.options.readYourWritesMs ?? 5000);
        |    let failed: Response | undefined, error: unknown;
        |    for (let n = 0; n < this.urls.length; n++) {
        |      const i = (this.current + n) % this.urls.length;
        |      try {
        |        const res = await this.f(this.urls[i] + pathQs, init);
        |        if (res.status !== 503) { this.current = i; return res; }
        |        failed ??= res;
        |      } catch (e) { error ??= e; }
        |    }
        |    if (failed) return failed;
        |    throw error;
        |  }
        |
        |  private headers(): Record<string, string> {
        |    const bearer = this.token ?? this.apiKey;
        |    return bearer ? { Authorization: "Bearer " + bearer } : {};
        |  }
        |
        |  /** Ham istek: JSON gövde, form (dosya) veya hiçbiri; 401'de yenileme token'ı varsa bir kez yenileyip tekrarlar. */
        |  async request<R>(method: string, path: string, init: { query?: Record<string, string>; json?: unknown; form?: FormData } = {}, retry = true): Promise<R> {
        |    const qs = init.query && Object.keys(init.query).length ? "?" + new URLSearchParams(init.query).toString() : "";
        |    const headers = this.headers();
        |    let body: BodyInit | undefined;
        |    if (init.json !== undefined) { headers["Content-Type"] = "application/json"; body = JSON.stringify(init.json); }
        |    else if (init.form) body = init.form;
        |    const res = await this.send(path + qs, { method, headers, body });
        |    if (res.status === 401 && retry && this.token && this.refreshToken && this.refreshPath && path !== this.refreshPath) {
        |      if (await this.tryRefresh()) return this.request<R>(method, path, init, false);
        |    }
        |    if (res.status === 204) return undefined as R;
        |    const text = await res.text();
        |    const data = text ? JSON.parse(text) : undefined;
        |    if (!res.ok) throw new DroidServerError(res.status, data?.message ?? res.statusText, data?.details ?? {});
        |    return data as R;
        |  }
        |
        |  private async tryRefresh(): Promise<boolean> {
        |    try {
        |      const t = await this.request<Tokens>("POST", this.refreshPath!, { json: { refreshToken: this.refreshToken } }, false);
        |      this.setTokens(t);
        |      return true;
        |    } catch { this.setTokens(null); return false; }
        |  }
        |
        |  /** Dosyanın adresi (indirmek veya <img src> için; yetki gerekiyorsa request ile alın). */
        |  fileUrl(collection: string, id: number, name: string, thumb?: string): string | null {
        |    if (!this.filesPath) return null;
        |    const p = this.filesPath.replace("{collection}", encodeURIComponent(collection)).replace("{id}", String(id)).replace("{name}", encodeURIComponent(name));
        |    return this.baseUrl + p + (thumb ? "?thumb=" + encodeURIComponent(thumb) : "");
        |  }
        |
        |  /**
        |   * Canlı abonelik (Server-Sent Events; yetki başlığıyla, fetch akışı üzerinden). Bağlantı koparsa kaldığı olaydan
        |   * devam eder. Dönen işlev aboneliği kapatır.
        |   */
        |  /** [onReady]: abonelik sunucuda kurulunca (her yeniden bağlanmada); o andan sonraki olaylar kaçmaz. */
        |  subscribe(collections: string[], onEvent: (e: RealtimeEvent) => void, onError?: (e: unknown) => void, onReady?: () => void): () => void {
        |    if (!this.realtimePath) throw new DroidServerError(404, "Canlı abonelik kapalı");
        |    const ctl = new AbortController();
        |    let lastId: string | undefined;
        |    const run = async () => {
        |      while (!ctl.signal.aborted) {
        |        try {
        |          const headers = { ...this.headers(), Accept: "text/event-stream", ...(lastId ? { "Last-Event-ID": lastId } : {}) };
        |          const res = await this.f(this.baseUrl + this.realtimePath + "?collections=" + encodeURIComponent(collections.join(",")), { headers, signal: ctl.signal });
        |          if (!res.ok || !res.body) throw new DroidServerError(res.status, "Canlı abonelik açılamadı");
        |          const reader = res.body.getReader();
        |          const dec = new TextDecoder();
        |          let buf = "";
        |          for (;;) {
        |            const { value, done } = await reader.read();
        |            if (done) break;
        |            buf += dec.decode(value, { stream: true });
        |            let i: number;
        |            while ((i = buf.indexOf("\n\n")) >= 0) {
        |              const block = buf.slice(0, i); buf = buf.slice(i + 2);
        |              let event = "message", data = "";
        |              for (const line of block.split("\n")) {
        |                if (line.startsWith("id: ")) lastId = line.slice(4);
        |                else if (line.startsWith("event: ")) event = line.slice(7);
        |                else if (line.startsWith("data: ")) data += line.slice(6);
        |              }
        |              if (event === "message" && data) onEvent(JSON.parse(data));
        |              else if (event === "ready") onReady?.();
        |            }
        |          }
        |        } catch (e) {
        |          if (ctl.signal.aborted) return;
        |          onError?.(e);
        |        }
        |        await new Promise((r) => setTimeout(r, 3000));
        |      }
        |    };
        |    run();
        |    return () => ctl.abort();
        |  }
        |}
        |
        |function query<T>(o: ListOptions<T> = {}): Record<string, string> {
        |  const q: Record<string, string> = {};
        |  if (o.page) q.page = String(o.page);
        |  if (o.perPage) q.perPage = String(o.perPage);
        |  if (o.sort) q.sort = o.sort;
        |  if (o.q) q.q = o.q;
        |  if (o.fields) q.fields = o.fields.join(",");
        |  if (o.expand) q.expand = o.expand.join(",");
        |  for (const [k, v] of Object.entries(o.filter ?? {})) {
        |    if (v === null || v === undefined) continue;
        |    if (typeof v !== "object") { q[k] = String(v); continue; }
        |    for (const [op, x] of Object.entries(v as Record<string, unknown>)) q[k + "[" + op + "]"] = Array.isArray(x) ? x.join(",") : String(x);
        |  }
        |  return q;
        |}
        |
        |function form(data: object, files: Files): FormData {
        |  const fd = new FormData();
        |  for (const [k, v] of Object.entries(data)) {
        |    if (v === undefined) continue;
        |    fd.append(k, v === null ? "" : typeof v === "object" ? JSON.stringify(v) : String(v));
        |  }
        |  for (const [k, v] of Object.entries(files)) for (const b of Array.isArray(v) ? v : [v]) fd.append(k, b, (b as File).name ?? k);
        |  return fd;
        |}
        |
        |// Passkey (WebAuthn): sunucu ikili değerleri base64url verir ve bekler.
        |function fromB64url(s: string): Uint8Array {
        |  const b = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (s.length % 4)) % 4));
        |  return Uint8Array.from(b, (c) => c.charCodeAt(0));
        |}
        |function toB64url(buf: ArrayBuffer): string {
        |  return btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
        |}
        |function passkeyOptions(o: any): any {
        |  const ids = (list: any[] | undefined) => (list ?? []).map((c) => ({ ...c, id: fromB64url(c.id) }));
        |  const out: any = { ...o, challenge: fromB64url(o.challenge) };
        |  if (o.user) out.user = { ...o.user, id: fromB64url(o.user.id) };
        |  if (o.excludeCredentials) out.excludeCredentials = ids(o.excludeCredentials);
        |  if (o.allowCredentials) out.allowCredentials = ids(o.allowCredentials);
        |  return out;
        |}
        |function passkeyCredential(c: Credential | null): Record<string, unknown> {
        |  if (!c) throw new DroidServerError(400, "Passkey işlemi iptal edildi");
        |  const pk = c as PublicKeyCredential;
        |  const r = pk.response as any;
        |  const response: Record<string, string> = {};
        |  for (const k of ["clientDataJSON", "attestationObject", "authenticatorData", "signature", "userHandle"]) if (r[k]) response[k] = toB64url(r[k]);
        |  return { id: pk.id, type: pk.type, response };
        |}
        |
        |type Paths = { list: string | null; view: string | null; create: string | null; update: string | null; delete: string | null; sync: string | null };
        |
        |export class Collection<T, I> {
        |  private readonly ds: DroidServerBase;
        |  readonly name: string;
        |  /** Kimlik alanının API adı (uzman modunda değişmiş olabilir); kaldırıldıysa null. */
        |  readonly idField: string | null;
        |  private readonly paths: Paths;
        |  constructor(ds: DroidServerBase, name: string, idField: string | null, paths: Paths) {
        |    this.ds = ds; this.name = name; this.idField = idField; this.paths = paths;
        |  }
        |
        |  private at(key: keyof Paths, id?: number): string {
        |    const p = this.paths[key];
        |    if (!p) throw new DroidServerError(404, "'" + this.name + "' koleksiyonunda bu uç kapalı: " + key);
        |    return id === undefined ? p : p.replace("{id}", String(id));
        |  }
        |
        |  list(o?: ListOptions<T>): Promise<Page<T>> { return this.ds.request("GET", this.at("list"), { query: query(o) }); }
        |  get(id: number, o: { fields?: string[]; expand?: string[] } = {}): Promise<T> { return this.ds.request("GET", this.at("view", id), { query: query(o) }); }
        |  create(data: I, files?: Files): Promise<T> {
        |    return this.ds.request("POST", this.at("create"), files ? { form: form(data as object, files) } : { json: data });
        |  }
        |  update(id: number, data: Partial<I>, files?: Files): Promise<T> {
        |    return this.ds.request("PATCH", this.at("update", id), files ? { form: form(data as object, files) } : { json: data });
        |  }
        |  delete(id: number): Promise<void> { return this.ds.request("DELETE", this.at("delete", id)); }
        |
        |  /** Çevrimdışı kopya (koleksiyonda eşitleme açık olmalı); depo varsayılan olarak tarayıcıda IndexedDB, Node'da bellek. */
        |  offline(store: OfflineStore = defaultStore(), key = "droidserver-" + this.name): OfflineCollection<T, I> {
        |    return new OfflineCollection<T, I>(this.ds, this.name, this.at("sync"), this.idField ?? "id", store, key);
        |  }
        |}
        |
        |type AuthPaths = Record<"signup" | "login" | "refresh" | "logout" | "me" | "verify2fa" | "requestLoginCode" | "loginCode" | "requestSmsCode" | "smsCode" | "usage"
        |  | "passkeyRegisterOptions" | "passkeyRegister" | "passkeys" | "passkeyDelete" | "passkeyLoginOptions" | "passkeyLogin"
        |  | "sessions" | "closeSessions" | "closeSession", string | null>;
        |
        |export interface Passkey { id: number; name: string; createdAt: string; lastUsedAt: string | null }
        |/** Açık oturum: cihaz (tarayıcı/uygulama), adres, son kullanım; `current` bu istemcinin oturumu. */
        |export interface Session { id: number; device: string | null; ip: string | null; createdAt: string; lastUsedAt: string; current: boolean }
        |
        |export class Auth {
        |  private readonly ds: DroidServerBase;
        |  private readonly paths: AuthPaths;
        |  constructor(ds: DroidServerBase, paths: AuthPaths) { this.ds = ds; this.paths = paths; }
        |  private at(k: keyof AuthPaths): string {
        |    const p = this.paths[k];
        |    if (!p) throw new DroidServerError(404, "Bu hesap ucu kapalı: " + k);
        |    return p;
        |  }
        |  private keep<R extends LoginResult | Record<string, unknown>>(r: R): R {
        |    if ("token" in r && "refreshToken" in r) this.ds.setTokens({ token: String(r.token), refreshToken: String(r.refreshToken) });
        |    return r;
        |  }
        |  async signup(body: Record<string, unknown>): Promise<Tokens | { user: Record<string, unknown>; verificationRequired: true }> {
        |    return this.keep(await this.ds.request("POST", this.at("signup"), { json: body }));
        |  }
        |  /** İki adımlı doğrulama açıksa `TwoFactorChallenge` döner; `verify2fa` ile tamamlayın. */
        |  async login(email: string, password: string): Promise<LoginResult> { return this.keep(await this.ds.request("POST", this.at("login"), { json: { email, password } })); }
        |  async verify2fa(challenge: string, code: string): Promise<Tokens> { return this.keep(await this.ds.request("POST", this.at("verify2fa"), { json: { challenge, code } })); }
        |  requestLoginCode(email: string): Promise<void> { return this.ds.request("POST", this.at("requestLoginCode"), { json: { email } }); }
        |  async loginWithCode(email: string, code: string): Promise<LoginResult> { return this.keep(await this.ds.request("POST", this.at("loginCode"), { json: { email, code } })); }
        |  /** Numara uluslararası biçimde (+905551234567); kod sunucu telefonundan SMS ile gelir. */
        |  requestSmsCode(phone: string): Promise<void> { return this.ds.request("POST", this.at("requestSmsCode"), { json: { phone } }); }
        |  async loginWithSms(phone: string, code: string): Promise<LoginResult> { return this.keep(await this.ds.request("POST", this.at("smsCode"), { json: { phone, code } })); }
        |  async refresh(): Promise<Tokens> { return this.keep(await this.ds.request("POST", this.at("refresh"), { json: { refreshToken: this.ds.refreshToken } }, false)); }
        |  async logout(): Promise<void> {
        |    if (this.ds.refreshToken) await this.ds.request("POST", this.at("logout"), { json: { refreshToken: this.ds.refreshToken } }).catch(() => undefined);
        |    this.ds.setTokens(null);
        |  }
        |  me(): Promise<Record<string, unknown>> { return this.ds.request("GET", this.at("me")); }
        |  usage(): Promise<Record<string, unknown>> { return this.ds.request("GET", this.at("usage")); }
        |  /** Tarayıcıda: giriş yapmış hesaba passkey ekler (cihazın kilidiyle). Sunucu HTTPS ve passkey alan adıyla açılmış olmalı. */
        |  async registerPasskey(name?: string): Promise<Passkey> {
        |    const options = await this.ds.request<Record<string, unknown>>("POST", this.at("passkeyRegisterOptions"));
        |    const cred = await navigator.credentials.create({ publicKey: passkeyOptions(options) });
        |    return this.ds.request("POST", this.at("passkeyRegister"), { json: { name, credential: passkeyCredential(cred) } });
        |  }
        |  /** Tarayıcıda: e-posta yazmadan passkey ile giriş (tarayıcı cihazdaki passkey'i önerir). */
        |  async loginWithPasskey(): Promise<Tokens> {
        |    const options = await this.ds.request<Record<string, unknown>>("POST", this.at("passkeyLoginOptions"));
        |    const cred = await navigator.credentials.get({ publicKey: passkeyOptions(options) });
        |    return this.keep(await this.ds.request<Tokens>("POST", this.at("passkeyLogin"), { json: { credential: passkeyCredential(cred) } }));
        |  }
        |  passkeys(): Promise<{ items: Passkey[] }> { return this.ds.request("GET", this.at("passkeys")); }
        |  deletePasskey(id: number): Promise<void> { return this.ds.request("DELETE", this.at("passkeyDelete").replace("{id}", String(id))); }
        |  sessions(): Promise<{ items: Session[] }> { return this.ds.request("GET", this.at("sessions")); }
        |  /** Oturumu kapatır; o cihazın erişim token'ı da hemen geçersiz olur. */
        |  closeSession(id: number): Promise<void> { return this.ds.request("DELETE", this.at("closeSession").replace("{id}", String(id))); }
        |  /** Bu istemcinin dışındaki tüm oturumları kapatır. */
        |  closeOtherSessions(): Promise<{ closed: number }> { return this.ds.request("DELETE", this.at("closeSessions")); }
        |}
        |
        |export interface TeamMember { user: number; email: string | null; role: "owner" | "member" }
        |export interface Team { id: number; name: string; role?: "owner" | "member"; memberCount: number; members?: TeamMember[] }
        |type TeamPaths = Record<"list" | "create" | "get" | "update" | "delete" | "addMember" | "updateMember" | "removeMember", string | null>;
        |
        |/** Takımlar (`team` kuralı): kullanıcının takımları; üye ekleme, rol ve silme takım sahibinindir. */
        |export class Teams {
        |  private readonly ds: DroidServerBase;
        |  private readonly paths: TeamPaths;
        |  constructor(ds: DroidServerBase, paths: TeamPaths) { this.ds = ds; this.paths = paths; }
        |  private at(k: keyof TeamPaths, id?: number, user?: number): string {
        |    const p = this.paths[k];
        |    if (!p) throw new DroidServerError(404, "Bu takım ucu kapalı: " + k);
        |    return p.replace("{id}", String(id)).replace("{user}", String(user));
        |  }
        |  list(): Promise<{ items: Team[] }> { return this.ds.request("GET", this.at("list")); }
        |  create(name: string): Promise<Team> { return this.ds.request("POST", this.at("create"), { json: { name } }); }
        |  get(id: number): Promise<Team> { return this.ds.request("GET", this.at("get", id)); }
        |  rename(id: number, name: string): Promise<Team> { return this.ds.request("PATCH", this.at("update", id), { json: { name } }); }
        |  delete(id: number): Promise<void> { return this.ds.request("DELETE", this.at("delete", id)); }
        |  /** `email` veya `user` (kimlik); rol varsayılan `member`. */
        |  addMember(id: number, member: { email?: string; user?: number; role?: "owner" | "member" }): Promise<Team> {
        |    return this.ds.request("POST", this.at("addMember", id), { json: member });
        |  }
        |  setRole(id: number, user: number, role: "owner" | "member"): Promise<Team> { return this.ds.request("PATCH", this.at("updateMember", id, user), { json: { role } }); }
        |  /** Sahip üyeyi çıkarır; kullanıcı kendi kimliğiyle çağırınca takımdan ayrılır. */
        |  removeMember(id: number, user: number): Promise<void> { return this.ds.request("DELETE", this.at("removeMember", id, user)); }
        |}
        |
        |export interface PaymentPlan { name: string; title: string; provider: string; role: string }
        |export interface Subscription { id: number; plan: string; provider: string; status: "active" | "past_due" | "canceled"; period_end: string | null; canceling?: boolean }
        |type PaymentPaths = Record<"plans" | "subscribe" | "subscriptions" | "cancel", string | null>;
        |
        |/** Abonelikler: planlar, ödeme sayfası (rol ödeme bildirimi gelince verilir) ve kullanıcının abonelikleri. */
        |export class Payments {
        |  private readonly ds: DroidServerBase;
        |  private readonly paths: PaymentPaths;
        |  constructor(ds: DroidServerBase, paths: PaymentPaths) { this.ds = ds; this.paths = paths; }
        |  private at(k: keyof PaymentPaths): string {
        |    const p = this.paths[k];
        |    if (!p) throw new DroidServerError(404, "Bu ödeme ucu kapalı: " + k);
        |    return p;
        |  }
        |  plans(): Promise<{ items: PaymentPlan[] }> { return this.ds.request("GET", this.at("plans")); }
        |  /** Ödeme sayfasının adresi; kullanıcıyı oraya yönlendirin. */
        |  subscribe(plan: string): Promise<{ plan: string; url: string }> { return this.ds.request("POST", this.at("subscribe"), { json: { plan } }); }
        |  subscriptions(): Promise<{ items: Subscription[] }> { return this.ds.request("GET", this.at("subscriptions")); }
        |  /** Aboneliği dönem sonunda bitirir; rol dönem bitene kadar kalır. */
        |  cancel(id: number): Promise<Subscription> { return this.ds.request("POST", this.at("cancel").replace("{id}", String(id))); }
        |}
        |
        |export interface ConnectionLink { url: string; name?: string; token?: string; expiresAt?: Date }
        |
        |/** Telefondaki "Bağlan (QR)" ekranının metni: `droidserver://connect?url=…&name=…&token=…&exp=…` veya düz `http(s)://` adresi. */
        |export function parseLink(link: string): ConnectionLink {
        |  const t = link.trim();
        |  if (/^https?:\/\//.test(t)) return { url: t.replace(/\/+$/, "") };
        |  if (!t.startsWith("droidserver://connect?")) throw new Error("DroidServer bağlantısı değil");
        |  const q = new URLSearchParams(t.slice(t.indexOf("?") + 1));
        |  const url = q.get("url");
        |  if (!url || !/^https?:\/\//.test(url)) throw new Error("Bağlantıda adres yok");
        |  const exp = q.get("exp");
        |  return { url, name: q.get("name") ?? undefined, token: q.get("token") ?? undefined, expiresAt: exp ? new Date(Number(exp) * 1000) : undefined };
        |}
        |
    """.trimMargin()

    // ---- Kotlin ----

    private val KOTLIN_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object",
        "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    private fun ktName(s: String) = if (s in KOTLIN_KEYWORDS) "`$s`" else s

    private fun ktType(f: FieldConfig?, type: FieldType): String = when (type) {
        FieldType.TEXT, FieldType.DATETIME, FieldType.SELECT -> "String"
        FieldType.INTEGER, FieldType.RELATION -> "Long"
        FieldType.REAL -> "Double"
        FieldType.BOOLEAN -> "Boolean"
        FieldType.JSON -> "JsonElement"
        FieldType.GEO -> "GeoPoint"
        FieldType.FILE -> if (f?.isMultiple == true) "List<String>" else "String"
        FieldType.MULTISELECT -> "List<String>"
    }

    private fun ktStr(s: String?) = if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

    private fun kotlin(config: ServerConfig, pkg: String): String {
        val api = ApiPaths(config)
        val users = config.auth.users
        fun p(e: Endpoint) = ktStr(api.path(e))
        return buildString {
            append(header(config, "//"))
            appendLine("// Gerekenler: kotlinx-serialization-json (ve Kotlin serialization eklentisi). JVM ve Android'de çalışır.")
            appendLine("@file:Suppress(\"unused\", \"MemberVisibilityCanBePrivate\", \"PropertyName\")")
            appendLine()
            appendLine("package $pkg")
            appendLine()
            append(KT_IMPORTS)
            for (c in config.collections) {
                val t = typeName(c.name)
                appendLine()
                appendLine("/** `${c.name}` kaydı. Gizli alanlar yetkiye göre gelmeyebilir; bu yüzden alanlar boş olabilir. */")
                appendLine("@Serializable")
                appendLine("data class $t(")
                for (pr in props(c)) {
                    val sysId = pr.system && pr.type == FieldType.INTEGER
                    appendLine("    val ${ktName(pr.name)}: ${ktType(pr.field, pr.type)}${if (sysId) "" else "? = null"},")
                }
                appendLine("    val expand: JsonObject? = null,")
                appendLine(")")
                appendLine()
                appendLine("/** `${c.name}` oluşturma/güncelleme gövdesi: verilmeyen (null) alanlar gönderilmez. Boşaltmak için `RecordCollection.updateRaw`. */")
                appendLine("@Serializable")
                appendLine("data class ${t}Input(")
                for (f in writable(c)) appendLine("    val ${ktName(f.name)}: ${ktType(f, f.type)}? = null,")
                appendLine(")")
            }
            appendLine()
            appendLine("/**")
            appendLine(" * ${config.name} istemcisi. [baseUrl]: API adresi, örn. `https://telefon.example.com/api`; [fallbackUrls]: canlı kopyaların")
            appendLine(" * adresleri (ana sunucuya ulaşılamazsa veya 503 dönerse sıradaki denenir). [readUrls]: okumaların gideceği kopyalar")
            appendLine(" * (sırayla; ulaşılamazsa ana adres); bir yazmadan sonra [readYourWritesMs] boyunca okumalar ana sunucudan.")
            appendLine(" */")
            appendLine("class DroidServerClient(")
            appendLine("    baseUrl: String, apiKey: String? = null, token: String? = null, refreshToken: String? = null, fallbackUrls: List<String> = emptyList(),")
            appendLine("    readUrls: List<String> = emptyList(), readYourWritesMs: Long = 5_000,")
            appendLine(") : DroidServerBase(")
            appendLine("    baseUrl, apiKey, token, refreshToken, realtimePath = ${p(Endpoint.REALTIME)}, refreshPath = ${if (users.enabled) p(Endpoint.AUTH_REFRESH) else "null"},")
            appendLine("    filesPath = ${p(Endpoint.FILES)}, fallbackUrls = fallbackUrls, readUrls = readUrls, readYourWritesMs = readYourWritesMs,")
            appendLine(") {")
            for (c in config.collections) {
                val t = typeName(c.name)
                val paths = TS_COLLECTION_ENDPOINTS.joinToString(", ") { e -> "${ktStr(api.path(c, e))}" } +
                    ", ${ktStr(api.path(c, CollectionEndpoint.SYNC))}, ${ktStr(idName(c) ?: "id")}"
                appendLine("    val ${memberName(c.name)} = RecordCollection(this, ${ktStr(c.name)}, CollectionPaths($paths), $t.serializer(), ${t}Input.serializer())")
            }
            if (users.enabled) {
                appendLine("    val auth = Auth(")
                appendLine("        this, signup = ${p(Endpoint.AUTH_SIGNUP)}, login = ${p(Endpoint.AUTH_LOGIN)}, logout = ${p(Endpoint.AUTH_LOGOUT)}, me = ${p(Endpoint.AUTH_ME)},")
                appendLine("        verify2fa = ${p(Endpoint.AUTH_2FA_VERIFY)}, requestLoginCode = ${p(Endpoint.AUTH_REQUEST_LOGIN_CODE)}, loginCode = ${p(Endpoint.AUTH_LOGIN_CODE)},")
                appendLine("        requestSmsCode = ${p(Endpoint.AUTH_REQUEST_SMS_CODE)}, smsCode = ${p(Endpoint.AUTH_SMS_CODE)}, usage = ${p(Endpoint.AUTH_USAGE)},")
                appendLine("    )")
                if (users.teams.enabled) {
                    appendLine("    val teams = Teams(")
                    appendLine("        this, list = ${p(Endpoint.TEAMS)}, create = ${p(Endpoint.TEAMS_CREATE)}, get = ${p(Endpoint.TEAM)}, update = ${p(Endpoint.TEAM_UPDATE)}, delete = ${p(Endpoint.TEAM_DELETE)},")
                    appendLine("        addMember = ${p(Endpoint.TEAM_ADD_MEMBER)}, updateMember = ${p(Endpoint.TEAM_UPDATE_MEMBER)}, removeMember = ${p(Endpoint.TEAM_REMOVE_MEMBER)},")
                    appendLine("    )")
                }
                if (config.payments.enabled && config.payments.plans.isNotEmpty()) {
                    appendLine("    val payments = Payments(this, plans = ${p(Endpoint.PAYMENT_PLANS)}, subscribe = ${p(Endpoint.PAYMENT_SUBSCRIBE)}, subscriptions = ${p(Endpoint.PAYMENT_SUBSCRIPTIONS)}, cancel = ${p(Endpoint.PAYMENT_SUBSCRIPTION_CANCEL)})")
                }
            }
            appendLine()
            appendLine("    companion object {")
            appendLine("        /** QR / bağlantı metninden (`droidserver://connect?url=…&token=…` veya düz adres): JWT token, değilse API anahtarı olur. */")
            appendLine("        fun fromLink(link: String): DroidServerClient {")
            appendLine("            val l = ConnectionLink.parse(link)")
            appendLine("            val jwt = l.token?.count { it == '.' } == 2")
            appendLine("            return DroidServerClient(l.url, apiKey = l.token?.takeIf { !jwt }, token = l.token?.takeIf { jwt })")
            appendLine("        }")
            appendLine()
            appendLine("        /** Yerel ağdaki DroidServer'lar (mDNS). Android'de `WifiManager.MulticastLock` gerekebilir. */")
            appendLine("        fun discover(timeoutMs: Long = 2_000): List<FoundServer> = LocalDiscovery.find(timeoutMs)")
            appendLine("    }")
            appendLine("}")
            append(KT_BASE)
            append(resource("offline.kt"))
        }
    }

    private val KT_IMPORTS = """
        |import kotlinx.serialization.KSerializer
        |import kotlinx.serialization.Serializable
        |import kotlinx.serialization.json.Json
        |import kotlinx.serialization.json.JsonArray
        |import kotlinx.serialization.json.JsonElement
        |import kotlinx.serialization.json.JsonNull
        |import kotlinx.serialization.json.JsonObject
        |import kotlinx.serialization.json.JsonPrimitive
        |import kotlinx.serialization.json.buildJsonObject
        |import kotlinx.serialization.json.jsonArray
        |import kotlinx.serialization.json.jsonObject
        |import kotlinx.serialization.json.jsonPrimitive
        |import java.io.Closeable
        |import java.io.File
        |import java.io.IOException
        |import java.net.DatagramPacket
        |import java.net.DatagramSocket
        |import java.net.HttpURLConnection
        |import java.net.InetAddress
        |import java.net.InetSocketAddress
        |import java.net.ProtocolException
        |import java.net.SocketTimeoutException
        |import java.net.URI
        |import java.net.URLDecoder
        |import java.net.URLEncoder
        |import java.util.concurrent.atomic.AtomicBoolean
        |import java.util.concurrent.atomic.AtomicReference
        |
    """.trimMargin()

    private val KT_BASE = """
        |
        |@Serializable
        |data class Page<T>(val items: List<T>, val page: Int, val perPage: Int, val totalItems: Long, val totalPages: Long)
        |
        |class DroidServerException(val status: Int, message: String, val details: Map<String, String> = emptyMap()) : IOException(message)
        |
        |/** Konum alanı; filtre: `"konum[near]" to "41.01,28.97,5"` (km, yakından uzağa) veya `"konum[box]" to "güney,batı,kuzey,doğu"`. */
        |@Serializable
        |data class GeoPoint(val lat: Double, val lng: Double, val accuracy: Double? = null)
        |
        |/** Giriş sonucu: [tokens] ya da iki adımlı doğrulama açıksa [challenge] (`Auth.verify2fa` ile tamamlayın). */
        |data class LoginResult(val tokens: JsonObject?, val challenge: String?)
        |
        |data class RealtimeEvent(val action: String, val collection: String, val record: JsonObject)
        |
        |/** Koleksiyonun uç yolları; [sync] eşitleme kapalıysa null, [idField] kimlik alanının API adı. */
        |data class CollectionPaths(
        |    val list: String?,
        |    val view: String?,
        |    val create: String?,
        |    val update: String?,
        |    val delete: String?,
        |    val sync: String? = null,
        |    val idField: String = "id",
        |)
        |
        |/** Liste seçenekleri. [filter]: `"done" to "false"`, `"qty[gt]" to "0"`, `"tags" to "yeni"` (sorgu parametresi biçiminde). */
        |data class ListOptions(
        |    val page: Int? = null,
        |    val perPage: Int? = null,
        |    val sort: String? = null,
        |    val q: String? = null,
        |    val fields: List<String>? = null,
        |    val expand: List<String>? = null,
        |    val filter: Map<String, String> = emptyMap(),
        |) {
        |    internal fun query(): Map<String, String> = buildMap {
        |        page?.let { put("page", it.toString()) }
        |        perPage?.let { put("perPage", it.toString()) }
        |        sort?.let { put("sort", it) }
        |        q?.let { put("q", it) }
        |        fields?.let { put("fields", it.joinToString(",")) }
        |        expand?.let { put("expand", it.joinToString(",")) }
        |        putAll(filter)
        |    }
        |}
        |
        |val DroidServerJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
        |
        |/** HTTP katmanı: yetki başlığı, JSON/çok parçalı gövde, hata, 401'de bir kez token yenileme. Çağrılar bloklayıcıdır. */
        |abstract class DroidServerBase(
        |    baseUrl: String,
        |    @Volatile var apiKey: String?,
        |    @Volatile var token: String?,
        |    @Volatile var refreshToken: String?,
        |    private val realtimePath: String?,
        |    private val refreshPath: String?,
        |    private val filesPath: String?,
        |    fallbackUrls: List<String> = emptyList(),
        |    readUrls: List<String> = emptyList(),
        |    private val readYourWritesMs: Long = 5_000,
        |) {
        |    private val urls = (listOf(baseUrl) + fallbackUrls).map { it.trimEnd('/') }
        |    @Volatile private var current = 0
        |    private val reads = readUrls.map { it.trimEnd('/') }
        |    @Volatile private var nextRead = 0
        |    @Volatile private var primaryUntil = 0L
        |
        |    /** Şu an kullanılan sunucu adresi (yedek adreslerden biri olabilir). */
        |    val baseUrl: String get() = urls[current]
        |
        |    /** Token'lar değişince (giriş, yenileme, çıkış): saklamak için. */
        |    var onTokens: ((token: String?, refreshToken: String?) -> Unit)? = null
        |
        |    fun setTokens(token: String?, refreshToken: String?) {
        |        this.token = token
        |        this.refreshToken = refreshToken
        |        onTokens?.invoke(token, refreshToken)
        |    }
        |
        |    /**
        |     * Ham istek; [json] veya [multipart] gövde. Yanıt JSON'u (204'te null). Yedek adres varsa: bağlantı kurulamazsa veya
        |     * 503 dönerse (salt okunur kopya) sıradaki denenir, çalışan adres hatırlanır.
        |     */
        |    fun request(method: String, path: String, query: Map<String, String> = emptyMap(), json: JsonElement? = null, multipart: Multipart? = null, retry: Boolean = true): JsonElement? {
        |        // Okuma kopyalara (sırayla); yazmadan sonra kısa süre ana sunucudan ("kendi yazdığını oku").
        |        if (method == "GET" && reads.isNotEmpty() && System.currentTimeMillis() >= primaryUntil) {
        |            repeat(reads.size) {
        |                val i = nextRead++.mod(reads.size)
        |                try {
        |                    return requestAt(reads[i], method, path, query, json, multipart, retry)
        |                } catch (e: DroidServerException) {
        |                    if (e.status != 503) throw e
        |                } catch (_: IOException) {
        |                }
        |            }
        |        }
        |        if (method != "GET") primaryUntil = System.currentTimeMillis() + readYourWritesMs
        |        var failed: DroidServerException? = null
        |        var error: IOException? = null
        |        for (n in urls.indices) {
        |            val i = (current + n) % urls.size
        |            try {
        |                return requestAt(urls[i], method, path, query, json, multipart, retry).also { current = i }
        |            } catch (e: DroidServerException) {
        |                if (e.status != 503) {
        |                    current = i
        |                    throw e
        |                }
        |                if (failed == null) failed = e
        |            } catch (e: IOException) {
        |                if (error == null) error = e
        |            }
        |        }
        |        throw failed ?: error!!
        |    }
        |
        |    private fun requestAt(base: String, method: String, path: String, query: Map<String, String>, json: JsonElement?, multipart: Multipart?, retry: Boolean): JsonElement? {
        |        val qs = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        |        val c = URI(base + path + qs).toURL().openConnection() as HttpURLConnection
        |        try {
        |            try {
        |                c.requestMethod = method
        |            } catch (_: ProtocolException) {
        |                // JVM'nin HttpURLConnection'ı PATCH yapamaz; sunucu X-HTTP-Method-Override'ı tanır.
        |                c.requestMethod = "POST"
        |                c.setRequestProperty("X-HTTP-Method-Override", method)
        |            }
        |            (token ?: apiKey)?.let { c.setRequestProperty("Authorization", "Bearer " + it) }
        |            c.setRequestProperty("Accept", "application/json")
        |            when {
        |                json != null -> {
        |                    c.doOutput = true
        |                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        |                    c.outputStream.use { it.write(json.toString().toByteArray()) }
        |                }
        |                multipart != null -> {
        |                    c.doOutput = true
        |                    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + multipart.boundary)
        |                    c.outputStream.use(multipart::writeTo)
        |                }
        |            }
        |            val status = c.responseCode
        |            if (status == 401 && retry && token != null && refreshToken != null && refreshPath != null && path != refreshPath && tryRefresh()) {
        |                return requestAt(base, method, path, query, json, multipart, retry = false)
        |            }
        |            val body = (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        |            val data = body.takeIf { it.isNotBlank() }?.let { runCatching { DroidServerJson.parseToJsonElement(it) }.getOrNull() }
        |            if (status >= 400) {
        |                val o = data as? JsonObject
        |                val details = (o?.get("details") as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() }.orEmpty()
        |                throw DroidServerException(status, (o?.get("message") as? JsonPrimitive)?.content ?: "HTTP " + status, details)
        |            }
        |            return data
        |        } finally {
        |            c.disconnect()
        |        }
        |    }
        |
        |    private fun tryRefresh(): Boolean = try {
        |        val t = request("POST", refreshPath!!, json = buildJsonObject { put("refreshToken", JsonPrimitive(refreshToken)) }, retry = false)!!.jsonObject
        |        setTokens(t["token"]!!.jsonPrimitive.content, t["refreshToken"]!!.jsonPrimitive.content)
        |        true
        |    } catch (_: IOException) {
        |        setTokens(null, null)
        |        false
        |    }
        |
        |    /** Dosyanın adresi (`thumb`: örn. "100x100"). */
        |    fun fileUrl(collection: String, id: Long, name: String, thumb: String? = null): String? = filesPath?.let { p ->
        |        baseUrl + p.replace("{collection}", enc(collection)).replace("{id}", id.toString()).replace("{name}", enc(name)) +
        |            (thumb?.let { "?thumb=" + enc(it) } ?: "")
        |    }
        |
        |    /**
        |     * Canlı abonelik (Server-Sent Events) arka plan iş parçacığında; bağlantı koparsa kaldığı olaydan devam eder.
        |     * [Closeable.close] aboneliği kapatır.
        |     */
        |    /** [onReady]: abonelik sunucuda kurulunca (her yeniden bağlanmada); o andan sonraki olaylar kaçmaz. */
        |    fun subscribe(collections: List<String>, onError: (Exception) -> Unit = {}, onReady: () -> Unit = {}, onEvent: (RealtimeEvent) -> Unit): Closeable {
        |        val path = realtimePath ?: throw DroidServerException(404, "Canlı abonelik kapalı")
        |        val open = AtomicBoolean(true)
        |        val conn = AtomicReference<HttpURLConnection?>()
        |        val thread = Thread {
        |            var lastId: String? = null
        |            while (open.get()) {
        |                try {
        |                    val c = URI(baseUrl + path + "?collections=" + enc(collections.joinToString(","))).toURL().openConnection() as HttpURLConnection
        |                    conn.set(c)
        |                    (token ?: apiKey)?.let { c.setRequestProperty("Authorization", "Bearer " + it) }
        |                    c.setRequestProperty("Accept", "text/event-stream")
        |                    lastId?.let { c.setRequestProperty("Last-Event-ID", it) }
        |                    if (c.responseCode != 200) throw DroidServerException(c.responseCode, "Canlı abonelik açılamadı")
        |                    c.inputStream.bufferedReader().use { r ->
        |                        var event = "message"
        |                        val data = StringBuilder()
        |                        while (open.get()) {
        |                            val line = r.readLine() ?: break
        |                            when {
        |                                line.isEmpty() -> {
        |                                    if (event == "message" && data.isNotEmpty()) {
        |                                        val o = DroidServerJson.parseToJsonElement(data.toString()).jsonObject
        |                                        onEvent(RealtimeEvent(o["action"]!!.jsonPrimitive.content, o["collection"]!!.jsonPrimitive.content, o["record"]!!.jsonObject))
        |                                    } else if (event == "ready") {
        |                                        onReady()
        |                                    }
        |                                    event = "message"
        |                                    data.setLength(0)
        |                                }
        |                                line.startsWith("id: ") -> lastId = line.removePrefix("id: ")
        |                                line.startsWith("event: ") -> event = line.removePrefix("event: ")
        |                                line.startsWith("data: ") -> data.append(line.removePrefix("data: "))
        |                            }
        |                        }
        |                    }
        |                } catch (e: Exception) {
        |                    if (open.get()) onError(e)
        |                }
        |                if (open.get()) runCatching { Thread.sleep(3000) }
        |            }
        |        }
        |        thread.isDaemon = true
        |        thread.start()
        |        return Closeable {
        |            open.set(false)
        |            conn.get()?.disconnect()
        |        }
        |    }
        |
        |    internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        |}
        |
        |/** Çok parçalı gövde: alanlar ve dosyalar. */
        |class Multipart(private val fields: Map<String, String>, private val files: Map<String, List<File>>) {
        |    val boundary = "droidserver" + System.nanoTime()
        |
        |    fun writeTo(out: java.io.OutputStream) {
        |        fun line(s: String) = out.write((s + "\r\n").toByteArray())
        |        for ((k, v) in fields) {
        |            line("--" + boundary); line("Content-Disposition: form-data; name=\"" + k + "\""); line(""); line(v)
        |        }
        |        for ((k, list) in files) for (f in list) {
        |            line("--" + boundary)
        |            line("Content-Disposition: form-data; name=\"" + k + "\"; filename=\"" + f.name.replace("\"", "") + "\"")
        |            line("Content-Type: application/octet-stream"); line("")
        |            f.inputStream().use { it.copyTo(out) }
        |            line("")
        |        }
        |        line("--" + boundary + "--")
        |    }
        |}
        |
        |class RecordCollection<T, I>(
        |    private val client: DroidServerBase,
        |    val name: String,
        |    private val paths: CollectionPaths,
        |    private val serializer: KSerializer<T>,
        |    private val input: KSerializer<I>,
        |) {
        |    private fun at(p: String?, key: String, id: Long? = null): String =
        |        (p ?: throw DroidServerException(404, "'" + name + "' koleksiyonunda bu uç kapalı: " + key)).let { if (id == null) it else it.replace("{id}", id.toString()) }
        |
        |    private fun record(e: JsonElement?): T = DroidServerJson.decodeFromJsonElement(serializer, e!!)
        |
        |    private fun body(data: I, files: Map<String, List<File>>): Pair<JsonElement?, Multipart?> {
        |        val json = DroidServerJson.encodeToJsonElement(input, data)
        |        if (files.isEmpty()) return json to null
        |        val fields = json.jsonObject.mapValues { (_, v) -> if (v is JsonPrimitive && v.isString) v.content else if (v is JsonNull) "" else v.toString() }
        |        return null to Multipart(fields, files)
        |    }
        |
        |    fun list(options: ListOptions = ListOptions()): Page<T> =
        |        DroidServerJson.decodeFromJsonElement(Page.serializer(serializer), client.request("GET", at(paths.list, "list"), options.query())!!)
        |
        |    fun get(id: Long, expand: List<String>? = null): T =
        |        record(client.request("GET", at(paths.view, "view", id), expand?.let { mapOf("expand" to it.joinToString(",")) }.orEmpty()))
        |
        |    /** [files]: dosya alanı → dosyalar. */
        |    fun create(data: I, files: Map<String, List<File>> = emptyMap()): T {
        |        val (json, multipart) = body(data, files)
        |        return record(client.request("POST", at(paths.create, "create"), json = json, multipart = multipart))
        |    }
        |
        |    fun update(id: Long, data: I, files: Map<String, List<File>> = emptyMap()): T {
        |        val (json, multipart) = body(data, files)
        |        return record(client.request("PATCH", at(paths.update, "update", id), json = json, multipart = multipart))
        |    }
        |
        |    /** Ham güncelleme (örn. alanı boşaltmak: `buildJsonObject { put("note", JsonNull) }`). */
        |    fun updateRaw(id: Long, body: JsonObject): T = record(client.request("PATCH", at(paths.update, "update", id), json = body))
        |
        |    fun delete(id: Long) {
        |        client.request("DELETE", at(paths.delete, "delete", id))
        |    }
        |
        |    /** Çevrimdışı kopya (koleksiyonda eşitleme açık olmalı). [key]: depodaki anahtar; sunucu başına farklı verin. */
        |    fun offline(store: OfflineStore, key: String = "droidserver-" + name): OfflineCollection<T, I> =
        |        OfflineCollection(client, name, at(paths.sync, "sync"), paths.idField, serializer, input, store, key)
        |}
        |
        |/** Takımlar (`team` kuralı): kullanıcının takımları; üye ekleme, rol ve silme takım sahibinindir. Yanıtlar JSON. */
        |class Teams(
        |    private val client: DroidServerBase,
        |    private val list: String?,
        |    private val create: String?,
        |    private val get: String?,
        |    private val update: String?,
        |    private val delete: String?,
        |    private val addMember: String?,
        |    private val updateMember: String?,
        |    private val removeMember: String?,
        |) {
        |    private fun at(p: String?, key: String, id: Long? = null, user: Long? = null) =
        |        (p ?: throw DroidServerException(404, "Bu takım ucu kapalı: " + key)).replace("{id}", id.toString()).replace("{user}", user.toString())
        |
        |    /** `{items: [{id, name, role, memberCount}]}` */
        |    fun list(): JsonObject = client.request("GET", at(list, "list"))!!.jsonObject
        |
        |    fun create(name: String): JsonObject = client.request("POST", at(create, "create"), json = buildJsonObject { put("name", JsonPrimitive(name)) })!!.jsonObject
        |
        |    /** Takım ve üyeleri: `{id, name, role, memberCount, members: [{user, email, role}]}` */
        |    fun get(id: Long): JsonObject = client.request("GET", at(get, "get", id))!!.jsonObject
        |
        |    fun rename(id: Long, name: String): JsonObject = client.request("PATCH", at(update, "update", id), json = buildJsonObject { put("name", JsonPrimitive(name)) })!!.jsonObject
        |
        |    fun delete(id: Long) {
        |        client.request("DELETE", at(delete, "delete", id))
        |    }
        |
        |    /** [email] veya [user] (kimlik); [role] `owner` veya `member`. */
        |    fun addMember(id: Long, email: String? = null, user: Long? = null, role: String = "member"): JsonObject = client.request(
        |        "POST", at(addMember, "addMember", id),
        |        json = buildJsonObject {
        |            email?.let { put("email", JsonPrimitive(it)) }
        |            user?.let { put("user", JsonPrimitive(it)) }
        |            put("role", JsonPrimitive(role))
        |        },
        |    )!!.jsonObject
        |
        |    fun setRole(id: Long, user: Long, role: String): JsonObject =
        |        client.request("PATCH", at(updateMember, "updateMember", id, user), json = buildJsonObject { put("role", JsonPrimitive(role)) })!!.jsonObject
        |
        |    /** Sahip üyeyi çıkarır; kullanıcı kendi kimliğiyle çağırınca takımdan ayrılır. */
        |    fun removeMember(id: Long, user: Long) {
        |        client.request("DELETE", at(removeMember, "removeMember", id, user))
        |    }
        |}
        |
        |/** Abonelikler: planlar, ödeme sayfası (rol ödeme bildirimi gelince verilir) ve kullanıcının abonelikleri. Yanıtlar JSON. */
        |class Payments(
        |    private val client: DroidServerBase,
        |    private val plans: String?,
        |    private val subscribe: String?,
        |    private val subscriptions: String?,
        |    private val cancel: String?,
        |) {
        |    private fun at(p: String?, key: String) = p ?: throw DroidServerException(404, "Bu ödeme ucu kapalı: " + key)
        |
        |    /** `{items: [{name, title, provider, role}]}` */
        |    fun plans(): JsonObject = client.request("GET", at(plans, "plans"))!!.jsonObject
        |
        |    /** `{plan, url}`: kullanıcıyı ödeme sayfasına ([url]) yönlendirin. */
        |    fun subscribe(plan: String): JsonObject = client.request("POST", at(subscribe, "subscribe"), json = buildJsonObject { put("plan", JsonPrimitive(plan)) })!!.jsonObject
        |
        |    /** `{items: [{id, plan, provider, status, period_end, canceling}]}` */
        |    fun subscriptions(): JsonObject = client.request("GET", at(subscriptions, "subscriptions"))!!.jsonObject
        |
        |    /** Aboneliği dönem sonunda bitirir; rol dönem bitene kadar kalır. */
        |    fun cancel(id: Long): JsonObject = client.request("POST", at(cancel, "cancel").replace("{id}", id.toString()))!!.jsonObject
        |}
        |
        |class Auth(
        |    private val client: DroidServerBase,
        |    private val signup: String?,
        |    private val login: String?,
        |    private val logout: String?,
        |    private val me: String?,
        |    private val verify2fa: String?,
        |    private val requestLoginCode: String?,
        |    private val loginCode: String?,
        |    private val requestSmsCode: String?,
        |    private val smsCode: String?,
        |    private val usage: String?,
        |) {
        |    private fun at(p: String?, key: String) = p ?: throw DroidServerException(404, "Bu hesap ucu kapalı: " + key)
        |
        |    private fun keep(e: JsonElement?): LoginResult {
        |        val o = e!!.jsonObject
        |        val token = (o["token"] as? JsonPrimitive)?.content
        |        val refresh = (o["refreshToken"] as? JsonPrimitive)?.content
        |        if (token != null && refresh != null) client.setTokens(token, refresh)
        |        return LoginResult(o.takeIf { token != null }, (o["challenge"] as? JsonPrimitive)?.content)
        |    }
        |
        |    /** Gövde: email, password ve ek alanlar. */
        |    fun signup(body: JsonObject): LoginResult = keep(client.request("POST", at(signup, "signup"), json = body))
        |
        |    fun login(email: String, password: String): LoginResult = keep(
        |        client.request("POST", at(login, "login"), json = buildJsonObject { put("email", JsonPrimitive(email)); put("password", JsonPrimitive(password)) }),
        |    )
        |
        |    fun verify2fa(challenge: String, code: String): LoginResult = keep(
        |        client.request("POST", at(verify2fa, "verify2fa"), json = buildJsonObject { put("challenge", JsonPrimitive(challenge)); put("code", JsonPrimitive(code)) }),
        |    )
        |
        |    fun requestLoginCode(email: String) {
        |        client.request("POST", at(requestLoginCode, "requestLoginCode"), json = buildJsonObject { put("email", JsonPrimitive(email)) })
        |    }
        |
        |    fun loginWithCode(email: String, code: String): LoginResult = keep(
        |        client.request("POST", at(loginCode, "loginCode"), json = buildJsonObject { put("email", JsonPrimitive(email)); put("code", JsonPrimitive(code)) }),
        |    )
        |
        |    /** Numara uluslararası biçimde (+905551234567); kod sunucu telefonundan SMS ile gelir. */
        |    fun requestSmsCode(phone: String) {
        |        client.request("POST", at(requestSmsCode, "requestSmsCode"), json = buildJsonObject { put("phone", JsonPrimitive(phone)) })
        |    }
        |
        |    fun loginWithSms(phone: String, code: String): LoginResult = keep(
        |        client.request("POST", at(smsCode, "smsCode"), json = buildJsonObject { put("phone", JsonPrimitive(phone)); put("code", JsonPrimitive(code)) }),
        |    )
        |
        |    fun logout() {
        |        client.refreshToken?.let { r -> runCatching { client.request("POST", at(logout, "logout"), json = buildJsonObject { put("refreshToken", JsonPrimitive(r)) }) } }
        |        client.setTokens(null, null)
        |    }
        |
        |    fun me(): JsonObject = client.request("GET", at(me, "me"))!!.jsonObject
        |
        |    fun usage(): JsonObject = client.request("GET", at(usage, "usage"))!!.jsonObject
        |}
        |
        |/** Telefondaki "Bağlan (QR)" ekranının metni: `droidserver://connect?url=…&name=…&token=…&exp=…` veya düz adres. */
        |data class ConnectionLink(val url: String, val name: String? = null, val token: String? = null, val expiresAtEpochSec: Long? = null) {
        |    companion object {
        |        fun parse(link: String): ConnectionLink {
        |            val t = link.trim()
        |            if (t.startsWith("http://") || t.startsWith("https://")) return ConnectionLink(t.trimEnd('/'))
        |            require(t.startsWith("droidserver://connect?")) { "DroidServer bağlantısı değil" }
        |            val q = t.substringAfter('?').split('&').filter { '=' in it }
        |                .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        |            val url = q["url"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: throw IllegalArgumentException("Bağlantıda adres yok")
        |            return ConnectionLink(url, q["name"], q["token"], q["exp"]?.toLongOrNull())
        |        }
        |    }
        |}
        |
        |/** Yerel ağda bulunan sunucu; [baseUrl] API adresi. */
        |data class FoundServer(val name: String, val port: Int, val addresses: List<InetAddress>, val attributes: Map<String, String>) {
        |    val baseUrl: String?
        |        get() {
        |            val a = addresses.firstOrNull { it.address.size == 4 } ?: addresses.firstOrNull() ?: return null
        |            val host = a.hostAddress.substringBefore('%').let { if (':' in it) "[${'$'}it]" else it }
        |            return "http://${'$'}host:${'$'}port" + (attributes["path"] ?: "/api")
        |        }
        |}
        |
        |/** `_droidserver._tcp.local` için mDNS PTR sorgusu (geçici porttan; yanıtlar doğrudan gelir). */
        |object LocalDiscovery {
        |    private const val SERVICE = "_droidserver._tcp.local"
        |
        |    fun find(timeoutMs: Long = 2_000, target: InetSocketAddress = InetSocketAddress(InetAddress.getByName("224.0.0.251"), 5353)): List<FoundServer> {
        |        val records = mutableListOf<Triple<String, Int, Any?>>()
        |        val sources = HashMap<String, InetAddress>()
        |        DatagramSocket().use { socket ->
        |            val q = java.io.ByteArrayOutputStream().apply {
        |                write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        |                SERVICE.split('.').forEach { l -> val b = l.toByteArray(); write(b.size); write(b) }
        |                write(byteArrayOf(0, 0, 12, 0, 1))
        |            }.toByteArray()
        |            socket.send(DatagramPacket(q, q.size, target))
        |            val deadline = System.currentTimeMillis() + timeoutMs
        |            val buf = ByteArray(9000)
        |            while (true) {
        |                val left = deadline - System.currentTimeMillis()
        |                if (left <= 0) break
        |                socket.soTimeout = left.toInt().coerceAtLeast(1)
        |                val packet = DatagramPacket(buf, buf.size)
        |                try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
        |                val parsed = runCatching { parse(packet.data.copyOf(packet.length)) }.getOrNull() ?: continue
        |                records += parsed
        |                parsed.filter { it.second == 12 }.forEach { sources[it.third as String] = packet.address }
        |            }
        |        }
        |        return records.filter { it.second == 12 && it.first == SERVICE.lowercase() }.map { it.third as String }.distinct().mapNotNull { instance ->
        |            val key = instance.lowercase()
        |            @Suppress("UNCHECKED_CAST")
        |            val srv = records.firstOrNull { it.second == 33 && it.first == key }?.third as Pair<Int, String>? ?: return@mapNotNull null
        |            @Suppress("UNCHECKED_CAST")
        |            val txt = records.firstOrNull { it.second == 16 && it.first == key }?.third as Map<String, String>? ?: emptyMap()
        |            val addresses = records.filter { (it.second == 1 || it.second == 28) && it.first == srv.second.lowercase() }.map { it.third as InetAddress }
        |                .ifEmpty { listOfNotNull(sources[instance]) }
        |            FoundServer(instance.removeSuffix(".${'$'}SERVICE"), srv.first, addresses.distinct(), txt)
        |        }
        |    }
        |
        |    private fun parse(msg: ByteArray): List<Triple<String, Int, Any?>> {
        |        var pos = 4
        |        fun u16(): Int = ((msg[pos].toInt() and 0xff) shl 8 or (msg[pos + 1].toInt() and 0xff)).also { pos += 2 }
        |        fun name(): String {
        |            val labels = mutableListOf<String>()
        |            var p = pos
        |            var jumped = false
        |            var hops = 0
        |            while (true) {
        |                val len = msg[p].toInt() and 0xff
        |                if (len == 0) { p++; break }
        |                if (len and 0xc0 == 0xc0) {
        |                    check(++hops <= 64) { "mDNS: ad döngüsü" }
        |                    if (!jumped) pos = p + 2
        |                    jumped = true
        |                    p = ((len and 0x3f) shl 8) or (msg[p + 1].toInt() and 0xff)
        |                } else {
        |                    labels += String(msg, p + 1, len, Charsets.UTF_8); p += len + 1
        |                }
        |            }
        |            if (!jumped) pos = p
        |            return labels.joinToString(".")
        |        }
        |        val questions = u16()
        |        val total = u16() + u16() + u16()
        |        repeat(questions) { name(); pos += 4 }
        |        return List(total) {
        |            val name = name().lowercase()
        |            val type = u16()
        |            pos += 6
        |            val len = u16()
        |            val start = pos
        |            val data: Any? = when (type) {
        |                12 -> name()
        |                33 -> { pos += 4; val port = u16(); port to name() }
        |                16 -> buildMap {
        |                    while (pos < start + len) {
        |                        val n = msg[pos++].toInt() and 0xff
        |                        val s = String(msg, pos, n, Charsets.UTF_8).also { pos += n }
        |                        if (s.isNotEmpty()) put(s.substringBefore('='), s.substringAfter('=', ""))
        |                    }
        |                }
        |                1, 28 -> InetAddress.getByAddress(msg.copyOfRange(start, start + len))
        |                else -> null
        |            }
        |            pos = start + len
        |            Triple(name, type, data)
        |        }
        |    }
        |}
        |
    """.trimMargin()

    private fun resource(name: String): String =
        SdkGenerator::class.java.getResourceAsStream("/sdk/$name")?.use { it.readBytes().decodeToString() } ?: error("SDK kaynağı yok: $name")

    // ---- Dart ----

    private val DART_KEYWORDS = setOf(
        "assert", "break", "case", "catch", "class", "const", "continue", "default", "do", "else", "enum", "extends", "false", "final",
        "finally", "for", "if", "in", "is", "new", "null", "rethrow", "return", "super", "switch", "this", "throw", "true", "try", "var",
        "void", "while", "with",
    )

    /** Dart'ta ayrılmış sözcük olan alan adı `_` ile biter (`in` → `in_`); JSON anahtarı değişmez. */
    private fun dartName(s: String) = if (s in DART_KEYWORDS) s + "_" else s

    private fun dartStr(s: String?) = if (s == null) "null" else "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("$", "\\$") + "'"

    private fun dartType(f: FieldConfig?, type: FieldType): String = when (type) {
        FieldType.TEXT, FieldType.DATETIME, FieldType.SELECT -> "String"
        FieldType.INTEGER, FieldType.RELATION -> "int"
        FieldType.REAL -> "double"
        FieldType.BOOLEAN -> "bool"
        FieldType.JSON -> "Object"
        FieldType.GEO -> "GeoPoint"
        FieldType.FILE -> if (f?.isMultiple == true) "List<String>" else "String"
        FieldType.MULTISELECT -> "List<String>"
    }

    /** JSON değerinden alanın Dart değeri (null olabilir; [required] ise değil). */
    private fun dartRead(key: String, f: FieldConfig?, type: FieldType, required: Boolean): String {
        val v = "j[${dartStr(key)}]"
        val q = if (required) "" else "?"
        return when (dartType(f, type)) {
            "int" -> "($v as num$q)$q.toInt()"
            "double" -> "($v as num$q)$q.toDouble()"
            "List<String>" -> "($v as List$q)$q.cast<String>()"
            "Object" -> v
            "GeoPoint" -> "$v == null ? null : GeoPoint.fromJson($v as Map<String, dynamic>)"
            else -> "$v as ${dartType(f, type)}$q"
        }
    }

    private fun dart(config: ServerConfig): String {
        val api = ApiPaths(config)
        val users = config.auth.users
        fun p(e: Endpoint) = dartStr(api.path(e))
        return buildString {
            append(header(config, "//"))
            appendLine("// Gerekenler: yalnızca Dart 3 SDK'sı (`dart:io`). Dart VM ve Flutter Android/iOS/masaüstünde çalışır; Flutter web'de çalışmaz.")
            appendLine("//")
            appendLine("// Kullanım:")
            appendLine("//   final ds = DroidServerClient('https://telefon.example.com/api', apiKey: '…');   // veya token / auth.login")
            appendLine("//   final page = await ds.${config.collections.firstOrNull()?.let { memberName(it.name) } ?: "todos"}.list(const ListOptions(filter: {'done': 'false'}, sort: '-id'));")
            appendLine("//   final sub = ds.subscribe(['${config.collections.firstOrNull()?.name ?: "todos"}']).listen((e) => print('${'$'}{e.action} ${'$'}{e.record}'));")
            appendLine("// ignore_for_file: non_constant_identifier_names")
            appendLine()
            appendLine("import 'dart:async';")
            appendLine("import 'dart:convert';")
            appendLine("import 'dart:io';")
            appendLine("import 'dart:typed_data';")
            for (c in config.collections) {
                val t = typeName(c.name)
                val props = props(c)
                appendLine()
                appendLine("/// `${c.name}` kaydı. Gizli alanlar yetkiye göre gelmeyebilir; bu yüzden alanlar boş olabilir.")
                appendLine("class $t {")
                for (pr in props) {
                    val sysId = pr.system && pr.type == FieldType.INTEGER
                    appendLine("  final ${dartType(pr.field, pr.type)}${if (sysId) "" else "?"} ${dartName(pr.name)};")
                }
                appendLine("  final Map<String, dynamic>? expand;")
                appendLine()
                append("  const $t({")
                append(props.joinToString(", ") { pr -> (if (pr.system && pr.type == FieldType.INTEGER) "required " else "") + "this.${dartName(pr.name)}" })
                appendLine(", this.expand});")
                appendLine()
                appendLine("  factory $t.fromJson(Map<String, dynamic> j) => $t(")
                for (pr in props) appendLine("        ${dartName(pr.name)}: ${dartRead(pr.name, pr.field, pr.type, pr.system && pr.type == FieldType.INTEGER)},")
                appendLine("        expand: j['expand'] as Map<String, dynamic>?,")
                appendLine("      );")
                appendLine("}")
                appendLine()
                appendLine("/// `${c.name}` oluşturma/güncelleme gövdesi: verilmeyen (null) alanlar gönderilmez. Boşaltmak için `RecordCollection.updateRaw`.")
                appendLine("class ${t}Input {")
                val inputs = writable(c)
                for (f in inputs) appendLine("  final ${dartType(f, f.type)}? ${dartName(f.name)};")
                appendLine()
                appendLine("  const ${t}Input(${if (inputs.isEmpty()) "" else "{" + inputs.joinToString(", ") { "this.${dartName(it.name)}" } + "}"});")
                appendLine()
                appendLine("  Map<String, dynamic> toJson() => {")
                for (f in inputs) appendLine("        if (${dartName(f.name)} != null) ${dartStr(f.name)}: ${dartName(f.name)}${if (f.type == FieldType.GEO) "?.toJson()" else ""},")
                appendLine("      };")
                appendLine("}")
            }
            appendLine()
            appendLine("/// ${config.name} istemcisi. `baseUrl`: API adresi, örn. `https://telefon.example.com/api`; `fallbackUrls`: canlı")
            appendLine("/// kopyaların adresleri (ana sunucuya ulaşılamazsa veya 503 dönerse sıradaki denenir). `readUrls`: okumaların gideceği")
            appendLine("/// kopyalar; bir yazmadan sonra `readYourWritesMs` boyunca okumalar ana sunucudan.")
            appendLine("class DroidServerClient extends DroidServerBase {")
            appendLine("  DroidServerClient(super.baseUrl, {super.apiKey, super.token, super.refreshToken, super.fallbackUrls, super.readUrls, super.readYourWritesMs})")
            appendLine("      : super(realtimePath: ${p(Endpoint.REALTIME)}, refreshPath: ${if (users.enabled) p(Endpoint.AUTH_REFRESH) else "null"}, filesPath: ${p(Endpoint.FILES)});")
            for (c in config.collections) {
                val t = typeName(c.name)
                val paths = TS_COLLECTION_ENDPOINTS.joinToString(", ") { e -> "${e.key}: ${dartStr(api.path(c, e))}" } +
                    ", sync: ${dartStr(api.path(c, CollectionEndpoint.SYNC))}, idField: ${dartStr(idName(c) ?: "id")}"
                appendLine()
                appendLine("  late final ${memberName(c.name)} = RecordCollection<$t, ${t}Input>(")
                appendLine("      this, ${dartStr(c.name)}, const CollectionPaths($paths), $t.fromJson, (i) => i.toJson());")
            }
            if (users.enabled) {
                appendLine()
                appendLine("  late final auth = Auth(this,")
                appendLine("      signup: ${p(Endpoint.AUTH_SIGNUP)}, login: ${p(Endpoint.AUTH_LOGIN)}, logout: ${p(Endpoint.AUTH_LOGOUT)}, me: ${p(Endpoint.AUTH_ME)},")
                appendLine("      verify2fa: ${p(Endpoint.AUTH_2FA_VERIFY)}, requestLoginCode: ${p(Endpoint.AUTH_REQUEST_LOGIN_CODE)}, loginCode: ${p(Endpoint.AUTH_LOGIN_CODE)},")
                appendLine("      requestSmsCode: ${p(Endpoint.AUTH_REQUEST_SMS_CODE)}, smsCode: ${p(Endpoint.AUTH_SMS_CODE)}, usage: ${p(Endpoint.AUTH_USAGE)});")
                if (users.teams.enabled) {
                    appendLine()
                    appendLine("  late final teams = Teams(this,")
                    appendLine("      list: ${p(Endpoint.TEAMS)}, create: ${p(Endpoint.TEAMS_CREATE)}, get: ${p(Endpoint.TEAM)}, update: ${p(Endpoint.TEAM_UPDATE)}, delete: ${p(Endpoint.TEAM_DELETE)},")
                    appendLine("      addMember: ${p(Endpoint.TEAM_ADD_MEMBER)}, updateMember: ${p(Endpoint.TEAM_UPDATE_MEMBER)}, removeMember: ${p(Endpoint.TEAM_REMOVE_MEMBER)});")
                }
                if (config.payments.enabled && config.payments.plans.isNotEmpty()) {
                    appendLine()
                    appendLine("  late final payments = Payments(this,")
                    appendLine("      plans: ${p(Endpoint.PAYMENT_PLANS)}, subscribe: ${p(Endpoint.PAYMENT_SUBSCRIBE)}, subscriptions: ${p(Endpoint.PAYMENT_SUBSCRIPTIONS)}, cancel: ${p(Endpoint.PAYMENT_SUBSCRIPTION_CANCEL)});")
                }
            }
            appendLine()
            appendLine("  /// QR / bağlantı metninden (`droidserver://connect?url=…&token=…` veya düz adres): JWT token, değilse API anahtarı olur.")
            appendLine("  static DroidServerClient fromLink(String link) {")
            appendLine("    final l = ConnectionLink.parse(link);")
            appendLine("    final jwt = l.token != null && '.'.allMatches(l.token!).length == 2;")
            appendLine("    return DroidServerClient(l.url, apiKey: jwt ? null : l.token, token: jwt ? l.token : null);")
            appendLine("  }")
            appendLine("}")
            append(resource("base.dart"))
            append(resource("offline.dart"))
        }
    }

    // ---- Swift ----

    private val SWIFT_KEYWORDS = setOf(
        "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import", "init", "inout", "internal", "let",
        "open", "operator", "private", "precedencegroup", "protocol", "public", "rethrows", "static", "struct", "subscript", "typealias",
        "var", "break", "case", "catch", "continue", "default", "defer", "do", "else", "fallthrough", "for", "guard", "if", "in", "repeat",
        "return", "throw", "switch", "where", "while", "Any", "as", "await", "false", "is", "nil", "self", "Self", "super", "throws",
        "true", "try",
    )

    private fun swiftName(s: String) = if (s in SWIFT_KEYWORDS) "`$s`" else s

    private fun swiftStr(s: String?) = if (s == null) "nil" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun swiftType(f: FieldConfig?, type: FieldType): String = when (type) {
        FieldType.TEXT, FieldType.DATETIME, FieldType.SELECT -> "String"
        FieldType.INTEGER, FieldType.RELATION -> "Int64"
        FieldType.REAL -> "Double"
        FieldType.BOOLEAN -> "Bool"
        FieldType.JSON -> "JSONValue"
        FieldType.GEO -> "GeoPoint"
        FieldType.FILE -> if (f?.isMultiple == true) "[String]" else "String"
        FieldType.MULTISELECT -> "[String]"
    }

    private fun swift(config: ServerConfig): String {
        val api = ApiPaths(config)
        val users = config.auth.users
        fun p(e: Endpoint) = swiftStr(api.path(e))
        return buildString {
            append(header(config, "//"))
            appendLine("// Gerekenler: Swift 5.9+ (async/await); bağımlılık yok. Apple platformları ve Linux (FoundationNetworking).")
            appendLine("//")
            appendLine("// Kullanım:")
            appendLine("//   let ds = DroidServerClient(\"https://telefon.example.com/api\", apiKey: \"…\")   // veya token / auth.login")
            appendLine("//   let page = try await ds.${config.collections.firstOrNull()?.let { memberName(it.name) } ?: "todos"}.list(ListOptions(sort: \"-id\", filter: [\"done\": \"false\"]))")
            appendLine("//   for await e in ds.subscribe([\"${config.collections.firstOrNull()?.name ?: "todos"}\"]) { print(e.action, e.record) }")
            appendLine()
            appendLine("import Foundation")
            appendLine("#if canImport(FoundationNetworking)")
            appendLine("import FoundationNetworking")
            appendLine("#endif")
            for (c in config.collections) {
                val t = typeName(c.name)
                appendLine()
                appendLine("/// `${c.name}` kaydı. Gizli alanlar yetkiye göre gelmeyebilir; bu yüzden alanlar boş olabilir.")
                appendLine("public struct $t: Decodable, Sendable {")
                for (pr in props(c)) {
                    val sysId = pr.system && pr.type == FieldType.INTEGER
                    appendLine("    public let ${swiftName(pr.name)}: ${swiftType(pr.field, pr.type)}${if (sysId) "" else "?"}")
                }
                appendLine("    public let expand: [String: JSONValue]?")
                appendLine("}")
                appendLine()
                appendLine("/// `${c.name}` oluşturma/güncelleme gövdesi: verilmeyen (nil) alanlar gönderilmez. Boşaltmak için `RecordCollection.updateRaw`.")
                appendLine("public struct ${t}Input: Encodable, Sendable {")
                val inputs = writable(c)
                for (f in inputs) appendLine("    public var ${swiftName(f.name)}: ${swiftType(f, f.type)}?")
                appendLine()
                appendLine("    public init(" + inputs.joinToString(", ") { "${swiftName(it.name)}: ${swiftType(it, it.type)}? = nil" } + ") {")
                for (f in inputs) appendLine("        self.${swiftName(f.name)} = ${swiftName(f.name)}")
                appendLine("    }")
                appendLine("}")
            }
            appendLine()
            appendLine("/// ${config.name} istemcisi. `baseUrl`: API adresi, örn. `https://telefon.example.com/api`; `fallbackUrls`: canlı")
            appendLine("/// kopyaların adresleri (ana sunucuya ulaşılamazsa veya 503 dönerse sıradaki denenir). `readUrls`: okumaların gideceği")
            appendLine("/// kopyalar; bir yazmadan sonra `readYourWritesMs` boyunca okumalar ana sunucudan.")
            appendLine("public final class DroidServerClient: DroidServerBase, @unchecked Sendable {")
            appendLine("    public init(")
            appendLine("        _ baseUrl: String, apiKey: String? = nil, token: String? = nil, refreshToken: String? = nil, fallbackUrls: [String] = [],")
            appendLine("        readUrls: [String] = [], readYourWritesMs: Int = 5000, session: URLSession = .shared,")
            appendLine("    ) {")
            appendLine("        super.init(")
            appendLine("            baseUrl: baseUrl, apiKey: apiKey, token: token, refreshToken: refreshToken, realtimePath: ${p(Endpoint.REALTIME)},")
            appendLine("            refreshPath: ${if (users.enabled) p(Endpoint.AUTH_REFRESH) else "nil"}, filesPath: ${p(Endpoint.FILES)}, fallbackUrls: fallbackUrls,")
            appendLine("            readUrls: readUrls, readYourWritesMs: readYourWritesMs, session: session,")
            appendLine("        )")
            appendLine("    }")
            for (c in config.collections) {
                val t = typeName(c.name)
                val paths = TS_COLLECTION_ENDPOINTS.joinToString(", ") { e -> "${e.key}: ${swiftStr(api.path(c, e))}" } +
                    ", sync: ${swiftStr(api.path(c, CollectionEndpoint.SYNC))}, idField: ${swiftStr(idName(c) ?: "id")}"
                appendLine()
                appendLine("    public var ${memberName(c.name)}: RecordCollection<$t, ${t}Input> {")
                appendLine("        RecordCollection(self, ${swiftStr(c.name)}, CollectionPaths($paths))")
                appendLine("    }")
            }
            if (users.enabled) {
                appendLine()
                appendLine("    public var auth: Auth {")
                appendLine("        Auth(")
                appendLine("            client: self, signupPath: ${p(Endpoint.AUTH_SIGNUP)}, loginPath: ${p(Endpoint.AUTH_LOGIN)}, logoutPath: ${p(Endpoint.AUTH_LOGOUT)}, mePath: ${p(Endpoint.AUTH_ME)},")
                appendLine("            verify2faPath: ${p(Endpoint.AUTH_2FA_VERIFY)}, requestLoginCodePath: ${p(Endpoint.AUTH_REQUEST_LOGIN_CODE)}, loginCodePath: ${p(Endpoint.AUTH_LOGIN_CODE)},")
                appendLine("            requestSmsCodePath: ${p(Endpoint.AUTH_REQUEST_SMS_CODE)}, smsCodePath: ${p(Endpoint.AUTH_SMS_CODE)}, usagePath: ${p(Endpoint.AUTH_USAGE)},")
                appendLine("        )")
                appendLine("    }")
                if (users.teams.enabled) {
                    appendLine()
                    appendLine("    public var teams: Teams {")
                    appendLine("        Teams(")
                    appendLine("            client: self, listPath: ${p(Endpoint.TEAMS)}, createPath: ${p(Endpoint.TEAMS_CREATE)}, getPath: ${p(Endpoint.TEAM)}, updatePath: ${p(Endpoint.TEAM_UPDATE)},")
                    appendLine("            deletePath: ${p(Endpoint.TEAM_DELETE)}, addMemberPath: ${p(Endpoint.TEAM_ADD_MEMBER)}, updateMemberPath: ${p(Endpoint.TEAM_UPDATE_MEMBER)},")
                    appendLine("            removeMemberPath: ${p(Endpoint.TEAM_REMOVE_MEMBER)},")
                    appendLine("        )")
                    appendLine("    }")
                }
                if (config.payments.enabled && config.payments.plans.isNotEmpty()) {
                    appendLine()
                    appendLine("    public var payments: Payments {")
                    appendLine("        Payments(")
                    appendLine("            client: self, plansPath: ${p(Endpoint.PAYMENT_PLANS)}, subscribePath: ${p(Endpoint.PAYMENT_SUBSCRIBE)},")
                    appendLine("            subscriptionsPath: ${p(Endpoint.PAYMENT_SUBSCRIPTIONS)}, cancelPath: ${p(Endpoint.PAYMENT_SUBSCRIPTION_CANCEL)},")
                    appendLine("        )")
                    appendLine("    }")
                }
            }
            appendLine()
            appendLine("    /// QR / bağlantı metninden (`droidserver://connect?url=…&token=…` veya düz adres): JWT token, değilse API anahtarı olur.")
            appendLine("    public static func fromLink(_ link: String) throws -> DroidServerClient {")
            appendLine("        let l = try ConnectionLink.parse(link)")
            appendLine("        let jwt = l.token.map { $0.split(separator: \".\", omittingEmptySubsequences: false).count == 3 } ?? false")
            appendLine("        return DroidServerClient(l.url, apiKey: jwt ? nil : l.token, token: jwt ? l.token : nil)")
            appendLine("    }")
            appendLine("}")
            append(resource("base.swift"))
            append(resource("offline.swift"))
        }
    }
}
