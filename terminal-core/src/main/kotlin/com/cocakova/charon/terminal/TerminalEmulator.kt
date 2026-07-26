package com.cocakova.charon.terminal

import java.util.BitSet

/**
 * The terminal: grid mutation + mode state driven by [Parser] actions. Pure Kotlin,
 * no Android — all correctness is JVM-testable.
 *
 * Threading: single-writer. The session reader thread feeds bytes via [write]; the
 * renderer reads the grid. Synchronization is the caller's concern (the app locks
 * around write/read; the emulator itself stays lock-free and fast).
 */
class TerminalEmulator(
    initialCols: Int,
    initialRows: Int,
    scrollbackLines: Int = 10_000,
    private val onResponse: (String) -> Unit = {},
    private val onBell: () -> Unit = {},
    private val onTitle: (String) -> Unit = {},
    /** Scheme hooks: themed ANSI-16 base (see [Palette]) and default fg/bg. */
    basePalette: IntArray? = null,
    private val initialFg: Int = 0xE6EDF3,
    private val initialBg: Int = 0x000000,
) : ParserSink {

    var cols: Int = initialCols
        private set
    var rows: Int = initialRows
        private set

    val primary = ScreenBuffer(initialCols, initialRows, scrollbackLines)
    val alt = ScreenBuffer(initialCols, initialRows, 0)
    var usingAlt = false
        private set
    val screen: ScreenBuffer get() = if (usingAlt) alt else primary

    var cursorX = 0
        private set
    var cursorY = 0
        private set
    private var pendingWrap = false
    private var attrs = CellAttrs.DEFAULT

    // Modes
    var autowrap = true; private set
    var originMode = false; private set
    var insertMode = false; private set
    var cursorKeysApp = false; private set
    var keypadApp = false; private set
    var cursorVisible = true; private set
    var bracketedPaste = false; private set
    var reverseVideo = false; private set
    var focusEvents = false; private set
    var mouseMode = 0; private set          // 0 off, else 9/1000/1002/1003
    var mouseSgr = false; private set
    var reverseWraparound = false; private set // DECSET 45 (xterm reverse-wrap)
    var cursorStyle = 1; private set        // DECSCUSR: 0/1 blink block … 6 steady bar
    private var linefeedMode = false        // LNM

    private var scrollTop = 0
    private var scrollBottom = initialRows - 1

    private var tabStops = defaultTabStops(initialCols)

    private var g0 = TermCharsets.ASCII
    private var g1 = TermCharsets.ASCII
    private var glIsG1 = false

    private class SavedCursor(
        var x: Int = 0, var y: Int = 0, var attrs: Long = CellAttrs.DEFAULT,
        var g0: Char = TermCharsets.ASCII, var g1: Char = TermCharsets.ASCII,
        var glIsG1: Boolean = false, var originMode: Boolean = false,
        var pendingWrap: Boolean = false,
    )
    private val savedPrimary = SavedCursor()
    private val savedAlt = SavedCursor()
    private val saved: SavedCursor get() = if (usingAlt) savedAlt else savedPrimary

    val palette = Palette(basePalette)
    var defaultFg = initialFg
    var defaultBg = initialBg

    /** Pixel cell size, set by the renderer; used for CSI 14t reports. */
    var cellWidthPx = 8
    var cellHeightPx = 16

    var title = ""
        private set

    /**
     * OSC 133 semantic-prompt relay: (kind, extra) where kind ∈ A/B/C/D and extra
     * is D's exit code when the shell sent one. Mutable so hosts can wire it
     * without touching the constructor; invoked from the writer's thread.
     */
    var onShellMark: ((Char, Int?) -> Unit)? = null

    /** Bumped on every visible mutation; renderers conflate on this. */
    var generation = 0L
        private set

    private val dirtyRows = BitSet(initialRows)
    private var allDirty = true
    private var lastPrinted = -1

    private val parser = Parser(this)
    private val decoder = Utf8Decoder { parser.feed(it) }

    /** Feed raw bytes from the SSH channel. */
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        decoder.feed(bytes, offset, length)
    }

    /** Feed already-decoded text (tests, local echo). */
    fun write(text: String) {
        parser.feed(text)
    }

    /**
     * Drain dirty state: returns null if nothing changed since last call, an empty
     * BitSet if everything is dirty, else the set of dirty rows.
     */
    fun drainDirty(): BitSet? {
        if (allDirty) {
            allDirty = false
            dirtyRows.clear()
            return ALL_DIRTY
        }
        if (dirtyRows.isEmpty) return null
        val out = dirtyRows.clone() as BitSet
        dirtyRows.clear()
        return out
    }

    // ------------------------------------------------------------------ ParserSink

    override fun print(codePoint: Int) {
        var cp = codePoint
        val activeCharset = if (glIsG1) g1 else g0
        if (activeCharset == TermCharsets.DEC_SPECIAL && cp in 0x5F..0x7E) {
            cp = TermCharsets.mapDecSpecial(cp)
        }
        val width = Wcwidth.of(cp)
        if (width == 0) {
            attachCombining(cp)
            return
        }

        if (pendingWrap) {
            if (autowrap) {
                cursorX = 0
                linefeed()
                screen.line(cursorY).isWrapped = true
            }
            pendingWrap = false
        }

        // A wide char that doesn't fit in the remaining columns wraps early (autowrap)
        // or is dropped at the hard margin.
        if (width == 2 && cursorX == cols - 1) {
            if (autowrap) {
                // blank the orphan last column with current attrs, then wrap
                screen.line(cursorY).set(cursorX, Line.SPACE, attrs)
                markDirty(cursorY)
                cursorX = 0
                linefeed()
                screen.line(cursorY).isWrapped = true
            } else {
                return
            }
        }

        val line = screen.line(cursorY)
        if (insertMode) line.insertCells(cursorX, width, attrs)
        clobberWide(line, cursorX)
        if (width == 2) {
            line.set(cursorX, cp, attrs or CellAttrs.WIDE)
            if (cursorX + 1 < cols) {
                clobberWide(line, cursorX + 1)
                line.set(cursorX + 1, Line.SPACE, attrs or CellAttrs.WIDE_CONTINUATION)
            }
        } else {
            line.set(cursorX, cp, attrs)
        }
        markDirty(cursorY)
        lastPrinted = cp

        val nextX = cursorX + width
        if (nextX >= cols) {
            cursorX = cols - 1
            if (autowrap) pendingWrap = true
        } else {
            cursorX = nextX
        }
        touch()
    }

    override fun execute(control: Int) {
        when (control) {
            0x07 -> onBell()
            0x08 -> when {
                // xterm reverse-wrap (mode 45): BS on a just-wrapped-pending cell only
                // annuls the pending wrap; BS at the left edge climbs to the previous
                // row's last column, staying inside the scroll region (top wraps to
                // bottom — xterm's post-2018 margin-confined behavior).
                reverseWraparound && autowrap && pendingWrap -> pendingWrap = false
                reverseWraparound && autowrap && cursorX == 0 -> {
                    cursorX = cols - 1
                    cursorY = when {
                        cursorY == scrollTop -> scrollBottom
                        cursorY > 0 -> cursorY - 1
                        else -> rows - 1
                    }
                }
                else -> { if (cursorX > 0) cursorX--; pendingWrap = false }
            }
            0x09 -> tabForward(1)
            0x0A, 0x0B, 0x0C -> {
                linefeed()
                if (linefeedMode) cursorX = 0
            }
            0x0D -> { cursorX = 0; pendingWrap = false }
            0x0E -> glIsG1 = true  // SO
            0x0F -> glIsG1 = false // SI
            0x84 -> linefeed()                       // IND
            0x85 -> { linefeed(); cursorX = 0 }      // NEL
            0x88 -> tabStops.set(cursorX)            // HTS
            0x8D -> reverseIndex()                   // RI
            else -> {} // remaining controls ignored
        }
        touch()
    }

    override fun escDispatch(collected: String, final: Char) {
        when {
            collected.isEmpty() -> when (final) {
                '7' -> saveCursor()
                '8' -> restoreCursor()
                'D' -> linefeed()
                'E' -> { linefeed(); cursorX = 0 }
                'H' -> tabStops.set(cursorX)
                'M' -> reverseIndex()
                'Z' -> onResponse("$CSI?62;1;6;9;15;22c") // DECID: same as DA1
                'c' -> fullReset()
                '=' -> keypadApp = true
                '>' -> keypadApp = false
                else -> {}
            }
            collected == "(" -> g0 = final
            collected == ")" -> g1 = final
            collected == "#" && final == '8' -> decAlignmentTest()
            else -> {}
        }
        touch()
    }

    override fun csiDispatch(params: CsiParams, collected: String, final: Char) {
        when (collected) {
            "" -> ansiCsi(params, final)
            "?" -> when (final) {
                'h' -> for (i in 0 until maxOf(params.count, 1)) decMode(params.get(i, 0), true)
                'l' -> for (i in 0 until maxOf(params.count, 1)) decMode(params.get(i, 0), false)
                'n' -> when (params.get(0, 0)) {
                    6 -> onResponse("$CSI?${reportRow()};${cursorX + 1}R")
                    15 -> onResponse("$CSI?13n")      // no printer
                    25 -> onResponse("$CSI?20n")      // UDKs unlocked
                    26 -> onResponse("$CSI?27;1;0;0n") // keyboard: North American
                }
                else -> {}
            }
            ">" -> if (final == 'c') onResponse("$CSI>41;377;0c") // DA2: xterm-ish
            "!" -> if (final == 'p') softReset()                  // DECSTR
            " " -> if (final == 'q') cursorStyle = params.get(0, 1) // DECSCUSR
            else -> {}
        }
        touch()
    }

    private fun ansiCsi(params: CsiParams, final: Char) {
        when (final) {
            'A' -> moveCursor(cursorX, cursorY - params.getOr1(0), clampToRegion = true)
            'B' -> moveCursor(cursorX, cursorY + params.getOr1(0), clampToRegion = true)
            'C' -> moveCursor(cursorX + params.getOr1(0), cursorY)
            'D' -> if (reverseWraparound && autowrap) cursorBackWrapping(params.getOr1(0))
                   else moveCursor(cursorX - params.getOr1(0), cursorY)
            'E' -> moveCursor(0, cursorY + params.getOr1(0), clampToRegion = true)
            'F' -> moveCursor(0, cursorY - params.getOr1(0), clampToRegion = true)
            'G' -> moveCursor(params.getOr1(0) - 1, cursorY)
            'H', 'f' -> cursorPosition(params.getOr1(0), params.getOr1(1))
            'I' -> tabForward(params.getOr1(0))
            'J' -> eraseDisplay(params.get(0, 0))
            'K' -> eraseLine(params.get(0, 0))
            'L' -> insertLines(params.getOr1(0))
            'M' -> deleteLines(params.getOr1(0))
            'P' -> { screen.line(cursorY).deleteCells(cursorX, params.getOr1(0), eraseAttr()); markDirty(cursorY); pendingWrap = false }
            'S' -> screen.scrollRegionUp(scrollTop, scrollBottom, params.getOr1(0), eraseAttr(), keepHistory = false).also { markAllDirty() }
            'T' -> screen.scrollRegionDown(scrollTop, scrollBottom, params.getOr1(0), eraseAttr()).also { markAllDirty() }
            'X' -> {
                val n = params.getOr1(0).coerceAtMost(cols - cursorX)
                screen.line(cursorY).fill(cursorX, cursorX + n, Line.SPACE, eraseAttr())
                markDirty(cursorY)
                pendingWrap = false
            }
            'Z' -> tabBackward(params.getOr1(0))
            '@' -> { screen.line(cursorY).insertCells(cursorX, params.getOr1(0), eraseAttr()); markDirty(cursorY); pendingWrap = false }
            '`' -> moveCursor(params.getOr1(0) - 1, cursorY)
            'a' -> moveCursor(cursorX + params.getOr1(0), cursorY)
            'b' -> if (lastPrinted > 0) repeat(params.getOr1(0).coerceAtMost(cols * rows)) { print(lastPrinted) }
            'c' -> onResponse("$CSI?62;1;6;9;15;22c") // DA1: VT220-class w/ color
            'd' -> moveCursor(cursorX, toAbsoluteRow(params.getOr1(0)), clampToRegion = originMode)
            'e' -> moveCursor(cursorX, cursorY + params.getOr1(0), clampToRegion = true)
            'g' -> when (params.get(0, 0)) {
                0 -> tabStops.clear(cursorX)
                3 -> tabStops.clear()
            }
            'h' -> for (i in 0 until params.count) ansiMode(params.get(i, 0), true)
            'l' -> for (i in 0 until params.count) ansiMode(params.get(i, 0), false)
            'm' -> applySgr(params)
            'n' -> when (params.get(0, 0)) {
                5 -> onResponse("${CSI}0n")
                6 -> onResponse("$CSI${reportRow()};${cursorX + 1}R")
            }
            'r' -> setScrollRegion(params.get(0, 1), params.get(1, rows))
            's' -> saveCursor()
            'u' -> restoreCursor()
            // DECREQTPARM → DECREPTPARM: no parity, 8 bits, 38400bd both ways, 16x clock.
            'x' -> when (val sol = params.get(0, 0)) {
                0, 1 -> onResponse("$CSI${sol + 2};1;1;128;128;1;0x")
            }
            't' -> when (params.get(0, 0)) {
                14 -> onResponse("${CSI}4;${rows * cellHeightPx};${cols * cellWidthPx}t")
                18 -> onResponse("${CSI}8;$rows;${cols}t")
            }
            else -> {}
        }
    }

    override fun oscDispatch(payload: String) {
        val sep = payload.indexOf(';')
        val code = (if (sep >= 0) payload.substring(0, sep) else payload).toIntOrNull() ?: return
        val arg = if (sep >= 0) payload.substring(sep + 1) else ""
        when (code) {
            0, 2 -> { title = arg; onTitle(arg) }
            1 -> {} // icon name — ignored
            4 -> oscPalette(arg)
            10 -> oscColor(arg, 10) { defaultFg = it }
            11 -> oscColor(arg, 11) { defaultBg = it }
            104 -> if (arg.isEmpty()) palette.reset() else arg.split(';').forEach {
                it.toIntOrNull()?.let { i -> palette.resetEntry(i) }
            }
            110 -> defaultFg = initialFg
            111 -> defaultBg = initialBg
            // OSC 133 shell integration (semantic prompts): A = prompt start,
            // B = prompt end, C = command output begins, D[;exit] = command done.
            // The emulator only relays the marks; meaning lives with the session.
            133 -> {
                val kind = arg.firstOrNull()
                if (kind != null) {
                    val extra = arg.substringAfter(';', "")
                        .takeWhile { it.isDigit() }.toIntOrNull()
                    onShellMark?.invoke(kind, extra)
                }
            }
            else -> {} // OSC 52 clipboard lands in v0.4 behind consent
        }
        touch()
    }

    override fun dcsHook(params: CsiParams, collected: String, final: Char) {}
    override fun dcsPut(codePoint: Int) {}
    override fun dcsUnhook() {}

    // ------------------------------------------------------------------ operations

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        primary.resize(newCols, newRows)
        alt.resize(newCols, newRows)
        val oldCols = cols
        cols = newCols
        rows = newRows
        scrollTop = 0
        scrollBottom = rows - 1
        if (newCols != oldCols) tabStops = defaultTabStops(newCols)
        cursorX = cursorX.coerceIn(0, cols - 1)
        cursorY = cursorY.coerceIn(0, rows - 1)
        pendingWrap = false
        markAllDirty()
        touch()
    }

    private fun linefeed() {
        pendingWrap = false
        if (cursorY == scrollBottom) {
            screen.scrollRegionUp(scrollTop, scrollBottom, 1, eraseAttr(), keepHistory = !usingAlt)
            markAllDirty()
        } else if (cursorY < rows - 1) {
            cursorY++
        }
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (cursorY == scrollTop) {
            screen.scrollRegionDown(scrollTop, scrollBottom, 1, eraseAttr())
            markAllDirty()
        } else if (cursorY > 0) {
            cursorY--
        }
    }

    private fun attachCombining(cp: Int) {
        val targetX = when {
            pendingWrap -> cursorX               // mark belongs to the just-written last cell
            cursorX > 0 -> cursorX - 1
            else -> return
        }
        // If the target is a wide-continuation cell, attach to the wide base instead.
        val line = screen.line(cursorY)
        val x = if (CellAttrs.hasStyle(line.attrs[targetX], CellAttrs.WIDE_CONTINUATION) && targetX > 0) {
            targetX - 1
        } else targetX
        line.appendCombining(x, cp)
        markDirty(cursorY)
        touch()
    }

    /** Overwriting half of a wide pair must blank the other half. */
    private fun clobberWide(line: Line, x: Int) {
        val a = line.attrs[x]
        if (CellAttrs.hasStyle(a, CellAttrs.WIDE) && x + 1 < cols) {
            line.set(x + 1, Line.SPACE, CellAttrs.withoutStyle(line.attrs[x + 1], CellAttrs.WIDE_CONTINUATION))
        } else if (CellAttrs.hasStyle(a, CellAttrs.WIDE_CONTINUATION) && x > 0) {
            line.set(x - 1, Line.SPACE, CellAttrs.withoutStyle(line.attrs[x - 1], CellAttrs.WIDE))
        }
    }

    private fun moveCursor(x: Int, y: Int, clampToRegion: Boolean = false) {
        val (top, bottom) = if (clampToRegion && cursorY in scrollTop..scrollBottom) {
            scrollTop to scrollBottom
        } else {
            0 to rows - 1
        }
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = y.coerceIn(top, bottom)
        pendingWrap = false
    }

    private fun cursorPosition(row1: Int, col1: Int) {
        val y = toAbsoluteRow(row1)
        val maxY = if (originMode) scrollBottom else rows - 1
        val minY = if (originMode) scrollTop else 0
        cursorX = (col1 - 1).coerceIn(0, cols - 1)
        cursorY = y.coerceIn(minY, maxY)
        pendingWrap = false
    }

    private fun toAbsoluteRow(row1: Int): Int =
        if (originMode) scrollTop + row1 - 1 else row1 - 1

    private fun reportRow(): Int =
        if (originMode) cursorY - scrollTop + 1 else cursorY + 1

    private fun tabForward(n: Int) {
        pendingWrap = false
        repeat(n) {
            val next = tabStops.nextSetBit(cursorX + 1)
            cursorX = if (next in 1 until cols) next else cols - 1
        }
    }

    private fun tabBackward(n: Int) {
        pendingWrap = false
        repeat(n) {
            val prev = if (cursorX > 0) tabStops.previousSetBit(cursorX - 1) else -1
            cursorX = if (prev >= 0) prev else 0
        }
    }

    private fun eraseDisplay(mode: Int) {
        pendingWrap = false
        val ea = eraseAttr()
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorY + 1 until rows) { screen.line(r).clear(ea); markDirty(r) }
            }
            1 -> {
                eraseLine(1)
                for (r in 0 until cursorY) { screen.line(r).clear(ea); markDirty(r) }
            }
            2 -> { screen.clearAll(ea); markAllDirty() }
            3 -> { primary.clearScrollback(); touch() }
        }
    }

    private fun eraseLine(mode: Int) {
        pendingWrap = false
        val line = screen.line(cursorY)
        val ea = eraseAttr()
        when (mode) {
            0 -> { line.fill(cursorX, cols, Line.SPACE, ea); line.isWrapped = line.isWrapped && cursorX > 0 }
            1 -> line.fill(0, cursorX + 1, Line.SPACE, ea)
            2 -> line.clear(ea)
        }
        markDirty(cursorY)
    }

    private fun insertLines(n: Int) {
        if (cursorY !in scrollTop..scrollBottom) return
        screen.scrollRegionDown(cursorY, scrollBottom, n, eraseAttr())
        cursorX = 0
        pendingWrap = false
        markAllDirty()
    }

    private fun deleteLines(n: Int) {
        if (cursorY !in scrollTop..scrollBottom) return
        screen.scrollRegionUp(cursorY, scrollBottom, n, eraseAttr(), keepHistory = false)
        cursorX = 0
        pendingWrap = false
        markAllDirty()
    }

    private fun setScrollRegion(top1: Int, bottom1: Int) {
        val top = (top1 - 1).coerceIn(0, rows - 1)
        val bottom = (bottom1 - 1).coerceIn(0, rows - 1)
        if (top >= bottom) return
        scrollTop = top
        scrollBottom = bottom
        cursorPosition(1, 1)
    }

    private fun ansiMode(mode: Int, on: Boolean) {
        when (mode) {
            4 -> insertMode = on
            20 -> linefeedMode = on
        }
    }

    private fun decMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> cursorKeysApp = on
            5 -> { reverseVideo = on; markAllDirty() }
            6 -> { originMode = on; cursorPosition(1, 1) }
            7 -> { autowrap = on; if (!on) pendingWrap = false }
            9 -> mouseMode = if (on) 9 else 0
            12 -> {} // cursor blink — renderer preference
            25 -> cursorVisible = on
            45 -> reverseWraparound = on
            47, 1047 -> switchAltScreen(on, saveCursorWithIt = false)
            1000 -> mouseMode = if (on) 1000 else 0
            1002 -> mouseMode = if (on) 1002 else 0
            1003 -> mouseMode = if (on) 1003 else 0
            1004 -> focusEvents = on
            1005 -> {} // UTF-8 mouse: never advertised
            1006 -> mouseSgr = on
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> switchAltScreen(on, saveCursorWithIt = true)
            2004 -> bracketedPaste = on
        }
    }

    private fun switchAltScreen(toAlt: Boolean, saveCursorWithIt: Boolean) {
        if (toAlt == usingAlt) return
        if (toAlt) {
            if (saveCursorWithIt) saveCursor()
            usingAlt = true
            alt.clearAll(CellAttrs.DEFAULT)
            cursorX = 0
            cursorY = 0
            pendingWrap = false
        } else {
            usingAlt = false
            if (saveCursorWithIt) restoreCursor()
        }
        markAllDirty()
    }

    /** CUB with reverse-wrap active: each step may climb a row (region-confined). */
    private fun cursorBackWrapping(n: Int) {
        if (pendingWrap) pendingWrap = false
        repeat(n.coerceAtMost(cols * rows)) {
            if (cursorX > 0) cursorX-- else {
                cursorX = cols - 1
                cursorY = when {
                    cursorY == scrollTop -> scrollBottom
                    cursorY > 0 -> cursorY - 1
                    else -> rows - 1
                }
            }
        }
    }

    private fun saveCursor() {
        val s = saved
        s.x = cursorX; s.y = cursorY; s.attrs = attrs
        s.g0 = g0; s.g1 = g1; s.glIsG1 = glIsG1
        s.originMode = originMode; s.pendingWrap = pendingWrap
    }

    private fun restoreCursor() {
        val s = saved
        cursorX = s.x.coerceIn(0, cols - 1)
        cursorY = s.y.coerceIn(0, rows - 1)
        attrs = s.attrs
        g0 = s.g0; g1 = s.g1; glIsG1 = s.glIsG1
        originMode = s.originMode
        pendingWrap = false
    }

    private fun decAlignmentTest() {
        scrollTop = 0
        scrollBottom = rows - 1
        for (r in 0 until rows) screen.line(r).fill(0, cols, 'E'.code, CellAttrs.DEFAULT)
        cursorX = 0
        cursorY = 0
        pendingWrap = false
        markAllDirty()
    }

    private fun softReset() {
        cursorVisible = true
        scrollTop = 0
        scrollBottom = rows - 1
        originMode = false
        insertMode = false
        autowrap = true
        attrs = CellAttrs.DEFAULT
        g0 = TermCharsets.ASCII
        g1 = TermCharsets.ASCII
        glIsG1 = false
        pendingWrap = false
        cursorKeysApp = false
        keypadApp = false
        reverseWraparound = false // xterm's DECSTR resets mode 45
        // DECSTR also resets the DECSC save state (DEC STD-070): a DECRC with no
        // save after a reset restores home + defaults, not a stale position.
        saved.let { s ->
            s.x = 0; s.y = 0; s.attrs = CellAttrs.DEFAULT
            s.g0 = TermCharsets.ASCII; s.g1 = TermCharsets.ASCII; s.glIsG1 = false
            s.originMode = false; s.pendingWrap = false
        }
    }

    private fun fullReset() {
        softReset()
        cursorX = 0
        cursorY = 0
        usingAlt = false
        primary.clearAll(CellAttrs.DEFAULT)
        alt.clearAll(CellAttrs.DEFAULT)
        primary.clearScrollback()
        tabStops = defaultTabStops(cols)
        bracketedPaste = false
        mouseMode = 0
        mouseSgr = false
        reverseWraparound = false
        focusEvents = false
        reverseVideo = false
        linefeedMode = false
        title = ""
        palette.reset()
        lastPrinted = -1
        markAllDirty()
    }

    // ------------------------------------------------------------------ SGR

    private fun applySgr(params: CsiParams) {
        if (params.count == 0) {
            attrs = CellAttrs.DEFAULT
            return
        }
        var i = 0
        while (i < params.count) {
            when (val p = params.get(i, 0)) {
                0 -> attrs = CellAttrs.DEFAULT
                1 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.BOLD)
                2 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.FAINT)
                3 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.ITALIC)
                4 -> attrs = if (params.subCount(i) > 0 && params.sub(i, 1, 1) == 0) {
                    CellAttrs.withoutStyle(attrs, CellAttrs.UNDERLINE)
                } else {
                    CellAttrs.withStyle(attrs, CellAttrs.UNDERLINE)
                }
                5, 6 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.BLINK)
                7 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.INVERSE)
                8 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.INVISIBLE)
                9 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.STRIKETHROUGH)
                21 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.UNDERLINE) // xterm: double underline
                22 -> attrs = CellAttrs.withoutStyle(CellAttrs.withoutStyle(attrs, CellAttrs.BOLD), CellAttrs.FAINT)
                23 -> attrs = CellAttrs.withoutStyle(attrs, CellAttrs.ITALIC)
                24 -> attrs = CellAttrs.withoutStyle(attrs, CellAttrs.UNDERLINE)
                25 -> attrs = CellAttrs.withoutStyle(attrs, CellAttrs.BLINK)
                27 -> attrs = CellAttrs.withoutStyle(attrs, CellAttrs.INVERSE)
                28 -> attrs = CellAttrs.withoutStyle(attrs, CellAttrs.INVISIBLE)
                29 -> attrs = CellAttrs.withoutStyle(attrs, CellAttrs.STRIKETHROUGH)
                in 30..37 -> attrs = CellAttrs.withFgPalette(attrs, p - 30)
                38 -> i = extendedColor(params, i, isFg = true)
                39 -> attrs = CellAttrs.withDefaultFg(attrs)
                in 40..47 -> attrs = CellAttrs.withBgPalette(attrs, p - 40)
                48 -> i = extendedColor(params, i, isFg = false)
                49 -> attrs = CellAttrs.withDefaultBg(attrs)
                in 90..97 -> attrs = CellAttrs.withFgPalette(attrs, p - 90 + 8)
                in 100..107 -> attrs = CellAttrs.withBgPalette(attrs, p - 100 + 8)
                else -> {}
            }
            i++
        }
    }

    /** Handles 38/48 in both colon (38:2:r:g:b, 38:5:i) and semicolon (38;2;r;g;b) forms. */
    private fun extendedColor(params: CsiParams, i: Int, isFg: Boolean): Int {
        val subs = params.subCount(i)
        if (subs > 0) {
            when (params.sub(i, 1, -1)) {
                5 -> setPalette(isFg, params.sub(i, 2, 0))
                2 -> {
                    // 38:2:r:g:b or 38:2:colorspace:r:g:b
                    val off = if (subs >= 5) 1 else 0
                    setRgb(
                        isFg,
                        params.sub(i, 2 + off, 0),
                        params.sub(i, 3 + off, 0),
                        params.sub(i, 4 + off, 0),
                    )
                }
            }
            return i
        }
        return when (params.get(i + 1, -1)) {
            5 -> { setPalette(isFg, params.get(i + 2, 0)); i + 2 }
            2 -> { setRgb(isFg, params.get(i + 2, 0), params.get(i + 3, 0), params.get(i + 4, 0)); i + 4 }
            else -> i
        }
    }

    private fun setPalette(isFg: Boolean, index: Int) {
        val idx = index.coerceIn(0, 255)
        attrs = if (isFg) CellAttrs.withFgPalette(attrs, idx) else CellAttrs.withBgPalette(attrs, idx)
    }

    private fun setRgb(isFg: Boolean, r: Int, g: Int, b: Int) {
        val rgb = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        attrs = if (isFg) CellAttrs.withFgRgb(attrs, rgb) else CellAttrs.withBgRgb(attrs, rgb)
    }

    /** Erase operations fill with default fg but keep the current background (BCE). */
    private fun eraseAttr(): Long {
        var ea = CellAttrs.DEFAULT
        ea = when (CellAttrs.bgMode(attrs)) {
            CellAttrs.MODE_PALETTE -> CellAttrs.withBgPalette(ea, CellAttrs.bgColor(attrs))
            CellAttrs.MODE_RGB -> CellAttrs.withBgRgb(ea, CellAttrs.bgColor(attrs))
            else -> ea
        }
        return ea
    }

    // ------------------------------------------------------------------ OSC helpers

    private fun oscPalette(arg: String) {
        // OSC 4;index;spec — possibly repeated pairs
        val parts = arg.split(';')
        var i = 0
        while (i + 1 < parts.size) {
            val idx = parts[i].toIntOrNull()
            val spec = parts[i + 1]
            if (idx != null && idx in 0..255) {
                if (spec == "?") {
                    onResponse("${OSC}4;$idx;${toXColor(palette[idx])}$ST")
                } else {
                    parseColor(spec)?.let { palette[idx] = it }
                }
            }
            i += 2
        }
    }

    private inline fun oscColor(arg: String, code: Int, set: (Int) -> Unit) {
        if (arg == "?") {
            val current = if (code == 10) defaultFg else defaultBg
            onResponse("$OSC$code;${toXColor(current)}$ST")
        } else {
            parseColor(arg)?.let(set)
        }
    }

    private fun toXColor(rgb: Int): String {
        fun ch(v: Int) = "%04x".format(v * 257)
        return "rgb:${ch((rgb shr 16) and 0xFF)}/${ch((rgb shr 8) and 0xFF)}/${ch(rgb and 0xFF)}"
    }

    private fun parseColor(spec: String): Int? {
        val s = spec.trim()
        if (s.startsWith("#")) {
            val hex = s.substring(1)
            return when (hex.length) {
                6 -> hex.toIntOrNull(16)
                3 -> hex.toIntOrNull(16)?.let { v ->
                    val r = (v shr 8) and 0xF
                    val g = (v shr 4) and 0xF
                    val b = v and 0xF
                    (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
                }
                else -> null
            }
        }
        if (s.startsWith("rgb:")) {
            val parts = s.substring(4).split('/')
            if (parts.size != 3) return null
            fun chan(p: String): Int? = p.toIntOrNull(16)?.let { v ->
                when (p.length) {
                    1 -> v * 17
                    2 -> v
                    3 -> v shr 4
                    4 -> v shr 8
                    else -> null
                }
            }
            val r = chan(parts[0]) ?: return null
            val g = chan(parts[1]) ?: return null
            val b = chan(parts[2]) ?: return null
            return (r shl 16) or (g shl 8) or b
        }
        return null
    }

    // ------------------------------------------------------------------ dirty/util

    private fun markDirty(row: Int) {
        dirtyRows.set(row)
    }

    private fun markAllDirty() {
        allDirty = true
    }

    private fun touch() {
        generation++
    }

    companion object {
        private const val CSI = "\u001B["
        private const val OSC = "\u001B]"
        private const val ST = "\u001B\\"

        /** Sentinel returned by [drainDirty] meaning "redraw everything". */
        val ALL_DIRTY = BitSet(0)

        private fun defaultTabStops(cols: Int): BitSet {
            val b = BitSet(cols)
            var i = 8
            while (i < cols) {
                b.set(i)
                i += 8
            }
            return b
        }
    }
}
