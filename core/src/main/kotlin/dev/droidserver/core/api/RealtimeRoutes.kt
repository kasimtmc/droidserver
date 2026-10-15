package dev.droidserver.core.api

import dev.droidserver.core.auth.Caller
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.Rules
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.realtime.RealtimeHub
import dev.droidserver.core.realtime.RecordEvent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `GET <basePath>/realtime?collections=a,b` — Server-Sent Events. Olay verisi:
 * `{"action":"create|update|delete","collection":"a","record":{...}}` (silmede yalnızca `{"id":..}`).
 * Bağlantı açılınca `ready`, yeniden bağlanan istemcinin kaçırdıkları getirilemiyorsa `reset` adlı olay gelir.
 *
 * Bağlanırken her koleksiyonda listeleme izni aranır; her olayda görüntüleme kuralı uygulanır (`owner` kuralında
 * yalnızca kullanıcının kendi kayıtları). Bağlantı en çok [MAX_STREAM_MS] açık kalır; istemci (EventSource kendiliğinden)
 * `Last-Event-ID` ile yeniden bağlanır ve aradaki olayları alır.
 */
internal fun Route.realtimeRoute(
    hub: RealtimeHub,
    collections: Map<String, CollectionConfig>,
    path: String?,
    names: (String) -> ApiNames?,
    caller: suspend RoutingContext.() -> Caller,
) {
    if (path == null) return
    get(path) {
        val who = caller()
        val names = call.request.queryParameters["collections"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        if (names.isNullOrEmpty()) throw ApiException(400, tr("collections parametresi gerekli, örn. ?collections=todos,posts"))
        for (name in names) {
            val c = collections[name] ?: throw ApiException(404, tr("'{0}' koleksiyonu yok", name))
            Access.scope(c, Rules.Op.LIST, who)
        }
        val subscription = hub.subscribe(names) ?: throw ApiException(503, tr("Çok fazla canlı bağlantı; biraz sonra tekrar deneyin"))
        val lastEventId = call.request.headers["Last-Event-ID"] ?: call.request.queryParameters["lastEventId"]

        call.response.headers.append("X-Accel-Buffering", "no")
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondBytesWriter(ContentType.Text.EventStream) {
            subscription.use { sub ->
                writeStringUtf8("retry: 3000\n\n")
                var lastSent = 0L
                if (lastEventId != null) {
                    // Abonelik açıldıktan sonra tampon okunur: arada gelen olay ya tampondan ya kuyruktan gelir (çift
                    // gelirse sıra numarasıyla atlanır).
                    val missed = hub.parseEventId(lastEventId)?.let { hub.since(it, names) }
                    if (missed == null) {
                        writeStringUtf8("event: reset\ndata: {}\n\n")
                    } else {
                        for (e in missed) {
                            visible(e, collections, who)?.let { send(hub, e, it, names(e.collection)) }
                            lastSent = e.id
                        }
                    }
                }
                val ready = buildJsonObject { put("collections", JsonArray(names.map(::JsonPrimitive))) }
                writeStringUtf8("event: ready\ndata: $ready\n\n")
                flush()
                val until = System.currentTimeMillis() + MAX_STREAM_MS
                try {
                    while (System.currentTimeMillis() < until) {
                        val e = withTimeoutOrNull(HEARTBEAT_MS) { sub.receive() }
                        if (e == null) {
                            writeStringUtf8(tr(": canlı\n\n"))
                        } else if (e.id > lastSent) {
                            visible(e, collections, who)?.let { send(hub, e, it, names(e.collection)) }
                        }
                        flush()
                    }
                } catch (_: ClosedReceiveChannelException) {
                    // Sunucu kapanıyor veya istemci olayları okuyamayacak kadar yavaş: istemci yeniden bağlanır.
                }
            }
        }
    }
}

private suspend fun ByteWriteChannel.send(hub: RealtimeHub, e: RecordEvent, visible: JsonObject, names: ApiNames?) {
    // Silmede yalnızca kimlik; uzman modunda alan adları API adlarıyla (kaldırılan alan çıkmaz).
    val record = if (e.action == RecordEvent.Action.DELETE) buildJsonObject { e.record["id"]?.let { put("id", it) } } else visible
    val out = names?.out(record) ?: record
    val data = buildJsonObject {
        put("action", JsonPrimitive(e.action.wire))
        put("collection", JsonPrimitive(e.collection))
        put("record", out)
    }
    writeStringUtf8("id: ${hub.eventId(e)}\ndata: $data\n\n")
}

/**
 * Olayın kaydı bu istemciye nasıl görünür: koleksiyonun görüntüleme kuralı, sahiplik ve kural koşulu uygulanır, gizli
 * alanlar çıkarılır. null: görmez.
 */
private fun visible(e: RecordEvent, collections: Map<String, CollectionConfig>, caller: Caller): JsonObject? {
    val collection = collections[e.collection] ?: return null
    val scope = runCatching { Access.scope(collection, Rules.Op.VIEW, caller) }.getOrNull() ?: return null
    if (!RuleConditions.visible(collection, e.record, scope)) return null
    return RuleConditions.present(collection, e.record, scope)
}

private const val HEARTBEAT_MS = 25_000L
private const val MAX_STREAM_MS = 30 * 60_000L
