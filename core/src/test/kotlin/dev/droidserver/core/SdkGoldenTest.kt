package dev.droidserver.core

import dev.droidserver.core.api.SdkGenerator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Üretilen Kotlin istemcisi test kaynaklarında derlenir ve [SdkClientTest] ile gerçek sunucuya karşı çalıştırılır; Dart ve
 * Swift istemcileri `src/test/sdk/` altındadır ve [SdkClientTest] onları da (araç kuruluysa) derleyip çalıştırır.
 * Üretici değişince: `UPDATE_SDK_GOLDEN=1 ./gradlew :core:test --tests '*SdkGoldenTest'`.
 */
class SdkGoldenTest {
    private fun check(lang: SdkGenerator.Lang, golden: File) {
        val generated = SdkGenerator.generate(SdkConfig.config, lang, SdkConfig.PACKAGE)
        if (System.getenv("UPDATE_SDK_GOLDEN") == "1") golden.writeText(generated)
        assertEquals(golden.readText(), generated, "Üretici değişti; altın dosyayı güncelleyin (UPDATE_SDK_GOLDEN=1)")
    }

    @Test
    fun `uretilen kotlin istemcisi derlenen altin dosyayla ayni`() =
        check(SdkGenerator.Lang.KOTLIN, File("src/test/kotlin/${SdkConfig.PACKAGE.replace('.', '/')}/DroidServerClient.kt"))

    @Test
    fun `uretilen dart istemcisi altin dosyayla ayni`() = check(SdkGenerator.Lang.DART, File("src/test/sdk/droidserver.dart"))

    @Test
    fun `uretilen swift istemcisi altin dosyayla ayni`() = check(SdkGenerator.Lang.SWIFT, File("src/test/sdk/DroidServer.swift"))
}
