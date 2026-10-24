import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Sürüm: `-PappVersion=1.2.3` (yayın iş akışı etiketten verir). versionCode sürümden türetilir ve her yeni sürümde
// büyür: 1.2.3 → 1020399, 1.3.0-beta.2 → 1030002 (ön sürümler aynı sürümün kararlısından küçük). Alt sürüm ve yama < 100.
val appVersion: String = providers.gradleProperty("appVersion").orNull ?: "0.1.0"

fun versionCodeOf(version: String): Int {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-[A-Za-z]*\.?(\d+)?)?$""").matchEntire(version)
        ?: error("Geçersiz sürüm: $version (beklenen 1.2.3 veya 1.3.0-beta.1)")
    val (major, minor, patch) = m.destructured.toList().take(3).map { it.toInt() }
    require(minor < 100 && patch < 100) { "Alt sürüm ve yama 100'den küçük olmalı: $version" }
    val pre = m.groupValues[4]
    val suffix = if ('-' !in version) 99 else (pre.toIntOrNull() ?: 0).coerceAtMost(98)
    return ((major * 100 + minor) * 100 + patch) * 100 + suffix
}

// Yayın imzası ortam değişkenlerinden (GitHub Actions secrets); yoksa yayın APK'sı imzasız üretilir.
val releaseKeystore: String? = System.getenv("ANDROID_KEYSTORE_FILE")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.plyg.droidserver.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.plyg.droidserver"
        minSdk = 26
        // BİLİNÇLİ SEÇİM: targetSdk 28 -> indirilen native binary'ler uygulama klasöründen
        // çalıştırılabilir (Android 10+ W^X kısıtı uygulanmaz). Play Store'da yayınlanamaz.
        targetSdk = 28
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
        // Yerel YZ (llama.cpp) yalnızca bu ABI'ler için derlenir; paketlenen diğer kitaplıklar (tüm ABI'ler) etkilenmez,
        // uygulama 32 bit telefonlara da kurulur (yerel YZ orada yok).
        externalNativeBuild { cmake { abiFilters += listOf("arm64-v8a", "x86_64"); arguments += listOf("-DCMAKE_BUILD_TYPE=Release") } }
    }

    ndkVersion = "29.0.14206865"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "4.1.2" } }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            // Tekrarlanabilir derleme (F-Droid aynı kaynaktan aynı APK'yı üretebilsin): derleme ortamına bağlı Git bilgisi
            // APK'ya yazılmaz.
            vcsInfo.include = false
        }
    }

    // Bağımlılık listesi APK'ya Google'ın anahtarıyla şifreli bir blok olarak eklenir; F-Droid bunu kabul etmez.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
            )
        }
        jniLibs { useLegacyPackaging = true }
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    lint {
        // targetSdk 28 Play Store dışı dağıtım için kasıtlı.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Uygulama içi yardım: README'ler derlemede `assets/help/` altına kopyalanır (çevrimdışı; bölümlere uygulama ayırır).
val helpAssets = layout.buildDirectory.dir("generated/help-assets")
val copyHelp by tasks.registering(Copy::class) {
    from(rootProject.file("README.md"), rootProject.file("README.en.md"))
    into(helpAssets.map { it.dir("help") })
}
android.sourceSets.getByName("main").assets.directories += helpAssets.get().asFile.path
tasks.named("preBuild") { dependsOn(copyHelp) }

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)

    // WizardViewModel saf Kotlin tutulur; testleri JVM'de (Robolectric olmadan) koşar.
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.sqlite.jdbc)

    // Ekran görüntüsü testleri (JVM'de, Robolectric ile çizilir): ./gradlew :app:testDebugUnitTest -Pscreenshots
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    val shots = project.hasProperty("screenshots")
    systemProperty("ds.screenshots", shots.toString())
    systemProperty("roborazzi.test.record", shots.toString())
    systemProperty("ds.screenshotDir", rootProject.file("build/screenshots").absolutePath)
}
