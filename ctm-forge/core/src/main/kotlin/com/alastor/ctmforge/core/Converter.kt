package com.alastor.ctmforge.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Converts Java Edition connected-texture packs into Bedrock packs for the Connected Textures
 * LeviLauncher mod: OptiFine/MCPatcher CTM rules (also used by Continuity), Fusion connecting
 * textures, and animated block textures. A line-for-line port of ctm_convert.py; the golden
 * tests hold the two to identical output.
 *
 * Every block a CTM rule targets gets a texture alias `ctm_<slug>_<block>` whose terrain
 * texture is a weighted variations list [base texture (weight 1000000), tile sheet (0.05)].
 * Without the mod the base texture practically always wins; with the mod the sheet is used
 * and the mod picks a tile per face. The sheet path tells the mod how to read it:
 * `textures/blocks/ctm/<method>-<cols>x<rows>-<count>[-s]/<slug>_<block>.png`.
 * Animated sheets are square and get a flipbook that plays every frame of the whole sheet.
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
    /** CTM method (or "fusion:<layout>") -> number of blocks converted with it. */
    val methods: Map<String, Int>,
    /** Number of CTM rules (OptiFine files and Fusion textures) read from the Java pack. */
    val rules: Int,
    val log: List<String>,
    val sheets: List<BlockSheet>,
    /** Animated blocks and textures (connected sheets and plain flipbooks). */
    val animated: List<String> = emptyList(),
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

object Converter {
    val FACE_NAMES = Bedrock.FACES
    private val SIDES = Bedrock.SIDES
    private val OPTIFINE_FACES = mapOf(
        "bottom" to setOf("down"), "top" to setOf("up"), "north" to setOf("north"), "south" to setOf("south"),
        "east" to setOf("east"), "west" to setOf("west"), "sides" to SIDES, "all" to FACE_NAMES.toSet(),
    )
    private val RULE_PATH = Regex("assets/[^/]+/(optifine|mcpatcher)/ctm/.*")
    private val BLOCK_TEXTURE = Regex("assets/minecraft/textures/(block|blocks)/([a-z0-9_]+)\\.png")

    /** 1.12 texture names (textures/blocks/) that are neither Bedrock's nor 1.13+ Java's. */
    private val LEGACY_TEXTURES = mapOf("fire_layer_0" to "fire_0", "fire_layer_1" to "fire_1")
    private val BLOCKSTATE = Regex("assets/minecraft/blockstates/[a-z0-9_]+\\.json")
    private val BLOCK_MODEL = Regex("assets/minecraft/models/block/([a-z0-9_]+)\\.json")
    private val RANGE = Regex("(\\d+)-(\\d+)")
    private val LIST_SEP = Regex("[\\s,]+")
    private val LINE_BREAK = Regex("\r\n|[\n\r\u000B\u000C\u001C\u001D\u001E\u0085\u2028\u2029]") // str.splitlines()

    /** Optional decoder for tiles that aren't PNG files (the app plugs in Android's decoders). */
    @Volatile var fallbackDecoder: ((ByteArray) -> Img?)? = null

    // ------------------------------------------------------------------ name helpers

    /** Java block texture name -> Bedrock texture path (without extension), glass family. */
    fun javaToBedrockTexture(name: String): String? = Bedrock.glassTexture(name)

    /** Java texture a block's main faces use (panes use their glass texture). */
    private fun mainJavaTexture(block: String) = when {
        block.startsWith("waxed_") && block.removePrefix("waxed_") in JavaNames.textures -> block.removePrefix("waxed_")
        block.endsWith("_pane") -> block.dropLast(5)
        else -> block
    }

    private fun isPane(block: String) = block.endsWith("glass_pane")

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

    /** re.split(r"[\s,]+", s) without the empty pieces. */
    private fun listItems(s: String): List<String> = s.split(LIST_SEP).filter { it.isNotEmpty() }

    private fun pyStem(fileName: String): String {
        val name = fileName.replace('\\', '/').substringAfterLast('/')
        val i = name.lastIndexOf('.')
        return if (i > 0 && i < name.length - 1) name.substring(0, i) else name
    }

    // ------------------------------------------------------------------ properties

