// DroidServer web paneli: bağımlılıksız tek sayfa. Veriler yalnızca textContent ile yazılır (betik enjeksiyonu yok).
"use strict";

// Arayüz dili: /_admin/i18n.js sunucunun dilini ve kataloğunu (Türkçe metin → çeviri) verir; Türkçede katalog boş.
const I18N = window.DS_I18N || {};
function T(text) { return Object.prototype.hasOwnProperty.call(I18N, text) ? I18N[text] : text; }
document.documentElement.lang = window.DS_LANG || "tr";
document.title = T("DroidServer Paneli");

const state = { token: sessionStorage.getItem("ds-token"), info: null, tab: null, abort: null };
const main = document.getElementById("main");
const nav = document.getElementById("nav");

function el(tag, attrs, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === "onclick" || k === "onchange" || k === "onsubmit") e[k] = v;
    else if (k === "class") e.className = v;
    else if (v !== undefined && v !== null && v !== false) e.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat()) if (c !== null && c !== undefined) e.append(c instanceof Node ? c : String(c));
  return e;
}

function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }

async function api(path, opts = {}) {
  const headers = Object.assign({}, opts.headers || {});
  if (state.token) headers.Authorization = "Bearer " + state.token;
  let body = opts.body;
  if (body !== undefined && !(body instanceof FormData) && typeof body !== "string") {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(body);
  }
  const res = await fetch(path, { method: opts.method || "GET", headers, body, signal: opts.signal });
  if (res.status === 401 && path !== "/_admin/api/pair") { logout(false); throw new Error(T("Oturum sona erdi")); }
  if (opts.raw) return res;
  const text = await res.text();
  const data = text ? (() => { try { return JSON.parse(text); } catch (e) { return text; } })() : null;
  if (!res.ok) {
    const details = data && data.details ? "\n" + Object.entries(data.details).map(([k, v]) => "• " + k + ": " + v).join("\n") : "";
    throw new Error(((data && data.message) || res.statusText) + details);
  }
  return data;
}

const U = "_users";
const base = () => state.info.basePath.replace(/\/$/, "");

// Uzman modunda uç yolları ve sistem alanı adları değişebilir; sunucu etkin hallerini /info ile bildirir.
function pathOf(p, params) {
  if (p === null) return null;
  return base() + String(p).replace(/\{(\w+)\}/g, (_, k) => encodeURIComponent((params || {})[k]));
}
function gp(key, params, fallback) {
  const paths = state.info.paths || {};
  return pathOf(key in paths ? paths[key] : fallback, params);
}
function cp(c, key, params) {
  const m = ((state.info.paths || {}).collections || {})[c];
  const def = { list: "/collections/{c}/records", view: "/collections/{c}/records/{id}", create: "/collections/{c}/records",
    update: "/collections/{c}/records/{id}", delete: "/collections/{c}/records/{id}", export: "/collections/{c}/export",
    import: "/collections/{c}/import", aggregate: "/collections/{c}/aggregate" }[key];
  // Geçmiş ve çöp kutusu uçları yalnızca koleksiyonda açıksa vardır (/info'da yolları; yoksa null).
  return pathOf(m && key in m ? m[key] : (def ? def.replace("{c}", c) : null), params);
}
// Sistem alanının API adı; kaldırıldıysa null.
function nm(coll, name) {
  const m = (state.info.names || {})[coll] || {};
  return name in m ? m[name] : name;
}

function errorBox(e) { return el("p", { class: "error" }, String(e.message || e)); }

async function guard(target, fn) {
  try { await fn(); } catch (e) { target.append(errorBox(e)); }
}

// ---- giriş

async function showLogin(message) {
  nav.hidden = true;
  clear(main);
  const input = el("input", { inputmode: "numeric", maxlength: "6", autocomplete: "one-time-code", placeholder: "000000" });
  // Panel doğrulayıcı kodu da istiyorsa ikinci alan.
  const login = await api("/_admin/api/login").catch(() => ({}));
  const totp = login.totp ? el("input", { inputmode: "numeric", maxlength: "6", autocomplete: "off", placeholder: T("doğrulayıcı kodu") }) : null;
  const status = el("p", { class: "error" }, message || "");
  const form = el("form", {
    onsubmit: async (ev) => {
      ev.preventDefault();
      status.textContent = "";
      try {
        const r = await api("/_admin/api/pair", { method: "POST", body: { code: input.value.trim(), totp: totp ? totp.value.trim() : undefined } });
        state.token = r.token;
        sessionStorage.setItem("ds-token", r.token);
        await start();
      } catch (e) { status.textContent = e.message; }
    },
  }, input, totp, el("div", { class: "row" }, el("button", { class: "btn", type: "submit" }, T("Giriş"))));
  main.append(el("div", { class: "card login" },
    el("h2", {}, T("Panele giriş")),
    el("p", { class: "muted" }, T("Telefonda DroidServer → Durum → Web paneli → \"Eşleştirme kodu oluştur\". Kod 5 dakika geçerlidir ve bir kez kullanılır.") +
      (totp ? T(" Ayrıca doğrulayıcı uygulamanızdaki DroidServer panel kodunu girin.") : "")),
    form, status));
  // QR ile giriş: telefondaki "Bağlan (QR)" ekranı adrese #pair=<kod> ekler (parça sunucuya gitmez); kod adresten silinir.
  const paired = /^#pair=(\d{6})$/.exec(location.hash);
  if (paired) {
    history.replaceState(null, "", location.pathname + location.search);
    input.value = paired[1];
    if (totp) totp.focus(); else form.requestSubmit();
    return;
  }
  input.focus();
}

async function logout(callServer = true) {
  if (callServer && state.token) { try { await api("/_admin/api/logout", { method: "POST" }); } catch (e) { /* yok say */ } }
  state.token = null;
  sessionStorage.removeItem("ds-token");
  if (state.abort) state.abort.abort();
  showLogin(callServer ? "" : T("Oturum sona erdi; yeniden giriş yapın."));
}

// ---- gezinme

// İlk sekme açılış ekranıdır.
const TABS = [
  ["metrics", T("Metrikler"), showMetrics],
  ["records", T("Kayıtlar"), showRecords],
  ["users", T("Kullanıcılar"), showUsers],
  ["logs", T("Loglar"), showLogs],
  ["audit", T("Denetim"), showAudit],
  ["jobs", T("İşler"), showJobs],
  ["views", T("Görünümler"), showViews],
  ["automation", T("Otomasyon"), showAutomation],
  ["services", T("Servisler"), showServices],
  ["api", "API", showApi],
  ["backups", T("Yedekler"), showBackups],
  ["schema", T("Şema"), showSchema],
  ["config", T("Konfigürasyon"), showConfig],
];

function renderNav() {
  clear(nav);
  nav.hidden = false;
  for (const [id, title] of TABS) {
    if (id === "users" && !state.info.users) continue;
    if (id === "jobs" && !state.info.jobs) continue;
    if (id === "views" && !state.info.views) continue;
    if (["logs", "backups", "schema", "config", "automation", "services"].includes(id) && !state.info.host) continue;
    nav.append(el("button", { class: state.tab === id ? "active" : "", onclick: () => openTab(id) }, title));
  }
  nav.append(el("button", { class: "logout", onclick: () => logout(true) }, T("Çıkış")));
}

function openTab(id) {
  if (state.abort) { state.abort.abort(); state.abort = null; }
  state.tab = id;
  location.hash = id;
  renderNav();
  clear(main);
  const tab = TABS.find((t) => t[0] === id) || TABS[0];
  main.append(el("h1", { class: "page-title" }, tab[1]));
  tab[2]().catch((e) => main.append(errorBox(e)));
}

async function start() {
  state.info = await api("/_admin/api/info");
  document.getElementById("title").textContent = state.info.name;
  document.getElementById("subtitle").textContent = "DroidServer";
  document.title = state.info.name + " · DroidServer";
  const hash = location.hash.slice(1);
  openTab(hash && !hash.startsWith("pair=") ? hash : TABS[0][0]);
}

// ---- kayıtlar

async function showRecords() {
  const schema = await api(gp("schema", null, "/_schema"));
  if (!schema.length) { main.append(el("p", { class: "muted" }, T("Koleksiyon yok."))); return; }
  const select = el("select", {}, schema.map((c) => el("option", { value: c.name }, c.name)));
  const filter = el("input", { placeholder: T("filtre, örn. q=süt veya title[like]=süt&done=false"), size: "40" });
  const out = el("div");
  let page = 1;
  const load = async () => {
    clear(out);
    const c = schema.find((x) => x.name === select.value);
    const idKey = nm(c.name, "id");
    const q = "page=" + page + "&perPage=20" + (idKey ? "&sort=-" + idKey : "") + (filter.value.trim() ? "&" + filter.value.trim() : "");
    await guard(out, async () => {
      const list = cp(c.name, "list");
      if (!list) { out.append(el("p", { class: "muted" }, T("Bu koleksiyonun listeleme ucu kapalı."))); return; }
      const r = await api(list + "?" + q);
      const cols = [idKey, ...c.fields.map((f) => f.name), nm(c.name, "created_at")].filter((k) => k);
      const rows = r.items.map((item) => {
        const id = idKey ? item[idKey] : null;
        const del = id !== null ? cp(c.name, "delete", { id }) : null;
        return el("tr", {},
          cols.map((k) => el("td", { title: cell(item[k]) }, cell(item[k]))),
          el("td", {},
            id !== null && cp(c.name, "update", { id }) ? el("button", { class: "ghost", onclick: () => editRecord(c, item, load) }, T("Düzenle")) : null, " ",
            id !== null && cp(c.name, "history", { id }) ? el("button", { class: "ghost", onclick: () => showHistory(c, id, load) }, T("Geçmiş")) : null, " ",
            del ? el("button", { class: "ghost danger", onclick: async () => {
              if (!confirm("#" + id + T(" silinsin mi?"))) return;
              await guard(out, async () => { await api(del, { method: "DELETE" }); await load(); });
            } }, T("Sil")) : null));
      });
      out.append(
        el("p", { class: "muted" }, r.totalItems + T(" kayıt · sayfa ") + r.page + "/" + Math.max(1, r.totalPages)),
        el("div", { class: "table-wrap" }, el("table", {}, el("thead", {}, el("tr", {}, cols.map((k) => el("th", {}, k)), el("th", {}))), el("tbody", {}, rows))),
        el("div", { class: "row" },
          el("button", { class: "ghost", disabled: page <= 1, onclick: () => { page--; load(); } }, T("‹ Önceki")),
          el("button", { class: "ghost", disabled: page >= r.totalPages, onclick: () => { page++; load(); } }, T("Sonraki ›"))));
    });
  };
  const trashButton = el("button", { class: "ghost", onclick: () => showTrash(schema.find((x) => x.name === select.value), load) }, T("Çöp kutusu"));
  const syncTrash = () => { trashButton.hidden = !cp(select.value, "trash"); };
  syncTrash();
  select.onchange = () => { page = 1; clear(importBox); syncTrash(); load(); };
  const live = el("span", { class: "muted" });
  const importBox = el("div");
  const exportFmt = el("select", {}, [["csv", "CSV"], ["excel", T("CSV (Excel, ;)")], ["json", "JSON"]].map(([v, t]) => el("option", { value: v }, t)));
  const doExport = () => guard(out, async () => {
    const f = exportFmt.value;
    const q = "format=" + (f === "json" ? "json" : "csv") + (f === "excel" ? "&delimiter=semicolon" : "") + (filter.value.trim() ? "&" + filter.value.trim() : "");
    const path = cp(select.value, "export");
    if (!path) throw new Error(T("Bu koleksiyonun dışa aktarma ucu kapalı"));
    const res = await api(path + "?" + q, { raw: true });
    if (!res.ok) { const d = await res.json().catch(() => null); throw new Error((d && d.message) || res.statusText); }
    const name = (/filename="([^"]+)"/.exec(res.headers.get("Content-Disposition") || "") || [])[1] || select.value + "." + (f === "json" ? "json" : "csv");
    saveBlob(await res.blob(), name);
  });
  const fileInput = el("input", { type: "file", accept: ".csv,.json,text/csv,application/json", class: "hidden" });
  fileInput.onchange = async () => {
    const file = fileInput.files[0];
    fileInput.value = "";
    if (file) await guard(importBox, () => importFlow(schema.find((x) => x.name === select.value), file, importBox, load));
  };
  main.append(el("div", { class: "card" },
    el("div", { class: "row" }, select, filter,
      el("button", { class: "ghost", onclick: () => { page = 1; load(); } }, T("Ara")),
      el("button", { class: "btn", onclick: () => editRecord(schema.find((x) => x.name === select.value), null, load) }, T("Yeni kayıt")),
      live),
    el("div", { class: "row" }, exportFmt,
      el("button", { class: "ghost", onclick: doExport }, T("Dışa aktar")),
      el("button", { class: "ghost", onclick: () => fileInput.click() }, T("İçe aktar…")), fileInput,
      trashButton),
    importBox,
    out));
  await load();
  if (state.info.realtime) liveRecords(schema.map((c) => c.name), () => select.value, load, live);
}

