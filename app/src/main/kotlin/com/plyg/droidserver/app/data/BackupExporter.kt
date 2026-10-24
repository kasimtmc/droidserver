package com.plyg.droidserver.app.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.DocumentsContract
import com.plyg.droidserver.app.Settings
import com.plyg.droidserver.core.alerts.AlertKind
import com.plyg.droidserver.core.audit.AuditLog
import com.plyg.droidserver.core.backup.BackupArchive
import com.plyg.droidserver.core.backup.BackupInfo
import com.plyg.droidserver.core.backup.BackupKind
import com.plyg.droidserver.core.backup.RemoteBackups
import com.plyg.droidserver.core.config.ConfigSecrets
import com.plyg.droidserver.core.config.RemoteBackupConfig
import com.plyg.droidserver.core.config.ServerConfig
import com.plyg.droidserver.core.i18n.tr
import com.plyg.droidserver.core.server.DeployOrchestrator
import com.plyg.droidserver.core.server.EngineState
import com.plyg.droidserver.core.server.ServerEngine
import java.io.File

/**
 * Yedek alma, kullanıcının seçtiği klasöre (SAF) kopyalama ve geri yükleme. Yedekler önce uygulama klasörüne yazılır;
 * klasör seçildiyse her yedeğin bir kopyası oraya da konur (telefon kaybolursa / uygulama silinirse diye).
 */
class BackupExporter(
    private val context: Context,
    private val repo: ConfigRepository,
    private val engine: ServerEngine,
    private val settings: Settings,
    private val logs: LogBuffer,
    private val secrets: SecretStore,
) {
    /** Kayıtlı konfigürasyondaki bulut deposu (sırlar çözülmüş); ayarlı değilse null. */
    fun remoteConfig(): RemoteBackupConfig? =
        runCatching { repo.loadActiveOrNull()?.let { ConfigSecrets.resolve(it, secrets::get) }?.backup?.remote }.getOrNull()

    fun remote(): RemoteBackups? = remoteConfig()?.let(::RemoteBackups)

    /** Çalışan sunucudan yedek alır; klasör seçiliyse kopyalar. Bloklayıcı. */
    fun backup(kind: BackupKind, actor: String = AuditLog.APP): BackupInfo {
        val info = engine.backup(repo.loadText(), kind)
        if (kind == BackupKind.MANUAL) engine.audit.record(actor, "backup.create", info.name)
        settings.backupTreeUri?.let { tree ->
            runCatching { copyTo(info.file, tree.toUri()) }
                .onSuccess { logs.add("Yedek klasöre kopyalandı: ${info.name}") }
                .onFailure {
                    logs.add("Yedek seçilen klasöre kopyalanamadı (izin kaldırılmış olabilir): ${it.message}")
                    engine.health.event(AlertKind.BACKUP, tr("Yedek seçilen klasöre kopyalanamadı (izin kaldırılmış olabilir): {0}", it.message), "folder")
                }
        }
        remoteConfig()?.takeIf { kind == BackupKind.SCHEDULED || (kind == BackupKind.MANUAL && it.uploadManual) }?.let { r ->
            runCatching { upload(RemoteBackups(r), info) }.onFailure {
                logs.add("Yedek buluta yüklenemedi: ${it.message}")
                engine.health.event(AlertKind.BACKUP, tr("Yedek buluta yüklenemedi: {0}", it.message), "remote")
            }
        }
        return info
    }

    /** Yedeği şifreleyip buluta yükler, depodaki fazlaları siler; yüklenen ad. Bloklayıcı. */
    fun upload(info: BackupInfo): String = upload(remote() ?: error(tr("Bulut yedeği ayarlı değil (Sihirbaz → Veritabanı → Bulut yedeği)")), info)

    private fun upload(remote: RemoteBackups, info: BackupInfo): String {
        val name = remote.upload(info.file)
        logs.add("Yedek buluta yüklendi (şifreli): $name")
        remote.prune().forEach { logs.add("Buluttaki eski yedek silindi: $it") }
        return name
    }

    /** Buluttaki yedeği indirip çözer ve doğrular; onay için özet döndürür. */
    fun prepareRemoteRestore(name: String): Pair<File, BackupArchive.Manifest> {
        val remote = remote() ?: error(tr("Bulut yedeği ayarlı değil"))
        val file = File(context.cacheDir, "geri-yukleme.zip")
        remote.download(name, file)
        return file to BackupArchive.inspect(file).first
    }

    /** SAF ağacına (OpenDocumentTree) yeni dosya olarak kopyalar. */
    fun copyTo(file: File, tree: Uri) {
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(context.contentResolver, dir, "application/zip", file.name)
            ?: error(tr("Klasörde dosya oluşturulamadı"))
        context.contentResolver.openOutputStream(doc)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error(tr("Dosya yazılamadı"))
    }

    /** Tek dosyaya (CreateDocument) kopyalar. */
    fun copyToDocument(file: File, target: Uri) {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error(tr("Dosya yazılamadı"))
    }

    /** Seçilen yedeği önbelleğe kopyalar ve doğrular; onay için özet döndürür. */
    fun prepareRestore(source: Uri): Pair<File, BackupArchive.Manifest> {
        val file = File(context.cacheDir, "geri-yukleme.zip")
        context.contentResolver.openInputStream(source)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: error(tr("Dosya açılamadı"))
        return file to BackupArchive.inspect(file).first
    }

    /**
     * Geri yükler: sunucu çalışıyorsa önce mevcut durumun yedeği alınır (alınamazsa durulur), sonra veritabanı ve
     * klasörler değişir, yedekteki konfigürasyon (bu cihazın veritabanı ayarıyla) kaydedilir. Çağıran deploy eder.
     */
    suspend fun restore(file: File, orchestrator: DeployOrchestrator) {
        if (engine.state.value is EngineState.Running) {
            val safety = backup(BackupKind.BEFORE_RESTORE)
            logs.add("Geri yüklemeden önce mevcut durum yedeklendi: ${safety.name}")
        } else {
            logs.add("Sunucu çalışmıyor; geri yükleme öncesi yedek alınamadı")
        }
        val current = if (repo.hasConfig()) repo.load() else ServerConfig.fromJson(repo.defaultConfigText())
        val merged = orchestrator.restore(file, current, engine.backupDirs)
        engine.audit.record(AuditLog.APP, "backup.restore", file.name)
        repo.saveText(merged.toJson())
        logs.add("Geri yükleme tamamlandı; yedekteki konfigürasyon kaydedildi. Sır bulunamadı hatası alırsanız Sihirbaz → Güvenlik'te anahtarı yeniden üretin.")
        file.delete()
    }
}
