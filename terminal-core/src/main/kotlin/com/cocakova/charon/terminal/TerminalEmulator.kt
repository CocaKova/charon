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
    /** Reported by XTVERSION so remote tools can recognise us and light up. */
    private val versionName: String = "1.0",
    /** Monotonic nanoseconds — the synchronized-output timeout reads it. */
    private val clock: () -> Long = System::nanoTime,
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

    // The pen's extended half ([CellExt]): SGR's underline style/color, and the open
    // OSC 8 link. Kept apart because SGR 0 wipes the first and never the second;
    // [penExt] is their union, recomputed on change so print stays a plain store.
    private var penUl = 0L
    private var linkId = 0
    private var penExt = 0L

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

    /**
     * Synchronized output (mode 2026): the program is mid-frame and asked us not to
     * paint until it says done. Held at most [SYNC_TIMEOUT_NANOS], so a program
     * that dies mid-frame can never freeze the glass.
     */
    var synchronizedOutput = false; private set
    @Volatile private var syncSince = 0L

    /** True while the renderer should keep showing the last finished frame. */
    fun syncHolding(now: Long = clock()): Boolean =
        synchronizedOutput && now - syncSince < SYNC_TIMEOUT_NANOS

    // Kitty keyboard protocol: one enhancement stack per screen (the spec's rule),
    // so flags a TUI pushes on the alternate screen never follow you back out.
    private val kittyKeysMain = KittyKeyboardStack()
    private val kittyKeysAlt = KittyKeyboardStack()
    private val kittyKeys: KittyKeyboardStack get() = if (usingAlt) kittyKeysAlt else kittyKeysMain

    /** Kitty keyboard enhancement flags in force on the active screen (0 = legacy). */
    val kittyKeyboardFlags: Int get() = kittyKeys.flags

    private var scrollTop = 0
    private var scrollBottom = initialRows - 1

    private var tabStops = defaultTabStops(initialCols)

    private var g0 = TermCharsets.ASCII
    private var g1 = TermCharsets.ASCII
    private var glIsG1 = false

    private class SavedCursor(
        var x: Int = 0, var y: Int = 0, var attrs: Long = CellAttrs.DEFAULT, var ul: Long = 0L,
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

    /** Pixel cell size, set by the renderer; used for CSI 14t/16t reports and for
     *  working out how many cells an image claims. */
    var cellWidthPx = 8
    var cellHeightPx = 16

    /**
     * Apparitions — inline images. The engine reads the Kitty and iTerm2 wire
     * protocols and hands back a rectangle of grid; anchoring it to a line and
     * moving the cursor around it is this class's job.
     */
    val apparitions = ApparitionEngine()

    var title = ""
        private set

    /**
     * Marked passages: every OSC 8 URI this terminal holds, interned once. Cells
     * carry the id; the app resolves a tapped cell's id back to its URI here. One
     * table for both screens, so a link in scrollback keeps its target.
     */
    val hyperlinks = HyperlinkTable()
    private var linkSweepCooldown = 0

    /**
     * OSC 133 semantic-prompt relay: (kind, extra) where kind ∈ A/B/C/D and extra
     * is D's exit code when the shell sent one. Mutable so hosts can wire it
     * without touching the constructor; invoked from the writer's thread.
     */
    var onShellMark: ((Char, Int?) -> Unit)? = null

    /** The command whose prompt is up or whose output is running (A … D), if rigged. */
    var currentCommand: CommandMark? = null
        private set

    /** The command the last D finished. */
    var lastFinished: CommandMark? = null
        private set
    private var nextMarkId = 1L

    /**
     * A program asked for a notification: OSC 9 ; text (iTerm2) or
     * OSC 777 ; notify ; title ; body (rxvt/foot). Title is null for OSC 9.
     */
    var onNotify: ((title: String?, body: String) -> Unit)? = null

    /** OSC 9 ; 4 ; state ; value — ConEmu/Windows Terminal progress (0 clear … 4 paused). */
    var onProgress: ((state: Int, value: Int?) -> Unit)? = null

    /**
     * Where the shell last said it stands (OSC 7), or null when it never has — or
     * when tmux reported that the active pane has no known path (an empty OSC 7).
     */
    var cwd: ShellCwd? = null
        private set

    /** OSC 7 relay: fired with each believed report (null = cleared), from the
     *  writer's thread. Malformed reports are dropped before they get here. */
    var onCwd: ((ShellCwd?) -> Unit)? = null

    /**
     * OSC 52 relay: the remote asked to put [text] on the clipboard (a tmux or nvim
     * yank). Never applied here — the host decides, behind the traveller's consent.
     * Requests to *read* the clipboard (`OSC 52 ; c ; ?`) are never answered.
     */
    var onClipboard: ((String) -> Unit)? = null

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
                screen.line(cursorY).set(cursorX, Line.SPACE, attrs, penExt)
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
            line.set(cursorX, cp, attrs or CellAttrs.WIDE, penExt)
            if (cursorX + 1 < cols) {
                clobberWide(line, cursorX + 1)
                line.set(cursorX + 1, Line.SPACE, attrs or CellAttrs.WIDE_CONTINUATION, penExt)
            }
        } else {
            line.set(cursorX, cp, attrs, penExt)
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
                'u' -> onResponse("$CSI?${kittyKeys.flags}u") // kitty keyboard: query flags
                'n' -> when (params.get(0, 0)) {
                    6 -> onResponse("$CSI?${reportRow()};${cursorX + 1}R")
                    15 -> onResponse("$CSI?13n")      // no printer
                    25 -> onResponse("$CSI?20n")      // UDKs unlocked
                    26 -> onResponse("$CSI?27;1;0;0n") // keyboard: North American
                }
                else -> {}
            }
            ">" -> when (final) {
                'c' -> onResponse("$CSI>41;377;0c") // DA2: xterm-ish
                // XTVERSION. Without a name, tools that gate their modern paths on
                // knowing the terminal — `kitten icat` among them — never even try.
                'q' -> onResponse("${DCS}>|Charon($versionName)$ST")
                'u' -> kittyKeys.push(params.get(0, 0))      // kitty keyboard: push
                else -> {}
            }
            "<" -> if (final == 'u') kittyKeys.pop(params.getOr1(0))   // kitty keyboard: pop
            "=" -> if (final == 'u') kittyKeys.set(params.get(0, 0), params.get(1, 1))
            "!" -> if (final == 'p') softReset()                  // DECSTR
            " " -> if (final == 'q') cursorStyle = params.get(0, 1).let { if (it in 0..6) it else 1 } // DECSCUSR
            // DECRQM: how a program asks "do you know this mode, and is it on?" —
            // neovim and friends ask about 2026 before trusting synchronized output.
            "?$" -> if (final == 'p') params.get(0, 0).let { m -> onResponse("$CSI?$m;${decModeState(m)}\$y") }
            "$" -> if (final == 'p') params.get(0, 0).let { m -> onResponse("$CSI$m;${ansiModeState(m)}\$y") }
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
                // Cell size in pixels: how an image sizer works out cells-per-pixel
                // when the PTY carries no pixel dimensions.
                16 -> onResponse("${CSI}6;$cellHeightPx;${cellWidthPx}t")
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
            8 -> oscHyperlink(arg)
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
                    soundMark(kind, extra)
                    onShellMark?.invoke(kind, extra)
                }
            }
            // OSC 9: ConEmu's numbered forms (9;4 is progress) or iTerm2's plain
            // notification text. A number-then-semicolon that isn't 4 is ConEmu's
            // business (sleep, message box, tab title…) and is left alone.
            9 -> {
                val sub = arg.substringBefore(';', "")
                when {
                    sub == "4" -> oscProgress(arg.substringAfter(';'))
                    sub.isNotEmpty() && sub.all { it.isDigit() } -> {}
                    arg.isNotBlank() -> onNotify?.invoke(null, arg)
                }
            }
            777 -> {
                val parts = arg.split(';', limit = 3)
                if (parts.size >= 2 && parts[0] == "notify") {
                    onNotify?.invoke(parts[1].ifBlank { null }, parts.getOrElse(2) { "" })
                }
            }
            // OSC 7: the shell reports its working directory as a file:// URL. An
            // empty report is tmux saying the active pane has none; a malformed one
            // is dropped and the last good report stands.
            7 -> if (arg.isEmpty()) {
                cwd = null
                onCwd?.invoke(null)
            } else {
                ShellCwd.parse(arg)?.let {
                    cwd = it
                    onCwd?.invoke(it)
                }
            }
            // iTerm2 inline images — what `imgcat` speaks.
            1337 -> applyGraphics(apparitions.iterm2(arg, gridFacts()))
            // OSC 52 ; selection ; base64 — a yank for the clipboard. Reads ("?")
            // are refused: the phone's clipboard is not the far shore's to see.
            52 -> oscClipboard(arg)
            else -> {}
        }
        touch()
    }

    /**
     * OSC 133 soundings, hung on the lines they land on. A opens a command at the
     * prompt line; B notes where typing begins; C marks the output's first line; D
     * closes it with the exit code. A D with no C (an empty Enter) finishes nothing
     * worth a whisper; an A with a command still open (an unrigged subshell, a ^C
     * the shell never closed) simply starts afresh.
     */
    private fun soundMark(kind: Char, extra: Int?) {
        when (kind) {
            // On the alternate screen (a shell inside tmux) lines are redrawn in
            // place, never cleared, so a mark there would whisper beside the wrong
            // text: the command is still followed, but nothing hangs on a line.
            'A' -> {
                val m = CommandMark(nextMarkId++)
                if (!usingAlt) screen.line(cursorY).promptMark = m
                currentCommand = m
            }
            'B' -> currentCommand?.inputCol = cursorX
            'C' -> {
                val m = currentCommand ?: CommandMark(nextMarkId++).also { currentCommand = it }
                m.ran = true
                if (!usingAlt) screen.line(cursorY).outputMark = m
            }
            'D' -> {
                currentCommand?.let { m ->
                    m.finished = true
                    m.exitCode = extra
                    lastFinished = m
                }
                currentCommand = null
            }
        }
    }

    /** `4;state;value`: the bar a long job paints in the taskbar, here steering the barge. */
    private fun oscProgress(arg: String) {
        val state = arg.substringBefore(';').toIntOrNull()?.takeIf { it in 0..4 } ?: return
        val value = arg.substringAfter(';', "").takeWhile { it.isDigit() }.toIntOrNull()
        onProgress?.invoke(state, value)
    }

    /** The Kitty graphics protocol arrives here: `ESC _ G … ESC \`. */
    override fun apcDispatch(payload: String) {
        applyGraphics(apparitions.kitty(payload, gridFacts()))
        touch()
    }

    // ------------------------------------------------------------------ DCS queries

    /** Which DCS request is being gathered, if any we answer. */
    private var dcsKind = DCS_NONE
    private val dcsData = StringBuilder()

    override fun dcsHook(params: CsiParams, collected: String, final: Char) {
        dcsData.setLength(0)
        dcsKind = when {
            collected == "$" && final == 'q' -> DCS_DECRQSS
            collected == "+" && final == 'q' -> DCS_XTGETTCAP
            else -> DCS_NONE
        }
    }

    override fun dcsPut(codePoint: Int) {
        if (dcsKind != DCS_NONE && dcsData.length < MAX_DCS_QUERY) dcsData.appendCodePoint(codePoint)
    }

    override fun dcsUnhook() {
        val kind = dcsKind
        dcsKind = DCS_NONE
        when (kind) {
            DCS_DECRQSS -> onResponse(TermQueries.decrqss(dcsData.toString(), this))
            DCS_XTGETTCAP -> TermQueries.xtgettcap(dcsData.toString()).forEach(onResponse)
        }
        dcsData.setLength(0)
    }

    override fun dcsCancel() {
        dcsKind = DCS_NONE
        dcsData.setLength(0)
    }

    /** The pen as SGR parameters (DECRQSS `m`): how a program reads back what it set. */
    internal fun penSgr(): String = TermQueries.sgrOf(attrs, penUl)

    internal val scrollRegion: Pair<Int, Int> get() = scrollTop to scrollBottom

    // ------------------------------------------------------------- apparitions

    private fun gridFacts() = ApparitionEngine.Grid(
        cellWidthPx = cellWidthPx,
        cellHeightPx = cellHeightPx,
        cursorCol = cursorX,
        cols = cols,
        rows = rows,
    )

    private fun applyGraphics(result: ApparitionEngine.Result?) {
        if (result == null) return
        result.response?.let(onResponse)
        result.delete?.let(::applyDelete)
        result.place?.let { place(it, moveCursor = result.moveCursor) }
    }

    /**
     * Anchor a placement to the line the cursor is on, then step the cursor past it
     * so the rows the image occupies are real rows — they scroll, they enter
     * scrollback, and whatever the remote prints next lands below the picture
     * instead of underneath it.
     */
    private fun place(placement: ApparitionPlacement, moveCursor: Boolean) {
        pendingWrap = false
        screen.line(cursorY).place(placement)
        markAllDirty()
        if (!moveCursor) return
        repeat(placement.rows - 1) { linefeed() }
        cursorX = (placement.startCol + placement.cols).coerceIn(0, cols - 1)
    }

    private fun applyDelete(delete: ApparitionEngine.Delete) {
        when (delete) {
            is ApparitionEngine.Delete.All -> {
                forEachScreenLine { it.unplace { true } }
                if (delete.freeData) apparitions.store.clear()
            }
            is ApparitionEngine.Delete.Image -> {
                forEachScreenLine { line ->
                    line.unplace {
                        it.imageId == delete.imageId &&
                            (delete.placementId == 0L || it.placementId == delete.placementId)
                    }
                }
                if (delete.freeData) apparitions.store.remove(delete.imageId)
            }
            ApparitionEngine.Delete.AtCursor -> {
                // A placement covers the cursor when its span reaches down to it.
                for (r in 0..cursorY) {
                    val depth = cursorY - r
                    screen.line(r).unplace {
                        depth < it.rows && cursorX in it.startCol until (it.startCol + it.cols)
                    }
                }
            }
        }
        markAllDirty()
    }

    private inline fun forEachScreenLine(action: (Line) -> Unit) {
        primary.forEachLine(action)
        alt.forEachLine(action)
    }

    // ------------------------------------------------------------------ operations

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        // The primary screen keeps its cursor's line on the glass: a shrink sends
        // lines over the top into scrollback instead of cutting the prompt off the
        // bottom (the keyboard rising on a bare shell). While a TUI holds the
        // alternate screen, the primary's cursor is the one 1049 saved for it.
        val primaryCursor = if (usingAlt) savedPrimary.y else cursorY
        val shift = primary.resize(newCols, newRows, primaryCursor)
        alt.resize(newCols, newRows)
        val oldCols = cols
        cols = newCols
        rows = newRows
        scrollTop = 0
        scrollBottom = rows - 1
        if (newCols != oldCols) tabStops = defaultTabStops(newCols)
        if (!usingAlt) cursorY -= shift
        savedPrimary.y = (savedPrimary.y - shift).coerceAtLeast(0)
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
            2026 -> {
                if (on && !synchronizedOutput) syncSince = clock()
                synchronizedOutput = on
            }
        }
    }

    /** DECRPM's answer for a DEC private mode: 1 set, 2 reset, 0 unknown, 4 never. */
    private fun decModeState(mode: Int): Int {
        fun b(v: Boolean) = if (v) 1 else 2
        return when (mode) {
            1 -> b(cursorKeysApp)
            5 -> b(reverseVideo)
            6 -> b(originMode)
            7 -> b(autowrap)
            9 -> b(mouseMode == 9)
            12 -> b(cursorStyle == 0 || cursorStyle % 2 == 1)
            25 -> b(cursorVisible)
            45 -> b(reverseWraparound)
            47, 1047, 1049 -> b(usingAlt)
            1000 -> b(mouseMode == 1000)
            1002 -> b(mouseMode == 1002)
            1003 -> b(mouseMode == 1003)
            1004 -> b(focusEvents)
            1005 -> 4
            1006 -> b(mouseSgr)
            2004 -> b(bracketedPaste)
            2026 -> b(synchronizedOutput)
            else -> 0
        }
    }

    private fun ansiModeState(mode: Int): Int = when (mode) {
        4 -> if (insertMode) 1 else 2
        20 -> if (linefeedMode) 1 else 2
        else -> 0
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
        s.x = cursorX; s.y = cursorY; s.attrs = attrs; s.ul = penUl
        s.g0 = g0; s.g1 = g1; s.glIsG1 = glIsG1
        s.originMode = originMode; s.pendingWrap = pendingWrap
    }

    private fun restoreCursor() {
        val s = saved
        cursorX = s.x.coerceIn(0, cols - 1)
        cursorY = s.y.coerceIn(0, rows - 1)
        attrs = s.attrs
        penUl = s.ul
        refreshPenExt()
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
        penUl = 0L
        linkId = 0 // an unterminated link must not bleed past a reset
        refreshPenExt()
        g0 = TermCharsets.ASCII
        g1 = TermCharsets.ASCII
        glIsG1 = false
        pendingWrap = false
        cursorKeysApp = false
        keypadApp = false
        reverseWraparound = false // xterm's DECSTR resets mode 45
        // kitty's soft reset forgets the keyboard stacks too: a program that died
        // mid-enhancement is exactly what `tput init`/`reset` are run to recover from.
        kittyKeysMain.reset()
        kittyKeysAlt.reset()
        // DECSTR also resets the DECSC save state (DEC STD-070): a DECRC with no
        // save after a reset restores home + defaults, not a stale position.
        saved.let { s ->
            s.x = 0; s.y = 0; s.attrs = CellAttrs.DEFAULT; s.ul = 0L
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
        synchronizedOutput = false
        cursorStyle = 1
        title = ""
        onTitle("")
        palette.reset()
        lastPrinted = -1
        // The lines are gone, so their anchors are; drop the pixels behind them too.
        apparitions.store.clear()
        hyperlinks.clear()
        linkSweepCooldown = 0
        markAllDirty()
    }

    // ------------------------------------------------------------------ SGR

    private fun applySgr(params: CsiParams) {
        if (params.count == 0) {
            attrs = CellAttrs.DEFAULT
            penUl = 0L
            refreshPenExt()
            return
        }
        var i = 0
        while (i < params.count) {
            when (val p = params.get(i, 0)) {
                0 -> { attrs = CellAttrs.DEFAULT; penUl = 0L }
                1 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.BOLD)
                2 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.FAINT)
                3 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.ITALIC)
                // 4 / 4:1 single, 4:2 double, 4:3 curly, 4:4 dotted, 4:5 dashed, 4:0 off.
                // Only the colon form carries a style; `4;3` is underline + italic.
                4 -> underline(if (params.subCount(i) > 0) params.sub(i, 1, 1) else CellExt.UL_SINGLE)
                5, 6 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.BLINK)
                7 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.INVERSE)
                8 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.INVISIBLE)
                9 -> attrs = CellAttrs.withStyle(attrs, CellAttrs.STRIKETHROUGH)
                21 -> underline(CellExt.UL_DOUBLE) // ECMA-48 / xterm: doubly underlined
                22 -> attrs = CellAttrs.withoutStyle(CellAttrs.withoutStyle(attrs, CellAttrs.BOLD), CellAttrs.FAINT)
                23 -> attrs = CellAttrs.withoutStyle(attrs, CellAttrs.ITALIC)
                24 -> underline(CellExt.UL_NONE)
                25 -> attrs = CellAttrs.withoutStyle(attrs, CellAttrs.BLINK)
                27 -> attrs = CellAttrs.withoutStyle(attrs, CellAttrs.INVERSE)
                28 -> attrs = CellAttrs.withoutStyle(attrs, CellAttrs.INVISIBLE)
                29 -> attrs = CellAttrs.withoutStyle(attrs, CellAttrs.STRIKETHROUGH)
                in 30..37 -> attrs = CellAttrs.withFgPalette(attrs, p - 30)
                38 -> i = extendedColor(params, i, TARGET_FG)
                39 -> attrs = CellAttrs.withDefaultFg(attrs)
                in 40..47 -> attrs = CellAttrs.withBgPalette(attrs, p - 40)
                48 -> i = extendedColor(params, i, TARGET_BG)
                49 -> attrs = CellAttrs.withDefaultBg(attrs)
                // Underline color (kitty/VTE/xterm-patched): same forms as 38/48.
                58 -> i = extendedColor(params, i, TARGET_UL)
                59 -> penUl = CellExt.withDefaultUl(penUl)
                in 90..97 -> attrs = CellAttrs.withFgPalette(attrs, p - 90 + 8)
                in 100..107 -> attrs = CellAttrs.withBgPalette(attrs, p - 100 + 8)
                else -> {}
            }
            i++
        }
        refreshPenExt()
    }

    /** Switch the underline to [style] (a `4:n` number; 0 = off). */
    private fun underline(style: Int) {
        if (style == CellExt.UL_NONE) {
            attrs = CellAttrs.withoutStyle(attrs, CellAttrs.UNDERLINE)
            penUl = CellExt.withUnderlineStyle(penUl, CellExt.UL_SINGLE) // back to the free 0
        } else {
            attrs = CellAttrs.withStyle(attrs, CellAttrs.UNDERLINE)
            penUl = CellExt.withUnderlineStyle(penUl, style)
        }
    }

    private fun refreshPenExt() {
        penExt = CellExt.withLink(penUl, linkId)
    }

    /** Handles 38/48/58 in both colon (38:2:r:g:b, 38:5:i) and semicolon (38;2;r;g;b) forms. */
    private fun extendedColor(params: CsiParams, i: Int, target: Int): Int {
        val subs = params.subCount(i)
        if (subs > 0) {
            when (params.sub(i, 1, -1)) {
                5 -> setPalette(target, params.sub(i, 2, 0))
                2 -> {
                    // 38:2:r:g:b or 38:2:colorspace:r:g:b
                    val off = if (subs >= 5) 1 else 0
                    setRgb(
                        target,
                        params.sub(i, 2 + off, 0),
                        params.sub(i, 3 + off, 0),
                        params.sub(i, 4 + off, 0),
                    )
                }
            }
            return i
        }
        return when (params.get(i + 1, -1)) {
            5 -> { setPalette(target, params.get(i + 2, 0)); i + 2 }
            2 -> { setRgb(target, params.get(i + 2, 0), params.get(i + 3, 0), params.get(i + 4, 0)); i + 4 }
            else -> i
        }
    }

    private fun setPalette(target: Int, index: Int) {
        val idx = index.coerceIn(0, 255)
        when (target) {
            TARGET_FG -> attrs = CellAttrs.withFgPalette(attrs, idx)
            TARGET_BG -> attrs = CellAttrs.withBgPalette(attrs, idx)
            else -> penUl = CellExt.withUlPalette(penUl, idx)
        }
    }

    private fun setRgb(target: Int, r: Int, g: Int, b: Int) {
        val rgb = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        when (target) {
            TARGET_FG -> attrs = CellAttrs.withFgRgb(attrs, rgb)
            TARGET_BG -> attrs = CellAttrs.withBgRgb(attrs, rgb)
            else -> penUl = CellExt.withUlRgb(penUl, rgb)
        }
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

    /**
     * OSC 8 ; params ; URI — open a link (cells printed from here carry it); an empty
     * URI closes it. params are `k=v` pairs split by ':'; only `id=` means anything.
     * The URI may itself hold ';', so only the first one splits.
     */
    private fun oscHyperlink(arg: String) {
        val sep = arg.indexOf(';')
        val uri = if (sep >= 0) arg.substring(sep + 1) else ""
        linkId = if (uri.isEmpty()) 0 else {
            val idParam = arg.substring(0, sep).split(':')
                .firstOrNull { it.startsWith("id=") }?.substring(3)
            internLink(uri, idParam)
        }
        refreshPenExt()
    }

    /**
     * Intern, sweeping the table once it fills: ids no cell on either screen (or in
     * scrollback) still wears are dropped. A sweep walks every held line, so a
     * stream minting endless distinct links can't make every OSC 8 pay for one —
     * after a sweep that leaves the table mostly full, the next few hundred links
     * are simply refused (their text still prints, just unlinked).
     */
    private fun internLink(uri: String, idParam: String?): Int {
        val id = hyperlinks.intern(uri, idParam)
        if (id != 0 || !hyperlinks.isFull) return id
        if (linkSweepCooldown > 0) {
            linkSweepCooldown--
            return 0
        }
        val live = BitSet()
        forEachScreenLine { line ->
            val ext = line.ext ?: return@forEachScreenLine
            for (e in ext) {
                val l = CellExt.linkId(e)
                if (l != 0) live.set(l)
            }
        }
        hyperlinks.retainOnly(live)
        if (hyperlinks.size > HyperlinkTable.MAX_LINKS * 3 / 4) linkSweepCooldown = LINK_SWEEP_COOLDOWN
        return hyperlinks.intern(uri, idParam)
    }

    private fun oscClipboard(arg: String) {
        val sep = arg.indexOf(';')
        if (sep < 0) return
        val data = arg.substring(sep + 1)
        if (data == "?") return // a read request: never answered
        if (data.length > MAX_CLIPBOARD_B64) return
        val bytes = WireBase64.decode(data) ?: return
        val text = String(bytes, Charsets.UTF_8)
        if (text.isEmpty()) return
        onClipboard?.invoke(text)
    }

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
        private const val DCS = "\u001BP"
        private const val ST = "\u001B\\"

        private const val TARGET_FG = 0
        private const val TARGET_BG = 1
        private const val TARGET_UL = 2

        /** Links refused after a sweep that couldn't make room, before the next try. */
        private const val LINK_SWEEP_COOLDOWN = 256

        /** A synchronized frame left open longer than this is painted anyway. */
        const val SYNC_TIMEOUT_NANOS = 150_000_000L

        /** Clipboard payload cap: ~1.5 MB of base64, ~1 MB of text. */
        private const val MAX_CLIPBOARD_B64 = 1_500_000

        private const val DCS_NONE = 0
        private const val DCS_DECRQSS = 1
        private const val DCS_XTGETTCAP = 2
        private const val MAX_DCS_QUERY = 4096

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