// Geçmiş ve çöp kutusu pencereleri: [render] listeyi yükleyip satırları üretir; işlemden sonra yeniden çağrılır.
function listDialog(title, render) {
  const body = el("div");
  const dialog = el("dialog", { class: "wide" }, el("h3", {}, title), body,
    el("div", { class: "row" }, el("button", { class: "ghost", onclick: () => { dialog.close(); dialog.remove(); } }, T("Kapat"))));
  const refresh = async () => { clear(body); await guard(body, () => render(body, refresh)); };
  document.body.append(dialog);
  dialog.showModal();
  refresh();
}

const ACTIONS = { create: T("oluşturuldu"), update: T("güncellendi"), delete: T("silindi"), restore: T("geri yüklendi"), revert: T("sürüme dönüldü") };

function showHistory(c, id, reload) {
  listDialog(c.name + " #" + id + T(" · geçmiş"), async (body, refresh) => {
    const r = await api(cp(c.name, "history", { id }) + "?perPage=100");
    if (!r.items.length) { body.append(el("p", { class: "muted" }, T("Geçmiş yok (geçmiş bu kayıttan sonra açılmış olabilir)."))); return; }
    body.append(el("div", { class: "table-wrap" }, el("table", {},
      el("thead", {}, el("tr", {}, [T("Sürüm"), T("İşlem"), T("Zaman"), T("Kim"), T("Değerler"), ""].map((h) => el("th", {}, h)))),
      el("tbody", {}, r.items.map((h) => el("tr", {},
        el("td", {}, String(h.version)), el("td", {}, ACTIONS[h.action] || h.action),
        el("td", {}, new Date(h.at).toLocaleString()), el("td", {}, h.actor || T("sunucu")),
        el("td", { title: cell(h.data) }, cell(h.data)),
        el("td", {}, h.action !== "delete" && h.data && cp(c.name, "revert", { id, version: h.version }) ? el("button", { class: "ghost", onclick: async () => {
          if (!confirm("#" + id + " " + h.version + T(". sürüme döndürülsün mü? (dosya ve hesaplanan alanlar hariç)"))) return;
          await guard(body, async () => { await api(cp(c.name, "revert", { id, version: h.version }), { method: "POST" }); await refresh(); reload(); });
        } }, T("Bu sürüme dön")) : null)))))));
  });
}

function showTrash(c, reload) {
  listDialog(c.name + T(" · çöp kutusu"), async (body, refresh) => {
    const r = await api(cp(c.name, "trash") + "?perPage=100");
    if (!r.items.length) { body.append(el("p", { class: "muted" }, T("Çöp kutusu boş."))); return; }
    body.append(el("p", { class: "muted" }, r.totalItems + T(" kayıt; süre dolunca kalıcı silinir. Bağlı kayıtları geri yüklemeden önce bağlandıkları kaydı geri yükleyin.")),
      el("div", { class: "table-wrap" }, el("table", {},
        el("thead", {}, el("tr", {}, [T("Kimlik"), T("Silinme"), T("Kim"), T("Kalıcı silinme"), T("Değerler"), ""].map((h) => el("th", {}, h)))),
        el("tbody", {}, r.items.map((t) => el("tr", {},
          el("td", {}, String(t.id)), el("td", {}, new Date(t.deletedAt).toLocaleString()), el("td", {}, t.actor || T("sunucu")),
          el("td", {}, new Date(t.expiresAt).toLocaleDateString()), el("td", { title: cell(t.data) }, cell(t.data)),
          el("td", {},
            el("button", { class: "ghost", onclick: () => guard(body, async () => {
              await api(cp(c.name, "restore", { id: t.id }), { method: "POST" }); await refresh(); reload();
            }) }, T("Geri yükle")), " ",
            el("button", { class: "ghost danger", onclick: async () => {
              if (!confirm("#" + t.id + T(" dosyalarıyla kalıcı silinsin mi? Geri alınamaz."))) return;
              await guard(body, async () => { await api(cp(c.name, "purge", { id: t.id }), { method: "DELETE" }); await refresh(); });
            } }, T("Kalıcı sil")))))))));
  });
}

function saveBlob(blob, name) {
  const url = URL.createObjectURL(blob);
  const a = el("a", { href: url, download: name });
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

// İçe aktarma: önce denetlenir (dryRun), sütunlar alanlara eşlenir; hatasızsa tek işlemde yazılır.
async function importFlow(c, file, box, reload) {
  clear(box);
  const text = await file.text();
  const format = /\.json$/i.test(file.name) || text.replace(/^\uFEFF/, "").trimStart().startsWith("[") ? "json" : "csv";
  let mapping = null;
  let mode = "insert";
  const run = async (dryRun) => {
    const q = "format=" + format + "&mode=" + mode + (dryRun ? "&dryRun=true" : "") + (mapping ? "&mapping=" + encodeURIComponent(JSON.stringify(mapping)) : "");
    const path = cp(c.name, "import");
    if (!path) throw new Error(T("Bu koleksiyonun içe aktarma ucu kapalı"));
    const res = await api(path + "?" + q, {
      method: "POST", body: text, raw: true, headers: { "Content-Type": format === "json" ? "application/json" : "text/csv" },
    });
    const data = await res.json().catch(() => null);
    if (!data || !Array.isArray(data.columns)) throw new Error((data && data.message) || res.statusText);
    return data;
  };
  const targets = [nm(c.name, "id"), ...c.fields.filter((f) => f.type !== "file").map((f) => f.name)].filter((t) => t);
  const render = (r) => {
    clear(box);
    mapping = r.mapping;
    const modeSel = el("select", {}, [["insert", T("Hepsini yeni kayıt olarak ekle")], ["upsert", T("id'si olanı güncelle, olmayanı ekle")]]
      .map(([v, t]) => el("option", { value: v, selected: v === mode }, t)));
    const recheck = () => guard(box, async () => render(await run(true)));
    modeSel.onchange = () => { mode = modeSel.value; recheck(); };
    const rows = r.columns.map((col) => {
      const sel = el("select", {}, el("option", { value: "" }, T("— alma —")), targets.map((t) => el("option", { value: t, selected: r.mapping[col] === t }, t)));
      sel.onchange = () => { mapping = Object.assign({}, mapping, { [col]: sel.value || null }); recheck(); };
      return el("tr", {}, el("td", {}, col), el("td", {}, sel));
    });
    const ok = r.errors.length === 0 && r.total > 0;
    box.append(el("div", { class: "card" },
      el("h3", {}, T("İçe aktar: ") + file.name + " (" + format.toUpperCase() + ")"),
      el("p", { class: "muted" }, T("Hatalı satır varsa hiçbiri yazılmaz. Boş hücre alanı göndermez. Dosya alanları ve olaylar (webhook, canlı) yok.")),
      el("div", { class: "row" }, modeSel),
      el("div", { class: "table-wrap" }, el("table", {}, el("thead", {}, el("tr", {}, el("th", {}, T("sütun")), el("th", {}, T("alan")))), el("tbody", {}, rows))),
      el("p", {}, r.total + T(" satır: ") + r.created + T(" yeni, ") + r.updated + T(" güncellenecek")),
      r.errors.slice(0, 20).map((e) => el("p", { class: "error" }, T("Satır ") + e.row + ": " + e.message + (e.details ? " (" + Object.entries(e.details).map(([k, v]) => k + ": " + v).join(", ") + ")" : ""))),
      r.sample.length ? el("pre", {}, r.sample.slice(0, 3).map((x) => JSON.stringify(x)).join("\n")) : null,
      el("div", { class: "row" },
        el("button", { class: "btn", disabled: !ok, onclick: () => guard(box, async () => {
          const done = await run(false);
          if (!done.applied) { render(done); return; }
          clear(box);
          box.append(el("p", {}, T("İçe aktarıldı: ") + done.created + T(" yeni, ") + done.updated + T(" güncellendi.")));
          await reload();
        }) }, T("İçe aktar")),
        el("button", { class: "ghost", onclick: () => clear(box) }, T("Vazgeç")))));
  };
  render(await run(true));
}

// Canlı abonelik açıksa tablo, seçili koleksiyonda değişiklik olunca kendiliğinden yenilenir.
async function liveRecords(names, current, reload, badge) {
  const ctrl = new AbortController();
  state.abort = ctrl;
  let timer = null;
  while (!ctrl.signal.aborted && state.tab === "records") {
    try {
      badge.textContent = T("● canlı");
      const rt = gp("realtime", null, "/realtime");
      if (!rt) { badge.textContent = ""; return; }
      await readSse(rt + "?collections=" + encodeURIComponent(names.join(",")), ctrl, (data) => {
        let e;
        try { e = JSON.parse(data); } catch (err) { return; }
        if (e.collection !== current()) return;
        clearTimeout(timer);
        timer = setTimeout(reload, 300); // art arda değişikliklerde tek yenileme
      });
    } catch (e) {
      if (ctrl.signal.aborted) return;
      badge.textContent = T("canlı bağlantı koptu; yeniden deneniyor");
    }
    if (ctrl.signal.aborted) return;
    await new Promise((r) => setTimeout(r, 3000));
  }
}

// Başlık gönderebilmek için EventSource yerine fetch akışı; adsız (message) olayların verisini iletir.
async function readSse(path, ctrl, onData) {
  const res = await api(path, { raw: true, signal: ctrl.signal });
  if (!res.ok) throw new Error("HTTP " + res.status);
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  for (;;) {
    const { value, done } = await reader.read();
    if (done) return;
    buffer += decoder.decode(value, { stream: true });
    let i;
    while ((i = buffer.indexOf("\n\n")) >= 0) {
      const block = buffer.slice(0, i);
      buffer = buffer.slice(i + 2);
      const lines = block.split("\n");
      if (lines.some((l) => l.startsWith("event:"))) continue;
      const data = lines.filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trimStart()).join("\n");
      if (data) onData(data);
    }
  }
}

function cell(v) {
  if (v === null || v === undefined) return "";
  return typeof v === "object" ? JSON.stringify(v) : String(v);
}

function editRecord(c, item, done) {
  const body = {};
  for (const f of c.fields) if (f.type !== "file" && !f.computed) body[f.name] = item ? item[f.name] : (f.default !== undefined ? f.default : null);
  const hints = c.fields.filter(f => f.options).map(f => f.name + ": " + f.options.join(", ")).join(" · ");
  const text = el("textarea", {}, JSON.stringify(body, null, 2));
  text.style.minHeight = "260px";
  const status = el("p", { class: "error" });
  const dialog = el("dialog", {},
    el("h3", {}, item ? c.name + " #" + item[nm(c.name, "id")] : T("Yeni ") + c.name),
    el("p", { class: "muted" }, T("Alanlar JSON olarak; dosya ve hesaplanan alanlar burada düzenlenemez.") + (hints ? T(" Seçenekler — ") + hints : "")),
    text, status,
    el("div", { class: "row" },
      el("button", { class: "btn", onclick: async () => {
        status.textContent = "";
        try {
          const data = JSON.parse(text.value);
          const path = item ? cp(c.name, "update", { id: item[nm(c.name, "id")] }) : cp(c.name, "create");
          if (!path) throw new Error(T("Bu işlemin ucu kapalı"));
          await api(path, { method: item ? "PATCH" : "POST", body: data });
          dialog.close(); dialog.remove(); done();
        } catch (e) { status.textContent = e.message; }
      } }, T("Kaydet")),
      el("button", { class: "ghost", onclick: () => { dialog.close(); dialog.remove(); } }, T("Vazgeç"))));
  document.body.append(dialog);
  dialog.showModal();
}

// ---- kullanıcılar

// Kullanıcı, yönetici ve sihirbazda tanımlanan özel roller.
function roleOptions() {
  return [["user", T("Kullanıcı")], ["admin", T("Yönetici")]].concat((state.info.roles || []).map((r) => [r.name, r.title]));
}

function roleTitle(role) {
  const o = roleOptions().find(([name]) => name === role);
  return o ? o[1] : cell(role);
}

function roleSelect(current, onPick) {
  const select = el("select", { title: T("Rol"), onchange: () => { if (select.value !== current) onPick(select.value); } },
    roleOptions().map(([name, title]) => el("option", { value: name, selected: name === current }, title)));
  return select;
}

