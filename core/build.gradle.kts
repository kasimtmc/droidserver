import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.ktor.server.core)
    api(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.method.override)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.conditional.headers)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.serialization.json)
    implementation(libs.java.jwt)
    // Script motoru: saf Java JavaScript yorumlayıcısı (Android'de bytecode üretilemediği için yorumlama kipinde).
    implementation(libs.rhino)
    api(libs.coroutines.core)
    api(libs.serialization.json)
    // PostgreSQL bileşenine bağlanmak için; saf Java, Android'de de çalışır.
    implementation(libs.pgjdbc)
    // Bağlantı QR kodu (saf Java, bağımlılıksız).
    implementation(libs.zxing.core)
    // YZ: resmi Claude API istemcisi (Java SDK).
    implementation(libs.anthropic.java)

    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    // Lehçe testleri gerçek bir PostgreSQL'e karşı koşar (ikililer Maven'den iner; root ile koşulursa
    // Postgres süreçleri root olmayan kullanıcıyla çalıştırılır, bkz. NonRootPostgres.kt).
    testImplementation(libs.embedded.postgres)
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(kotlin("test-junit"))
}

tasks.test {
    doFirst {
        if (System.getProperty("user.name") == "root") {
            logger.warn(
                "UYARI: PostgreSQL root kullanıcısıyla çalıştırılamıyor; Postgres süreçleri otomatik olarak " +
                    "root olmayan kullanıcıyla (nobody) çalıştırılıyor.",
            )
        }
    }
}
