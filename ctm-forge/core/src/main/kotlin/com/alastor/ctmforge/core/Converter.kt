package com.alastor.ctmforge.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Converts Java Edition OptiFine/MCPatcher CTM packs into Bedrock packs for the Connected
 * Textures LeviLauncher mod. A line-for-line port of ctm_convert.py (the converter the
 * shipped glass packs were made with); the golden tests hold the two to identical output.
 *
 * Every block a CTM rule targets gets a texture alias `ctm_<slug>_<block>` whose terrain
 * texture is a weighted variations list [base texture (weight 1000000), tile sheet (0.05)].
 * Without the mod the base texture practically always wins; with the mod the sheet is used
 * and the mod picks a tile per face. The sheet path tells the mod how to read it:
 * `textures/blocks/ctm/<method>-<cols>x<rows>-<count>[-s]/<slug>_<block>.png`.
 */
class ConversionException(message: String, val log: List<String>) : Exception(message)

data class ConvertOptions(
    /** Display name; defaults to the source file name. */
    val name: String? = null,
    /** Identifier used in file names and UUIDs; defaults to a slug of the name. */
    val slug: String? = null,
    /** Fallback layout: the block's texture is the sheet itself (looks wrong without the mod). */
    val direct: Boolean = false,
    /** File name of the source (used for the default name). */
    val sourceName: String = "pack",
    /** Pack version; bump it to re-import a pack Minecraft already has. */
    val version: List<Int> = listOf(1, 0, 0),
    /** zlib level for the PNGs written into the pack. */
    val pngLevel: Int = 9,
)

/** One converted block and the sheet it uses. */
class BlockSheet(val block: String, val spec: String, val sheetPath: String, val faces: List<String>)