async function showUsers() {
  const search = el("input", { placeholder: T("e-postada ara") });
  const out = el("div");
  const load = async () => {
    clear(out);
    await guard(out, async () => {
      const list = gp("auth.users.list", null, "/auth/users");
      if (!list) { out.append(el("p", { class: "muted" }, T("Kullanıcı listeleme ucu kapalı."))); return; }
      const r = await api(list + "?perPage=100&" + (nm(U, "email") || "email") + "=" + encodeURIComponent(search.value.trim()));
      out.append(el("p", { class: "muted" }, r.totalItems + T(" kullanıcı")),
        el("div", { class: "table-wrap" }, el("table", {},
          el("thead", {}, el("tr", {}, ["id", T("e-posta"), T("rol"), T("doğrulandı"), T("kayıt"), ""].map((h) => el("th", {}, h)))),
          el("tbody", {}, r.items.map((raw) => {
            // Uzman modunda yeniden adlandırılan alanlar özgün adlarıyla okunur.
            const u = {};
            for (const k of ["id", "email", "role", "verified", "created_at"]) { const a = nm(U, k); u[k] = a ? raw[a] : undefined; }
            const roleKey = nm(U, "role"), passKey = nm(U, "password");
            const who = u.email || ("#" + u.id);
            const del = u.id !== undefined ? gp("auth.users.delete", { id: u.id }, "/auth/users/{id}") : null;
            return el("tr", {},
              el("td", {}, cell(u.id)), el("td", {}, cell(u.email)), el("td", {}, roleTitle(u.role)), el("td", {}, u.verified === undefined ? "" : u.verified ? T("evet") : T("hayır")),
              el("td", {}, u.created_at === undefined ? "" : String(u.created_at).slice(0, 10)),
              el("td", {},
                roleKey && u.id !== undefined ? roleSelect(u.role, (role) => userAction(u.id, { [roleKey]: role })) : null, " ",
                passKey && u.id !== undefined ? el("button", { class: "ghost", onclick: () => {
                  const p = prompt(who + T(" için yeni parola (tüm oturumları kapanır):"));
                  if (p) userAction(u.id, { [passKey]: p });
                } }, T("Parola")) : null, " ",
                del ? el("button", { class: "ghost danger", onclick: async () => {
                  if (!confirm(who + T(" silinsin mi? Bağlı kayıtlar onDelete ayarına göre etkilenir."))) return;
                  await guard(out, async () => { await api(del, { method: "DELETE" }); await load(); });
                } }, T("Sil")) : null));
          })))));
    });
  };
  const userAction = (id, body) => guard(out, async () => {
    const path = gp("auth.users.update", { id }, "/auth/users/{id}");
    if (!path) throw new Error(T("Kullanıcı güncelleme ucu kapalı"));
    await api(path, { method: "PATCH", body }); await load();
  });
  main.append(el("div", { class: "card" }, el("div", { class: "row" }, search, el("button", { class: "ghost", onclick: load }, T("Ara"))), out));
  await load();
  if (state.info.teams) await showTeams();
}

// Takımlar: panel yönetici olarak tüm takımları görür (uygulamadaki Sunucu → Takımlar gibi).
async function showTeams() {
  const out = el("div");
  const name = el("input", { placeholder: T("Yeni takım adı") });
  const load = () => guard(out, async () => {
    clear(out);
    const r = await api(gp("teams.list", null, "/teams"));
    if (!r.items.length) { out.append(el("p", { class: "muted" }, T("Henüz takım yok."))); return; }
    out.append(el("div", { class: "table-wrap" }, el("table", {},
      el("thead", {}, el("tr", {}, ["id", T("Ad"), T("Üye"), ""].map((h) => el("th", {}, h)))),
      el("tbody", {}, r.items.map((t) => el("tr", {},
        el("td", {}, String(t.id)), el("td", {}, t.name), el("td", {}, String(t.memberCount)),
        el("td", {},
          el("button", { class: "ghost", onclick: () => teamDialog(t, load) }, T("Üyeler")), " ",
          el("button", { class: "ghost danger", onclick: async () => {
            if (!confirm("'" + t.name + T("' silinsin mi? Üyelikler silinir; takıma bağlı kayıtlar takım alanının silme davranışına göre silinir veya boşalır."))) return;
            await guard(out, async () => { await api(gp("teams.delete", { id: t.id }, "/teams/{id}"), { method: "DELETE" }); await load(); });
          } }, T("Sil")))))))));
  });
  const create = el("button", { class: "btn", onclick: () => guard(out, async () => {
    await api(gp("teams.create", null, "/teams"), { method: "POST", body: { name: name.value.trim() } });
    name.value = "";
    await load();
  }) }, T("Kur"));
  main.append(el("div", { class: "card" }, el("h3", {}, T("Takımlar")),
    el("p", { class: "muted" }, T("Kullanıcılar da kendi takımlarını /teams uçlarıyla yönetir (takımı kuran sahibi olur).")),
    el("div", { class: "row" }, name, create), out));
  await load();
}

function teamDialog(team, reload) {
  listDialog(team.name, async (body, refresh) => {
    const t = await api(gp("teams.get", { id: team.id }, "/teams/{id}"));
    const act = (fn) => guard(body, async () => { await fn(); await refresh(); await reload(); });
    const member = (m) => ({ id: team.id, user: m.user });
    body.append(el("div", { class: "table-wrap" }, el("table", {},
      el("thead", {}, el("tr", {}, [T("e-posta"), T("rol"), ""].map((h) => el("th", {}, h)))),
      el("tbody", {}, (t.members || []).map((m) => el("tr", {},
        el("td", {}, m.email || "#" + m.user), el("td", {}, m.role === "owner" ? T("sahip") : T("üye")),
        el("td", {},
          el("button", { class: "ghost", onclick: () => act(() => api(gp("teams.updateMember", member(m), "/teams/{id}/members/{user}"),
            { method: "PATCH", body: { role: m.role === "owner" ? "member" : "owner" } })) }, m.role === "owner" ? T("Üye yap") : T("Sahip yap")), " ",
          el("button", { class: "ghost danger", onclick: () => act(() => api(gp("teams.removeMember", member(m), "/teams/{id}/members/{user}"), { method: "DELETE" })) }, T("Çıkar")))))))));
    const email = el("input", { type: "email", placeholder: T("Üye e-postası") });
    body.append(el("div", { class: "row" }, email, el("button", { class: "btn", onclick: () => act(() =>
      api(gp("teams.addMember", { id: team.id }, "/teams/{id}/members"), { method: "POST", body: { email: email.value.trim() } })) }, T("Ekle"))));
  });
}

// ---- metrikler

// Sekme açıkken 10 saniyede bir yenilenir (sekme arka plandayken beklenir); bu okumalar istek sayılarına eklenmez.
// Sağlık uyarıları: süren sorunlar, son gönderilenler, deneme uyarısı.
async function renderAlerts(box) {
  const path = gp("alerts", null, "/_alerts");
  if (!path) return;
  const r = await api(path);
  clear(box);
  if (!r.enabled) return;
  const time = (a) => new Date(a.at).toLocaleString();
  const test = el("button", { class: "ghost", onclick: () => guard(box, async () => {
    await api(gp("alerts.test", null, "/_alerts/test"), { method: "POST" });
    await renderAlerts(box);
  }) }, T("Deneme uyarısı gönder"));
  box.append(el("div", { class: "card" }, el("h3", {}, T("Sağlık")),
    r.active.length
      ? el("ul", {}, r.active.map((a) => el("li", { class: a.critical ? "error" : "" }, el("strong", {}, a.title), " — " + a.message)))
      : el("p", { class: "muted" }, T("Her şey yolunda.")),
    r.recent.length ? el("details", {}, el("summary", {}, T("Son uyarılar (") + r.recent.length + ")"),
      el("ul", {}, r.recent.slice(0, 20).map((a) => el("li", {}, time(a) + " · " + a.title + (a.resolved ? "" : " — " + a.message))))) : "",
    el("div", { class: "row" }, test)));
}

async function showMetrics() {
  const box = el("div");
  const alertsBox = el("div");
  const note = el("p", { class: "muted" }, T("Her 10 saniyede bir güncellenir."));
  main.append(note, alertsBox, box);
  const ctrl = new AbortController();
  state.abort = ctrl;
  let first = true;
  while (!ctrl.signal.aborted && state.tab === "metrics") {
    if (!document.hidden || first) {
      try {
        const mp = gp("metrics", null, "/_metrics");
        if (!mp) { note.textContent = T("Metrik ucu kapalı (uzman modu)."); return; }
        const m = await api(mp, { signal: ctrl.signal });
        if (ctrl.signal.aborted) return;
        await renderAlerts(alertsBox).catch(() => {});
        clear(box);
        renderMetrics(box, m);
        note.textContent = T("Her 10 saniyede bir güncellenir · son: ") + new Date().toLocaleTimeString();
      } catch (e) {
        if (ctrl.signal.aborted) return;
        if (first) throw e;
        note.textContent = T("Güncellenemedi (") + (e.message || e) + T("); yeniden denenecek.");
      }
      first = false;
    }
    await new Promise((resolve) => {
      const t = setTimeout(resolve, 10_000);
      ctrl.signal.addEventListener("abort", () => { clearTimeout(t); resolve(); }, { once: true });
    });
  }
}

function renderMetrics(box, m) {
  const by = m.requests.byStatus;
  const d = m.device || {};
  const stat = (label, value) => el("div", { class: "stat" }, el("b", {}, value), el("span", { class: "muted" }, label));
  const hours = Math.floor(m.uptimeSec / 3600), mins = Math.floor((m.uptimeSec % 3600) / 60);
  box.append(el("div", { class: "stats" },
    stat(T("toplam istek"), m.requests.total),
    stat("2xx / 4xx / 5xx", by["2xx"] + " / " + by["4xx"] + " / " + by["5xx"]),
    stat(T("süren istek"), m.requests.active),
    stat(T("çalışma süresi"), hours + T(" sa ") + mins + T(" dk")),
    d.batteryPercent !== undefined ? stat(T("pil") + (d.charging ? T(" (şarjda)") : ""), "%" + d.batteryPercent) : null,
    d.batteryTempC !== undefined ? stat(T("pil sıcaklığı"), d.batteryTempC.toFixed(1) + " °C") : null,
    d.freeStorageBytes !== undefined ? stat(T("boş depolama"), (d.freeStorageBytes / 1073741824).toFixed(1) + " GB") : null));
  box.append(el("div", { class: "card" }, el("h3", {}, T("Son 60 dakika: dakikada istek")), chart(m.minutes.map((x) => x.requests))));
  box.append(el("div", { class: "card table-wrap" }, el("table", {},
    el("thead", {}, el("tr", {}, [T("uç"), T("istek"), "5xx", "p50 ms", "p95 ms"].map((h) => el("th", {}, h)))),
    el("tbody", {}, m.routes.map((r) => el("tr", {}, el("td", {}, r.route), el("td", {}, r.count), el("td", {}, r.errors),
      el("td", {}, r.p50Ms === null ? "" : r.p50Ms), el("td", {}, r.p95Ms === null ? "" : r.p95Ms)))))));
}

function chart(values) {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("class", "chart");
  svg.setAttribute("viewBox", "0 0 600 120");
  svg.setAttribute("preserveAspectRatio", "none");
  const max = Math.max(1, ...values);
  const w = 600 / Math.max(values.length, 1);
  values.forEach((v, i) => {
    const r = document.createElementNS(ns, "rect");
    const h = (v / max) * 110;
    r.setAttribute("x", String(i * w + 1));
    r.setAttribute("y", String(120 - h));
    r.setAttribute("width", String(Math.max(w - 2, 1)));
    r.setAttribute("height", String(h));
    const t = document.createElementNS(ns, "title");
    t.textContent = v + T(" istek");
    r.append(t);
    svg.append(r);
  });
  return svg;
}

// ---- API (OpenAPI dokümanından)

