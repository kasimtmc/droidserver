
// ---- Çevrimdışı eşitleme (koleksiyonda `sync` açıkken) ----

/** Çevrimdışı deponun saklama yeri: anahtar → JSON metni. Başka bir depo (SQLite, dosya) bu arayüzle takılabilir. */
export interface OfflineStore {
  get(key: string): Promise<string | undefined>;
  set(key: string, value: string): Promise<void>;
}

/** Bellekte (sayfa kapanınca silinir; Node ve denemeler için). */
export class MemoryStore implements OfflineStore {
  private readonly values = new Map<string, string>();
  async get(key: string): Promise<string | undefined> { return this.values.get(key); }
  async set(key: string, value: string): Promise<void> { this.values.set(key, value); }
}

/** Tarayıcıda IndexedDB (`droidserver` veritabanı, `kv` deposu). */
export class IndexedDbStore implements OfflineStore {
  private readonly name: string;
  private db?: Promise<IDBDatabase>;
  constructor(name = "droidserver") { this.name = name; }

  private open(): Promise<IDBDatabase> {
    this.db ??= new Promise((ok, fail) => {
      const r = indexedDB.open(this.name, 1);
      r.onupgradeneeded = () => r.result.createObjectStore("kv");
      r.onsuccess = () => ok(r.result);
      r.onerror = () => fail(r.error);
    });
    return this.db;
  }

  private async tx<R>(mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest): Promise<R> {
    const db = await this.open();
    return new Promise((ok, fail) => {
      const r = f(db.transaction("kv", mode).objectStore("kv"));
      r.onsuccess = () => ok(r.result as R);
      r.onerror = () => fail(r.error);
    });
  }

  get(key: string): Promise<string | undefined> { return this.tx("readonly", (s) => s.get(key)); }
  async set(key: string, value: string): Promise<void> { await this.tx("readwrite", (s) => s.put(value, key)); }
}

/** Tarayıcıda IndexedDB, başka yerde (Node) bellek. */
export function defaultStore(): OfflineStore {
  return typeof indexedDB !== "undefined" ? new IndexedDbStore() : new MemoryStore();
}

/**
 * Eşitleme çakışması. `merged`: son yazan kazanır, bazı alanlarda sunucunun değeri kaldı; `conflict`: elle çözme kipinde
 * değişiklik uygulanmadı ya da silinen kayıt sunucuda daha yeni. `local` gönderilen alanlar, `server` kaydın sunucudaki
 * (artık yerelde de olan) hali. Elle çözmek için kaydı yeniden `update` edin.
 */
export interface SyncConflict { id: number; status: "merged" | "conflict"; local: Record<string, unknown>; server: Record<string, unknown> | null }

interface PendingOp { op: "create" | "update" | "delete"; id: number; base: number; fields: Record<string, unknown>; times: Record<string, number>; time: number }
interface OfflineState { cursor: number; epoch?: string; nextTemp: number; records: Record<string, { v: number; r: Record<string, unknown> }>; pending: PendingOp[] }

/**
 * Bir koleksiyonun çevrimdışı kopyası. Okuma ve yazma yereldir; yazmalar kayıt başına birleştirilerek bekler ve
 * `sync()` ile gönderilir, sonra sunucudaki değişiklikler çekilir. Çevrimdışı oluşturulan kaydın kimliği eşitlenene kadar
 * negatiftir. Dosya alanları yalnızca adıyla gelir (dosya indirilmez, çevrimdışıyken yüklenmez). Çevrimdışı oluşturulan
 * kayda başka koleksiyondan ilişki, kayıt eşitlendikten sonra kurulmalı. İşlemler sırayla çalışır.
 */
export class OfflineCollection<T, I> {
  onConflict?: (c: SyncConflict) => void;
  /** Sunucunun kabul etmediği (doğrulama, kural) ve kuyruktan çıkarılan değişiklik. */
  onRejected?: (id: number, e: DroidServerError) => void;
  readonly name: string;
  private readonly ds: DroidServerBase;
  private readonly path: string;
  private readonly idField: string;
  private readonly store: OfflineStore;
  private readonly key: string;
  private state?: OfflineState;
  private chain: Promise<unknown> = Promise.resolve();

  constructor(ds: DroidServerBase, name: string, path: string, idField: string, store: OfflineStore, key: string) {
    this.ds = ds; this.name = name; this.path = path; this.idField = idField; this.store = store; this.key = key;
  }

  private run<R>(f: (s: OfflineState) => Promise<R>): Promise<R> {
    const p = this.chain.then(async () => f(await this.load()));
    this.chain = p.catch(() => undefined);
    return p;
  }

  private async load(): Promise<OfflineState> {
    if (!this.state) {
      const text = await this.store.get(this.key);
      this.state = text ? JSON.parse(text) : { cursor: 0, nextTemp: -1, records: {}, pending: [] };
    }
    return this.state!;
  }

  private save(s: OfflineState): Promise<void> { return this.store.set(this.key, JSON.stringify(s)); }

  /** Yereldeki kayıtlar (bekleyen değişiklikler dahil). */
  all(): Promise<T[]> { return this.run(async (s) => Object.values(s.records).map((x) => x.r as T)); }
  get(id: number): Promise<T | undefined> { return this.run(async (s) => s.records[id]?.r as T | undefined); }
  /** Gönderilmeyi bekleyen değişiklik sayısı. */
  pendingCount(): Promise<number> { return this.run(async (s) => s.pending.length); }

