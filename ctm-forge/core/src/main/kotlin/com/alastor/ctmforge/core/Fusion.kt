package com.alastor.ctmforge.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fusion (SuperMartijn642) connecting, random and continuous textures, and the block models
 * that point at them. Layout tile positions follow Fusion's layout handlers; every layout
 * that depends on the 8 neighbours is expanded to the standard 47 CTM tiles.
 */
object Fusion {
    val GRID = mapOf(
        "full" to (8 to 6), "simple" to (4 to 4), "compact" to (5 to 1), "pieced" to (5 to 1),
        "horizontal" to (4 to 1), "vertical" to (1 to 4), "overlay" to (6 to 3),
    )

    fun type(f: JsonObject): String {
        val t = f["type"].text("").lowercase()
        return t.removePrefix("fusion:")
    }

    /** CTM47 neighbour bits -> (top, topRight, right, bottomRight, bottom, bottomLeft, left, topLeft). */
    private class Flags(s: Int) {
        val t = s and 64 != 0; val tr = s and 32 != 0; val r = s and 16 != 0; val br = s and 8 != 0
        val b = s and 4 != 0; val bl = s and 2 != 0; val l = s and 1 != 0; val tl = s and 128 != 0
    }

    private fun full(s: Int): Pair<Int, Int> {
        val c = Flags(s)
        val t = c.t; val tr = c.tr; val r = c.r; val br = c.br; val b = c.b; val bl = c.bl; val l = c.l; val tl = c.tl
        return when {
            !l && !t && !r && !b -> 0 to 0
            l && !t && !r && !b -> 3 to 0
            !l && t && !r && !b -> 0 to 3
            !l && !t && r && !b -> 1 to 0
            !l && !t && !r && b -> 0 to 1
            l && !t && r && !b -> 2 to 0
            !l && t && !r && b -> 0 to 2
            l && t && !r && !b -> if (tl) 3 to 3 else 5 to 1
            !l && t && r && !b -> if (tr) 1 to 3 else 4 to 1
            !l && !t && r && b -> if (br) 1 to 1 else 4 to 0
            l && !t && !r && b -> if (bl) 3 to 1 else 5 to 0
            !l -> if (tr && br) 1 to 2 else if (tr) 4 to 2 else if (br) 6 to 2 else 6 to 0
            !t -> if (bl && br) 2 to 1 else if (bl) 7 to 2 else if (br) 5 to 2 else 7 to 0
            !r -> if (tl && bl) 3 to 2 else if (tl) 7 to 3 else if (bl) 5 to 3 else 7 to 1
            !b -> if (tl && tr) 2 to 3 else if (tl) 4 to 3 else if (tr) 6 to 3 else 6 to 1
            tl && tr && bl && br -> 2 to 2
            !tl && tr && bl && br -> 7 to 5
            tl && !tr && bl && br -> 6 to 5
            tl && tr && !bl && br -> 7 to 4
            tl && tr && bl && !br -> 6 to 4
            !tl && tr && !br && bl -> 0 to 4
            tl && !tr && br && !bl -> 0 to 5
            !tl && !tr && br && bl -> 3 to 4
            tl && !tr && !br && bl -> 3 to 5
            tl && tr && !br && !bl -> 2 to 5
            !tl && tr && br && !bl -> 2 to 4
            tl -> 5 to 5
            tr -> 4 to 5
            br -> 4 to 4
            bl -> 5 to 4
            else -> 1 to 4
        }
    }

    private fun simple(s: Int): Pair<Int, Int> {
        val c = Flags(s)
        val l = c.l; val t = c.t; val r = c.r; val b = c.b
        if (!(l || t || r || b)) return 0 to 0
        val one = mapOf(
            listOf(true, false, false, false) to (3 to 0), listOf(false, true, false, false) to (3 to 1),
            listOf(false, false, true, false) to (2 to 1), listOf(false, false, false, true) to (2 to 0),
            listOf(true, false, true, false) to (0 to 1), listOf(false, true, false, true) to (1 to 1),
            listOf(true, true, false, false) to (3 to 3), listOf(false, true, true, false) to (2 to 3),
            listOf(false, false, true, true) to (2 to 2), listOf(true, false, false, true) to (3 to 2),
        )
        one[listOf(l, t, r, b)]?.let { return it }
        return when {
            !l -> 0 to 2
            !t -> 1 to 2
            !r -> 1 to 3
            !b -> 0 to 3
            else -> 1 to 0
        }
    }