async function showApi() {
  const op = gp("openapi", null, "/_openapi.json");
  if (!op) { main.append(el("p", { class: "muted" }, T("OpenAPI ucu kapalı (uzman modu)."))); return; }
  const doc = await api(op);
  const schemas = (doc.components && doc.components.schemas) || {};
  const origin = location.origin + base();
  const download = el("button", { class: "ghost", onclick: () => {
    const url = URL.createObjectURL(new Blob([JSON.stringify(doc, null, 2)], { type: "application/json" }));
    const a = el("a", { href: url, download: "openapi.json" });
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } }, T("openapi.json indir"));
  const sdk = (lang, file) => el("button", { class: "ghost", onclick: () => guard(main, async () => {
    const path = gp("sdk", { lang }, "/_sdk/{lang}");
    if (!path) throw new Error(T("SDK ucu kapalı (uzman modu)"));
    const res = await api(path, { raw: true });
    if (!res.ok) { const d = await res.json().catch(() => null); throw new Error((d && d.message) || res.statusText); }
    saveBlob(await res.blob(), file);
  }) }, file + T(" indir"));
  main.append(el("div", { class: "card" },
    el("p", {}, el("b", {}, doc.info.title), " · OpenAPI " + doc.openapi),
    el("p", { class: "muted" }, T("Adres: ") + location.origin + op + T(" — herkes kendi yetkisine göre görür (burada yönetici görünümü). ") +
      T("Postman, Insomnia veya Swagger Editor'e içe aktarılabilir, istemci kod üreticileriyle kullanılabilir. ") +
      T("Tipli istemci (SDK): koleksiyon tipleri, CRUD, giriş, dosya ve canlı abonelik içeren tek dosya; konfigürasyon değişince yeniden indirin.")),
    el("div", { class: "row" }, download, sdk("ts", "droidserver.ts"), sdk("kotlin", "DroidServerClient.kt"))));

  const byTag = new Map();
  for (const [path, methods] of Object.entries(doc.paths)) {
    for (const [method, op] of Object.entries(methods)) {
      const tag = (op.tags && op.tags[0]) || T("Diğer");
      if (!byTag.has(tag)) byTag.set(tag, []);
      byTag.get(tag).push({ path, method, op });
    }
  }
  for (const [tag, ops] of byTag) {
    const card = el("div", { class: "card" }, el("h3", {}, tag));
    for (const { path, method, op } of ops) {
      const locked = op.security ? "🔒 " : "";
      const rows = [];
      for (const p of op.parameters || []) {
        rows.push(el("tr", {}, el("td", {}, p.name), el("td", {}, p.in), el("td", {}, (p.schema && p.schema.type) || ""), el("td", {}, p.description || "")));
      }
      const bodyRef = op.requestBody && op.requestBody.content["application/json"] && op.requestBody.content["application/json"].schema;
      const bodySchema = bodyRef && bodyRef.$ref ? schemas[bodyRef.$ref.split("/").pop()] : bodyRef;
      const bodyRows = bodySchema && bodySchema.properties ? Object.entries(bodySchema.properties).map(([k, v]) =>
        el("tr", {}, el("td", {}, k + ((bodySchema.required || []).includes(k) ? " *" : "")), el("td", {}, v.type || "json"), el("td", {}, v.format || v.description || ""))) : [];
      const curl = "curl " + (method === "get" ? "" : "-X " + method.toUpperCase() + " ") + "'" + origin + path + "'" +
        (op.security ? " -H 'Authorization: Bearer <token>'" : "") + (bodySchema ? " -H 'Content-Type: application/json' -d '{...}'" : "");
      card.append(el("details", {},
        el("summary", {}, el("span", { class: "method m-" + method }, method.toUpperCase()), " ", el("code", {}, path), " ",
          el("span", { class: "muted" }, locked + op.summary + (op["x-rule"] ? T(" · kural: ") + op["x-rule"] : ""))),
        rows.length ? el("div", { class: "table-wrap" }, el("table", {}, el("thead", {}, el("tr", {}, [T("parametre"), T("yer"), T("tip"), T("açıklama")].map((h) => el("th", {}, h)))), el("tbody", {}, rows))) : null,
        bodyRows.length ? el("div", { class: "table-wrap" }, el("p", { class: "muted" }, T("Gövde (JSON; * zorunlu)")),
          el("table", {}, el("thead", {}, el("tr", {}, [T("alan"), T("tip"), T("not")].map((h) => el("th", {}, h)))), el("tbody", {}, bodyRows))) : null,
        el("pre", { class: "code" }, curl)));
    }
    main.append(card);
  }
}

// ---- loglar (canlı)

async function showLogs() {
  const pre = el("pre", { class: "logs" });
  main.append(el("p", { class: "muted" }, T("Canlı; yeni satırlar otomatik gelir.")), pre);
  const ctrl = new AbortController();
  state.abort = ctrl;
  let after = 0;
  // Sunucu akışı en çok 30 dk açık tutar; bitince kaldığı yerden yeniden bağlanılır.
  while (!ctrl.signal.aborted && state.tab === "logs") {
    const r = await api("/_admin/api/logs?after=" + after);
    if (after === 0) after = Math.max(0, r.next - 500); // ilk açılışta son 500 satır
    else after = r.next;
    if (await streamLogs(pre, ctrl, after) === "aborted") return;
    after = (await api("/_admin/api/logs?after=0")).next;
  }
}

async function streamLogs(pre, ctrl, after) {
  const res = await api("/_admin/api/logs/stream?after=" + after, { raw: true, signal: ctrl.signal });
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let i;
      while ((i = buffer.indexOf("\n\n")) >= 0) {
        const event = buffer.slice(0, i);
        buffer = buffer.slice(i + 2);
        const lines = event.split("\n").filter((l) => l.startsWith("data: ")).map((l) => l.slice(6));
        if (!lines.length) continue;
        const atBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 20;
        pre.append(lines.join("\n") + "\n");
        while (pre.childNodes.length > 2000) pre.removeChild(pre.firstChild);
        if (atBottom) pre.scrollTop = pre.scrollHeight;
      }
    }
  } catch (e) {
    if (e.name === "AbortError") return "aborted";
    throw e;
  }
  return "ended";
}

// ---- yedekler

async function showBackups() {
  const out = el("div");
  const load = async () => {
    clear(out);
    await guard(out, async () => {
      const list = await api("/_admin/api/backups");
      if (!list.length) { out.append(el("p", { class: "muted" }, T("Henüz yedek yok."))); return; }
      out.append(el("div", { class: "table-wrap" }, el("table", {},
        el("thead", {}, el("tr", {}, ["ad", T("tür"), T("boyut"), ""].map((h) => el("th", {}, h)))),
        el("tbody", {}, list.map((b) => el("tr", {},
          el("td", {}, b.name), el("td", {}, b.kind), el("td", {}, (b.size / 1048576).toFixed(2) + " MB"),
          el("td", {}, el("button", { class: "ghost", onclick: () => download(b.name) }, T("İndir")))))))));
    });
  };
  const download = async (name) => {
    const res = await api("/_admin/api/backups/" + encodeURIComponent(name), { raw: true });
    if (!res.ok) { out.append(errorBox(new Error(T("İndirilemedi")))); return; }
    saveBlob(await res.blob(), name);
  };
  main.append(el("div", { class: "card" },
    el("div", { class: "row" },
      el("button", { class: "btn", onclick: async (ev) => {
        ev.target.disabled = true;
        await guard(out, async () => { await api("/_admin/api/backups", { method: "POST" }); await load(); });
        ev.target.disabled = false;
      } }, T("Şimdi yedek al"))),
    el("p", { class: "muted" }, T("Yedek: veritabanı (SQLite veya PostgreSQL, kayıt kayıt), konfigürasyon (sırlar hariç), yüklenen dosyalar, script'ler ve site. Geri yükleme telefondaki uygulamadan yapılır.")),
    out));
  await load();
}

// ---- konfigürasyon

// ---- denetim kaydı

const AUDIT_ACTIONS = {
  "server.start": T("Sunucu başlatıldı"), deploy: T("Konfigürasyon değişti"), "config.save": T("Panelden konfigürasyon kaydedildi"),
  "panel.login": T("Panel girişi"), "panel.login.failed": T("Başarısız panel girişi"), "user.create": T("Kullanıcı açıldı"),
  "user.update": T("Kullanıcı güncellendi"), "user.delete": T("Kullanıcı silindi"), "record.revert": T("Kayıt eski sürüme döndü"),
  "record.restore": T("Kayıt çöp kutusundan geri yüklendi"), "record.purge": T("Kayıt kalıcı silindi"), "data.import": T("İçe aktarma"),
  "backup.create": T("Yedek alındı"), "backup.restore": T("Yedekten geri yüklendi"),
  "firewall.ban": T("Adres engellendi"), "firewall.unban": T("Adres engeli kaldırıldı"),
  "job.enqueue": T("İş eklendi"), "job.retry": T("İş yeniden çalıştırıldı"), "job.cancel": T("İş iptal edildi"),
  "replica.promote": T("Kopya devraldı (ana sunucu oldu)"),
  "replica.failover": T("Kopya otomatik devraldı (ana sunucuya ulaşılamadı)"), "replica.failback": T("Ana rol geri alındı"),
  "replica.demoted": T("Bu sunucu devredildi, kopyaya döndü"), "replica.fenced": T("Eski ana sunucu kopyaya çevrildi"),
  "phone.sms": T("SMS gönderildi"),
};

async function showAudit() {
  const path = gp("audit", null, "/_audit");
  if (!path) { main.append(el("p", { class: "muted" }, T("Denetim kaydı ucu kapalı (uzman modu)."))); return; }
  const search = el("input", { placeholder: T("ara: işlem, kişi, ayrıntı, adres"), size: "36" });
  const out = el("div");
  const load = () => guard(out, async () => {
    clear(out);
    const q = "?limit=500" + (search.value.trim() ? "&q=" + encodeURIComponent(search.value.trim()) : "");
    const r = await api(path + q);
    if (!r.items.length) { out.append(el("p", { class: "muted" }, T("Kayıt yok."))); return; }
    out.append(el("div", { class: "table-wrap" }, el("table", {},
      el("thead", {}, el("tr", {}, [T("Zaman"), T("İşlem"), T("Kim"), T("Hedef"), T("Ayrıntı"), T("Adres")].map((h) => el("th", {}, h)))),
      el("tbody", {}, r.items.map((e) => el("tr", {},
        el("td", {}, new Date(e.at).toLocaleString()), el("td", {}, AUDIT_ACTIONS[e.action] || e.action), el("td", {}, e.actor),
        el("td", {}, e.target || ""), el("td", { title: e.detail || "" }, e.detail || ""), el("td", {}, e.ip || "")))))));
  });
  const csv = el("button", { class: "ghost", onclick: () => guard(out, async () => {
    const res = await api(path + "?limit=20000&format=csv" + (search.value.trim() ? "&q=" + encodeURIComponent(search.value.trim()) : ""), { raw: true });
    if (!res.ok) throw new Error(res.statusText);
    saveBlob(await res.blob(), "denetim.csv");
  }) }, T("CSV indir"));
  main.append(el("div", { class: "card" },
    el("p", { class: "muted" }, T("Yönetici işlemleri: konfigürasyon değişiklikleri, kullanıcı yönetimi, geri yükleme, kalıcı silme, panel girişleri.")),
    el("div", { class: "row" }, search, el("button", { class: "ghost", onclick: load }, T("Ara")), csv), out));
  await load();
  await showFirewall(load);
}

// Görünümler ve raporlar (Sihirbaz → Koleksiyonlar): ilk 100 satır veya özet; CSV indirme.
async function showViews() {
  const listPath = gp("views", null, "/views");
  if (!listPath) { main.append(el("p", { class: "muted" }, T("Görünüm uçları kapalı (uzman modu)."))); return; }
  const r = await api(listPath);
  if (!r.items.length) { main.append(el("p", { class: "muted" }, T("Görünüm yok. Sihirbaz → Koleksiyonlar → Görünümler ve raporlar."))); return; }
  const select = el("select", { onchange: () => load() }, r.items.map((v) => el("option", { value: v.name }, v.title + (v.report ? T(" (rapor)") : ""))));
  const out = el("div");
  const path = () => gp("views.get", { name: select.value }, "/views/{name}");
  const load = () => guard(out, async () => {
    clear(out);
    const data = await api(path() + "?perPage=100");
    const rows = data.items || data.groups || [data];
    if (data.totalItems !== undefined) out.append(el("p", { class: "muted" }, data.totalItems + T(" kayıt") + (data.totalItems > rows.length ? T(" (ilk 100)") : "")));
    if (!rows.length) return;
    const cols = Object.keys(rows[0]);
    out.append(el("div", { class: "table-wrap" }, el("table", {},
      el("thead", {}, el("tr", {}, cols.map((h) => el("th", {}, h)))),
      el("tbody", {}, rows.map((row) => el("tr", {}, cols.map((k) => el("td", {}, cell(row[k])))))))));
  });
  const csv = el("button", { class: "ghost", onclick: () => guard(out, async () => {
    const res = await api(path() + "?format=csv", { raw: true });
    if (!res.ok) throw new Error(res.statusText);
    saveBlob(await res.blob(), select.value + ".csv");
  }) }, T("CSV indir"));
  main.append(el("div", { class: "card" },
    el("p", { class: "muted" }, T("Salt okunur görünümler ve raporlar; adresleri istemcilerle paylaşılabilir (kim görebileceği görünümde seçilir).")),
    el("div", { class: "row" }, select, csv), out));
  await load();
}

