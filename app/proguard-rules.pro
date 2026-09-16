# Jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# SMBJ + jeho závislosti (self-update z NAS-u).
# SMBJ mapuje SMB správy cez reflexiu a mbassador skenuje @Handler metódy,
# takže minifikácia by mu inak podrezala nohy.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassador.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class sk.lukac.tankomat.**$$serializer { *; }
-keepclassmembers class sk.lukac.tankomat.** {
    *** Companion;
}
-keepclasseswithmembers class sk.lukac.tankomat.** {
    kotlinx.serialization.KSerializer serializer(...);
}