    private fun compact(s: Int): Pair<Int, Int> {
        val c = Flags(s)
        val sides = listOf(c.l, c.t, c.r, c.b).count { it }
        if (sides <= 1) return 0 to 0
        if (sides == 2) return if (c.l && c.r) 3 to 0 else if (c.t && c.b) 2 to 0 else 0 to 0
        if (sides == 3) {
            if (c.l && c.r) return if ((c.tl && c.t && c.tr) || (c.bl && c.b && c.br)) 3 to 0 else 0 to 0
            return if ((c.tl && c.l && c.bl) || (c.tr && c.r && c.br)) 2 to 0 else 0 to 0
        }
        if (c.tl && c.tr && c.bl && c.br) return 1 to 0
        if (!c.tl && !c.tr && !c.bl && !c.br) return 4 to 0
        return 0 to 0
    }

    /** Lowest neighbour state of every CTM47 class. */
    val CTM47_REP: IntArray by lazy { IntArray(47) { c -> Ctm.CTM47.indexOf(c) } }

    private fun layout47(fn: (Int) -> Pair<Int, Int>, gridW: Int) = List(47) { c ->
        val (x, y) = fn(CTM47_REP[c]); x + y * gridW
    }

    val TO_47: Map<String, List<Int>> by lazy {
        mapOf("full" to layout47(::full, 8), "simple" to layout47(::simple, 4), "compact" to layout47(::compact, 5))
    }

    /** OptiFine tile order of the 4-tile methods: [one neighbour after, both, one neighbour before, none]. */
    val LINE = mapOf("horizontal" to listOf(1, 2, 3, 0), "vertical" to listOf(3, 2, 1, 0))

    fun empty(im: Img): Boolean = im.px.all { (it ushr 24) == 0 }

    /** Fusion texture -> Tex per grid tile (row-major), honouring animation. */
    fun grid(im0: Img, gw: Int, gh: Int, meta: JsonObject, legacyFull: Boolean): List<Tex>? {
        var im = im0
        val anim = meta["animation"] as? JsonObject
        val f = meta["fusion"] as? JsonObject ?: JsonObject(emptyMap())
        if (legacyFull && anim == null && im.width == im.height) im = im.crop(0, 0, im.width, im.height * 6 / 8)
        if (anim == null) {
            if (im.width % gw != 0 || im.height % gh != 0) return null
            val tw = im.width / gw
            val th = im.height / gh
            if (tw <= 0 || th <= 0) return null
            val out = ArrayList<Tex>()
            for (y in 0 until gh) for (x in 0 until gw) {
                val t = im.crop(x * tw, y * th, (x + 1) * tw, (y + 1) * th)
                out += Tex(listOf(if (tw == th) t else t.resizeNearest(tw, tw)))
            }
            return out
        }
        if (isTrue(f["per_tile_animation"])) {
            if (im.width % gw != 0 || im.height % gh != 0) return null
            val cw = im.width / gw
            val ch = im.height / gh
            val out = ArrayList<Tex>()
            for (y in 0 until gh) for (x in 0 until gw) {
                val cell = im.crop(x * cw, y * ch, (x + 1) * cw, (y + 1) * ch)
                out += Anim.animatedTex(cell, anim) ?: return null
            }
            return out
        }
        val ts = minOf(im.width / gw, im.height / gh)
        val fw = asInt(anim["width"]).let { if (it != null && it > 0) it else gw * ts }
        val fh = asInt(anim["height"]).let { if (it != null && it > 0) it else gh * ts }
        if (ts <= 0 || fw % gw != 0 || fh % gh != 0 || im.width % fw != 0 || im.height % fh != 0) return null
        val tw = fw / gw
        val th = fh / gh
        val cols = im.width / fw
        val count = cols * (im.height / fh)
        val timing = Anim.timing(anim, count)
        val interp = isTrue(anim["interpolate"])
        val out = ArrayList<Tex>()
        for (y in 0 until gh) for (x in 0 until gw) {
            val frames = List(count) { i ->
                val ox = (i % cols) * fw
                val oy = (i / cols) * fh
                val t = im.crop(ox + x * tw, oy + y * th, ox + (x + 1) * tw, oy + (y + 1) * th)
                if (tw == th) t else t.resizeNearest(tw, tw)
            }
            out += Tex(frames, timing, interp)
        }
        return out
    }

