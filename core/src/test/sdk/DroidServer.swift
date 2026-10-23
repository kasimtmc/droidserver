// DroidServer istemcisi: "SDK deneme" sunucusu için üretildi (konfigürasyon v21).
// Konfigürasyon değişince yeniden indirin: GET <API>/_sdk/{ts|kotlin|dart|swift} (yönetici) veya uygulama/panel.
// Gerekenler: Swift 5.9+ (async/await); bağımlılık yok. Apple platformları ve Linux (FoundationNetworking).
//
// Kullanım:
//   let ds = DroidServerClient("https://telefon.example.com/api", apiKey: "…")   // veya token / auth.login
//   let page = try await ds.todoItems.list(ListOptions(sort: "-id", filter: ["done": "false"]))
//   for await e in ds.subscribe(["todo_items"]) { print(e.action, e.record) }

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// `todo_items` kaydı. Gizli alanlar yetkiye göre gelmeyebilir; bu yüzden alanlar boş olabilir.
public struct TodoItems: Decodable, Sendable {
    public let id: Int64
    public let title: String?
    public let done: Bool?
    public let qty: Int64?
    public let price: Double?
    public let status: String?
    public let tags: [String]?
    public let meta: JSONValue?
    public let `in`: String?
    public let due: String?
    public let place: GeoPoint?
    public let photo: String?
    public let total: Double?
    public let owner: Int64?
    public let olusturulma: String?
    public let updated_at: String?
    public let expand: [String: JSONValue]?
}

/// `todo_items` oluşturma/güncelleme gövdesi: verilmeyen (nil) alanlar gönderilmez. Boşaltmak için `RecordCollection.updateRaw`.
public struct TodoItemsInput: Encodable, Sendable {
    public var title: String?
    public var done: Bool?
    public var qty: Int64?
    public var price: Double?
    public var status: String?
    public var tags: [String]?
    public var meta: JSONValue?
    public var `in`: String?
    public var due: String?
    public var place: GeoPoint?
    public var owner: Int64?

    public init(title: String? = nil, done: Bool? = nil, qty: Int64? = nil, price: Double? = nil, status: String? = nil, tags: [String]? = nil, meta: JSONValue? = nil, `in`: String? = nil, due: String? = nil, place: GeoPoint? = nil, owner: Int64? = nil) {
        self.title = title
        self.done = done
        self.qty = qty
        self.price = price
        self.status = status
        self.tags = tags
        self.meta = meta
        self.`in` = `in`
        self.due = due
        self.place = place
        self.owner = owner
    }
}

/// SDK deneme istemcisi. `baseUrl`: API adresi, örn. `https://telefon.example.com/api`; `fallbackUrls`: canlı
/// kopyaların adresleri (ana sunucuya ulaşılamazsa veya 503 dönerse sıradaki denenir). `readUrls`: okumaların gideceği
/// kopyalar; bir yazmadan sonra `readYourWritesMs` boyunca okumalar ana sunucudan.
public final class DroidServerClient: DroidServerBase, @unchecked Sendable {
    public init(
        _ baseUrl: String, apiKey: String? = nil, token: String? = nil, refreshToken: String? = nil, fallbackUrls: [String] = [],
        readUrls: [String] = [], readYourWritesMs: Int = 5000, session: URLSession = .shared,
    ) {
        super.init(
            baseUrl: baseUrl, apiKey: apiKey, token: token, refreshToken: refreshToken, realtimePath: "/realtime",
            refreshPath: "/auth/refresh", filesPath: "/files/{collection}/{id}/{name}", fallbackUrls: fallbackUrls,
            readUrls: readUrls, readYourWritesMs: readYourWritesMs, session: session,
        )
    }

    public var todoItems: RecordCollection<TodoItems, TodoItemsInput> {
        RecordCollection(self, "todo_items", CollectionPaths(list: "/collections/todo_items/records", view: "/collections/todo_items/records/{id}", create: "/collections/todo_items/records", update: "/collections/todo_items/records/{id}", delete: "/collections/todo_items/records/{id}", sync: "/sync/todo_items", idField: "id"))
    }

