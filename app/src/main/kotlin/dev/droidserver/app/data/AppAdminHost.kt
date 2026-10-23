package dev.droidserver.app.data

import android.content.Context
import dev.droidserver.app.service.ServerService
import dev.droidserver.core.admin.AdminHost
import dev.droidserver.core.backup.BackupInfo
import dev.droidserver.core.backup.BackupKind
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.replication.Failover
import dev.droidserver.core.replication.ReplicaStatus
import dev.droidserver.core.security.FieldEncryption
import dev.droidserver.core.server.ServerEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Web panelinin konfigürasyon, deploy, log ve yedek işleri (uygulamadaki karşılıklarıyla aynı yoldan). */
class AppAdminHost(
    private val context: Context,
    private val repo: ConfigRepository,
    private val secrets: SecretStore,
    private val engine: ServerEngine,
    private val logs: LogBuffer,
    private val backups: BackupExporter,
) : AdminHost {
    override fun configText(): String? = if (repo.hasConfig()) repo.loadText() else null

    override fun plan(text: String): JsonObject {
        val errors = mutableListOf<String>()
        var summary: String? = null
        var blockers = emptyList<String>()
        var warnings = emptyList<String>()
        try {
            val cfg = ServerConfig.fromJson(FieldEncryption.ensureKey(text))
            val resolved = ConfigSecrets.resolve(cfg, secrets::get)
            errors += ConfigValidator.validate(resolved)
            if (errors.isEmpty()) {
                val plan = engine.plan(resolved)
                summary = plan.summary()
                blockers = plan.blockers
                warnings = plan.warnings
            }
        } catch (e: Exception) {
            errors += e.message ?: e.toString()
        }
        return buildJsonObject {
            put("errors", JsonArray(errors.map(::JsonPrimitive)))
            summary?.let { put("plan", JsonPrimitive(it)) }
            put("blockers", JsonArray(blockers.map(::JsonPrimitive)))
            put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
        }
    }

    override fun deploy(raw: String) {
        // Panelde şifreli yapılan ilk alan için anahtar burada üretilir; kaydederken sır deposuna taşınır.
        val text = FieldEncryption.ensureKey(raw)
        val p = plan(text)
        val problems = (p["errors"] as JsonArray) + (p["blockers"] as JsonArray)
        require(problems.isEmpty()) { problems.joinToString("; ") { (it as JsonPrimitive).content } }
        repo.saveText(text)
        logs.add(tr("Web panelinden yeni konfigürasyon kaydedildi; deploy ediliyor"))
        ServerService.deploy(context)
    }

    override fun logs(after: Long) = logs.since(after)

    override fun backups(): List<BackupInfo> = engine.backups?.list().orEmpty()

    override fun createBackup(): BackupInfo = backups.backup(BackupKind.MANUAL, actor = "panel")

    override fun replica(): ReplicaStatus? = engine.replica.value

    override fun takeover() {
        val cfg = repo.load()
        check(cfg.replication.replicaOf != null) { tr("Bu sunucu kopya değil") }
        repo.saveText(Failover.promote(cfg, engine.replica.value?.primaryId).toJson())
        logs.add(tr("Web panelinden devralındı; deploy ediliyor"))
        ServerService.deploy(context)
    }
}