// Güvenlik duvarı: engelli adresler (otomatik ve elle), elle engelleme ve engel kaldırma.
async function showFirewall(reloadAudit) {
  const path = gp("firewall", null, "/_firewall");
  if (!path) return;
  const out = el("div");
  const after = async () => { await load(); await reloadAudit(); };
  const load = () => guard(out, async () => {
    clear(out);
    const r = await api(path);
    const lists = [r.allow.length ? T("İzinli: ") + r.allow.join(", ") : "", r.deny.length ? T("Engelli aralıklar: ") + r.deny.join(", ") : ""].filter(Boolean);
    if (lists.length) out.append(el("p", { class: "muted" }, lists.join(" · ")));
    if (!r.bans.length) { out.append(el("p", { class: "muted" }, T("Şu an engelli adres yok."))); return; }
    out.append(el("div", { class: "table-wrap" }, el("table", {},
      el("thead", {}, el("tr", {}, [T("Adres"), T("Bitiş"), T("Neden"), ""].map((h) => el("th", {}, h)))),
      el("tbody", {}, r.bans.map((b) => el("tr", {},
        el("td", {}, b.client), el("td", {}, new Date(b.until).toLocaleString()), el("td", {}, b.reason),
        el("td", {}, el("button", { class: "ghost", onclick: async () => {
          if (!confirm(b.client + T(" engeli kaldırılsın mı?"))) return;
          await guard(out, async () => { await api(gp("firewall.unban", { client: b.client }, "/_firewall/bans/{client}"), { method: "DELETE" }); await after(); });
        } }, T("Engeli kaldır")))))))));
  });
  const ip = el("input", { placeholder: T("IP adresi"), size: "20" });
  const minutes = el("input", { type: "number", min: "1", value: "60", size: "6", title: T("dakika") });
  const reason = el("input", { placeholder: T("neden (isteğe bağlı)"), size: "24" });
  const ban = el("button", { onclick: () => guard(out, async () => {
    await api(gp("firewall.ban", null, "/_firewall/bans"), { method: "POST",
      body: { client: ip.value.trim(), minutes: Number(minutes.value) || 60, reason: reason.value.trim() || undefined } });
    ip.value = ""; reason.value = "";
    await after();
  }) }, T("Engelle"));
  main.append(el("div", { class: "card" }, el("h3", {}, T("Engelli adresler")),
    el("p", { class: "muted" }, T("Çok sayıda hatalı kimlik denemesi yapan adresler otomatik engellenir. Kalıcı izin/engel listeleri uygulamada Güvenlik bölümünden.")),
    el("div", { class: "row" }, ip, minutes, el("span", { class: "muted" }, T("dk")), reason, ban), out));
  await load();
}

// ---- arka plan işleri

const JOB_STATUS = { queued: T("Bekliyor"), running: T("Çalışıyor"), done: T("Bitti"), failed: T("Başarısız"), cancelled: T("İptal edildi") };

async function showJobs() {
  const path = gp("jobs", null, "/_jobs");
  if (!path) { main.append(el("p", { class: "muted" }, T("İş kuyruğu ucu kapalı (uzman modu)."))); return; }
  let status = "";
  const filters = el("div", { class: "row" });
  const out = el("div");
  const act = (j, key, label) => el("button", { class: "ghost", onclick: () => guard(out, async () => {
    await api(gp(key, { id: j.id }, key === "jobs.retry" ? "/_jobs/{id}/retry" : "/_jobs/{id}/cancel"), { method: "POST" });
    await load();
  }) }, label);
  const load = () => guard(out, async () => {
    const r = await api(path + "?perPage=100" + (status ? "&status=" + status : ""));
    clear(filters); clear(out);
    const total = Object.values(r.counts).reduce((a, b) => a + b, 0);
    filters.append(...[["", T("Tümü (") + total + ")"], ...Object.entries(JOB_STATUS).map(([k, t]) => [k, t + " (" + (r.counts[k] || 0) + ")"])]
      .map(([k, t]) => el("button", { class: status === k ? "" : "ghost", onclick: () => { status = k; load(); } }, t)));
    if (r.types.length) out.append(el("p", { class: "muted" }, T("Türler: ") + r.types.map((t) => t.name + " (" + t.attempts + T(" deneme, ") + t.concurrency + T(" eşzamanlı)")).join(", ")));
    if (!r.items.length) { out.append(el("p", { class: "muted" }, T("İş yok."))); return; }
    out.append(el("div", { class: "table-wrap" }, el("table", {},
      el("thead", {}, el("tr", {}, ["#", T("Tür"), T("Durum"), T("Deneme sayısı"), T("Eklendi"), T("Veri"), T("Sonuç / hata"), ""].map((h) => el("th", {}, h)))),
      el("tbody", {}, r.items.map((j) => el("tr", {},
        el("td", {}, String(j.id)), el("td", {}, j.name), el("td", {}, JOB_STATUS[j.status] || j.status),
        el("td", {}, j.attempts + "/" + j.maxAttempts), el("td", {}, j.createdAt ? new Date(j.createdAt).toLocaleString() : ""),
        el("td", { title: JSON.stringify(j.data) }, JSON.stringify(j.data).slice(0, 80)),
        el("td", { class: j.error ? "error" : "", title: j.error || JSON.stringify(j.result ?? "") }, (j.error || (j.result === undefined ? "" : JSON.stringify(j.result))).slice(0, 120)),
        el("td", {}, ["failed", "cancelled"].includes(j.status) ? act(j, "jobs.retry", T("Yeniden")) : ["queued", "running"].includes(j.status) ? act(j, "jobs.cancel", T("İptal")) : "")))))));
  });
  main.append(el("div", { class: "card" },
    el("p", { class: "muted" }, T("Script'lerin jobAdd ile tanımlayıp ctx.jobs.enqueue ile eklediği işler. Başarısız iş beklemeyle yeniden denenir.")),
    filters, el("button", { class: "ghost", onclick: load }, T("Yenile")), out));
  await load();
}

// ---- şema: koleksiyon, alan ve kural düzenleyici (konfigürasyonun yalnızca collections kısmını değiştirir)

const FIELD_TYPES = [["text", T("Metin")], ["integer", T("Tam sayı")], ["real", T("Ondalıklı")], ["boolean", T("Evet/Hayır")], ["datetime", T("Tarih-saat")],
  ["json", "JSON"], ["relation", T("İlişki")], ["file", T("Dosya")], ["select", T("Seçim")], ["multiselect", T("Çoklu seçim")]];
const RULES = [["public", T("Herkes")], ["auth", T("Giriş yapmış")], ["owner", T("Kaydın sahibi")], ["team", T("Takım üyeleri")], ["admin", T("Yönetici")], ["nobody", T("Kapalı")]];
const RULE_OPS = [["list", T("Listele")], ["view", T("Görüntüle")], ["create", T("Ekle")], ["update", T("Güncelle")], ["delete", T("Sil")]];
const NAME_RE = /^[a-z][a-z0-9_]*$/;

function options(list, value) { return list.map(([v, t]) => el("option", { value: v, selected: v === value }, t)); }

// Boş/varsayılan değerler dosyaya yazılmaz (sihirbazın ürettiği konfigürasyonla aynı görünüm).
function setProp(obj, key, value) {
  if (value === undefined || value === null || value === "" || value === false || (Array.isArray(value) && !value.length)) delete obj[key];
  else obj[key] = value;
}

// Tip değişince yeni tipe uymayan ayarlar atılır (sihirbazdaki FieldRules.fitTo ile aynı kurallar).
function fitField(f, type) {
  const text = type === "text", number = type === "integer" || type === "real", choice = type === "select" || type === "multiselect";
  if (!choice) delete f.options;
  if (!text) { delete f.format; delete f.pattern; }
  if (type !== f.type || !(text || type === "multiselect")) { delete f.minLength; delete f.maxLength; }
  if (!number) { delete f.min; delete f.max; }
  if (!["text", "integer", "real", "boolean"].includes(type)) delete f.computed;
  if (type !== "relation") { delete f.collection; delete f.onDelete; }
  if (type !== "file") { delete f.multiple; delete f.maxSizeMb; delete f.mimeTypes; delete f.thumbs; }
  if (type === "file") { delete f.unique; delete f.index; }
  if (type !== "text" && type !== "json") delete f.encrypted;
  delete f.default;
  f.type = type;
}