    public var auth: Auth {
        Auth(
            client: self, signupPath: "/auth/signup", loginPath: "/auth/login", logoutPath: "/auth/logout", mePath: "/auth/me",
            verify2faPath: nil, requestLoginCodePath: nil, loginCodePath: nil,
            requestSmsCodePath: nil, smsCodePath: nil, usagePath: "/auth/me/usage",
        )
    }

    public var teams: Teams {
        Teams(
            client: self, listPath: "/teams", createPath: "/teams", getPath: "/teams/{id}", updatePath: "/teams/{id}",
            deletePath: "/teams/{id}", addMemberPath: "/teams/{id}/members", updateMemberPath: "/teams/{id}/members/{user}",
            removeMemberPath: "/teams/{id}/members/{user}",
        )
    }

    /// QR / bağlantı metninden (`droidserver://connect?url=…&token=…` veya düz adres): JWT token, değilse API anahtarı olur.
    public static func fromLink(_ link: String) throws -> DroidServerClient {
        let l = try ConnectionLink.parse(link)
        let jwt = l.token.map { $0.split(separator: ".", omittingEmptySubsequences: false).count == 3 } ?? false
        return DroidServerClient(l.url, apiKey: jwt ? nil : l.token, token: jwt ? l.token : nil)
    }
}

// MARK: - Çalışma zamanı (her sunucu için aynı)

/// Herhangi bir JSON değeri (JSON alanları, `expand`, takım ve hesap yanıtları).
public enum JSONValue: Codable, Sendable, Equatable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .number(let n): try c.encode(n)
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    public subscript(key: String) -> JSONValue? {
        if case .object(let o) = self { return o[key] }
        return nil
    }

    public var string: String? { if case .string(let s) = self { return s }; return nil }
    public var number: Double? { if case .number(let n) = self { return n }; return nil }
    public var int: Int64? { number.map { Int64($0) } }
    public var array: [JSONValue]? { if case .array(let a) = self { return a }; return nil }
}

/// Sayfalı liste.
public struct Page<T: Decodable & Sendable>: Decodable, Sendable {
    public let items: [T]
    public let page: Int
    public let perPage: Int
    public let totalItems: Int64
    public let totalPages: Int64
}

/// Konum alanı; filtre: `["konum[near]": "41.01,28.97,5"]` (km, yakından uzağa) veya `["konum[box]": "güney,batı,kuzey,doğu"]`.
public struct GeoPoint: Codable, Sendable, Equatable {
    public var lat: Double
    public var lng: Double
    public var accuracy: Double?

    public init(lat: Double, lng: Double, accuracy: Double? = nil) {
        self.lat = lat
        self.lng = lng
        self.accuracy = accuracy
    }
}

/// Sunucunun hata yanıtı (`status`, `message`, alan başına `details`).
public struct DroidServerError: Error, CustomStringConvertible, Sendable {
    public let status: Int
    public let message: String
    public let details: [String: String]

    public init(_ status: Int, _ message: String, _ details: [String: String] = [:]) {
        self.status = status
        self.message = message
        self.details = details
    }

    public var description: String { "DroidServerError(\(status)): \(message)" + (details.isEmpty ? "" : " \(details)") }
}

/// Giriş sonucu: `tokens` ya da iki adımlı doğrulama açıksa `challenge` (`Auth.verify2fa` ile tamamlayın).
public struct LoginResult: Sendable {
    public let tokens: [String: JSONValue]?
    public let challenge: String?
}

public struct RealtimeEvent: Sendable {
    public let action: String
    public let collection: String
    public let record: [String: JSONValue]
}

/// Koleksiyonun uç yolları; `sync` eşitleme kapalıysa nil, `idField` kimlik alanının API adı.
public struct CollectionPaths: Sendable {
    public let list: String?, view: String?, create: String?, update: String?, delete: String?, sync: String?
    public let idField: String

    public init(list: String?, view: String?, create: String?, update: String?, delete: String?, sync: String? = nil, idField: String = "id") {
        self.list = list
        self.view = view
        self.create = create
        self.update = update
        self.delete = delete
        self.sync = sync
        self.idField = idField
    }
}

