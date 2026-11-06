package com.alastor.ctmforge.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A texture: its frames and Java timing ((frame, ticks) pairs; one pair when static). */
class Tex(val frames: List<Img>, val timing: List<Pair<Int, Int>> = listOf(0 to 1), val interpolate: Boolean = false) {
    val animated: Boolean get() = timing.map { it.first }.toSet().size > 1
    val first: Img get() = frames[timing[0].first]
}

/** Combined loop of several textures (see [Anim.mergeTimeline]). */
class Timeline(val states: List<List<Int>>, val frames: List<Int>, val ticksPerFrame: Int, val blend: Boolean)

/** Java .mcmeta animations, Fusion scrolling textures and Bedrock flipbooks (ctm_convert.py). */
object Anim {
    const val MAX_TICKS = 12000
    const val MAX_FRAMES = 64
    const val MAX_STRIP_HEIGHT = 16384

    fun timing(anim: JsonObject, count: Int): List<Pair<Int, Int>> {
        val d = asInt(anim["frametime"])
        val default = if (d != null && d > 0) d else 1
        val out = ArrayList<Pair<Int, Int>>()
        val fl = anim["frames"]
        if (fl is JsonArray) {
            for (e in fl) {
                val idx: Int?
                val t: Int?
                if (asInt(e) != null) {
                    idx = asInt(e); t = default
                } else if (e is JsonObject) {
                    idx = asInt(e["index"]); t = if ("time" in e) asInt(e["time"]) else default
                } else continue
                if (idx != null && idx in 0 until count && t != null && t > 0) out += idx to t
            }
        }
        return out.ifEmpty { List(count) { it to default } }
    }

    fun frameSize(anim: JsonObject, w: Int, h: Int): Pair<Int, Int> {
        val fw = asInt(anim["width"])
        val fh = asInt(anim["height"])
        if (fw != null && fw > 0) return fw to (if (fh != null && fh > 0) fh else h)
        if (fh != null && fh > 0) return w to fh
        val s = minOf(w, h)
        return s to s
    }

    fun splitFrames(im: Img, fw: Int, fh: Int): List<Img>? {
        if (fw <= 0 || fh <= 0 || im.width % fw != 0 || im.height % fh != 0) return null
        val cols = im.width / fw
        val count = cols * (im.height / fh)
        val frames = List(count) { i ->
            im.crop((i % cols) * fw, (i / cols) * fh, (i % cols + 1) * fw, (i / cols + 1) * fh)
        }
        return if (fw != fh) frames.map { it.resizeNearest(fw, fw) } else frames // Bedrock flipbooks are square
    }

    fun animatedTex(im: Img, anim: JsonObject): Tex? {
        val (fw, fh) = frameSize(anim, im.width, im.height)
        val frames = splitFrames(im, fw, fh)
        if (frames.isNullOrEmpty()) return null
        return Tex(frames, timing(anim, frames.size), isTrue(anim["interpolate"]))
    }

    private fun roundHalfUp(num: Int, den: Int): Int = Math.floorDiv(2 * num + den, 2 * den)
    private fun roundHalfUp(num: Long, den: Long): Long = Math.floorDiv(2 * num + den, 2 * den)
    private fun ceilDiv(num: Long, den: Long): Long = -Math.floorDiv(-num, den)

    /** Fusion scrolling texture -> frames (the frame window moves across the image). */
    fun scrollingTex(im: Img, meta: JsonObject): Tex? {
        val f = meta["fusion"] as? JsonObject ?: return null
        if (Fusion.type(f) != "scrolling") return null
        val fw = asInt(f["frame_width"]).takeIf { it != null && it != 0 } ?: 16
        val fh = asInt(f["frame_height"]).takeIf { it != null && it != 0 } ?: 16
        val ft = asInt(f["frame_time"]).takeIf { it != null && it != 0 } ?: 10
        val pause = asInt(f["loop_pause"]) ?: 0
        val loop = f["loop_type"].text("wrap").lowercase()
        val start = f["from"].text("top_left").lowercase()
        val end = f["to"].text("bottom_left").lowercase()
        val w = im.width
        val h = im.height
        if (fw <= 0 || fh <= 0 || ft <= 0 || w < fw || h < fh) return null
        val wrap = loop == "wrap"
        val rev = loop == "reverse"
        fun left(p: String) = p == "top_left" || p == "bottom_left"
        fun top(p: String) = p == "top_left" || p == "top_right"
        val sx = if (left(start)) 0 else w - fw
        val sy = if (top(start)) 0 else h - fh
        val ex = if (left(end)) (if (wrap) 1 - fw else 0) else (if (wrap) w - 1 else w - fw)
        val ey = if (top(end)) (if (wrap) 1 - fh else 0) else (if (wrap) h - 1 else h - fh)
        val steps = maxOf(kotlin.math.abs(ex - sx), kotlin.math.abs(ey - sy)) + 1
        if (steps > 1024) return null
        val count = if (rev) maxOf((steps - 1) * 2, 1) else steps
        val frames = ArrayList<Img>()
        val timing = ArrayList<Pair<Int, Int>>()
        for (i in 0 until count) {
            val k = if (i < steps) i else 2 * (steps - 1) - i
            val x: Int
            val y: Int
            if (steps > 1) {
                x = roundHalfUp(sx * (steps - 1) + (ex - sx) * k, steps - 1)
                y = roundHalfUp(sy * (steps - 1) + (ey - sy) * k, steps - 1)
            } else {
                x = roundHalfUp(2 * sx + (ex - sx), 2)
                y = roundHalfUp(2 * sy + (ey - sy), 2)
            }
            var fr = if (wrap) wrappedCrop(im, x, y, fw, fh) else im.crop(x, y, x + fw, y + fh)
            if (fw != fh) fr = fr.resizeNearest(fw, fw)
            frames += fr
            timing += i to (if ((i == 0 && rev) || i == steps - 1) ft + pause else ft)
        }
        return Tex(frames, timing, false)
    }

