package com.alastor.ctmforge.core

import kotlinx.serialization.json.*
import kotlin.test.*

class CopperGrateTest {
    private val stages = listOf("copper_grate", "exposed_copper_grate", "weathered_copper_grate", "oxidized_copper_grate")
    private val all = (stages + stages.map { "waxed_$it" }).toSet()
    private val dir = "assets/minecraft/optifine/ctm"
    private fun image(color: Int = 0xffb66649.toInt(), width: Int = 16) = Png.encode(Img(width, 16,
        IntArray(width * 16) { i -> if (i % width % 16 in 5..10 && i / width in 5..10) 0 else color }))
    private fun convert(files: Map<String, ByteArray>) = Converter.convert(JavaPack.fromEntries(files), ConvertOptions(name = "Grates", slug = "grates"))

    @Test fun filenameInferenceTargetsAllEightStatesAndPreservesTransparentHoles() {
        val files = mutableMapOf("$dir/0.png" to image())
        stages.forEach { files["$dir/$it.properties"] = "method=ctm_compact\ntiles=0 0 0 0 0".toByteArray() }
        val result = convert(files)
        assertEquals(all, result.blocks.keys)
        assertEquals(8, result.methods["ctm_compact"])
        assertTrue(result.log.any { "inferred matchTiles=copper_grate" in it })
        assertEquals(8, result.sheets.size)
        for (sheet in result.sheets) {
            val im = Png.decode(result.files.getValue(sheet.sheetPath + ".png"))
            assertEquals(0, im[7, 7] ushr 24)
            assertEquals(255, im[0, 0] ushr 24)
        }
        assertTrue(all.all { it in PackCreator.blocks })
    }

    @Test fun waxedTargetsUseTheSourcesUnwaxedFallbackAndExplicitTargetsOverrideNames() {
        for (target in all) {
            val result = convert(mapOf(
                "$dir/block_$target.properties" to "method=fixed\ntiles=0\nbiomes=desert".toByteArray(),
                "$dir/0.png" to image(),
                "assets/minecraft/textures/block/${target.removePrefix("waxed_")}.png" to image(0xff010203.toInt()),
            ))
            assertEquals(setOf(target), result.blocks.keys)
            val terrain = Json.parseToJsonElement(result.files.getValue("textures/terrain_texture.json").decodeToString()).jsonObject.getValue("texture_data").jsonObject
            for (entry in terrain.values) {
                val base = entry.jsonObject.getValue("textures").jsonObject.getValue("variations").jsonArray[0].jsonObject.getValue("path").jsonPrimitive.content
                val im = Png.decode(result.files.getValue("$base.png"))
                assertEquals(0xff010203.toInt(), im[0, 0])
                assertEquals(0, im[7, 7] ushr 24)
            }
        }
        val result = convert(mapOf("$dir/copper_grate.properties" to "matchBlocks=glass\nmethod=fixed\ntiles=0".toByteArray(), "$dir/0.png" to image()))
        assertEquals(setOf("glass"), result.blocks.keys)
        assertFailsWith<ConversionException> {
            convert(mapOf("$dir/copper_grate.properties" to "matchBlocks=\nmethod=fixed\ntiles=0".toByteArray(), "$dir/0.png" to image()))
        }
    }

    @Test fun fusionHorizontalGratesConvertAllStatesWithoutFlatteningAlpha() {
        val files = mutableMapOf<String, ByteArray>()
        for (name in stages) {
            val path = "assets/minecraft/textures/block/$name.png"
            files[path] = image(width = 64)
            files["$path.mcmeta"] = """{"fusion":{"type":"connecting","layout":"horizontal"}}""".toByteArray()
        }
        val result = convert(files)
        assertEquals(all, result.blocks.keys)
        assertEquals(8, result.sheets.size)
        assertTrue(result.sheets.all { "/horizontal-4x1-4/" in it.sheetPath })
        for (sheet in result.sheets)
            assertEquals(0, Png.decode(result.files.getValue(sheet.sheetPath + ".png"))[7, 7] ushr 24)
    }
}
