package com.cocakova.charon.presentation.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.cocakova.charon.R
import com.cocakova.charon.theme.DaybreakPalette
import com.cocakova.charon.theme.LocalCharonPalette
import com.cocakova.charon.theme.NightPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.sin

/**
 * The crossing, staged: Charon's skiff on the braille Styx. Moored at the pier
 * while the Dock is idle; poles off eastward into the mist when a connection is
 * being made; and when a session ends, comes back out of the mist and docks —
 * returning you to shore. Everything is character-grid: waves are braille dot
 * fields, the boat is box-drawing, the lantern is the one point of gold.
 *
 * [arrivals] counts returns from sea; when the Dock mounts with arrivals > 0 the
 * boat starts inside the mist and plays the docking. The skiff is double-ended,
 * as ferries are — Charon just turns and poles the other way.
 */
@Composable
fun StyxCrossing(
    connecting: Boolean,
    arrivals: Int,
    modifier: Modifier = Modifier,
    rows: Int = 8,
    fontSize: TextUnit = 12.sp,
) {
    val context = LocalContext.current
    val ink = if (LocalCharonPalette.current === NightPalette) NightInk else DaybreakInk
    val density = LocalDensity.current
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
                ?: Typeface.MONOSPACE
            textSize = with(density) { fontSize.toPx() }
        }
    }
    val glowPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val charW = remember(paint) { paint.measureText("0") }
    val lineH = remember(paint) {
        paint.fontMetrics.let { it.descent - it.ascent }
    }
    val ascent = remember(paint) { -paint.fontMetrics.ascent }
    val heightDp = with(density) { (lineH * rows).toDp() }

    // One clock, two hands: timeS for sprites/bob/twinkle, seaPhase so the water
    // can quicken during a crossing without a phase jump.
    var timeS by remember { mutableFloatStateOf(0f) }
    var seaPhase by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier.height(heightDp)) {
        val cols = (constraints.maxWidth / charW).toInt().coerceIn(20, 120)
        val moorX = PIER_W + 0.7f
        val awayX = cols + 4f
        val boatX = remember(cols) {
            Animatable(if (arrivals > 0) awayX else moorX)
        }
        var facing by remember { mutableStateOf(Facing.WEST) }

        LaunchedEffect(connecting, cols) {
            if (connecting && boatX.value < awayX) {
                // Cast off: a slow push away from the pier, gathering way east.
                facing = Facing.EAST
                boatX.animateTo(
                    awayX,
                    tween(3400, easing = CubicBezierEasing(0.55f, 0f, 0.85f, 0.7f)),
                )
            } else if (!connecting && boatX.value > moorX + 0.01f) {
                // Return to shore: out of the mist, way coming off, a gentle
                // bump against the pilings, then the drift back onto her lines.
                facing = Facing.WEST
                delay(250)
                boatX.animateTo(moorX - 0.6f, tween(2600, easing = LinearOutSlowInEasing))
                boatX.animateTo(moorX, tween(500, easing = FastOutSlowInEasing))
            }
        }

        LaunchedEffect(connecting) {
            var last = -1L
            while (isActive) {
                // ~30 fps while anything moves, ~10 fps for idle water.
                delay(if (connecting || boatX.isRunning) 33 else 100)
                val now = System.nanoTime()
                if (last > 0) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.25f)
                    timeS += dt
                    seaPhase += dt * if (connecting) 7.5f else 3.2f
                }
                last = now
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            val canvas = drawContext.canvas.nativeCanvas
            val w = cols * 2
            val h = rows * 4
            val t = seaPhase

            // Wave surface height (in dot rows from the top) per dot column.
            val surface = FloatArray(w) { x ->
                val fx = x.toFloat()
                h * 0.55f +
                    h * 0.10f * sin(fx * 0.21f + t * 0.9f) +
                    h * 0.07f * sin(fx * 0.083f - t * 0.55f + 1.7f) +
                    h * 0.04f * sin(fx * 0.37f + t * 1.8f)
            }

            fun mistT(col: Float): Float {
                val start = cols - MIST_COLS.toFloat()
                return ((col - start) / MIST_COLS).coerceIn(0f, 1f)
            }

            fun drawChar(ch: Char, col: Float, row: Float, color: Int, alpha: Float) {
                if (alpha <= 0.01f) return
                paint.color = color
                paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
                canvas.drawText(
                    charArrayOf(ch), 0, 1,
                    col * charW, row * lineH + ascent, paint,
                )
            }

            // Lantern-light on the water: a swaying column of gold dots dropping from
            // the surface beneath a light, each row a little dimmer and a little more
            // adrift — the cheapest trick every harbor at night plays for free.
            fun drawReflection(colF: Float, strength: Float, phase: Float) {
                if (strength <= 0.05f) return
                val dotX = (colF * 2).toInt().coerceIn(0, w - 1)
                val surfRow = surface[dotX] / 4f
                for (i in 0 until 4) {
                    val rr = surfRow + 0.35f + i * 0.72f
                    if (rr > rows - 0.4f) break
                    val sway = (0.25f + 0.12f * i) * sin(timeS * (1.3f + 0.35f * i) + phase + i * 1.9f)
                    val shimmer = 0.6f + 0.4f * sin(timeS * 2.3f + phase * 2f + i * 1.1f)
                    val a = strength * (0.30f - 0.06f * i) * shimmer
                    drawChar(
                        REFLECT_CHARS[(i + (phase * 7).toInt()) % REFLECT_CHARS.size],
                        colF + sway, rr, ink.gold, a,
                    )
                }
            }

            // Stars: sparse single braille dots above the waterline, slow twinkle.
            val skyRows = (rows * 0.42f).toInt()
            for (cy in 0 until skyRows) {
                for (cx in 0 until cols) {
                    val hsh = cellHash(cx, cy, 7)
                    if (hsh < 0.05f) {
                        val tw = 0.5f + 0.5f * sin(timeS * 1.2f + hsh * 125f)
                        drawChar(
                            STAR_CHARS[(hsh * 1000).toInt() % STAR_CHARS.size],
                            cx.toFloat(), cy.toFloat(),
                            ink.mist, 0.06f + 0.11f * tw,
                        )
                    }
                }
            }

            // The Styx itself.
            for (cy in 0 until rows) {
                val depth = ((cy + 1) * 4 - h * 0.55f) / (h * 0.45f)
                if (depth < -0.4f) continue
                val alphaBase = 0.85f - 0.5f * depth.coerceIn(0f, 1f)
                for (cx in 0 until cols) {
                    var bits = 0
                    for (sx in 0 until 2) {
                        val x = cx * 2 + sx
                        for (sy in 0 until 4) {
                            if (cy * 4 + sy >= surface[x]) bits = bits or DOT_BITS[sx][sy]
                        }
                    }
                    if (bits == 0) continue
                    val a = alphaBase * (1f - 0.85f * mistT(cx.toFloat()))
                    drawChar((0x2800 + bits).toChar(), cx.toFloat(), cy.toFloat(), ink.sea, a)
                }
            }

            // The pier: deck planks off the left edge, pilings standing in the water.
            val deckRow = (rows * 0.55f) - 1.3f
            for (i in PIER_CHARS.indices) {
                val line = PIER_CHARS[i]
                for (c in line.indices) {
                    if (line[c] == ' ') continue
                    if (i == 0) {
                        paint.color = ink.occluder
                        paint.alpha = 255
                        canvas.drawRect(
                            c * charW, (deckRow + i) * lineH,
                            (c + 1) * charW, (deckRow + i + 1) * lineH, paint,
                        )
                    }
                    drawChar(line[c], c.toFloat(), deckRow + i, ink.pier, if (i == 0) 0.75f else 0.5f)
                }
            }

            // The pier lamp: a lantern on its post at the shore end, burning gold so
            // the dock is never dark even with the ferry away. Its light pools on the
            // water between the pilings.
            val lampCol = 1f
            val lampRow = deckRow - 2f
            val lampBreath = 0.5f + 0.5f * sin(timeS * 1.1f + 2.3f)
            glowPaint.color = ink.gold
            glowPaint.alpha = ((0.05f + 0.04f * lampBreath) * 255).toInt()
            canvas.drawCircle(
                (lampCol + 0.5f) * charW, (lampRow + 0.4f) * lineH, lineH * 1.6f, glowPaint,
            )
            glowPaint.alpha = ((0.11f + 0.07f * lampBreath) * 255).toInt()
            canvas.drawCircle(
                (lampCol + 0.5f) * charW, (lampRow + 0.4f) * lineH, lineH * 0.75f, glowPaint,
            )
            drawChar('●', lampCol, lampRow, ink.gold, 0.85f + 0.15f * lampBreath)
            drawChar('┃', lampCol, lampRow + 1, ink.pole, 0.55f)
            drawReflection(lampCol + 0.6f, 0.75f, 2.3f)

            // The skiff.
            val bx = boatX.value
            if (bx < cols + BOAT_W) {
                val centerDot = ((bx + BOAT_W / 2f) * 2).toInt().coerceIn(1, w - 2)
                val ride = (surface[centerDot - 1] + surface[centerDot] + surface[centerDot + 1]) / 3f
                val bob = 0.10f * sin(timeS * 2.1f + bx * 0.4f)
                val topRow = ride / 4f - 2.55f + bob
                val boatAlpha = 1f - 0.97f * mistT(bx + BOAT_W / 2f)

                val moving = abs(boatX.velocity) > 0.8f
                val sprite = when {
                    !moving -> if (facing == Facing.WEST) WEST_REST else EAST_REST
                    (timeS % 0.9f) < 0.45f ->
                        if (facing == Facing.WEST) WEST_PUSH else EAST_PUSH
                    else -> if (facing == Facing.WEST) WEST_REST else EAST_REST
                }

                if (boatAlpha > 0.02f) {
                    // Silhouette first: black cells so the waves don't shine through the hull.
                    paint.color = ink.occluder
                    paint.alpha = (boatAlpha * 255).toInt()
                    for (r in sprite.chars.indices) {
                        val line = sprite.chars[r]
                        for (c in line.indices) {
                            if (line[c] == ' ') continue
                            canvas.drawRect(
                                (bx + c) * charW, (topRow + r) * lineH,
                                (bx + c + 1) * charW, (topRow + r + 1) * lineH, paint,
                            )
                        }
                    }
                    // Lantern glow beneath the glyphs: the one warm light on the river.
                    val lantern = sprite.lantern
                    val lx = (bx + lantern.second + 0.5f) * charW
                    val ly = (topRow + lantern.first + 0.5f) * lineH
                    val breathe = 0.5f + 0.5f * sin(timeS * 1.6f)
                    glowPaint.color = ink.gold
                    glowPaint.alpha = ((0.07f + 0.05f * breathe) * boatAlpha * 255).toInt()
                    canvas.drawCircle(lx, ly, lineH * 1.7f, glowPaint)
                    glowPaint.alpha = ((0.14f + 0.08f * breathe) * boatAlpha * 255).toInt()
                    canvas.drawCircle(lx, ly, lineH * 0.8f, glowPaint)

                    for (r in sprite.chars.indices) {
                        val line = sprite.chars[r]
                        val tone = sprite.tones[r]
                        for (c in line.indices) {
                            val ch = line[c]
                            if (ch == ' ') continue
                            val (color, a) = when (tone[c]) {
                                'L' -> ink.gold to 1.0f
                                'C' -> ink.cloak to 0.95f
                                'P' -> ink.pole to 0.85f
                                else -> ink.hull to 0.95f
                            }
                            drawChar(ch, bx + c, topRow + r, color, a * boatAlpha)
                        }
                    }
                    // The ferry's own lantern, mirrored on the river beneath her.
                    drawReflection(bx + sprite.lantern.second, boatAlpha * 0.9f, bx * 0.31f)
                }

                // Wake: bright dots trailing off the stern while under way.
                if (moving) {
                    val westward = facing == Facing.WEST
                    for (i in 1..WAKE_LEN) {
                        val wc = if (westward) bx + BOAT_W - 1 + i else bx - i
                        val wci = wc.toInt()
                        if (wci < 0 || wci >= cols) continue
                        val hsh = cellHash(wci, (seaPhase * 2).toInt(), 13)
                        val wr = surface[(wci * 2).coerceIn(0, w - 1)] / 4f - 0.6f +
                            0.3f * (hsh - 0.5f)
                        drawChar(
                            WAKE_CHARS[(hsh * 100).toInt() % WAKE_CHARS.size],
                            wc, wr, ink.sea,
                            0.65f * (1f - i.toFloat() / WAKE_LEN) * (1f - mistT(wc)),
                        )
                    }
                }
            }

            // The mist bank: the far shore is not for the living to see. Dense and
            // churning where it meets the water, thin wisps up in the sky.
            for (cy in 0 until rows) {
                val overWater = cy >= skyRows
                for (cx in cols - MIST_COLS until cols) {
                    if (cx < 0) continue
                    val m = mistT(cx.toFloat())
                    val hsh = cellHash(cx, cy, (seaPhase * 0.7f).toInt())
                    val density = if (overWater) 0.30f + 0.45f * m else 0.10f + 0.15f * m
                    if (hsh < density) {
                        val chars = if (overWater) MIST_CHARS else STAR_CHARS
                        val a = (if (overWater) 0.05f + 0.22f * m else 0.04f + 0.10f * m) *
                            (0.4f + 0.6f * hsh / density)
                        drawChar(
                            chars[(hsh * 500).toInt() % chars.size],
                            cx.toFloat(), cy.toFloat(), ink.mist, a,
                        )
                    }
                }
            }
        }
    }
}

