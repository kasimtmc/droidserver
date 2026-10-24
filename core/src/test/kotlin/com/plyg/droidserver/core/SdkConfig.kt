package com.plyg.droidserver.core

import com.plyg.droidserver.core.config.AuthConfig
import com.plyg.droidserver.core.config.AuthMode
import com.plyg.droidserver.core.config.CollectionConfig
import com.plyg.droidserver.core.config.FieldConfig
import com.plyg.droidserver.core.config.FieldType
import com.plyg.droidserver.core.config.OnDelete
import com.plyg.droidserver.core.config.RealtimeConfig
import com.plyg.droidserver.core.config.Rule
import com.plyg.droidserver.core.config.Rules
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.config.SystemCollections
import com.plyg.droidserver.core.config.SystemFieldConfig
import com.plyg.droidserver.core.config.UsersConfig

/** SDK testlerinin konfigürasyonu; üretilen Kotlin istemcisi `sdkclient/DroidServerClient.kt`'de (altın dosya). */
object SdkConfig {
    const val KEY = "yonetici-anahtari-1234567890"
    const val PACKAGE = "com.plyg.droidserver.core.sdkclient"

    val config = ServerConfig(
        name = "SDK deneme",
        auth = AuthConfig(AuthMode.API_KEY, apiKeys = listOf(KEY), users = UsersConfig(enabled = true, sendVerification = false, teams = com.plyg.droidserver.core.config.TeamsConfig(enabled = true))),
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
                    FieldConfig("place", FieldType.GEO),
                    FieldConfig("photo", FieldType.FILE),
                    FieldConfig("total", FieldType.REAL, computed = "price * qty"),
                    FieldConfig("owner", FieldType.RELATION, collection = SystemCollections.USERS, onDelete = OnDelete.SET_NULL),
                ),
                rules = Rules(Rule.AUTH, Rule.AUTH, Rule.AUTH, Rule.AUTH, Rule.AUTH),
                ownerField = "owner",
                systemFields = mapOf("created_at" to SystemFieldConfig(name = "olusturulma")),
                sync = com.plyg.droidserver.core.config.SyncConfig(),
            ),
        ),
    )
}
