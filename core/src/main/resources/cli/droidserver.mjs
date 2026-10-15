#!/usr/bin/env node
// DroidServer komut satırı aracı (Node.js 18+, bağımlılık yok).
// Kurulum: curl -o droidserver.mjs http://<telefon>:8080/api/_cli && node droidserver.mjs help
// Ayarlar ~/.droidserver/cli.json'da (yalnızca sizin okuyabileceğiniz izinlerle); DROIDSERVER_URL ve DROIDSERVER_KEY
// ortam değişkenleri dosyanın yerine geçer (CI için).

import { readFileSync, writeFileSync, mkdirSync, existsSync, chmodSync, createWriteStream } from "node:fs";
import { homedir } from "node:os";
import { join, dirname } from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";

const VERSION = "1";
const TR = (process.env.LC_ALL || process.env.LANG || "").toLowerCase().startsWith("tr");
const t = (tr, en) => (TR ? tr : en);

const HELP = t(`DroidServer komut satırı aracı

Bağlanma:
  login <api-adresi> [--email E]     Yönetici anahtarıyla (sorulur) veya kullanıcı olarak giriş
  connect "<droidserver://…>"         Telefondaki "Bağlan (QR)" metniyle
  logout                              Kayıtlı oturumu sil
  whoami                              Kayıtlı sunucu ve kimlik türü

Sunucu:
  status                              Durum, istek ve cihaz özeti
  collections                         Koleksiyonlar ve alanları
  logs [-f]                           Loglar (-f: canlı izle)            [web paneli açık olmalı]
  config get [-o dosya]               Konfigürasyon (JSON)                [web paneli açık olmalı]
  config plan <dosya>                 Şema değişiklik planı (uygulamaz)   [web paneli açık olmalı]
  deploy <dosya>                      Konfigürasyonu kaydet ve deploy et  [web paneli açık olmalı]
  backup list | create | download <ad> [-o dosya]                        [web paneli açık olmalı]
  apps | apps deploy <ad>             Uygulamalar ve Git'ten deploy
  sdk <ts|kotlin> [-o dosya]          Tipli istemci SDK'sı

Kayıtlar:
  list <koleksiyon> [alan=değer …] [--sort -id] [--page 2] [--per-page 50]
  get <koleksiyon> <id>
  create <koleksiyon> '<json>' | -f dosya.json
  update <koleksiyon> <id> '<json>' | -f dosya.json
  delete <koleksiyon> <id>
  export <koleksiyon> [--format csv|json] [-o dosya]
  import <koleksiyon> <dosya> [--mode upsert|insert] [--dry-run]

Diğer:
  api <YÖNTEM> <yol> ['<json>']        Herhangi bir uç (örn. api GET /_jobs)

Seçenekler: --json (ham JSON), --profile <ad> (birden çok sunucu).
Kendinden imzalı HTTPS için: NODE_EXTRA_CA_CERTS=sertifika.pem node droidserver.mjs …`,
`DroidServer command-line tool

Connecting:
  login <api-url> [--email E]         Sign in with an admin key (prompted) or as a user
  connect "<droidserver://…>"         With the text of "Connect (QR)" on the phone
  logout                              Delete the saved session
  whoami                              Saved server and credential type

Server:
  status                              Status, requests and device summary
  collections                         Collections and fields
  logs [-f]                           Logs (-f: follow)                   [web panel must be on]
  config get [-o file]                Configuration (JSON)                [web panel must be on]
  config plan <file>                  Schema change plan (not applied)    [web panel must be on]
  deploy <file>                       Save and deploy the configuration   [web panel must be on]
  backup list | create | download <name> [-o file]                       [web panel must be on]
  apps | apps deploy <name>           Apps and deploy from Git
  sdk <ts|kotlin> [-o file]           Typed client SDK

Records:
  list <collection> [field=value …] [--sort -id] [--page 2] [--per-page 50]
  get <collection> <id>
  create <collection> '<json>' | -f file.json
  update <collection> <id> '<json>' | -f file.json
  delete <collection> <id>
  export <collection> [--format csv|json] [-o file]
  import <collection> <file> [--mode upsert|insert] [--dry-run]

Other:
  api <METHOD> <path> ['<json>']       Any endpoint (e.g. api GET /_jobs)

Options: --json (raw JSON), --profile <name> (several servers).
For self-signed HTTPS: NODE_EXTRA_CA_CERTS=cert.pem node droidserver.mjs …`);