    private fun wrappedCrop(im: Img, x: Int, y: Int, w: Int, h: Int): Img {
        val out = Img(w, h)
        for (yy in 0 until h) for (xx in 0 until w) {
            out[xx, yy] = im[Math.floorMod(x + xx, im.width), Math.floorMod(y + yy, im.height)]
        }
        return out
    }

    /** Common loop of several textures. A state is the frame index of every texture at one moment. */
    fun mergeTimeline(texs: List<Tex>): Timeline {
        // very long loops (huge frame times) are counted in coarser steps
        val longest = texs.maxOf { t -> t.timing.sumOf { it.second.toLong() } }
        val scale = if (longest > MAX_TICKS) ceilDiv(longest, MAX_TICKS.toLong()) else 1L
        val seqs = texs.map { t ->
            val s = ArrayList<Int>()
            for ((idx, n) in t.timing) repeat(maxOf(1L, roundHalfUp(n.toLong(), scale)).toInt()) { s += idx }
            if (s.isEmpty()) listOf(0) else s
        }
        var period = 1L
        for (s in seqs) {
            if (s.size > 1) {
                period = period * s.size / gcd(period, s.size.toLong())
                if (period > MAX_TICKS) {
                    period = seqs.maxOf { it.size }.toLong()
                    break
                }
            }
        }
        val ticks = List(period.toInt()) { k -> seqs.map { s -> s[k % s.size] } }
        var step = 1
        var runs: MutableList<Pair<List<Int>, Int>>
        var states: List<List<Int>>
        while (true) {
            runs = ArrayList()
            var k = 0
            while (k < ticks.size) {
                val st = ticks[k]
                if (runs.isNotEmpty() && runs.last().first == st) runs[runs.size - 1] = st to runs.last().second + 1
                else runs += st to 1
                k += step
            }
            states = LinkedHashSet(runs.map { it.first }).toList()
            if (states.size <= MAX_FRAMES) break
            step++
        }
        var g = 0L
        for ((_, n) in runs) g = gcd(g, n.toLong())
        val index = HashMap<List<Int>, Int>()
        states.forEachIndexed { i, s -> index[s] = i }
        val frames = ArrayList<Int>()
        for ((st, n) in runs) repeat((n / g).toInt()) { frames += index.getValue(st) }
        return Timeline(states, frames, (g * step * scale).toInt(), texs.any { it.animated && it.interpolate })
    }

    /**
     * One animated block texture, like [mergeTimeline] but the strip keeps every frame in the
     * pack's order (the layout a Java strip and vanilla's entry for the tile use). Too tall strips
     * keep only the frames that play; null when even that is too tall for one flipbook.
     */
    fun plainTimeline(t: Tex): Timeline? {
        val w = t.frames[0].width.toLong()
        if (t.frames.size * w > MAX_STRIP_HEIGHT) {
            val merged = mergeTimeline(listOf(t))
            return if (merged.states.size * w <= MAX_STRIP_HEIGHT) merged else null
        }
        val runs = ArrayList<LongArray>() // (frame, ticks)
        for ((idx, n) in t.timing) {
            if (runs.isNotEmpty() && runs.last()[0] == idx.toLong()) runs.last()[1] += n.toLong()
            else runs += longArrayOf(idx.toLong(), n.toLong())
        }
        var g = 0L
        for (r in runs) g = gcd(g, r[1])
        if (runs.sumOf { it[1] / g } > MAX_TICKS) g = ceilDiv(runs.sumOf { it[1] }, MAX_TICKS.toLong()) // very uneven frame times
        val frames = ArrayList<Int>()
        for (r in runs) repeat(maxOf(1L, roundHalfUp(r[1], g)).toInt()) { frames += r[0].toInt() }
        return Timeline(t.frames.indices.map { listOf(it) }, frames, g.toInt(), t.interpolate)
    }

    fun flipbookEntry(
        strip: String, alias: String, t: Timeline, index: Int? = null, variant: Int? = null, replicate: JsonElement? = null,
    ): JsonObject {
        val e = LinkedHashMap<String, JsonElement>()
        e["flipbook_texture"] = JsonPrimitive(strip)
        e["atlas_tile"] = JsonPrimitive(alias)
        if (index != null) e["atlas_index"] = JsonPrimitive(index)
        if (variant != null) e["atlas_tile_variant"] = JsonPrimitive(variant)
        e["ticks_per_frame"] = JsonPrimitive(t.ticksPerFrame)
        e["frames"] = JsonArray(t.frames.map { JsonPrimitive(it) })
        e["blend_frames"] = JsonPrimitive(t.blend)
        if (replicate != null) e["replicate"] = replicate
        return JsonObject(e)
    }

    fun stack(frames: List<Img>): Img {
        val w = frames[0].width
        val h = frames[0].height
        val out = Img(w, h * frames.size)
        frames.forEachIndexed { i, f -> out.paste(f, 0, i * h) }
        return out
    }

    private fun gcd(a: Long, b: Long): Long {
        var x = kotlin.math.abs(a); var y = kotlin.math.abs(b)
        while (y != 0L) { val t = x % y; x = y; y = t }
        return x
    }
}
