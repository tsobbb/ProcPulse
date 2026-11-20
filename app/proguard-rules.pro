# Keep Shizuku AIDL and UserService entry points
-keep class com.example.procpulse.core.bridge.TelemetryUserService { *; }
-keep class com.example.procpulse.core.bridge.ITelemetryUserService** { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# Keep PowerProfile reflection fallback
-dontwarn com.android.internal.os.PowerProfile

# Preserve mapping info for open-source reproducible debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
