package com.cocakova.charon.presentation.terminal

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import com.cocakova.charon.R
import com.cocakova.charon.ssh.TerminalSession
import com.cocakova.charon.terminal.CellAttrs
import com.cocakova.charon.terminal.Line
import com.cocakova.charon.terminal.TerminalEmulator
import com.cocakova.charon.terminal.TextSelection

/**
 * The grid renderer: run-batched `nativeCanvas.drawText` with cached Paints, frame-
 * paced via [withFrameNanos] against the emulator's generation counter (floods skip
 * straight to the latest grid — frames are never queued). ASCII same-attr runs draw
 * as single calls; wide/fallback/combining glyphs draw individually, centered in
 * their cell span, so column alignment survives font fallback.
 */
@Composable
fun TerminalView(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 13f,
    onRequestFocus: () -> Unit = {},
    onZoom: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textSizePx = with(density) { fontSizeSp.sp.toPx() }
    val paints = remember(textSizePx) {
        val regular = ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
        val bold = ResourcesCompat.getFont(context, R.font.jetbrains_mono_bold)
            ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        TerminalPaints(regular, bold, textSizePx)
    }
    val selection by session.selection.collectAsState()
    val scrollOffset by session.scrollOffset.collectAsState()

    var frame by remember { mutableLongStateOf(0L) }
    var cursorOn by remember { mutableStateOf(true) }
    LaunchedEffect(session) {
        var lastDrawn = -1L
        var lastChangeNanos = 0L
        while (true) {
            val now = withFrameNanos { it }
            val g = session.term.generation
            if (g != lastDrawn) {
                lastDrawn = g
                lastChangeNanos = now // output resets the blink: cursor solid while streaming
                frame++
            }
            val on = ((now - lastChangeNanos) / CURSOR_BLINK_NANOS) % 2 == 0L
            if (on != cursorOn) {
                cursorOn = on
                frame++
            }
        }
    }

    // Debounced grid resize: the IME animates the canvas per frame, and resizing the
    // emulator + WINCHing the PTY 20 times per transition makes remote TUIs thrash.
    // The grid re-snaps once, ~120ms after the size settles; while animating we just
    // draw the old grid clipped.
    var pendingSize by remember { mutableStateOf<IntSize?>(null) }
    LaunchedEffect(session, paints) {
        snapshotFlow { pendingSize }.filterNotNull().collectLatest { size ->
            delay(120)
            val cols = (size.width / paints.cellWidth).toInt().coerceAtLeast(4)
            val rows = (size.height / paints.cellHeight).toInt().coerceAtLeast(2)
            session.resize(cols, rows, paints.cellWidth.toInt(), paints.cellHeight.toInt())
        }
    }

    fun cellOf(pos: Offset): TextSelection.Cell {
        val col = (pos.x / paints.cellWidth).toInt().coerceIn(0, (session.term.cols - 1).coerceAtLeast(0))
        val row = (pos.y / paints.cellHeight).toInt().coerceIn(0, (session.term.rows - 1).coerceAtLeast(0))
        return TextSelection.Cell(row, col)
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { pendingSize = it }
            // Pinch to zoom the font; single-finger has zoom == 1 so this stays inert.
            .pointerInput(paints) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) onZoom(zoom)
                }
            }
            // Tap: clear an active selection, else send a mouse click (mouse apps),
            // else focus the input and raise the keyboard.
            .pointerInput(session, paints) {
                detectTapGestures(
                    onLongPress = { pos -> session.selectWordAt(cellOf(pos)) },
                    onTap = { pos ->
                        when {
                            session.selection.value != null -> session.clearSelection()
                            session.mouseActive -> session.mouseClick(cellOf(pos))
                            else -> onRequestFocus()
                        }
                    },
                )
            }
            // Drag: extend a long-press selection; in a mouse app send wheel notches;
            // otherwise scroll our own scrollback. One notch per cell-height dragged.
            .pointerInput(session, paints) {
                var mode = DragMode.NONE
                var startCell = TextSelection.Cell(0, 0)
                var accum = 0f
                detectDragGestures(
                    onDragStart = { pos ->
                        startCell = cellOf(pos)
                        accum = 0f
                        mode = when {
                            session.selection.value != null -> DragMode.SELECT // from long-press
                            session.mouseActive -> DragMode.WHEEL
                            else -> DragMode.SCROLL
                        }
                    },
                    onDrag = { change, drag ->
                        when (mode) {
                            DragMode.SELECT -> session.extendSelection(cellOf(change.position))
                            DragMode.WHEEL -> {
                                accum += drag.y
                                while (accum >= paints.cellHeight) {
                                    session.mouseWheel(up = true, startCell); accum -= paints.cellHeight
                                }
                                while (accum <= -paints.cellHeight) {
                                    session.mouseWheel(up = false, startCell); accum += paints.cellHeight
                                }
                            }
                            DragMode.SCROLL -> {
                                // Drag down reveals older lines (offset grows); drag up returns.
                                accum += drag.y
                                while (accum >= paints.cellHeight) {
                                    session.scrollBy(1); accum -= paints.cellHeight
                                }
                                while (accum <= -paints.cellHeight) {
                                    session.scrollBy(-1); accum += paints.cellHeight
                                }
                            }
                            DragMode.NONE -> {}
                        }
                    },
                    onDragEnd = { mode = DragMode.NONE },
                    onDragCancel = { mode = DragMode.NONE },
                )
            },
    ) {
        frame // subscribe: redraw whenever the emulator generation advances
        selection // subscribe: redraw when the selection changes
        scrollOffset // subscribe: redraw when the viewport scrolls
        drawIntoCanvas { canvas ->
            synchronized(session.lock) {
                drawTerminal(
                    canvas.nativeCanvas, session.term, paints,
                    size.width, size.height, cursorOn, selection, scrollOffset,
                )
            }
        }
    }
}

