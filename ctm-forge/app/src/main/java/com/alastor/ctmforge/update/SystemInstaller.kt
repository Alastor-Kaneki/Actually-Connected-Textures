package com.alastor.ctmforge.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import com.alastor.ctmforge.App
import java.io.File

/** Installs app updates with Android's own installer: the user confirms each one. */
object SystemInstaller {
    const val ACTION_STATUS = "com.alastor.ctmforge.action.INSTALL_STATUS"

    /** Whether CTM Forge may install apps ("Install unknown apps" in system settings). */
    fun allowed(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Starts an install session; [InstallReceiver] hears how it went. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Android 12+: once CTM Forge has installed itself, later updates can skip the prompt
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = Intent(context, InstallReceiver::class.java).setAction(ACTION_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
            }
        } catch (e: Throwable) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }
}

/** Gets the install session's result: asks the user to confirm, or reports a failure. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SystemInstaller.ACTION_STATUS) return
        val updater = (context.applicationContext as App).updater
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    updater.onInstallFailed("Android didn't show the install prompt")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    updater.onInstallFailed("Couldn't show the install prompt: ${e.message}")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> updater.onInstalled()
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.onInstallFailed(null)
            else -> updater.onInstallFailed(describe(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }

    private fun describe(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "Android refused the update: it's signed with a different key than the installed app. Uninstall CTM Forge, then install the new APK."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update doesn't work on this phone."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install."
        else -> "The install failed" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
    }
}
