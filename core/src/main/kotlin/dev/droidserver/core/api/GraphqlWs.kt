package dev.droidserver.core.api

import dev.droidserver.core.auth.Caller
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.Rules
import dev.droidserver.core.graphql.GqlBackend
import dev.droidserver.core.graphql.GraphQL
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.realtime.RealtimeHub
import dev.droidserver.core.realtime.RecordEvent
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * GraphQL WebSocket'i, `graphql-transport-ws` protokolü (graphql-ws, Apollo, urql istemcileri). İstemci
 * `connection_init` gönderir (payload'da `Authorization: "Bearer …"` veya `token`; tarayıcı WebSocket'i başlık
 * gönderemez) → `connection_ack`. `subscribe` {id, payload: {query, variables}}: abonelikte her olayda `next`, sorgu ve
 * mutasyonda tek `next` ve `complete`. `complete` aboneliği bitirir, `ping` → `pong`. Her olayda koleksiyonun
 * görüntüleme kuralı uygulanır (canlı abonelikle aynı); silmede kayıt yalnızca kimlik taşır.
 */
internal class GraphqlWs(
    private val gql: GraphQL,
    private val hub: RealtimeHub,
    private val collections: Map<String, CollectionConfig>,
    private val names: (String) -> ApiNames?,
    private val backendFor: (Caller) -> GqlBackend,
    private val resolve: suspend (token: String?) -> Caller,
) {
    suspend fun run(ws: DefaultWebSocketServerSession) {
        val init = withTimeoutOrNull(INIT_TIMEOUT_MS) { (ws.incoming.receiveCatching().getOrNull() as? Frame.Text)?.readText() }?.let(::parse)
        if (init?.text("type") != "connection_init") return ws.close(CloseReason(4408, "Connection initialisation timeout"))
        val payload = init["payload"] as? JsonObject
        val token = (payload?.text("Authorization") ?: payload?.text("authorization"))?.removePrefix("Bearer ")?.trim() ?: payload?.text("token")
        val caller = try {
            resolve(token)
        } catch (e: Exception) {
            return ws.close(CloseReason(4403, "Forbidden"))
        }
        ws.sendJson(message("connection_ack"))
        val active = ConcurrentHashMap<String, Job>()
        coroutineScope {
            try {
                for (frame in ws.incoming) {
                    val msg = (frame as? Frame.Text)?.readText()?.let(::parse) ?: return@coroutineScope ws.close(CloseReason(4400, "Invalid message"))
                    when (msg.text("type")) {
                        "ping" -> ws.sendJson(message("pong"))
                        "pong" -> {}
                        "subscribe" -> {
                            val id = msg.text("id") ?: return@coroutineScope ws.close(CloseReason(4400, "Missing id"))
                            val request = msg["payload"] as? JsonObject ?: return@coroutineScope ws.close(CloseReason(4400, "Missing payload"))
                            if (active.containsKey(id)) return@coroutineScope ws.close(CloseReason(4409, "Subscriber for $id already exists"))
                            if (active.size >= MAX_SUBSCRIPTIONS) {
                                ws.sendJson(error(id, tr("Bir bağlantıda en çok {0} abonelik", MAX_SUBSCRIPTIONS)))
                                continue
                            }
                            val (sub, failure) = gql.subscribe(request)
                            when {
                                failure != null -> ws.sendJson(message("error", id, failure["errors"]))
                                sub == null -> active[id] = launch {
                                    val result = withContext(Dispatchers.IO) { gql.execute(request, backendFor(caller)) }
                                    ws.sendJson(message("next", id, result))
                                    ws.sendJson(message("complete", id))
                                    active.remove(id)
                                }
                                else -> active[id] = launch {
                                    stream(ws, id, sub, caller)
                                    active.remove(id)
                                }
                            }
                        }
                        "complete" -> msg.text("id")?.let { active.remove(it)?.cancel() }
                        else -> return@coroutineScope ws.close(CloseReason(4400, "Unknown message type"))
                    }
                }
            } finally {
                active.values.forEach { it.cancel() }
            }
        }
    }

    private suspend fun stream(ws: DefaultWebSocketServerSession, id: String, sub: GraphQL.Subscription, caller: Caller) {
        val c = collections.getValue(sub.collection)
        try {
            Access.scope(c, Rules.Op.VIEW, caller)
        } catch (e: ApiException) {
            return ws.sendJson(error(id, e.message ?: tr("Yetkiniz yok")))
        }
        val s = hub.subscribe(setOf(sub.collection)) ?: return ws.sendJson(error(id, tr("Çok fazla canlı bağlantı; biraz sonra tekrar deneyin")))
        s.use {
            try {
                while (true) {
                    val e = s.receive()
                    if (e.action.wire !in sub.events) continue
                    if (sub.id != null && (e.record["id"] as? JsonPrimitive)?.longOrNull != sub.id) continue
                    val visible = visible(e, collections, caller) ?: continue
                    val record = if (e.action == RecordEvent.Action.DELETE) buildJsonObject { e.record["id"]?.let { put("id", it) } } else visible
                    ws.sendJson(message("next", id, sub.payload(e.action.wire, names(e.collection)?.out(record) ?: record)))
                }
            } catch (_: ClosedReceiveChannelException) {
                ws.sendJson(if (s.overflowed) error(id, tr("Olaylar yeterince hızlı okunmuyor; yeniden abone olun")) else message("complete", id))
            }
        }
    }

    private fun parse(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun message(type: String, id: String? = null, payload: JsonElement? = null) = buildJsonObject {
        id?.let { put("id", JsonPrimitive(it)) }
        put("type", JsonPrimitive(type))
        payload?.let { put("payload", it) }
    }

    private fun error(id: String, text: String) = message("error", id, JsonArray(listOf(buildJsonObject { put("message", JsonPrimitive(text)) })))

    private suspend fun DefaultWebSocketServerSession.sendJson(o: JsonObject) = send(Frame.Text(o.toString()))

    companion object {
        const val PROTOCOL = "graphql-transport-ws"
        private const val INIT_TIMEOUT_MS = 10_000L
        private const val MAX_SUBSCRIPTIONS = 20
    }
}