class ConversionResult(
    val name: String,
    val slug: String,
    val direct: Boolean,
    val headerUuid: String,
    val version: List<Int>,
    /** Every file of the Bedrock pack, sorted by path. */
    val files: Map<String, ByteArray>,
    /** Converted block -> sheet spec, in conversion order. */
    val blocks: Map<String, String>,
    /** CTM method -> number of blocks converted with it. */
    val methods: Map<String, Int>,
    /** Number of CTM rules read from the Java pack. */
    val rules: Int,
    val log: List<String>,
    val sheets: List<BlockSheet>,
) {
    val icon: ByteArray? get() = files["pack_icon.png"]

    fun writeMcpack(out: OutputStream) {
        val zip = ZipOutputStream(out)
        for ((path, bytes) in files) {
            // PNGs are already deflated; storing them again at level 9 only costs time
            zip.setLevel(if (path.endsWith(".png")) 1 else 9)
            val e = ZipEntry(path)
            e.time = FIXED_TIME
            zip.putNextEntry(e)
            zip.write(bytes)
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    fun mcpackBytes(): ByteArray = ByteArrayOutputStream().also { writeMcpack(it) }.toByteArray()

    private companion object {
        const val FIXED_TIME = 1735689600000L // 2025-01-01, so identical input gives an identical file
    }
}

/** Bedrock 1.26.60 vanilla data: blocks.json entries and terrain_texture.json aliases. */
object Vanilla {
    private val root: JsonObject by lazy {
        val stream = Vanilla::class.java.getResourceAsStream("/vanilla_26.60.json")
            ?: error("vanilla_26.60.json is missing from the build")
        Json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
    }
    val blocks: JsonObject get() = root["blocks"] as JsonObject
    val terrainTextures: JsonObject get() = root["terrain_textures"] as JsonObject
    val source: String get() = (root["source"] as? JsonPrimitive)?.content ?: ""
}

object Converter {
    val FACE_NAMES = listOf("down", "up", "north", "south", "west", "east")
    private val SIDES = setOf("north", "south", "east", "west")
    private val OPTIFINE_FACES = mapOf(
        "bottom" to setOf("down"), "top" to setOf("up"), "north" to setOf("north"), "south" to setOf("south"),
        "east" to setOf("east"), "west" to setOf("west"), "sides" to SIDES, "all" to FACE_NAMES.toSet(),
    )
    private val COLORS = listOf(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    )
    private val BEDROCK_COLOR = COLORS.associateWith { if (it == "light_gray") "silver" else it }
    private val STAINED = Regex("(\\w+?)_stained_glass(_pane_top)?")
    private val RULE_PATH = Regex("assets/[^/]+/(optifine|mcpatcher)/ctm/.*")
    private val BLOCK_TEXTURE = Regex("assets/minecraft/textures/block/([a-z0-9_]+)\\.png")
    private val RANGE = Regex("(\\d+)-(\\d+)")
    private val LINE_BREAK = Regex("\r\n|[\n\r\u000B\u000C\u001C\u001D\u001E\u0085  ]")

    /** Optional decoder for tiles that aren't PNG files (the app plugs in Android's decoders). */
    @Volatile var fallbackDecoder: ((ByteArray) -> Img?)? = null

    // ------------------------------------------------------------------ name helpers

    /** Java block texture name -> Bedrock texture path (without extension). */
    fun javaToBedrockTexture(name: String): String? {
        if (name == "glass" || name == "glass_pane_top" || name == "tinted_glass") return "textures/blocks/$name"
        val m = STAINED.matchEntire(name) ?: return null
        val c = BEDROCK_COLOR[m.groupValues[1]] ?: return null
        return if (m.groups[2] != null) "textures/blocks/glass_pane_top_$c" else "textures/blocks/glass_$c"
    }

    /** Bedrock texture path for a converted block whose vanilla texture is one plain file. */
    private fun convertedBlockTexture(name: String, converted: Map<String, String>): String? {
        if (name !in converted) return null
        val alias = ((Vanilla.blocks[name] as? JsonObject)?.get("textures") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content ?: return null
        return (Vanilla.terrainTextures[alias] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** Java texture a block's main faces use (panes use their glass texture). */
    private fun mainJavaTexture(block: String) = if (block.endsWith("_pane")) block.dropLast(5) else block

    private fun isPane(block: String) = block.endsWith("glass_pane")

    private fun mainTexturePath(block: String) = "assets/minecraft/textures/block/${mainJavaTexture(block)}.png"

    fun slugify(s: String): String =
        Regex("[^a-z0-9]+").replace(s.lowercase(), "_").trim('_').take(40).ifEmpty { "pack" }

    /** Default display name for a source file, like the Python converter's `src.stem`. */
    fun displayNameFor(fileName: String): String =
        pyStrip(Regex("[_\\-]+").replace(pyStem(fileName), " "))

    // ------------------------------------------------------------------ python string semantics

    private fun pySpace(c: Char) = c.isWhitespace() || c == '\u0085'
    internal fun pyStrip(s: String) = s.trim(::pySpace)
    internal fun pySplit(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && pySpace(s[i])) i++
            val start = i
            while (i < s.length && !pySpace(s[i])) i++
            if (i > start) out += s.substring(start, i)
        }
        return out
    }
    private fun pyStem(fileName: String): String {
        val name = fileName.replace('\\', '/').substringAfterLast('/')
        val i = name.lastIndexOf('.')
        return if (i > 0 && i < name.length - 1) name.substring(0, i) else name
    }

    // ------------------------------------------------------------------ properties

    fun parseProperties(text: String): Map<String, String> {
        val props = LinkedHashMap<String, String>()
        for (raw in text.removePrefix("﻿").split(LINE_BREAK)) {
            val line = pyStrip(raw)
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || '=' !in line) continue
            props[pyStrip(line.substringBefore('='))] = pyStrip(line.substringAfter('='))
        }
        return props
    }

    fun expandTileList(spec: String): List<String> {
        val out = ArrayList<String>()
        for (tok in pySplit(spec)) {
            val m = RANGE.matchEntire(tok)
            val a = m?.groupValues?.get(1)?.toIntOrNull()
            val b = m?.groupValues?.get(2)?.toIntOrNull()
            if (a != null && b != null && kotlin.math.abs(b.toLong() - a) <= 100_000) {
                if (a <= b) for (i in a..b) out += i.toString() else for (i in a downTo b) out += i.toString()
            } else {
                out += tok
            }
        }
        return out
    }

    fun blockNames(spec: String): List<String> {
        val out = ArrayList<String>()
        for (tok in pySplit(spec)) {
            var parts = tok.split(":")
            if (parts.size >= 2 && parts[0] == "minecraft") parts = parts.drop(1)
            else if (parts.size >= 2 && '=' !in parts[1]) continue // other namespace (mod block)
            out += parts[0]
        }
        return out
    }

    private fun plainDescription(desc: JsonElement?): String {
        fun flat(d: JsonElement?): String = when (d) {
            null, JsonNull -> ""
            is JsonArray -> d.joinToString("") { plainDescription(it) }
            is JsonObject -> flat(d["text"]) + ((d["extra"] as? JsonArray)?.joinToString("") { plainDescription(it) } ?: "")
            is JsonPrimitive -> when {
                d.isString -> d.content
                d.booleanOrNull != null -> if (d.booleanOrNull == true) "True" else ""
                d.content.toDoubleOrNull() == 0.0 -> ""
                else -> d.content
            }
        }
        return pyStrip(Regex("§[^\n]").replace(flat(desc), ""))
    }

    // ------------------------------------------------------------------ rules

    private class Rule(
        val path: String,
        val props: Map<String, String>,
        val method: String,
        val blocks: List<String>,
        val faces: Set<String>,
        val innerSeams: Boolean,
        /** Pack path of every tile, in the order the properties list them. */
        val tiles: List<String>,
        val weights: List<Int>,
        val width: Int,
        val height: Int,
        val fusion: Fusion.Rule? = null,
    )

    private class Layout(val method: String, val tiles: List<Img>, val cols: Int)

    private class Job(val pack: JavaPack, val opt: ConvertOptions) {
        val log = ArrayList<String>()
        private val images = HashMap<String, Img>()
        private val fusionPaths = Fusion.texturePaths(pack)

        fun has(path: String): Boolean = pack.get(path)?.isNotEmpty() == true

        /** Decodes a pack image (first frame of animated strips). Cached for the current rule. */
        fun image(path: String): Img = images.getOrPut(path) {
            decode(pack.get(path) ?: throw IllegalStateException("missing $path"))
        }

        fun releaseImages() = images.clear()

        fun resolveTile(ruleDir: String, namespace: String, tok: String, basePath: String?): String? {
            if (tok == "<default>" || tok == "<skip>") return basePath
            val name = if (tok.endsWith(".png")) tok.dropLast(4) else tok
            val cands = when {
                ':' in name -> listOf("assets/${name.substringBefore(':')}/textures/${name.substringAfter(':')}.png")
                '/' in name -> listOf(
                    "assets/$namespace/$name.png", "assets/minecraft/$name.png",
                    "$ruleDir/$name.png", "assets/$namespace/textures/$name.png",
                )
                else -> listOf("$ruleDir/$name.png", "assets/minecraft/textures/block/$name.png")
            }
            return cands.firstOrNull { pack.get(it) != null }
        }

        fun readRules(): List<Rule> {
            val rules = ArrayList<Rule>()
            val paths = pack.files.keys.filter { it.endsWith(".properties") && RULE_PATH.matches(it) }.sorted()
            for (path in paths) {
                val namespace = path.split("/")[1]
                val ruleDir = path.substringBeforeLast('/')
                val props = parseProperties(String(pack.files.getValue(path), Charsets.UTF_8))
                val method = (props["method"] ?: "ctm").lowercase()
                    .replace("horizontal+vertical", "hv").replace("vertical+horizontal", "vh")
                if (method.startsWith("overlay")) {
                    log += "skip $path: overlay methods are not supported"
                    continue
                }
                var blocks = blockNames(props["matchBlocks"] ?: "")
                if (blocks.isEmpty() && !props["matchTiles"].isNullOrEmpty()) {
                    blocks = pySplit(props.getValue("matchTiles")).map { it.substringAfterLast('/') }
                }
                if (blocks.isEmpty()) {
                    val stem = path.substringAfterLast('/').removeSuffix(".properties")
                    if (stem.startsWith("block_")) blocks = listOf(stem.removePrefix("block_"))
                }
                val faces = LinkedHashSet<String>()
                // OptiFine separates faces with spaces or commas ("faces=north,south,east,west")
                for (f in pySplit((props["faces"] ?: "all").replace(',', ' '))) faces += OPTIFINE_FACES[f.lowercase()] ?: emptySet()
                for (unsupported in listOf("biomes", "heights", "minHeight", "maxHeight", "metadata", "name")) {
                    if (unsupported in props) log += "note $path: '$unsupported' is ignored"
                }
                val connect = (props["connect"] ?: "block").lowercase()
                if (connect != "block" && connect != "") log += "note $path: connect=$connect is treated as connect=block"
                val innerSeams = (props["innerSeams"] ?: "false").lowercase() == "true"
                val basePath = blocks.firstOrNull()?.let { mainTexturePath(it) }?.takeIf { has(it) }
                val toks = expandTileList(props["tiles"] ?: "")
                val tiles = toks.map { resolveTile(ruleDir, namespace, it, basePath) }
                if (tiles.isEmpty() || tiles.any { it == null }) {
                    log += "skip $path: missing tile images"
                    continue
                }
                var weights = emptyList<Int>()
                if (method == "random") {
                    val w = pySplit(props["weights"] ?: "").filter { s -> s.isNotEmpty() && s.all { it in '0'..'9' } }
                        .map { it.toIntOrNull() ?: Int.MAX_VALUE }
                    weights = if (w.size == tiles.size) w else List(tiles.size) { 1 }
                }
                var width = 1
                var height = 1
                if (method == "repeat") {
                    width = props["width"]?.let { pyStrip(it).toIntOrNull() } ?: 1
                    height = props["height"]?.let { pyStrip(it).toIntOrNull() } ?: 1
                }
                rules += Rule(
                    path, props, method, blocks, faces.ifEmpty { FACE_NAMES.toSet() }, innerSeams,
                    tiles.map { it!! }, weights, width, height,
                )
            }
            for (f in Fusion.readRules(pack, log)) rules += Rule(
                f.path, emptyMap(), "fusion_${f.layout}", listOf(f.block), f.faces, false, emptyList(), emptyList(), 1, 1, f,
            )
            return rules
        }

        fun ruleLayout(rule: Rule): Layout? {
            rule.fusion?.let { return Layout("ctm", Fusion.tiles(pack, it), 8) }
            val m = rule.method
            val t = rule.tiles
            fun load(paths: List<String>) = paths.map { image(it) }
            return when (m) {
                "ctm" -> {
                    if (t.size < 47) {
                        log += "skip ${rule.path}: ctm needs 47 tiles, found ${t.size}"
                        null
                    } else Layout("ctm", load(t.take(47)), 8)
                }
                "ctm_compact" -> {
                    if (t.size < 5) {
                        log += "skip ${rule.path}: ctm_compact needs 5 tiles"
                        null
                    } else {
                        if (rule.props.keys.any { it.startsWith("ctm.") }) log += "note ${rule.path}: ctm.N replacement tiles are ignored"
                        Layout("ctm", compactTo47(load(t.take(5))), 8)
                    }
                }
                "horizontal", "vertical" -> if (t.size >= 4) Layout(m, load(t.take(4)), 4) else {
                    log += "skip ${rule.path}: $m needs 4 tiles, found ${t.size}"
                    null
                }
                "hv", "vh" -> if (t.size >= 7) Layout(m, load(t.take(7)), 7) else {
                    log += "skip ${rule.path}: ${rule.props["method"]} needs 7 tiles, found ${t.size}"
                    null
                }
                "top" -> {
                    val base = rule.blocks.firstOrNull()?.let { mainTexturePath(it) }?.takeIf { has(it) }
                    if (base == null) {
                        log += "skip ${rule.path}: 'top' needs the block's own texture in the pack"
                        null
                    } else Layout("top", listOf(image(base), image(t[0])), 2)
                }
                "fixed" -> Layout("fixed", load(t.take(1)), 1)
                "repeat" -> {
                    val n = rule.width.toLong() * rule.height
                    if (rule.width > 0 && rule.height > 0 && t.size >= n) Layout("repeat", load(t.take(n.toInt())), rule.width) else {
                        log += "skip ${rule.path}: repeat needs width x height (${rule.width} x ${rule.height}) tiles, found ${t.size}"
                        null
                    }
                }
                "random" -> {
                    var g = 0
                    for (w in rule.weights) g = gcd(g, w)
                    val counts = rule.weights.map { maxOf(1L, it.toLong() / maxOf(g, 1)) }
                    val imgs = load(t)
                    if (counts.sum() > 64) {
                        log += "note ${rule.path}: random weights too fine, using equal weights"
                        Layout("random", imgs, minOf(8, imgs.size))
                    } else {
                        val expanded = ArrayList<Img>()
                        for ((i, img) in imgs.withIndex()) repeat(counts[i].toInt()) { expanded += img }
                        Layout("random", expanded, minOf(8, expanded.size))
                    }
                }
                else -> {
                    log += "skip ${rule.path}: unknown method '$m'"
                    null
                }
            }
        }

        fun png(img: Img): ByteArray = Png.encode(img, opt.pngLevel)

        fun run(progress: (Int, Int) -> Unit): ConversionResult {
            val meta = runCatching {
                (Json.parseToJsonElement(String(pack.get("pack.mcmeta") ?: "{}".toByteArray(), Charsets.UTF_8).removePrefix("﻿"))
                    as? JsonObject)?.get("pack") as? JsonObject
            }.getOrNull()
            val display = opt.name ?: displayNameFor(opt.sourceName)
            val slug = opt.slug ?: slugify(display)
            val rules = readRules()

            val files = HashMap<String, ByteArray>()
            val terrain = LinkedHashMap<String, JsonElement>()
            val blocksJson = LinkedHashMap<String, JsonElement>()
            blocksJson["format_version"] = JsonPrimitive("1.21.120")
            val entries = HashMap<String, LinkedHashMap<String, JsonElement>>()
            val assigned = HashMap<String, MutableSet<String>>()
            val ruleCount = HashMap<String, Int>()
            val blocks = LinkedHashMap<String, String>()
            val methods = LinkedHashMap<String, Int>()
            val sheets = ArrayList<BlockSheet>()

            for ((ri, rule) in rules.withIndex()) {
                progress(ri, rules.size)
                val layout = try {
                    ruleLayout(rule)
                } catch (e: Exception) {
                    log += "skip ${rule.path}: unreadable tile image (${e.message})"
                    null
                }
                if (layout == null) { releaseImages(); continue }
                val (sheet, cols, rows) = buildSheet(layout.tiles, layout.cols)
                val spec = "${layout.method}-${cols}x$rows-${layout.tiles.size}" + if (rule.innerSeams) "-s" else ""
                var sheetPng: ByteArray? = null
                val basePngs = HashMap<String, ByteArray>()
                val tile0 = layout.tiles[0]
                for (block in rule.blocks) {
                    val vanilla = Vanilla.blocks[block] as? JsonObject
                    if (vanilla == null) {
                        log += "skip ${rule.path}: '$block' has no Bedrock blocks.json entry"
                        continue
                    }
                    val pane = isPane(block)
                    val faces = LinkedHashSet<String>()
                    if (pane && rule.faces.any { it in SIDES }) faces += listOf("down", "up", "north", "south", "west")
                    else faces += rule.faces.filter { !(pane && it == "east") }
                    faces -= assigned[block] ?: emptySet()
                    if (faces.isEmpty()) {
                        log += "note ${rule.path}: $block faces already covered by an earlier rule"
                        continue
                    }
                    val entry = entries[block] ?: LinkedHashMap(vanilla)
                    val tex: LinkedHashMap<String, JsonElement> = when (val t = entry["textures"]) {
                        is JsonPrimitive -> LinkedHashMap<String, JsonElement>().also { m -> FACE_NAMES.forEach { m[it] = t } }
                        is JsonObject -> LinkedHashMap(t)
                        else -> {
                            log += "skip ${rule.path}: '$block' has no textures in blocks.json"
                            continue
                        }
                    }
                    val nth = ruleCount[block] ?: 0
                    ruleCount[block] = nth + 1
                    assigned.getOrPut(block) { HashSet() } += faces
                    val stem = "${slug}_$block" + if (nth > 0) "_${nth + 1}" else ""
                    val sheetPath = "textures/blocks/ctm/$spec/$stem"
                    val basePath = "textures/blocks/ctm/base/$stem"
                    files["$sheetPath.png"] = sheetPng ?: png(sheet).also { sheetPng = it }
                    val alias = "ctm_$stem"
                    if (opt.direct) {
                        terrain[alias] = JsonObject(mapOf("textures" to JsonPrimitive(sheetPath)))
                    } else {
                        val src = mainTexturePath(block).takeIf { rule.fusion == null && it !in fusionPaths && has(it) }
                        val key = src ?: "<tile0>"
                        files["$basePath.png"] = basePngs.getOrPut(key) {
                            val base = src?.let { runCatching { image(it) }.getOrNull() } ?: tile0
                            png(base.resizeNearest(tile0.width, tile0.height))
                        }
                        terrain[alias] = JsonObject(mapOf("textures" to JsonObject(mapOf("variations" to JsonArray(listOf(
                            JsonObject(mapOf("path" to JsonPrimitive(basePath), "weight" to JsonPrimitive(1000000))),
                            JsonObject(mapOf("path" to JsonPrimitive(sheetPath), "weight" to JsonPrimitive(0.05))),
                        ))))))
                    }
                    if ("carried_textures" !in entry) entry["carried_textures"] = vanilla["textures"] ?: JsonNull
                    for (f in FACE_NAMES) if (f in faces) tex[f] = JsonPrimitive(alias)
                    entry["textures"] = if (tex.values.toSet().size > 1) JsonObject(tex) else tex.values.first()
                    entry.remove("isotropic") // random texture rotation would scramble connected tiles
                    entries[block] = entry
                    blocksJson[block] = JsonObject(entry)
                    blocks[block] = spec
                    methods[rule.method] = (methods[rule.method] ?: 0) + 1
                    sheets += BlockSheet(block, spec, sheetPath, FACE_NAMES.filter { it in faces })
                    if (rule.fusion != null) {
                        val target = javaToBedrockTexture(block) ?: convertedBlockTexture(block, blocks)
                        if (target != null) files["$target.png"] = png(tile0)
                    }
                }
                releaseImages()
            }
            progress(rules.size, rules.size)

            // base texture overrides (items, particles) when the Java pack ships them
            for ((p, data) in pack.files) {
                if (p in fusionPaths) continue // Never use a Fusion atlas as a base/item texture.
                val m = BLOCK_TEXTURE.matchEntire(p) ?: continue
                val name = m.groupValues[1]
                val target = javaToBedrockTexture(name) ?: convertedBlockTexture(name, blocks) ?: continue
                try {
                    files["$target.png"] = png(decode(data))
                } catch (e: Exception) {
                    log += "note $p: unreadable image, not copied (${e.message})"
                }
            }

            if (blocks.isEmpty()) throw ConversionException("No convertible CTM rules found", log)

            files["textures/terrain_texture.json"] = pretty(JsonObject(linkedMapOf(
                "resource_pack_name" to JsonPrimitive(slug), "texture_name" to JsonPrimitive("atlas.terrain"),
                "padding" to JsonPrimitive(8), "num_mip_levels" to JsonPrimitive(4), "texture_data" to JsonObject(terrain),
            )))
            files["blocks.json"] = pretty(JsonObject(blocksJson))
            val desc = plainDescription(meta?.get("description"))
            val ns = Uuid5.of(Uuid5.NAMESPACE_URL, "https://example.invalid/ctm-converter/$slug" + if (opt.direct) "/direct" else "")
            val headerUuid = Uuid5.of(ns, "header").toString()
            val version = JsonArray(opt.version.map { JsonPrimitive(it) })
            files["manifest.json"] = pretty(JsonObject(linkedMapOf(
                "format_version" to JsonPrimitive(2),
                "header" to JsonObject(linkedMapOf(
                    "name" to JsonPrimitive("$display (CTM${if (opt.direct) " direct" else ""})"),
                    "description" to JsonPrimitive(
                        "Converted from the Java pack \"$display\" for the Connected Textures LeviLauncher mod." +
                            if (desc.isNotEmpty()) " Original: $desc" else "",
                    ),
                    "uuid" to JsonPrimitive(headerUuid),
                    "version" to version,
                    "min_engine_version" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(21), JsonPrimitive(120))),
                )),
                "modules" to JsonArray(listOf(JsonObject(linkedMapOf(
                    "type" to JsonPrimitive("resources"),
                    "uuid" to JsonPrimitive(Uuid5.of(ns, "resources").toString()),
                    "version" to version,
                )))),
            )))
            pack.get("pack.png")?.takeIf { it.isNotEmpty() }?.let { icon ->
                try {
                    var im = decode(icon)
                    if (im.width > 256) im = im.resizeArea(256, 256)
                    files["pack_icon.png"] = png(im)
                } catch (e: Exception) {
                    log += "note pack.png: unreadable image, no pack icon (${e.message})"
                }
            }
            return ConversionResult(
                display, slug, opt.direct, headerUuid, opt.version, TreeMap(files), blocks, methods, rules.size,
                log.toList(), sheets,
            )
        }
    }

    private val PRETTY = Json { prettyPrint = true }

    private fun pretty(e: JsonElement): ByteArray = PRETTY.encodeToString(JsonElement.serializer(), e).toByteArray()

    private fun gcd(a: Int, b: Int): Int {
        var x = kotlin.math.abs(a); var y = kotlin.math.abs(b)
        while (y != 0) { val t = x % y; x = y; y = t }
        return x
    }

    /** Decodes an image the way the Python converter's load_image does (RGBA, first frame). */
    fun decode(bytes: ByteArray): Img {
        val img = if (Png.isPng(bytes)) Png.decode(bytes)
        else fallbackDecoder?.invoke(bytes) ?: throw Png.PngException("not a PNG image")
        return img.firstFrame()
    }

    /** Builds the 47 standard tiles from the 5 ctm_compact tiles (OptiFine/Continuity rules). */
    fun compactTo47(compact: List<Img>): List<Img> {
        val size = compact.maxOf { it.width }
        val c = compact.map { it.resizeNearest(size, size) }
        val h = size / 2
        // quadrant q: 0=top-left, 1=bottom-left, 2=bottom-right, 3=top-right; dirs [L, D, R, U]
        val boxes = arrayOf(intArrayOf(0, 0, h, h), intArrayOf(0, h, h, size), intArrayOf(h, h, size, size), intArrayOf(h, 0, size, h))
        val rep = IntArray(47) { -1 }
        for (s in 0 until 256) if (rep[Ctm.CTM47[s]] < 0) rep[Ctm.CTM47[s]] = s
        return List(47) { idx ->
            val s = rep[idx]
            val tile = Img(size, size)
            for (q in 0 until 4) {
                val i1 = q
                val i2 = (q + 3) % 4
                val c1 = (s shr (i1 * 2)) and 1
                val c2 = (s shr (i2 * 2)) and 1
                val k = when {
                    c1 == 1 && c2 == 1 -> if ((s shr (i2 * 2 + 1)) and 1 == 1) 1 else 4
                    c1 == 1 -> 3 - q % 2
                    c2 == 1 -> 2 + q % 2
                    else -> 0
                }
                val b = boxes[q]
                tile.paste(c[k].crop(b[0], b[1], b[2], b[3]), b[0], b[1])
            }
            tile
        }
    }

    /** All tiles in one image, [cols] per row, every tile scaled to the largest tile size. */
    fun buildSheet(tiles: List<Img>, cols: Int): Triple<Img, Int, Int> {
        val size = tiles.maxOf { maxOf(it.width, it.height) }
        val rows = (tiles.size + cols - 1) / cols
        val sheet = Img(cols * size, rows * size)
        for ((i, t0) in tiles.withIndex()) {
            val t = if (t0.width != size || t0.height != size) t0.resizeNearest(size, size) else t0
            sheet.paste(t, (i % cols) * size, (i / cols) * size)
        }
        return Triple(sheet, cols, rows)
    }

    /**
     * Converts [pack]. [progress] gets (rules done, rule count); throw from it to cancel.
     * Throws [ConversionException] (with the log) when nothing could be converted.
     */
    fun convert(pack: JavaPack, options: ConvertOptions, progress: (Int, Int) -> Unit = { _, _ -> }): ConversionResult =
        Job(pack, options).run(progress)

    /** Number of CTM rule files in a pack (quick check before converting). */
    fun countRules(pack: JavaPack): Int = pack.ctmRuleCount
}
