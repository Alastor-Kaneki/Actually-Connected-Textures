package com.alastor.ctmforge.update

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import com.alastor.ctmforge.App
import com.alastor.ctmforge.AppViewModel
import com.alastor.ctmforge.BuildConfig
import com.alastor.ctmforge.core.Updates
import com.alastor.ctmforge.core.net.GitHub
import com.alastor.ctmforge.core.net.Http
import com.alastor.ctmforge.data.Launchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/**
 * Checks this repository's latest GitHub release for a newer CTM Forge and a newer Connected
 * Textures mod. The mod is small, so a newer one is downloaded right away; LeviLauncher still
 * has to import it. App updates install through Shizuku when it's available (by themselves
 * after an automatic check), else through Android's installer when the user taps Update.
 */
class Updater(private val app: App) {
    private val settings get() = app.settings
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    sealed interface Check {
        data object Idle : Check
        data object Running : Check
        data class Done(val at: Long) : Check
        data class Failed(val message: String) : Check
    }

    class AppUpdate(val versionCode: Int, val release: GitHub.Release, val asset: GitHub.Asset)

    sealed interface Install {
        data object None : Install
        data class Downloading(val done: Long, val total: Long) : Install
        /** Handed to Shizuku or to Android's installer. */
        data class Installing(val viaShizuku: Boolean) : Install
        data class Failed(val message: String) : Install
    }

    var check by mutableStateOf<Check>(Check.Idle)
        private set
    var appUpdate by mutableStateOf<AppUpdate?>(null)
        private set
    var install by mutableStateOf<Install>(Install.None)
        private set
    /** The newest mod the app has: a downloaded update, else the bundled one. */
    var mod by mutableStateOf<Updates.ModInfo?>(null)
        private set
    var bundledMod by mutableStateOf<Updates.ModInfo?>(null)
        private set

    /** A downloaded mod is newer than this build's own copy and hasn't gone to LeviLauncher yet. */
    val modPending: Boolean
        get() {
            val m = mod ?: return false
            val b = bundledMod
            if (b != null && Updates.compareVersions(m.version, b.version) <= 0) return false
            val sent = settings.modSentVersion
            return sent == null || Updates.compareVersions(m.version, sent) > 0
        }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    /** True while something shouldn't be cut off by the app restarting (set by the view model). */
    @Volatile var busy: () -> Boolean = { false }

    private val apkDir get() = File(app.cacheDir, "updates")

    val lastCheck: Long get() = prefs.getLong("last_check", 0L)

    fun start() {
        scope.launch {
            withContext(Dispatchers.IO) { tidyUp() }
            refreshModState()
        }
    }

    /** Checks if automatic checks are on and the last check is old enough. */
    fun checkIfDue() {
        if (!settings.autoUpdate || Build.FINGERPRINT == "robolectric") return
        if (System.currentTimeMillis() - lastCheck < CHECK_EVERY_MS) return
        check(auto = true)
    }

    fun check(auto: Boolean = false) {
        if (check == Check.Running) return
        check = Check.Running
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { GitHub.latestRelease(Updates.REPO) }
                val found = Updates.appAsset(release)?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                appUpdate = found?.let { AppUpdate(it.versionCode, release, it.asset) }
                val modBefore = mod?.version
                withContext(Dispatchers.IO) { fetchMod(release) }
                refreshModState()
                val now = System.currentTimeMillis()
                prefs.edit { putLong("last_check", now) }
                check = Check.Done(now)

                val newMod = mod?.version?.takeIf { it != modBefore && modPending }
                val up = appUpdate
                when {
                    up != null && auto && shizukuReady() -> installApp(automatic = true)
                    up != null && auto -> _messages.tryEmit("CTM Forge build ${up.versionCode} is out. Update it in Settings.")
                }
                if (newMod != null && (auto || up == null)) {
                    _messages.tryEmit("Connected Textures $newMod downloaded. Install it in LeviLauncher from Settings.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                check = Check.Failed(friendly(e))
            }
        }
    }

    private fun shizukuReady() = settings.shizukuInstall && ShizukuInstaller.state(app) == ShizukuInstaller.State.READY

