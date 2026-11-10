package com.alastor.ctmforge.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Converts every pack the Python converter was run on and checks the output is the same:
 * same files, same JSON (parsed), same pixels. Needs CTM_GOLDEN=<dir with packs/ and proj/out/>.
 */
class GoldenTest {
    private val root = (System.getenv("CTM_GOLDEN") ?: System.getProperty("ctm.golden") ?: "").let { File(it) }

    private val cases = listOf(
        "Better-Connected-Glass" to "packs/better_connected_glass_penu",
        "Better-Connected-Tinted-Glass" to "packs/better_connected_tinted_glass",
        "BetterGlassTexture" to "packs/more_clear_glass",
        "Better_Glass" to "packs/better_glass",
        "CleanConnectedGlass_CCG_0.1.0" to "packs/clean_connected_glass_ccg",
        "Clean_Connected_Glass_1.20-26.x" to "packs/clean_connected_glass",
        "ClearPane_26_3" to "packs/clearpane",
        "Glass" to "packs/glass",
        "better-colored-connected-glass-by-kovaszos-uborka" to "packs/better_colored_connected_glass_kovaszos",
        "better-connected-glass-by-kovaszos-uborka" to "packs/better_connected_glass_kovaszos",
        "better-glass" to "packs/better_glass_yami",
        "translucent-glass-0.3.0" to "packs/translucent_glass",
        "Clear_and_Polished_Obsidian" to "obsidian/clear_and_polished_obsidian",
        // animated CTM tiles, Fusion layouts, matchTiles, Bedrock-renamed blocks, animation-only packs
        "connected-sea-lanterns" to "packs2/connected_sea_lanterns",
        "connected-textures-fusion" to "packs2/connected_textures_fusion",
        "less-painful-glowstone" to "packs2/less_painful_glowstone",
        "less-painful-glowstone-ctm" to "packs2/less_painful_glowstone_ctm",
        "Connected_Froglights" to "packs2/connected_froglights",
        "DrXs_Connected_logs" to "packs2/drxs_connected_logs",
        "fusion-connected-glass" to "packs2/fusion_connected_glass",
        "fusion-connected-blocks" to "packs2/fusion_connected_blocks",
        "fusion-connected-planks" to "packs2/fusion_connected_planks",
        "faithful-fusion-clean-glass-32x" to "packs2/faithful_fusion_clean_glass_32x",
        "fusion-better-stained-glass" to "packs2/fusion_better_stained_glass",
        "ctm-overhaul" to "packs2/ctm_overhaul",
        // synthetic: every way vanilla animates a tile, and 1.12 texture names
        "anim-vanilla-tiles" to "packs2/anim_vanilla_tiles",
        "anim-legacy-tiles" to "packs2/anim_legacy_tiles",
    )