private enum class DragMode { NONE, SELECT, WHEEL, SCROLL }

/** The water's glow: the cursor is StyxTeal, the one always-on brand mark in the grid. */
private const val CURSOR_TEAL = 0x3ECFB2
private const val CURSOR_BLINK_NANOS = 530_000_000L

class TerminalPaints(val regular: Typeface, val bold: Typeface, textSizePx: Float) {
    val text = Paint().apply {
        typeface = regular
        textSize = textSizePx
        isAntiAlias = true
    }
    val fill = Paint()
    val cellWidth = text.measureText("M")
    private val fm = text.fontMetrics
    val cellHeight = fm.descent - fm.ascent
    val baselineOffset = -fm.ascent
}

private fun drawTerminal(
    canvas: android.graphics.Canvas,
    term: TerminalEmulator,
    p: TerminalPaints,
    width: Float,
    height: Float,
    cursorOn: Boolean,
    selection: TerminalSession.Selection?,
    scrollOffset: Int,
) {
    val defaultFg = if (term.reverseVideo) term.defaultBg else term.defaultFg
    val defaultBg = if (term.reverseVideo) term.defaultFg else term.defaultBg

    p.fill.color = opaque(defaultBg)
    canvas.drawRect(0f, 0f, width, height, p.fill)

    val cw = p.cellWidth
    val ch = p.cellHeight
    val sb = StringBuilder(term.cols)

    // Selection tint under the glyphs: a translucent wash of the river's teal.
    if (selection != null) {
        drawSelection(canvas, p, term, selection, cw, ch)
    }

    for (row in 0 until term.rows) {
        val line = term.screen.viewLine(scrollOffset, row)
        val top = row * ch
        val baseline = top + p.baselineOffset
        var col = 0
        while (col < term.cols) {
            val attrs = line.attrs[col]
            if (CellAttrs.hasStyle(attrs, CellAttrs.WIDE_CONTINUATION)) {
                col++
                continue
            }
            val cp = line.codePoints[col]
            val wide = CellAttrs.hasStyle(attrs, CellAttrs.WIDE)
            val simple = !wide && cp in 0x20..0x7E && line.combiningAt(col) == null

            // Extend a batchable run of simple same-attr cells.
            var end = col + 1
            if (simple) {
                while (end < term.cols) {
                    val a2 = line.attrs[end]
                    val c2 = line.codePoints[end]
                    if (a2 != attrs || c2 !in 0x20..0x7E || line.combiningAt(end) != null) break
                    end++
                }
            }

            val fg = resolveFg(attrs, term, defaultFg, defaultBg)
            val bg = resolveBg(attrs, term, defaultFg, defaultBg)
            val cells = if (wide) 2 else end - col
            val left = col * cw
            val right = left + cells * cw

            if (bg != defaultBg) {
                p.fill.color = opaque(bg)
                canvas.drawRect(left, top, right, top + ch, p.fill)
            }
            if (!CellAttrs.hasStyle(attrs, CellAttrs.INVISIBLE)) {
                p.text.color = opaque(fg)
                p.text.typeface = if (CellAttrs.hasStyle(attrs, CellAttrs.BOLD)) p.bold else p.regular
                p.text.alpha = if (CellAttrs.hasStyle(attrs, CellAttrs.FAINT)) 140 else 255
                p.text.isUnderlineText = CellAttrs.hasStyle(attrs, CellAttrs.UNDERLINE)
                p.text.isStrikeThruText = CellAttrs.hasStyle(attrs, CellAttrs.STRIKETHROUGH)

                if (simple) {
                    sb.setLength(0)
                    for (i in col until end) sb.append(line.codePoints[i].toChar())
                    canvas.drawText(sb, 0, sb.length, left, baseline, p.text)
                } else if (cp != Line.SPACE || line.combiningAt(col) != null) {
                    // Individual glyph (wide/unicode/combining): center it in its span
                    // so fallback-font advances can't break the column grid.
                    val text = line.textAt(col)
                    val advance = p.text.measureText(text)
                    val x = left + (cells * cw - advance) / 2f
                    canvas.drawText(text, x, baseline, p.text)
                }
            }
            col += cells
        }
    }

    // Cursor: teal block over text (translucent, the glyph stays readable); the
    // blink's off-phase leaves a hairline outline so the cursor never vanishes.
    // Hidden while scrolled back — it isn't where you're looking.
    if (term.cursorVisible && scrollOffset == 0) {
        val wideCursor = CellAttrs.hasStyle(
            term.screen.line(term.cursorY).attrs[term.cursorX], CellAttrs.WIDE,
        )
        val left = term.cursorX * cw
        val top = term.cursorY * ch
        val right = (term.cursorX + if (wideCursor) 2 else 1) * cw
        p.fill.color = opaque(CURSOR_TEAL)
        if (cursorOn) {
            p.fill.alpha = 170
            canvas.drawRect(left, top, right, top + ch, p.fill)
        } else {
            p.fill.alpha = 140
            p.fill.style = Paint.Style.STROKE
            p.fill.strokeWidth = p.cellWidth * 0.09f
            canvas.drawRect(left, top, right, top + ch, p.fill)
            p.fill.style = Paint.Style.FILL
        }
        p.fill.alpha = 255
    }
}