    // ------------------------------------------------------------------ models

    /** Model texture reference -> "namespace:path" (no #, no extension). */
    fun texRef(ref0: JsonElement?, textures: Map<String, JsonElement>): String? {
        var ref = ref0
        var resolved: String? = null
        for (i in 0 until 16) {
            if (ref is JsonObject) ref = ref["sprite"]
            val s = ref.str()
            if (s.isNullOrEmpty()) return null
            if (!s.startsWith("#")) { resolved = s; break }
            ref = textures[s.substring(1)]
        }
        val r = resolved ?: return null
        return if (':' in r) r else "minecraft:$r"
    }

    fun texFile(ref: String): String = "assets/${ref.substringBefore(':')}/textures/${ref.substringAfter(':')}.png"

    fun normModel(ref: String): String {
        val ns = if (':' in ref) ref.substringBefore(':') else "minecraft"
        var path = if (':' in ref) ref.substringAfter(':') else ref
        if ('/' !in path) path = "block/$path"
        return "$ns:$path"
    }

    private val ALL = Bedrock.FACES.associateWith { "#all" }
    private val COLUMN = mapOf("down" to "#end", "up" to "#end", "north" to "#side", "south" to "#side", "west" to "#side", "east" to "#side")
    private val BOTTOM_TOP = mapOf("down" to "#bottom", "up" to "#top", "north" to "#side", "south" to "#side", "west" to "#side", "east" to "#side")
    private val CUBE = Bedrock.FACES.associateWith { "#$it" }
    private val PANE = mapOf("pane" to "#pane")
    val TEMPLATE_FACES: Map<String, Map<String, String>> = mapOf(
        "cube" to CUBE, "cube_mirrored" to CUBE, "cube_directional" to CUBE,
        "cube_all" to ALL, "cube_mirrored_all" to ALL, "cube_north_west_mirrored_all" to ALL, "leaves" to ALL,
        "cube_column" to COLUMN, "cube_column_mirrored" to COLUMN, "cube_column_uv_locked_x" to COLUMN,
        "cube_column_uv_locked_y" to COLUMN, "cube_column_uv_locked_z" to COLUMN,
        "cube_column_horizontal" to mapOf("down" to "#side", "up" to "#side", "north" to "#end", "south" to "#end", "west" to "#side", "east" to "#side"),
        "cube_bottom_top" to BOTTOM_TOP, "slab" to BOTTOM_TOP, "slab_top" to BOTTOM_TOP, "stairs" to BOTTOM_TOP,
        "inner_stairs" to BOTTOM_TOP, "outer_stairs" to BOTTOM_TOP,
        "cube_top" to mapOf("down" to "#side", "up" to "#top", "north" to "#side", "south" to "#side", "west" to "#side", "east" to "#side"),
        "orientable" to mapOf("down" to "#top", "up" to "#top", "north" to "#front", "south" to "#side", "west" to "#side", "east" to "#side"),
        "orientable_with_bottom" to mapOf("down" to "#bottom", "up" to "#top", "north" to "#front", "south" to "#side", "west" to "#side", "east" to "#side"),
        "orientable_vertical" to mapOf("down" to "#side", "up" to "#front", "north" to "#side", "south" to "#side", "west" to "#side", "east" to "#side"),
        "template_glass_pane_post" to PANE, "template_glass_pane_side" to PANE, "template_glass_pane_side_alt" to PANE,
        "template_glass_pane_noside" to PANE, "template_glass_pane_noside_alt" to PANE,
    )

