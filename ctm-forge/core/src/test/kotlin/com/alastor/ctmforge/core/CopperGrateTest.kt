package com.alastor.ctmforge.core

import kotlinx.serialization.json.*
import kotlin.test.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CopperGrateTest {
    private val stages = listOf("copper_grate", "exposed_copper_grate", "weathered_copper_grate", "oxidized_copper_grate")
    private val all = (stages + stages.map { "waxed_$it" }).toSet()
    private val dir = "assets/minecraft/optifine/ctm"
    private fun image(color: Int = 0xffb66649.toInt(), width: Int = 16) = Png.encode(Img(width, 16,
        IntArray(width * 16) { i -> if (i % width % 16 in 5..10 && i / width in 5..10) 0 else color }))
    private fun convert(files: Map<String, ByteArray>) = Converter.convert(JavaPack.fromEntries(files), ConvertOptions(name = "Grates", slug = "grates"))

    @Test fun uploadedZipWithUppercaseTile45ConvertsAllEightStates() {
        val entries = mutableMapOf<String, ByteArray>()
        for ((index, block) in stages.withIndex()) {
            val d = "$dir/copper_grate/${index + 1}"
            entries["$d/copper_grate.properties"] = "method=ctm\ntiles=0-46\nmatchBlocks=waxed_$block $block".toByteArray()
            for (tile in 0..46) {
                val ext = if (index == 0 && tile == 45) "PNG" else "png"
                entries["$d/$tile.$ext"] = image(0xff000003.toInt() or (tile shl 16) or ((index + 1) shl 8))
            }
        }
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { z ->
            for ((name, content) in entries) { z.putNextEntry(ZipEntry(name)); z.write(content); z.closeEntry() }
        } }.toByteArray()
        val pack = JavaPack.candidates(bytes, "Grates").single().pack
        assertEquals(4, pack.ctmRuleCount)
        val result = Converter.convert(pack, ConvertOptions())
        assertEquals(all, result.blocks.keys)
        assertTrue(result.log.isEmpty())
        for (sheet in result.sheets) {
            val im = Png.decode(result.files.getValue(sheet.sheetPath + ".png"))
            assertEquals(45, (im[5 * 16, 5 * 16] ushr 16) and 255)
            assertEquals(0, im[5 * 16 + 7, 5 * 16 + 7] ushr 24)
        }
    }

    @Test fun exactSpellingWinsAndAmbiguousOrMissingTilesAreNamed() {
        val props = "matchBlocks=copper_grate\nmethod=fixed\ntiles=45"
        val files = mutableMapOf("$dir/a.properties" to props.toByteArray(),
            "$dir/45.PNG" to image(0xff010000.toInt()), "$dir/45.PnG" to image(0xff020000.toInt()))
        val error = assertFailsWith<ConversionException> { convert(files) }
        assertTrue(error.log.any { "45.png" in it && "ambiguous" in it })
        files["$dir/45.png"] = image(0xff030000.toInt())
        fun color() = convert(files).let { Png.decode(it.files.getValue(it.sheets.first().sheetPath + ".png"))[0, 0] }
        assertEquals(0xff030000.toInt(), color())
        files["$dir/a.properties"] = props.replace("tiles=45", "tiles=45.PNG").toByteArray()
        assertEquals(0xff010000.toInt(), color())
        listOf("45.png", "45.PNG", "45.PnG").forEach { files.remove("$dir/$it") }
        assertTrue(assertFailsWith<ConversionException> { convert(files) }.log.any { "45.PNG" in it })
    }

    @Test fun uppercaseTileStillLoadsItsAnimationMetadata() {
        val result = convert(mapOf(
            "$dir/45.PNG" to Png.encode(Img(16, 32, IntArray(512) { 0xff123456.toInt() })),
            "$dir/45.png.MCMETA" to """{"animation":{"frametime":3}}""".toByteArray(),
            "$dir/a.properties" to "matchBlocks=copper_grate\nmethod=fixed\ntiles=45".toByteArray(),
        ))
        val flips = Json.parseToJsonElement(result.files.getValue("textures/flipbook_textures.json").decodeToString()).jsonArray
        assertTrue(flips.any { it.jsonObject.getValue("ticks_per_frame").jsonPrimitive.int == 3 && it.jsonObject.getValue("frames").jsonArray.size == 2 })
    }

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
