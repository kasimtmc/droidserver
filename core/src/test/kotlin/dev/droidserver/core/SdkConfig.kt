package dev.droidserver.core

import dev.droidserver.core.config.AuthConfig
import dev.droidserver.core.config.AuthMode
import dev.droidserver.core.config.CollectionConfig
import dev.droidserver.core.config.FieldConfig
import dev.droidserver.core.config.FieldType
import dev.droidserver.core.config.OnDelete
import dev.droidserver.core.config.RealtimeConfig
import dev.droidserver.core.config.Rule
import dev.droidserver.core.config.Rules
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.SystemCollections
import dev.droidserver.core.config.SystemFieldConfig
import dev.droidserver.core.config.UsersConfig

/** SDK testlerinin konfigürasyonu; üretilen Kotlin istemcisi `sdkclient/DroidServerClient.kt`'de (altın dosya). */
object SdkConfig {
    const val KEY = "yonetici-anahtari-1234567890"
    const val PACKAGE = "dev.droidserver.core.sdkclient"

    val config = ServerConfig(
        name = "SDK deneme",
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(KEY), users = UsersConfig(enabled = true, sendVerification = false, teams = dev.droidserver.core.config.TeamsConfig(enabled = true))),
        realtime = RealtimeConfig(enabled = true),
        collections = listOf(
            CollectionConfig(
                "todo_items",
                listOf(
                    FieldConfig("title", FieldType.TEXT, required = true),
                    FieldConfig("done", FieldType.BOOLEAN),
                    FieldConfig("qty", FieldType.INTEGER),
                    FieldConfig("price", FieldType.REAL),
                    FieldConfig("status", FieldType.SELECT, options = listOf("açık", "kapalı")),
                    FieldConfig("tags", FieldType.MULTISELECT, options = listOf("ev", "iş")),
                    FieldConfig("meta", FieldType.JSON),
                    FieldConfig("in", FieldType.TEXT),
                    FieldConfig("due", FieldType.DATETIME),
                    FieldConfig("photo", FieldType.FILE),
                    FieldConfig("total", FieldType.REAL, computed = "price * qty"),
                    FieldConfig("owner", FieldType.RELATION, collection = SystemCollections.USERS, onDelete = OnDelete.SET_NULL),
                ),
                rules = Rules(Rule.AUTH, Rule.AUTH, Rule.AUTH, Rule.AUTH, Rule.AUTH),
                ownerField = "owner",
                systemFields = mapOf("created_at" to SystemFieldConfig(name = "olusturulma")),
            ),
        ),
    )
}
