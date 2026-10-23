
// MARK: - Çevrimdışı eşitleme (koleksiyonda `sync` açıkken)

/// Çevrimdışı deponun saklama yeri: anahtar → JSON metni. SQLite gibi başka bir depo bu protokolle takılabilir.
public protocol OfflineStore: Sendable {
    func read(_ key: String) async throws -> String?
    func write(_ key: String, _ value: String) async throws
}

/// Bellekte (uygulama kapanınca silinir; denemeler için).
public actor MemoryOfflineStore: OfflineStore {
    private var values: [String: String] = [:]

    public init() {}

    public func read(_ key: String) async throws -> String? { values[key] }
    public func write(_ key: String, _ value: String) async throws { values[key] = value }
}

/// Klasörde anahtar başına bir dosya (iOS'ta Application Support). Yazma atomik.
public struct FileOfflineStore: OfflineStore {
    public let directory: URL

    public init(directory: URL) { self.directory = directory }

    private func file(_ key: String) -> URL {
        let safe = String(key.map { $0.isLetter || $0.isNumber || "._-".contains($0) ? $0 : "_" })
        return directory.appendingPathComponent(safe + ".json")
    }

    public func read(_ key: String) async throws -> String? {
        let f = file(key)
        guard FileManager.default.fileExists(atPath: f.path) else { return nil }
        return try String(contentsOf: f, encoding: .utf8)
    }

    public func write(_ key: String, _ value: String) async throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data(value.utf8).write(to: file(key), options: .atomic)
    }
}

/// Eşitleme çakışması. `merged`: son yazan kazanır, bazı alanlarda sunucunun değeri kaldı; `conflict`: elle çözme kipinde
/// değişiklik uygulanmadı ya da silinen kayıt sunucuda daha yeni. `local` gönderilen alanlar, `server` kaydın sunucudaki
/// (artık yerelde de olan) hali. Elle çözmek için kaydı yeniden `update` edin.
public struct SyncConflictInfo: Sendable {
    public let id: Int64
    public let status: String
    public let local: [String: JSONValue]
    public let server: [String: JSONValue]?
}

private struct PendingOp: Codable, Sendable, Equatable {
    var op: String
    var id: Int64
    var base: Int64
    var fields: [String: JSONValue]
    var times: [String: Int64]
    var time: Int64
}

private struct StoredRecord: Codable, Sendable {
    var v: Int64
    var r: [String: JSONValue]
}

private struct OfflineState: Codable, Sendable {
    var cursor: Int64 = 0
    var epoch: String?
    var nextTemp: Int64 = -1
    var records: [String: StoredRecord] = [:]
    var pending: [PendingOp] = []
}

private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

