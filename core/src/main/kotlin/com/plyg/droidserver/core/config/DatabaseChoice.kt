package com.plyg.droidserver.core.config

/** Uygulamanın indirdiği PostgreSQL bileşeniyle kullanılan varsayılanlar. */
object PostgresDefaults {
    const val COMPONENT_ID = "postgres"
    const val URL = "jdbc:postgresql://127.0.0.1:5432/postgres"
    const val USER = "droid"

    /** Bileşenin ilk kurulumunda initdb'nin parola dosyasına yazılan ortam değişkeni (bkz. setupFiles). */
    const val PASSWORD_ENV = "PGPASSWORD"
}

/** JDBC adresindeki veritabanı adı: `jdbc:postgresql://host:port/<ad>?...`. */
object PostgresUrl {
    private val URL = Regex("^(jdbc:postgresql://[^/?]*)(?:/([^?]*))?(\\?.*)?$")

    /** Adresteki veritabanı adı; adres geçersizse veya ad yoksa null. */
    fun databaseName(url: String): String? = URL.matchEntire(url)?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }

    /** Veritabanı adı değiştirilmiş adres (sunucu ve parametreler korunur). */
    fun withDatabase(url: String, name: String): String {
        val m = URL.matchEntire(url) ?: return url
        return "${m.groupValues[1]}/$name${m.groupValues[3]}"
    }
}

/**
 * Veritabanını bileşenle çalışan PostgreSQL'e çevirir. Parola (genellikle `secret:` referansı) hem bağlantıya
 * hem bileşenin ilk kurulumuna (initdb) verilir; bileşen yoksa eklenir, kapalıysa açılır.
 */
fun ServerConfig.withPostgres(password: String): ServerConfig {
    val id = PostgresDefaults.COMPONENT_ID
    val env = PostgresDefaults.PASSWORD_ENV to password
    val updated = components.map { if (it.id == id) it.copy(enabled = true, env = it.env + env) else it }
    return copy(
        database = database.copy(
            engine = DbEngine.POSTGRES,
            url = database.url ?: PostgresDefaults.URL,
            user = database.user ?: PostgresDefaults.USER,
            password = password,
        ),
        components = if (updated.any { it.id == id }) updated else updated + ComponentConfig(id, env = mapOf(env)),
    )
}

/** SQLite'a döner (veya dosya adını değiştirir). Postgres bileşeni silinmez, kapatılır; parolası korunur. */
fun ServerConfig.withSqlite(file: String): ServerConfig = copy(
    database = database.copy(engine = DbEngine.SQLITE, file = file),
    components = if (database.engine != DbEngine.POSTGRES) components
    else components.map { if (it.id == PostgresDefaults.COMPONENT_ID) it.copy(enabled = false) else it },
)

/**
 * Verinin fiilen durduğu yer: SQLite için dosya, Postgres için adres + kullanıcı. Kullanılmayan alanlar
 * (SQLite'a dönünce kalan Postgres adresi gibi) ve parola karşılaştırmaya girmez.
 */
fun DatabaseConfig.location(): String = when (engine) {
    DbEngine.SQLITE -> "sqlite:$file"
    DbEngine.POSTGRES -> "postgres:$user@$url"
}

/** Konfigürasyonda kayıtlı Postgres parolası (bağlantıdaki veya bileşen ortamındaki); yoksa null. */
fun ServerConfig.postgresPassword(): String? =
    database.password ?: components.firstOrNull { it.id == PostgresDefaults.COMPONENT_ID }?.env?.get(PostgresDefaults.PASSWORD_ENV)