/// Liste seçenekleri. `filter`: `["done": "false", "qty[gt]": "0", "tags": "yeni"]` (sorgu parametresi biçiminde).
public struct ListOptions: Sendable {
    public var page: Int?
    public var perPage: Int?
    public var sort: String?
    public var q: String?
    public var fields: [String]?
    public var expand: [String]?
    public var filter: [String: String]

    public init(page: Int? = nil, perPage: Int? = nil, sort: String? = nil, q: String? = nil, fields: [String]? = nil, expand: [String]? = nil, filter: [String: String] = [:]) {
        self.page = page
        self.perPage = perPage
        self.sort = sort
        self.q = q
        self.fields = fields
        self.expand = expand
        self.filter = filter
    }

    func query() -> [String: String] {
        var q = filter
        if let page { q["page"] = String(page) }
        if let perPage { q["perPage"] = String(perPage) }
        if let sort { q["sort"] = sort }
        if let v = self.q { q["q"] = v }
        if let fields { q["fields"] = fields.joined(separator: ",") }
        if let expand { q["expand"] = expand.joined(separator: ",") }
        return q
    }
}

/// Çok parçalı gövde: alanlar ve dosyalar.
public struct Multipart: Sendable {
    let fields: [String: String]
    let files: [String: [URL]]
    let boundary = "droidserver" + UUID().uuidString

    func body() throws -> Data {
        var out = Data()
        func line(_ s: String) { out.append((s + "\r\n").data(using: .utf8)!) }
        for (k, v) in fields.sorted(by: { $0.key < $1.key }) {
            line("--" + boundary); line("Content-Disposition: form-data; name=\"\(k)\""); line(""); line(v)
        }
        for (k, list) in files.sorted(by: { $0.key < $1.key }) {
            for f in list {
                line("--" + boundary)
                line("Content-Disposition: form-data; name=\"\(k)\"; filename=\"\(f.lastPathComponent.replacingOccurrences(of: "\"", with: ""))\"")
                line("Content-Type: application/octet-stream"); line("")
                out.append(try Data(contentsOf: f))
                line("")
            }
        }
        line("--" + boundary + "--")
        return out
    }
}

let droidServerDecoder = JSONDecoder()
let droidServerEncoder = JSONEncoder()

private func percent(_ s: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
}

/// HTTP katmanı: yetki başlığı, JSON/çok parçalı gövde, hata, 401'de bir kez token yenileme. async/await; Apple
/// platformları ve Linux (FoundationNetworking).
public class DroidServerBase: @unchecked Sendable {
    private let lock = NSLock()
    private let urls: [String]
    private var current = 0
    private let reads: [String]
    private var nextRead = 0
    private var primaryUntil = Date.distantPast
    private let readYourWrites: TimeInterval
    private var _apiKey: String?
    private var _token: String?
    private var _refreshToken: String?
    private var _onTokens: (@Sendable (String?, String?) -> Void)?
    let realtimePath: String?
    let refreshPath: String?
    let filesPath: String?
    let session: URLSession

    init(
        baseUrl: String, apiKey: String?, token: String?, refreshToken: String?, realtimePath: String?, refreshPath: String?, filesPath: String?,
        fallbackUrls: [String], readUrls: [String] = [], readYourWritesMs: Int = 5000, session: URLSession,
    ) {
        urls = ([baseUrl] + fallbackUrls).map { $0.hasSuffix("/") ? String($0.dropLast()) : $0 }
        reads = readUrls.map { $0.hasSuffix("/") ? String($0.dropLast()) : $0 }
        readYourWrites = TimeInterval(readYourWritesMs) / 1000
        _apiKey = apiKey
        _token = token
        _refreshToken = refreshToken
        self.realtimePath = realtimePath
        self.refreshPath = refreshPath
        self.filesPath = filesPath
        self.session = session
    }

    private func locked<T>(_ f: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return f() }

    public var apiKey: String? { get { locked { _apiKey } } set { locked { _apiKey = newValue } } }
    public var token: String? { get { locked { _token } } set { locked { _token = newValue } } }
    public var refreshToken: String? { get { locked { _refreshToken } } set { locked { _refreshToken = newValue } } }

    /// Token'lar değişince (giriş, yenileme, çıkış): saklamak için.
    public var onTokens: (@Sendable (String?, String?) -> Void)? { get { locked { _onTokens } } set { locked { _onTokens = newValue } } }

