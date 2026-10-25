package com.plyg.droidserver.core.config

import com.plyg.droidserver.core.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Sırları konfigürasyon dışında saklayan depo (app: Android Keystore ile şifreli). */
fun interface SecretSink {
    /** Değeri saklar ve adını döndürür. [kind]: `api_key` veya `jwt` (ad üretmek için ipucu). */
    fun store(kind: String, value: String): String
}

class MissingSecretsException(val names: List<String>) :
    RuntimeException(tr("Sır bulunamadı: {0}", names.joinToString { ConfigSecrets.PREFIX + it }))

/**
 * Konfigürasyondaki sırlar (`auth.apiKeys`, `auth.jwtSecret`, `auth.users.oauth.providers[].clientSecret`, `database.password`, `access.direct.ddns.token`, bileşen
 * ve script ortam değişkenleri) düz metin yerine
 * `"secret:<ad>"` referansı tutabilir. Referanslar deploy anında çözülür; çekirdek sırların nerede ve nasıl
 * saklandığını bilmez, yalnızca `(ad) -> değer?` çözücüsünü ve [SecretSink]'i kullanır.
 *
 * Kaydederken depoya otomatik taşınanlar `auth` sırları, `database.password`, `database.fieldKey`, DuckDNS token'ı, SMTP parolası (`email.password`), Telegram bot token'ı (`alerts.telegram.botToken`) ve uygulamaların Git token'ı ile webhook sırrı ve YZ anahtarları (`ai.apiKey`, `ai.embeddings.apiKey`) WebDAV parolası (`webdav.password`), MQTT parolası (`mqtt.password`), ödeme sağlayıcılarının anahtarları (`payments.providers[]`) ve SMS sağlayıcısının gizli anahtarıdır (`phone.smsProvider.secret`); bileşen, uygulama ve ortak
 * ortamdaki (`env`) hangi değerin sır olduğu bilinemez, oraya referansı sihirbaz (örn. tünel token'ı), Ortam ve sırlar
 * ekranı ([EnvFile.toConfig]) veya kullanıcı yazar.
 */
object ConfigSecrets {
    const val PREFIX = "secret:"
    private val NAME = Regex("^[a-z0-9_]{1,64}$")

    fun isRef(value: String) = value.startsWith(PREFIX)

    fun ref(name: String): String {
        require(NAME.matches(name)) { tr("Geçersiz sır adı: {0}", name) }
        return PREFIX + name
    }

    /** Referansları değerleriyle değiştirir; düz metin değerler olduğu gibi kalır. */
    fun resolve(config: ServerConfig, resolver: (String) -> String?): ServerConfig {
        val missing = mutableListOf<String>()
        fun value(v: String): String {
            if (!isRef(v)) return v
            val name = v.removePrefix(PREFIX)
            return resolver(name) ?: v.also { missing += name }
        }
        val auth = config.auth
        val resolved = config.copy(
            auth = auth.copy(
                apiKeys = auth.apiKeys.map(::value),
                jwtSecret = auth.jwtSecret?.let(::value),
                users = auth.users.copy(
                    oauth = auth.users.oauth.copy(providers = auth.users.oauth.providers.map { it.copy(clientSecret = value(it.clientSecret)) }),
                ),
            ),
            database = config.database.copy(password = config.database.password?.let(::value), fieldKey = config.database.fieldKey?.let(::value)),
            components = config.components.map { c -> c.copy(env = c.env.mapValues { value(it.value) }) },
            scripts = config.scripts.copy(env = config.scripts.env.mapValues { value(it.value) }),
            access = config.access.copy(
                direct = config.access.direct.copy(
                    ddns = config.access.direct.ddns?.let { it.copy(token = value(it.token)) },
                ),
            ),
            email = config.email.copy(password = config.email.password?.let(::value)),
            webhooks = config.webhooks.map { it.copy(secret = it.secret?.let(::value)) },
            push = config.push.copy(privateKey = value(config.push.privateKey)),
            adminPanel = config.adminPanel.copy(totpSecret = config.adminPanel.totpSecret?.let(::value)),
            backup = config.backup.copy(remote = config.backup.remote?.let { it.copy(password = value(it.password), passphrase = value(it.passphrase)) }),
            alerts = config.alerts.copy(telegram = config.alerts.telegram?.let { it.copy(botToken = value(it.botToken)) }),
            replication = config.replication.copy(
                replicaOf = config.replication.replicaOf?.let { it.copy(key = value(it.key)) },
                formerPrimary = config.replication.formerPrimary?.let { it.copy(key = value(it.key)) },
            ),
            env = config.env.mapValues { value(it.value) },
            webdav = config.webdav.copy(password = value(config.webdav.password)),
            mqtt = config.mqtt.copy(password = value(config.mqtt.password), devices = config.mqtt.devices.map { it.copy(password = value(it.password)) }),
            ai = config.ai.copy(apiKey = value(config.ai.apiKey), embeddings = config.ai.embeddings?.let { it.copy(apiKey = value(it.apiKey)) }),
            phone = config.phone.copy(smsProvider = config.phone.smsProvider?.let { it.copy(secret = value(it.secret)) }),
            payments = config.payments.copy(
                providers = config.payments.providers.map { it.copy(secretKey = value(it.secretKey), apiKey = value(it.apiKey), webhookSecret = value(it.webhookSecret)) },
            ),
            apps = config.apps.map { a ->
                a.copy(
                    env = a.env.mapValues { value(it.value) },
                    git = a.git?.let { g -> g.copy(token = g.token?.let(::value), webhookSecret = g.webhookSecret?.let(::value)) },
                )
            },
        )
        if (missing.isNotEmpty()) throw MissingSecretsException(missing)
        return resolved
    }

