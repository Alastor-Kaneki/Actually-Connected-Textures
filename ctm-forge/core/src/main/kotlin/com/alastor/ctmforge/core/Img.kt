package com.alastor.ctmforge.core

/** A simple ARGB image (non-premultiplied, 0xAARRGGBB per pixel). */
class Img(val width: Int, val height: Int, val px: IntArray = IntArray(width * height)) {
    init {
        require(width >= 0 && height >= 0 && px.size == width * height) { "bad image size" }
    }

    operator fun get(x: Int, y: Int): Int = px[y * width + x]
    operator fun set(x: Int, y: Int, argb: Int) { px[y * width + x] = argb }

    fun copy(): Img = Img(width, height, px.copyOf())

    /** Like PIL's crop((x0, y0, x1, y1)); areas outside the image become transparent. */
    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): Img {
        val out = Img(x1 - x0, y1 - y0)
        for (y in 0 until out.height) {
            val sy = y0 + y
            if (sy < 0 || sy >= height) continue
            for (x in 0 until out.width) {
                val sx = x0 + x
                if (sx in 0 until width) out[x, y] = this[sx, sy]
            }
        }
        return out
    }

    /** Copies [src] onto this image at (x, y), replacing pixels (PIL paste without a mask). */
    fun paste(src: Img, x: Int, y: Int) {
        for (sy in 0 until src.height) {
            val dy = y + sy
            if (dy < 0 || dy >= height) continue
            for (sx in 0 until src.width) {
                val dx = x + sx
                if (dx in 0 until width) this[dx, dy] = src[sx, sy]
            }
        }
    }

    /**
     * Nearest-neighbour resize, step for step like PIL's NEAREST (pixel-centre sampling with
     * the same floating-point accumulation), so converted packs match the reference converter.
     */
    fun resizeNearest(w: Int, h: Int): Img {
        if (w == width && h == height) return copy()
        val out = Img(w, h)
        if (w == 0 || h == 0 || width == 0 || height == 0) return out
        val a0 = width.toDouble() / w
        val a4 = height.toDouble() / h
        val xin = IntArray(w)
        var xo = a0 * 0.5
        for (x in 0 until w) { xin[x] = coord(xo); xo += a0 }
        var yo = a4 * 0.5
        for (y in 0 until h) {
            val sy = coord(yo)
            if (sy in 0 until height) {
                for (x in 0 until w) {
                    val sx = xin[x]
                    if (sx in 0 until width) out.px[y * w + x] = px[sy * width + sx]
                }
            }
            yo += a4
        }
        return out
    }

    private fun coord(v: Double): Int = if (v < 0.0) -1 else v.toInt()

    /** Box-filter downscale for icons (alpha-weighted). */
    fun resizeArea(w: Int, h: Int): Img {
        if (w >= width || h >= height) return resizeNearest(w, h)
        val out = Img(w, h)
        for (y in 0 until h) {
            val y0 = y * height / h
            val y1 = maxOf(y0 + 1, (y + 1) * height / h)
            for (x in 0 until w) {
                val x0 = x * width / w
                val x1 = maxOf(x0 + 1, (x + 1) * width / w)
                var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0L
                for (sy in y0 until y1) for (sx in x0 until x1) {
                    val c = this[sx, sy]
                    val ca = (c ushr 24) and 0xFF
                    a += ca; r += ((c shr 16) and 0xFF) * ca; g += ((c shr 8) and 0xFF) * ca; b += (c and 0xFF) * ca; n++
                }
                out[x, y] = if (a == 0L) 0 else
                    (((a / n).toInt() shl 24) or ((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt())
            }
        }
        return out
    }

    /** First frame of an animated strip (frames stacked vertically). */
    fun firstFrame(): Img = if (height > width && width > 0 && height % width == 0) crop(0, 0, width, width) else this

    override fun equals(other: Any?): Boolean =
        other is Img && other.width == width && other.height == height && other.px.contentEquals(px)

    override fun hashCode(): Int = 31 * (31 * width + height) + px.contentHashCode()

    companion object {
        fun alpha(c: Int) = (c ushr 24) and 0xFF
    }
}
