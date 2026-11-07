package com.alastor.ctmforge.core

import com.alastor.ctmforge.core.net.GitHub
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * What a GitHub release of this repository offers. CI attaches `CTM-Forge-<versionCode>.apk`
 * and `ConnectedTextures.levipack` to every `v*` tag (see .github/workflows/build.yml).
 */
object Updates {
    const val REPO = "Alastor-Kaneki/Actually-Connected-Textures"
    const val MOD_ASSET = "ConnectedTextures.levipack"
    private val APK_NAME = Regex("""CTM-Forge-(\d+)\.apk""")

    /** "CTM-Forge-5.apk" -> 5 */
    fun apkVersionCode(assetName: String): Int? = APK_NAME.matchEntire(assetName)?.groupValues?.get(1)?.toIntOrNull()

    class AppAsset(val asset: GitHub.Asset, val versionCode: Int)

    /** The release's app APK with the highest version code. */
    fun appAsset(release: GitHub.Release): AppAsset? =
        release.assets.mapNotNull { a -> apkVersionCode(a.name)?.let { AppAsset(a, it) } }.maxByOrNull { it.versionCode }

    fun modAsset(release: GitHub.Release): GitHub.Asset? =
        release.assets.firstOrNull { it.name == MOD_ASSET } ?: release.assets.firstOrNull { it.name.endsWith(".levipack") }

    /**
     * Compares dotted versions part by part: numbers as numbers (1.0.10 > 1.0.9), anything else
     * as text. Missing parts count as 0, a leading "v" is ignored.
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.trim().removePrefix("v").split('.', '-', '+')
        val pb = b.trim().removePrefix("v").split('.', '-', '+')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { "0" }
            val y = pb.getOrElse(i) { "0" }
            val nx = x.toLongOrNull()
            val ny = y.toLongOrNull()
            val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
            if (c != 0) return if (c < 0) -1 else 1
        }
        return 0
    }

    class ModInfo(
        val version: String,
        /** "1.26.60 and 1.26.62", from "Built for Minecraft ..." in the manifest's description. */
        val minecraft: String?,
    )

    /** Version info from a .levipack's manifest.json, or null when it isn't a levipack. */
    fun modInfo(levipack: ByteArray): ModInfo? = runCatching {
        ZipInputStream(levipack.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name != "manifest.json") continue
                val o = Json.parseToJsonElement(z.readBytes().toString(Charsets.UTF_8)) as JsonObject
                val version = (o["version"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@use null
                val description = (o["description"] as? JsonPrimitive)?.content.orEmpty()
                val minecraft = Regex("""Built for Minecraft (.+?)\.?\s*$""").find(description)?.groupValues?.get(1)
                return@use ModInfo(version, minecraft)
            }
            null
        }
    }.getOrNull()

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** True when [file] is what [asset] describes: same size, and same SHA-256 when GitHub gave one. */
    fun matches(file: File, asset: GitHub.Asset): Boolean =
        file.isFile && (asset.size < 0 || file.length() == asset.size) && (asset.sha256 == null || sha256(file) == asset.sha256)
}
