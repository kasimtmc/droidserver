package dev.droidserver.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Seçilen uygulama şablonunun bekleyen kurulumu (profil klasöründe `template.json`). [applyDraft]: yeni profil
 * şablonla açıldı, sihirbaz ilk açılışta taslağa uygular. Deploy başarılı olunca örnek veri ve site kurulur, kayıt silinir.
 */
@Serializable
data class TemplateRequest(val id: String, val samples: Boolean = true, val site: Boolean = true, val applyDraft: Boolean = false)

class PendingTemplate(root: File) {
    private val file = File(root, "template.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun get(): TemplateRequest? = runCatching { json.decodeFromString(TemplateRequest.serializer(), file.readText()) }.getOrNull()

    fun set(request: TemplateRequest) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(TemplateRequest.serializer(), request))
    }

    fun clear() {
        file.delete()
    }
}
