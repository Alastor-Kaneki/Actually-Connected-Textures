package com.alastor.ctmforge.core

import kotlinx.serialization.json.*
import kotlin.test.*

class ConditionsTest {
    private val tile = CreatorTexture("a.png", 16, 2)
    private val rule = CreatorRule(tiles = listOf(tile))
    private val png = Png.encode(Anim.stack(listOf(
        Img(16, 16, IntArray(256) { 0xffff0000.toInt() }), Img(16, 16, IntArray(256) { 0xff0000ff.toInt() }),
    )))
    private fun build(vararg rules: CreatorRule) = PackCreator.build(
        CreatorProject(id = "conditions", rules = rules.toList()), 1,
    ) { png }
    private fun variations(result: ConversionResult) = Json.parseToJsonElement(
        result.files.getValue("textures/terrain_texture.json").decodeToString(),
    ).jsonObject.getValue("texture_data").jsonObject.values.map {
        it.jsonObject.getValue("textures").jsonObject.getValue("variations").jsonArray
    }

    @Test fun keepsConditionalRulesAndUnconditionalFallbackOnTheSameFace() {
        val result = build(rule, rule.copy(biomes = "desert"), rule.copy(neighborBlocks = "sand"),
            rule.copy(biomes = "plains", neighborBlocks = "stone", connectBlocks = "glass tinted_glass"))
        assertEquals(4, result.methods.getValue("fixed"))
        val variants = variations(result).single()
        assertEquals(5, variants.size) // one base + four rule sheets
        assertTrue(variants[0].jsonObject.getValue("path").jsonPrimitive.content.contains("/base/"))
        assertEquals(3, variants.count { "/when/" in it.jsonObject.getValue("path").jsonPrimitive.content })
        assertFalse(result.log.any { "already covered" in it || "biomes' is ignored" in it })
    }

    @Test fun conditionalOnlyRulesKeepEachFacesVanillaFallback() {
        val result = build(rule.copy(block = "grass_block", biomes = "desert"))
        val bases = variations(result).map { it[0].jsonObject.getValue("path").jsonPrimitive.content }.toSet()
        assertTrue(bases.size > 1, "grass sides, top and bottom must retain their own base")
        assertFalse(bases.any { "/ctm/" in it })
    }

    @Test fun animatedSheetsAndFlipbookStripsCarryTheSameConditions() {
        val result = build(rule.copy(animated = true, ticks = "3", biomes = "!desert", neighborBlocks = "sand"))
        assertTrue(result.sheets.all { "/when/x-desert/n-sand/end/" in it.sheetPath })
        val flips = Json.parseToJsonElement(result.files.getValue("textures/flipbook_textures.json").decodeToString()).jsonArray
        assertTrue(flips.isNotEmpty())
        for (f in flips) {
            val path = f.jsonObject.getValue("flipbook_texture").jsonPrimitive.content
            assertTrue("/when/x-desert/n-sand/end/anim/" in path)
            assertTrue(result.files.containsKey("$path.png"))
            assertEquals(3, f.jsonObject.getValue("ticks_per_frame").jsonPrimitive.int)
        }
    }

    @Test fun creatorDoesNotReinterpretCanonicalSnowAsJavaSnowLayers() {
        val result = build(rule.copy(neighborBlocks = "snow_block"))
        assertTrue(result.sheets.all { "/n-snow.snow_block/" in it.sheetPath })
        assertTrue(result.sheets.none { "snow_layer" in it.sheetPath })
    }

    @Test fun validatesNamesAndCanonicalizesBlockAndBiomeAliases() {
        val c = RuleConditions.parse("minecraft:snowy_plains, desert", "grass_block minecraft:glass", "sand")
        assertEquals(listOf("desert", "ice_plains", "snowy_plains"), c.biomes)
        assertEquals(listOf("glass", "grass", "grass_block"), c.connectBlocks)
        assertFalse(RuleConditions.parse(connectBlocks = "glass").conditional)
        assertEquals(listOf("sea_lantern", "sealantern"), RuleConditions.parse(connectBlocks = "sea_lantern").connectBlocks)
        assertEquals(listOf("snow_layer"), RuleConditions.parse(connectBlocks = "snow").connectBlocks)
        val again = RuleConditions.parse(connectBlocks = RuleConditions.parse(connectBlocks = "sea_lantern").connectBlocks.joinToString(" "))
        assertEquals(listOf("sea_lantern", "sealantern"), again.connectBlocks)
        for (invalid in listOf("!", "desert !plains", "mod:desert", "../desert", "1"))
            assertFailsWith<IllegalArgumentException> { RuleConditions.parse(biomes = invalid) }
        for (invalid in listOf("not_a_block", "oak_log:axis=x", "stone[foo=bar]"))
            assertFailsWith<IllegalArgumentException> { RuleConditions.parse(connectBlocks = invalid) }
        assertFailsWith<IllegalArgumentException> { build(rule.copy(biomes = "desert"), rule.copy(biomes = "desert")) }
    }

    @Test fun importedOptifineConditionsArePreservedAndDirectModeRejectsThem() {
        val dir = "assets/minecraft/optifine/ctm/conditional"
        fun pack(extra: String) = JavaPack.fromEntries(mapOf(
            "$dir/0.png" to png,
            "$dir/a.properties" to "matchBlocks=glass\nmethod=fixed\ntiles=0\n$extra".toByteArray(),
        ))
        val result = Converter.convert(pack("biomes=desert\nconnectBlocks=glass tinted_glass"), ConvertOptions())
        assertTrue(result.sheets.single().sheetPath.contains("/when/b-desert/c-glass.tinted_glass/end/"))
        val direct = assertFailsWith<ConversionException> { Converter.convert(pack("biomes=desert"), ConvertOptions(direct = true)) }
        assertTrue(direct.log.any { "require standard mode" in it })
        val invalid = assertFailsWith<ConversionException> { Converter.convert(pack("connectBlocks=not_a_block"), ConvertOptions()) }
        assertTrue(invalid.log.any { "Unknown connection block" in it })
    }
}
