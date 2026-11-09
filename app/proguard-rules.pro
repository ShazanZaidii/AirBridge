# Readable crash traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp, Okio, Play Services (Nearby), ML Kit and CameraX ship their own consumer rules.
# OkHttp optionally probes these TLS providers; they are not present on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# The input half calls BubbleController.INSTANCE.setSuppressed(String, boolean) (and optionally
# setLeaseHolder(String)) by reflection; keep the class name, INSTANCE and those methods.
-keep class com.shazan.airbridge.BubbleController {
    public static final com.shazan.airbridge.BubbleController INSTANCE;
    public final void setSuppressed(java.lang.String, boolean);
    public final void setLeaseHolder(java.lang.String);
}
# NetHooks falls back to ClipboardSync.pushNow(String) by reflection.
-keep class com.shazan.airbridge.net.ClipboardSync {
    public static final com.shazan.airbridge.net.ClipboardSync INSTANCE;
    public final void pushNow(java.lang.String);
    public static void pushNow(java.lang.String);
}

# ML Kit (and any Firebase-style component) builds its registrars from manifest meta-data by
# reflection. R8 full mode drops their no-arg constructors, ML Kit then has no barcode component and
# BarcodeScanning.getClient() crashes with a NullPointerException ("Scan QR code" in release builds).
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
    public java.util.List getComponents();
}