    /// Şu an kullanılan sunucu adresi (yedek adreslerden biri olabilir).
    public var baseUrl: String { locked { urls[current] } }

    public func setTokens(_ token: String?, _ refreshToken: String?) {
        let f: (@Sendable (String?, String?) -> Void)? = locked {
            _token = token
            _refreshToken = refreshToken
            return _onTokens
        }
        f?(token, refreshToken)
    }

    private var authorization: String? { locked { _token ?? _apiKey }.map { "Bearer " + $0 } }

    func url(_ base: String, _ path: String, _ query: [String: String]) -> URL {
        let qs = query.isEmpty ? "" : "?" + query.sorted(by: { $0.key < $1.key }).map { percent($0.key) + "=" + percent($0.value) }.joined(separator: "&")
        return URL(string: base + path + qs)!
    }

    /// Ham istek; `json` (kodlanmış) veya `multipart` gövde. Yanıt gövdesi (boşsa nil). Yedek adres varsa: bağlantı
    /// kurulamazsa veya 503 dönerse (salt okunur kopya) sıradaki denenir, çalışan adres hatırlanır.
    public func request(_ method: String, _ path: String, query: [String: String] = [:], json: Data? = nil, multipart: Multipart? = nil, retry: Bool = true) async throws -> Data? {
        // Okuma kopyalara (sırayla); yazmadan sonra kısa süre ana sunucudan ("kendi yazdığını oku").
        if method == "GET" && !reads.isEmpty && Date() >= locked({ primaryUntil }) {
            for _ in 0..<reads.count {
                let url = locked { () -> String in
                    defer { nextRead += 1 }
                    return reads[nextRead % reads.count]
                }
                do {
                    return try await requestAt(url, method, path, query, json, multipart, retry)
                } catch let e as DroidServerError {
                    if e.status != 503 { throw e }
                } catch is URLError {
                    // sıradaki
                }
            }
        }
        if method != "GET" { locked { primaryUntil = Date().addingTimeInterval(readYourWrites) } }
        var failed: DroidServerError?
        var networkError: Error?
        let start = locked { current }
        for n in 0..<urls.count {
            let i = (start + n) % urls.count
            do {
                let data = try await requestAt(urls[i], method, path, query, json, multipart, retry)
                locked { current = i }
                return data
            } catch let e as DroidServerError {
                if e.status != 503 {
                    locked { current = i }
                    throw e
                }
                if failed == nil { failed = e }
            } catch is URLError {
                if networkError == nil { networkError = URLError(.cannotConnectToHost) }
            }
        }
        if let failed { throw failed }
        throw networkError!
    }

    private func requestAt(_ base: String, _ method: String, _ path: String, _ query: [String: String], _ json: Data?, _ multipart: Multipart?, _ retry: Bool) async throws -> Data? {
        var r = URLRequest(url: url(base, path, query))
        r.httpMethod = method
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        if let a = authorization { r.setValue(a, forHTTPHeaderField: "Authorization") }
        if let json {
            r.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            r.httpBody = json
        } else if let multipart {
            r.setValue("multipart/form-data; boundary=" + multipart.boundary, forHTTPHeaderField: "Content-Type")
            r.httpBody = try multipart.body()
        }
        let (data, response) = try await session.data(for: r)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let canRefresh = locked { _token != nil && _refreshToken != nil }
        if status == 401 && retry && canRefresh, let refreshPath, path != refreshPath, await tryRefresh() {
            return try await requestAt(base, method, path, query, json, multipart, false)
        }
        if status >= 400 {
            let o = (try? droidServerDecoder.decode(JSONValue.self, from: data)) ?? .null
            var details: [String: String] = [:]
            if case .object(let d)? = o["details"] { for (k, v) in d { details[k] = v.string ?? "\(v)" } }
            throw DroidServerError(status, o["message"]?.string ?? "HTTP \(status)", details)
        }
        return data.isEmpty ? nil : data
    }

