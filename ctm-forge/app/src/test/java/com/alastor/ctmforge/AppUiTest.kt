package com.alastor.ctmforge

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alastor.ctmforge.AppViewModel.Phase
import com.alastor.ctmforge.AppViewModel.Source
import com.alastor.ctmforge.AppViewModel.Tab
import com.alastor.ctmforge.data.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Runs the real app under Robolectric: live Modrinth search, a full conversion through the
 * UI, the hand-off intent to LeviLauncher, and screenshots of every screen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val shots = File("build/screens").apply { mkdirs() }
    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun shot(name: String, settleMs: Long = 400) {
        compose.waitForIdle()
        Thread.sleep(settleMs) // images load on IO threads
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(shots, "$name.png").path)
        println("screenshot $name")
    }

    private fun zipDir(dir: File, out: File): File {
        ZipOutputStream(out.outputStream()).use { z ->
            dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return out
    }

    private fun back() = compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun fusionImportConvertAndReportUnsupported() {
        val fixtures = File(System.getProperty("ctm.fusionFixtures"))
        for (name in listOf("glass", "glass-legacy", "animated")) {
            val zip = zipDir(File(fixtures, name), File(compose.activity.cacheDir, "$name.zip"))
            compose.runOnUiThread { vm.openUri(Uri.fromFile(zip)) }
            compose.waitUntil(30_000) { vm.session?.phase == Phase.Ready || vm.session?.phase is Phase.Failed }
            assertEquals("Fusion pack must reach conversion", Phase.Ready, vm.session?.phase)
            compose.runOnUiThread { vm.convert() }
            compose.waitUntil(30_000) { vm.session?.phase is Phase.Done || vm.session?.phase is Phase.Failed }
            val phase = vm.session?.phase
            if (name == "animated") {
                assertTrue("Expected unsupported animation diagnostics: $phase", phase is Phase.Failed)
                assertTrue((phase as Phase.Failed).log.any { "animated" in it })
            } else {
                assertTrue("Fusion conversion failed: $phase", phase is Phase.Done)
                assertEquals(mapOf("glass" to "ctm-8x6-47"), (phase as Phase.Done).result.blocks)
            }
            compose.runOnUiThread { vm.closeSession() }
        }
    }

    @Test
    fun launcherIcon() {
        val d = androidx.core.content.ContextCompat.getDrawable(compose.activity, R.mipmap.ic_launcher)!!
        val bmp = android.graphics.Bitmap.createBitmap(432, 432, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, 432, 432)
        d.draw(canvas)
        File(shots, "00_launcher_icon.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun walkThroughEveryScreen() {
        val golden = File(System.getProperty("ctm.golden") ?: "", "packs/Clean_Connected_Glass_1.20-26.x")
        org.junit.Assume.assumeTrue("needs CTM_GOLDEN with the test packs", golden.isDirectory)
        // make LeviLauncher look installed, so "Import" targets it like on the user's phone
        shadowOf(compose.activity.packageManager).installPackage(PackageInfo().apply {
            packageName = "org.levimc.launcher"
            applicationInfo = ApplicationInfo().apply { packageName = "org.levimc.launcher" }
        })

        // 1. Browse: live Modrinth search
        compose.waitUntil(30_000) { vm.hits.isNotEmpty() || vm.searchError != null }
        println("search: ${vm.hits.size} hits, error=${vm.searchError}")
        shot("01_browse_modrinth", 2500)

        // 2. A project page with its versions
        if (vm.hits.isNotEmpty()) {
            val hit = vm.hits.first()
            compose.onAllNodesWithText(hit.title)[0].performClick()
            compose.waitUntil(30_000) { vm.projectState(hit).versions != null || vm.projectState(hit).error != null }
            println("versions: ${vm.projectState(hit).versions?.size}, error=${vm.projectState(hit).error}")
            shot("02_project", 2500)

            // download the latest version through the UI and convert it
            compose.onAllNodes(hasText("Convert") and hasClickAction())[0].performClick()
            compose.waitUntil(60_000) { vm.session?.phase == Phase.Ready || vm.session?.phase is Phase.Failed }
            println("modrinth download: ${vm.session?.fileName} -> ${vm.session?.phase} (${vm.session?.candidates?.map { it.label + ":" + it.rules }})")
            shot("02b_modrinth_ready")
            if (vm.session?.phase == Phase.Ready) {
                compose.onNode(hasText("Convert") and hasClickAction()).performScrollTo().performClick()
                compose.waitUntil(120_000) { vm.session?.phase is Phase.Done || vm.session?.phase is Phase.Failed }
                val r = vm.session?.phase
                println("modrinth convert: ${if (r is Phase.Done) "${r.result.blocks.size} blocks, log ${r.result.log}" else r.toString()}")
                assertTrue(r is Phase.Done)
            }
            compose.runOnUiThread { vm.closeSession() }
            back()
            compose.waitUntil(5_000) { vm.routes.isEmpty() }
        }

        // 3. Convert a local pack through the UI
        val zip = zipDir(golden, File(compose.activity.cacheDir, "Clean_Connected_Glass [1.20-26.x].zip"))
        compose.runOnUiThread { vm.openUri(Uri.fromFile(zip)) }
        compose.waitUntil(30_000) { vm.session?.phase == Phase.Ready || vm.session?.phase is Phase.Failed }
        assertEquals(Phase.Ready, vm.session?.phase)
        assertEquals("Clean Connected Glass", vm.session?.name)
        shot("03_convert_ready")

        compose.onNode(hasText("Convert") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(120_000) { vm.session?.phase is Phase.Done || vm.session?.phase is Phase.Failed }
        val done = vm.session?.phase
        assertTrue("conversion failed: $done", done is Phase.Done)
        done as Phase.Done
        println("converted ${done.result.blocks.size} blocks into ${done.result.files.size} files")
        assertEquals(35, done.result.blocks.size)
        shot("04_converted", 800)

        // 4. Import hands a readable content:// .mcpack to LeviLauncher
        compose.onNode(hasText("Import into LeviLauncher") and hasClickAction()).performScrollTo().performClick()
        val sent = shadowOf(compose.activity).nextStartedActivity
        println("import intent: $sent data=${sent.data}")
        assertEquals(Intent.ACTION_VIEW, sent.action)
        assertEquals("org.levimc.launcher", sent.`package`)
        assertTrue(sent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = sent.data!!
        assertEquals("content", uri.scheme)
        assertTrue(uri.path!!.endsWith(".mcpack"))
        val resolver = compose.activity.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { c ->
            c.moveToFirst(); c.getString(0)
        }
        println("display name: $displayName")
        assertTrue(displayName.endsWith(".mcpack"))
        val names = ArrayList<String>()
        ZipInputStream(resolver.openInputStream(uri)!!).use { z -> while (true) names += (z.nextEntry ?: break).name }
        assertTrue("manifest.json" in names && "blocks.json" in names && "textures/terrain_texture.json" in names)

        // 5. Install the bundled mod in LeviLauncher
        compose.runOnUiThread { vm.closeSession() }
        compose.runOnUiThread { vm.tab = Tab.LIBRARY }
        shot("05_library")
        compose.onNode(hasText("Install mod") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { shadowOf(compose.activity).peekNextStartedActivity() != null }
        val mod = shadowOf(compose.activity).nextStartedActivity
        println("mod intent: $mod data=${mod.data}")
        assertEquals("org.levimc.launcher", mod.`package`)
        assertTrue(mod.data!!.path!!.endsWith(".levipack"))

        // 6. The converted pack's page
        compose.onAllNodesWithText("Clean Connected Glass")[0].performClick()
        compose.waitUntil(5_000) { vm.routes.isNotEmpty() }
        shot("06_pack_page", 800)
        back()

        // 7. Settings
        compose.runOnUiThread { vm.tab = Tab.SETTINGS }
        shot("07_settings")

        // 8. Dark theme
        compose.runOnUiThread { vm.settings.updateThemeMode(ThemeMode.DARK) }
        compose.runOnUiThread { vm.tab = Tab.LIBRARY }
        shot("08_library_dark")
        compose.runOnUiThread { vm.tab = Tab.BROWSE }
        shot("09_browse_dark", 1500)

        // 9. The CurseForge tab (Robolectric's WebView is a stub: this only checks nothing crashes)
        compose.runOnUiThread { vm.source = Source.CURSEFORGE }
        shot("10_curseforge_tab")

        // 10. The built-in palette (phones without Material You), dark and light
        compose.runOnUiThread { vm.settings.updateDynamicColor(false); vm.source = Source.MODRINTH; vm.tab = Tab.LIBRARY }
        compose.onAllNodesWithText("Clean Connected Glass")[0].performClick()
        compose.waitUntil(5_000) { vm.routes.isNotEmpty() }
        shot("11_pack_page_fallback_dark", 800)
        compose.runOnUiThread { vm.settings.updateThemeMode(ThemeMode.LIGHT) }
        shot("12_pack_page_fallback_light", 800)
        back()
        compose.runOnUiThread { vm.tab = Tab.BROWSE }
        shot("13_browse_fallback_light", 1500)
    }
}
