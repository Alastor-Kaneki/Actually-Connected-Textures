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
        assertTrue(bases.all { "/fallback/" in it && "$it.png" in result.files })
        // Bedrock prunes variations from lower-priority packs. Every variant must
        // resolve in this pack, so the fallback survives beside the rule sheet.
        assertTrue(variations(result).flatten().all {
            it.jsonObject.getValue("path").jsonPrimitive.content + ".png" in result.files
        })
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

    @Test fun endFireKeepsBothLayersFrameOrderAndAnimatedVanillaFallback() {
        val dir = "assets/minecraft/optifine/ctm/end"
        val strip = Png.encode(Img(16, 512, IntArray(16 * 512) { 0xff0000c8.toInt() or ((it / 256) shl 16) }))
        val files = LinkedHashMap<String, ByteArray>()
        for (layer in 0..1) {
            val name = "fire_$layer"
            files["$dir/$name.png"] = strip
            files["$dir/$name.properties"] = ("matchTiles=$name\nmethod=fixed\ntiles=$name\n" +
                "biomes=the_end end_highlands end_midlands end_barrens small_end_islands").toByteArray()
            val frames = ((16..31) + (0..15)).joinToString(",")
            files["$dir/$name.png.mcmeta"] = (if (layer == 0)
                "{\"animation\":{\"frames\":[$frames]}}" else "{\"animation\":{}}").toByteArray()
        }
        val result = Converter.convert(JavaPack.fromEntries(files), ConvertOptions(name = "End Fire", slug = "end_fire"))
        fun json(path: String) = Json.parseToJsonElement(result.files.getValue(path).decodeToString())
        val faces = json("blocks.json").jsonObject.getValue("fire").jsonObject.getValue("textures").jsonObject
        assertEquals(Bedrock.FACES.toSet(), faces.keys)
        assertNotEquals(faces["up"], faces["down"])
        assertEquals(faces["up"], faces["north"])
        val terrain = json("textures/terrain_texture.json").jsonObject.getValue("texture_data").jsonObject
        val flips = json("textures/flipbook_textures.json").jsonArray.map { it.jsonObject }
        assertEquals(4, flips.size)
        for ((face, layer) in listOf("up" to 0, "down" to 1)) {
            val alias = faces.getValue(face).jsonPrimitive.content
            val variants = terrain.getValue(alias).jsonObject.getValue("textures").jsonObject.getValue("variations").jsonArray
            val fallback = "textures/blocks/ctm/fallback/end_fire/blocks/fire_$layer"
            assertEquals(fallback, variants[0].jsonObject.getValue("path").jsonPrimitive.content)
            assertTrue(variants.all { it.jsonObject.getValue("path").jsonPrimitive.content + ".png" in result.files })
            assertTrue("the_end/end/" in variants[1].jsonObject.getValue("path").jsonPrimitive.content)
            val base = flips.single { it["atlas_tile"]?.jsonPrimitive?.content == alias && it["atlas_tile_variant"]?.jsonPrimitive?.int == 0 }
            assertEquals(fallback + "_animation", base.getValue("flipbook_texture").jsonPrimitive.content)
            assertEquals(if (layer == 0) 16 else 0, base.getValue("frames").jsonArray.first().jsonPrimitive.int)
            assertContentEquals(VanillaImages.png("textures/blocks/fire_$layer"), result.files.getValue(fallback + "_animation.png"))
            val sheet = flips.single { it["atlas_tile"]?.jsonPrimitive?.content == alias && it["atlas_tile_variant"]?.jsonPrimitive?.int == 1 }
            val image = Png.decode(result.files.getValue(sheet.getValue("flipbook_texture").jsonPrimitive.content + ".png"))
            assertEquals(16, image.width)
            assertEquals(512, image.height)
            assertEquals(if (layer == 0) 16 else 0, (image[0, 0] ushr 16) and 255)
            assertEquals((0..31).toList(), sheet.getValue("frames").jsonArray.map { it.jsonPrimitive.int })
        }
        assertFalse("soul_fire" in json("blocks.json").jsonObject)
        assertFalse(result.files.keys.any { it.startsWith("textures/blocks/fire_") || "soul_fire" in it })
    }

    @Test fun suppliedStaticBaseIsCopiedWithoutVanillaAnimation() {
        val dir = "assets/minecraft/optifine/ctm/end"
        val normal = Png.encode(Img(16, 16, IntArray(256) { 0xffff7800.toInt() }))
        val pack = JavaPack.fromEntries(mapOf(
            "$dir/0.png" to png,
            "$dir/a.properties" to "matchTiles=fire_0\nmethod=fixed\ntiles=0\nbiomes=the_end".toByteArray(),
            "assets/minecraft/textures/block/fire_0.png" to normal,
        ))
        val result = Converter.convert(pack, ConvertOptions())
        val base = variations(result).single()[0].jsonObject.getValue("path").jsonPrimitive.content
        assertEquals(0xffff7800.toInt(), Png.decode(result.files.getValue("$base.png"))[0, 0])
        assertFalse("textures/flipbook_textures.json" in result.files)
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
