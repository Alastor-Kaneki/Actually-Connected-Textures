package com.alastor.ctmforge.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

enum class OpenWith(val label: String) {
    AUTO("Automatic"),
    LEVILAUNCHER("LeviLauncher"),
    MINECRAFT("Minecraft"),
    ASK("Ask every time"),
}

/** App settings, backed by SharedPreferences and readable as Compose state. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    var themeMode by mutableStateOf(enumPref("theme", ThemeMode.SYSTEM))
        private set
    var dynamicColor by mutableStateOf(prefs.getBoolean("dynamic_color", true))
        private set
    var pureBlack by mutableStateOf(prefs.getBoolean("pure_black", false))
        private set
    var immersive by mutableStateOf(prefs.getBoolean("immersive", true))
        private set
    var directLayout by mutableStateOf(prefs.getBoolean("direct_layout", false))
        private set
    var openWith by mutableStateOf(enumPref("open_with", OpenWith.AUTO))
        private set
    var modCardDismissed by mutableStateOf(prefs.getBoolean("mod_card_dismissed", false))
        private set
    /** Look for new versions of the app and the mod on GitHub when the app opens. */
    var autoUpdate by mutableStateOf(prefs.getBoolean("auto_update", true))
        private set
    /** Install app updates by themselves through Shizuku, when it's running and allowed. */
    var shizukuInstall by mutableStateOf(prefs.getBoolean("shizuku_install", true))
        private set
    /** Newest mod version handed to LeviLauncher from this app. */
    var modSentVersion by mutableStateOf(prefs.getString("mod_sent_version", null))
        private set

    fun updateThemeMode(v: ThemeMode) { themeMode = v; prefs.edit { putString("theme", v.name) } }
    fun updateDynamicColor(v: Boolean) { dynamicColor = v; prefs.edit { putBoolean("dynamic_color", v) } }
    fun updatePureBlack(v: Boolean) { pureBlack = v; prefs.edit { putBoolean("pure_black", v) } }
    fun updateImmersive(v: Boolean) { immersive = v; prefs.edit { putBoolean("immersive", v) } }
    fun updateDirectLayout(v: Boolean) { directLayout = v; prefs.edit { putBoolean("direct_layout", v) } }
    fun updateOpenWith(v: OpenWith) { openWith = v; prefs.edit { putString("open_with", v.name) } }
    fun updateModCardDismissed(v: Boolean) { modCardDismissed = v; prefs.edit { putBoolean("mod_card_dismissed", v) } }
    fun updateAutoUpdate(v: Boolean) { autoUpdate = v; prefs.edit { putBoolean("auto_update", v) } }
    fun updateShizukuInstall(v: Boolean) { shizukuInstall = v; prefs.edit { putBoolean("shizuku_install", v) } }
    fun updateModSentVersion(v: String) { modSentVersion = v; prefs.edit { putString("mod_sent_version", v) } }

    /**
     * Next patch version for a pack. Minecraft refuses to import a pack whose UUID and version
     * it already has, so every conversion of the same pack gets a higher version.
     */
    @Synchronized
    fun nextPatch(key: String): Int {
        val n = prefs.getInt("patch_$key", -1) + 1
        prefs.edit { putInt("patch_$key", n) }
        return n
    }
}