class CliError extends Error {}

// ---- argümanlar ----
function parseArgs(argv) {
  const pos = [];
  const opt = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "-f" || a === "-o") {
      const v = argv[i + 1];
      // `logs -f`: izle; diğerlerinde -f dosya.
      if (a === "-f" && (v === undefined || v.startsWith("-"))) opt.follow = true;
      else { opt[a.slice(1)] = v; i++; }
    } else if (a.startsWith("--")) {
      const [k, v] = a.slice(2).split("=", 2);
      if (v !== undefined) opt[k] = v;
      else if (["json", "dry-run", "follow", "help"].includes(k)) opt[k] = true;
      else opt[k] = argv[++i];
    } else pos.push(a);
  }
  return { pos, opt };
}

// ---- ayarlar ----
const CONFIG = process.env.DROIDSERVER_CONFIG || join(homedir(), ".droidserver", "cli.json");

function loadAll() {
  try { return JSON.parse(readFileSync(CONFIG, "utf8")); } catch { return { profiles: {} }; }
}

function saveAll(all) {
  mkdirSync(dirname(CONFIG), { recursive: true, mode: 0o700 });
  writeFileSync(CONFIG, JSON.stringify(all, null, 2), { mode: 0o600 });
  try { chmodSync(CONFIG, 0o600); } catch { /* Windows */ }
}

function profileName(opt) { return opt.profile || process.env.DROIDSERVER_PROFILE || "default"; }

function session(opt) {
  if (process.env.DROIDSERVER_URL) return { url: process.env.DROIDSERVER_URL.replace(/\/+$/, ""), key: process.env.DROIDSERVER_KEY };
  const p = loadAll().profiles[profileName(opt)];
  if (!p) throw new CliError(t("Bağlı değil: önce `login <api-adresi>` çalıştırın.", "Not connected: run `login <api-url>` first."));
  return p;
}

function store(opt, s) {
  const all = loadAll();
  all.profiles[profileName(opt)] = s;
  saveAll(all);
}

// ---- HTTP ----
async function http(s, method, path, { json, body, headers = {}, raw = false, opt = {}, retry = true } = {}) {
  const url = (path.startsWith("http") ? path : s.url + path);
  const h = { ...headers };
  const bearer = s.token || s.key;
  if (bearer) h.Authorization = "Bearer " + bearer;
  if (json !== undefined) { h["Content-Type"] = "application/json"; body = JSON.stringify(json); }
  let res;
  try {
    res = await fetch(url, { method, headers: h, body });
  } catch (e) {
    throw new CliError(t("Sunucuya ulaşılamadı: ", "Cannot reach the server: ") + url + " (" + (e.cause?.code || e.message) + ")");
  }
  if (res.status === 401 && retry && s.refreshToken && s.refreshPath) {
    const r = await fetch(s.url + s.refreshPath, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ refreshToken: s.refreshToken }) });
    if (r.ok) {
      const tk = await r.json();
      s.token = tk.token; s.refreshToken = tk.refreshToken;
      if (!process.env.DROIDSERVER_URL) store(opt, s);
      return http(s, method, path, { json, body, headers, raw, opt, retry: false });
    }
  }
  if (raw) {
    if (!res.ok) throw await httpError(res);
    return res;
  }
  if (res.status === 204) return null;
  const text = await res.text();
  let data;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!res.ok) throw httpErrorFrom(res.status, data);
  return data;
}

async function httpError(res) {
  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch { data = text; }
  return httpErrorFrom(res.status, data);
}

function httpErrorFrom(status, data) {
  const msg = (data && typeof data === "object" && data.message) || (typeof data === "string" && data.slice(0, 200)) || "";
  const details = data && typeof data === "object" && data.details && Object.keys(data.details).length
    ? "\n" + Object.entries(data.details).map(([k, v]) => "  " + k + ": " + v).join("\n") : "";
  if (status === 404 && /_admin\/api/.test(msg)) return new CliError(msg);
  return new CliError(`HTTP ${status}${msg ? ": " + msg : ""}${details}`);
}

