package com.alastor.ctmforge

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alastor.ctmforge.core.*
import com.alastor.ctmforge.data.CreatorStore
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CreatorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    @Test fun createEditPersistAndBuildPack() {
        compose.waitUntil(10000) { !vm.creator.busy }
        compose.onNodeWithText("Create", useUnmergedTree = true).performClick()
        compose.onNodeWithText("New pack").performClick()
        compose.waitUntil(10000) { vm.creator.project != null && !vm.creator.busy }
        compose.onNodeWithText("Pack name").performTextReplacement("Null glass")
        compose.waitUntil(10000) { vm.creator.pendingSaves == 0 }
        assertEquals("Null glass", vm.creator.store.read(vm.creator.project!!.id).name)
        val p = vm.creator.project!!
        val texture = CreatorTexture("test.png", 16, 1)
        vm.creator.store.asset(p.id, texture.asset).writeBytes(Png.encode(Img(16, 16, IntArray(256) { 0xff7700aa.toInt() })))
        compose.runOnUiThread { vm.creator.editRule(p.rules.first().id) { it.copy(tiles = listOf(texture)) } }
        compose.waitUntil(10000) { vm.creator.pendingSaves == 0 }
        val restored = CreatorStore(compose.activity).read(p.id)
        assertEquals(vm.creator.project, restored)
        File("build/screens").mkdirs()
        compose.onRoot().captureRoboImage("build/screens/creator-editor.png")
        compose.onNodeWithText("Build .mcpack").performScrollTo().performClick()
        compose.waitUntil(20000) { !vm.creator.busy }
        assertNull(vm.creator.error)
        assertTrue(vm.routes.lastOrNull() is AppViewModel.Route.Pack)
        val entry = vm.library.entries.first { it.source == "Created in CTM Forge" }
        assertTrue(entry.mcpack.isFile)
        assertEquals("Null glass", entry.name)
        compose.onRoot().captureRoboImage("build/screens/creator-export.png")
    }

    @Test fun importsSeparateFramesInNaturalOrderAndKeepsLatestEdits() {
        compose.waitUntil(10000) { !vm.creator.busy }
        compose.runOnUiThread { vm.creator.create() }
        compose.waitUntil(10000) { vm.creator.project != null && !vm.creator.busy }
        val p = vm.creator.project!!
        val dir = File(compose.activity.cacheDir, "creator-import").apply { mkdirs() }
        val first = File(dir, "frame2.png").apply { writeBytes(Png.encode(Img(16, 16, IntArray(256) { 0xff220000.toInt() }))) }
        val second = File(dir, "frame10.png").apply { writeBytes(Png.encode(Img(16, 16, IntArray(256) { 0xffaa0000.toInt() }))) }
        compose.runOnUiThread {
            vm.creator.importImages(listOf(Uri.fromFile(second), Uri.fromFile(first)), p.rules.first().id, 0, "frames")
        }
        compose.waitUntil(10000) { !vm.creator.busy }
        assertNull(vm.creator.error)
        val tile = vm.creator.project!!.rules.first().tiles.first()!!
        assertEquals(2, tile.frames)
        val image = Png.decode(vm.creator.store.asset(p.id, tile.asset).readBytes())
        assertEquals(0xff220000.toInt(), image[0, 0])
        assertEquals(0xffaa0000.toInt(), image[0, 16])
        compose.runOnUiThread {
            repeat(20) { vm.creator.edit(vm.creator.project!!.copy(name = "Draft $it")) }
            vm.creator.editRule(p.rules.first().id) { it.copy(animated = true, order = "1,0", ticks = "3") }
        }
        compose.waitUntil(10000) { vm.creator.pendingSaves == 0 }
        val saved = CreatorStore(compose.activity).read(p.id)
        assertEquals("Draft 19", saved.name)
        assertTrue(saved.rules.first().animated)
        compose.runOnUiThread { vm.creator.close(); vm.creator.open(saved); vm.creator.build() }
        compose.waitUntil(20000) { !vm.creator.busy }
        assertNull(vm.creator.error)
        assertTrue(vm.library.entries.first { it.slug == "created_${p.id}" }.animated.contains("glass"))
    }

    @Test fun animatedDraftRoundTripsAllSettings() {
        val p = CreatorProject(name = "Animated", rules = listOf(CreatorRule(method = CreatorMethod.COMPACT,
            tiles = List(5) { CreatorTexture("$it.png", 32, 3) }, animated = true, ticks = "4", order = "0,1,2,1",
            interpolate = true, innerSeams = true, faces = "sides")))
        val store = CreatorStore(compose.activity)
        store.save(p)
        assertEquals(p, store.read(p.id))
        store.delete(p.id)
    }
}