private enum class Facing { WEST, EAST }

private class Skiff(val chars: List<String>, val tones: List<String>) {
    /** (row, col) of the lantern glyph. */
    val lantern: Pair<Int, Int> = run {
        for (r in tones.indices) {
            val c = tones[r].indexOf('L')
            if (c >= 0) return@run r to c
        }
        0 to 0
    }
}

private const val BOAT_W = 13
private const val PIER_W = 5
private const val MIST_COLS = 8
private const val WAKE_LEN = 9

// Westbound: prow (and lantern) to the left, Charon aft with the pole.
private val WEST_REST = Skiff(
    chars = listOf(
        " ●       ▟▙│ ",
        " ┃       ██│ ",
        "╾┻━━━━━━━━━┷╼",
        " ╲▁▁▁▁▁▁▁▁▁╱ ",
    ),
    tones = listOf(
        " L       CCP ",
        " H       CCP ",
        "HHHHHHHHHHHHH",
        " HHHHHHHHHHH ",
    ),
)

private val WEST_PUSH = Skiff(
    chars = listOf(
        " ●       ▟▙  ",
        " ┃       ██╲ ",
        "╾┻━━━━━━━━━╲╼",
        " ╲▁▁▁▁▁▁▁▁▁╱╲",
    ),
    tones = listOf(
        " L       CC  ",
        " H       CCP ",
        "HHHHHHHHHHHPH",
        " HHHHHHHHHHHP",
    ),
)