    private func tryRefresh() async -> Bool {
        guard let refreshPath, let refresh = refreshToken else { return false }
        do {
            let body = try droidServerEncoder.encode(["refreshToken": refresh])
            let t = try droidServerDecoder.decode([String: JSONValue].self, from: try await request("POST", refreshPath, json: body, retry: false) ?? Data())
            guard let token = t["token"]?.string, let next = t["refreshToken"]?.string else { throw DroidServerError(401, "Yenilenemedi") }
            setTokens(token, next)
            return true
        } catch {
            setTokens(nil, nil)
            return false
        }
    }

    /// İsteği yapıp JSON nesnesi döndürür (takım ve hesap yanıtları).
    func object(_ method: String, _ path: String, _ body: [String: JSONValue]? = nil) async throws -> [String: JSONValue] {
        let data = try await request(method, path, json: try body.map { try droidServerEncoder.encode($0) })
        return try droidServerDecoder.decode([String: JSONValue].self, from: data ?? Data("{}".utf8))
    }

    /// Dosyanın adresi (`thumb`: örn. "100x100").
    public func fileUrl(_ collection: String, _ id: Int64, _ name: String, thumb: String? = nil) -> String? {
        guard let p = filesPath else { return nil }
        let path = p.replacingOccurrences(of: "{collection}", with: percent(collection))
            .replacingOccurrences(of: "{id}", with: String(id))
            .replacingOccurrences(of: "{name}", with: percent(name))
        return baseUrl + path + (thumb.map { "?thumb=" + percent($0) } ?? "")
    }

