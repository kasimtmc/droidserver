package dev.droidserver.core

import dev.droidserver.core.api.SdkGenerator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Üretilen Kotlin istemcisi test kaynaklarında derlenir ve [SdkClientTest] ile gerçek sunucuya karşı çalıştırılır.
 * Üretici değişince: `UPDATE_SDK_GOLDEN=1 ./gradlew :core:test --tests '*SdkGoldenTest'`.
 */
class SdkGoldenTest {
    private val golden = File("src/test/kotlin/${SdkConfig.PACKAGE.replace('.', '/')}/DroidServerClient.kt")

    @Test
    fun `uretilen kotlin istemcisi derlenen altin dosyayla ayni`() {
        val generated = SdkGenerator.generate(SdkConfig.config, SdkGenerator.Lang.KOTLIN, SdkConfig.PACKAGE)
        if (System.getenv("UPDATE_SDK_GOLDEN") == "1") golden.writeText(generated)
        assertEquals(golden.readText(), generated, "Üretici değişti; altın dosyayı güncelleyin (UPDATE_SDK_GOLDEN=1)")
    }
}
