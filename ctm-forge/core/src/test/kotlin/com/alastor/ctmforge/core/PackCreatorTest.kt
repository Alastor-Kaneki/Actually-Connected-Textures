package com.alastor.ctmforge.core

import kotlinx.serialization.json.*
import kotlin.test.*

class PackCreatorTest {
    private fun strip(frames: Int, size: Int = 16) = Png.encode(Anim.stack(List(frames) { f ->
        Img(size, size, IntArray(size * size) { 0xff000000.toInt() or ((f + 1) shl 16) })
    }))
    private fun rule(method: CreatorMethod = CreatorMethod.FIXED, frames: Int = 1) = CreatorRule(
        method = method, tiles = List(method.tileCount) { CreatorTexture("$it.png", 16, frames) },
        animated = frames > 1,
    )
    private fun build(r: CreatorRule, bytes: ByteArray = strip(r.tiles.first()!!.frames)) =
        PackCreator.build(CreatorProject(id = "test-project", rules = listOf(r)), 1) { bytes }

    @Test fun everyMethodExportsACompletePack() {
        for (m in CreatorMethod.entries) {
            val result = build(rule(m))
            assertEquals(setOf("glass"), result.blocks.keys)
            assertEquals(m.tileCount, if (m == CreatorMethod.COMPACT) 5 else Ctm.parseSpec("textures/blocks/ctm/${result.blocks.getValue("glass")}/x")!!.count)
            assertTrue(result.files.containsKey("blocks.json"))
            assertFalse(result.files.containsKey("textures/flipbook_textures.json"))
            assertEquals(result.files.keys, JavaPack.readZip(result.mcpackBytes().inputStream()).keys)
        }
    }
    @Test fun animationTimingAndPixelsSurviveExport() {
        val result = build(rule(frames = 3).copy(order = "2,0,1,0", ticks = "3", interpolate = true))
        val flip = Json.parseToJsonElement(result.files.getValue("textures/flipbook_textures.json").decodeToString()).jsonArray
            .map { it.jsonObject }.first { "/anim/" in it.getValue("flipbook_texture").jsonPrimitive.content }
        assertEquals(3, flip.getValue("ticks_per_frame").jsonPrimitive.int)
        assertTrue(flip.getValue("blend_frames").jsonPrimitive.boolean)
        val image = Png.decode(result.files.getValue(flip.getValue("flipbook_texture").jsonPrimitive.content + ".png"))
        val colors = flip.getValue("frames").jsonArray.map { image[0, it.jsonPrimitive.int * image.width] }
        assertEquals(listOf(0xff030000.toInt(), 0xff010000.toInt(), 0xff020000.toInt(), 0xff010000.toInt()), colors)
        assertTrue("glass" in result.animated)
    }
    @Test fun compactAnimationUsesSquareSheetsAndStaticTilesCanMix() {
        val r = rule(CreatorMethod.COMPACT, 2)
        val result = PackCreator.build(CreatorProject(rules = listOf(r)), 1) { name -> strip(if (name == "0.png") 1 else 2) }
        assertEquals("ctm-7x7-47", result.blocks["glass"])
        assertTrue(result.animated.isNotEmpty())
    }
    @Test fun disablingAnimationExportsOnlyTheFirstFrame() {
        val result = build(rule(frames = 3).copy(animated = false))
        assertTrue(result.animated.isEmpty())
        assertFalse(result.files.containsKey("textures/flipbook_textures.json"))
        val sheet = Png.decode(result.files.getValue(result.sheets.first().sheetPath + ".png"))
        assertEquals(0xff010000.toInt(), sheet[0, 0])
    }
    @Test fun rejectsIncompleteAndInvalidInputs() {
        assertFailsWith<IllegalArgumentException> { build(rule().copy(tiles = listOf(null)), strip(1)) }
        assertFailsWith<IllegalArgumentException> { build(rule().copy(block = "not_a_block")) }
        assertFailsWith<IllegalArgumentException> { build(rule(frames = 2).copy(order = "0,9")) }
        assertFailsWith<IllegalArgumentException> { build(rule(frames = 2).copy(ticks = "0")) }
        assertFailsWith<IllegalArgumentException> { build(rule().copy(animated = true)) }
        assertFailsWith<IllegalArgumentException> { PackCreator.decodeTexture(Png.encode(Img(16, 17))) }
        assertFailsWith<IllegalArgumentException> { build(rule(CreatorMethod.CTM, 64), strip(64, 256)) }
    }
    @Test fun overlappingRulesAreRejectedButDifferentFacesWork() {
        val r = rule()
        assertFailsWith<IllegalArgumentException> { PackCreator.build(CreatorProject(rules = listOf(r, r.copy())), 1) { strip(1) } }
        val result = PackCreator.build(CreatorProject(rules = listOf(r.copy(faces = "top"), r.copy(faces = "sides"))), 1) { strip(1) }
        assertEquals(2, result.sheets.size)
    }
    @Test fun rebuildKeepsIdentityAndDistinctDraftsDoNotCollide() {
        val p = CreatorProject(rules = listOf(rule()))
        val a = PackCreator.build(p, 1) { strip(1) }
        val b = PackCreator.build(p.copy(name = "Renamed"), 2) { strip(1) }
        val c = PackCreator.build(p.copy(id = "other-project"), 1) { strip(1) }
        assertEquals(a.headerUuid, b.headerUuid)
        assertNotEquals(a.headerUuid, c.headerUuid)
        assertEquals(listOf(1, 0, 2), b.version)
        val manifest = Json.parseToJsonElement(b.files.getValue("manifest.json").decodeToString()).jsonObject
        assertEquals("Renamed", manifest.getValue("header").jsonObject.getValue("name").jsonPrimitive.content)
    }
}