    /// Canlı abonelik (Server-Sent Events): bağlantı koparsa kaldığı olaydan devam eder; hatalar `onError`'a gelir.
    /// Akışı dinlemeyi bırakmak (görevi iptal etmek) aboneliği kapatır.
    public func subscribe(_ collections: [String], onError: (@Sendable (Error) -> Void)? = nil) -> AsyncStream<RealtimeEvent> {
        AsyncStream { continuation in
            let task = Task { [self] in
                guard let path = realtimePath else {
                    onError?(DroidServerError(404, "Canlı abonelik kapalı"))
                    continuation.finish()
                    return
                }
                let lastId = EventId()
                while !Task.isCancelled {
                    do {
                        var r = URLRequest(url: url(baseUrl, path, ["collections": collections.joined(separator: ",")]))
                        r.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                        r.timeoutInterval = 3600
                        if let a = authorization { r.setValue(a, forHTTPHeaderField: "Authorization") }
                        if let id = lastId.value { r.setValue(id, forHTTPHeaderField: "Last-Event-ID") }
                        try await EventSource.run(r) { event, data, id in
                            if let id { lastId.value = id }
                            guard event == "message", !data.isEmpty,
                                  case .object(let o)? = try? droidServerDecoder.decode(JSONValue.self, from: Data(data.utf8)),
                                  let action = o["action"]?.string, let collection = o["collection"]?.string, case .object(let record)? = o["record"] else { return }
                            continuation.yield(RealtimeEvent(action: action, collection: collection, record: record))
                        }
                    } catch {
                        if !Task.isCancelled { onError?(error) }
                    }
                    if !Task.isCancelled { try? await Task.sleep(nanoseconds: 3_000_000_000) }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

private final class EventId: @unchecked Sendable {
    private let lock = NSLock()
    private var _value: String?
    var value: String? {
        get { lock.lock(); defer { lock.unlock() }; return _value }
        set { lock.lock(); _value = newValue; lock.unlock() }
    }
}

/// Server-Sent Events okuyucu: veri geldikçe satırlara ayırır (Linux'ta `URLSession.bytes` olmadığı için temsilciyle).
private final class EventSource: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let onEvent: @Sendable (String, String, String?) -> Void
    private var buffer = Data()
    private var event = "message"
    private var data = ""
    private var id: String?
    private var done: CheckedContinuation<Void, Error>?
    private var status = 0
    private let lock = NSLock()

    private init(onEvent: @escaping @Sendable (String, String, String?) -> Void) {
        self.onEvent = onEvent
    }

    static func run(_ request: URLRequest, onEvent: @escaping @Sendable (String, String, String?) -> Void) async throws {
        let source = EventSource(onEvent: onEvent)
        let session = URLSession(configuration: .default, delegate: source, delegateQueue: nil)
        let task = session.dataTask(with: request)
        defer { session.invalidateAndCancel() }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                source.lock.lock()
                source.done = c
                source.lock.unlock()
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    private func finish(_ error: Error?) {
        lock.lock()
        let c = done
        done = nil
        lock.unlock()
        if let error { c?.resume(throwing: error) } else { c?.resume() }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status != 200 {
            completionHandler(.cancel)
            finish(DroidServerError(status, "Canlı abonelik açılamadı"))
            return
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        buffer.append(chunk)
        while let nl = buffer.firstIndex(of: 0x0A) {
            var lineData = buffer[buffer.startIndex..<nl]
            if lineData.last == 0x0D { lineData = lineData.dropLast() }
            buffer.removeSubrange(buffer.startIndex...nl)
            let line = String(decoding: lineData, as: UTF8.self)
            if line.isEmpty {
                onEvent(event, data, id)
                event = "message"
                data = ""
            } else if line.hasPrefix("id: ") {
                id = String(line.dropFirst(4))
            } else if line.hasPrefix("event: ") {
                event = String(line.dropFirst(7))
            } else if line.hasPrefix("data: ") {
                data += line.dropFirst(6)
            }
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        finish(error)
    }
}

public struct RecordCollection<T: Decodable & Sendable, I: Encodable & Sendable>: Sendable {
    let client: DroidServerBase
    public let name: String
    let paths: CollectionPaths

    public init(_ client: DroidServerBase, _ name: String, _ paths: CollectionPaths) {
        self.client = client
        self.name = name
        self.paths = paths
    }

    private func at(_ p: String?, _ key: String, _ id: Int64? = nil) throws -> String {
        guard let p else { throw DroidServerError(404, "'\(name)' koleksiyonunda bu uç kapalı: \(key)") }
        return id.map { p.replacingOccurrences(of: "{id}", with: String($0)) } ?? p
    }

    private func record(_ d: Data?) throws -> T { try droidServerDecoder.decode(T.self, from: d ?? Data()) }

    private func body(_ data: I, _ files: [String: [URL]]) throws -> (Data?, Multipart?) {
        let json = try droidServerEncoder.encode(data)
        if files.isEmpty { return (json, nil) }
        var fields: [String: String] = [:]
        if case .object(let o) = try droidServerDecoder.decode(JSONValue.self, from: json) {
            for (k, v) in o {
                switch v {
                case .string(let s): fields[k] = s
                case .null: fields[k] = ""
                default: fields[k] = String(decoding: try droidServerEncoder.encode(v), as: UTF8.self)
                }
            }
        }
        return (nil, Multipart(fields: fields, files: files))
    }

    public func list(_ options: ListOptions = ListOptions()) async throws -> Page<T> {
        try droidServerDecoder.decode(Page<T>.self, from: try await client.request("GET", try at(paths.list, "list"), query: options.query()) ?? Data())
    }

    public func get(_ id: Int64, expand: [String]? = nil) async throws -> T {
        try record(try await client.request("GET", try at(paths.view, "view", id), query: expand.map { ["expand": $0.joined(separator: ",")] } ?? [:]))
    }

    /// `files`: dosya alanı → dosyalar.
    public func create(_ data: I, files: [String: [URL]] = [:]) async throws -> T {
        let (json, multipart) = try body(data, files)
        return try record(try await client.request("POST", try at(paths.create, "create"), json: json, multipart: multipart))
    }

    public func update(_ id: Int64, _ data: I, files: [String: [URL]] = [:]) async throws -> T {
        let (json, multipart) = try body(data, files)
        return try record(try await client.request("PATCH", try at(paths.update, "update", id), json: json, multipart: multipart))
    }

    /// Ham güncelleme (örn. alanı boşaltmak: `["note": .null]`).
    public func updateRaw(_ id: Int64, _ body: [String: JSONValue]) async throws -> T {
        try record(try await client.request("PATCH", try at(paths.update, "update", id), json: try droidServerEncoder.encode(body)))
    }

    public func delete(_ id: Int64) async throws {
        _ = try await client.request("DELETE", try at(paths.delete, "delete", id))
    }

    /// Çevrimdışı kopya (koleksiyonda eşitleme açık olmalı). `key`: depodaki anahtar; sunucu başına farklı verin.
    public func offline(
        _ store: OfflineStore, key: String? = nil,
        onConflict: (@Sendable (SyncConflictInfo) -> Void)? = nil, onRejected: (@Sendable (Int64, DroidServerError) -> Void)? = nil,
    ) throws -> OfflineCollection<T, I> {
        OfflineCollection(
            client: client, name: name, path: try at(paths.sync, "sync"), idField: paths.idField, store: store, key: key ?? "droidserver-" + name,
            onConflict: onConflict, onRejected: onRejected,
        )
    }
}

/// Abonelikler: planlar, ödeme sayfası (rol ödeme bildirimi gelince verilir) ve kullanıcının abonelikleri. Yanıtlar JSON.
public struct Payments: Sendable {
    let client: DroidServerBase
    let plansPath: String?, subscribePath: String?, subscriptionsPath: String?

    private func at(_ p: String?, _ key: String) throws -> String {
        guard let p else { throw DroidServerError(404, "Bu ödeme ucu kapalı: \(key)") }
        return p
    }

    /// `{items: [{name, title, provider, role}]}`
    public func plans() async throws -> [String: JSONValue] { try await client.object("GET", try at(plansPath, "plans")) }

    /// `{plan, url}`: kullanıcıyı ödeme sayfasına (`url`) yönlendirin.
    public func subscribe(_ plan: String) async throws -> [String: JSONValue] { try await client.object("POST", try at(subscribePath, "subscribe"), ["plan": .string(plan)]) }

    /// `{items: [{id, plan, provider, status, period_end, manage_url}]}`
    public func subscriptions() async throws -> [String: JSONValue] { try await client.object("GET", try at(subscriptionsPath, "subscriptions")) }
}

/// Takımlar (`team` kuralı): kullanıcının takımları; üye ekleme, rol ve silme takım sahibinindir. Yanıtlar JSON.
public struct Teams: Sendable {
    let client: DroidServerBase
    let listPath: String?, createPath: String?, getPath: String?, updatePath: String?, deletePath: String?
    let addMemberPath: String?, updateMemberPath: String?, removeMemberPath: String?

    private func at(_ p: String?, _ key: String, _ id: Int64? = nil, _ user: Int64? = nil) throws -> String {
        guard let p else { throw DroidServerError(404, "Bu takım ucu kapalı: \(key)") }
        return p.replacingOccurrences(of: "{id}", with: id.map { String($0) } ?? "").replacingOccurrences(of: "{user}", with: user.map { String($0) } ?? "")
    }

    /// `{items: [{id, name, role, memberCount}]}`
    public func list() async throws -> [String: JSONValue] { try await client.object("GET", try at(listPath, "list")) }

    public func create(_ name: String) async throws -> [String: JSONValue] { try await client.object("POST", try at(createPath, "create"), ["name": .string(name)]) }

    /// Takım ve üyeleri: `{id, name, role, memberCount, members: [{user, email, role}]}`
    public func get(_ id: Int64) async throws -> [String: JSONValue] { try await client.object("GET", try at(getPath, "get", id)) }

    public func rename(_ id: Int64, _ name: String) async throws -> [String: JSONValue] {
        try await client.object("PATCH", try at(updatePath, "update", id), ["name": .string(name)])
    }

    public func delete(_ id: Int64) async throws { _ = try await client.request("DELETE", try at(deletePath, "delete", id)) }

    /// `email` veya `user` (kimlik); `role` `owner` veya `member`.
    public func addMember(_ id: Int64, email: String? = nil, user: Int64? = nil, role: String = "member") async throws -> [String: JSONValue] {
        var body: [String: JSONValue] = ["role": .string(role)]
        if let email { body["email"] = .string(email) }
        if let user { body["user"] = .number(Double(user)) }
        return try await client.object("POST", try at(addMemberPath, "addMember", id), body)
    }

    public func setRole(_ id: Int64, _ user: Int64, _ role: String) async throws -> [String: JSONValue] {
        try await client.object("PATCH", try at(updateMemberPath, "updateMember", id, user), ["role": .string(role)])
    }

    /// Sahip üyeyi çıkarır; kullanıcı kendi kimliğiyle çağırınca takımdan ayrılır.
    public func removeMember(_ id: Int64, _ user: Int64) async throws { _ = try await client.request("DELETE", try at(removeMemberPath, "removeMember", id, user)) }
}

public struct Auth: Sendable {
    let client: DroidServerBase
    let signupPath: String?, loginPath: String?, logoutPath: String?, mePath: String?, verify2faPath: String?
    let requestLoginCodePath: String?, loginCodePath: String?, requestSmsCodePath: String?, smsCodePath: String?, usagePath: String?

    private func at(_ p: String?, _ key: String) throws -> String {
        guard let p else { throw DroidServerError(404, "Bu hesap ucu kapalı: \(key)") }
        return p
    }

    private func keep(_ o: [String: JSONValue]) -> LoginResult {
        let token = o["token"]?.string
        if let token, let refresh = o["refreshToken"]?.string { client.setTokens(token, refresh) }
        return LoginResult(tokens: token == nil ? nil : o, challenge: o["challenge"]?.string)
    }

    /// Gövde: email, password ve ek alanlar.
    public func signup(_ body: [String: JSONValue]) async throws -> LoginResult { keep(try await client.object("POST", try at(signupPath, "signup"), body)) }

    public func login(_ email: String, _ password: String) async throws -> LoginResult {
        keep(try await client.object("POST", try at(loginPath, "login"), ["email": .string(email), "password": .string(password)]))
    }

    public func verify2fa(_ challenge: String, _ code: String) async throws -> LoginResult {
        keep(try await client.object("POST", try at(verify2faPath, "verify2fa"), ["challenge": .string(challenge), "code": .string(code)]))
    }

    public func requestLoginCode(_ email: String) async throws {
        _ = try await client.object("POST", try at(requestLoginCodePath, "requestLoginCode"), ["email": .string(email)])
    }

    public func loginWithCode(_ email: String, _ code: String) async throws -> LoginResult {
        keep(try await client.object("POST", try at(loginCodePath, "loginCode"), ["email": .string(email), "code": .string(code)]))
    }

    /// Numara uluslararası biçimde (+905551234567); kod sunucu telefonundan SMS ile gelir.
    public func requestSmsCode(_ phone: String) async throws {
        _ = try await client.object("POST", try at(requestSmsCodePath, "requestSmsCode"), ["phone": .string(phone)])
    }

    public func loginWithSms(_ phone: String, _ code: String) async throws -> LoginResult {
        keep(try await client.object("POST", try at(smsCodePath, "smsCode"), ["phone": .string(phone), "code": .string(code)]))
    }

    public func logout() async {
        if let r = client.refreshToken, let p = logoutPath {
            _ = try? await client.object("POST", p, ["refreshToken": .string(r)])
        }
        client.setTokens(nil, nil)
    }

    public func me() async throws -> [String: JSONValue] { try await client.object("GET", try at(mePath, "me")) }

    public func usage() async throws -> [String: JSONValue] { try await client.object("GET", try at(usagePath, "usage")) }
}

/// Telefondaki "Bağlan (QR)" ekranının metni: `droidserver://connect?url=…&name=…&token=…&exp=…` veya düz adres.
public struct ConnectionLink: Sendable {
    public let url: String
    public let name: String?
    public let token: String?
    public let expiresAtEpochSec: Int64?

    public static func parse(_ link: String) throws -> ConnectionLink {
        let t = link.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("http://") || t.hasPrefix("https://") {
            return ConnectionLink(url: t.hasSuffix("/") ? String(t.dropLast()) : t, name: nil, token: nil, expiresAtEpochSec: nil)
        }
        guard t.hasPrefix("droidserver://connect?"), let items = URLComponents(string: t)?.queryItems else {
            throw DroidServerError(400, "DroidServer bağlantısı değil")
        }
        var q: [String: String] = [:]
        for i in items { q[i.name] = i.value }
        guard let url = q["url"], url.hasPrefix("http://") || url.hasPrefix("https://") else { throw DroidServerError(400, "Bağlantıda adres yok") }
        return ConnectionLink(url: url, name: q["name"], token: q["token"], expiresAtEpochSec: q["exp"].flatMap { Int64($0) })
    }
}

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