private val EAST_REST = Skiff(
    chars = listOf(
        "  │▟▙      ● ",
        "  │██      ┃ ",
        "╾━┷━━━━━━━━┻╼",
        " ╲▁▁▁▁▁▁▁▁▁╱ ",
    ),
    tones = listOf(
        "  PCC      L ",
        "  PCC      H ",
        "HHHHHHHHHHHHH",
        " HHHHHHHHHHH ",
    ),
)

private val EAST_PUSH = Skiff(
    chars = listOf(
        "   ▟▙      ● ",
        "  ╱██      ┃ ",
        "╾╱━━━━━━━━━┻╼",
        "╱╲▁▁▁▁▁▁▁▁▁╱ ",
    ),
    tones = listOf(
        "   CC      L ",
        "  PCC      H ",
        "HPHHHHHHHHHHH",
        "PHHHHHHHHHHH ",
    ),
)

private val PIER_CHARS = listOf(
    "━━━━┓",
    "  ┃ ┃",
    "  ┃ ┃",
)

private val STAR_CHARS = charArrayOf('⠁', '⠂', '⠄', '⠈')
private val WAKE_CHARS = charArrayOf('⠐', '⠂', '⠄', '⠠', '⠆')
private val REFLECT_CHARS = charArrayOf('⠒', '⠔', '⠂', '⠑', '⠄', '⠊')
private val MIST_CHARS = charArrayOf('⠛', '⠿', '⠷', '⠧', '⠻', '⠟', '⠾')

