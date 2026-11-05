package com.alastor.ctmforge.core

/**
 * The mod's tile selection, used here for previews. Mirrors CtmLogic.hpp in the
 * Connected Textures mod (OptiFine/MCPatcher CTM behaviour).
 */
object Ctm {
    /** 47-tile lookup, index bits: L=1 DL=2 D=4 DR=8 R=16 UR=32 U=64 UL=128. */
    val CTM47 = intArrayOf(
        0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15, 1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
        0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15, 1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
        36, 17, 36, 17, 24, 19, 24, 43, 36, 17, 36, 17, 24, 19, 24, 43, 16, 18, 16, 18, 6, 46, 6, 21, 16, 18, 16, 18, 28, 9, 28, 22,
        36, 17, 36, 17, 24, 19, 24, 43, 36, 17, 36, 17, 24, 19, 24, 43, 37, 40, 37, 40, 30, 8, 30, 34, 37, 40, 37, 40, 25, 23, 25, 45,
        0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15, 1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
        0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15, 1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
        36, 39, 36, 39, 24, 41, 24, 27, 36, 39, 36, 39, 24, 41, 24, 27, 16, 42, 16, 42, 6, 20, 6, 10, 16, 42, 16, 42, 28, 35, 28, 44,
        36, 39, 36, 39, 24, 41, 24, 27, 36, 39, 36, 39, 24, 41, 24, 27, 37, 38, 37, 38, 30, 11, 30, 32, 37, 38, 37, 38, 25, 33, 25, 26,
    )
    private val LINE4 = intArrayOf(3, 2, 0, 1)
    private val HV2 = intArrayOf(
        3, 3, 6, 3, 3, 3, 3, 3, 3, 3, 6, 3, 3, 3, 3, 3, 4, 4, 5, 4, 4, 4, 4, 4, 3, 3, 6, 3, 3, 3, 3, 3,
        3, 3, 6, 3, 3, 3, 3, 3, 3, 3, 6, 3, 3, 3, 3, 3, 3, 3, 6, 3, 3, 3, 3, 3, 3, 3, 6, 3, 3, 3, 3, 3,
    )
    private val VH2 = intArrayOf(
        3, 6, 3, 3, 3, 6, 3, 3, 4, 5, 4, 4, 3, 6, 3, 3, 3, 6, 3, 3, 3, 6, 3, 3, 3, 6, 3, 3, 3, 6, 3, 3,
        3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
    )

    data class Spec(val method: String, val cols: Int, val rows: Int, val count: Int, val innerSeams: Boolean)

    /** Parses ".../ctm/<method>-<cols>x<rows>-<count>[-s]/<name>". */
    fun parseSpec(path: String): Spec? {
        val m = Regex("/ctm/([a-z]+)-(\\d+)x(\\d+)-(\\d+)(-s)?/[^/]+$").find(path) ?: return null
        return Spec(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[5].isNotEmpty())
    }

    /**
     * Tile for a south-facing face at (x, y) in a flat wall; [solid] says which cells hold
     * the same block. Left/right are -x/+x and up/down are +y/-y, as seen in game.
     */
    fun wallTile(spec: Spec, x: Int, y: Int, solid: (Int, Int) -> Boolean): Int {
        fun c(dx: Int, dy: Int) = solid(x + dx, y + dy)
        return when (spec.method) {
            "ctm" -> {
                val l = c(-1, 0); val d = c(0, -1); val r = c(1, 0); val u = c(0, 1)
                var bits = 0
                if (l) bits = bits or 1
                if (d) bits = bits or 4
                if (r) bits = bits or 16
                if (u) bits = bits or 64
                if (l && d && c(-1, -1)) bits = bits or 2
                if (d && r && c(1, -1)) bits = bits or 8
                if (r && u && c(1, 1)) bits = bits or 32
                if (u && l && c(-1, 1)) bits = bits or 128
                CTM47[bits]
            }
            "horizontal" -> LINE4[(if (c(-1, 0)) 1 else 0) or (if (c(1, 0)) 2 else 0)]
            "vertical" -> LINE4[(if (c(0, -1)) 1 else 0) or (if (c(0, 1)) 2 else 0)]
            "hv" -> {
                val h = (if (c(-1, 0)) 1 else 0) or (if (c(1, 0)) 2 else 0)
                if (h != 0) LINE4[h] else {
                    var b = 0
                    if (c(0, -1)) { b = b or 2; if (c(-1, -1)) b = b or 1; if (c(1, -1)) b = b or 4 }
                    if (c(0, 1)) { b = b or 16; if (c(1, 1)) b = b or 8; if (c(-1, 1)) b = b or 32 }
                    HV2[b]
                }
            }
            "vh" -> {
                val v = (if (c(0, -1)) 1 else 0) or (if (c(0, 1)) 2 else 0)
                if (v != 0) LINE4[v] else {
                    var b = 0
                    if (c(-1, 0)) { b = b or 1; if (c(-1, -1)) b = b or 32; if (c(-1, 1)) b = b or 2 }
                    if (c(1, 0)) { b = b or 8; if (c(1, 1)) b = b or 4; if (c(1, -1)) b = b or 16 }
                    VH2[b]
                }
            }
            "top" -> if (c(0, 1)) 1 else 0
            "random" -> Integer.remainderUnsigned(hash3(x, y, 0, 7), spec.count) // south face salt
            "repeat" -> Math.floorMod(-y, spec.rows) * spec.cols + Math.floorMod(x, spec.cols)
            else -> 0
        }
    }

    /** Same hash as the mod, so previews pick the same random tiles as the game. */
    fun hash3(x: Int, y: Int, z: Int, salt: Int): Int {
        var h = (x * 0x8DA6B343.toInt()) xor (y * 0xD8163841.toInt()) xor (z * 0xCB1AB31F.toInt()) xor (salt * 0x9E3779B9.toInt())
        h = h xor (h ushr 15); h *= 0x2C1B3C6D; h = h xor (h ushr 12); h *= 0x297A2D39; h = h xor (h ushr 15)
        return h
    }

    /** The preview wall: '#' cells are blocks (row 0 is the top). */
    val PREVIEW_WALL = listOf(
        "##########....###",
        "#....#..##.##.#.#",
        "#....#..#..##.###",
        "######..##.......",
        "##..##.....#####.",
        "##..######.#...#.",
        "...........#####.",
    )

    /** Tile index for every block in [PREVIEW_WALL]: list of (column, row-from-top, tile). */
    fun previewTiles(spec: Spec, wall: List<String> = PREVIEW_WALL): List<Triple<Int, Int, Int>> {
        val rows = wall.size
        fun solid(x: Int, y: Int): Boolean {
            val r = rows - 1 - y
            return r in 0 until rows && x >= 0 && x < wall[r].length && wall[r][x] == '#'
        }
        val out = ArrayList<Triple<Int, Int, Int>>()
        for (y in 0 until rows) for (x in 0 until wall[0].length) {
            if (solid(x, y)) out += Triple(x, rows - 1 - y, wallTile(spec, x, y, ::solid))
        }
        return out
    }
}
