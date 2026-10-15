package dev.droidserver.core.server

import dev.droidserver.core.apps.AppManager
import dev.droidserver.core.components.ComponentAdmin
import dev.droidserver.core.backup.BackupArchive
import dev.droidserver.core.config.AppConfig
import dev.droidserver.core.config.ComponentConfig
import dev.droidserver.core.config.ConfigSecrets
import dev.droidserver.core.config.ConfigValidator
import dev.droidserver.core.config.DatabaseConfig
import dev.droidserver.core.config.DbEngine
import dev.droidserver.core.config.MissingSecretsException
import dev.droidserver.core.config.ServerConfig
import dev.droidserver.core.config.location
import dev.droidserver.core.config.runtimeComponents
import dev.droidserver.core.db.DataCopier
import dev.droidserver.core.db.PostgresAdmin
import dev.droidserver.core.db.SqlDriver
import dev.droidserver.core.i18n.tr
import dev.droidserver.core.net.AccessRuntime
import dev.droidserver.core.process.ProcessSupervisor
import dev.droidserver.core.process.ReadinessProbe
import dev.droidserver.core.security.FieldEncryption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deploy sırası:
 *  1. sırları çöz, doğrula — hata varsa hiçbir şeye dokunulmaz
 *  2. bileşenleri başlat — yalnızca tanımı değişenler yeniden başlar
 *  3. `readiness` tanımlı bileşenlerin hazır olmasını bekle (Postgres gibi veritabanları sunucudan önce ayakta olmalı)
 *  3b. kullanıcı uygulamalarını başlat ve sağlık denetimini bekle; yeni sürüm geçemezse önceki sürüme dön
 *  4. HTTP motoru: şema + sunucu
 *  5. artık istenmeyen bileşenleri durdur — en sonda, çünkü motor deploy'u başarısız olursa
 *     eski sunucu onları kullanmaya devam eder
 *
 * Başlatılamayan veya zamanında hazır olmayan bileşen ya da uygulama deploy'u durdurmaz; [Result.componentErrors]'a yazılır.
 */