async function showSchema() {
  const loaded = await api("/_admin/api/config");
  const cfg = JSON.parse(loaded.text);
  cfg.collections = cfg.collections || [];
  // Kayıtlı (çalışan) tipler: tip değişince kayıtlı değerlere ne olacağı sorulur.
  const savedTypes = {};
  for (const c of JSON.parse(loaded.text).collections || []) for (const f of c.fields || []) savedTypes[c.name + "." + f.name] = f.type;
  let selected = 0;
  const list = el("div", { class: "schema-list" });
  const editor = el("div");
  const out = el("div");

  const render = () => { renderList(); renderCollection(); };

  function renderList() {
    clear(list);
    cfg.collections.forEach((c, i) => list.append(el("button", { class: i === selected ? "btn" : "ghost", onclick: () => { selected = i; render(); } }, c.name)));
    list.append(el("button", { class: "ghost", onclick: () => {
      const name = (prompt(T("Yeni koleksiyonun adı (küçük harf, rakam, _; harfle başlar)")) || "").trim();
      if (!name) return;
      if (!NAME_RE.test(name) || cfg.collections.some((c) => c.name === name)) { alert(T("Geçersiz veya kullanılan ad: ") + name); return; }
      cfg.collections.push({ name, fields: [{ name: "title", type: "text", required: true }], rules: { list: "admin", view: "admin", create: "admin", update: "admin", delete: "admin" } });
      selected = cfg.collections.length - 1;
      render();
    } }, T("+ Koleksiyon")));
  }

  function renderCollection() {
    clear(editor);
    const c = cfg.collections[selected];
    if (!c) { editor.append(el("p", { class: "muted" }, T("Koleksiyon yok; soldan ekleyin."))); return; }
    c.fields = c.fields || [];
    c.rules = c.rules || {};
    const name = el("input", { value: c.name, size: "24", onchange: () => {
      const v = name.value.trim();
      if (!NAME_RE.test(v) || cfg.collections.some((o) => o !== c && o.name === v)) { alert(T("Geçersiz veya kullanılan ad")); name.value = c.name; return; }
      // İlişkiler yeni adı izler.
      for (const o of cfg.collections) for (const f of o.fields || []) if (f.type === "relation" && f.collection === c.name) f.collection = v;
      c.name = v;
      render();
    } });
    editor.append(el("div", { class: "row" }, el("b", {}, T("Ad")), name,
      el("button", { class: "ghost danger", onclick: () => {
        if (!confirm("'" + c.name + T("' kaldırılsın mı? Tablo ve kayıtlar veritabanında kalır (silinmez); yeniden eklenirse geri gelir."))) return;
        cfg.collections.splice(selected, 1); selected = 0; render();
      } }, T("Koleksiyonu kaldır"))));

    // Kurallar
    const rules = el("div", { class: "row" }, el("b", {}, T("Kurallar")));
    for (const [op, title] of RULE_OPS) {
      const sel = el("select", { onchange: () => { c.rules[op] = sel.value; } }, options(RULES, c.rules[op] || "admin"));
      rules.append(el("label", {}, title + " ", sel));
    }
    editor.append(rules);
    // Özel roller: kural izin vermese de seçilen işlemleri tüm kayıtlarda yapar.
    for (const r of ((cfg.auth || {}).users || {}).roles || []) {
      const row = el("div", { class: "row" }, el("span", { class: "muted" }, (r.title || r.name) + ":"));
      for (const [op, title] of RULE_OPS) {
        const on = ((c.rules.roles || {})[op] || []).includes(r.name);
        const box = el("input", { type: "checkbox", checked: on, onchange: () => {
          const roles = c.rules.roles = c.rules.roles || {};
          const list = (roles[op] || []).filter((n) => n !== r.name);
          if (box.checked) list.push(r.name);
          if (list.length) roles[op] = list; else delete roles[op];
          if (!Object.keys(roles).length) delete c.rules.roles;
        } });
        row.append(el("label", {}, box, " " + title));
      }
      editor.append(row);
    }
    const owners = c.fields.filter((f) => f.type === "relation" && f.collection === "_users").map((f) => [f.name, f.name]);
    const owner = el("select", { onchange: () => setProp(c, "ownerField", owner.value) }, options([["", T("(yok)")], ...owners], c.ownerField || ""));
    editor.append(el("div", { class: "row" }, el("b", {}, T("Sahip alanı")), owner, el("span", { class: "muted" }, T("\"Kaydın sahibi\" kuralı ve kotalar için; kullanıcılara bağlı ilişki alanı."))));
    const teamFields = c.fields.filter((f) => f.type === "relation" && f.collection === "_teams").map((f) => [f.name, f.name]);
    if (teamFields.length || c.teamField) {
      const team = el("select", { onchange: () => setProp(c, "teamField", team.value) }, options([["", T("(yok)")], ...teamFields], c.teamField || ""));
      editor.append(el("div", { class: "row" }, el("b", {}, T("Takım alanı")), team, el("span", { class: "muted" }, T("\"Takım üyeleri\" kuralı için; takımlara bağlı ilişki alanı."))));
    }

    // Arama, geçmiş, çöp kutusu
    const texts = c.fields.filter((f) => f.type === "text");
    const search = el("div", { class: "row" }, el("b", {}, T("Aranabilir")));
    for (const f of texts) {
      const cb = el("input", { type: "checkbox", checked: (c.search || []).includes(f.name), onchange: () => {
        const set = new Set(c.search || []); cb.checked ? set.add(f.name) : set.delete(f.name);
        setProp(c, "search", [...set]);
        if (!(c.search || []).length) delete c.fullText;
      } });
      search.append(el("label", {}, cb, " " + f.name));
    }
    const full = el("input", { type: "checkbox", checked: !!c.fullText, onchange: () => setProp(c, "fullText", full.checked) });
    search.append(el("label", {}, full, T(" tam metin dizini")));
    editor.append(search);
    const hist = el("input", { type: "checkbox", checked: !!c.history, onchange: () => { setProp(c, "history", hist.checked ? { keep: Number(keep.value) || 50 } : null); } });
    const keep = el("input", { type: "number", min: "1", max: "1000", value: (c.history && c.history.keep) || 50, size: "5", onchange: () => { if (c.history) c.history.keep = Number(keep.value); } });
    const trash = el("input", { type: "checkbox", checked: !!c.trash, onchange: () => { setProp(c, "trash", trash.checked ? { days: Number(days.value) || 30 } : null); } });
    const days = el("input", { type: "number", min: "1", max: "3650", value: (c.trash && c.trash.days) || 30, size: "5", onchange: () => { if (c.trash) c.trash.days = Number(days.value); } });
    editor.append(el("div", { class: "row" }, el("label", {}, hist, T(" Geçmiş (sürüm: ")), keep, ")", el("label", {}, trash, T(" Çöp kutusu (gün: ")), days, ")"));

    // Alanlar
    const rows = c.fields.map((f, i) => fieldRow(c, f, i));
    editor.append(el("div", { class: "table-wrap" }, el("table", { class: "schema-fields" },
      el("thead", {}, el("tr", {}, [T("Alan"), T("Tip"), T("Zorunlu"), T("Benzersiz"), T("İndeks"), T("Ayarlar"), ""].map((h) => el("th", {}, h)))),
      el("tbody", {}, rows))));
    editor.append(el("div", { class: "row" }, el("button", { class: "ghost", onclick: () => {
      let n = 1; while (c.fields.some((f) => f.name === "alan" + n)) n++;
      c.fields.push({ name: "alan" + n, type: "text" }); render();
    } }, T("+ Alan"))));
  }

  function fieldRow(c, f, i) {
    const key = c.name + "." + f.name;
    const name = el("input", { value: f.name, size: "14", onchange: () => {
      const v = name.value.trim();
      if (!NAME_RE.test(v) || c.fields.some((o) => o !== f && o.name === v)) { alert(T("Geçersiz veya kullanılan ad")); name.value = f.name; return; }
      if (c.search) c.search = c.search.map((s) => (s === f.name ? v : s));
      if (c.ownerField === f.name) c.ownerField = v;
      if (c.teamField === f.name) c.teamField = v;
      f.name = v; render();
    } });
    const type = el("select", { onchange: () => { fitField(f, type.value); render(); } }, options(FIELD_TYPES, f.type));
    const cb = (prop, disabled) => {
      const x = el("input", { type: "checkbox", checked: !!f[prop], disabled, onchange: () => setProp(f, prop, x.checked) });
      return x;
    };
    const extra = el("div", { class: "row" });
    const input = (prop, label, size, conv) => {
      const x = el("input", { value: f[prop] === undefined ? "" : (Array.isArray(f[prop]) ? f[prop].join(", ") : f[prop]), size: size || "10", placeholder: label,
        onchange: () => setProp(f, prop, conv ? conv(x.value) : x.value.trim()) });
      extra.append(el("label", { class: "muted" }, label + " "), x);
    };
    const num = (v) => (v.trim() === "" ? null : Number(v));
    if (f.type === "relation") {
      const teamsOn = cfg.auth && cfg.auth.users && cfg.auth.users.teams && cfg.auth.users.teams.enabled;
      const targets = [["_users", T("_users (kullanıcılar)")], ...(teamsOn || f.collection === "_teams" ? [["_teams", T("_teams (takımlar)")]] : []), ...cfg.collections.map((o) => [o.name, o.name])];
      if (!f.collection) f.collection = cfg.collections[0] ? cfg.collections[0].name : "_users";
      const target = el("select", { onchange: () => setProp(f, "collection", target.value) }, options(targets, f.collection || ""));
      const del = el("select", { onchange: () => setProp(f, "onDelete", del.value) },
        options([["", T("silinince: varsayılan")], ["cascade", T("bu kayıtlar da silinsin")], ["set_null", T("alan boşalsın")], ["restrict", T("silme engellensin")]], f.onDelete || ""));
      extra.append(target, del);
    }
    if (f.type === "select" || f.type === "multiselect") input("options", T("seçenekler (virgülle)"), "24", (v) => v.split(",").map((x) => x.trim()).filter((x) => x));
    if (f.type === "text") {
      const fmt = el("select", { onchange: () => setProp(f, "format", fmt.value) }, options([["", T("biçim: serbest")], ["email", T("e-posta")], ["url", T("web adresi")]], f.format || ""));
      extra.append(fmt);
      input("pattern", T("düzenli ifade"), "14");
    }
    if (f.type === "text" || f.type === "multiselect") { input("minLength", "en az", "4", num); input("maxLength", T("en çok"), "4", num); }
    if (f.type === "integer" || f.type === "real") { input("min", T("en küçük"), "6", num); input("max", T("en büyük"), "6", num); }
    if (["text", "integer", "real", "boolean"].includes(f.type)) input("computed", T("formül (hesaplanan)"), "18");
    if (f.type === "file") {
      const multi = el("input", { type: "checkbox", checked: !!f.multiple, onchange: () => setProp(f, "multiple", multi.checked) });
      extra.append(el("label", {}, multi, T(" çoklu")));
      input("maxSizeMb", T("en çok MB"), "4", num);
    }
    // Kayıtlı tipten farklıysa kayıtlı değerler: dönüştür veya boşalt.
    const saved = savedTypes[key];
    if (saved && saved !== f.type) {
      if (!f.typeChange || f.typeChange.from !== saved) f.typeChange = { from: saved, action: "convert" };
      const tc = el("select", { onchange: () => { f.typeChange.action = tc.value; } },
        options([["convert", T("kayıtlı değerler dönüştürülsün (") + saved + " → " + f.type + ")"], ["clear", T("kayıtlı değerler silinsin")]], f.typeChange.action));
      extra.append(el("span", { class: "error" }, T("Tip değişti: ")), tc);
    } else delete f.typeChange;
    // Şifreli alan: veritabanında şifreli durur; benzersiz, indeksli, aranabilir olamaz (anahtarı sunucu üretir).
    if ((f.type === "text" || f.type === "json") && !f.computed) {
      const enc = el("input", { type: "checkbox", checked: !!f.encrypted, onchange: () => {
        setProp(f, "encrypted", enc.checked);
        if (enc.checked) {
          delete f.unique; delete f.index;
          if (c.search) setProp(c, "search", c.search.filter((s) => s !== f.name));
        }
        render();
      } });
      extra.append(el("label", { class: "muted", title: T("Değer veritabanında ve yedeklerde şifreli durur; bu alana göre filtrelenemez ve aranamaz.") }, enc, T(" şifreli")));
    }
    const move = (d) => { const j = i + d; if (j < 0 || j >= c.fields.length) return; [c.fields[i], c.fields[j]] = [c.fields[j], c.fields[i]]; render(); };
    return el("tr", {},
      el("td", {}, name), el("td", {}, type), el("td", {}, cb("required")), el("td", {}, cb("unique", f.type === "file")), el("td", {}, cb("index", f.type === "file" || f.type === "json")),
      el("td", {}, extra),
      el("td", {},
        el("button", { class: "ghost", title: T("Yukarı"), onclick: () => move(-1) }, "↑"),
        el("button", { class: "ghost", title: T("Aşağı"), onclick: () => move(1) }, "↓"),
        el("button", { class: "ghost danger", title: T("Kaldır"), onclick: () => {
          if (!confirm("'" + f.name + T("' kaldırılsın mı? Sütun ve değerleri veritabanında kalır (silinmez)."))) return;
          c.fields.splice(i, 1);
          if (c.search) setProp(c, "search", c.search.filter((s) => s !== f.name));
          if (c.ownerField === f.name) delete c.ownerField;
          if (c.teamField === f.name) delete c.teamField;
          render();
        } }, "✕")));
  }

  const text = () => JSON.stringify(cfg, null, 2) + "\n";
  const plan = async () => {
    clear(out);
    const p = await api("/_admin/api/config/plan", { method: "POST", body: { text: text() } });
    const errors = p.errors || [], blockers = p.blockers || [];
    if (errors.length) out.append(el("p", { class: "error" }, errors.join("\n")));
    if (p.plan) out.append(el("p", {}, T("Değişiklikler: ") + p.plan));
    const losing = [];
    for (const c of cfg.collections) for (const f of c.fields) if (f.typeChange && f.typeChange.action === "clear") losing.push(c.name + "." + f.name + T(": kayıtlı değerler silinecek"));
    if (losing.length) out.append(el("p", { class: "error" }, T("Veri kaybı: ") + losing.join("; ")));
    if (blockers.length) out.append(el("p", { class: "error" }, T("Uygulanamaz: ") + blockers.join("\n")));
    if ((p.warnings || []).length) out.append(el("p", { class: "muted" }, p.warnings.join("\n")));
    const ok = !errors.length && !blockers.length;
    if (ok) out.append(el("p", { class: "ok" }, T("Geçerli; deploy edilebilir.")));
    return ok;
  };
  main.append(el("div", { class: "card" },
    el("p", { class: "muted" }, T("Koleksiyonlar, alanlar ve kurallar. Önce \"Planı göster\" ile ne değişeceğini görün; kaldırılan alan ve ") +
      T("koleksiyonların verileri silinmez. Diğer ayarlar için Konfigürasyon sekmesi veya telefondaki sihirbaz.")),
    el("div", { class: "schema" }, list, editor),
    el("div", { class: "row" },
      el("button", { class: "ghost", onclick: () => plan().catch((e) => out.append(errorBox(e))) }, T("Planı göster")),
      el("button", { class: "btn", onclick: async () => {
        try {
          if (!(await plan())) return;
          if (!confirm(T("Kaydedilip deploy edilsin mi? Sunucu kısa süre yeniden başlar."))) return;
          const d = await api("/_admin/api/config/deploy", { method: "POST", body: { text: text() } });
          out.append(el("p", { class: "ok" }, d.message));
        } catch (e) { out.append(errorBox(e)); }
      } }, T("Kaydet ve deploy et")),
      el("button", { class: "ghost", onclick: () => { if (confirm(T("Değişiklikler atılsın mı?"))) openTab("schema"); } }, T("Vazgeç"))),
    out));
  render();
}

async function showConfig() {
  const r = await api("/_admin/api/config");
  const text = el("textarea", { spellcheck: "false" }, r.text);
  const out = el("div");
  const plan = async () => {
    clear(out);
    const p = await api("/_admin/api/config/plan", { method: "POST", body: { text: text.value } });
    if (p.errors && p.errors.length) out.append(el("p", { class: "error" }, p.errors.join("\n")));
    if (p.plan) out.append(el("p", {}, T("Şema: ") + p.plan));
    if (p.blockers && p.blockers.length) out.append(el("p", { class: "error" }, p.blockers.join("\n")));
    if (p.warnings && p.warnings.length) out.append(el("p", { class: "muted" }, p.warnings.join("\n")));
    if (!(p.errors || []).length && !(p.blockers || []).length) out.append(el("p", { class: "ok" }, T("Geçerli; deploy edilebilir.")));
    return !(p.errors || []).length && !(p.blockers || []).length;
  };
  main.append(el("div", { class: "card" },
    el("p", { class: "muted" }, T("server.json. Sırlar \"secret:…\" referansı olarak görünür; değerleri telefondaki şifreli depodadır.")),
    text,
    el("div", { class: "row" },
      el("button", { class: "ghost", onclick: () => plan().catch((e) => out.append(errorBox(e))) }, T("Denetle")),
      el("button", { class: "btn", onclick: async () => {
        try {
          if (!(await plan())) return;
          if (!confirm(T("Kaydedilip deploy edilsin mi? Sunucu kısa süre yeniden başlar."))) return;
          const d = await api("/_admin/api/config/deploy", { method: "POST", body: { text: text.value } });
          out.append(el("p", { class: "ok" }, d.message));
        } catch (e) { out.append(errorBox(e)); }
      } }, T("Kaydet ve deploy et"))),
    out));
}