    /** Model elements -> face -> texture ref of the biggest element face in that direction. */
    fun elementFaces(elements: JsonArray): Map<String, JsonElement> {
        val best = LinkedHashMap<String, Pair<Double, JsonElement>>()
        for (el in elements) {
            if (el !is JsonObject) continue
            val a = el["from"] ?: JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(0), JsonPrimitive(0)))
            val b = el["to"] ?: JsonArray(listOf(JsonPrimitive(16), JsonPrimitive(16), JsonPrimitive(16)))
            val size = try {
                List(3) { i -> kotlin.math.abs(num((b as JsonArray)[i]) - num((a as JsonArray)[i])) }
            } catch (e: Exception) {
                continue
            }
            val area = mapOf(
                "down" to size[0] * size[2], "up" to size[0] * size[2], "north" to size[0] * size[1],
                "south" to size[0] * size[1], "west" to size[2] * size[1], "east" to size[2] * size[1],
            )
            val faces = el["faces"] as? JsonObject
            for (face in Bedrock.FACES) {
                val fd = faces?.get(face) as? JsonObject ?: continue
                val tex = fd["texture"] ?: continue
                if (area.getValue(face) > (best[face]?.first ?: -1.0)) best[face] = area.getValue(face) to tex
            }
        }
        return best.mapValues { it.value.second }
    }

    private fun num(e: JsonElement): Double {
        val p = e as JsonPrimitive
        if (p.content == "true") return 1.0 // float(True) in the reference converter
        if (p.content == "false") return 0.0
        return p.content.toDouble()
    }

    /** -> (face -> texture ref, textures) with parents applied; null when unknown. */
    fun resolveModel(pack: JavaPack, ref: String): Pair<Map<String, JsonElement>, Map<String, JsonElement>>? {
        val chain = ArrayList<JsonObject>()
        var cur = normModel(ref)
        var faces: Map<String, JsonElement>? = null
        for (i in 0 until 32) {
            val ns = cur.substringBefore(':')
            val path = cur.substringAfter(':')
            val m = parseJson(pack.get("assets/$ns/models/$path.json"))
            if (m is JsonObject) {
                chain += m
                val els = m["elements"]
                if (els is JsonArray) {
                    faces = elementFaces(els)
                    break
                }
                val parent = m["parent"].str()
                if (parent == null || parent.startsWith("builtin/")) break
                cur = normModel(parent)
                continue
            }
            if (ns == "minecraft" && path.startsWith("block/")) {
                val name = path.removePrefix("block/")
                val tmpl = TEMPLATE_FACES[name] ?: TEMPLATE_FACES[JavaNames.modelParents[name] ?: ""]
                if (tmpl != null) faces = tmpl.mapValues { JsonPrimitive(it.value) }
            }
            break
        }
        val f = faces ?: return null
        val textures = LinkedHashMap<String, JsonElement>()
        for (m in chain.asReversed()) (m["textures"] as? JsonObject)?.let { textures.putAll(it) }
        return f to textures
    }

    /** Default models of a blockstate file (the "axis=y" / first variant, or every multipart part). */
    fun blockstateModels(state: JsonElement?): List<String> {
        val out = ArrayList<String>()
        if (state !is JsonObject) return out
        val variants = state["variants"]
        if (variants is JsonObject && variants.isNotEmpty()) {
            val keys = variants.keys.toList()
            val key = keys.firstOrNull { it == "" || "axis=y" in it.split(",") } ?: keys[0]
            val v = variants.getValue(key)
            for (m in if (v is JsonArray) v.toList() else listOf(v)) {
                val model = (m as? JsonObject)?.get("model").str()
                if (model != null) { out += model; break }
            }
        }
        val parts = state["multipart"]
        if (parts is JsonArray) for (part in parts) {
            val a = (part as? JsonObject)?.get("apply")
            for (m in if (a is JsonArray) a.toList() else listOf(a)) {
                (m as? JsonObject)?.get("model").str()?.let { out += it }
            }
        }
        return out
    }

    fun sameBlockPredicate(p: JsonElement?): Boolean = when (p) {
        is JsonArray -> p.all { sameBlockPredicate(it) }
        is JsonObject -> {
            val t = p["type"].text("").lowercase().removePrefix("fusion:")
            if (t == "or" || t == "and") sameBlockPredicate(p["predicates"] ?: JsonArray(emptyList()))
            else t == "is_same_block" || t == "is_same_state"
        }
        else -> false
    }
}