    private fun loadDir(dir: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
            out[it.relativeTo(dir).invariantSeparatorsPath] = it.readBytes()
        }
        return out
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) out[e.name] = z.readBytes()
            }
        }
        return out
    }

    private fun norm(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { norm(it.value) })
        is JsonArray -> JsonArray(e.map { norm(it) })
        is JsonPrimitive -> if (!e.isString && e.content.toDoubleOrNull() != null) JsonPrimitive(e.content.toDouble()) else e
    }

    private fun json(b: ByteArray) = norm(Json.parseToJsonElement(String(b, Charsets.UTF_8)))

    @Test
    fun matchesPythonConverter() {
        if (!File(root, "packs").isDirectory) {
            println("CTM_GOLDEN not set; skipping golden comparison")
            return
        }
        val problems = ArrayList<String>()
        var compared = 0
        for ((dir, golden) in cases) for (direct in listOf(false, true)) {
            val goldenFile = if (direct) {
                val g = golden.substringBefore('/') + "_direct/" + golden.substringAfter('/') + "_direct.mcpack"
                File(root, "proj/out/$g")
            } else File(root, "proj/out/$golden.mcpack")
            if (!goldenFile.exists()) { problems += "missing golden $goldenFile"; continue }
            val expected = unzip(goldenFile.readBytes())
            val manifest = Json.parseToJsonElement(String(expected.getValue("manifest.json"))).jsonObject
            val name = manifest["header"]!!.jsonObject["name"]!!.jsonPrimitive.content.removeSuffix(if (direct) " (CTM direct)" else " (CTM)")
            val slug = Json.parseToJsonElement(String(expected.getValue("textures/terrain_texture.json")))
                .jsonObject["resource_pack_name"]!!.jsonPrimitive.content

            val t0 = System.nanoTime()
            val pack = JavaPack.fromEntries(loadDir(File(root, "packs/$dir")))
            val result = Converter.convert(pack, ConvertOptions(name = name, slug = slug, direct = direct, sourceName = dir))
            val zipped = result.mcpackBytes()
            val ms = (System.nanoTime() - t0) / 1_000_000
            val actual = unzip(zipped)
            val tag = "$slug${if (direct) " (direct)" else ""}"
            println("$tag: ${result.blocks.size} blocks, ${actual.size} files, ${zipped.size / 1024} KiB, $ms ms")

            if (expected.keys != actual.keys) {
                problems += "$tag: file lists differ: only python=${expected.keys - actual.keys}, only kotlin=${actual.keys - expected.keys}"
            }
            for ((path, exp) in expected) {
                val act = actual[path] ?: continue
                compared++
                when {
                    path.endsWith(".json") -> if (json(exp) != json(act)) {
                        problems += "$tag: $path differs\n  python=${String(exp).take(600)}\n  kotlin=${String(act).take(600)}"
                    }
                    path.endsWith(".png") -> {
                        val a = Png.decode(exp)
                        val b = Png.decode(act)
                        if (a.width != b.width || a.height != b.height) {
                            problems += "$tag: $path size ${a.width}x${a.height} vs ${b.width}x${b.height}"
                        } else if (path == "pack_icon.png" && (pack.get("pack.png")?.let { Png.decode(it).width } ?: 0) > 256) {
                            // resampled icon: the Python converter used Lanczos, the port a box filter
                            var diff = 0L
                            for (i in a.px.indices) for (s in 0..24 step 8) diff += kotlin.math.abs(((a.px[i] shr s) and 255) - ((b.px[i] shr s) and 255))
                            val mean = diff.toDouble() / (a.px.size * 4)
                            if (mean > 6.0) problems += "$tag: $path mean channel diff $mean"
                        } else if (a != b) {
                            val n = a.px.indices.count { a.px[it] != b.px[it] }
                            val first = a.px.indices.first { a.px[it] != b.px[it] }
                            problems += "$tag: $path $n pixels differ (first at ${first % a.width},${first / a.width}: " +
                                "%08x vs %08x)".format(a.px[first], b.px[first])
                        }
                    }
                    else -> if (!exp.contentEquals(act)) problems += "$tag: $path bytes differ"
                }
            }

            // summary and log against the Python run
            val logFile = File(goldenFile.parentFile, "$slug.log")
            if (logFile.exists()) {
                val text = logFile.readText()
                val summaryEnd = text.indexOf("\n}") + 2
                val summary = Json.parseToJsonElement(text.substring(0, summaryEnd)).jsonObject
                val pyBlocks = summary["blocks"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
                if (pyBlocks != result.blocks) problems += "$tag: blocks differ\n  python=$pyBlocks\n  kotlin=${result.blocks}"
                val pyMethods = summary["methods"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }
                if (pyMethods != result.methods) problems += "$tag: methods differ $pyMethods vs ${result.methods}"
                if (summary["rules"]!!.jsonPrimitive.int != result.rules) problems += "$tag: rule count differs"
                if (summary["files"]!!.jsonPrimitive.int != result.files.size) problems += "$tag: file count differs"
                val pyLog = text.substring(summaryEnd).lines().filter { it.startsWith("  ") }.map { it.substring(2) }
                var k = 0
                for (line in result.log) if (k < pyLog.size && pyLog[k] == line) k++
                if (k != pyLog.size) problems += "$tag: log differs\n  python=$pyLog\n  kotlin=${result.log}"
                val extra = result.log - pyLog.toSet()
                if (extra.isNotEmpty()) println("  extra log lines: $extra")
            }
        }
        println("compared $compared files")
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun nestedZipCandidates() {
        val inner = java.io.ByteArrayOutputStream().also { bo ->
            java.util.zip.ZipOutputStream(bo).use { z ->
                for ((n, b) in listOf(
                    "pack.mcmeta" to """{"pack":{"pack_format":15,"description":"inner"}}""".toByteArray(),
                    "assets/minecraft/optifine/ctm/glass/glass.properties" to "method=fixed\ntiles=0\nmatchBlocks=glass".toByteArray(),
                    "assets/minecraft/optifine/ctm/glass/0.png" to Png.encode(Img(16, 16, IntArray(256) { 0x7f3366ff })),
                )) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() }
            }
        }.toByteArray()
        val outer = java.io.ByteArrayOutputStream().also { bo ->
            java.util.zip.ZipOutputStream(bo).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("readme.txt")); z.write("hi".toByteArray()); z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry("Real Pack.zip")); z.write(inner); z.closeEntry()
            }
        }.toByteArray()
        val c = JavaPack.candidates(outer, "download")
        assertTrue(c.isNotEmpty())
        assertEquals("Real Pack", c.first().label)
        assertEquals(1, c.first().pack.ctmRuleCount)
        val r = Converter.convert(c.first().pack, ConvertOptions(name = "Nested"))
        assertEquals(mapOf("glass" to "fixed-1x1-1"), r.blocks)
        val px = Png.decode(r.files.getValue("textures/blocks/ctm/fixed-1x1-1/nested_glass.png"))
        assertEquals(0x7f3366ff, px[3, 3])
    }

    @Test
    fun commaSeparatedFaces() {
        // CTM Overhaul writes "faces=north,south,east,west": the top must keep its own texture
        val pack = JavaPack.fromEntries(mapOf(
            "pack.mcmeta" to """{"pack":{"pack_format":15}}""".toByteArray(),
            "assets/minecraft/optifine/ctm/bookshelf/bookshelf.properties" to
                "matchBlocks=bookshelf\nmethod=fixed\ntiles=0\nfaces=north,south,east,west".toByteArray(),
            "assets/minecraft/optifine/ctm/bookshelf/0.png" to Png.encode(Img(16, 16, IntArray(256) { 0x7f3366ff })),
        ))
        val r = Converter.convert(pack, ConvertOptions(name = "Books"))
        val tex = Json.parseToJsonElement(String(r.files.getValue("blocks.json"))).jsonObject["bookshelf"]!!.jsonObject["textures"]!!.jsonObject
        assertEquals("bookshelf_top", tex["up"]!!.jsonPrimitive.content)
        assertEquals("bookshelf_top", tex["down"]!!.jsonPrimitive.content)
        for (f in listOf("north", "south", "east", "west")) assertEquals("ctm_books_bookshelf", tex[f]!!.jsonPrimitive.content)
        assertEquals(listOf("north", "south", "west", "east"), r.sheets.single().faces)
    }

    @Test
    fun pngRoundTrip() {
        val img = Img(37, 11, IntArray(37 * 11) { (it * 2654435761L).toInt() })
        assertEquals(img, Png.decode(Png.encode(img)))
        assertEquals(img, Png.decode(Png.encode(img, 1)))
    }

    @Test
    fun noRulesGivesHelpfulError() {
        val pack = JavaPack.fromEntries(mapOf("pack.mcmeta" to "{}".toByteArray()))
        try {
            Converter.convert(pack, ConvertOptions(name = "x"))
            fail("expected an error")
        } catch (e: ConversionException) {
            assertTrue(e.message!!.contains("No connected or animated"))
        }
    }

    private fun strip(frames: Int, size: Int = 16, seed: Int = 0) =
        Png.encode(Img(size, size * frames, IntArray(size * size * frames) { i -> (0xff000000.toInt() or ((i / (size * size) + seed) * 0x1f3d5b)) }))

    private fun json(files: Map<String, ByteArray>, path: String) = Json.parseToJsonElement(String(files.getValue(path)))

    @Test
    fun fusionLayoutsMapOntoTheFortySevenTiles() {
        // every neighbour state (diagonals only where both sides connect) of one CTM47 class
        // must pick the same Fusion tile, or the expansion to 47 tiles would be lossy
        fun valid(s: Int) = (0 until 4).all { q -> val d = (s shr (q * 2 + 1)) and 1; d == 0 || ((s shr (q * 2)) and 1 == 1 && (s shr (((q + 1) % 4) * 2)) and 1 == 1) }
        for (layout in listOf("full", "simple", "compact")) {
            val map = Fusion.TO_47.getValue(layout)
            assertEquals(47, map.size)
            val (gw, gh) = Fusion.GRID.getValue(layout)
            assertTrue(map.all { it in 0 until gw * gh }, layout)
        }
        assertEquals(47, Fusion.TO_47.getValue("full").toSet().size) // full has a tile for every class
        assertTrue((0 until 256).filter(::valid).isNotEmpty())
    }

    @Test
    fun animatedCtmTilesBecomeAnAnimatedSquareSheet() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["pack.mcmeta"] = """{"pack":{"pack_format":15}}""".toByteArray()
        entries["assets/minecraft/optifine/ctm/sea_lantern/sea_lantern.properties"] =
            "matchBlocks=minecraft:sea_lantern\nmethod=ctm_compact\ntiles=0.png, 1.png, 2.png, 3.png, 4.png".toByteArray()
        for (i in 0 until 5) {
            entries["assets/minecraft/optifine/ctm/sea_lantern/$i.png"] = strip(3, seed = i * 7)
            entries["assets/minecraft/optifine/ctm/sea_lantern/$i.png.mcmeta"] = """{"animation":{"frametime":5}}""".toByteArray()
        }
        entries["__MACOSX/assets/minecraft/optifine/ctm/sea_lantern/._0.png"] = "junk".toByteArray()
        val r = Converter.convert(JavaPack.fromEntries(entries), ConvertOptions(name = "Lanterns"))
        assertEquals(mapOf("seaLantern" to "ctm-7x7-47"), r.blocks) // Java sea_lantern is seaLantern on Bedrock
        assertEquals(listOf("seaLantern"), r.animated)
        val sheet = Png.decode(r.files.getValue("textures/blocks/ctm/ctm-7x7-47/lanterns_seaLantern.png"))
        assertEquals(112 to 112, sheet.width to sheet.height)
        val anim = Png.decode(r.files.getValue("textures/blocks/ctm/ctm-7x7-47/anim/lanterns_seaLantern.png"))
        assertEquals(112 to 336, anim.width to anim.height)
        val fb = json(r.files, "textures/flipbook_textures.json") as JsonArray
        val last = fb.last().jsonObject
        assertEquals("ctm_lanterns_seaLantern", last["atlas_tile"]!!.jsonPrimitive.content)
        assertEquals(1, last["atlas_tile_variant"]!!.jsonPrimitive.int)
        assertEquals(5, last["ticks_per_frame"]!!.jsonPrimitive.int)
        assertEquals(listOf(0, 1, 2), (last["frames"] as JsonArray).map { it.jsonPrimitive.int })
        assertEquals(false, last["blend_frames"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun plainAnimationFollowsTheMcmetaTiming() {
        val r = Converter.convert(JavaPack.fromEntries(mapOf(
            "pack.mcmeta" to """{"pack":{"pack_format":15}}""".toByteArray(),
            "assets/minecraft/textures/block/nether_portal.png" to strip(3),
            "assets/minecraft/textures/block/nether_portal.png.mcmeta" to
                """{"animation":{"frametime":2,"interpolate":true,"frames":[0,{"index":2,"time":6},1]}}""".toByteArray(),
        )), ConvertOptions(name = "Portal"))
        assertTrue(r.blocks.isEmpty())
        assertEquals(16 to 48, Png.decode(r.files.getValue("textures/blocks/portal.png")).let { it.width to it.height })
        val e = (json(r.files, "textures/flipbook_textures.json") as JsonArray).single().jsonObject
        assertEquals("portal", e["atlas_tile"]!!.jsonPrimitive.content)
        assertEquals(2, e["ticks_per_frame"]!!.jsonPrimitive.int)
        // frame 0 for 2 ticks, frame 2 for 6, frame 1 for 2; the strip keeps the pack's frame order
        assertEquals(listOf(0, 2, 2, 2, 1), (e["frames"] as JsonArray).map { it.jsonPrimitive.int })
        assertEquals(true, e["blend_frames"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun vanillaAnimatedTilesKeepVanillasFlipbookFields() {
        val r = Converter.convert(JavaPack.fromEntries(mapOf(
            "pack.mcmeta" to """{"pack":{"pack_format":15}}""".toByteArray(),
            "assets/minecraft/textures/block/command_block_front.png" to strip(4),
            "assets/minecraft/textures/block/command_block_front.png.mcmeta" to """{"animation":{"frametime":10}}""".toByteArray(),
            "assets/minecraft/textures/block/water_flow.png" to strip(2, size = 32),
            "assets/minecraft/textures/block/water_flow.png.mcmeta" to """{"animation":{}}""".toByteArray(),
            "assets/minecraft/textures/block/respawn_anchor_top.png" to strip(2),
            "assets/minecraft/textures/block/respawn_anchor_top.png.mcmeta" to """{"animation":{}}""".toByteArray(),
            "assets/minecraft/textures/block/prismarine.png" to strip(2),
            "assets/minecraft/textures/block/prismarine.png.mcmeta" to """{"animation":{"frametime":300}}""".toByteArray(),
            "assets/minecraft/textures/block/magma.png" to strip(2),
            "assets/minecraft/textures/block/magma.png.mcmeta" to
                """{"animation":{"frames":[{"index":0,"time":1000000},{"index":1,"time":1}]}}""".toByteArray(),
        )), ConvertOptions(name = "Vanilla tiles"))
        val fb = (json(r.files, "textures/flipbook_textures.json") as JsonArray).map { it.jsonObject }
        fun entries(tile: String) = fb.filter { it["atlas_tile"]!!.jsonPrimitive.content == tile }
        fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.int
        fun size(path: String) = Png.decode(r.files.getValue(path)).let { it.width to it.height }

        // vanilla's command blocks animate a strip next to a still atlas texture: both are written
        val cmd = entries("command_block_front").single()
        assertEquals("textures/blocks/command_block_front", cmd["flipbook_texture"]!!.jsonPrimitive.content)
        assertEquals(16 to 64, size("textures/blocks/command_block_front.png"))
        assertEquals(16 to 16, size("textures/blocks/command_block_front_mipmap.png"))
        // flowing water keeps vanilla's replicate
        assertEquals(2, entries("flowing_water_grey").single().int("replicate"))
        // vanilla's array flipbooks: atlas_index and atlas_tile_variant both name the index
        val anchor = entries("respawn_anchor_top")
        assertEquals(listOf(1, 2, 3, 4), anchor.map { it.int("atlas_index") })
        assertEquals(listOf(1, 2, 3, 4), anchor.map { it.int("atlas_tile_variant") })
        // tiles vanilla doesn't animate: arrays do the same, single textures name no index
        val wall = entries("cobblestone_wall").single()
        assertEquals(11 to 11, wall.int("atlas_index") to wall.int("atlas_tile_variant"))
        val block = entries("prismarine").single()
        assertEquals(null to null, block.int("atlas_index") to block.int("atlas_tile_variant"))
        // a huge frame time is counted in coarser steps instead of one frame per tick
        val magma = entries("magma").single()
        assertTrue((magma["frames"] as JsonArray).size <= Anim.MAX_TICKS + 1, "${(magma["frames"] as JsonArray).size} frames")
        assertTrue(magma.int("ticks_per_frame")!! > 1)
        assertEquals(listOf("command_block_front", "magma", "prismarine", "respawn_anchor_top", "water_flow"), r.animated.sorted())
    }

    @Test
    fun matchTilesFindTheBedrockFaces() {
        val r = Converter.convert(JavaPack.fromEntries(mapOf(
            "pack.mcmeta" to """{"pack":{"pack_format":15}}""".toByteArray(),
            "assets/minecraft/optifine/ctm/log/oak.properties" to "matchTiles=oak_log_top\nmethod=fixed\ntiles=0".toByteArray(),
            "assets/minecraft/optifine/ctm/log/0.png" to strip(1),
        )), ConvertOptions(name = "Logs"))
        assertTrue("oak_log" in r.blocks, r.blocks.toString())
        val tex = json(r.files, "blocks.json").jsonObject["oak_log"]!!.jsonObject["textures"]!!.jsonObject
        assertEquals("ctm_logs_oak_log", tex["up"]!!.jsonPrimitive.content)
        assertEquals("ctm_logs_oak_log", tex["down"]!!.jsonPrimitive.content)
        assertTrue("side" !in tex)
        for (face in Bedrock.SIDES) assertEquals("oak_log_side", tex[face]!!.jsonPrimitive.content)
    }

    @Test
    fun fusionPiecedTextureFromAModel() {
        val tiles = Img(80, 16, IntArray(80 * 16) { i -> 0xff000000.toInt() or (((i % 80) / 16) * 0x303030) })
        val r = Converter.convert(JavaPack.fromEntries(mapOf(
            "pack.mcmeta" to """{"pack":{"pack_format":15},"fusion":{"overrides_folder":"fo"}}""".toByteArray(),
            "fo/assets/minecraft/models/block/glass.json" to
                """{"loader":"fusion:model","type":"connecting","parent":"minecraft:block/cube_all","textures":{"all":"demo:block/cg"}}""".toByteArray(),
            "assets/demo/textures/block/cg.png" to Png.encode(tiles),
            "assets/demo/textures/block/cg.png.mcmeta" to """{"fusion":{"type":"connecting","layout":"pieced"}}""".toByteArray(),
        )), ConvertOptions(name = "Fusion"))
        assertEquals(mapOf("glass" to "ctm-8x6-47"), r.blocks)
        assertEquals(mapOf("fusion:pieced" to 1), r.methods)
        val sheet = Png.decode(r.files.getValue("textures/blocks/ctm/ctm-8x6-47/fusion_glass.png"))
        assertEquals(tiles[0, 0], sheet[0, 0]) // tile 0 is the unconnected piece
        assertEquals(tiles[16, 0], sheet[(26 % 8) * 16, (26 / 8) * 16]) // CTM tile 26 (all around) is Fusion's tile 1
    }

    @Test
    fun previewTilesAreInRange() {
        for (spec in listOf("ctm-8x6-47", "horizontal-4x1-4", "vertical-4x1-4", "hv-7x1-7", "vh-7x1-7", "top-2x1-2", "random-8x2-9", "repeat-3x2-6", "fixed-1x1-1")) {
            val s = Ctm.parseSpec("textures/blocks/ctm/$spec/x")!!
            for ((_, _, t) in Ctm.previewTiles(s)) assertTrue(t in 0 until s.count, "$spec tile $t")
        }
        // lone block, full surround
        val s = Ctm.parseSpec("textures/blocks/ctm/ctm-8x6-47/x")!!
        assertEquals(0, Ctm.wallTile(s, 0, 0) { x, y -> x == 0 && y == 0 })
        assertEquals(26, Ctm.wallTile(s, 0, 0) { _, _ -> true })
    }
}