// ---- konfigürasyon bölümleri (Otomasyon, Servisler): server.json'un bir kısmını düzenler. Kaydetme Şema ve
// Konfigürasyon sekmeleriyle aynı yoldan gider (plan → onay → deploy): uygulamadaki doğrulamadan geçer, denetim
// kaydına "panel" olarak düşer. Yeni parolalar düz yazılır; telefon kaydederken şifreli depoya taşır.

async function loadConfig() { return JSON.parse((await api("/_admin/api/config")).text); }

async function saveConfig(cfg, out) {
  clear(out);
  const text = JSON.stringify(cfg, null, 2) + "\n";
  try {
    const p = await api("/_admin/api/config/plan", { method: "POST", body: { text } });
    const problems = [...(p.errors || []), ...(p.blockers || [])];
    if (problems.length) { out.append(el("p", { class: "error" }, problems.join("\n"))); return; }
    if ((p.warnings || []).length) out.append(el("p", { class: "muted" }, p.warnings.join("\n")));
    if (!confirm(T("Kaydedilip deploy edilsin mi? Sunucu kısa süre yeniden başlar."))) return;
    const d = await api("/_admin/api/config/deploy", { method: "POST", body: { text } });
    out.append(el("p", { class: "ok" }, d.message));
  } catch (e) { out.append(errorBox(e)); }
}

// Kaydet düğmesi ve sonucu; [cfg] düzenlenen nesnenin kendisi.
function saveRow(cfg) {
  const out = el("div");
  return el("div", {}, el("div", { class: "row" }, el("button", { class: "btn", onclick: () => saveConfig(cfg, out) }, T("Kaydet ve deploy et"))), out);
}

function grid(...rows) { return el("div", { class: "form-grid" }, rows.flat()); }
function line(label, input) { return [el("span", { class: "muted" }, label), input]; }
function textIn(obj, key, attrs) {
  return el("input", Object.assign({ value: obj[key] ?? "", onchange: (e) => setProp(obj, key, e.target.value.trim()) }, attrs || {}));
}
function numIn(obj, key, fallback) {
  return el("input", { type: "number", min: "1", value: obj[key] ?? fallback, onchange: (e) => setProp(obj, key, e.target.value === "" ? undefined : Number(e.target.value)) });
}
// Varsayılanı false olan ayar (false yazılmaz).
function checkIn(obj, key, label) {
  return el("label", {}, el("input", { type: "checkbox", checked: !!obj[key], onchange: (e) => setProp(obj, key, e.target.checked) }), " " + label);
}
// Varsayılanı true olan "enabled" (yalnızca kapalıyken yazılır).
function enabledIn(obj, label) {
  return el("label", {}, el("input", { type: "checkbox", checked: obj.enabled !== false, onchange: (e) => { if (e.target.checked) delete obj.enabled; else obj.enabled = false; } }), " " + label);
}
// Yeni parola: boşsa kayıtlı parola korunur.
function passwordIn(obj) {
  const input = el("input", { type: "text", autocomplete: "off", placeholder: obj.password ? T("kayıtlı parola korunuyor") : T("en az 8 karakter"),
    onchange: (e) => { if (e.target.value) obj.password = e.target.value; } });
  const gen = el("button", { class: "ghost", onclick: () => {
    const a = new Uint8Array(15); crypto.getRandomValues(a);
    input.value = btoa(String.fromCharCode(...a)).replace(/[+/=]/g, "x");
    obj.password = input.value;
  } }, T("Parola üret"));
  return el("span", { class: "row" }, input, gen);
}
function collectionNames(cfg) { return (cfg.collections || []).map((c) => c.name); }
function fieldNames(cfg, name) { return ((cfg.collections || []).find((c) => c.name === name) || { fields: [] }).fields.map((f) => f.name); }
function pick(list, value, onPick, empty) {
  return el("select", { onchange: (e) => onPick(e.target.value || undefined) },
    empty !== undefined ? el("option", { value: "" }, empty) : null, options(list.map((n) => [n, n]), value));
}
// Durum ucu açık değilse (özellik kapalı, kural yok) null.
async function optional(path) {
  if (!path) return null;
  const res = await api(path, { raw: true });
  if (res.status === 404) return null;
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.message || res.statusText);
  return data;
}
function when(iso) { return iso ? new Date(iso).toLocaleString() : "—"; }

// ---- otomasyon: kurallar ve formlar

const EVENTS = [["create", T("eklenince")], ["update", T("değişince")], ["delete", T("silinince")]];
const ACTION_TYPES = [["log", T("Loga yaz")], ["email", T("E-posta gönder")], ["push", T("Bildirim gönder")], ["webhook", T("Webhook'a gönder")],
  ["update", T("Kaydı güncelle")], ["create", T("Kayıt oluştur")]];

async function showAutomation() {
  const cfg = await loadConfig();
  cfg.automations = cfg.automations || [];
  cfg.forms = cfg.forms || [];
  const status = await optional(gp("automations", null, "/_automations"));
  const rules = el("div"), forms = el("div");
  const renderRules = () => { clear(rules); cfg.automations.forEach((a, i) => rules.append(ruleCard(cfg, a, i, status, renderRules))); };
  const renderForms = () => { clear(forms); cfg.forms.forEach((f, i) => forms.append(formCard(cfg, f, i, renderForms))); };
  main.append(el("div", { class: "card" }, el("h3", {}, T("Otomasyon kuralları")),
    el("p", { class: "muted" }, T("Kayıt olayında veya belirli zamanlarda, koşula uyunca eylemleri sırayla çalıştırır. Metinlerde {alan} kaydın değeriyle dolar.")),
    rules,
    el("div", { class: "row" }, el("button", { class: "ghost", onclick: () => {
      cfg.automations.push({ name: "kural-" + (cfg.automations.length + 1), trigger: { collection: collectionNames(cfg)[0] }, actions: [{ type: "log", body: "{id}" }] });
      renderRules();
    } }, T("+ Kural"))),
    status && status.runs.length ? el("details", {}, el("summary", {}, T("Son çalışmalar (") + status.runs.length + ")"),
      el("ul", {}, status.runs.slice(0, 30).map(runLine))) : null,
    saveRow(cfg)));
  main.append(el("div", { class: "card" }, el("h3", {}, T("Formlar")),
    el("p", { class: "muted" }, T("Koleksiyona kayıt ekleyen herkese açık sayfa; yalnızca formdaki alanlar yazılabilir.")),
    forms,
    el("div", { class: "row" }, el("button", { class: "ghost", onclick: () => {
      const c = collectionNames(cfg)[0];
      if (!c) { alert(T("Önce bir koleksiyon oluşturun.")); return; }
      cfg.forms.push({ name: "form-" + (cfg.forms.length + 1), collection: c, title: T("Bize yazın") });
      renderForms();
    } }, T("+ Form"))),
    saveRow(cfg)));
  renderRules();
  renderForms();
}

function runLine(r) { return el("li", { class: r.ok ? "" : "error" }, when(r.at) + " · " + r.rule + " · " + r.trigger + " · " + r.message + " (" + r.durationMs + " ms)"); }

function ruleCard(cfg, a, i, status, rerender) {
  a.trigger = a.trigger || {};
  const scheduled = !!a.trigger.schedule;
  const saved = status && status.rules.some((r) => r.name === a.name);
  const out = el("div");
  const trigger = scheduled
    ? line(T("Zamanlama (cron)"), textIn(a.trigger, "schedule", { placeholder: "0 8 * * *" }))
    : line(T("Olay"), el("span", {}, EVENTS.map(([k, t]) => el("label", {}, el("input", { type: "checkbox", checked: (a.trigger.events || ["create"]).includes(k), onchange: (e) => {
        const set = new Set(a.trigger.events || ["create"]);
        if (e.target.checked) set.add(k); else set.delete(k);
        const list = EVENTS.map(([x]) => x).filter((x) => set.has(x));
        if (list.length === 1 && list[0] === "create") delete a.trigger.events; else a.trigger.events = list;
      } }), " " + t + " "))));
  return el("div", { class: "card" },
    grid(
      line(T("Ad"), textIn(a, "name")),
      line(T("Ne zaman?"), el("select", { onchange: (e) => {
        if (e.target.value === "schedule") { a.trigger.schedule = "0 3 * * *"; delete a.trigger.events; } else delete a.trigger.schedule;
        rerender();
      } }, options([["event", T("Kayıt olayında")], ["schedule", T("Belirli zamanlarda")]], scheduled ? "schedule" : "event"))),
      line(T("Koleksiyon"), pick(collectionNames(cfg), a.trigger.collection, (v) => setProp(a.trigger, "collection", v), scheduled ? T("(yok)") : undefined)),
      trigger,
      line(T("Koşul (isteğe bağlı)"), textIn(a, "condition", { placeholder: "status=open&qty[gt]=0" })),
      line("", enabledIn(a, T("Açık")))),
    el("h4", {}, T("Eylemler")),
    a.actions.map((x, j) => actionRow(cfg, a, x, j, rerender)),
    el("div", { class: "row" },
      el("button", { class: "ghost", onclick: () => { a.actions.push({ type: "log", body: "" }); rerender(); } }, T("+ Eylem")),
      el("button", { class: "ghost", disabled: !saved, title: saved ? "" : T("Önce kaydedin"), onclick: () => guard(out, async () => {
        clear(out);
        const r = await api(gp("automations.run", { name: a.name }, "/_automations/{name}/run"), { method: "POST" });
        out.append(el("p", { class: r.ok ? "ok" : "error" }, r.message));
      }) }, T("Şimdi dene")),
      el("button", { class: "ghost danger", onclick: () => { if (confirm("'" + a.name + T("' kuralı silinsin mi?"))) { cfg.automations.splice(i, 1); rerender(); } } }, T("Kuralı sil"))),
    out);
}

function actionRow(cfg, a, x, j, rerender) {
  const values = () => el("textarea", { rows: "2", placeholder: T("alan=değer (satır başına bir)"),
    onchange: (e) => setProp(x, "values", Object.fromEntries(e.target.value.split("\n").map((l) => l.trim()).filter((l) => l.includes("=")).map((l) => [l.slice(0, l.indexOf("=")).trim(), l.slice(l.indexOf("=") + 1).trim()]))) },
    Object.entries(x.values || {}).map(([k, v]) => k + "=" + v).join("\n"));
  const fields = {
    log: [textIn(x, "body", { placeholder: T("metin") })],
    email: [textIn(x, "to", { placeholder: T("alıcı veya {email}") }), textIn(x, "subject", { placeholder: T("konu") }), textIn(x, "body", { placeholder: T("metin") })],
    push: [textIn(x, "to", { placeholder: T("owner veya kullanıcı kimliği") }), textIn(x, "subject", { placeholder: T("başlık") }), textIn(x, "body", { placeholder: T("metin") }), textIn(x, "url", { placeholder: T("adres (isteğe bağlı)") })],
    webhook: [pick((cfg.webhooks || []).map((w) => w.name), x.webhook, (v) => setProp(x, "webhook", v), T("(webhook seçin)"))],
    update: [values()],
    create: [pick(collectionNames(cfg), x.collection, (v) => setProp(x, "collection", v), T("(koleksiyon)")), values()],
  }[x.type] || [];
  return el("div", { class: "row" },
    el("select", { onchange: (e) => { a.actions[j] = { type: e.target.value }; rerender(); } }, options(ACTION_TYPES, x.type)),
    fields,
    el("button", { class: "ghost danger", title: T("Kaldır"), onclick: () => { a.actions.splice(j, 1); rerender(); } }, "✕"));
}

function formCard(cfg, f, i, rerender) {
  f.fields = f.fields || [];
  const url = location.origin + "/forms/" + f.name;
  const available = fieldNames(cfg, f.collection);
  return el("div", { class: "card" },
    grid(
      line(T("Adres"), el("span", { class: "row" }, textIn(f, "name"), el("a", { href: url, target: "_blank", rel: "noopener" }, T("Aç")))),
      line(T("Başlık"), textIn(f, "title")),
      line(T("Kayıtlar nereye?"), pick(collectionNames(cfg), f.collection, (v) => { f.collection = v; f.fields = []; rerender(); })),
      line(T("Açıklama"), textIn(f, "description")),
      line(T("Gönder düğmesi"), textIn(f, "submitLabel", { placeholder: T("Gönder") })),
      line(T("Teşekkür metni"), textIn(f, "successMessage")),
      line(T("Yönlendirme"), textIn(f, "redirect", { placeholder: "https://… " + T("veya") + " /…" })),
      line(T("10 dakikada en çok"), numIn(f, "maxPerTenMinutes", 5)),
      line("", enabledIn(f, T("Açık")))),
    el("h4", {}, T("Alanlar")),
    el("p", { class: "muted" }, T("Boşsa koleksiyonun yazılabilir tüm alanları.")),
    f.fields.map((x, j) => el("div", { class: "row" },
      pick(available, x.name, (v) => { x.name = v; }),
      textIn(x, "label", { placeholder: T("görünen ad") }),
      checkIn(x, "multiline", T("Çok satırlı")),
      el("button", { class: "ghost danger", title: T("Kaldır"), onclick: () => { f.fields.splice(j, 1); rerender(); } }, "✕"))),
    el("div", { class: "row" },
      el("button", { class: "ghost", disabled: !available.length, onclick: () => { f.fields.push({ name: available[0] }); rerender(); } }, T("+ Alan")),
      el("button", { class: "ghost danger", onclick: () => { if (confirm("'" + f.name + T("' formu silinsin mi?"))) { cfg.forms.splice(i, 1); rerender(); } } }, T("Formu sil"))));
}

