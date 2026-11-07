package com.alastor.ctmforge.update

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alastor.ctmforge.BuildConfig
import com.alastor.ctmforge.MainActivity
import com.alastor.ctmforge.data.Launchers
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Installs app updates through Shizuku (https://shizuku.rikka.app), without the system prompt. */
object ShizukuInstaller {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST = 4207

    enum class State(val label: String) {
        NOT_INSTALLED("Shizuku isn't installed"),
        NOT_RUNNING("Shizuku isn't running. Start it in the Shizuku app."),
        TOO_OLD("This Shizuku is too old. Update it."),
        NO_PERMISSION("CTM Forge isn't allowed in Shizuku yet"),
        READY("Ready: updates install without the system prompt"),
    }

    /** Bumped whenever Shizuku's binder or permission changes, so Compose re-reads [state]. */
    var changes by mutableStateOf(0)
        private set

    private var permissionCallback: ((Boolean) -> Unit)? = null

    /** Call once from Application.onCreate. */
    fun init() {
        Shizuku.addBinderReceivedListenerSticky { changes++ }
        Shizuku.addBinderDeadListener { changes++ }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code != PERMISSION_REQUEST) return@addRequestPermissionResultListener
            changes++
            permissionCallback?.invoke(result == PackageManager.PERMISSION_GRANTED)
            permissionCallback = null
        }
    }

    fun state(context: Context): State {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return if (Launchers.isInstalled(context, SHIZUKU_PACKAGE)) State.NOT_RUNNING else State.NOT_INSTALLED
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) return State.TOO_OLD
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return if (granted) State.READY else State.NO_PERMISSION
    }

    /** Shows Shizuku's permission dialog. [done] gets whether CTM Forge was allowed. */
    fun requestPermission(done: (Boolean) -> Unit) {
        runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                done(true)
                return
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                // the user picked "deny and don't ask again": only the Shizuku app can undo that
                done(false)
                return
            }
            permissionCallback = done
            Shizuku.requestPermission(PERMISSION_REQUEST)
        }.onFailure { done(false) }
    }

    suspend fun awaitPermission(): Boolean = suspendCancellableCoroutine { cont ->
        requestPermission { ok -> if (cont.isActive) cont.resume(ok) }
    }

    /**
     * Installs [apk] as the shell user and, with [reopen], starts CTM Forge again. Installing over
     * the running app ends this process, so this only returns when something went wrong (or
     * Android kept the app alive): then it throws, or returns pm's output.
     */
    suspend fun install(context: Context, apk: File, reopen: Boolean): String = suspendCancellableCoroutine { cont ->
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, InstallService::class.java.name))
            .daemon(false)
            .processNameSuffix("installer")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        val component = if (reopen) "${context.packageName}/${MainActivity::class.java.name}" else ""
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                if (binder == null || !binder.pingBinder()) {
                    if (cont.isActive) cont.resumeWithException(IOException("Shizuku couldn't start the installer"))
                    return
                }
                val service = IInstallService.Stub.asInterface(binder)
                val conn = this
                thread(name = "shizuku-install") {
                    try {
                        val out = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { service.install(it, component) }
                        if (cont.isActive) cont.resume(out)
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resumeWithException(e)
                    } finally {
                        runCatching { Shizuku.unbindUserService(args, conn, true) }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Throwable) {
            cont.resumeWithException(e)
        }
    }
}