    /** Whether CTM Forge is what the user is looking at (so reopening it after an update is expected). */
    private fun onScreen(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** Downloads the release's mod when it differs from the last one looked at, and keeps it if it's newer. */
    private fun fetchMod(release: GitHub.Release) {
        val asset = Updates.modAsset(release) ?: return
        val key = asset.sha256 ?: "${asset.url}#${asset.size}"
        if (prefs.getString("mod_checked", null) == key) return // already looked at this exact file
        val tmpDir = File(app.cacheDir, "updates/mod").apply { mkdirs() }
        val dl = Http.download(asset.url, tmpDir, suggestedName = asset.name, maxBytes = 64L * 1024 * 1024)
        try {
            if (!Updates.matches(dl.file, asset)) throw IOException("The mod download was damaged. Try again.")
            val bytes = dl.file.readBytes()
            val info = Updates.modInfo(bytes) ?: throw IOException("The release's mod file isn't a .levipack")
            val current = Launchers.modInfo(app)
            if (current == null || Updates.compareVersions(info.version, current.version) > 0) {
                val target = Launchers.downloadedModFile(app)
                target.parentFile?.mkdirs()
                val part = File(target.parentFile, target.name + ".part")
                part.writeBytes(bytes)
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
            }
            prefs.edit { putString("mod_checked", key) }
        } finally {
            dl.file.delete()
        }
    }

    private suspend fun refreshModState() {
        val (newest, bundled) = withContext(Dispatchers.IO) { Launchers.modInfo(app) to Launchers.bundledModInfo(app) }
        mod = newest
        bundledMod = bundled
    }

    /** Call after the mod went to LeviLauncher. */
    fun markModSent() {
        mod?.let { settings.updateModSentVersion(it.version) }
    }

    /**
     * Downloads and installs [appUpdate]: through Shizuku when it's on and ready, else with
     * Android's installer (which asks the user to confirm).
     */
    fun installApp(automatic: Boolean = false) {
        val up = appUpdate ?: return
        if (install is Install.Downloading || install is Install.Installing) return
        scope.launch {
            try {
                install = Install.Downloading(0, up.asset.size)
                val apk = withContext(Dispatchers.IO) { downloadApk(up).also { verify(it) } }
                if (automatic) {
                    // don't restart the app in the middle of a conversion
                    var waited = 0L
                    while (busy() && waited < 10 * 60_000L) { delay(3_000); waited += 3_000 }
                    if (busy() || !shizukuReady()) {
                        install = Install.None
                        _messages.tryEmit("CTM Forge build ${up.versionCode} is out. Update it in Settings.")
                        return@launch
                    }
                }
                var viaShizuku = shizukuReady()
                if (!viaShizuku && !automatic && settings.shizukuInstall &&
                    ShizukuInstaller.state(app) == ShizukuInstaller.State.NO_PERMISSION
                ) {
                    // Shizuku is running but hasn't allowed CTM Forge yet: ask once, here
                    install = Install.Installing(viaShizuku = true)
                    viaShizuku = withTimeoutOrNull(120_000) { ShizukuInstaller.awaitPermission() } == true && shizukuReady()
                }
                if (viaShizuku) {
                    install = Install.Installing(viaShizuku = true)
                    _messages.tryEmit("Updating CTM Forge to build ${up.versionCode}. It closes and opens again by itself.")
                    delay(1_500) // let the message show before the app closes
                    val out = ShizukuInstaller.install(app, apk, reopen = onScreen())
                    // Still here: pm failed, or it worked and Android didn't stop the app yet
                    if (!out.contains("Success")) throw IOException(pmFailure(out))
                    install = Install.None
                } else {
                    if (!SystemInstaller.allowed(app)) {
                        install = Install.Failed("Allow CTM Forge to install apps in the screen that just opened, then tap Update again.")
                        SystemInstaller.openUnknownSourcesSettings(app)
                        return@launch
                    }
                    install = Install.Installing(viaShizuku = false)
                    withContext(Dispatchers.IO) { SystemInstaller.install(app, apk) }
                    // InstallReceiver reports back
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                install = Install.Failed(friendly(e))
            }
        }
    }

    /** Puts the updater in a given state, for screenshot tests. */
    @androidx.annotation.VisibleForTesting
    internal fun showForTest(appUpdate: AppUpdate?, mod: Updates.ModInfo?, install: Install = Install.None) {
        this.appUpdate = appUpdate
        this.mod = mod
        this.install = install
        check = Check.Done(System.currentTimeMillis() - 5 * 60_000L)
    }

    fun onInstalled() {
        install = Install.None
        appUpdate = null
    }

    /** [message] null: the user cancelled. */
    fun onInstallFailed(message: String?) {
        install = if (message == null) Install.None else Install.Failed(message)
    }

    private fun downloadApk(up: AppUpdate): File {
        val dir = apkDir.apply { mkdirs() }
        val existing = File(dir, up.asset.name)
        if (Updates.matches(existing, up.asset)) return existing
        val dl = Http.download(up.asset.url, dir, suggestedName = up.asset.name, maxBytes = 256L * 1024 * 1024) { done, total ->
            install = Install.Downloading(done, if (total > 0) total else up.asset.size)
        }
        if (!Updates.matches(dl.file, up.asset)) {
            dl.file.delete()
            throw IOException("The download was damaged. Try again.")
        }
        return dl.file
    }

    /** Same app, newer build, and signed with the same key (else Android refuses the update). */
    @Suppress("DEPRECATION")
    private fun verify(apk: File) {
        val pm = app.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: run { apk.delete(); throw IOException("The downloaded file isn't an APK") }
        if (info.packageName != app.packageName) throw IOException("The downloaded APK is a different app (${info.packageName})")
        if (PackageInfoCompat.getLongVersionCode(info) <= BuildConfig.VERSION_CODE) throw IOException("The downloaded APK isn't newer than this build")
        val mine = runCatching { pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo }.getOrNull()
        val theirs = info.signingInfo
        if (mine != null && theirs != null) {
            val ours = signers(mine).toSet()
            if (ours.isNotEmpty() && signers(theirs).none { it in ours }) throw IOException(SIGNED_DIFFERENTLY)
        }
    }

    private fun signers(s: android.content.pm.SigningInfo): List<Signature> =
        (runCatching { s.apkContentsSigners?.toList() }.getOrNull() ?: emptyList()) +
            (runCatching { s.signingCertificateHistory?.toList() }.getOrNull() ?: emptyList())

    /** Drops APKs this build has caught up with, and a downloaded mod the bundled one replaced. */
    private fun tidyUp() {
        apkDir.listFiles()?.forEach { f ->
            val code = Updates.apkVersionCode(f.name)
            if (f.isFile && (code == null || code <= BuildConfig.VERSION_CODE)) f.delete()
        }
        val downloaded = Launchers.downloadedModFile(app)
        if (downloaded.isFile) {
            val d = runCatching { Updates.modInfo(downloaded.readBytes()) }.getOrNull()
            val b = Launchers.bundledModInfo(app)
            if (d == null || (b != null && Updates.compareVersions(d.version, b.version) <= 0)) downloaded.delete()
        }
    }

    private fun pmFailure(out: String): String = when {
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out || "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE" in out -> SIGNED_DIFFERENTLY
        "INSTALL_FAILED_VERSION_DOWNGRADE" in out -> "Android refused the update: it's older than the installed app."
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" in out -> "Not enough storage to install the update."
        out.isBlank() -> "Shizuku's install didn't report anything. Try again."
        else -> "The install failed: ${out.lineSequence().lastOrNull { it.isNotBlank() }?.take(200)}"
    }

    private fun friendly(e: Throwable): String = when {
        e is Http.HttpException && e.code == 404 -> "There are no releases on GitHub yet."
        e is Http.HttpException && (e.code == 403 || e.code == 429) -> "GitHub's hourly limit for update checks was reached. Try again later."
        else -> AppViewModel.friendly(e)
    }

    companion object {
        const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
        const val SIGNED_DIFFERENTLY =
            "This update is signed with a different key than the CTM Forge you have, so Android won't install it over " +
                "this one. Uninstall CTM Forge, then install the APK from the release page."
    }
}
