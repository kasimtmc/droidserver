# Yayın derlemesinde R8 (küçültme ve karartma). Varsayılanlar proguard-android-optimize.txt'den; kütüphanelerin
# kendi kuralları (kotlinx.serialization, coroutines, Compose, Play) otomatik gelir. Burada yalnızca yansımayla
# çalışan, kendi kuralı olmayan kütüphaneler:

# Rhino: standart nesneleri (JSON, RegExp …) ve derlenen kodu sınıf adıyla yükler.
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# PostgreSQL JDBC: sürücü, bağlantı özellikleri ve tip eşlemeleri yansımayla.
-keep class org.postgresql.** { *; }
-dontwarn org.postgresql.**

# Jackson (Anthropic SDK, java-jwt): veri sınıflarını ve modülleri yansımayla okur/yazar.
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keep class com.anthropic.** { *; }
-dontwarn com.anthropic.**
-keep class com.auth0.jwt.** { *; }
-dontwarn com.auth0.jwt.**
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
# Anthropic SDK'nın getirdikleri: JSON şeması üreteci (yansıma) ve OpenTelemetry. Android'de olmayan JDK sınıflarına
# (java.lang.management, AnnotatedType) ve derlemede kalan AutoValue ek açıklamasına başvururlar; çağrılmazlar.
-keep class com.github.victools.jsonschema.** { *; }
-dontwarn com.github.victools.jsonschema.**
-dontwarn com.google.auto.value.AutoValue
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType

# Ktor'un hata ayıklayıcı denetimi (yalnızca JVM'de çalışır).
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Hata raporlarında satır numaraları (mapping.txt ile çözülür).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
