package com.alastor.ctmforge.core

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.util.UUID

enum class CreatorMethod(val label: String, val property: String, val tileCount: Int) {
    FIXED("Single texture", "fixed", 1),
    COMPACT("Connected · 5 tiles", "ctm_compact", 5),
    CTM("Connected · 47 tiles", "ctm", 47),
    HORIZONTAL("Horizontal · 4 tiles", "horizontal", 4),
    VERTICAL("Vertical · 4 tiles", "vertical", 4),
    RANDOM("Random variants", "random", 4),
}

data class CreatorTexture(val asset: String, val size: Int, val frames: Int)

data class CreatorRule(
    val id: String = UUID.randomUUID().toString(),
    val block: String = "glass",
    val method: CreatorMethod = CreatorMethod.FIXED,
    val faces: String = "all",
    val tiles: List<CreatorTexture?> = listOf(null),
    val animated: Boolean = false,
    val ticks: String = "2",
    val order: String = "",
    val interpolate: Boolean = false,
    val innerSeams: Boolean = false,
) {
    fun withMethod(value: CreatorMethod) = copy(method = value, tiles = List(value.tileCount) { tiles.getOrNull(it) })
}

data class CreatorProject(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "My CTM pack",
    val description: String = "",
    val rules: List<CreatorRule> = listOf(CreatorRule()),
)

/** Authoring uses the same converter and atlas layout as imported packs. */
object PackCreator {
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    const val MAX_PROJECT_PIXELS = 16 * 1024 * 1024L
    val faceOptions = listOf("all", "sides", "top", "bottom", "north", "south", "west", "east")
    val blocks: List<String> by lazy {
        (Vanilla.blocks.keys + JavaNames.blocks.keys).filter { id ->
            Bedrock.block(id)?.let { Vanilla.blocks[it] as? JsonObject }?.let { Bedrock.faceAliases(it).isNotEmpty() } == true
        }.sorted()
    }

