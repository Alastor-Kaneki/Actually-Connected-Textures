package com.alastor.ctmforge.core

import com.alastor.ctmforge.core.net.GitHub
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/github/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun levipack(manifest: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("libConnectedTextures.so")); z.write(ByteArray(16)); z.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun parsesARealRelease() {
        val r = GitHub.parseRelease(Json.parseToJsonElement(fixture("release-latest.json")))
        assertEquals("v2026.11.05", r.tag)
        assertFalse(r.prerelease)
        assertTrue(r.pageUrl.endsWith("/releases/tag/v2026.11.05"))

        val app = assertNotNull(Updates.appAsset(r))
        assertEquals(3, app.versionCode)
        assertEquals("CTM-Forge-3.apk", app.asset.name)
        assertEquals(2962738L, app.asset.size)
        assertEquals("ed8ebf424cdb8bf77ec4ac15d3e783e1d33b18c1b4d01e5014f479285b971120", app.asset.sha256)
        assertTrue(app.asset.url.startsWith("https://github.com/Alastor-Kaneki/Actually-Connected-Textures/releases/download/"))

        val mod = assertNotNull(Updates.modAsset(r))
        assertEquals("ConnectedTextures.levipack", mod.name)
        assertEquals(93191L, mod.size)
    }

    @Test
    fun picksTheHighestApk() {
        val json = """{"tag_name":"v9","assets":[
            {"name":"CTM-Forge-7.apk","browser_download_url":"https://x/7","size":1},
            {"name":"CTM-Forge-12.apk","browser_download_url":"https://x/12","size":1,"digest":"sha256:ABCDEF"},
            {"name":"notes.txt","browser_download_url":"https://x/n","size":1}]}"""
        val r = GitHub.parseRelease(Json.parseToJsonElement(json))
        assertEquals("v9", r.name) // no name: falls back to the tag
        val app = assertNotNull(Updates.appAsset(r))
        assertEquals(12, app.versionCode)
        assertEquals("abcdef", app.asset.sha256)
        assertNull(Updates.modAsset(r))
        assertNull(Updates.apkVersionCode("CTM-Forge-x.apk"))
        assertNull(Updates.apkVersionCode("app-release.apk"))
    }

    @Test
    fun comparesVersions() {
        assertTrue(Updates.compareVersions("1.0.2", "1.0.1") > 0)
        assertTrue(Updates.compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(Updates.compareVersions("1.0", "1.0.1") < 0)
        assertEquals(0, Updates.compareVersions("1.0", "1.0.0"))
        assertEquals(0, Updates.compareVersions("v2.1", "2.1"))
        assertTrue(Updates.compareVersions("2.0", "10.0") < 0)
        assertTrue(Updates.compareVersions("1.0.2-beta", "1.0.2") > 0) // text parts compare as text
    }

    @Test
    fun readsTheModManifest() {
        val info = assertNotNull(
            Updates.modInfo(
                levipack(
                    """{"type":"preload-native","name":"Connected Textures","version":"1.0.2",
                    "description":"OptiFine-style connected textures. Needs a converted CTM resource pack. Built for Minecraft 1.26.60 and 1.26.62."}""",
                ),
            ),
        )
        assertEquals("1.0.2", info.version)
        assertEquals("1.26.60 and 1.26.62", info.minecraft)

        val old = assertNotNull(Updates.modInfo(levipack("""{"version":"1.0.1","description":"Built for Minecraft 1.26.60."}""")))
        assertEquals("1.26.60", old.minecraft)
        assertNull(Updates.modInfo(levipack("""{"name":"no version"}""")))
        assertNull(Updates.modInfo("not a zip".toByteArray()))
    }

    @Test
    fun checksDownloads() {
        val f = Files.createTempFile("asset", ".bin").toFile()
        f.writeText("hello")
        val sha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertEquals(sha, Updates.sha256(f))
        assertTrue(Updates.matches(f, GitHub.Asset("a", "u", 5, sha)))
        assertTrue(Updates.matches(f, GitHub.Asset("a", "u", 5, null)))
        assertFalse(Updates.matches(f, GitHub.Asset("a", "u", 6, null)))
        assertFalse(Updates.matches(f, GitHub.Asset("a", "u", 5, "00".repeat(32))))
        f.delete()
    }

    /** Live lookup of this repository's latest release. Needs CTM_NET=1. */
    @Test
    fun liveLatestRelease() {
        if (System.getenv("CTM_NET") != "1") {
            println("CTM_NET not set; skipping live test")
            return
        }
        val r = GitHub.latestRelease(Updates.REPO)
        println("latest: ${r.tag}, assets ${r.assets.map { it.name + " " + it.size }}")
        assertNotNull(Updates.appAsset(r))
        assertNotNull(Updates.modAsset(r))
    }
}
