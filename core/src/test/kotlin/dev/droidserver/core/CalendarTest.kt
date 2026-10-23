package dev.droidserver.core

import dev.droidserver.core.api.droidServerModule
import dev.droidserver.core.calendar.ICal
import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CalendarConfig
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.ContactsConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.WebDavConfig
import dev.droidserver.core.db.SchemaMigrator
import dev.droidserver.core.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.ZoneId
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Faz 18.4: çakışma kuralı, .ics aboneliği, CalDAV ve CardDAV, iki lehçede. */
@RunWith(Parameterized::class)
class CalendarTest(private val dialect: Dialect) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun dialects() = Dialect.entries.toList()
    }

    private val key = "yonetici-anahtari-1234567890"
    private val token = "gizli-takvim-anahtari-42"
    private val bookings = CollectionConfig(
        "bookings",
        listOf(
            FieldConfig("title", FieldType.TEXT, required = true),
            FieldConfig("starts", FieldType.DATETIME, required = true),
            FieldConfig("ends", FieldType.DATETIME),
            FieldConfig("room", FieldType.TEXT),
            FieldConfig("notes", FieldType.TEXT),
        ),
        calendar = CalendarConfig("title", "starts", "ends", description = "notes", noOverlap = true, resource = "room", feed = true, feedToken = token, dav = true),
    )
    private val people = CollectionConfig(
        "people",
        listOf(FieldConfig("name", FieldType.TEXT, required = true), FieldConfig("email", FieldType.TEXT), FieldConfig("phone", FieldType.TEXT)),
        contacts = ContactsConfig("name", email = "email", phone = "phone"),
    )
    private val config = ServerConfig(
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(key)),
        collections = listOf(bookings, people),
        webdav = WebDavConfig(enabled = true, username = "droid", password = "dav-parolasi-1"),
    )
    private lateinit var db: SqlDriver

    @Before
    fun open() {
        db = TestDb.create(dialect).open()
    }

    @After
    fun close() = db.close()

    private suspend fun HttpClient.api(method: String, path: String, body: String? = null): HttpResponse = when (method) {
        "GET" -> get(path) { bearerAuth(key) }
        "PATCH" -> patch(path) { bearerAuth(key); contentType(ContentType.Application.Json); setBody(body ?: "{}") }
        else -> post(path) { bearerAuth(key); contentType(ContentType.Application.Json); setBody(body ?: "{}") }
    }

    private suspend fun HttpClient.book(title: String, from: String, to: String, room: String?): HttpResponse =
        api("POST", "/api/collections/bookings/records", """{"title":"$title","starts":"2026-11-02T$from:00Z","ends":"2026-11-02T$to:00Z"${room?.let { ",\"room\":\"$it\"" }.orEmpty()}}""")

    private val basic = "Basic " + Base64.getEncoder().encodeToString("droid:dav-parolasi-1".toByteArray())

    private suspend fun HttpClient.dav(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse =
        request(path) {
            this.method = HttpMethod.parse(method)
            header("Authorization", basic)
            header("X-Forwarded-Proto", "https")
            headers.forEach { (k, v) -> header(k, v) }
            body?.let { setBody(it) }
        }

    @Test
    fun `cakisma kurali ve takvim aboneligi`() = testApplication {
        SchemaMigrator(db).migrate(config.schemaCollections())
        application { droidServerModule(config, db) }
        assertEquals(201, client.book("Toplantı", "10:00", "11:00", "A").status.value)
        val clash = client.book("Çakışan", "10:30", "11:30", "A")
        assertEquals(409, clash.status.value, clash.bodyAsText())
        assertTrue("çakışıyor" in clash.bodyAsText())
        assertEquals(201, client.book("Başka oda", "10:30", "11:30", "B").status.value)
        val next = client.book("Uç uca", "11:00", "12:00", "A")
        assertEquals(201, next.status.value, "bitişik aralık çakışma sayılmaz")
        // Kaynaksız kayıtlar kendi aralarında.
        assertEquals(201, client.book("Odasız", "10:00", "11:00", null).status.value)
        assertEquals(409, client.book("Odasız 2", "10:15", "10:45", null).status.value)
        val id = Json.parseToJsonElement(next.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
        assertEquals(409, client.api("PATCH", "/api/collections/bookings/records/$id", """{"starts":"2026-11-02T10:45:00Z"}""").status.value)
        val backwards = client.api("PATCH", "/api/collections/bookings/records/$id", """{"ends":"2026-11-02T09:00:00Z"}""")
        assertEquals(400, backwards.status.value)
        assertTrue("ends" in backwards.bodyAsText())

        // Abonelik: yetkiyle veya gizli anahtarla.
        val feed = client.api("GET", "/api/calendar/bookings.ics")
        assertEquals(200, feed.status.value)
        assertTrue(feed.headers["Content-Type"]!!.startsWith("text/calendar"))
        val ics = feed.bodyAsText()
        assertEquals(4, Regex("BEGIN:VEVENT").findAll(ics).count(), ics)
        assertTrue("SUMMARY:Toplantı" in ics && "DTSTART:20261102T100000Z" in ics && "DTEND:20261102T110000Z" in ics, ics)
        assertEquals(200, client.get("/api/calendar/bookings.ics?token=$token").status.value)
        assertEquals(403, client.get("/api/calendar/bookings.ics?token=yanlis").status.value)
        assertEquals(401, client.get("/api/calendar/bookings.ics").status.value)
    }

    @Test
    fun `caldav ve carddav ile iki yonlu esitleme`() = testApplication {
        SchemaMigrator(db).migrate(config.schemaCollections())
        application { droidServerModule(config, db) }
        client.book("Sunucuda", "08:00", "09:00", null)

        // Keşif: kök → kullanıcı → takvim evi → takvimler.
        assertEquals(301, client.config { followRedirects = false }.get("/.well-known/caldav").status.value)
        assertEquals(401, client.request("/dav/") { method = HttpMethod.parse("PROPFIND") }.status.value)
        val root = client.dav("PROPFIND", "/dav/", """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>""", mapOf("Depth" to "0"))
        assertTrue("<D:current-user-principal><D:href>/dav/principal/</D:href>" in root.bodyAsText(), root.bodyAsText())
        val principal = client.dav("PROPFIND", "/dav/principal/", """<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:r="urn:ietf:params:xml:ns:carddav"><d:prop><c:calendar-home-set/><r:addressbook-home-set/><d:displayname/><c:schedule-inbox-URL/></d:prop></d:propfind>""", mapOf("Depth" to "0"))
        val p = principal.bodyAsText()
        assertEquals(207, principal.status.value)
        assertTrue("<C:calendar-home-set><D:href>/dav/calendars/</D:href>" in p && "<CR:addressbook-home-set><D:href>/dav/contacts/</D:href>" in p, p)
        assertTrue("<C:schedule-inbox-URL/></D:prop><D:status>HTTP/1.1 404 Not Found" in p, p)
        val home = client.dav("PROPFIND", "/dav/calendars/", """<d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"><d:prop><d:resourcetype/><cs:getctag/><d:current-user-privilege-set/></d:prop></d:propfind>""", mapOf("Depth" to "1")).bodyAsText()
        assertTrue("<D:href>/dav/calendars/bookings/</D:href>" in home && "<C:calendar/>" in home && "<D:write/>" in home, home)
        val ctag1 = Regex("<CS:getctag>([^<]+)</CS:getctag>").find(home)!!.groupValues[1]

        // Telefondan yeni etkinlik (kendi adı ve UID'siyle), istanbul saatiyle.
        val event = """BEGIN:VCALENDAR
            |VERSION:2.0
            |PRODID:-//test//EN
            |BEGIN:VEVENT
            |UID:telefon-uid-1
            |SUMMARY:Diş hekimi\, kontrol
            |DTSTART;TZID=Europe/Istanbul:20261103T140000
            |DURATION:PT45M
            |DESCRIPTION:Satır 1\nSatır 2
            |BEGIN:VALARM
            |TRIGGER:-PT15M
            |END:VALARM
            |END:VEVENT
            |END:VCALENDAR
            |""".trimMargin().replace("\n", "\r\n")
        assertEquals(201, client.dav("PUT", "/dav/calendars/bookings/abc-123.ics", event, mapOf("If-None-Match" to "*", "Content-Type" to "text/calendar")).status.value)
        assertEquals(412, client.dav("PUT", "/dav/calendars/bookings/abc-123.ics", event, mapOf("If-None-Match" to "*")).status.value)
        val records = Json.parseToJsonElement(client.api("GET", "/api/collections/bookings/records?title[like]=hekimi").bodyAsText()).jsonObject.getValue("items").jsonArray
        val rec = records.single().jsonObject
        assertEquals("Diş hekimi, kontrol", rec.getValue("title").jsonPrimitive.content)
        assertEquals("2026-11-03T11:00:00Z", rec.getValue("starts").jsonPrimitive.content)
        assertEquals("2026-11-03T11:45:00Z", rec.getValue("ends").jsonPrimitive.content)
        assertEquals("Satır 1\nSatır 2", rec.getValue("notes").jsonPrimitive.content)

        // Okuma: aynı ad ve UID; liste değişti.
        val got = client.dav("GET", "/dav/calendars/bookings/abc-123.ics")
        val etag = got.headers["ETag"]!!
        val body = got.bodyAsText()
        assertTrue("UID:telefon-uid-1" in body && "SUMMARY:Diş hekimi\\, kontrol" in body && "DTSTART:20261103T110000Z" in body, body)
        val multiget = client.dav("REPORT", "/dav/calendars/bookings/", """<c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><d:getetag/><c:calendar-data/></d:prop><d:href>/dav/calendars/bookings/abc-123.ics</d:href><d:href>/dav/calendars/bookings/yok.ics</d:href></c:calendar-multiget>""")
        val m = multiget.bodyAsText()
        assertEquals(207, multiget.status.value)
        assertTrue("<D:getetag>${etag.replace("\"", "&quot;")}</D:getetag>" in m && "UID:telefon-uid-1" in m && "404 Not Found" in m, m)
        val list = client.dav("PROPFIND", "/dav/calendars/bookings/", """<d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"><d:prop><d:getetag/><cs:getctag/></d:prop></d:propfind>""", mapOf("Depth" to "1")).bodyAsText()
        assertTrue("/dav/calendars/bookings/abc-123.ics" in list && Regex("/dav/calendars/bookings/\\d+\\.ics").containsMatchIn(list), list)
        assertFalse(ctag1 in list, "ctag değişmeli")

        // Güncelleme: eski ETag reddedilir; çakışan saat 409.
        val moved = event.replace("20261103T140000", "20261103T150000")
        assertEquals(412, client.dav("PUT", "/dav/calendars/bookings/abc-123.ics", moved, mapOf("If-Match" to "\"eski\"")).status.value)
        assertEquals(204, client.dav("PUT", "/dav/calendars/bookings/abc-123.ics", moved, mapOf("If-Match" to etag)).status.value)
        val clash = event.replace("abc", "x").replace("telefon-uid-1", "u2").replace("20261103T140000", "20261103T151500")
        assertEquals(409, client.dav("PUT", "/dav/calendars/bookings/xyz.ics", clash).status.value)

        // Silme.
        assertEquals(204, client.dav("DELETE", "/dav/calendars/bookings/abc-123.ics").status.value)
        assertEquals(404, client.dav("GET", "/dav/calendars/bookings/abc-123.ics").status.value)
        assertEquals("0", Json.parseToJsonElement(client.api("GET", "/api/collections/bookings/records?title[like]=hekimi").bodyAsText()).jsonObject.getValue("totalItems").jsonPrimitive.content)

        // Kişiler.
        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:kisi-1\r\nN:Yılmaz;Ayşe;;;\r\nFN:Ayşe Yılmaz\r\nitem1.EMAIL;TYPE=INTERNET:ayse@example.com\r\nTEL;TYPE=CELL:+905551112233\r\nEND:VCARD\r\n"
        assertEquals(201, client.dav("PUT", "/dav/contacts/people/k1.vcf", card).status.value)
        val person = Json.parseToJsonElement(client.api("GET", "/api/collections/people/records").bodyAsText()).jsonObject.getValue("items").jsonArray.single().jsonObject
        assertEquals("Ayşe Yılmaz", person.getValue("name").jsonPrimitive.content)
        assertEquals("ayse@example.com", person.getValue("email").jsonPrimitive.content)
        assertEquals("+905551112233", person.getValue("phone").jsonPrimitive.content)
        val vcf = client.dav("GET", "/dav/contacts/people/k1.vcf").bodyAsText()
        assertTrue("UID:kisi-1" in vcf && "FN:Ayşe Yılmaz" in vcf && "N:Yılmaz;Ayşe;;;" in vcf, vcf)
        assertTrue("<CR:addressbook/>" in client.dav("PROPFIND", "/dav/contacts/", null, mapOf("Depth" to "1")).bodyAsText())
        assertTrue("calendar-access" in client.dav("OPTIONS", "/dav/").headers["DAV"]!!)
    }

    @Test
    fun `ical bicimi ve dogrulama`() {
        val long = "DESCRIPTION:" + "ğ".repeat(60)
        val folded = ICal.lines(listOf(long))
        assertTrue(folded.split("\r\n").filter { it.isNotEmpty() }.all { it.toByteArray().size <= 75 }, folded)
        assertEquals(long.removePrefix("DESCRIPTION:"), ICal.parse("BEGIN:X\r\n${folded}END:X\r\n").first("DESCRIPTION")!!.value)
        assertEquals("a,b;c\\d\ne", ICal.unescape(ICal.escape("a,b;c\\d\ne")))
        val day = ICal.parse("BEGIN:VEVENT\r\nDTSTART;VALUE=DATE:20261224\r\nEND:VEVENT").first("DTSTART")!!
        val (t, allDay) = ICal.time(day, ZoneId.of("Europe/Istanbul"))!!
        assertTrue(allDay)
        assertEquals("2026-12-23T21:00:00Z", t.toString())
        assertEquals(java.time.Duration.ofDays(14), ICal.duration("P2W"))

        val bad = config.copy(
            webdav = WebDavConfig(),
            collections = listOf(bookings.copy(calendar = CalendarConfig("starts", "title", feedToken = "kisa", dav = true, resource = "room"))),
        )
        val errors = ConfigValidator.validateDetailed(bad).map { it.path }
        for (p in listOf("collections[0].calendar.title", "collections[0].calendar.start", "collections[0].calendar.feedToken", "collections[0].calendar.dav", "collections[0].calendar.resource")) {
            assertTrue(p in errors, "$p yok: $errors")
        }
    }
}