function origin(s) { return new URL(s.url).origin; }

async function panel(s, method, path, opts = {}) {
  try {
    return await http(s, method, origin(s) + "/_admin/api" + path, opts);
  } catch (e) {
    if (/^HTTP 404/.test(e.message)) {
      throw new CliError(t("Bu komut web panelinin uçlarını kullanır; Sihirbaz → Güvenlik → Web paneli'ni açın.",
        "This command uses the web panel's endpoints; turn on Wizard → Security → Web panel."));
    }
    throw e;
  }
}

// ---- çıktı ----
function print(data, opt) {
  if (opt.json || typeof data !== "object" || data === null) {
    process.stdout.write((typeof data === "string" ? data : JSON.stringify(data, null, 2)) + "\n");
    return;
  }
  process.stdout.write(JSON.stringify(data, null, 2) + "\n");
}

function table(rows, columns) {
  if (!rows.length) { process.stdout.write(t("(boş)\n", "(empty)\n")); return; }
  const cell = (v) => {
    if (v === null || v === undefined) return "";
    const s = typeof v === "object" ? JSON.stringify(v) : String(v);
    return s.length > 40 ? s.slice(0, 39) + "…" : s;
  };
  const widths = columns.map((c) => Math.max(c.length, ...rows.map((r) => cell(r[c]).length)));
  const line = (vals) => vals.map((v, i) => v.padEnd(widths[i])).join("  ").trimEnd();
  process.stdout.write(line(columns) + "\n" + line(widths.map((w) => "-".repeat(w))) + "\n");
  for (const r of rows) process.stdout.write(line(columns.map((c) => cell(r[c]))) + "\n");
}

async function ask(question, hidden = false) {
  if (!process.stdin.isTTY) {
    const chunks = [];
    for await (const c of process.stdin) chunks.push(c);
    return Buffer.concat(chunks).toString("utf8").split(/\r?\n/)[0].trim();
  }
  process.stdout.write(question);
  return new Promise((resolve) => {
    let value = "";
    const stdin = process.stdin;
    stdin.setRawMode?.(hidden);
    stdin.resume();
    stdin.setEncoding("utf8");
    const onData = (ch) => {
      for (const c of ch) {
        if (c === "\r" || c === "\n") {
          stdin.setRawMode?.(false); stdin.pause(); stdin.off("data", onData);
          process.stdout.write("\n");
          resolve(value.trim());
          return;
        }
        if (c === "\u0003") { process.stdout.write("\n"); process.exit(130); }
        if (c === "\u007f" || c === "\b") { value = value.slice(0, -1); continue; }
        value += c;
        if (!hidden) process.stdout.write(c);
      }
    };
    stdin.on("data", onData);
  });
}

function bodyArg(pos, i, opt) {
  const text = opt.f ? readFileSync(opt.f, "utf8") : pos[i];
  if (text === undefined) throw new CliError(t("JSON gövde gerekli ('{…}' veya -f dosya.json)", "JSON body required ('{…}' or -f file.json)"));
  try { return JSON.parse(text); } catch (e) { throw new CliError(t("Geçersiz JSON: ", "Invalid JSON: ") + e.message); }
}

function need(v, what) {
  if (v === undefined || v === "") throw new CliError(t("Eksik: ", "Missing: ") + what + t(" (yardım: help)", " (see: help)"));
  return v;
}

const enc = encodeURIComponent;

async function save(res, file) {
  if (!file) { await pipeline(Readable.fromWeb(res.body), process.stdout); return; }
  await pipeline(Readable.fromWeb(res.body), createWriteStream(file));
  process.stderr.write(t("Kaydedildi: ", "Saved: ") + file + "\n");
}

