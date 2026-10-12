# kotlinx.serialization: keep generated serializers for the catalog and cache models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.tvessentials.core.**$$serializer { *; }
-keepclassmembers class app.tvessentials.core.** { *** Companion; }
-keepclasseswithmembers class app.tvessentials.core.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp optional TLS providers are not bundled on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