/// Bir koleksiyonun çevrimdışı kopyası. Okuma ve yazma yereldir; yazmalar kayıt başına birleştirilerek bekler ve
/// `sync()` ile gönderilir, sonra sunucudaki değişiklikler çekilir. Çevrimdışı oluşturulan kaydın kimliği eşitlenene kadar
/// negatiftir. Dosya alanları yalnızca adıyla gelir (dosya indirilmez, çevrimdışıyken yüklenmez). Çevrimdışı oluşturulan
/// kayda başka koleksiyondan ilişki, kayıt eşitlendikten sonra kurulmalı. İşlemler sırayla çalışır.
public actor OfflineCollection<T: Decodable & Sendable, I: Encodable & Sendable> {
    public nonisolated let name: String
    private let client: DroidServerBase
    private let path: String
    private let idField: String
    private let store: OfflineStore
    private let key: String
    private let onConflict: (@Sendable (SyncConflictInfo) -> Void)?
    private let onRejected: (@Sendable (Int64, DroidServerError) -> Void)?
    private var state: OfflineState?
    private var tail: Task<Void, Never>?

    init(client: DroidServerBase, name: String, path: String, idField: String, store: OfflineStore, key: String,
         onConflict: (@Sendable (SyncConflictInfo) -> Void)?, onRejected: (@Sendable (Int64, DroidServerError) -> Void)?) {
        self.client = client
        self.name = name
        self.path = path
        self.idField = idField
        self.store = store
        self.key = key
        self.onConflict = onConflict
        self.onRejected = onRejected
    }

    /// İşlemler sırayla: eşitleme ağ beklerken yerel yazmalar araya girmez.
    private func serial<R: Sendable>(_ f: @escaping @Sendable () async throws -> R) async throws -> R {
        let previous = tail
        let task = Task { () async throws -> R in
            await previous?.value
            return try await f()
        }
        tail = Task { _ = try? await task.value }
        return try await task.value
    }

    private func load() async throws -> OfflineState {
        if let state { return state }
        let loaded = try await store.read(key).map { try droidServerDecoder.decode(OfflineState.self, from: Data($0.utf8)) } ?? OfflineState()
        state = loaded
        return loaded
    }

    private func save(_ s: OfflineState) async throws {
        state = s
        try await store.write(key, String(decoding: try droidServerEncoder.encode(s), as: UTF8.self))
    }

    private func decode(_ r: [String: JSONValue]) throws -> T { try droidServerDecoder.decode(T.self, from: try droidServerEncoder.encode(r)) }

    private func fields(_ data: I) throws -> [String: JSONValue] {
        try droidServerDecoder.decode([String: JSONValue].self, from: try droidServerEncoder.encode(data))
    }

    /// Yereldeki kayıtlar (bekleyen değişiklikler dahil).
    public func all() async throws -> [T] { try await serial { try await self.doAll() } }
    public func get(_ id: Int64) async throws -> T? { try await serial { try await self.doGet(id) } }
    /// Gönderilmeyi bekleyen değişiklik sayısı.
    public func pendingCount() async throws -> Int { try await serial { try await self.load().pending.count } }
    public func create(_ data: I) async throws -> T { try await serial { try await self.doCreate(data) } }
    public func update(_ id: Int64, _ data: I) async throws -> T { try await serial { try await self.doUpdate(id, data) } }
    public func delete(_ id: Int64) async throws { try await serial { try await self.doDelete(id) } }

    /// Bekleyen değişiklikleri gönderir, sonra sunucudaki değişiklikleri çeker. Ağ hatasında hata fırlatır (bekleyenler kalır).
    public func sync() async throws {
        try await serial {
            try await self.push()
            try await self.pull()
        }
    }

    private func doAll() async throws -> [T] { try await load().records.values.map { try decode($0.r) } }

    private func doGet(_ id: Int64) async throws -> T? { try await load().records[String(id)].map { try decode($0.r) } }

    private func doCreate(_ data: I) async throws -> T {
        var s = try await load()
        let f = try fields(data)
        let id = s.nextTemp
        s.nextTemp -= 1
        let now = nowMs()
        var record = f
        record[idField] = .number(Double(id))
        s.records[String(id)] = StoredRecord(v: 0, r: record)
        s.pending.append(PendingOp(op: "create", id: id, base: 0, fields: f, times: f.mapValues { _ in now }, time: now))
        try await save(s)
        return try decode(record)
    }

    private func doUpdate(_ id: Int64, _ data: I) async throws -> T {
        var s = try await load()
        guard var cur = s.records[String(id)] else { throw DroidServerError(404, "Kayıt yerelde yok: \(id)") }
        let f = try fields(data)
        let now = nowMs()
        cur.r.merge(f) { _, new in new }
        s.records[String(id)] = cur
        if let i = s.pending.firstIndex(where: { $0.id == id }) {
            s.pending[i].fields.merge(f) { _, new in new }
            for k in f.keys { s.pending[i].times[k] = now }
            s.pending[i].time = now
        } else {
            s.pending.append(PendingOp(op: "update", id: id, base: cur.v, fields: f, times: f.mapValues { _ in now }, time: now))
        }
        try await save(s)
        return try decode(cur.r)
    }

    private func doDelete(_ id: Int64) async throws {
        var s = try await load()
        guard let cur = s.records.removeValue(forKey: String(id)) else { return }
        let op = s.pending.firstIndex(where: { $0.id == id }).map { s.pending.remove(at: $0) }
        // Henüz gönderilmemiş kayıt: sunucuya hiç gitmez.
        if op?.op != "create" {
            s.pending.append(PendingOp(op: "delete", id: id, base: op?.base ?? cur.v, fields: [:], times: [:], time: nowMs()))
        }
        try await save(s)
    }

    private func push() async throws {
        var s = try await load()
        while !s.pending.isEmpty {
            let batch = Array(s.pending.prefix(100))
            let changes: [JSONValue] = batch.map { p in
                var o: [String: JSONValue] = [
                    "op": .string(p.op), "id": .number(Double(p.id)), "base": .number(Double(p.base)), "fields": .object(p.fields),
                    "times": .object(p.times.mapValues { .number(Double($0)) }), "time": .number(Double(p.time)),
                ]
                if p.op == "create" { o["tempId"] = .number(Double(p.id)) }
                return .object(o)
            }
            let body = try droidServerEncoder.encode(JSONValue.object(["now": .number(Double(nowMs())), "changes": .array(changes)]))
            let response = try droidServerDecoder.decode(JSONValue.self, from: try await client.request("POST", path, json: body) ?? Data())
            let results = response["results"]?.array ?? []
            for (op, o) in zip(batch, results) {
                s.pending.removeAll { $0 == op }
                let status = o["status"]?.string ?? "error"
                let id = o["id"]?.int
                var server: [String: JSONValue]?
                if case .object(let r)? = o["record"] { server = r }
                switch status {
                case "ok", "merged", "conflict":
                    if op.op == "create" { s.records.removeValue(forKey: String(op.id)) }
                    if let id {
                        if let server { s.records[String(id)] = StoredRecord(v: o["version"]?.int ?? 0, r: server) }
                        else { s.records.removeValue(forKey: String(id)) }
                    }
                    if status != "ok" { onConflict?(SyncConflictInfo(id: id ?? op.id, status: status, local: op.fields, server: server)) }
                case "gone":
                    s.records.removeValue(forKey: String(op.id))
                default:
                    let code = Int(o["code"]?.number ?? 0)
                    var details: [String: String] = [:]
                    if case .object(let d)? = o["details"] { for (k, v) in d { details[k] = v.string ?? "\(v)" } }
                    let e = DroidServerError(code, o["message"]?.string ?? "HTTP \(code)", details)
                    if code >= 500 {
                        // Sunucu tarafı geçici hata: sonra yeniden denenir.
                        s.pending.insert(op, at: 0)
                        try await save(s)
                        throw e
                    }
                    onRejected?(op.id, e)
                }
            }
            try await save(s)
        }
    }

    private func pull() async throws {
        var s = try await load()
        while true {
            var query = ["since": String(s.cursor), "limit": "500"]
            if let epoch = s.epoch { query["epoch"] = epoch }
            let r = try droidServerDecoder.decode(JSONValue.self, from: try await client.request("GET", path, query: query) ?? Data())
            if case .bool(true)? = r["reset"] {
                // Sunucudaki iz yenilendi: yerel kopya baştan kurulur (gönderilmemiş yeni kayıtlar kalır).
                s.records = s.records.filter { (Int64($0.key) ?? 0) < 0 }
                s.cursor = 0
            }
            s.epoch = r["epoch"]?.string
            for c in r["changes"]?.array ?? [] {
                guard let id = c["id"]?.int else { continue }
                if s.pending.contains(where: { $0.id == id }) { continue } // yerel değişiklik bekliyor
                if c["action"]?.string == "upsert", case .object(let record)? = c["record"] {
                    s.records[String(id)] = StoredRecord(v: c["version"]?.int ?? 0, r: record)
                } else {
                    s.records.removeValue(forKey: String(id))
                }
            }
            s.cursor = r["next"]?.int ?? s.cursor
            try await save(s)
            guard case .bool(true)? = r["more"] else { break }
        }
    }

    /// Canlı abonelikle: sunucuda değişiklik olunca `sync()` çalışır. Dönen görevi iptal etmek durdurur.
    public nonisolated func live(onError: (@Sendable (Error) -> Void)? = nil) -> Task<Void, Never> {
        Task {
            do { try await self.sync() } catch { onError?(error) }
            for await _ in client.subscribe([name], onError: onError) {
                do { try await self.sync() } catch { onError?(error) }
            }
        }
    }
}
