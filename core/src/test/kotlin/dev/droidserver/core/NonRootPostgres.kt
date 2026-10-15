package dev.droidserver.core

import io.zonky.test.db.postgres.embedded.DefaultPostgresBinaryResolver
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import io.zonky.test.db.postgres.embedded.PgBinaryResolver
import java.io.File

/**
 * PostgreSQL root olarak çalışmayı reddeder (`initdb: cannot be run as root`). Testler root ile koşturulursa
 * embedded-postgres'in ikilileri (`initdb`, `pg_ctl`, `postgres`) küçük betiklerle sarılır: betik root olarak
 * çağrılınca veri/soket klasörlerini ve parola dosyasını [USER] kullanıcısına devreder ve gerçek ikiliyi o
 * kullanıcıyla (`runuser`) çalıştırır. Root değilse gerçek ikiliyi doğrudan çalıştırır.
 *
 * İkililer paylaşılan `/tmp/embedded-pg` yerine ayrı bir klasöre açılır ki başka projelerin önbelleği değişmesin.
 */
object NonRootPostgres {
    const val USER = "nobody"

    val isRoot: Boolean get() = System.getProperty("user.name") == "root"

    /** Sarılmış ikililerin `bin` klasörü (root değilse embedded-postgres'in kendi klasörü). */
    val binDir: File by lazy { prepare() }

    private fun prepare(): File {
        if (isRoot) {
            System.err.println(
                "UYARI: PostgreSQL root kullanıcısıyla çalıştırılamıyor; Postgres süreçleri otomatik olarak " +
                    "root olmayan kullanıcıyla ($USER) çalıştırılıyor.",
            )
            check(File("/usr/sbin/runuser").canExecute() || File("/sbin/runuser").canExecute() || File("/usr/bin/runuser").canExecute()) {
                "Root olmayan Postgres için 'runuser' gerekli (util-linux)"
            }
            System.setProperty("ot.epg.working-dir", File(System.getProperty("java.io.tmpdir"), "embedded-pg-nonroot").path)
        }
        // embedded-postgres ikilileri ilk sunucuda açar; sarmalamak için önceden açtırılır.
        val prepare = EmbeddedPostgres::class.java.getDeclaredMethod("prepareBinaries", PgBinaryResolver::class.java, File::class.java)
        prepare.isAccessible = true
        val pgDir = prepare.invoke(null, DefaultPostgresBinaryResolver.INSTANCE, null) as File
        val bin = File(pgDir, "bin")
        if (isRoot) {
            // Betiğin okunabilmesi ve ikililere ulaşılabilmesi için klasörler herkese açık olmalı.
            generateSequence(pgDir) { it.parentFile }.takeWhile { it.path != "/" && it.path != System.getProperty("java.io.tmpdir") }
                .forEach { it.setReadable(true, false); it.setExecutable(true, false) }
            for (name in listOf("initdb", "pg_ctl", "postgres")) wrap(File(bin, name))
        }
        return bin
    }

    private fun wrap(binary: File) {
        val real = File(binary.path + ".real")
        if (!real.exists()) check(binary.renameTo(real)) { "$binary taşınamadı" }
        binary.writeText(
            """
            |#!/bin/sh
            |# droidserver testleri: root ise Postgres'i $USER kullanıcısıyla çalıştırır (bkz. NonRootPostgres.kt).
            |real="${'$'}0.real"
            |[ "${'$'}(id -u)" = 0 ] || exec "${'$'}real" "${'$'}@"
            |prev=""
            |for a in "${'$'}@"; do
            |  case "${'$'}prev" in -D|-k) mkdir -p "${'$'}a"; chown -R $USER "${'$'}a" ;; esac
            |  case "${'$'}a" in --pwfile=*) chown $USER "${'$'}{a#--pwfile=}" ;; esac
            |  prev="${'$'}a"
            |done
            |exec runuser -u $USER -- "${'$'}real" "${'$'}@"
            |""".trimMargin(),
        )
        binary.setExecutable(true, false)
        binary.setReadable(true, false)
    }
}
