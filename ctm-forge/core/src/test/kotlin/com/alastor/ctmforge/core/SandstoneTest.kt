package com.alastor.ctmforge.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SandstoneTest {
    private fun convert(targets: List<String>, direct: Boolean = false): JsonObject {
        val source = linkedMapOf<String, ByteArray>()
        for (block in targets) {
            val dir = "assets/minecraft/optifine/ctm/$block"
            source["$dir/rule.properties"] = "matchBlocks=$block\nmethod=fixed\ntiles=0\nfaces=sides\n".toByteArray()
            source["$dir/0.png"] = Png.encode(Img(16, 16, IntArray(256) { 0xff7d3c14.toInt() }))
        }
        val result = Converter.convert(JavaPack.fromEntries(source), ConvertOptions(name = "Sandstone", direct = direct))
        assertEquals(targets.toSet(), result.blocks.keys)
        return Json.parseToJsonElement(result.files.getValue("blocks.json").decodeToString()).jsonObject
    }

    @Test
    fun baseRulesKeepDistinctVariantsAndInventoryTextures() {
        for (direct in listOf(false, true)) for (explicit in listOf(false, true)) {
            val targets = listOf("sandstone", "red_sandstone") + if (explicit) listOf(
                "chiseled_sandstone", "cut_sandstone", "smooth_sandstone",
                "chiseled_red_sandstone", "cut_red_sandstone",
            ) else emptyList()
            val blocks = convert(targets, direct)
            val order = blocks.keys.toList()
            for (base in listOf("sandstone", "red_sandstone")) {
                for (block in listOf(base, "chiseled_$base", "cut_$base", "smooth_$base")) {
                    assertTrue(block in blocks, block)
                    val entry = blocks.getValue(block).jsonObject
                    val vanilla = Vanilla.blocks.getValue(block).jsonObject
                    assertEquals(vanilla["textures"], entry["carried_textures"], block)
                    val faces = Bedrock.faceAliases(entry)
                    val normal = Bedrock.faceAliases(vanilla)
                    if (block in targets) {
                        assertTrue(faces.getValue("north").startsWith("ctm_"), block)
                        assertTrue(faces.getValue("north").endsWith("_$block"), block)
                        for (face in listOf("up", "down")) assertEquals(normal[face], faces[face])
                    } else assertEquals(normal, faces, block)
                    if (block != base) assertTrue(order.indexOf(base) < order.indexOf(block), block)
                }
            }
        }
    }

    @Test
    fun unrelatedAndVariantOnlyRulesDoNotOverrideSiblings() {
        for (target in listOf("glass", "cut_sandstone", "chiseled_red_sandstone")) {
            assertEquals(setOf("format_version", target), convert(listOf(target)).keys)
        }
    }
}
