# CTM Forge has no reflection-based serialization (kotlinx JsonElement only), so the
# default Android rules are enough. Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Shizuku starts the install service by class name in its own process.
-keep class com.alastor.ctmforge.update.InstallService { public <init>(); *; }
-keep class com.alastor.ctmforge.update.IInstallService** { *; }
# Shizuku's binder plumbing (small; keep it whole rather than trust reflection-free shrinking)
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
