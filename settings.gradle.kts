pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "droidserver"

// core: saf Kotlin/JVM — Android'e bağımlı değil, bilgisayarda test edilebilir.
// app : Android uygulaması (UI, foreground service, Android SQLite sürücüsü).
include(":core", ":app")
// components:<ad>: Play sürümünün isteğe bağlı bileşen modülleri (yalnızca programlar, kod yok; bkz. tools/play-components.py).
include(":components:cloudflared")
