# Readable crash traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp, Okio, Play Services (Nearby), ML Kit and CameraX ship their own consumer rules.
# OkHttp optionally probes these TLS providers; they are not present on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