// ---- komutlar ----
const commands = {
  async help() { process.stdout.write(HELP + "\n"); },

  async version() { process.stdout.write("droidserver-cli " + VERSION + "\n"); },

  async login(pos, opt) {
    const url = need(pos[0], t("API adresi (örn. http://192.168.1.20:8080/api)", "API address (e.g. http://192.168.1.20:8080/api)")).replace(/\/+$/, "");
    if (!/^https?:\/\//.test(url)) throw new CliError(t("Adres http:// veya https:// ile başlamalı", "The address must start with http:// or https://"));
    const health = await http({ url }, "GET", "/health");
    if (opt.email) {
      const password = await ask(t("Parola: ", "Password: "), true);
      let r = await http({ url }, "POST", "/auth/login", { json: { email: opt.email, password } });
      if (r.twoFactorRequired) {
        const code = await ask(t("Doğrulayıcı kodu: ", "Authenticator code: "));
        r = await http({ url }, "POST", "/auth/2fa/verify", { json: { challenge: r.challenge, code } });
      }
      store(opt, { url, token: r.token, refreshToken: r.refreshToken, refreshPath: "/auth/refresh", user: opt.email });
    } else {
      const key = opt.key || process.env.DROIDSERVER_KEY || await ask(t("Yönetici anahtarı (görünmez): ", "Admin key (hidden): "), true);
      if (!key) throw new CliError(t("Anahtar gerekli", "Key required"));
      await http({ url, key }, "GET", "/_schema");
      store(opt, { url, key });
    }
    process.stdout.write(t(`Bağlandı: ${health.name} (${url})\n`, `Connected: ${health.name} (${url})\n`));
  },

  async connect(pos, opt) {
    const link = need(pos[0], t("bağlantı metni", "link text"));
    let url, token;
    if (/^https?:\/\//.test(link)) url = link;
    else {
      if (!link.startsWith("droidserver://connect?")) throw new CliError(t("DroidServer bağlantısı değil", "Not a DroidServer link"));
      const q = new URLSearchParams(link.slice(link.indexOf("?") + 1));
      url = q.get("url"); token = q.get("token") || undefined;
    }
    url = need(url, "url").replace(/\/+$/, "");
    const s = token ? (token.split(".").length === 3 ? { url, token } : { url, key: token }) : { url };
    const health = await http(s, "GET", "/health");
    store(opt, s);
    process.stdout.write(t(`Bağlandı: ${health.name} (${url})\n`, `Connected: ${health.name} (${url})\n`));
  },

  async logout(pos, opt) {
    const all = loadAll();
    const s = all.profiles[profileName(opt)];
    if (s?.refreshToken) await http(s, "POST", "/auth/logout", { json: { refreshToken: s.refreshToken } }).catch(() => undefined);
    delete all.profiles[profileName(opt)];
    saveAll(all);
    process.stdout.write(t("Oturum silindi.\n", "Session deleted.\n"));
  },

  async whoami(pos, opt) {
    const s = session(opt);
    const kind = s.key ? t("yönetici anahtarı", "admin key") : s.token ? t("kullanıcı ", "user ") + (s.user || "") : t("kimliksiz", "no credentials");
    process.stdout.write(`${s.url} · ${kind}\n`);
  },

  async status(pos, opt) {
    const s = session(opt);
    const health = await http(s, "GET", "/health");
    const metrics = await http(s, "GET", "/_metrics").catch(() => null);
    const phone = await http(s, "GET", "/_phone/status").catch(() => null);
    if (opt.json) return print({ health, metrics, phone }, opt);
    const up = Math.round((Date.now() - Date.parse(health.startedAt)) / 60000);
    process.stdout.write(`${health.name} · ${t("çalışıyor", "running")} · ${up} ${t("dk", "min")} · ${health.collections} ${t("koleksiyon", "collections")}\n`);
    if (metrics?.requests) {
      const r = metrics.requests;
      process.stdout.write(t("İstek: ", "Requests: ") + r.total + t(" · hata (5xx) ", " · errors (5xx) ") + (r.byStatus?.["5xx"] ?? 0) + "\n");
    }
    if (phone) {
      const parts = [];
      if (phone.batteryPercent != null) parts.push(t("pil %", "battery ") + phone.batteryPercent + (TR ? "" : "%") + (phone.charging ? t(" (şarjda)", " (charging)") : ""));
      if (phone.batteryTempC != null) parts.push(phone.batteryTempC + " °C");
      if (phone.freeStorageBytes != null) parts.push(t("boş ", "free ") + Math.round(phone.freeStorageBytes / 1048576) + " MB");
      if (parts.length) process.stdout.write(t("Telefon: ", "Phone: ") + parts.join(" · ") + "\n");
    }
  },

  async collections(pos, opt) {
    const s = session(opt);
    const list = await http(s, "GET", "/_schema");
    if (opt.json) return print(list, opt);
    const [name, fields] = TR ? ["ad", "alanlar"] : ["name", "fields"];
    table(list.map((c) => ({ [name]: c.name, [fields]: (c.fields || []).map((f) => f.name + ":" + f.type).join(", ") })), [name, fields]);
  },

  async list(pos, opt) {
    const s = session(opt);
    const c = need(pos[0], t("koleksiyon", "collection"));
    const q = new URLSearchParams();
    for (const p of pos.slice(1)) {
      const i = p.indexOf("=");
      if (i <= 0) throw new CliError(t("Filtre alan=değer biçiminde olmalı: ", "Filter must be field=value: ") + p);
      q.append(p.slice(0, i), p.slice(i + 1));
    }
    if (opt.sort) q.set("sort", opt.sort);
    if (opt.page) q.set("page", opt.page);
    if (opt["per-page"]) q.set("perPage", opt["per-page"]);
    const page = await http(s, "GET", `/collections/${enc(c)}/records${q.size ? "?" + q : ""}`);
    if (opt.json) return print(page, opt);
    const cols = [...new Set(page.items.flatMap((r) => Object.keys(r)).filter((k) => k !== "expand"))].slice(0, 8);
    table(page.items, cols);
    process.stdout.write(t(`Sayfa ${page.page}/${page.totalPages} · toplam ${page.totalItems}\n`, `Page ${page.page}/${page.totalPages} · total ${page.totalItems}\n`));
  },

  async get(pos, opt) {
    const s = session(opt);
    print(await http(s, "GET", `/collections/${enc(need(pos[0], "collection"))}/records/${enc(need(pos[1], "id"))}`), opt);
  },

  async create(pos, opt) {
    const s = session(opt);
    print(await http(s, "POST", `/collections/${enc(need(pos[0], "collection"))}/records`, { json: bodyArg(pos, 1, opt) }), opt);
  },

  async update(pos, opt) {
    const s = session(opt);
    const path = `/collections/${enc(need(pos[0], "collection"))}/records/${enc(need(pos[1], "id"))}`;
    print(await http(s, "PATCH", path, { json: bodyArg(pos, 2, opt) }), opt);
  },

  async delete(pos, opt) {
    const s = session(opt);
    await http(s, "DELETE", `/collections/${enc(need(pos[0], "collection"))}/records/${enc(need(pos[1], "id"))}`);
    process.stdout.write(t("Silindi.\n", "Deleted.\n"));
  },

  async export(pos, opt) {
    const s = session(opt);
    const format = opt.format || "csv";
    const res = await http(s, "GET", `/collections/${enc(need(pos[0], "collection"))}/export?format=${enc(format)}`, { raw: true });
    await save(res, opt.o);
  },

  async import(pos, opt) {
    const s = session(opt);
    const c = need(pos[0], t("koleksiyon", "collection"));
    const file = need(pos[1], t("dosya", "file"));
    const format = opt.format || (file.toLowerCase().endsWith(".json") ? "json" : "csv");
    const q = new URLSearchParams({ format });
    if (opt.mode) q.set("mode", opt.mode);
    if (opt["dry-run"]) q.set("dryRun", "true");
    const r = await http(s, "POST", `/collections/${enc(c)}/import?${q}`, { body: readFileSync(file), headers: { "Content-Type": "text/plain; charset=utf-8" } });
    print(r, opt);
  },

  async logs(pos, opt) {
    const s = session(opt);
    let after = 0;
    for (;;) {
      const r = await panel(s, "GET", "/logs?after=" + after);
      for (const line of r.lines) process.stdout.write(line + "\n");
      after = r.next;
      if (!opt.follow) return;
      await new Promise((ok) => setTimeout(ok, 2000));
    }
  },

  async config(pos, opt) {
    const s = session(opt);
    const sub = pos[0] || "get";
    if (sub === "get") {
      const r = await panel(s, "GET", "/config");
      if (opt.o) { writeFileSync(opt.o, r.text); process.stderr.write(t("Kaydedildi: ", "Saved: ") + opt.o + "\n"); } else process.stdout.write(r.text + "\n");
    } else if (sub === "plan") {
      print(await panel(s, "POST", "/config/plan", { json: { text: readFileSync(need(pos[1], t("dosya", "file")), "utf8") } }), opt);
    } else throw new CliError(t("config get | config plan <dosya>", "config get | config plan <file>"));
  },

  async deploy(pos, opt) {
    const s = session(opt);
    const text = readFileSync(need(pos[0], t("konfigürasyon dosyası", "configuration file")), "utf8");
    const plan = await panel(s, "POST", "/config/plan", { json: { text } });
    if ((plan.blockers && plan.blockers.length) || (plan.errors && plan.errors.length)) {
      print(plan, { json: true });
      throw new CliError(t("Deploy edilmedi: plan engel içeriyor.", "Not deployed: the plan has blockers."));
    }
    const before = (await panel(s, "GET", "/logs?after=0")).next;
    await panel(s, "POST", "/config/deploy", { json: { text } });
    process.stdout.write(t("Deploy başladı…\n", "Deploy started…\n"));
    // Sonucu loglardan izle (en çok 2 dk); sunucu yeniden başlarken bağlantı kısa süre kopabilir.
    let after = before;
    const end = Date.now() + 120_000;
    while (Date.now() < end) {
      await new Promise((ok) => setTimeout(ok, 1500));
      const r = await panel(s, "GET", "/logs?after=" + after).catch(() => null);
      if (!r) continue;
      after = r.next;
      for (const line of r.lines) {
        process.stdout.write(line + "\n");
        if (/çalışıyor|running on/i.test(line)) { process.stdout.write(t("Tamam.\n", "Done.\n")); return; }
        if (/Deploy başarısız|Konfigürasyon hatası|Başlatılamadı|Migration hatası/.test(line)) throw new CliError(t("Deploy başarısız.", "Deploy failed."));
      }
    }
    process.stdout.write(t("Sonuç 2 dakikada görülmedi; `logs` ile bakın.\n", "No result within 2 minutes; check `logs`.\n"));
  },

  async backup(pos, opt) {
    const s = session(opt);
    const sub = pos[0] || "list";
    if (sub === "list") {
      const list = await panel(s, "GET", "/backups");
      if (opt.json) return print(list, opt);
      return table(list, Object.keys(list[0] || { name: "" }));
    }
    if (sub === "create") return print(await panel(s, "POST", "/backups"), opt);
    if (sub === "download") {
      const name = need(pos[1], t("yedek adı", "backup name"));
      const res = await http(s, "GET", origin(s) + "/_admin/api/backups/" + enc(name), { raw: true });
      return save(res, opt.o || name);
    }
    throw new CliError("backup list | create | download <name>");
  },

  async apps(pos, opt) {
    const s = session(opt);
    if (pos[0] === "deploy") return print(await http(s, "POST", `/_apps/${enc(need(pos[1], t("uygulama", "app")))}/deploy`), opt);
    print(await http(s, "GET", "/_apps"), opt);
  },

  async sdk(pos, opt) {
    const s = session(opt);
    const lang = need(pos[0], "ts | kotlin");
    const res = await http(s, "GET", `/_sdk/${enc(lang)}`, { raw: true });
    await save(res, opt.o);
  },

  async api(pos, opt) {
    const s = session(opt);
    const method = need(pos[0], t("yöntem", "method")).toUpperCase();
    const path = need(pos[1], t("yol", "path"));
    const json = pos[2] !== undefined || opt.f ? bodyArg(pos, 2, opt) : undefined;
    print(await http(s, method, path.startsWith("/") ? path : "/" + path, { json }), opt);
  },
};

async function main() {
  const [cmd = "help", ...rest] = process.argv.slice(2);
  const { pos, opt } = parseArgs(rest);
  const fn = commands[cmd] || (["-h", "--help"].includes(cmd) ? commands.help : null);
  if (!fn) throw new CliError(t("Bilinmeyen komut: ", "Unknown command: ") + cmd + t(" (yardım: help)", " (see: help)"));
  if (opt.help) return commands.help();
  await fn(pos, opt);
}

main().catch((e) => {
  process.stderr.write((e instanceof CliError ? e.message : (e.stack || String(e))) + "\n");
  process.exit(1);
});