/** The scene's ink, pre-resolved to ARGB ints for the native canvas. Two skies:
 *  night keeps the colors the crossing was born with; daybreak re-inks the same
 *  drawing on paper — dark hull, deep water, gold gone from light to ink. */
private class SceneInk(
    val sea: Int,
    val gold: Int,
    val mist: Int,
    /** The background's own color: hull/pier silhouettes that occlude the waves. */
    val occluder: Int,
    val hull: Int,
    val cloak: Int,
    val pole: Int,
    val pier: Int,
)

private val NightInk = SceneInk(
    sea = NightPalette.water.toArgbInt(),
    gold = NightPalette.coin.toArgbInt(),
    mist = NightPalette.mist.toArgbInt(),
    occluder = NightPalette.night.toArgbInt(),
    hull = 0xFFC7D1D9.toInt(),
    cloak = 0xFF44545E.toInt(),
    pole = 0xFF8FA3AD.toInt(),
    pier = 0xFF6E7F89.toInt(),
)

private val DaybreakInk = SceneInk(
    sea = DaybreakPalette.water.toArgbInt(),
    gold = DaybreakPalette.coin.toArgbInt(),
    mist = DaybreakPalette.mist.toArgbInt(),
    occluder = DaybreakPalette.night.toArgbInt(),
    hull = 0xFF3A4A54.toInt(),
    cloak = 0xFF6E7F89.toInt(),
    pole = 0xFF5C6E78.toInt(),
    pier = 0xFF6E7F89.toInt(),
)

private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
    )

/** Deterministic per-cell noise in [0, 1). */
private fun cellHash(x: Int, y: Int, salt: Int): Float {
    var h = x * 374761393 + y * 668265263 + salt * 2147483647.toInt()
    h = (h xor (h shr 13)) * 1274126177
    h = h xor (h shr 16)
    return (h and 0x7FFFFFFF) / 2147483648f
}

// Braille dot bits by [subcolumn][subrow] (U+2800 encoding order).
private val DOT_BITS = arrayOf(
    intArrayOf(0x01, 0x02, 0x04, 0x40),
    intArrayOf(0x08, 0x10, 0x20, 0x80),
)