    /** Check dimensions before allocating any pixels in the PNG decoder. */
    fun decodeTexture(bytes: ByteArray): Img {
        require(bytes.size in 24..MAX_IMAGE_BYTES) { "Choose a PNG smaller than 8 MB." }
        require(bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() }) { "Choose a PNG image." }
        val w = ByteBuffer.wrap(bytes, 16, 4).int
        val h = ByteBuffer.wrap(bytes, 20, 4).int
        require(w in 8..256 && w and (w - 1) == 0) { "Texture width must be 8, 16, 32, 64, 128 or 256 pixels." }
        require(h >= w && h % w == 0 && h / w <= 64) { "Use a square PNG or a vertical strip of up to 64 square frames." }
        var pos = 8
        var headers = 0
        while (pos + 12 <= bytes.size) {
            val length = ByteBuffer.wrap(bytes, pos, 4).int
            require(length >= 0 && pos.toLong() + 12 + length <= bytes.size) { "Invalid PNG chunk." }
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            if (type == "IHDR") {
                headers++
                require(pos == 8 && length == 13 && headers == 1) { "Invalid PNG header." }
            }
            if (type == "IEND") break
            pos += 12 + length
        }
        require(headers == 1) { "Missing PNG header." }
        // RGBA16 plus row/filter overhead, including Adam7 passes.
        return Png.decode(bytes, w * h * 8 + h * 8 + 1024).also { require(it.width == w && it.height == h) }
    }

    fun sequence(rule: CreatorRule, count: Int): List<Int> {
        if (rule.order.isBlank()) return (0 until count).toList()
        val fields = rule.order.trim().split(Regex("[\\s,]+"))
        require(fields.size <= 256) { "Frame order may contain at most 256 entries." }
        return fields.map {
            val n = it.toIntOrNull()
            require(n != null && n in 0 until count) { "Frame order must use numbers from 0 to ${count - 1}." }
            n
        }
    }

    private fun faces(value: String): Set<String> = when (value) {
        "all" -> Bedrock.FACES.toSet()
        "sides" -> Bedrock.SIDES
        "top" -> setOf("up")
        "bottom" -> setOf("down")
        else -> setOf(value)
    }

    fun build(project: CreatorProject, patch: Int, read: (String) -> ByteArray): ConversionResult {
        require(project.name.trim().isNotEmpty()) { "Give your pack a name." }
        require(project.name.length <= 80 && project.description.length <= 1000) { "Use a name up to 80 characters and description up to 1,000." }
        require(project.rules.size in 1..32) { "Add between 1 and 32 block rules." }
        require(Regex("[a-zA-Z0-9-]{1,64}").matches(project.id)) { "Invalid project ID." }
        val files = linkedMapOf<String, ByteArray>()
        files["pack.mcmeta"] = buildJsonObject {
            putJsonObject("pack") { put("pack_format", JavaPack.CURRENT_FORMAT); put("description", project.description) }
        }.toString().toByteArray()
        val occupied = mutableSetOf<Pair<String, String>>()
        var pixels = 0L
        var outputPixels = 0L
        project.rules.forEachIndexed { ri, rule ->
            val prefix = "Rule ${ri + 1} (${rule.block})"
            try {
                val block = rule.block.trim().removePrefix("minecraft:")
                require(block in blocks) { "Choose a supported vanilla block ID." }
                require(rule.faces in faceOptions) { "Choose valid faces." }
                val bedrock = Bedrock.block(block)!!
                faces(rule.faces).forEach { f ->
                    require(occupied.add(bedrock to f)) { "Another rule already covers $f on $bedrock. Choose different faces." }
                }
                require(if (rule.method == CreatorMethod.RANDOM) rule.tiles.size in 1..16 else rule.tiles.size == rule.method.tileCount) { "Wrong number of tiles for this method." }
                require(rule.tiles.all { it != null }) { "Import every tile before building." }
                val images = rule.tiles.map { tile ->
                    tile!!
                    // Metadata is advisory; validate the actual bytes on every build.
                    val image = decodeTexture(read(tile.asset))
                    pixels += image.width.toLong() * image.height
                    require(pixels <= MAX_PROJECT_PIXELS) { "Pack textures exceed the 16 million pixel budget. Use smaller textures or fewer frames." }
                    image
                }
                require(images.map { it.width }.distinct().size == 1) { "All tiles in a rule must have the same width." }
                val counts = images.map { it.height / it.width }.filter { it > 1 }.distinct()
                var order = listOf(0)
                var ticks = 1
                if (rule.animated) {
                    require(counts.size == 1) { "Add an animation strip; all animated tiles must have the same frame count. Static tiles are allowed." }
                    ticks = rule.ticks.toIntOrNull() ?: error("Enter a whole number of ticks per frame.")
                    require(ticks in 1..200) { "Ticks per frame must be between 1 and 200 (20 ticks = 1 second)." }
                    order = sequence(rule, counts.single())
                    require(order.toSet().size >= 2) { "Choose at least two different frames, or turn animation off." }
                    require(order.size.toLong() * ticks <= Anim.MAX_TICKS) { "Animation loop is too long. Use fewer frames or shorter timing." }
                    val outputTiles = if (rule.method == CreatorMethod.COMPACT) 47 else rule.tiles.size
                    val columns = kotlin.math.ceil(kotlin.math.sqrt(outputTiles.toDouble())).toInt()
                    require(columns * images.first().width * order.distinct().size <= Anim.MAX_STRIP_HEIGHT) { "Animated sheet is too tall. Use smaller tiles or fewer frames." }
                }
                val outputTiles = if (rule.method == CreatorMethod.COMPACT) 47 else rule.tiles.size
                val animatedColumns = kotlin.math.ceil(kotlin.math.sqrt(outputTiles.toDouble())).toInt()
                outputPixels += images.first().width.toLong() * images.first().width *
                    (if (rule.animated) animatedColumns * animatedColumns * order.distinct().size else outputTiles + 7)
                require(outputPixels <= MAX_PROJECT_PIXELS) { "Generated atlases exceed 16 million pixels. Use smaller tiles or fewer animation frames." }
                val dir = "assets/minecraft/optifine/ctm/creator/r${ri.toString().padStart(2, '0')}"
                images.forEachIndexed { ti, im ->
                    val path = "$dir/$ti.png"
                    val animated = rule.animated && im.height > im.width
                    files[path] = Png.encode(if (animated) im else im.firstFrame(), 6)
                    if (animated) files["$path.mcmeta"] = buildJsonObject {
                        putJsonObject("animation") {
                            put("frametime", ticks)
                            put("interpolate", rule.interpolate)
                            put("frames", JsonArray(order.map { JsonPrimitive(it) }))
                        }
                    }.toString().toByteArray()
                }
                files["$dir/rule.properties"] = buildString {
                    append("matchBlocks=minecraft:$block\nmethod=${rule.method.property}\n")
                    append("tiles=${images.indices.joinToString(" ")}\nfaces=${rule.faces}\n")
                    append("connect=block\ninnerSeams=${rule.innerSeams}\n")
                }.toByteArray()
                if (ri == 0) files["pack.png"] = Png.encode(images.first().firstFrame(), 6)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$prefix: ${e.message}", e)
            }
        }
        val result = Converter.convert(JavaPack.fromEntries(files), ConvertOptions(
            name = project.name.trim(), slug = "created_${project.id}", version = listOf(1, 0, patch), pngLevel = 6,
        ))
        // Never silently export an incomplete pack if the converter rejects a target.
        require(result.methods.values.sum() == project.rules.size) { "Some rules could not be built: ${result.log.joinToString("; ")}" }
        val output = result.files.toMutableMap()
        val manifest = Json.parseToJsonElement(output.getValue("manifest.json").decodeToString()).jsonObject
        output["manifest.json"] = JsonObject(manifest + mapOf(
            "header" to JsonObject(manifest.getValue("header").jsonObject + mapOf(
                "name" to JsonPrimitive(project.name.trim()),
                "description" to JsonPrimitive(project.description.ifBlank { "Created with CTM Forge. Requires the Connected Textures LeviLauncher mod." }),
            )),
            "metadata" to buildJsonObject { put("product_type", "addon") },
        )).toString().toByteArray()
        return ConversionResult(result.name, result.slug, result.direct, result.headerUuid, result.version,
            output.toSortedMap(), result.blocks, result.methods, result.rules, result.log, result.sheets, result.animated)
    }
}