    /**
     * Düz metin sırları depoya taşır, yerlerine referans koyar. JSON düzeyinde çalışır;
     * bilinmeyen anahtarlar ve alan sırası korunur.
     */
    fun externalize(json: JsonObject, sink: SecretSink): JsonObject =
        mapSecrets(json) { kind, v -> if (v.isEmpty() || isRef(v)) v else ref(sink.store(kind, v)) }

    /**
     * Kaydetmeden önce: sürümü yükseltir ve düz metin sırları depoya taşır.
     * Değişiklik yoksa null (dosya kullanıcının biçimiyle kalsın diye).
     */
    fun secureText(text: String, sink: SecretSink): String? {
        val original = ConfigJson.parseToJsonElement(text).jsonObject
        val secured = externalize(ConfigMigrations.upgrade(original, sink), sink)
        return if (secured == original) null else ConfigJson.encodeToString(JsonObject.serializer(), secured)
    }

    /** Sır alanlarındaki her metin değere [transform] uygular. */
    internal fun mapSecrets(json: JsonObject, transform: (kind: String, value: String) -> String): JsonObject {
        val out = json.toMutableMap()
        (json["auth"] as? JsonObject)?.let { auth ->
            val newAuth = auth.toMutableMap()
            (auth["apiKeys"] as? JsonArray)?.let { keys ->
                newAuth["apiKeys"] = JsonArray(keys.map { el -> el.stringOrNull()?.let { JsonPrimitive(transform("api_key", it)) } ?: el })
            }
            auth["jwtSecret"]?.stringOrNull()?.let { newAuth["jwtSecret"] = JsonPrimitive(transform("jwt", it)) }
            val users = auth["users"] as? JsonObject
            val oauth = users?.get("oauth") as? JsonObject
            (oauth?.get("providers") as? JsonArray)?.let { providers ->
                val mapped = JsonArray(providers.map { p ->
                    val o = p as? JsonObject ?: return@map p
                    val secret = o["clientSecret"]?.stringOrNull() ?: return@map o
                    JsonObject(o + ("clientSecret" to JsonPrimitive(transform("oauth_secret", secret))))
                })
                newAuth["users"] = JsonObject(users + ("oauth" to JsonObject(oauth + ("providers" to mapped))))
            }
            out["auth"] = JsonObject(newAuth)
        }
        (json["database"] as? JsonObject)?.let { db ->
            val mapped = db.toMutableMap()
            db["password"]?.stringOrNull()?.let { mapped["password"] = JsonPrimitive(transform("db_password", it)) }
            db["fieldKey"]?.stringOrNull()?.let { mapped["fieldKey"] = JsonPrimitive(transform("field_key", it)) }
            if (mapped != db) out["database"] = JsonObject(mapped)
        }
        (json["access"] as? JsonObject)?.let { access ->
            val direct = access["direct"] as? JsonObject ?: return@let
            val ddns = direct["ddns"] as? JsonObject ?: return@let
            val token = ddns["token"]?.stringOrNull() ?: return@let
            val newDdns = JsonObject(ddns + ("token" to JsonPrimitive(transform("ddns_token", token))))
            out["access"] = JsonObject(access + ("direct" to JsonObject(direct + ("ddns" to newDdns))))
        }
        (json["webhooks"] as? JsonArray)?.let { hooks ->
            out["webhooks"] = JsonArray(hooks.map { h ->
                val o = h as? JsonObject ?: return@map h
                val secret = o["secret"]?.stringOrNull() ?: return@map o
                JsonObject(o + ("secret" to JsonPrimitive(transform("webhook_secret", secret))))
            })
        }
        (json["push"] as? JsonObject)?.let { push ->
            push["privateKey"]?.stringOrNull()?.let { out["push"] = JsonObject(push + ("privateKey" to JsonPrimitive(transform("vapid_private", it)))) }
        }
        (json["backup"] as? JsonObject)?.let { backup ->
            val remote = backup["remote"] as? JsonObject ?: return@let
            val mapped = remote.toMutableMap()
            remote["password"]?.stringOrNull()?.let { mapped["password"] = JsonPrimitive(transform("backup_password", it)) }
            remote["passphrase"]?.stringOrNull()?.let { mapped["passphrase"] = JsonPrimitive(transform("backup_passphrase", it)) }
            out["backup"] = JsonObject(backup + ("remote" to JsonObject(mapped)))
        }
        (json["adminPanel"] as? JsonObject)?.let { panel ->
            panel["totpSecret"]?.stringOrNull()?.let { out["adminPanel"] = JsonObject(panel + ("totpSecret" to JsonPrimitive(transform("panel_totp", it)))) }
        }
        (json["alerts"] as? JsonObject)?.let { alerts ->
            val telegram = alerts["telegram"] as? JsonObject ?: return@let
            val token = telegram["botToken"]?.stringOrNull() ?: return@let
            out["alerts"] = JsonObject(alerts + ("telegram" to JsonObject(telegram + ("botToken" to JsonPrimitive(transform("telegram_token", token))))))
        }
        (json["replication"] as? JsonObject)?.let { rep ->
            val mapped = rep.toMutableMap()
            for (field in listOf("replicaOf", "formerPrimary")) {
                val src = rep[field] as? JsonObject ?: continue
                val key = src["key"]?.stringOrNull() ?: continue
                mapped[field] = JsonObject(src + ("key" to JsonPrimitive(transform("replica_key", key))))
            }
            if (mapped != rep) out["replication"] = JsonObject(mapped)
        }
        (json["apps"] as? JsonArray)?.let { apps ->
            out["apps"] = JsonArray(apps.map { a ->
                val o = a as? JsonObject ?: return@map a
                val git = o["git"] as? JsonObject ?: return@map o
                val mapped = git.toMutableMap()
                git["token"]?.stringOrNull()?.let { mapped["token"] = JsonPrimitive(transform("git_token", it)) }
                git["webhookSecret"]?.stringOrNull()?.let { mapped["webhookSecret"] = JsonPrimitive(transform("git_hook", it)) }
                JsonObject(o + ("git" to JsonObject(mapped)))
            })
        }
        (json["webdav"] as? JsonObject)?.let { dav ->
            dav["password"]?.stringOrNull()?.let { out["webdav"] = JsonObject(dav + ("password" to JsonPrimitive(transform("webdav_password", it)))) }
        }
        (json["mqtt"] as? JsonObject)?.let { mqtt ->
            val mapped = mqtt.toMutableMap()
            mqtt["password"]?.stringOrNull()?.let { mapped["password"] = JsonPrimitive(transform("mqtt_password", it)) }
            (mqtt["devices"] as? JsonArray)?.let { devices ->
                mapped["devices"] = JsonArray(devices.map { d ->
                    val o = d as? JsonObject ?: return@map d
                    o["password"]?.stringOrNull()?.let { JsonObject(o + ("password" to JsonPrimitive(transform("mqtt_device", it)))) } ?: o
                })
            }
            out["mqtt"] = JsonObject(mapped)
        }
        (json["ai"] as? JsonObject)?.let { ai ->
            val mapped = ai.toMutableMap()
            ai["apiKey"]?.stringOrNull()?.let { mapped["apiKey"] = JsonPrimitive(transform("ai_api_key", it)) }
            (ai["embeddings"] as? JsonObject)?.let { e ->
                e["apiKey"]?.stringOrNull()?.let { mapped["embeddings"] = JsonObject(e + ("apiKey" to JsonPrimitive(transform("embeddings_key", it)))) }
            }
            out["ai"] = JsonObject(mapped)
        }
        (json["payments"] as? JsonObject)?.let { payments ->
            val providers = payments["providers"] as? JsonArray ?: return@let
            out["payments"] = JsonObject(payments + ("providers" to JsonArray(providers.map { p ->
                val o = p as? JsonObject ?: return@map p
                val mapped = o.toMutableMap()
                for ((field, kind) in listOf("secretKey" to "payment_secret", "apiKey" to "payment_key", "webhookSecret" to "payment_hook")) {
                    o[field]?.stringOrNull()?.let { mapped[field] = JsonPrimitive(transform(kind, it)) }
                }
                JsonObject(mapped)
            })))
        }
        (json["phone"] as? JsonObject)?.let { phone ->
            val provider = phone["smsProvider"] as? JsonObject ?: return@let
            val secret = provider["secret"]?.stringOrNull() ?: return@let
            out["phone"] = JsonObject(phone + ("smsProvider" to JsonObject(provider + ("secret" to JsonPrimitive(transform("sms_secret", secret))))))
        }
        (json["email"] as? JsonObject)?.let { email ->
            email["password"]?.stringOrNull()?.let { out["email"] = JsonObject(email + ("password" to JsonPrimitive(transform("email_password", it)))) }
        }
        return JsonObject(out)
    }

    private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