/** Wash the selected cells teal, computing each row's span like the copy does. */
private fun drawSelection(
    canvas: android.graphics.Canvas,
    p: TerminalPaints,
    term: TerminalEmulator,
    selection: TerminalSession.Selection,
    cw: Float,
    ch: Float,
) {
    val a = selection.anchor
    val b = selection.focus
    val (start, end) = if (a.row < b.row || (a.row == b.row && a.col <= b.col)) a to b else b to a
    val firstRow = start.row.coerceIn(0, term.rows - 1)
    val lastRow = end.row.coerceIn(0, term.rows - 1)
    p.fill.color = CURSOR_TEAL
    p.fill.alpha = 70
    for (row in firstRow..lastRow) {
        val from = if (row == firstRow) start.col.coerceIn(0, term.cols - 1) else 0
        val to = if (row == lastRow) end.col.coerceIn(0, term.cols - 1) else term.cols - 1
        val left = from * cw
        val right = (to + 1) * cw
        val top = row * ch
        canvas.drawRect(left, top, right, top + ch, p.fill)
    }
    p.fill.alpha = 255
}

private fun resolveFg(attrs: Long, term: TerminalEmulator, defaultFg: Int, defaultBg: Int): Int {
    val inverse = CellAttrs.hasStyle(attrs, CellAttrs.INVERSE)
    val raw = when (CellAttrs.fgMode(attrs)) {
        CellAttrs.MODE_PALETTE -> {
            var idx = CellAttrs.fgColor(attrs)
            // bold brightens the base 8, the classic terminal convention
            if (CellAttrs.hasStyle(attrs, CellAttrs.BOLD) && idx < 8) idx += 8
            term.palette[idx]
        }
        CellAttrs.MODE_RGB -> CellAttrs.fgColor(attrs)
        else -> defaultFg
    }
    return if (inverse) resolveBgRaw(attrs, term, defaultBg) else raw
}

private fun resolveBg(attrs: Long, term: TerminalEmulator, defaultFg: Int, defaultBg: Int): Int {
    val inverse = CellAttrs.hasStyle(attrs, CellAttrs.INVERSE)
    return if (inverse) {
        when (CellAttrs.fgMode(attrs)) {
            CellAttrs.MODE_PALETTE -> term.palette[CellAttrs.fgColor(attrs)]
            CellAttrs.MODE_RGB -> CellAttrs.fgColor(attrs)
            else -> defaultFg
        }
    } else {
        resolveBgRaw(attrs, term, defaultBg)
    }
}

private fun resolveBgRaw(attrs: Long, term: TerminalEmulator, defaultBg: Int): Int =
    when (CellAttrs.bgMode(attrs)) {
        CellAttrs.MODE_PALETTE -> term.palette[CellAttrs.bgColor(attrs)]
        CellAttrs.MODE_RGB -> CellAttrs.bgColor(attrs)
        else -> defaultBg
    }

private fun opaque(rgb: Int): Int = 0xFF000000.toInt() or (rgb and 0xFFFFFF)
