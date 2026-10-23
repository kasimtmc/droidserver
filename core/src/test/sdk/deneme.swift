// SdkClientTest'in Swift denemesi: üretilen istemci gerçek sunucuya karşı. Argümanlar: API adresi, yönetici anahtarı,
// ulaşılamayan bir adres. Başarıda "TAMAM" yazar. Derleme: swiftc -parse-as-library DroidServer.swift deneme.swift
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

struct Failure: Error, CustomStringConvertible { let description: String }

func check(_ ok: Bool, _ what: String) throws {
    if !ok { throw Failure(description: what) }
}

func status(_ f: () async throws -> Void) async -> Int {
    do { try await f() } catch let e as DroidServerError { return e.status } catch { return -1 }
    return 0
}

final class Saved: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String?] = []
    func add(_ t: String?) { lock.lock(); items.append(t); lock.unlock() }
    var all: [String?] { lock.lock(); defer { lock.unlock() }; return items }
}

@main
struct Deneme {
    static func main() async {
        do {
            try await run()
            print("TAMAM")
        } catch {
            print("HATA: \(error)")
            exit(1)
        }
    }

    static func run() async throws {
        let args = CommandLine.arguments
        let base = args[1], key = args[2], dead = args[3], reader = args[4]
        let enc = { (s: String) in s.addingPercentEncoding(withAllowedCharacters: .alphanumerics)! }

        let l = try ConnectionLink.parse("droidserver://connect?url=" + enc("http://x/api") + "&name=Evim&token=a.b.c&exp=1800000000")
        try check(l.url == "http://x/api" && l.name == "Evim" && l.token == "a.b.c" && l.expiresAtEpochSec == 1800000000, "bağlantı")
        let linked = try DroidServerClient.fromLink("droidserver://connect?url=" + enc(base) + "&token=ds_xyz")
        try check(linked.apiKey == "ds_xyz" && linked.token == nil && linked.baseUrl == base, "fromLink")
        try check(try DroidServerClient.fromLink("droidserver://connect?url=http%3A%2F%2Fx&token=a.b.c").token == "a.b.c", "fromLink jwt")

        let admin = DroidServerClient(base, apiKey: key)
        try check(try await admin.todoItems.list().totalItems == 0, "boş liste")

        let ds = DroidServerClient(base)
        let saved = Saved()
        ds.onTokens = { t, _ in saved.add(t) }
        let signup = try await ds.auth.signup(["email": .string("swift@example.com"), "password": .string("parola-1234")])
        try check(signup.tokens != nil && ds.token != nil, "kayıt")
        let myId = try await ds.auth.me()["id"]?.int

        // Canlı abonelik: oluşturma olayı gelir.
        let events = Task { () -> RealtimeEvent? in
            for await e in ds.subscribe(["todo_items"]) { return e }
            return nil
        }
        try await Task.sleep(nanoseconds: 500_000_000)

        let t = try await ds.todoItems.create(TodoItemsInput(title: "Süt", qty: 2, price: 1.5, status: "açık", tags: ["ev"], meta: .object(["a": .number(1)]), in: "x"))
        try check(t.total == 3.0, "hesaplanan alan")
        try check(t.owner == myId, "sahip alanı")
        try check(t.olusturulma != nil, "uzman modu adı")
        try check(t.in == "x" && t.tags == ["ev"] && t.meta?["a"]?.number == 1, "alanlar")
        let timeout = Task { try await Task.sleep(nanoseconds: 5_000_000_000); events.cancel() }
        let e = await events.value
        timeout.cancel()
        try check(e?.action == "create" && e?.collection == "todo_items" && e?.record["title"]?.string == "Süt", "canlı olay")

        // Liste, filtre, PATCH, alan boşaltma.
        _ = try await ds.todoItems.create(TodoItemsInput(title: "Ekmek", tags: ["iş"]))
        let page = try await ds.todoItems.list(ListOptions(sort: "-id", filter: ["tags": "ev"]))
        try check(page.items.map { $0.title } == ["Süt"], "filtre")
        let updated = try await ds.todoItems.update(t.id, TodoItemsInput(done: true, qty: 4))
        try check(updated.done == true && updated.total == 6.0, "güncelleme")
        try check(try await ds.todoItems.updateRaw(t.id, ["in": .null]).in == nil, "boşaltma")

        // Dosya yükleme ve adres.
        let png = FileManager.default.temporaryDirectory.appendingPathComponent("foto-\(UUID().uuidString).png")
        try Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] + [UInt8](repeating: 0, count: 64)).write(to: png)
        let withPhoto = try await ds.todoItems.create(TodoItemsInput(title: "Fotoğraf"), files: ["photo": [png]])
        try? FileManager.default.removeItem(at: png)
        let name = try withPhoto.photo ?? { throw Failure(description: "dosya adı yok") }()
        try check(ds.fileUrl("todo_items", withPhoto.id, name)!.hasSuffix("/api/files/todo_items/\(withPhoto.id)/\(name)"), "dosya adresi")

        // Doğrulama hatası alan başına; silinen kayıt 404.
        do {
            _ = try await ds.todoItems.create(TodoItemsInput(status: "yok"))
            try check(false, "doğrulama hatası bekleniyordu")
        } catch let x as DroidServerError {
            try check(x.status == 400 && x.details["title"] != nil && x.details["status"] != nil, "doğrulama \(x.details)")
        }
        try await ds.todoItems.delete(t.id)
        try check(await status { _ = try await ds.todoItems.get(t.id) } == 404, "silindi")

        // Erişim token'ı geçersizse yenileme token'ıyla bir kez yenilenip tekrarlanır.
        ds.token = "bozuk"
        try check(try await ds.todoItems.list().totalItems == 2, "yenileme")
        try check(saved.all.count >= 2 && saved.all.last! != "bozuk", "token saklandı")

        // Çevrimdışı kopya: yerel yazma, eşitleme, başka istemcinin değişikliği, çakışma, silme, depodan yeniden açma.
        let storeDir = FileManager.default.temporaryDirectory.appendingPathComponent("ds-offline-\(UUID().uuidString)")
        let conflicts = Saved()
        let off = try ds.todoItems.offline(FileOfflineStore(directory: storeDir), onConflict: { conflicts.add($0.status) })
        try await off.sync()
        let local = try await off.create(TodoItemsInput(title: "Çevrimdışı", qty: 1))
        let pendingBefore = try await off.pendingCount()
        try check(local.id < 0 && pendingBefore == 1, "geçici kimlik")
        _ = try await off.update(local.id, TodoItemsInput(qty: 2))
        try await off.sync()
        let synced = try await off.all().first { $0.title == "Çevrimdışı" }
        let pendingAfter = try await off.pendingCount()
        try check(synced != nil && synced!.id > 0 && synced!.qty == 2 && pendingAfter == 0, "eşitleme")
        let sid = synced!.id
        _ = try await ds.todoItems.update(sid, TodoItemsInput(title: "sunucuda"))
        try await off.sync()
        try check(try await off.get(sid)?.title == "sunucuda", "çekme")
        _ = try await off.update(sid, TodoItemsInput(title: "yerel", price: 9))
        try await Task.sleep(nanoseconds: 20_000_000)
        _ = try await ds.todoItems.update(sid, TodoItemsInput(title: "sunucu"))
        try await off.sync()
        let merged = try await off.get(sid)
        try check(merged?.title == "sunucu" && merged?.price == 9 && conflicts.all == ["merged"], "çakışma \(String(describing: merged)) \(conflicts.all)")
        try await off.delete(sid)
        try await off.sync()
        try check(try await ds.todoItems.offline(FileOfflineStore(directory: storeDir)).get(sid) == nil, "silme ve yeniden açma")
        try? FileManager.default.removeItem(at: storeDir)

        // Takımlar.
        let team = try await ds.teams.create("Mutfak")
        try check(team["role"]?.string == "owner", "takım rolü")
        let id = team["id"]!.int!
        try check(try await ds.teams.get(id)["members"]?.array?.count == 1, "takım üyeleri")
        try check(try await ds.teams.rename(id, "Ev")["name"]?.string == "Ev", "takım adı")
        try check(try await ds.teams.list()["items"]?.array?.count == 1, "takım listesi")
        try await ds.teams.delete(id)
        await ds.auth.logout()
        try check(ds.token == nil, "çıkış")
        try check(await status { _ = try await ds.todoItems.list() } == 401, "çıkıştan sonra 401")

        // Yedek adres: ana sunucuya ulaşılamıyor, sıradaki denenir ve hatırlanır.
        let fb = DroidServerClient(dead, apiKey: key, fallbackUrls: [base])
        _ = try await fb.todoItems.list()
        try check(fb.baseUrl == base, "yedek adres \(fb.baseUrl)")

        // Okuma kopyaya; yazmadan hemen sonra ana sunucuya.
        let rd = DroidServerClient(base, apiKey: key, readUrls: [reader], readYourWritesMs: 300)
        func health() async throws -> String? {
            try droidServerDecoder.decode(JSONValue.self, from: try await rd.request("GET", "/health") ?? Data())["name"]?.string
        }
        try check(try await health() == "Okuma", "okuma kopyaya")
        _ = try await rd.todoItems.create(TodoItemsInput(title: "okuma denemesi"))
        try check(try await health() == "SDK deneme", "kendi yazdığını oku")
        try await Task.sleep(nanoseconds: 400_000_000)
        try check(try await health() == "Okuma", "süre bitince yine kopya")
    }
}
