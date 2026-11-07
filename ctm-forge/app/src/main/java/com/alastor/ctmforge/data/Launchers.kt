package com.alastor.ctmforge.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.alastor.ctmforge.core.Updates
import java.io.File

/** Hands packs and the mod to LeviLauncher / Minecraft, and shares files. */
object Launchers {
    const val LEVILAUNCHER = "org.levimc.launcher"
    const val MINECRAFT = "com.mojang.minecraftpe"
    const val MOD_ASSET = "ConnectedTextures.levipack"

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** Which app "Import" sends packs to, given the setting and what is installed. */
    fun importTarget(context: Context, setting: OpenWith): String? = when (setting) {
        OpenWith.AUTO -> when {
            isInstalled(context, LEVILAUNCHER) -> LEVILAUNCHER
            isInstalled(context, MINECRAFT) -> MINECRAFT
            else -> null
        }
        OpenWith.LEVILAUNCHER -> LEVILAUNCHER
        OpenWith.MINECRAFT -> MINECRAFT
        OpenWith.ASK -> null
    }

    fun importLabel(context: Context, setting: OpenWith): String = when (importTarget(context, setting)) {
        LEVILAUNCHER -> "Import into LeviLauncher"
        MINECRAFT -> "Import into Minecraft"
        else -> "Open with…"
    }

    private fun viewIntent(context: Context, file: File): Intent {
        val uri = uriFor(context, file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Opens [file] (an .mcpack or .levipack) in [pkg], or in a chooser when [pkg] is null or
     * missing. Returns a message for the user when something didn't work out.
     */
    fun open(context: Context, file: File, pkg: String?): String? {
        val intent = viewIntent(context, file)
        if (pkg != null) {
            if (!isInstalled(context, pkg)) {
                val name = if (pkg == LEVILAUNCHER) "LeviLauncher" else "Minecraft"
                return chooser(context, intent, "$name isn't installed, pick another app")
            }
            intent.setPackage(pkg)
            try {
                context.startActivity(intent)
                return null
            } catch (_: ActivityNotFoundException) {
                intent.setPackage(null)
            } catch (e: SecurityException) {
                return "Couldn't open the file: ${e.message}"
            }
        }
        return chooser(context, intent, null)
    }

    private fun chooser(context: Context, intent: Intent, note: String?): String? = try {
        context.startActivity(Intent.createChooser(intent, "Open ${intent.clipData?.description?.label ?: "file"}"))
        note
    } catch (_: ActivityNotFoundException) {
        "No app on this device can open this file"
    }

    fun share(context: Context, file: File, mime: String = "application/octet-stream") {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share ${file.name}"))
    }

    fun openUrl(context: Context, url: String): String? = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        null
    } catch (_: ActivityNotFoundException) {
        "No browser found"
    }

    /** Where the updater keeps a newer mod downloaded from GitHub. */
    fun downloadedModFile(context: Context): File = File(File(context.filesDir, "updates"), MOD_ASSET)

    private fun bundledMod(context: Context): ByteArray? = runCatching { context.assets.open(MOD_ASSET).use { it.readBytes() } }.getOrNull()

    /** The newest Connected Textures mod the app has: a downloaded update, else the bundled copy. */
    fun modBytes(context: Context): ByteArray {
        val bundled = bundledMod(context)
        val downloaded = downloadedModFile(context).takeIf { it.isFile }?.let { f -> runCatching { f.readBytes() }.getOrNull() }
        if (downloaded != null) {
            val d = Updates.modInfo(downloaded)
            val b = bundled?.let { Updates.modInfo(it) }
            if (d != null && (b == null || Updates.compareVersions(d.version, b.version) > 0)) return downloaded
        }
        return bundled ?: throw java.io.IOException("The mod file is missing from this build")
    }

    /** Version of the bundled mod alone (what a fresh install of this build offers). */
    fun bundledModInfo(context: Context): Updates.ModInfo? = bundledMod(context)?.let { Updates.modInfo(it) }

    fun modInfo(context: Context): Updates.ModInfo? = runCatching { Updates.modInfo(modBytes(context)) }.getOrNull()

    /** The newest mod, copied where FileProvider can share it. */
    fun modFile(context: Context): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, MOD_ASSET)
        val bytes = modBytes(context)
        if (!out.isFile || out.length() != bytes.size.toLong() || !out.readBytes().contentEquals(bytes)) out.writeBytes(bytes)
        return out
    }

    fun modVersion(context: Context): String = modInfo(context)?.version ?: "?"
}
