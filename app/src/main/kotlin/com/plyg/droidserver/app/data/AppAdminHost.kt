package com.plyg.droidserver.app.data

import android.content.Context
import com.plyg.droidserver.app.service.ServerService
import com.plyg.droidserver.core.admin.AdminHost
import com.plyg.droidserver.core.backup.BackupInfo
import com.plyg.droidserver.core.backup.BackupKind
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.ConfigValidator
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.packages.PackageFile
import com.plyg.droidserver.core.packages.PackagePlan
import com.plyg.droidserver.core.packages.PackageSignature
import com.plyg.droidserver.core.packages.Packages
import com.plyg.droidserver.core.replication.Failover
import com.plyg.droidserver.core.replication.ReplicaStatus
import com.plyg.droidserver.core.security.FieldEncryption
import com.plyg.droidserver.core.server.ServerEngine
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

    override fun planPackage(pkg: PackageFile): PackagePlan =
        Packages.plan(ConfigSecrets.resolve(repo.load(), secrets::get), pkg, engine.scriptsDir?.list()?.toSet().orEmpty())

    override fun installPackage(pkg: PackageFile): String {
        val raw = repo.load()
        val plan = planPackage(pkg)
        require(!plan.blocked) { (plan.conflicts + plan.errors + listOfNotNull((pkg.signature as? PackageSignature.Invalid)?.reason)).joinToString("; ") }
        engine.stagePackage(pkg, plan.previous)
        repo.saveText(Packages.apply(raw, pkg).toJson())
        logs.add(tr("Paket kuruldu: {0} {1}; deploy ediliyor", pkg.manifest.name, pkg.manifest.version))
        ServerService.deploy(context)
        return tr("{0} {1} kuruluyor; sunucu kısa süre yeniden başlar.", pkg.manifest.title, pkg.manifest.version)
    }

    override fun removePackage(name: String): String {
        val raw = repo.load()
        val p = raw.packages.firstOrNull { it.name == name } ?: throw IllegalArgumentException(tr("'{0}' paketi kurulu değil", name))
        repo.saveText(Packages.remove(raw, name).toJson())
        engine.unstagePackage(p)
        logs.add(tr("Paket kaldırıldı: {0}; deploy ediliyor", name))
        ServerService.deploy(context)
        return tr("{0} kaldırılıyor; koleksiyonların verileri veritabanında kalır.", p.title)
    }

    override fun takeover() {
        val cfg = repo.load()
        check(cfg.replication.replicaOf != null) { tr("Bu sunucu kopya değil") }
        repo.saveText(Failover.promote(cfg, engine.replica.value?.primaryId).toJson())
        logs.add(tr("Web panelinden devralındı; deploy ediliyor"))
        ServerService.deploy(context)
    }
}
