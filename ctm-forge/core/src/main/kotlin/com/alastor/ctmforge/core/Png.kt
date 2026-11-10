package com.alastor.ctmforge.core

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Self-contained PNG reader/writer so pixels come out identical on Android and the JVM
 * (Android's decoders premultiply alpha, which changes faint glass pixels).
 * Reads every standard colour type and bit depth, including palettes, tRNS and Adam7.
 */
object Png {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    class PngException(msg: String) : Exception(msg)

    fun isPng(bytes: ByteArray): Boolean = bytes.size >= 8 && (0 until 8).all { bytes[it] == SIGNATURE[it] }

    fun decode(bytes: ByteArray, maxInflatedBytes: Int = 512 * 1024 * 1024): Img {
        if (!isPng(bytes)) throw PngException("not a PNG file")
        var pos = 8
        var width = 0; var height = 0; var depth = 0; var colorType = 0; var interlace = 0
        var palette: IntArray? = null
        var trns: ByteArray? = null
        val idat = ByteArrayOutputStream()
        fun u32(p: Int) = ((bytes[p].toInt() and 0xFF) shl 24) or ((bytes[p + 1].toInt() and 0xFF) shl 16) or
            ((bytes[p + 2].toInt() and 0xFF) shl 8) or (bytes[p + 3].toInt() and 0xFF)
        while (pos + 8 <= bytes.size) {
            val len = u32(pos)
            if (len < 0 || pos + 12 + len > bytes.size) throw PngException("truncated chunk")
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val data = pos + 8
            when (type) {
                "IHDR" -> {
                    width = u32(data); height = u32(data + 4)
                    depth = bytes[data + 8].toInt() and 0xFF
                    colorType = bytes[data + 9].toInt() and 0xFF
                    interlace = bytes[data + 12].toInt() and 0xFF
                }
                "PLTE" -> palette = IntArray(len / 3) { i ->
                    (0xFF shl 24) or ((bytes[data + 3 * i].toInt() and 0xFF) shl 16) or
                        ((bytes[data + 3 * i + 1].toInt() and 0xFF) shl 8) or (bytes[data + 3 * i + 2].toInt() and 0xFF)
                }
                "tRNS" -> trns = bytes.copyOfRange(data, data + len)
                "IDAT" -> idat.write(bytes, data, len)
                "IEND" -> break
            }
            pos += 12 + len
        }
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) throw PngException("bad size ${width}x$height")
        val channels = when (colorType) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> throw PngException("colour type $colorType") }
        if (depth !in intArrayOf(1, 2, 4, 8, 16)) throw PngException("bit depth $depth")
        val raw = inflate(idat.toByteArray(), maxInflatedBytes)
        val bitsPerPixel = channels * depth
        val bpp = maxOf(1, bitsPerPixel / 8)
        val img = Img(width, height)
        var offset = 0
        val passes = if (interlace == 1) ADAM7 else arrayOf(intArrayOf(0, 0, 1, 1))
        for (p in passes) {
            val (x0, y0, dx, dy) = p.toList()
            val pw = if (width > x0) (width - x0 + dx - 1) / dx else 0
            val ph = if (height > y0) (height - y0 + dy - 1) / dy else 0
            if (pw == 0 || ph == 0) continue
            val stride = (pw * bitsPerPixel + 7) / 8
            var prev = ByteArray(stride)
            for (row in 0 until ph) {
                if (offset + 1 + stride > raw.size) throw PngException("truncated image data")
                val filter = raw[offset].toInt() and 0xFF
                val line = raw.copyOfRange(offset + 1, offset + 1 + stride)
                unfilter(filter, line, prev, bpp)
                offset += 1 + stride
                for (col in 0 until pw) {
                    img[x0 + col * dx, y0 + row * dy] = pixel(line, col, colorType, depth, palette, trns)
                }
                prev = line
            }
        }
        return img
    }

    private val ADAM7 = arrayOf(
        intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8), intArrayOf(2, 0, 4, 4),
        intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2),
    )

    private fun inflate(data: ByteArray, limit: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream(maxOf(64, data.size * 4))
        val buf = ByteArray(65536)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break
                }
                out.write(buf, 0, n)
                if (out.size() > limit) throw PngException("image too large")
            }
        } finally {
            inf.end()
        }
        return out.toByteArray()
    }

    private fun unfilter(filter: Int, line: ByteArray, prev: ByteArray, bpp: Int) {
        when (filter) {
            0 -> {}
            1 -> for (i in bpp until line.size) line[i] = (line[i] + line[i - bpp]).toByte()
            2 -> for (i in line.indices) line[i] = (line[i] + prev[i]).toByte()
            3 -> for (i in line.indices) {
                val left = if (i >= bpp) line[i - bpp].toInt() and 0xFF else 0
                line[i] = (line[i] + ((left + (prev[i].toInt() and 0xFF)) ushr 1)).toByte()
            }
            4 -> for (i in line.indices) {
                val a = if (i >= bpp) line[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val pa = kotlin.math.abs(b - c); val pb = kotlin.math.abs(a - c); val pc = kotlin.math.abs(a + b - 2 * c)
                val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                line[i] = (line[i] + pred).toByte()
            }
            else -> throw PngException("filter $filter")
        }
    }

    private fun sample(line: ByteArray, index: Int, depth: Int): Int = when (depth) {
        8 -> line[index].toInt() and 0xFF
        16 -> line[index * 2].toInt() and 0xFF  // high byte
        else -> {
            val bit = index * depth
            val byte = line[bit ushr 3].toInt() and 0xFF
            (byte ushr (8 - depth - (bit and 7))) and ((1 shl depth) - 1)
        }
    }

    private fun sample16(line: ByteArray, index: Int): Int =
        ((line[index * 2].toInt() and 0xFF) shl 8) or (line[index * 2 + 1].toInt() and 0xFF)

    private fun scale(v: Int, depth: Int): Int = when (depth) { 1 -> v * 255; 2 -> v * 85; 4 -> v * 17; else -> v }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun trnsValue(trns: ByteArray?, i: Int): Int? =
        if (trns == null || trns.size < 2 * i + 2) null
        else ((trns[2 * i].toInt() and 0xFF) shl 8) or (trns[2 * i + 1].toInt() and 0xFF)

    private fun pixel(line: ByteArray, col: Int, colorType: Int, depth: Int, palette: IntArray?, trns: ByteArray?): Int =
        when (colorType) {
            0 -> {
                val raw = if (depth == 16) sample16(line, col) else sample(line, col, depth)
                val v = if (depth == 16) raw ushr 8 else scale(raw, depth)
                val transparent = trnsValue(trns, 0)?.let { it == raw } ?: false
                argb(if (transparent) 0 else 255, v, v, v)
            }
            2 -> {
                val r: Int; val g: Int; val b: Int; val transparent: Boolean
                if (depth == 16) {
                    val rr = sample16(line, col * 3); val gg = sample16(line, col * 3 + 1); val bb = sample16(line, col * 3 + 2)
                    transparent = trns != null && trns.size >= 6 && rr == trnsValue(trns, 0) && gg == trnsValue(trns, 1) && bb == trnsValue(trns, 2)
                    r = rr ushr 8; g = gg ushr 8; b = bb ushr 8
                } else {
                    r = sample(line, col * 3, 8); g = sample(line, col * 3 + 1, 8); b = sample(line, col * 3 + 2, 8)
                    transparent = trns != null && trns.size >= 6 && r == trnsValue(trns, 0) && g == trnsValue(trns, 1) && b == trnsValue(trns, 2)
                }
                argb(if (transparent) 0 else 255, r, g, b)
            }
            3 -> {
                val idx = sample(line, col, depth)
                val pal = palette ?: throw PngException("missing palette")
                val c = if (idx < pal.size) pal[idx] else 0xFF000000.toInt()
                val a = if (trns != null && idx < trns.size) trns[idx].toInt() and 0xFF else 255
                (a shl 24) or (c and 0xFFFFFF)
            }
            4 -> {
                val v = sample(line, col * 2, depth); val a = sample(line, col * 2 + 1, depth)
                argb(a, v, v, v)
            }
            else -> argb(sample(line, col * 4 + 3, depth), sample(line, col * 4, depth), sample(line, col * 4 + 1, depth), sample(line, col * 4 + 2, depth))
        }

    /** Writes an 8-bit RGBA PNG. */
    fun encode(img: Img, level: Int = 9): ByteArray {
        val w = img.width; val h = img.height
        val stride = w * 4
        val raw = ByteArray((stride + 1) * h)
        val cur = ByteArray(stride); val prev = ByteArray(stride); val tmp = ByteArray(stride)
        var o = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = img[x, y]
                cur[x * 4] = (c shr 16).toByte(); cur[x * 4 + 1] = (c shr 8).toByte()
                cur[x * 4 + 2] = c.toByte(); cur[x * 4 + 3] = (c ushr 24).toByte()
            }
            // pick the filter with the smallest sum of absolute values (standard heuristic)
            var bestFilter = 0; var bestSum = Long.MAX_VALUE
            for (f in 0..4) {
                filterLine(f, cur, prev, tmp, 4)
                var sum = 0L
                for (b in tmp) sum += kotlin.math.abs(b.toInt())
                if (sum < bestSum) { bestSum = sum; bestFilter = f }
            }
            filterLine(bestFilter, cur, prev, tmp, 4)
            raw[o++] = bestFilter.toByte()
            System.arraycopy(tmp, 0, raw, o, stride); o += stride
            System.arraycopy(cur, 0, prev, 0, stride)
        }
        val def = Deflater(level)
        def.setInput(raw); def.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        def.end()
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val ihdr = ByteArrayOutputStream().apply {
            writeInt(w); writeInt(h); write(8); write(6); write(0); write(0); write(0)
        }.toByteArray()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun filterLine(f: Int, cur: ByteArray, prev: ByteArray, out: ByteArray, bpp: Int) {
        for (i in cur.indices) {
            val x = cur[i].toInt() and 0xFF
            val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
            val b = prev[i].toInt() and 0xFF
            val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
            out[i] = when (f) {
                0 -> x
                1 -> x - a
                2 -> x - b
                3 -> x - ((a + b) ushr 1)
                else -> {
                    val pa = kotlin.math.abs(b - c); val pb = kotlin.math.abs(a - c); val pc = kotlin.math.abs(a + b - 2 * c)
                    x - (if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c)
                }
            }.toByte()
        }
    }

    private fun ByteArrayOutputStream.writeInt(v: Int) {
        write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v)
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.writeInt(data.size)
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        out.writeInt(crc.value.toInt())
    }
}