    fun parseProperties(text: String): Map<String, String> {
        val props = LinkedHashMap<String, String>()
        for (raw in text.removePrefix("\uFEFF").split(LINE_BREAK)) {
            val line = pyStrip(raw)
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || '=' !in line) continue
            props[pyStrip(line.substringBefore('='))] = pyStrip(line.substringAfter('='))
        }
        return props
    }

    fun expandTileList(spec: String): List<String> {
        val out = ArrayList<String>()
        for (tok in listItems(spec)) { // OptiFine separates list items with spaces or commas
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
        for (tok in listItems(spec)) {
            var parts = tok.split(":")
            if (parts.size >= 2 && parts[0] == "minecraft") parts = parts.drop(1)
            else if (parts.size >= 2 && '=' !in parts[1]) continue // other namespace (mod block)
            out += parts[0]
        }
        return out
    }

    /** matchTiles entries -> Java block texture names (vanilla textures only). */
    fun tileNames(spec: String): List<String> {
        val out = ArrayList<String>()
        for (t0 in listItems(spec)) {
            var tok = t0
            if (':' in tok) {
                if (tok.substringBefore(':') != "minecraft") continue
                tok = tok.substringAfter(':')
            }
            if (tok.endsWith(".png")) tok = tok.dropLast(4)
            if (tok.startsWith("textures/")) tok = tok.removePrefix("textures/")
            if ('/' in tok && !tok.startsWith("block/") && !tok.startsWith("blocks/")) continue // a texture of the pack itself
            out += tok.substringAfterLast('/')
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

    internal class Rule(
        val path: String,
        val props: Map<String, String>,
        var method: String,
        val blocks: List<String>,
        val faces: Set<String>,
        var tiles: List<Tex> = emptyList(),
        var innerSeams: Boolean = false,
        var weights: List<Int> = emptyList(),
        var width: Int = 1,
        var height: Int = 1,
        /** matchTiles / texture-driven: Java texture names. */
        val textures: List<String> = emptyList(),
        val source: String = "optifine",
        var base: Tex? = null,
        /** Fusion layout of the tiles. */
        var layout: String? = null,
        /** OptiFine overlay* methods. */
        var overlay: Boolean = false,
        var conditions: RuleConditions = RuleConditions(),
    )

    private class Layout(val method: String, val inputs: List<Tex>, val build: (List<Img>) -> List<Img>, val cols: Int, val count: Int, val overlay: Boolean = false)

    /** A Bedrock block's faces a rule applies to. */
    private class Target(val block: String, val faces: Set<String>, val index: Int?, val texture: String?)

    /** One rule's sheet and everything needed to write its alias and flipbooks. */
    private class SheetEntry(
        val layout: Layout,
        val spec: String,
        val sheetPath: String,
        val basePath: String,
        val baseImg: Img,
        val tile0: Img,
        val baseTex: Tex?,
        val animated: Boolean,
        val stripPath: String?,
        val timeline: Timeline?,
        val conditions: RuleConditions,
    )

    private class Job(val pack: JavaPack, val opt: ConvertOptions) {
        val log = ArrayList<String>()
        val claimed = HashSet<String>()

        fun has(path: String): Boolean = pack.get(path) != null

        /** A pack texture with its .mcmeta animation (first frame only when not animated). */
        fun loadTex(path: String): Tex? {
            val data = pack.get(path) ?: return null
            val im = decodeFull(data)
            val meta = parseJson(pack.get("$path.mcmeta")) as? JsonObject ?: JsonObject(emptyMap())
            val anim = meta["animation"] as? JsonObject
            val f = meta["fusion"] as? JsonObject
            val layoutType = f != null && Fusion.type(f) in setOf("connecting", "random", "continuous", "scrolling")
            if (anim != null && !layoutType) Anim.animatedTex(im, anim)?.let { return it }
            Anim.scrollingTex(im, meta)?.let { return it }
            return Tex(listOf(im.firstFrame()))
        }

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

        fun mainTexturePath(block: String): String? =
            "assets/minecraft/textures/block/${mainJavaTexture(block)}.png".takeIf { has(it) }

        /** OptiFine's <skip> overlay tile: fully transparent, so nothing is drawn. */
        fun skippedTex() = Tex(listOf(Img(16, 16)))

        fun readRules(): List<Rule> {
            val rules = ArrayList<Rule>()
            val paths = pack.files.keys.filter { it.endsWith(".properties") && RULE_PATH.matches(it) }.sorted()
            for (path in paths) {
                val namespace = path.split("/")[1]
                val ruleDir = path.substringBeforeLast('/')
                val props = parseProperties(String(pack.files.getValue(path), Charsets.UTF_8))
                val method = (props["method"] ?: "ctm").lowercase()
                    .replace("horizontal+vertical", "hv").replace("vertical+horizontal", "vh")
                val overlay = method.startsWith("overlay")
                if (overlay && method !in setOf("overlay", "overlay_ctm", "overlay_random", "overlay_repeat", "overlay_fixed")) {
                    log += "skip $path: unknown method '$method'"
                    continue
                }
                var blocks = blockNames(props["matchBlocks"] ?: "")
                var textures = emptyList<String>()
                if (blocks.isEmpty() && !props["matchTiles"].isNullOrEmpty()) textures = tileNames(props.getValue("matchTiles"))
                if ("matchBlocks" !in props && "matchTiles" !in props) {
                    val stem = path.substringAfterLast('/').removeSuffix(".properties")
                    if (stem.startsWith("block_")) blocks = listOf(stem.removePrefix("block_"))
                    else textures = tileNames(stem)
                    log += "note $path: inferred ${if (blocks.isNotEmpty()) "matchBlocks=${stem.removePrefix("block_")}" else "matchTiles=$stem"} from filename"
                }
                val faces = LinkedHashSet<String>()
                // OptiFine separates faces with spaces or commas ("faces=north,south,east,west")
                for (f in listItems(props["faces"] ?: "all")) faces += OPTIFINE_FACES[f.lowercase()] ?: emptySet()
                for (unsupported in listOf("metadata", "name")) {
                    if (unsupported in props) log += "note $path: '$unsupported' is ignored"
                }
                val r = Rule(path, props, method, blocks, faces.ifEmpty { FACE_NAMES.toSet() }, textures = textures)
                r.overlay = overlay
                r.conditions = try {
                    RuleConditions.parse(props["biomes"] ?: "", props["connectBlocks"] ?: "",
                        props["ctmforge.neighborBlocks"] ?: "", props["ctmforge.belowBlocks"] ?: "",
                        props["heights"] ?: "", props["minHeight"] ?: "", props["maxHeight"] ?: "")
                } catch (e: IllegalArgumentException) {
                    log += "skip $path: ${e.message}"
                    continue
                }
                if (opt.direct && r.conditions.path.isNotEmpty()) {
                    log += "skip $path: conditional and custom connection rules require standard mode"
                    continue
                }
                val connect = (props["connect"] ?: "block").lowercase()
                if (connect != "block" && connect != "") log += "note $path: connect=$connect is treated as connect=block"
                if (overlay) {
                    for (k in listOf("connectTiles", "tintIndex", "tintBlock", "layer")) {
                        if (k in props) log += "note $path: '$k' is ignored"
                    }
                }
                r.innerSeams = (props["innerSeams"] ?: "false").lowercase() == "true"
                val basePath = if (blocks.isNotEmpty()) mainTexturePath(blocks[0])
                else textures.firstOrNull()?.let { "assets/minecraft/textures/block/$it.png" }?.takeIf { has(it) }
                val toks = expandTileList(props["tiles"] ?: "")
                val tilePaths = toks.map { if (overlay && it == "<skip>") "<skip>" else resolveTile(ruleDir, namespace, it, basePath) }
                if (tilePaths.isEmpty() || tilePaths.any { it == null }) {
                    log += "skip $path: missing tile images"
                    continue
                }
                try {
                    r.tiles = tilePaths.map { if (it == "<skip>") skippedTex() else loadTex(it!!)!! }
                } catch (e: Exception) {
                    log += "skip $path: unreadable tile image (${e.message})"
                    continue
                }
                if (basePath != null) r.base = runCatching { loadTex(basePath) }.getOrNull()
                if (method == "random" || method == "overlay_random") {
                    val w = listItems(props["weights"] ?: "").filter { s -> s.all { it in '0'..'9' } }
                        .map { it.toIntOrNull() ?: Int.MAX_VALUE }
                    r.weights = if (w.size == r.tiles.size) w else List(r.tiles.size) { 1 }
                }
                if (method == "repeat" || method == "overlay_repeat") {
                    r.width = props["width"]?.let { pyStrip(it).toIntOrNull() } ?: 1
                    r.height = props["height"]?.let { pyStrip(it).toIntOrNull() } ?: 1
                }
                rules += r
            }
            return rules
        }

        // -------------------------------------------------------------- Fusion

        fun fusionRules(): List<Rule> {
            val metas = LinkedHashMap<String, JsonObject>()
            for (p in pack.files.keys.sorted()) {
                if (!p.endsWith(".png.mcmeta") || !p.startsWith("assets/")) continue
                val m = parseJson(pack.get(p)) as? JsonObject ?: continue
                val f = m["fusion"] as? JsonObject ?: continue
                if (Fusion.type(f) !in setOf("connecting", "random", "continuous")) continue
                val png = p.removeSuffix(".mcmeta")
                if (pack.get(png) != null) metas[png] = m
            }
            if (metas.isEmpty()) return emptyList()
            // model-driven: block -> faces using each Fusion texture
            val uses = LinkedHashMap<Pair<String, String>, MutableSet<String>>()
            val blocks = LinkedHashMap<String, List<String>>()
            for (p in pack.files.keys.filter { BLOCKSTATE.matches(it) }.sorted()) {
                blocks[p.substringAfterLast('/').removeSuffix(".json")] =
                    Fusion.blockstateModels(parseJson(pack.get(p))).map { Fusion.normModel(it) }
            }
            for (p in pack.files.keys.sorted()) {
                val id = BLOCK_MODEL.matchEntire(p)?.groupValues?.get(1) ?: continue
                if (id !in blocks && Bedrock.block(id) != null) blocks[id] = listOf("minecraft:block/$id")
            }
            for ((jblock, models) in blocks) {
                for (model in models) {
                    val (faces, textures) = Fusion.resolveModel(pack, model) ?: continue
                    for ((face, ref) in faces) {
                        val r = Fusion.texRef(ref, textures) ?: continue
                        val file = Fusion.texFile(r)
                        if (file !in metas) continue
                        val fs = when {
                            isPane(jblock) -> setOf("down", "up", "north", "south", "west")
                            face == "pane" -> continue
                            else -> setOf(face)
                        }
                        uses.getOrPut(jblock to file) { LinkedHashSet() } += fs
                    }
                }
            }
            val rules = ArrayList<Rule>()
            val keys = uses.keys.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            for (key in keys) {
                val (jblock, png) = key
                fusionRule(png, metas.getValue(png), listOf(jblock), emptyList(), uses.getValue(key))?.let {
                    rules += it
                    claimed += png
                }
            }
            // texture-driven: Fusion textures that replace a vanilla texture no pack model points at
            for ((png, meta) in metas.entries.sortedBy { it.key }) {
                val name = Regex("assets/minecraft/textures/block/([a-z0-9_]+)\\.png").matchEntire(png)?.groupValues?.get(1)
                if (png in claimed || name == null) continue
                fusionRule(png, meta, emptyList(), listOf(name), FACE_NAMES.toSet())?.let {
                    rules += it
                    claimed += png
                }
            }
            return rules
        }

        fun fusionRule(png: String, meta: JsonObject, blocks: List<String>, textures: List<String>, faces: Set<String>): Rule? {
            val f = meta.getValue("fusion") as JsonObject
            val kind = Fusion.type(f)
            val im = try {
                decodeFull(pack.get(png)!!)
            } catch (e: Exception) {
                log += "skip $png: unreadable image (${e.message})"
                return null
            }
            val r = Rule(png, emptyMap(), "", blocks, faces, textures = textures, source = "fusion")
            when (kind) {
                "connecting" -> {
                    val layout = f["layout"].text("full").lowercase()
                    if (layout !in Fusion.GRID || layout == "overlay") {
                        log += "skip $png: Fusion '$layout' layout is not supported"
                        return null
                    }
                    val (gw, gh) = Fusion.GRID.getValue(layout)
                    val tiles = Fusion.grid(im, gw, gh, meta, layout == "full")
                    if (tiles == null) {
                        log += "skip $png: image size doesn't fit the '$layout' layout"
                        return null
                    }
                    r.method = "fusion"; r.layout = layout; r.tiles = tiles
                    val conn = f["connections"]
                    if (conn != null && !Fusion.sameBlockPredicate(conn)) {
                        log += "note $png: Fusion connection rules are treated as 'is_same_block'"
                    }
                }
                "random", "continuous" -> {
                    val rows = asInt(f["rows"]).takeIf { it != null && it != 0 } ?: 1
                    val cols = asInt(f["columns"]).takeIf { it != null && it != 0 } ?: 1
                    if (rows <= 0 || cols <= 0) return null
                    var tiles = Fusion.grid(im, cols, rows, meta, false)
                    if (tiles == null) {
                        log += "skip $png: image size doesn't fit ${cols}x$rows tiles"
                        return null
                    }
                    if (kind == "random") {
                        tiles = tiles.filterIndexed { i, t -> i == 0 || !Fusion.empty(t.first) }
                        r.method = "random"; r.tiles = tiles; r.weights = List(tiles.size) { 1 }
                    } else {
                        r.method = "repeat"; r.tiles = tiles; r.width = cols; r.height = rows
                    }
                }
                else -> return null
            }
            if (f["sub_texture"] is JsonObject) log += "note $png: Fusion sub_texture is ignored"
            return r
        }

        // -------------------------------------------------------------- layouts and targets

        fun ruleLayout(rule: Rule): Layout? {
            val m = rule.method
            val t = rule.tiles
            val ident: (List<Img>) -> List<Img> = { it }
            return when (m) {
                "fusion" -> {
                    val lay = rule.layout!!
                    val idx = Fusion.TO_47[lay]
                    when {
                        idx != null -> Layout("ctm", t, { ims -> idx.map { ims[it] } }, 8, 47)
                        lay == "pieced" -> Layout("ctm", t.take(5), ::compactTo47, 8, 47)
                        else -> Layout(lay, Fusion.LINE.getValue(lay).map { t[it] }, ident, 4, 4)
                    }
                }
                "ctm" -> {
                    if (t.size < 47) {
                        log += "skip ${rule.path}: ctm needs 47 tiles, found ${t.size}"
                        null
                    } else Layout("ctm", t.take(47), ident, 8, 47)
                }
                "ctm_compact" -> {
                    if (t.size < 5) {
                        log += "skip ${rule.path}: ctm_compact needs 5 tiles"
                        null
                    } else {
                        if (rule.props.keys.any { it.startsWith("ctm.") }) log += "note ${rule.path}: ctm.N replacement tiles are ignored"
                        Layout("ctm", t.take(5), ::compactTo47, 8, 47)
                    }
                }
                "horizontal", "vertical" -> if (t.size >= 4) Layout(m, t.take(4), ident, 4, 4) else {
                    log += "skip ${rule.path}: $m needs 4 tiles, found ${t.size}"
                    null
                }
                "hv", "vh" -> if (t.size >= 7) Layout(m, t.take(7), ident, 7, 7) else {
                    log += "skip ${rule.path}: ${rule.props["method"]} needs 7 tiles, found ${t.size}"
                    null
                }
                "top" -> {
                    val base = rule.base
                    if (base == null) {
                        log += "skip ${rule.path}: 'top' needs the block's own texture in the pack"
                        null
                    } else Layout("top", listOf(base, t[0]), ident, 2, 2)
                }
                "fixed" -> Layout("fixed", t.take(1), ident, 1, 1)
                "repeat" -> {
                    val n = rule.width.toLong() * rule.height
                    if (rule.width > 0 && rule.height > 0 && t.size >= n) Layout("repeat", t.take(n.toInt()), ident, rule.width, n.toInt()) else {
                        log += "skip ${rule.path}: repeat needs width x height (${rule.width} x ${rule.height}) tiles, found ${t.size}"
                        null
                    }
                }
                "random" -> {
                    var g = 0
                    for (w in rule.weights) g = gcd(g, w)
                    var counts = rule.weights.map { maxOf(1L, it.toLong() / maxOf(g, 1)) }
                    if (counts.sum() > 64) {
                        log += "note ${rule.path}: random weights too fine, using equal weights"
                        counts = List(t.size) { 1L }
                    }
                    val n = counts.sum().toInt()
                    val expand: (List<Img>) -> List<Img> = { ims ->
                        val out = ArrayList<Img>()
                        for ((i, im) in ims.withIndex()) repeat(counts[i].toInt()) { out += im }
                        out
                    }
                    Layout("random", t, expand, minOf(8, n), n)
                }
                "overlay" -> if (t.size >= 17) Layout("overlay", t.take(17), ident, 6, 17, true) else {
                    log += "skip ${rule.path}: overlay needs 17 tiles, found ${t.size}"
                    null
                }
                "overlay_ctm" -> if (t.size >= 47) Layout("ctm", t.take(47), ident, 8, 47, true) else {
                    log += "skip ${rule.path}: overlay_ctm needs 47 tiles, found ${t.size}"
                    null
                }
                "overlay_fixed" -> Layout("fixed", t.take(1), ident, 1, 1, true)
                "overlay_repeat" -> {
                    val n = rule.width.toLong() * rule.height
                    if (rule.width > 0 && rule.height > 0 && t.size >= n) Layout("repeat", t.take(n.toInt()), ident, rule.width, n.toInt(), true) else {
                        log += "skip ${rule.path}: overlay_repeat needs width x height (${rule.width} x ${rule.height}) tiles, found ${t.size}"
                        null
                    }
                }
                "overlay_random" -> {
                    var g = 0
                    for (w in rule.weights) g = gcd(g, w)
                    var counts = rule.weights.map { maxOf(1L, it.toLong() / maxOf(g, 1)) }
                    if (counts.sum() > 64) {
                        log += "note ${rule.path}: random weights too fine, using equal weights"
                        counts = List(t.size) { 1L }
                    }
                    val n = counts.sum().toInt()
                    val expand: (List<Img>) -> List<Img> = { ims ->
                        val out = ArrayList<Img>()
                        for ((i, im) in ims.withIndex()) repeat(counts[i].toInt()) { out += im }
                        out
                    }
                    Layout("random", t, expand, minOf(8, n), n, true)
                }
                else -> {
                    log += "skip ${rule.path}: unknown method '$m'"
                    null
                }
            }
        }

        fun ruleTargets(rule: Rule): List<Target> {
            val out = ArrayList<Target>()
            for (jblock in rule.blocks) {
                val block = Bedrock.block(jblock)
                if (block == null) {
                    log += "skip ${rule.path}: '$jblock' has no Bedrock blocks.json entry"
                    continue
                }
                val faces = if (rule.source == "fusion") rule.faces.toSet()
                else if (isPane(block) && rule.faces.any { it in SIDES }) setOf("down", "up", "north", "south", "west")
                else rule.faces.filter { !(isPane(block) && it == "east") }.toSet()
                out += Target(block, faces, null, mainJavaTexture(jblock))
            }
            for (name in rule.textures) {
                val uses = Bedrock.javaTexturePath(name)?.let { Bedrock.pathUses[it] } ?: emptyList()
                if (uses.isEmpty()) {
                    log += "skip ${rule.path}: texture '$name' is not used by any Bedrock block"
                    continue
                }
                val byBlock = LinkedHashMap<Pair<String, Int?>, MutableSet<String>>()
                for (u in uses) {
                    if (u.face !in rule.faces) continue
                    val n = Bedrock.aliasEntries(u.alias)?.size ?: 0
                    byBlock.getOrPut(u.block to (if (n > 1) u.index else null)) { LinkedHashSet() } += u.face
                }
                for ((key, faces) in byBlock) out += Target(key.first, faces, key.second, name)
            }
            return out
        }

        fun png(img: Img): ByteArray = Png.encode(img, opt.pngLevel)

        fun run(progress: (Int, Int) -> Unit): ConversionResult {
            val meta = (parseJson(pack.get("pack.mcmeta")) as? JsonObject)?.get("pack") as? JsonObject
            val display = opt.name ?: displayNameFor(opt.sourceName)
            val slug = opt.slug ?: slugify(display)
            val rules = readRules() + fusionRules()

            val files = HashMap<String, ByteArray>()
            val terrain = LinkedHashMap<String, JsonElement>()
            val arrays = HashMap<String, MutableList<JsonElement>>() // array aliases, filled in place
            val blocksJson = LinkedHashMap<String, JsonElement>()
            blocksJson["format_version"] = JsonPrimitive("1.21.120")
            val entries = HashMap<String, LinkedHashMap<String, JsonElement>>()
            val flipbooks = ArrayList<JsonElement>()
            data class BaseAnimation(val oldAlias: String, val oldIndex: Int, val alias: String, val index: Int?,
                                     val oldPath: String, val path: String)
            val baseAnimations = ArrayList<BaseAnimation>()
            val arrayAliases = HashMap<Pair<String, String?>, String>()
            val ruleCount = HashMap<String, Int>()
            val blocks = LinkedHashMap<String, String>()
            val methods = LinkedHashMap<String, Int>()
            val sheets = ArrayList<BlockSheet>()
            val animatedList = ArrayList<String>()

            // Pass 1: build every rule's sheet and collect the sheets that apply to each
            // (block, face). Keep conditional rules plus one unconditional fallback; overlays stack.
            val blockFaces: MutableMap<Pair<String, Int?>, MutableMap<String, MutableList<SheetEntry>>> = LinkedHashMap()
            for ((ri, rule) in rules.withIndex()) {
                progress(ri, rules.size)
                val layout = try {
                    ruleLayout(rule)
                } catch (e: Exception) {
                    log += "skip ${rule.path}: unreadable tile image (${e.message})"
                    null
                } ?: continue
                val targets = ruleTargets(rule)
                if (targets.isEmpty()) continue
                var animated = layout.inputs.any { it.animated }
                if (animated && layout.overlay) {
                    log += "note ${rule.path}: overlay animations are not supported, using the first frame"
                    animated = false
                }
                var timeline: Timeline? = null
                var frameSheets: List<Img> = emptyList()
                var n = 0
                if (animated) {
                    val tl = Anim.mergeTimeline(layout.inputs)
                    n = isqrt(layout.count - 1) + 1
                    frameSheets = tl.states.map { s -> buildSheet(layout.build(s.indices.map { layout.inputs[it].frames[s[it]] }), n, n).first }
                    timeline = tl
                    if (frameSheets[0].height.toLong() * frameSheets.size > Anim.MAX_STRIP_HEIGHT || layout.method == "repeat") {
                        log += "note ${rule.path}: animation too large for one flipbook, using the first frame"
                        animated = false
                    }
                }
                val sheet: Img
                val cols: Int
                val rows: Int
                val tile0: Img
                if (animated) {
                    sheet = frameSheets[0]; cols = n; rows = n
                    tile0 = layout.build(layout.inputs.mapIndexed { i, t -> t.frames[timeline!!.states[0][i]] })[0]
                } else {
                    val built = layout.build(layout.inputs.map { it.first })
                    val b = buildSheet(built, layout.cols)
                    sheet = b.first; cols = b.second; rows = b.third
                    tile0 = built[0]
                }
                val spec = "${layout.method}-${cols}x$rows-${layout.count}" + (if (rule.innerSeams) "-s" else "") + (if (layout.overlay) "-o" else "")
                var sheetPng: ByteArray? = null
                var stripPath: String? = null
                val contributed = HashSet<String>()
                for (tg in targets) {
                    val block = tg.block
                    val nth = ruleCount[block] ?: 0
                    ruleCount[block] = nth + 1
                    val stem = "${slug}_$block" + if (nth > 0) "_${nth + 1}" else ""
                    val sheetPath = "textures/blocks/ctm/$spec/${rule.conditions.path}$stem"
                    val basePath = "textures/blocks/ctm/base/$stem"
                    files["$sheetPath.png"] = sheetPng ?: png(sheet).also { sheetPng = it }
                    var baseTex: Tex? = null
                    if (tg.texture != null) {
                        val bp = "assets/minecraft/textures/block/${tg.texture}.png"
                        if (has(bp) && bp !in claimed) baseTex = runCatching { loadTex(bp) }.getOrNull() // not a Fusion layout image
                    }
                    val baseImg = baseTex?.first ?: tile0
                    if (animated && stripPath == null) {
                        // parseable like the sheet itself, in case the game reports the flipbook's path
                        stripPath = "textures/blocks/ctm/$spec/${rule.conditions.path}anim/$stem"
                        files["$stripPath.png"] = png(Anim.stack(frameSheets))
                    }
                    if (opt.direct && layout.overlay) {
                        log += "note ${rule.path}: overlay rules are not supported in direct mode"
                        continue
                    }
                    val sheetEntry = SheetEntry(layout, spec, sheetPath, basePath, baseImg, tile0, baseTex, animated, stripPath, timeline, rule.conditions)
                    val faces = blockFaces.getOrPut(block to tg.index) { LinkedHashMap() }
                    for (f in tg.faces) {
                        val lst = faces.getOrPut(f) { ArrayList() }
                        if (!layout.overlay && !rule.conditions.conditional && lst.any { !it.layout.overlay && !it.conditions.conditional }) {
                            log += "note ${rule.path}: $block $f already covered by an earlier rule"
                            continue
                        }
                        if (opt.direct && lst.isNotEmpty()) continue // direct mode has no variations list to merge into
                        lst += sheetEntry
                        contributed += block
                    }
                }
                val key = if (rule.source == "fusion" && rule.layout != null) "fusion:${rule.layout}" else rule.method
                methods[key] = (methods[key] ?: 0) + contributed.size
                for (block in contributed.sorted()) {
                    if (animated && block !in animatedList) animatedList += block
                    blocks[block] = spec
                }
            }

            // Pass 2: one alias per block and face set, merging all the sheets that apply.
            for ((key, faces) in blockFaces) {
                val (block, index) = key
                val groups = LinkedHashMap<Pair<List<String>, String>, MutableList<String>>()
                for ((f, entries) in faces) {
                    // Faces with different vanilla textures need their own base, so an
                    // overlay-only block (e.g. grass) still shows each face's own texture.
                    val baseKey = entries.firstOrNull { !it.layout.overlay && !it.conditions.conditional }?.basePath
                        ?: Bedrock.aliasEntries(Bedrock.faceAliases(Vanilla.blocks.getValue(block) as JsonObject)[f])
                            ?.getOrNull(index ?: 0)?.let { Bedrock.entryPaths(it).firstOrNull() } ?: ""
                    groups.getOrPut(entries.map { it.sheetPath } to baseKey) { ArrayList() } += f
                }
                val vanilla = Vanilla.blocks.getValue(block) as JsonObject
                val entry = entries[block] ?: LinkedHashMap(vanilla)
                val tex: LinkedHashMap<String, JsonElement> = when (val t = entry["textures"]) {
                    is JsonPrimitive -> LinkedHashMap<String, JsonElement>().also { m -> FACE_NAMES.forEach { m[it] = t } }
                    is JsonObject -> LinkedHashMap(t)
                    else -> {
                        log += "skip: '$block' has no textures in blocks.json"
                        continue
                    }
                }
                // Remove generic side before assigning individual faces; otherwise it
                // can restore the vanilla alias when Bedrock loads blocks.json.
                tex.remove("side")?.let { side -> Bedrock.SIDES.forEach { tex.putIfAbsent(it, side) } }
                if ("carried_textures" !in entry) entry["carried_textures"] = vanilla["textures"] ?: JsonNull
                if (faces.values.any { lst -> lst.any { it.layout.overlay } }) {
                    // Overlay quads are cutout, not opaque: without alpha testing their
                    // transparent pixels paint black over the base texture.
                    entry["render_layer"] = JsonPrimitive("alpha_test")
                }
                for ((gi, group) in groups.entries.withIndex()) {
                    val (paths, baseKey) = group.key
                    val groupFaces = group.value
                    val entries2 = faces.getValue(groupFaces[0])
                    var alias = "ctm_${slug}_$block" + (if (index != null) "_$index" else "") + (if (gi > 0) "_$gi" else "")
                    val normal = entries2.firstOrNull { !it.layout.overlay && !it.conditions.conditional }
                    val value: JsonElement = if (opt.direct) { // fallback layout: the block's texture is the sheet itself
                        JsonPrimitive(entries2[0].sheetPath)
                    } else {
                        val baseValue = if (normal != null) {
                            files["${normal.basePath}.png"] = png(normal.baseImg.resizeNearest(normal.tile0.width, normal.tile0.height))
                            normal.basePath
                        } else {
                            // Vanilla paths alone are pruned by Bedrock's pack-stack
                            // filtering. Copy the fallback after plain overrides below.
                            if (baseKey.isEmpty()) "" else
                                "textures/blocks/ctm/fallback/$slug/${baseKey.removePrefix("textures/")}"
                        }
                        if (baseValue.isEmpty()) {
                            log += "skip: '$block' has no vanilla base texture"
                            continue
                        }
                        JsonObject(mapOf("variations" to JsonArray(listOf(
                            JsonObject(mapOf("path" to JsonPrimitive(baseValue), "weight" to JsonPrimitive(1000000))),
                        ) + paths.map { JsonObject(mapOf("path" to JsonPrimitive(it), "weight" to JsonPrimitive(0.05))) })))
                    }
                    val vanillaAlias = Bedrock.faceAliases(vanilla)[groupFaces.min()]
                    val aEntries = if (index != null) Bedrock.aliasEntries(vanillaAlias) else null
                    val array = aEntries != null && aEntries.size > 1
                    val shared = arrayAliases[block to vanillaAlias]
                    if (array && shared != null) {
                        // another entry of the same array alias was converted already: share its alias
                        alias = shared
                        arrays.getValue(alias)[index!!] = value
                    } else if (array) {
                        val arr = aEntries!!.toMutableList()
                        arr[index!!] = value
                        arrays[alias] = arr
                        terrain[alias] = JsonNull // filled in below, once every entry is known
                        arrayAliases[block to vanillaAlias] = alias
                    } else {
                        terrain[alias] = JsonObject(mapOf("textures" to value))
                    }
                    if (!opt.direct && normal == null && vanillaAlias != null)
                        baseAnimations += BaseAnimation(vanillaAlias, index ?: 0, alias, if (array) index else null,
                            baseKey, "textures/blocks/ctm/fallback/$slug/${baseKey.removePrefix("textures/")}")
                    for ((pos, e) in entries2.withIndex()) {
                        sheets += BlockSheet(block, e.spec, e.sheetPath, FACE_NAMES.filter { it in groupFaces })
                        if (!e.animated) continue
                        val tl = e.timeline!!
                        val idx = if (array) index else null
                        if (!opt.direct && e === normal && e.baseTex != null && e.baseTex.animated) {
                            val bt = Anim.mergeTimeline(listOf(e.baseTex))
                            val bstrip = "textures/blocks/ctm/base/anim/${slug}_$block"
                            files["$bstrip.png"] = png(Anim.stack(bt.states.map { e.baseTex.frames[it[0]].resizeNearest(e.tile0.width, e.tile0.height) }))
                            flipbooks += Anim.flipbookEntry(bstrip, alias, bt, idx, 0)
                        }
                        // the sheet goes last: Bedrock may only play the last entry of one atlas tile
                        flipbooks += Anim.flipbookEntry(e.stripPath!!, alias, tl, idx, if (opt.direct) null else 1 + pos)
                    }
                    for (f in groupFaces) tex[f] = JsonPrimitive(alias)
                }
                entry["textures"] = if (tex.values.toSet().size > 1) JsonObject(tex) else tex.values.first()
                entry.remove("isotropic") // random texture rotation would scramble connected tiles
                entries[block] = entry
                blocksJson[block] = JsonObject(entry)
            }
            progress(rules.size, rules.size)
            for ((alias, arr) in arrays) terrain[alias] = JsonObject(mapOf("textures" to JsonArray(arr)))

            // Bedrock texture paths of converted blocks (for the plain texture copies below)
            val convertedPaths = HashSet<String>()
            for (block in blocks.keys) {
                for (alias in Bedrock.faceAliases(Vanilla.blocks.getValue(block) as JsonObject).values) {
                    Bedrock.aliasEntries(alias)?.forEach { convertedPaths += Bedrock.entryPaths(it) }
                }
            }

            // plain texture overrides: animated block textures get a flipbook; static ones are copied
            // for converted blocks (items, particles) and the glass family
            val animatedPaths = HashSet<String>()
            for (p in pack.files.keys.sorted()) {
                val m = BLOCK_TEXTURE.matchEntire(p) ?: continue
                if (p in claimed) continue
                val name = m.groupValues[2]
                val target = if (m.groupValues[1] == "blocks") { // 1.12 and older packs already use (most) Bedrock names
                    // (1.12 water_still/water_flow keep their blue, untinted Bedrock twins: the grey ones are tinted)
                    "textures/blocks/$name".takeIf { it in Bedrock.pathTiles }
                        ?: Bedrock.javaTexturePath(LEGACY_TEXTURES[name] ?: name)
                } else Bedrock.javaTexturePath(name)
                if (target == null) continue
                val t = try {
                    loadTex(p)
                } catch (e: Exception) {
                    log += "note $p: unreadable image, not copied (${e.message})"
                    continue
                } ?: continue
                if (t.animated) {
                    val tiles = Bedrock.pathTiles[target] ?: emptyList()
                    if (tiles.isEmpty()) continue
                    val tl = Anim.plainTimeline(t)
                    if (tl == null) {
                        log += "note $p: animation too large for one flipbook, not converted"
                        continue
                    }
                    val images = ArrayList<String>()
                    for ((alias, i) in tiles) {
                        val v = Bedrock.vanillaFlipbooks[alias to i]
                        val image: String
                        val index: Int?
                        val variant: Int?
                        if (v != null) { // a tile vanilla animates: same image and atlas fields as vanilla
                            image = v["flipbook_texture"].str() ?: target
                            index = asInt(v["atlas_index"])
                            variant = asInt(v["atlas_tile_variant"])
                        } else { // array tiles name their index twice, as vanilla's own array flipbooks do
                            val many = (Bedrock.aliasEntries(alias)?.size ?: 0) > 1
                            image = target
                            index = if (many) i else null
                            variant = if (many) i else null
                        }
                        flipbooks += Anim.flipbookEntry(image, alias, tl, index, variant, v?.get("replicate"))
                        if (image !in images) images += image
                    }
                    val strip = png(Anim.stack(tl.states.map { t.frames[it[0]] }))
                    for (image in images) files["$image.png"] = strip
                    // the atlas keeps a still copy, as vanilla's command blocks do
                    if (target !in images) files["$target.png"] = png(t.first)
                    animatedPaths += target
                    animatedList += name
                } else if (javaToBedrockTexture(name) != null || target in convertedPaths) {
                    files["$target.png"] = png(t.first)
                }
            }

            if (blocks.isEmpty() && animatedPaths.isEmpty()) throw ConversionException("No connected or animated textures found", log)

            files["textures/terrain_texture.json"] = pretty(JsonObject(linkedMapOf(
                "resource_pack_name" to JsonPrimitive(slug), "texture_name" to JsonPrimitive("atlas.terrain"),
                "padding" to JsonPrimitive(8), "num_mip_levels" to JsonPrimitive(4), "texture_data" to JsonObject(terrain),
            )))
            files["blocks.json"] = pretty(Bedrock.preserveSandstoneVariants(blocksJson))
            // Both variations must live in this pack, or Bedrock removes the lower
            // priority fallback and clamps the sheet's animation onto slot zero.
            fun fallbackImage(path: String) = files["$path.png"] ?: VanillaImages.png(path)
            val baseFlips = baseAnimations.mapNotNull { base ->
                val source = flipbooks.filterIsInstance<JsonObject>().firstOrNull {
                    it["atlas_tile"].str() == base.oldAlias &&
                        (asInt(it["atlas_index"]) ?: 0) == base.oldIndex
                } ?: if ("${base.oldPath}.png" !in files) Bedrock.vanillaFlipbooks[base.oldAlias to base.oldIndex] else null
                var image = Png.decode(fallbackImage(base.oldPath))
                val stripPath = base.path + "_animation"
                if (source != null) {
                    files["$stripPath.png"] = fallbackImage(source.getValue("flipbook_texture").str()!!)
                    if (image.height > image.width) image = image.crop(0, 0, image.width, image.width)
                }
                files["${base.path}.png"] = png(image)
                if (source == null) return@mapNotNull null
                val f = LinkedHashMap(source)
                f["flipbook_texture"] = JsonPrimitive(stripPath)
                f["atlas_tile"] = JsonPrimitive(base.alias)
                f["atlas_tile_variant"] = JsonPrimitive(0)
                f.remove("atlas_index")
                base.index?.let { f["atlas_index"] = JsonPrimitive(it) }
                JsonObject(f)
            }.distinct()
            if (flipbooks.isNotEmpty() || baseFlips.isNotEmpty())
                files["textures/flipbook_textures.json"] = pretty(JsonArray(baseFlips + flipbooks))
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
                "metadata" to JsonObject(mapOf("product_type" to JsonPrimitive("addon"))),
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
                log.toList(), sheets, animatedList.toList(),
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

    private fun isqrt(n: Int): Int {
        var r = kotlin.math.sqrt(n.toDouble()).toInt()
        while (r * r > n) r--
        while ((r + 1) * (r + 1) <= n) r++
        return r
    }

    /** Decodes an image (RGBA, every frame). */
    fun decodeFull(bytes: ByteArray): Img =
        if (Png.isPng(bytes)) Png.decode(bytes) else fallbackDecoder?.invoke(bytes) ?: throw Png.PngException("not a PNG image")

    /** Decodes an image the way the Python converter's load_image does (RGBA, first frame). */
    fun decode(bytes: ByteArray): Img = decodeFull(bytes).firstFrame()

    /** Builds the 47 standard tiles from the 5 ctm_compact tiles (OptiFine/Continuity rules). */
    fun compactTo47(compact: List<Img>): List<Img> {
        val size = compact.maxOf { it.width }
        val c = compact.map { it.resizeNearest(size, size) }
        val h = size / 2
        // quadrant q: 0=top-left, 1=bottom-left, 2=bottom-right, 3=top-right; dirs [L, D, R, U]
        val boxes = arrayOf(intArrayOf(0, 0, h, h), intArrayOf(0, h, h, size), intArrayOf(h, h, size, size), intArrayOf(h, 0, size, h))
        return List(47) { idx ->
            val s = Fusion.CTM47_REP[idx]
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
    fun buildSheet(tiles: List<Img>, cols: Int, rows0: Int? = null): Triple<Img, Int, Int> {
        val size = tiles.maxOf { maxOf(it.width, it.height) }
        val rows = rows0 ?: ((tiles.size + cols - 1) / cols)
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
    fun countRules(pack: JavaPack): Int = pack.files.keys.count { it.endsWith(".properties") && RULE_PATH.matches(it) }
}
