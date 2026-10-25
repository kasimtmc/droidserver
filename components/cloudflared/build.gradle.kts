// Play sürümünün isteğe bağlı cloudflared modülü: yalnızca program (libcloudflared.so), kod yok. Program depodaki
// bileşen paketinden tools/play-components.py ile build/play-components altına çıkarılır (Git'te tutulmaz).
plugins {
    alias(libs.plugins.android.dynamic.feature)
}

android {
    namespace = "com.plyg.droidserver.components.cloudflared"
    compileSdk = 37
    defaultConfig { minSdk = 26 }

    // Temel modülün türleriyle aynı olmalı; program yalnızca play türünde (GitHub sürümü indirir).
    flavorDimensions += "channel"
    productFlavors {
        create("full") { dimension = "channel" }
        create("play") { dimension = "channel" }
    }
    sourceSets.getByName("play").jniLibs.directories += rootProject.file("build/play-components/cloudflared/jniLibs").path

    packaging {
        // Uygulama gibi: programlar kurulumda kitaplık klasörüne açılır (oradan çalıştırılır). Go programı zaten
        // soyulmuş; derleme yeniden soymaya çalışmasın.
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" }
    }
}

dependencies {
    implementation(project(":app"))
}
