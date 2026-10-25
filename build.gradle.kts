plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.dynamic.feature) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Play sürümünün bileşen modülleri (components/<id>): her biri yalnızca tools/play-components.py'nin ürettiği dosyaları
// taşır (kod yok). Dosyalar yalnızca play türünde girer; GitHub sürümü bileşenleri indirir.
subprojects {
    if (parent?.name != "components") return@subprojects
    pluginManager.withPlugin("com.android.dynamic-feature") {
        extensions.configure<com.android.build.api.dsl.DynamicFeatureExtension> {
            namespace = "com.plyg.droidserver.components.${project.name}"
            compileSdk = 37
            defaultConfig { minSdk = 26 }
            // Temel modülün türleriyle aynı olmalı.
            flavorDimensions += "channel"
            productFlavors {
                create("full") { dimension = "channel" }
                create("play") { dimension = "channel" }
            }
            sourceSets.getByName("play").jniLibs.directories += rootProject.file("build/play-components/${project.name}/jniLibs").path
            // Uygulama gibi: dosyalar kurulumda kitaplık klasörüne açılır (oradan çalıştırılır). Programlar zaten soyulmuş;
            // veri dosyaları da ".so" adını taşıdığı için derleme soymaya çalışmasın.
            packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" } }
        }
        dependencies.add("implementation", project(":app"))
    }
}
