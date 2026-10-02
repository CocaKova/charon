package com.cocakova.charon.presentation.terminal

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import com.cocakova.charon.R
import com.cocakova.charon.ssh.TerminalSession
import com.cocakova.charon.terminal.Apparition
import com.cocakova.charon.terminal.CellAttrs
import com.cocakova.charon.terminal.CellExt
import com.cocakova.charon.terminal.Line
import com.cocakova.charon.terminal.SearchEngine
import com.cocakova.charon.terminal.TerminalEmulator
import com.cocakova.charon.terminal.TextSelection
import com.cocakova.charon.terminal.UrlScanner

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
    fontSizeSp: Float = 14f,
    /** The bell draws its ripple (the helm can still it). */
    bellRipples: Boolean = true,
    onRequestFocus: () -> Unit = {},
    onZoom: (Float) -> Unit = {},
    /** Decoded shades, shared with the lightbox so a tap costs no second decode. */
    apparitions: ApparitionCache? = null,
    /** A shade was touched: the lightbox opens on it. */
    onApparitionTap: (Apparition) -> Unit = {},
    /** Dredge results to wash over the grid: every hit, plus the one the eye is on. */
    search: SearchEngine.SearchState? = null,
    /** A marked passage (OSC 8 link) was touched: the confirm sheet opens on it. */
    onLinkTap: (LinkSighting) -> Unit = {},
    /** A link id to wash in the livery's accent — the one the sheet is showing. */
    highlightLink: Int = 0,
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

    // The cursor's shape (DECSCUSR) morphs rather than snaps: vim's insert bar grows
    // out of the block and shrinks back in ~120 ms. Read through the flow's own
    // value so a tab switch never borrows the last tab's shape for a frame.
    val styleTick by session.cursorStyle.collectAsState()
    val cursorShape = cursorShapeOf(session.cursorStyle.value.also { styleTick })
    var shapeFrom by remember(session) { mutableStateOf(cursorShape) }
    var shapeTo by remember(session) { mutableStateOf(cursorShape) }
    val morph = remember(session) { Animatable(1f) }
    LaunchedEffect(session, cursorShape) {
        if (cursorShape == shapeTo) return@LaunchedEffect
        shapeFrom = shapeTo
        shapeTo = cursorShape
        morph.snapTo(0f)
        morph.animateTo(1f, tween(CURSOR_MORPH_MS, easing = FastOutSlowInEasing))
    }

    // The bell as a ripple: one teal ring opening from the cursor cell, then still.
    val bellTick by session.bell.collectAsState()
    val bellNow = session.bell.value.also { bellTick }
    var bellSeen by remember(session) { mutableLongStateOf(bellNow) }
    val ripple = remember(session) { Animatable(1f) }
    LaunchedEffect(session, bellNow) {
        if (bellNow == bellSeen) return@LaunchedEffect
        bellSeen = bellNow
        if (!bellRipples) return@LaunchedEffect
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(BELL_RIPPLE_MS, easing = FastOutSlowInEasing))
    }

    // Two-gear render loop. Hot: ride the frame clock while output is streaming
    // (floods skip straight to the latest grid, cursor sits solid). Idle: a bare
    // withFrameNanos loop keeps the Choreographer pumping at the display's refresh
    // rate forever — 120 wakeups/s to blink a cursor twice a second — so once
    // nothing has arrived for a grace window, the loop parks on the session's
    // outputTick and blinks on a timer instead. The first byte of new output wakes
    // it back into the hot gear within a frame, so echo latency is untouched.
    var frame by remember { mutableLongStateOf(0L) }
    var cursorOn by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(session, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastDrawn = -1L
            var lastChangeNanos = 0L
            while (true) {
                // Hot gear: per-frame against the generation counter.
                while (true) {
                    val now = withFrameNanos { it }
                    val g = session.term.generation
                    // Synchronized output (2026): the program is mid-frame; keep the
                    // last finished one on the glass until it says done (or times out).
                    if (g != lastDrawn && !session.term.syncHolding()) {
                        lastDrawn = g
                        lastChangeNanos = now // output resets the blink: cursor solid while streaming
                        frame++
                    }
                    val on = ((now - lastChangeNanos) / CURSOR_BLINK_NANOS) % 2 == 0L
                    if (on != cursorOn) {
                        cursorOn = on
                        frame++
                    }
                    if (now - lastChangeNanos > STREAM_GRACE_NANOS) break
                }
                // Idle gear: 2 wakeups/s. Snapshot the tick BEFORE re-checking the
                // generation — a burst landing between the two reads is then caught
                // by the check (drop back to hot), and one landing after it trips
                // first{} immediately off the StateFlow's current value. No gap.
                var tick = session.outputTick.value
                if (session.term.generation != lastDrawn) continue
                while (true) {
                    // A steady cursor (DECSCUSR 2/4/6) has nothing to blink: park on
                    // the wire alone, no timer at all.
                    val steady = cursorShapeSteady(session.term.cursorStyle)
                    val woke = withTimeoutOrNull(if (steady) Long.MAX_VALUE else CURSOR_BLINK_NANOS / 1_000_000) {
                        session.outputTick.first { it != tick }
                    }
                    if (woke != null) break // the remote spoke — back to the frame clock
                    cursorOn = !cursorOn
                    frame++
                }
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
            val cols = ((size.width - 2 * paints.padX) / paints.cellWidth).toInt().coerceAtLeast(4)
            val rows = ((size.height - 2 * paints.padY) / paints.cellHeight).toInt().coerceAtLeast(2)
            session.resize(cols, rows, paints.cellWidth.toInt(), paints.cellHeight.toInt())
        }
    }

    // Two coordinate spaces meet at the glass. Mouse reporting wants viewport cells
    // (what the remote app drew where); selection wants buffer cells (negative rows =
    // scrollback) so a selection stays glued to its text while the view scrolls.
    fun viewCellOf(pos: Offset): TextSelection.Cell {
        val col = ((pos.x - paints.padX) / paints.cellWidth).toInt()
            .coerceIn(0, (session.term.cols - 1).coerceAtLeast(0))
        val row = ((pos.y - paints.padY) / paints.cellHeight).toInt()
            .coerceIn(0, (session.term.rows - 1).coerceAtLeast(0))
        return TextSelection.Cell(row, col)
    }

    fun selCellOf(pos: Offset): TextSelection.Cell {
        val v = viewCellOf(pos)
        return TextSelection.Cell(v.row - session.scrollOffset.value, v.col)
    }

    /** The shade under a finger, if the finger landed on one. */
    fun apparitionUnder(pos: Offset): Apparition? = synchronized(session.lock) {
        if (session.term.apparitions.store.count == 0) return@synchronized null
        val hits = visibleApparitions(
            session.term, session.scrollOffset.value, paints.cellWidth, paints.cellHeight,
        )
        apparitionAt(hits, pos.x - paints.padX, pos.y - paints.padY)?.image
    }

    /**
     * The marked passage under a finger, if the cell there wears an OSC 8 link —
     * with the link's words on that row (the run of cells sharing its id), so the
     * sheet can set what the text says beside where it really leads.
     */
    fun linkUnder(pos: Offset): LinkSighting? = synchronized(session.lock) {
        val term = session.term
        val v = viewCellOf(pos)
        val line = term.screen.viewLine(session.scrollOffset.value, v.row)
        val col = v.col.coerceAtMost(line.cols - 1)
        val id = if (line.ext == null) 0 else CellExt.linkId(line.extAt(col))
        if (id == 0) {
            // No mark: a URL printed as plain text, read across soft wraps.
            val selRow = v.row - session.scrollOffset.value
            val screen = term.screen
            val plain = UrlScanner.linkAt(
                { r -> if (r >= -screen.scrollbackSize && r < term.rows) screen.relativeLine(r) else null },
                selRow, col,
            ) ?: return@synchronized null
            return@synchronized LinkSighting(plain.url, 0, plain.url, TextSelection.Cell(selRow, col))
        }
        val uri = term.hyperlinks.uri(id) ?: return@synchronized null
        var from = col
        while (from > 0 && CellExt.linkId(line.extAt(from - 1)) == id) from--
        var to = col
        while (to < line.cols - 1 && CellExt.linkId(line.extAt(to + 1)) == id) to++
        val text = buildString {
            for (c in from..to) {
                if (!CellAttrs.hasStyle(line.attrs[c], CellAttrs.WIDE_CONTINUATION)) append(line.textAt(c))
            }
        }
        LinkSighting(uri, id, text, TextSelection.Cell(v.row - session.scrollOffset.value, col))
    }
    val currentOnLinkTap by rememberUpdatedState(onLinkTap)

    // Selection edge-crawl: while a select-drag holds at the glass's top or bottom,
    // the viewport steps a row at a time (+1 = older) and the selection's focus rides
    // the revealed edge — how a finger reaches text that's off-screen.
    var selEdgeDrag by remember { mutableStateOf(0) }
    var selEdgeCol by remember { mutableStateOf(0) }
    LaunchedEffect(session) {
        snapshotFlow { selEdgeDrag }.collectLatest { dir ->
            while (dir != 0) {
                session.scrollBy(dir)
                val edgeViewRow = if (dir > 0) 0 else session.term.rows - 1
                session.extendSelection(
                    TextSelection.Cell(edgeViewRow - session.scrollOffset.value, selEdgeCol),
                )
                delay(80)
            }
        }
    }

    // The zoom sink must outlive recomposition: the pinch handler below is keyed on
    // Unit so the gesture survives the font-size changes it causes itself.
    val currentOnZoom by rememberUpdatedState(onZoom)

    Canvas(
        modifier = modifier
            .onSizeChanged { pendingSize = it }
            // Pinch to zoom the font; single-finger has zoom == 1 so this stays inert.
            // Keyed on Unit, NOT paints: every zoom tick rebuilds paints, and keying on
            // them restarted this pointerInput mid-gesture — the pinch died after one
            // delta and each squeeze only moved the font a hair. The gesture must ride
            // through the very recompositions it triggers.
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) currentOnZoom(zoom)
                }
            }
            // Tap: clear an active selection; in a mouse app send the click AND make
            // sure the keyboard is up (tmux-with-mouse used to swallow every tap, so
            // the keyboard could never be raised — showSoftInput is a no-op when it
            // already shows); otherwise just focus and raise.
            //
            // Marked passages (OSC 8): a tap opens the link sheet — unless the app
            // has the mouse, in which case the click is the app's (it drew the link
            // and may well answer clicks on it). A long-press reaches the link in
            // every mode, the same way long-press select works inside mouse apps;
            // the sheet's "select" falls back to the word select it replaced.
            .pointerInput(session, paints) {
                detectTapGestures(
                    onLongPress = { pos ->
                        val link = linkUnder(pos)
                        if (link != null) currentOnLinkTap(link) else session.selectWordAt(selCellOf(pos))
                    },
                    onTap = { pos ->
                        // A shade answers the tap before anything else: on a phone an
                        // inline image is a photo you can open, not a dead rectangle.
                        val shade = apparitionUnder(pos)
                        val link = if (session.mouseActive) null else linkUnder(pos)
                        when {
                            session.selection.value != null -> session.clearSelection()
                            shade != null -> onApparitionTap(shade)
                            link != null -> currentOnLinkTap(link)
                            session.mouseActive -> {
                                session.mouseClick(viewCellOf(pos))
                                onRequestFocus()
                            }
                            else -> onRequestFocus()
                        }
                    },
                )
            }
            // Drag: extend a selection when the drag starts on it; in a mouse app send
            // wheel notches; otherwise scroll our own scrollback (the selection now
            // survives that — grab clear water to scroll, grab the selection to grow
            // it). One notch per cell-height dragged.
            .pointerInput(session, paints) {
                var mode = DragMode.NONE
                var startCell = TextSelection.Cell(0, 0)
                var lastMouseCell = TextSelection.Cell(0, 0)
                var accum = 0f
                detectDragGestures(
                    onDragStart = { pos ->
                        startCell = viewCellOf(pos)
                        accum = 0f
                        val sel = session.selection.value
                        mode = when {
                            sel != null && nearSelection(sel, selCellOf(pos)) -> DragMode.SELECT
                            // An app that reports drags (1002/1003) gets one when the
                            // finger sets off sideways; up and down stay the wheel, so
                            // scrolling tmux or vim feels exactly as it always did.
                            session.mouseMode >= 1002 -> DragMode.MOUSE_PENDING
                            session.mouseActive -> DragMode.WHEEL
                            else -> DragMode.SCROLL
                        }
                    },
                    onDrag = { change, drag ->
                        if (mode == DragMode.MOUSE_PENDING) {
                            mode = if (abs(drag.x) > abs(drag.y)) {
                                session.mouseDown(startCell)
                                lastMouseCell = startCell
                                DragMode.MOUSE_DRAG
                            } else {
                                DragMode.WHEEL
                            }
                        }
                        when (mode) {
                            DragMode.MOUSE_DRAG -> {
                                val cell = viewCellOf(change.position)
                                if (cell != lastMouseCell) {
                                    lastMouseCell = cell
                                    session.mouseDrag(cell)
                                }
                            }
                            DragMode.SELECT -> {
                                session.extendSelection(selCellOf(change.position))
                                // Past the glass top/bottom: crawl the viewport so the
                                // selection can keep growing into scrollback / back to live.
                                selEdgeDrag = when {
                                    change.position.y < paints.cellHeight * 0.5f -> 1
                                    change.position.y > size.height - paints.cellHeight * 0.5f -> -1
                                    else -> 0
                                }
                                selEdgeCol = selCellOf(change.position).col
                            }
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
                            DragMode.NONE, DragMode.MOUSE_PENDING -> {}
                        }
                    },
                    onDragEnd = {
                        if (mode == DragMode.MOUSE_DRAG) session.mouseUp(lastMouseCell)
                        mode = DragMode.NONE
                        selEdgeDrag = 0
                    },
                    onDragCancel = {
                        if (mode == DragMode.MOUSE_DRAG) session.mouseUp(lastMouseCell)
                        mode = DragMode.NONE
                        selEdgeDrag = 0
                    },
                )
            },
    ) {
        frame // subscribe: redraw whenever the emulator generation advances
        selection // subscribe: redraw when the selection changes
        scrollOffset // subscribe: redraw when the viewport scrolls
        val cursor = CursorLook(shapeFrom, shapeTo, morph.value, ripple.value)
        drawIntoCanvas { canvas ->
            synchronized(session.lock) {
                drawTerminal(
                    canvas.nativeCanvas, session.term, paints,
                    size.width, size.height, cursorOn, selection, scrollOffset,
                    session.cursorColor, apparitions, search, highlightLink, cursor,
                )
            }
        }
    }
}