class DeployOrchestrator(
    private val engine: EngineControl,
    private val supervisor: ProcessSupervisor,
    private val prepareComponent: (ComponentConfig) -> ProcessSupervisor.Spec,
    /** `secret:<ad>` referanslarını çözer (app: SecretStore). */
    private val resolveSecret: (String) -> String? = { null },
    private val probe: ReadinessProbe = ReadinessProbe(),
    private val log: (String) -> Unit = ::println,
    /** Veri taşıma ([copyDatabase]) için veritabanı açıcı; motorunkiyle aynı olmalı. */
    private val openDatabase: (DatabaseConfig) -> SqlDriver = { error(tr("Veri taşıma için openDatabase verilmedi")) },
    /** Bileşen bakım işleri ve veri sıfırlama (app: ComponentManager). */
    private val components: ComponentAdmin = ComponentAdmin.None,
    /** İnternetten doğrudan erişim (HTTPS ucu, port yönlendirme); deploy'dan sonra uygulanır. */
    private val access: AccessRuntime = AccessRuntime.None,
    /** Kullanıcı uygulamalarının sürümleri (`apps`); yoksa uygulamalar başlatılmaz. */
    private val apps: AppManager? = null,
) {
    data class Result(
        /** Sırları çözülmüş, motora verilen konfigürasyon. */
        val config: ServerConfig,
        val componentErrors: List<String>,
    )

    suspend fun deploy(config: ServerConfig): Result {
        val resolved = resolveAndValidate(config)
        val problems = startComponents(resolved) + startApps(resolved)
        engine.deploy(resolved)
        stopUnwanted(resolved)
        // Erişim katmanı sunucudan sonra: HTTPS ucu çalışan sunucuya bağlanır. Hatası deploy'u geri almaz.
        val accessProblems = runCatching { access.apply(resolved) }.exceptionOrNull()
            ?.let { listOf(tr("Doğrudan erişim başlatılamadı: {0}", it.message)).also { m -> log(m.single()) } }.orEmpty()
        return Result(resolved, problems + accessProblems)
    }

    /** Veri taşırken kullanıcının seçimleri. */
    data class MoveOptions(
        /** Taşıma sürerken sunucu: durur ya da salt okunur çalışır (okumalar sürer, yazmalar 503 alır). */
        val duringMove: DuringMove = DuringMove.STOP,
        /** Hedefte kayıt varsa: true → kopyalanacak tablolardaki kayıtlar silinir; false → taşıma durur. */
        val replaceExisting: Boolean = false,
    )

    enum class DuringMove { STOP, READ_ONLY }

    /**
     * Veritabanı değişirken (örn. SQLite → PostgreSQL) mevcut veriyi yenisine taşır. Yeni konfigürasyonun
     * bileşenleri başlatılır (Postgres ayağa kalkar); taşıma boyunca sunucu, seçime göre durur ya da salt okunur
     * çalışır — yazma kaybolmasın diye. Başarısız olursa sunucu eski haline döner ve [DeployException] fırlar;
     * hedefte yarım veri kalmaz. Başarılıysa çağıran yeni konfigürasyonu kaydedip [deploy] eder (salt okunur
     * modda yazmalar o deploy'a kadar kapalı kalır).
     */
    suspend fun copyDatabase(previous: ServerConfig, next: ServerConfig, options: MoveOptions = MoveOptions()): DataCopier.Report {
        val prev = resolveAndValidate(previous)
        val target = resolveAndValidate(next)
        require(prev.database.location() != target.database.location()) { tr("Veritabanı değişmiyor; taşınacak bir şey yok") }
        val problems = startComponents(target)
        // Hedefe ulaşılamıyorsa sunucu hiç durdurulmaz; asıl neden (örn. bileşen başlatılamadı) önce gösterilir.
        runCatching { openDatabase(target.database).close() }.onFailure { t ->
            log("Veriler taşınmadı: ${describe(target.database)} veritabanına bağlanılamadı; sunucu eski veritabanıyla çalışmaya devam ediyor")
            throw DeployException(problems + tr("Yeni veritabanına bağlanılamadı: {0}", t.message))
        }
        val readOnly = options.duringMove == DuringMove.READ_ONLY
        try {
            if (readOnly) engine.pauseWrites() else engine.stop()
        } catch (t: Throwable) {
            throw DeployException(listOf(tr("Sunucu salt okunur yapılamadı: {0}", t.message)) + problems)
        }
        log("Veriler taşınıyor: ${describe(prev.database)} → ${describe(target.database)}" + if (readOnly) tr(" (sunucu salt okunur)") else "")
        try {
            val report = openDatabase(prev.database).use { source ->
                openDatabase(target.database).use { dest ->
                    DataCopier.copy(source, dest, prev.schemaCollections(), target.schemaCollections(), replaceExisting = options.replaceExisting, log = log)
                }
            }
            log("Taşıma tamamlandı: $report")
            return report
        } catch (t: Throwable) {
            log("Veri taşıma başarısız: ${t.message}; sunucu eski haline dönüyor")
            runCatching { if (readOnly) engine.resumeWrites() else engine.deploy(prev) }
                .onFailure { log("Eski konfigürasyon açılamadı: ${it.message}") }
            throw DeployException(listOf(tr("Veri taşınamadı: {0}", t.message)) + problems)
        }
    }

    /**
     * Veritabanı parolasını değiştirir: eski konfigürasyonun bilgileriyle bağlanıp kullanıcının parolasını
     * yenisine çevirir (PostgreSQL `ALTER ROLE`). Sunucu ve veritabanı adresi aynı kalmalı. Eski parola artık
     * geçmiyorsa (unutulduysa) bileşenin parola sıfırlama işi kullanılır ([runComponentTask]).
     * Başarılıysa çağıran yeni konfigürasyonu kaydedip [deploy] eder.
     */
    suspend fun changeDatabasePassword(previous: ServerConfig, next: ServerConfig) {
        val prev = resolveAndValidate(previous)
        val target = resolveAndValidate(next)
        require(prev.database.engine == DbEngine.POSTGRES && prev.database.location() == target.database.location()) {
            tr("Parola yalnızca aynı PostgreSQL veritabanında değiştirilebilir")
        }
        val password = requireNotNull(target.database.password) { tr("Yeni parola yok") }
        if (password == prev.database.password) return
        try {
            openDatabase(prev.database).use { PostgresAdmin.changePassword(it, password) }
            log("PostgreSQL parolası değiştirildi (${prev.database.user})")
        } catch (t: Throwable) {
            throw DeployException(listOf(tr("Parola değiştirilemedi: {0}", t.message)))
        }
    }

    /**
     * Bileşenin bakım işini [config]'teki ayarlarıyla (sırlar çözülmüş) çalıştırır, örn. Postgres parolasını sıfırla.
     * İş gerektiriyorsa bileşen önce durdurulur; çağıran ardından [deploy] eder ki bileşen yeniden başlasın.
     */
    suspend fun runComponentTask(config: ServerConfig, componentId: String, taskId: String): String {
        val resolved = resolveAndValidate(config)
        val cfg = resolved.runtimeComponents().firstOrNull { it.id == componentId }
            ?: throw DeployException(listOf(tr("{0} konfigürasyonda yok", componentId)))
        val task = components.task(componentId, taskId)
            ?: throw DeployException(listOf(tr("{0}: '{1}' işi yok (bileşeni güncelleyin)", componentId, taskId)))
        if (task.stopFirst && supervisor.isAlive(componentId)) {
            log("[$componentId] '${task.title}' için durduruluyor")
            supervisor.stop(componentId)
        }
        return try {
            components.runTask(cfg, taskId).also { log("[$componentId] ${task.title}: tamamlandı") }
        } catch (t: Throwable) {
            throw DeployException(listOf(tr("{0} başarısız: {1}", task.title, t.message)))
        }
    }

    /**
     * Bileşeni durdurur ve verisini siler (geri alınamaz); sonraki [deploy]'da ilk kurulum yeniden yapılır
     * (Postgres için: boş veritabanı, konfigürasyondaki parolayla).
     */
    fun resetComponentData(componentId: String) {
        supervisor.stop(componentId)
        try {
            components.resetData(componentId)
        } catch (t: Throwable) {
            throw DeployException(listOf(tr("{0} verisi silinemedi: {1}", componentId, t.message)))
        }
    }

    /** Önce erişim katmanı ve sunucu (bileşenleri kullanıyor olabilir), sonra bileşenler. */
    fun stopAll() {
        access.stop()
        engine.stop()
        supervisor.stopAll()
    }

    /**
     * Yedeği geri yükler: yedeğin geçerliliği denetlenir, sunucu durdurulur, veritabanı ve klasörler yedekteki hale
     * getirilir. Yedekteki konfigürasyon kullanılır ama veritabanı ayarı bu cihazınki kalır (SQLite yedeği PostgreSQL'e
     * de yüklenir). Dönen konfigürasyonu çağıran kaydedip [deploy] eder; hata olursa sunucu eski haliyle açılır.
     * Mevcut durumun yedeğini çağıran bundan önce almalıdır.
     */
    suspend fun restore(file: java.io.File, current: ServerConfig, dirs: Map<String, java.io.File?>): ServerConfig {
        val (manifest, fromBackup) = withContext(Dispatchers.IO) { BackupArchive.inspect(file) }
        val merged = fromBackup.copy(database = current.database)
        val prev = resolveAndValidate(current)
        // Yedekteki sırlar (API anahtarı vb.) bu cihazın deposunda olmayabilir (yeni telefon); veritabanına bağlanmak için
        // yalnızca bu cihazın veritabanı ayarı gerekir. Eksik sırlar deploy'da sorulur.
        val target = merged.copy(database = prev.database)
        manifest.fieldKeyId?.let { id ->
            val here = FieldEncryption.cipher(prev)?.keyId
            if (here != id) {
                throw DeployException(listOf(tr("Yedekteki şifreli alanlar başka bir anahtarla şifrelenmiş ({0}). Önce Sihirbaz → Veritabanı → Alan şifreleme'de o anahtarı girin; sonra geri yükleyin.", id)))
            }
        }
        log("Geri yükleniyor: ${file.name} (${manifest.createdAt}, ${manifest.tables.values.sum()} kayıt)")
        engine.stop()
        try {
            withContext(Dispatchers.IO) {
                openDatabase(target.database).use { BackupArchive.restore(file, it, target, dirs, log) }
            }
        } catch (t: Throwable) {
            log("Geri yükleme başarısız, sunucu eski haliyle açılıyor: ${t.message}")
            runCatching { engine.deploy(prev) }
            throw t
        }
        return merged
    }

    private fun resolveAndValidate(config: ServerConfig): ServerConfig {
        val resolved = try {
            ConfigSecrets.resolve(config, resolveSecret)
        } catch (e: MissingSecretsException) {
            throw DeployException(e.names.map { tr("Sır bulunamadı: {0}{1} (yeniden girin)", ConfigSecrets.PREFIX, it) })
        }
        val errors = ConfigValidator.validate(resolved)
        if (errors.isNotEmpty()) throw DeployException(errors)
        return resolved
    }

    /** Etkin bileşenleri başlatır (yalnızca değişenler yeniden başlar) ve hazır olmalarını bekler. */
    private suspend fun startComponents(resolved: ServerConfig): List<String> {
        val problems = mutableListOf<String>()
        fun problem(msg: String) { problems += msg; log(msg) }

        val started = mutableListOf<ProcessSupervisor.Spec>()
        for (c in resolved.runtimeComponents().filter { it.enabled }) {
            val spec = try {
                prepareComponent(c)
            } catch (t: Throwable) {
                problem(tr("[{0}] başlatılamadı: {1}", c.id, t.message))
                continue
            }
            if (supervisor.start(spec)) log("[${c.id}] başlatılıyor") else log("[${c.id}] değişmedi, yeniden başlatılmadı")
            started += spec
        }

        for (spec in started) {
            val r = spec.readiness ?: continue
            log("[${spec.id}] hazır olması bekleniyor (tcp ${r.tcpPort}, en çok ${r.timeoutSec} sn)")
            if (!probe.await(r) { supervisor.isAlive(spec.id) }) {
                problem(tr("[{0}] hazır olmadı (tcp {1}, {2} sn)", spec.id, r.tcpPort, r.timeoutSec))
            }
        }
        return problems
    }

    /**
     * Etkin uygulamaları başlatır. Bekleyen yeni sürüm hazırlanamaz veya sağlık denetiminden geçemezse bırakılır ve
     * çalışan sürüm yeniden başlatılır (ilk sürümse bekler; düzeltilip yeniden deploy edilebilir).
     */
    private suspend fun startApps(resolved: ServerConfig): List<String> {
        val mgr = apps ?: return emptyList()
        val problems = mutableListOf<String>()
        fun problem(msg: String) { problems += msg; log(msg) }

        suspend fun prepare(app: AppConfig, currentOnly: Boolean) =
            withContext(Dispatchers.IO) { mgr.prepare(app, resolved.env, currentOnly) }

        /** Süreci başlatır ve sağlık denetimini bekler; sorun yoksa null. */
        suspend fun run(app: AppConfig, p: AppManager.Prepared): String? {
            if (!supervisor.start(p.spec)) {
                log("[${app.processId}] değişmedi, yeniden başlatılmadı")
                return null
            }
            log("[${app.processId}] başlatılıyor (sürüm ${p.release}); sağlık denetimi bekleniyor")
            return mgr.awaitHealthy(app, { supervisor.isAlive(p.spec.id) })
        }

        for (app in resolved.apps.filter { it.enabled }) {
            val hasCurrent = mgr.state(app.name).current != null
            val first = try {
                prepare(app, currentOnly = false)
            } catch (t: Throwable) {
                val pending = mgr.state(app.name).pending
                if (pending != null && hasCurrent) {
                    mgr.markFailed(app.name, pending, t.message ?: "")
                    problem(tr("[{0}] yeni sürüm hazırlanamadı, çalışan sürümle devam ediliyor: {1}", app.name, t.message))
                    runCatching { prepare(app, currentOnly = true) }.getOrNull()
                } else {
                    problem(tr("[{0}] başlatılamadı: {1}", app.name, t.message))
                    null
                }
            } ?: continue

            val reason = run(app, first)
            when {
                reason == null -> mgr.promote(app, first.release)
                first.candidate && hasCurrent -> {
                    mgr.markFailed(app.name, first.release, reason)
                    problem(tr("[{0}] yeni sürüm ({1}) sağlık denetiminden geçmedi: {2}. Önceki sürüme dönülüyor.", app.name, first.release, reason))
                    val back = runCatching { prepare(app, currentOnly = true) }.getOrElse {
                        problem(tr("[{0}] önceki sürüm başlatılamadı: {1}", app.name, it.message))
                        supervisor.stop(app.processId)
                        null
                    } ?: continue
                    run(app, back)?.let { problem(tr("[{0}] önceki sürüm de sağlıklı değil: {1}", app.name, it)) }
                }
                else -> problem(tr("[{0}] sağlık denetiminden geçmedi: {1}", app.name, reason))
            }
        }
        return problems
    }

    private fun stopUnwanted(resolved: ServerConfig) {
        val wantedIds = resolved.components.filter { it.enabled }.map { it.id }.toSet() +
            resolved.apps.filter { it.enabled && apps != null }.map { it.processId }
        for (id in supervisor.ids() - wantedIds) {
            supervisor.stop(id)
            log("[$id] durduruldu (konfigürasyonda etkin değil)")
        }
    }

    private fun describe(db: DatabaseConfig) = when (db.engine) {
        DbEngine.SQLITE -> "SQLite (${db.file})"
        DbEngine.POSTGRES -> "PostgreSQL (${db.url})"
    }
}
