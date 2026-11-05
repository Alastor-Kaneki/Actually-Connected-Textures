package com.alastor.ctmforge.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.alastor.ctmforge.core.Ctm
import com.alastor.ctmforge.core.Png
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Decodes a PNG from a pack into an ImageBitmap, off the main thread. */
@Composable
fun rememberPackImage(key: Any?, load: () -> ByteArray?): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, key) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = load() ?: return@runCatching null
                val img = Png.decode(bytes)
                Bitmap.createBitmap(img.px, img.width, img.height, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
        }
    }
    return image
}

/**
 * A little wall of blocks drawn with the converted tile sheet, picking tiles the same way
 * the mod does in game. [connected] = false shows every block as a lone block (no mod).
 */
@Composable
fun CtmWallPreview(sheet: ImageBitmap?, spec: String, connected: Boolean, modifier: Modifier = Modifier) {
    val parsed = remember(spec) { Ctm.parseSpec("textures/blocks/ctm/$spec/x") }
    val wall = Ctm.PREVIEW_WALL
    val cols = wall[0].length
    val rows = wall.size
    val tiles = remember(parsed, connected) {
        when {
            parsed == null -> emptyList()
            connected -> Ctm.previewTiles(parsed)
            else -> Ctm.previewTiles(parsed).map { (x, y, _) ->
                Triple(x, y, if (parsed.method in setOf("horizontal", "vertical", "hv", "vh")) 3 else 0)
            }
        }
    }
    val c = MaterialTheme.colorScheme
    // glass is mostly white edges: keep the backdrop on the darker side in both themes
    val light = c.background.luminance() > 0.5f
    val skyTop = if (light) c.primary else c.primaryContainer
    val skyBottom = if (light) c.tertiary else c.tertiaryContainer
    val checker = c.onSurface.copy(alpha = 0.06f)
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio((cols + 1) / (rows + 1).toFloat())
            .clip(RoundedCornerShape(16)),
    ) {
        drawRect(Brush.verticalGradient(listOf(skyTop, skyBottom)))
        // half a block of margin around the wall
        val cell = size.width / (cols + 1)
        val half = cell / 2
        for (y in 0 until (rows + 1) * 2) for (x in 0 until (cols + 1) * 2) {
            if ((x + y) % 2 == 0) drawRect(checker, topLeft = Offset(x * half, y * half), size = Size(half, half))
        }
        if (sheet == null || parsed == null) return@Canvas
        val tw = sheet.width / parsed.cols
        val th = sheet.height / parsed.rows
        if (tw <= 0 || th <= 0) return@Canvas
        for ((x, y, t) in tiles) {
            val x0 = (half + x * cell).roundToInt()
            val y0 = (half + y * cell).roundToInt()
            val x1 = (half + (x + 1) * cell).roundToInt()
            val y1 = (half + (y + 1) * cell).roundToInt()
            drawImage(
                sheet,
                srcOffset = IntOffset((t % parsed.cols) * tw, (t / parsed.cols) * th),
                srcSize = IntSize(tw, th),
                dstOffset = IntOffset(x0, y0),
                dstSize = IntSize(x1 - x0, y1 - y0),
                filterQuality = FilterQuality.None,
            )
        }
    }
}