private enum class DragMode { NONE, SELECT, WHEEL, SCROLL, MOUSE_PENDING, MOUSE_DRAG }

/** The three cursor shapes DECSCUSR can ask for. */
enum class CursorShape { BLOCK, UNDERLINE, BAR }

/** DECSCUSR's number as a shape: 0–2 block, 3–4 underline, 5–6 bar. */
fun cursorShapeOf(style: Int): CursorShape = when (style) {
    3, 4 -> CursorShape.UNDERLINE
    5, 6 -> CursorShape.BAR
    else -> CursorShape.BLOCK
}

/** Even DECSCUSR numbers (2, 4, 6) are steady; 0, 1, 3, 5 blink. */
fun cursorShapeSteady(style: Int): Boolean = style == 2 || style == 4 || style == 6

/**
 * A drag "grabs" the selection when it starts within a row of it; anywhere else the
 * drag scrolls. This is what lets a selection survive scrolling — it no longer
 * hijacks every drag on the screen.
 */
private fun nearSelection(sel: TerminalSession.Selection, cell: TextSelection.Cell): Boolean {
    val lo = minOf(sel.anchor.row, sel.focus.row) - 1
    val hi = maxOf(sel.anchor.row, sel.focus.row) + 1
    return cell.row in lo..hi
}

