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
            assertTrue(e.message!!.contains("No convertible"))
        }
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