  create(data: I): Promise<T> {
    return this.run(async (s) => {
      const fields = { ...(data as Record<string, unknown>) };
      const id = s.nextTemp--;
      const now = Date.now();
      const record = { ...fields, [this.idField]: id };
      s.records[id] = { v: 0, r: record };
      s.pending.push({ op: "create", id, base: 0, fields, times: Object.fromEntries(Object.keys(fields).map((k) => [k, now])), time: now });
      await this.save(s);
      return record as T;
    });
  }

  update(id: number, data: Partial<I>): Promise<T> {
    return this.run(async (s) => {
      const cur = s.records[id];
      if (!cur) throw new DroidServerError(404, "Kayıt yerelde yok: " + id);
      const fields = { ...(data as Record<string, unknown>) };
      const now = Date.now();
      cur.r = { ...cur.r, ...fields };
      const op = s.pending.find((p) => p.id === id);
      if (op) {
        Object.assign(op.fields, fields);
        for (const k of Object.keys(fields)) op.times[k] = now;
        op.time = now;
      } else {
        s.pending.push({ op: "update", id, base: cur.v, fields, times: Object.fromEntries(Object.keys(fields).map((k) => [k, now])), time: now });
      }
      await this.save(s);
      return cur.r as T;
    });
  }

  delete(id: number): Promise<void> {
    return this.run(async (s) => {
      const cur = s.records[id];
      if (!cur) return;
      delete s.records[id];
      const i = s.pending.findIndex((p) => p.id === id);
      const op = i >= 0 ? s.pending.splice(i, 1)[0] : undefined;
      // Henüz gönderilmemiş kayıt: sunucuya hiç gitmez.
      if (op?.op !== "create") s.pending.push({ op: "delete", id, base: op?.base ?? cur.v, fields: {}, times: {}, time: Date.now() });
      await this.save(s);
    });
  }

  /** Bekleyen değişiklikleri gönderir, sonra sunucudaki değişiklikleri çeker. Ağ hatasında hata fırlatır (bekleyenler kalır). */
  sync(): Promise<void> {
    return this.run(async (s) => {
      await this.push(s);
      await this.pull(s);
    });
  }

  private async push(s: OfflineState): Promise<void> {
    while (s.pending.length) {
      const batch = s.pending.slice(0, 100);
      const changes = batch.map((p) => ({ ...p, tempId: p.op === "create" ? p.id : undefined }));
      const r = await this.ds.request<{ results: Array<Record<string, any>> }>("POST", this.path, { json: { now: Date.now(), changes } });
      for (let n = 0; n < batch.length; n++) {
        const op = batch[n], o = r.results[n];
        s.pending.splice(s.pending.indexOf(op), 1);
        if (o.status === "ok" || o.status === "merged" || o.status === "conflict") {
          if (op.op === "create") delete s.records[op.id];
          if (o.record && o.id !== undefined) s.records[o.id] = { v: o.version ?? 0, r: o.record };
          else if (o.id !== undefined) delete s.records[o.id];
          if (o.status !== "ok") this.onConflict?.({ id: o.id ?? op.id, status: o.status, local: op.fields, server: o.record ?? null });
        } else if (o.status === "gone") {
          delete s.records[op.id];
        } else {
          const e = new DroidServerError(o.code ?? 0, o.message ?? "HTTP " + o.code, o.details ?? {});
          if ((o.code ?? 0) >= 500) {
            // Sunucu tarafı geçici hata: sonra yeniden denenir.
            s.pending.unshift(op);
            await this.save(s);
            throw e;
          }
          this.onRejected?.(op.id, e);
        }
      }
      await this.save(s);
    }
  }

  private async pull(s: OfflineState): Promise<void> {
    for (;;) {
      const query: Record<string, string> = { since: String(s.cursor), limit: "500" };
      if (s.epoch) query.epoch = s.epoch;
      const r = await this.ds.request<{ epoch: string; reset: boolean; next: number; more: boolean; changes: Array<{ id: number; version: number; action: string; record?: Record<string, unknown> }> }>(
        "GET", this.path, { query },
      );
      if (r.reset) {
        // Sunucudaki iz yenilendi: yerel kopya baştan kurulur (gönderilmemiş yeni kayıtlar kalır).
        for (const id of Object.keys(s.records)) if (Number(id) > 0) delete s.records[id];
        s.cursor = 0;
      }
      s.epoch = r.epoch;
      for (const c of r.changes) {
        if (s.pending.some((p) => p.id === c.id)) continue; // yerel değişiklik bekliyor
        if (c.action === "upsert" && c.record) s.records[c.id] = { v: c.version, r: c.record };
        else delete s.records[c.id];
      }
      s.cursor = r.next;
      await this.save(s);
      if (!r.more) break;
    }
  }

  /** Canlı abonelikle: sunucuda değişiklik olunca `sync()` çalışır (art arda olaylar birleşir). Dönen işlev durdurur. */
  live(onError?: (e: unknown) => void): () => void {
    let busy = false, again = false;
    const go = async () => {
      if (busy) { again = true; return; }
      busy = true;
      try {
        do {
          again = false;
          try { await this.sync(); } catch (e) { onError?.(e); }
        } while (again);
      } finally { busy = false; }
    };
    void go();
    return this.ds.subscribe([this.name], () => { void go(); }, onError);
  }
}