/** How the cursor is drawn this frame: mid-morph between two shapes, plus the bell's ring. */
class CursorLook(
    val from: CursorShape,
    val to: CursorShape,
    /** 0 = still [from], 1 = fully [to]. */
    val morph: Float,
    /** The bell's ripple, 0..1; 1 = no ripple showing. */
    val ripple: Float,
)

private const val CURSOR_MORPH_MS = 120
private const val BELL_RIPPLE_MS = 480

/** The water's glow: the cursor is Styx.water, the one always-on brand mark in the grid. */
private const val CURSOR_TEAL = 0x3ECFB2
/** The dredge washes: teal for every sighting, gold for the one the eye is on. */
private const val SEARCH_TEAL = 0x3ECFB2
private const val SEARCH_GOLD = 0xD9A441
private const val CURSOR_BLINK_NANOS = 530_000_000L
/** A link's own dotted underline: present, never louder than the text it marks. */
private const val LINK_ALPHA = 150

/** How long after the last remote burst the render loop keeps riding the frame
 *  clock before parking on [TerminalSession.outputTick] — long enough that
 *  intermittent streams (a build, htop's refresh) never feel a gear change. */
private const val STREAM_GRACE_NANOS = 500_000_000L

class TerminalPaints(val regular: Typeface, val bold: Typeface, textSizePx: Float) {
    val text = Paint().apply {
        typeface = regular
        textSize = textSizePx
        isAntiAlias = true
        // Subpixel + hinting: mono glyphs stay crisp and evenly weighted at small sizes.
        isSubpixelText = true
        hinting = Paint.HINTING_ON
    }
    val fill = Paint()

