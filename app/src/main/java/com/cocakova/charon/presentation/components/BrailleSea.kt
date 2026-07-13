package com.cocakova.charon.presentation.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * The waters of the Styx — Charon's signature visual. An animated sea rendered in
 * braille cells (U+2800 block): each character is a 2x4 dot matrix, so a cols x rows
 * text grid gives a (2*cols) x (4*rows) dot field, enough resolution for layered
 * waves at text size. Deliberately text, not pixels: the app's identity is character-
 * grid to the bone. Runs at ~10 fps to stay kind to the battery.
 */
@Composable
fun BrailleSea(
    modifier: Modifier = Modifier,
    rows: Int = 5,
    fontSize: TextUnit = 14.sp,
    color: Color = MaterialTheme.colorScheme.primary,
    frameMillis: Long = 100,
) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(frameMillis) {
        while (true) {
            delay(frameMillis)
            tick++
        }
    }
    BoxWithConstraints(modifier) {
        val cols = with(LocalDensity.current) {
            (constraints.maxWidth / (fontSize.toPx() * 0.62f)).toInt().coerceIn(8, 120)
        }
        val sea = remember(tick, cols, rows) { renderSea(cols, rows, tick) }
        Column {
            sea.forEachIndexed { i, line ->
                val depth = if (rows <= 1) 0f else i / (rows - 1f)
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize,
                    lineHeight = fontSize,
                    color = color.copy(alpha = 0.9f - 0.55f * depth),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

// Braille dot bits by [subcolumn][subrow]: dots 1-3 and 7 in the left column,
// dots 4-6 and 8 in the right (U+2800 encoding order).
private val DOT_BITS = arrayOf(
    intArrayOf(0x01, 0x02, 0x04, 0x40),
    intArrayOf(0x08, 0x10, 0x20, 0x80),
)

internal fun renderSea(cols: Int, rows: Int, tick: Int): List<String> {
    val t = tick * 0.32f
    val w = cols * 2
    val h = rows * 4
    // Wave surface height in dot rows from the top: three layered sines — a slow
    // swell, a broad drift, and a fast ripple.
    val surface = FloatArray(w) { x ->
        val fx = x.toFloat()
        h * 0.42f +
            h * 0.16f * sin(fx * 0.21f + t * 0.9f) +
            h * 0.11f * sin(fx * 0.083f - t * 0.55f + 1.7f) +
            h * 0.06f * sin(fx * 0.37f + t * 1.8f)
    }
    return (0 until rows).map { cy ->
        buildString(cols) {
            for (cx in 0 until cols) {
                var bits = 0
                for (sx in 0 until 2) {
                    val x = cx * 2 + sx
                    for (sy in 0 until 4) {
                        if (cy * 4 + sy >= surface[x]) bits = bits or DOT_BITS[sx][sy]
                    }
                }
                append((0x2800 + bits).toChar())
            }
        }
    }
}