// ---- servisler: MQTT, ağ sürücüsü, canlı kopya, yapay zekâ, kendi uygulamalarınız, komut satırı

async function showServices() {
  const cfg = await loadConfig();
  for (const k of ["mqtt", "webdav", "replication"]) cfg[k] = cfg[k] || {};
  await guard(main, () => mqttCard(cfg));
  webdavCard(cfg);
  await guard(main, () => replicaCard(cfg));
  await guard(main, aiCard);
  await guard(main, appsCard);
  cliCard();
}

async function mqttCard(cfg) {
  const m = cfg.mqtt;
  m.routes = m.routes || [];
  const status = await optional(gp("mqtt", null, "/_mqtt"));
  const routes = el("div");
  const renderRoutes = () => {
    clear(routes);
    m.routes.forEach((r, i) => routes.append(el("div", { class: "row" },
      el("input", { value: r.topic, placeholder: "sensors/+/temp", onchange: (e) => { r.topic = e.target.value.trim(); } }),
      pick(collectionNames(cfg), r.collection, (v) => { r.collection = v; renderRoutes(); }),
      pick(fieldNames(cfg, r.collection), r.valueField, (v) => setProp(r, "valueField", v), T("(değer alanı yok)")),
      pick(fieldNames(cfg, r.collection), r.topicField, (v) => setProp(r, "topicField", v), T("(konu alanı yok)")),
      el("button", { class: "ghost danger", title: T("Kaldır"), onclick: () => { m.routes.splice(i, 1); renderRoutes(); } }, "✕"))));
  };
  const events = el("span", {}, collectionNames(cfg).map((c) => el("label", {}, el("input", { type: "checkbox", checked: (m.events || []).includes(c), onchange: (e) => {
    const set = new Set(m.events || []);
    if (e.target.checked) set.add(c); else set.delete(c);
    setProp(m, "events", [...set]);
  } }), " " + c + " ")));
  const out = el("div");
  const topic = el("input", { placeholder: "ev/salon/lamba" }), payload = el("input", { placeholder: "on" });
  const publish = el("button", { class: "btn", disabled: !status || !status.listening, onclick: () => guard(out, async () => {
    clear(out);
    const r = await api(gp("mqtt.publish", null, "/_mqtt/publish"), { method: "POST", body: { topic: topic.value.trim(), payload: payload.value } });
    out.append(el("p", { class: "ok" }, r.delivered + T(" cihaza gönderildi")));
  }) }, T("Gönder"));
  main.append(el("div", { class: "card" }, el("h3", {}, "MQTT (IoT)"),
    status && status.listening
      ? el("p", {}, T("Açık · port ") + status.port + " · " + status.received + T(" mesaj alındı · ") + status.delivered + T(" iletildi · ") + status.failed + T(" hata"))
      : el("p", { class: "muted" }, T("Kapalı")),
    grid(
      line("", checkIn(m, "enabled", T("MQTT açık"))),
      line(T("Port"), numIn(m, "port", 1883)),
      line(T("Kullanıcı adı"), textIn(m, "username", { placeholder: "device" })),
      line(T("Yeni parola"), passwordIn(m))),
    el("p", { class: "muted" }, T("Bağlantı şifresizdir (düz TCP): yalnızca yerel ağda kullanın.")),
    el("h4", {}, T("Mesajları kaydet")),
    el("p", { class: "muted" }, T("Konu süzgeci, koleksiyon, değer alanı (düz değer için), konu alanı (+ bölümü için).")),
    routes,
    el("button", { class: "ghost", disabled: !collectionNames(cfg).length, onclick: () => { m.routes.push({ topic: "sensors/+/value", collection: collectionNames(cfg)[0] }); renderRoutes(); } }, T("Yönlendirme ekle")),
    el("h4", {}, T("Kayıt olaylarını yayınla")), events,
    saveRow(cfg),
    status && status.listening ? el("div", {},
      el("h4", {}, T("Bağlı cihazlar (") + status.clients.length + ")"),
      el("ul", {}, status.clients.map((c) => el("li", {}, c.id + " · " + c.address + " · " + c.subscriptions.join(", ")))),
      el("details", {}, el("summary", {}, T("Son mesajlar")), el("ul", {}, status.recent.map((r) => el("li", {}, when(r.at) + " · " + r.topic + " · " + r.result)))),
      el("h4", {}, T("Cihazlara mesaj gönder")), el("div", { class: "row" }, topic, payload, publish), out) : null));
  renderRoutes();
}

function webdavCard(cfg) {
  const w = cfg.webdav;
  const url = location.origin + (w.path || "/dav");
  main.append(el("div", { class: "card" }, el("h3", {}, T("Ağ sürücüsü (WebDAV)")),
    el("p", { class: "muted" }, w.enabled ? T("Adres: ") + url + " · " + T("kullanıcı: ") + (w.username || "droid") : T("Kapalı")),
    grid(
      line("", checkIn(w, "enabled", T("Ağ sürücüsü açık"))),
      line(T("Yol"), textIn(w, "path", { placeholder: "/dav" })),
      line(T("Kullanıcı adı"), textIn(w, "username", { placeholder: "droid" })),
      line(T("Yeni parola"), passwordIn(w)),
      line(T("Paylaşımlar"), el("span", {}, checkIn(w, "readOnly", T("Sürücü yalnızca okunur")), " ", checkIn(w, "site", "site"), " ", checkIn(w, "scripts", "scripts"), " ", checkIn(w, "uploads", T("uploads (salt okunur)")))),
      line(T("Tek dosya en çok (MB)"), numIn(w, "maxFileMb", 2048))),
    el("p", { class: "muted" }, T("Windows: Bu bilgisayar → Ağ konumu ekle. macOS: Finder → Sunucuya Bağlan. Linux: dav:// (GNOME) veya webdav:// (KDE) ile aynı adres.")),
    saveRow(cfg)));
}

async function replicaCard(cfg) {
  const r = await api("/_admin/api/replica");
  const rep = cfg.replication;
  rep.failover = rep.failover || {};
  const s = r.status;
  const out = el("div");
  const rows = [line("", checkIn(rep, "source", T("Bu sunucu kopyalanabilir"))), line(T("Değişiklik günlüğü (gün)"), numIn(rep, "keepDays", 7))];
  if (r.primary) {
    rows.push(line("", checkIn(rep.failover, "auto", T("Otomatik devral"))), line(T("Ulaşılamazsa (sn)"), numIn(rep.failover, "afterSec", 120)),
      line(T("Tanık adresi (isteğe bağlı)"), textIn(rep.failover, "witness", { placeholder: "http://192.168.1.1" })));
  } else {
    rows.push(line("", checkIn(rep.failover, "failback", T("Dönünce ana rolünü geri al"))));
  }
  main.append(el("div", { class: "card" }, el("h3", {}, T("Canlı kopya ve devralma")),
    el("p", {}, r.primary ? T("Bu sunucu kopya; ana sunucu: ") + r.primary : r.source ? T("Ana sunucu (kopyalanabilir).") : T("Kopyalama kapalı.")),
    r.formerPrimary ? el("p", { class: "muted" }, T("Eski ana sunucu: ") + r.formerPrimary) : null,
    s ? el("p", { class: s.error ? "error" : "muted" }, s.phaseTitle + " · " + T("geride ") + Math.max(0, s.latestSeq - s.appliedSeq) + T(" değişiklik · son temas ") + when(s.lastContact) +
      (s.progress ? " · " + s.progress : "") + (s.error ? "\n" + s.error : "") + (s.failoverNote ? "\n" + s.failoverNote : "") +
      (s.takeoverAt ? "\n" + T("Otomatik devralma: ") + when(s.takeoverAt) : "")) : null,
    grid(...rows),
    el("p", { class: "muted" }, T("Bir telefonu kopya yapmak (ana sunucu adresi ve anahtarı) telefondaki sihirbazdan yapılır.")),
    saveRow(cfg),
    r.primary ? el("div", { class: "row" }, el("button", { class: "ghost danger", onclick: () => guard(out, async () => {
      if (!confirm(T("Bu kopya yazılabilir ana sunucu olsun mu? Eşitleme durur; son saniyelerdeki yazmalar kaybolabilir."))) return;
      const d = await api("/_admin/api/replica/takeover", { method: "POST" });
      clear(out); out.append(el("p", { class: "ok" }, d.message));
    }) }, T("Devral"))) : null,
    out));
}

async function aiCard() {
  const r = await optional(gp("ai.status", null, "/_ai"));
  const out = el("div");
  main.append(el("div", { class: "card" }, el("h3", {}, T("Yapay zekâ")),
    !r ? el("p", { class: "muted" }, T("Kapalı. Sağlayıcı ve anahtar telefonda Sunucu → Yapay zekâ ekranından girilir.")) : el("div", {},
      el("p", {}, r.provider + " · " + r.model + " · " + T("bugün ") + r.today.requests + T(" istek, ") + r.today.inputTokens + " + " + r.today.outputTokens + T(" token")),
      r.semantic.length ? el("ul", {}, r.semantic.map((x) => el("li", {}, x.collection + ": " + x.indexed + T(" kayıt dizinde") + (x.pending ? ", " + x.pending + T(" bekliyor") : "")))) : null,
      r.error ? el("p", { class: "error" }, r.error) : null,
      r.semantic.length ? el("button", { class: "ghost", onclick: () => guard(out, async () => {
        await api(gp("ai.reindex", null, "/_ai/reindex"), { method: "POST" });
        clear(out); out.append(el("p", { class: "ok" }, T("Dizin yeniden kuruluyor.")));
      }) }, T("Dizini yeniden kur")) : null, out)));
}

async function appsCard() {
  const list = await optional(gp("apps", null, "/_apps"));
  const out = el("div");
  main.append(el("div", { class: "card" }, el("h3", {}, T("Kendi uygulamalarınız")),
    !list || !list.length ? el("p", { class: "muted" }, T("Uygulama yok. Zip ile yükleme ve Git kaynağı telefonda Sunucu → Uygulamalar ekranından.")) : el("div", { class: "table-wrap" }, el("table", {},
      el("thead", {}, el("tr", {}, [T("Ad"), T("Yol"), T("Durum"), T("Sürüm"), ""].map((h) => el("th", {}, h)))),
      el("tbody", {}, list.map((a) => el("tr", {},
        el("td", {}, a.name + (a.runtime ? " · " + a.runtime : "")),
        el("td", {}, el("a", { href: a.path, target: "_blank", rel: "noopener" }, a.path)),
        el("td", {}, a.enabled === false ? T("kapalı") : (a.phase || "—")),
        el("td", {}, (a.current || "—") + (a.pending ? T(" (bekleyen: ") + a.pending + ")" : "") + (a.commit ? " · " + String(a.commit).slice(0, 7) : "")),
        el("td", {}, el("button", { class: "ghost", onclick: () => guard(out, async () => {
          await api(gp("apps.deploy", { name: a.name }, "/_apps/{name}/deploy"), { method: "POST" });
          clear(out); out.append(el("p", { class: "ok" }, a.name + T(": deploy başladı; sonuç loglarda")));
        }) }, a.git ? T("Çek ve deploy et") : T("Yeniden deploy et")))))))),
    out));
}

function cliCard() {
  const apiUrl = location.origin + base();
  const cli = gp("cli", null, "/_cli");
  main.append(el("div", { class: "card" }, el("h3", {}, T("Komut satırı (CLI)")),
    el("p", { class: "muted" }, T("Bilgisayardan yönetim (Node.js 18+). Giriş yönetici anahtarını sorar; adlandırılmış, iptal edilebilir bir anahtar önerilir.")),
    cli ? el("pre", { class: "code" }, [
      "curl -o droidserver.mjs " + location.origin + cli,
      "node droidserver.mjs login " + apiUrl,
      "node droidserver.mjs status",
      "node droidserver.mjs list <" + T("koleksiyon") + ">",
      "node droidserver.mjs logs -f",
    ].join("\n")) : el("p", { class: "muted" }, T("Komut satırı ucu kapalı (uzman modu)."))));
}

// ---- başlat

if (state.token) start().catch(() => showLogin()); else showLogin();