    /** The duration whisper beside a finished prompt: smaller, quieter than the text. */
    val whisper = Paint().apply {
        typeface = regular
        textSize = textSizePx * 0.78f
        isAntiAlias = true
        isSubpixelText = true
    }

    /** Bitmaps go down filtered — a scaled photo should not look like a mosaic. */
    val image = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val srcRect = Rect()
    private val dstRect = Rect()

    /** Scratch rects, reused: an image pass must not allocate per frame. */
    fun srcScratch(): Rect = srcRect
    fun dstScratch(): Rect = dstRect

    val cellWidth = text.measureText("M")
    private val fm = text.fontMetrics
    private val glyphHeight = fm.descent - fm.ascent
    // A touch of leading so rows breathe: tight mono reads cramped on a phone. The
    // glyph is re-centered in the taller cell so the extra space splits above/below.
    val cellHeight = glyphHeight * LINE_SPACING
    val baselineOffset = -fm.ascent + (cellHeight - glyphHeight) / 2f
    // A slim gutter so the grid isn't jammed into the screen's corner.
    val padX = cellWidth * 0.5f
    val padY = cellHeight * 0.30f

    // The deep colors: underline geometry in cell space, all derived from the text
    // size so a pinch-zoom carries every style with it. Each style is drawn by hand
    // (the platform's underline can't curl, dot, or take its own color), and each
    // pattern repeats per cell so a run broken by an attr change joins seamlessly.
    val ulThickness = maxOf(1f, textSizePx / 15f)
    /** Centre of a single underline, below the baseline, kept inside the cell. */
    val ulY = minOf(baselineOffset + maxOf(ulThickness * 1.5f, textSizePx * 0.11f), cellHeight - ulThickness)
    /** The upper of the two double-underline strokes; the lower sits 2 strokes down. */
    val ulDoubleY = minOf(ulY, cellHeight - ulThickness * 2.5f)
    /** Undercurl: one full wave per cell, amplitude a stroke-ish, hung a hair low. */
    val curlAmp = maxOf(1f, ulThickness * 1.1f)
    val curlY = minOf(ulY + curlAmp * 0.5f, cellHeight - curlAmp - ulThickness * 0.5f)
    /** Dotted: square dots a stroke wide, an exact whole number per cell. */
    val dotStep = cellWidth / maxOf(1, Math.round(cellWidth / (ulThickness * 2.5f)))
    val deco = Paint()
    val curl = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = ulThickness
        strokeJoin = Paint.Join.ROUND
    }
    /** Reused wave path — rewound, never reallocated, per run. */
    val wave = Path()

    /** The cursor's rectangle, reused per frame. */
    val cursorRect = android.graphics.RectF()

    /** The bell's ring. */
    val ring = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
    }

    private companion object { const val LINE_SPACING = 1.16f }
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
    cursorColor: Int = CURSOR_TEAL,
    apparitions: ApparitionCache? = null,
    search: SearchEngine.SearchState? = null,
    highlightLink: Int = 0,
    cursorLook: CursorLook? = null,
) {
    val defaultFg = if (term.reverseVideo) term.defaultBg else term.defaultFg
    val defaultBg = if (term.reverseVideo) term.defaultFg else term.defaultBg

    p.fill.color = opaque(defaultBg)
    canvas.drawRect(0f, 0f, width, height, p.fill)

    // Everything from here draws in grid space, offset into the gutter.
    canvas.save()
    canvas.translate(p.padX, p.padY)

    val cw = p.cellWidth
    val ch = p.cellHeight
    val sb = StringBuilder(term.cols)

    // Shades rise before the text: an image is the backdrop its caption sits on, and
    // a placement that overhangs the glass is clipped rather than painted over chrome.
    if (apparitions != null && term.apparitions.store.count > 0) {
        val hits = visibleApparitions(term, scrollOffset, cw, ch)
        if (hits.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(0f, 0f, width - p.padX, height - p.padY)
            drawApparitions(canvas, hits, apparitions, p.image, p.srcScratch(), p.dstScratch())
            canvas.restore()
        }
    }

    // Selection tint under the glyphs: a translucent wash of the river's teal.
    if (selection != null) {
        drawSelection(canvas, p, term, selection, cw, ch, scrollOffset)
    }
    // Dredge hits wash under the glyphs too: teal for every sighting, gold for
    // the one the eye is on. Drawn after the selection so the gold stays visible.
    if (search != null && search.hits.isNotEmpty()) {
        drawSearchHits(canvas, p, term, search, cw, ch, scrollOffset)
    }

    for (row in 0 until term.rows) {
        val line = term.screen.viewLine(scrollOffset, row)
        val top = row * ch
        val baseline = top + p.baselineOffset
        // Extended attrs (undercurl, underline color, links) — null on nearly every line.
        val lineExt = line.ext
        var col = 0
        while (col < term.cols) {
            val attrs = line.attrs[col]
            if (CellAttrs.hasStyle(attrs, CellAttrs.WIDE_CONTINUATION)) {
                col++
                continue
            }
            val ext = if (lineExt != null) lineExt[col] else 0L // no boxing in the draw loop
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
                    if (lineExt != null && lineExt[end] != ext) break
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
            val linkId = CellExt.linkId(ext)
            // The link the sheet is showing glows as a whole — every cell of it,
            // split across rows or not, since they share one id.
            if (linkId != 0 && linkId == highlightLink) {
                p.fill.color = opaque(cursorColor)
                p.fill.alpha = 80
                canvas.drawRect(left, top, right, top + ch, p.fill)
                p.fill.alpha = 255
            }
            if (!CellAttrs.hasStyle(attrs, CellAttrs.INVISIBLE)) {
                val alpha = if (CellAttrs.hasStyle(attrs, CellAttrs.FAINT)) 140 else 255
                p.text.color = opaque(fg)
                p.text.typeface = if (CellAttrs.hasStyle(attrs, CellAttrs.BOLD)) p.bold else p.regular
                p.text.alpha = alpha
                p.text.isStrikeThruText = CellAttrs.hasStyle(attrs, CellAttrs.STRIKETHROUGH)

                // Underlines go down before the glyphs so descenders sit over them.
                val ulStyle = CellExt.underlineStyle(attrs, ext)
                if (ulStyle != CellExt.UL_NONE) {
                    drawUnderline(canvas, p, ulStyle, left, right, top, resolveUl(ext, term, fg), alpha)
                } else if (linkId != 0) {
                    // A marked passage the remote didn't underline itself: a quiet
                    // dotted line in the livery's accent, so links read as links.
                    drawUnderline(canvas, p, CellExt.UL_DOTTED, left, right, top, cursorColor, LINK_ALPHA)
                }

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
        // A finished command's whisper at the row's far end ("✓ 2m14s", "✕ exit 2"),
        // only where the prompt line leaves room for it — never over the text.
        if (!term.usingAlt) line.promptMark?.whisper()?.let { words ->
            drawWhisper(canvas, p, line, words, line.promptMark!!.failed, baseline, term.cols, cw, defaultFg, term.palette[1])
        }
    }

    // Cursor: the livery's accent in whatever shape the program asked for (DECSCUSR),
    // morphing between shapes. A block is translucent so the glyph stays readable,
    // and its blink's off-phase leaves a hairline outline — the cursor never
    // vanishes; a bar or underline dims instead. Steady shapes don't blink at all.
    // Hidden while scrolled back — it isn't where you're looking.
    if (term.cursorVisible && scrollOffset == 0) {
        val wideCursor = CellAttrs.hasStyle(
            term.screen.line(term.cursorY).attrs[term.cursorX], CellAttrs.WIDE,
        )
        val left = term.cursorX * cw
        val top = term.cursorY * ch
        val width = (if (wideCursor) 2 else 1) * cw
        val look = cursorLook ?: CursorLook(CursorShape.BLOCK, CursorShape.BLOCK, 1f, 1f)
        val on = cursorOn || cursorShapeSteady(term.cursorStyle)
        val r = p.cursorRect
        cursorBox(look.from, left, top, width, ch, p, r)
        val fl = r.left; val ft = r.top; val fr = r.right; val fb = r.bottom
        cursorBox(look.to, left, top, width, ch, p, r)
        val t = look.morph.coerceIn(0f, 1f)
        r.set(lerp(fl, r.left, t), lerp(ft, r.top, t), lerp(fr, r.right, t), lerp(fb, r.bottom, t))
        val block = look.to == CursorShape.BLOCK && t >= 1f
        p.fill.color = opaque(cursorColor)
        when {
            on -> {
                p.fill.alpha = if (block) 170 else 225
                canvas.drawRect(r, p.fill)
            }
            block -> {
                p.fill.alpha = 140
                p.fill.style = Paint.Style.STROKE
                p.fill.strokeWidth = p.cellWidth * 0.09f
                canvas.drawRect(r, p.fill)
                p.fill.style = Paint.Style.FILL
            }
            else -> {
                p.fill.alpha = 80
                canvas.drawRect(r, p.fill)
            }
        }
        p.fill.alpha = 255
        // The bell: one ring opening out of the cursor cell, fading as it goes.
        if (look.ripple < 1f) {
            val q = look.ripple
            p.ring.color = opaque(cursorColor)
            p.ring.alpha = ((1f - q) * 200).toInt()
            p.ring.strokeWidth = maxOf(1.5f, p.cellWidth * 0.12f) * (1f - 0.5f * q)
            canvas.drawCircle(left + width / 2f, top + ch / 2f, ch * (0.55f + 3.2f * q), p.ring)
        }
    }

    canvas.restore()
}

/**
 * Wash the selected cells teal, computing each row's span like the copy does. The
 * selection lives in buffer space (negative rows = scrollback); each row maps onto
 * the viewport through [scrollOffset], and rows off the glass simply don't draw.
 */
private fun drawSelection(
    canvas: android.graphics.Canvas,
    p: TerminalPaints,
    term: TerminalEmulator,
    selection: TerminalSession.Selection,
    cw: Float,
    ch: Float,
    scrollOffset: Int,
) {
    val a = selection.anchor
    val b = selection.focus
    val (start, end) = if (a.row < b.row || (a.row == b.row && a.col <= b.col)) a to b else b to a
    p.fill.color = CURSOR_TEAL
    p.fill.alpha = 70
    // Clamp the walk to the visible window up front — a select-all over a deep
    // scrollback must not iterate thousands of off-screen rows every frame.
    val firstVisible = maxOf(start.row, -scrollOffset)
    val lastVisible = minOf(end.row, term.rows - 1 - scrollOffset)
    for (row in firstVisible..lastVisible) {
        val viewRow = row + scrollOffset
        val from = if (row == start.row) start.col.coerceIn(0, term.cols - 1) else 0
        val to = if (row == end.row) end.col.coerceIn(0, term.cols - 1) else term.cols - 1
        val left = from * cw
        val right = (to + 1) * cw
        val top = viewRow * ch
        canvas.drawRect(left, top, right, top + ch, p.fill)
    }
    p.fill.alpha = 255
}

/**
 * Wash the dredge hits over the grid: a translucent teal for every sighting, gold
 * for the one the eye is on. Hits live in selection space (negative rows =
 * scrollback), so each maps onto the viewport exactly like the selection does —
 * off-glass rows simply don't draw.
 */
private fun drawSearchHits(
    canvas: android.graphics.Canvas,
    p: TerminalPaints,
    term: TerminalEmulator,
    search: SearchEngine.SearchState,
    cw: Float,
    ch: Float,
    scrollOffset: Int,
) {
    val firstVisible = -scrollOffset
    val lastVisible = term.rows - 1 - scrollOffset
    // Hits name their lines by lasting number; today's push count maps them onto
    // rows, so the wash stays on its words between refreshes while output scrolls.
    val pushed = term.screen.linesPushed
    p.fill.alpha = 84
    for ((i, hit) in search.hits.withIndex()) {
        val firstRow = hit.row(pushed)
        val lastRow = (hit.endLine - pushed).toInt()
        if (lastRow < firstVisible || firstRow > lastVisible) continue
        p.fill.color = if (i == search.current) SEARCH_GOLD else SEARCH_TEAL
        // A hit the grid wrapped runs to the row's end, then on from the next row's start.
        for (row in maxOf(firstRow, firstVisible)..minOf(lastRow, lastVisible)) {
            val from = if (row == firstRow) hit.start else 0
            val to = if (row == lastRow) hit.end else term.cols - 1
            val top = (row + scrollOffset) * ch
            canvas.drawRect(from * cw, top, (to + 1) * cw, top + ch, p.fill)
        }
    }
    p.fill.alpha = 255
}

/**
 * One underline across [left, right) of the row at [top]. Allocation-free: rects for
 * the straight styles, the shared [TerminalPaints.wave] rewound for the curl. Every
 * pattern starts on a cell boundary and repeats per cell, so neighbouring runs meet
 * without a seam.
 */
private fun drawUnderline(
    canvas: android.graphics.Canvas,
    p: TerminalPaints,
    style: Int,
    left: Float,
    right: Float,
    top: Float,
    color: Int,
    alpha: Int,
) {
    val t = p.ulThickness
    val half = t / 2f
    p.deco.color = opaque(color)
    p.deco.alpha = alpha
    when (style) {
        CellExt.UL_DOUBLE -> {
            val y = top + p.ulDoubleY
            canvas.drawRect(left, y - half, right, y + half, p.deco)
            canvas.drawRect(left, y + 2 * t - half, right, y + 2 * t + half, p.deco)
        }
        CellExt.UL_CURLY -> {
            // A smooth wave from alternating quadratic arcs: each half-period's
            // control point sits at twice the amplitude, which puts the crest at
            // exactly the amplitude and matches slopes where arcs meet — no kinks,
            // at any zoom.
            val c = top + p.curlY
            val halfWave = p.cellWidth / 2f
            val w = p.wave
            w.rewind()
            w.moveTo(left, c)
            var x = left
            var up = true
            while (x < right - 0.5f) {
                val peak = if (up) c - 2 * p.curlAmp else c + 2 * p.curlAmp
                w.quadTo(x + halfWave / 2f, peak, x + halfWave, c)
                x += halfWave
                up = !up
            }
            p.curl.color = opaque(color)
            p.curl.alpha = alpha
            canvas.drawPath(w, p.curl)
        }
        CellExt.UL_DOTTED -> {
            val y = top + p.ulY
            var x = left
            while (x < right - 0.5f) {
                canvas.drawRect(x, y - half, x + t, y + half, p.deco)
                x += p.dotStep
            }
        }
        CellExt.UL_DASHED -> {
            // One dash per cell, a little over half its width.
            val y = top + p.ulY
            val dash = p.cellWidth * 0.55f
            var x = left
            while (x < right - 0.5f) {
                canvas.drawRect(x + p.cellWidth * 0.1f, y - half, x + p.cellWidth * 0.1f + dash, y + half, p.deco)
                x += p.cellWidth
            }
        }
        else -> {
            val y = top + p.ulY
            canvas.drawRect(left, y - half, right, y + half, p.deco)
        }
    }
}

/** SGR 58's color when set, else the glyph's own (already inverse-resolved) fg. */
private fun resolveUl(ext: Long, term: TerminalEmulator, fg: Int): Int =
    when (CellExt.ulMode(ext)) {
        CellAttrs.MODE_PALETTE -> term.palette[CellExt.ulColor(ext)]
        CellAttrs.MODE_RGB -> CellExt.ulColor(ext)
        else -> fg
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

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** A shape's box inside the cursor cell: the whole cell, a floor, or a left post. */
private fun cursorBox(
    shape: CursorShape,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    p: TerminalPaints,
    out: android.graphics.RectF,
) {
    when (shape) {
        CursorShape.BLOCK -> out.set(left, top, left + width, top + height)
        CursorShape.UNDERLINE -> {
            val t = maxOf(2f, height * 0.12f)
            out.set(left, top + height - t, left + width, top + height)
        }
        CursorShape.BAR -> {
            val t = maxOf(2f, p.cellWidth * 0.16f)
            out.set(left, top, left + t, top + height)
        }
    }
}

/** The whisper beside a prompt, right-aligned in the grid, skipped when the line is too full. */
private fun drawWhisper(
    canvas: android.graphics.Canvas,
    p: TerminalPaints,
    line: Line,
    words: String,
    failed: Boolean,
    baseline: Float,
    cols: Int,
    cw: Float,
    defaultFg: Int,
    /** The livery's own red (ANSI 1), so aground reads right on paper and on night. */
    red: Int,
) {
    var lastInk = cols - 1
    while (lastInk >= 0 && (line.codePoints[lastInk] == Line.SPACE || line.codePoints[lastInk] == 0)) lastInk--
    val width = p.whisper.measureText(words)
    val right = cols * cw - cw * 0.25f
    val left = right - width
    if (left < (lastInk + 2) * cw) return
    p.whisper.color = if (failed) opaque(red) else opaque(defaultFg)
    p.whisper.alpha = if (failed) 200 else 110
    canvas.drawText(words, left, baseline - (p.text.textSize - p.whisper.textSize) * 0.15f, p.whisper)
    p.whisper.alpha = 255
}
