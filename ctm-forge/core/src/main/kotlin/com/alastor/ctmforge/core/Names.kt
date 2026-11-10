package com.alastor.ctmforge.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Bedrock 1.26.60 vanilla data: blocks.json entries, terrain_texture.json aliases and flipbooks. */
object Vanilla {
    private val root: JsonObject by lazy { resource("/vanilla_26.60.json") }
    val blocks: JsonObject get() = root["blocks"] as JsonObject
    val terrainTextures: JsonObject get() = root["terrain_textures"] as JsonObject
    /** Vanilla flipbook entries including timing for normal fallback animations. */
    val flipbooks: List<JsonObject> get() = (root["flipbooks"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
    val source: String get() = (root["source"] as? JsonPrimitive)?.content ?: ""

    internal fun resource(name: String): JsonObject {
        val stream = Vanilla::class.java.getResourceAsStream(name) ?: error("$name is missing from the build")
        return Json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
    }
}

/**
 * How Java Edition names map onto Bedrock ones (tools/build_java_map.py): block texture
 * names -> Bedrock texture paths, renamed block ids, and the template each vanilla block
 * model is built on.
 */
object JavaNames {
    private val root: JsonObject by lazy { Vanilla.resource("/java_26.3.json") }
    val textures: Map<String, String> by lazy { strings(root["textures"]) }
    val blocks: Map<String, String> by lazy { strings(root["blocks"]) }
    val modelParents: Map<String, String> by lazy { strings(root["model_parents"]) }

    private fun strings(e: JsonElement?): Map<String, String> =
        (e as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }
            ?.toMap() ?: emptyMap()
}

/** A face of a Bedrock block that shows a texture: (block, face, alias, atlas_index). */
internal class PathUse(val block: String, val face: String, val alias: String, val index: Int)

/** Lookups over the vanilla Bedrock data, mirroring ctm_convert.py. */
internal object Bedrock {
    val FACES = listOf("down", "up", "north", "south", "west", "east")
    val SIDES = setOf("north", "south", "west", "east")

    /** terrain alias -> raw per-atlas_index entries (strings or objects). */
    fun aliasEntries(alias: String?): List<JsonElement>? {
        val t = alias?.let { Vanilla.terrainTextures[it] } ?: return null
        return when (t) {
            is JsonPrimitive -> if (t.isString) listOf(t) else null
            is JsonObject -> listOf(t)
            is JsonArray -> t.toList()
        }
    }

    fun entryPaths(e: JsonElement): List<String> = when (e) {
        is JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
        is JsonObject -> {
            val v = e["variations"]
            if (v is JsonArray) v.mapNotNull { x -> (x as? JsonObject)?.let { (it["path"] as? JsonPrimitive)?.content ?: "" } }
            else (e["path"] as? JsonPrimitive)?.takeIf { it.isString }?.let { listOf(it.content) } ?: emptyList()
        }
        else -> emptyList()
    }

    /** blocks.json entry -> face -> terrain alias. */
    fun faceAliases(entry: JsonObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        when (val t = entry["textures"]) {
            is JsonPrimitive -> if (t.isString) FACES.forEach { out[it] = t.content }
            is JsonObject -> for (f in FACES) {
                val a = if (f in t) t[f] else if (f in SIDES) t["side"] else null
                (a as? JsonPrimitive)?.takeIf { it.isString }?.let { out[f] = it.content }
            }
            else -> Unit
        }
        return out
    }

    /** Bedrock path -> every block face showing it. */
    val pathUses: Map<String, List<PathUse>> by lazy {
        val uses = LinkedHashMap<String, MutableList<PathUse>>()
        for ((block, entry) in Vanilla.blocks) {
            val obj = entry as? JsonObject ?: continue
            for ((f, alias) in faceAliases(obj)) {
                aliasEntries(alias)?.forEachIndexed { i, e ->
                    for (p in entryPaths(e)) uses.getOrPut(p) { ArrayList() } += PathUse(block, f, alias, i)
                }
            }
        }
        uses
    }

    /** Bedrock path -> every (alias, atlas_index) holding it. */
    val pathTiles: Map<String, List<Pair<String, Int>>> by lazy {
        val tiles = LinkedHashMap<String, MutableList<Pair<String, Int>>>()
        for (alias in Vanilla.terrainTextures.keys) {
            aliasEntries(alias)?.forEachIndexed { i, e ->
                for (p in entryPaths(e)) {
                    val l = tiles.getOrPut(p) { ArrayList() }
                    if ((alias to i) !in l) l += alias to i
                }
            }
        }
        tiles
    }

    /**
     * (atlas tile, atlas_index) -> vanilla's flipbook entry. A tile a pack re-animates keeps that
     * entry's image path, atlas_index, atlas_tile_variant and replicate.
     */
    val vanillaFlipbooks: Map<Pair<String, Int>, JsonObject> by lazy {
        val out = HashMap<Pair<String, Int>, JsonObject>()
        for (e in Vanilla.flipbooks) {
            val tile = e["atlas_tile"].str() ?: continue
            out[tile to (asInt(e["atlas_index"]) ?: 0)] = e
        }
        out
    }

    fun block(javaId: String): String? {
        val b = JavaNames.blocks[javaId] ?: javaId
        return if (Vanilla.blocks[b] is JsonObject) b else null
    }

    private val COLORS = listOf(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    )
    private val BEDROCK_COLOR = COLORS.associateWith { if (it == "light_gray") "silver" else it }
    private val STAINED = Regex("(\\w+?)_stained_glass(_pane_top)?")

    /** Java block texture name -> Bedrock texture path (without extension), glass family. */
    fun glassTexture(name: String): String? {
        if (name == "glass" || name == "glass_pane_top" || name == "tinted_glass") return "textures/blocks/$name"
        val m = STAINED.matchEntire(name) ?: return null
        val c = BEDROCK_COLOR[m.groupValues[1]] ?: return null
        return if (m.groups[2] != null) "textures/blocks/glass_pane_top_$c" else "textures/blocks/glass_$c"
    }

    /** Java block texture name (no namespace/folder) -> Bedrock texture path. */
    fun javaTexturePath(name: String): String? = glassTexture(name) ?: JavaNames.textures[name]
}

// ---------------------------------------------------------------------- JSON helpers (pack files)

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Like Python's str(x) for the JSON values packs use (strings, numbers, booleans). */
internal fun JsonElement?.text(default: String): String = when (this) {
    null -> default
    is JsonPrimitive -> if (isString) content else when (content) { "true" -> "True"; "false" -> "False"; else -> content }
    else -> toString()
}

/** Integer value of a JSON number (integral floats too), like the converter's as_int. */
internal fun asInt(e: JsonElement?): Int? {
    val p = e as? JsonPrimitive ?: return null
    if (p.isString || p.content == "true" || p.content == "false" || p.content == "null") return null
    p.content.toIntOrNull()?.let { return it }
    val d = p.content.toDoubleOrNull() ?: return null
    return if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < Int.MAX_VALUE) d.toInt() else null
}

internal fun isTrue(e: JsonElement?): Boolean = (e as? JsonPrimitive)?.let { !it.isString && it.content == "true" } ?: false

internal fun parseJson(bytes: ByteArray?): JsonElement? {
    if (bytes == null) return null
    return try {
        Json.parseToJsonElement(String(bytes, Charsets.UTF_8).removePrefix("\uFEFF"))
    } catch (e: Exception) {
        null
    }
}
