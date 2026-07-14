package com.cocakova.charon.ssh

import com.cocakova.charon.terminal.TerminalEmulator
import com.cocakova.charon.terminal.TextSelection
import com.cocakova.charon.terminal.input.KeyEncoder
import com.cocakova.charon.terminal.input.MouseEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * One live terminal: the emulator plus its plumbing to a transport. The SSH layer
 * feeds remote bytes in via [feedRemote] and receives user/emulator output through
 * [onOutput]; the renderer reads [term] under [lock].
 */
class TerminalSession(
    val label: String,
    cols: Int = 80,
    rows: Int = 24,
    basePalette: IntArray? = null,
    initialFg: Int = 0xE6EDF3,
    initialBg: Int = 0x000000,
) {
    val id: String = UUID.randomUUID().toString()

    /** Guards all access to [term]: the reader thread writes, the renderer reads. */
    val lock = Any()

    /** Bytes headed to the remote (SSH channel stdin). */
    var onOutput: ((ByteArray) -> Unit)? = null

    /** PTY window-change hook, wired by the SSH layer. */
    var onResize: ((cols: Int, rows: Int) -> Unit)? = null

    sealed class State {
        data object Connecting : State()
        data object Connected : State()
        /** Down after a transport drop, redialing. [attempt] counts from 1. */
        data class Reconnecting(val attempt: Int) : State()
        /**
         * Down for good (until a manual re-cross). [clean] = the remote closed the
         * channel normally (you typed `exit`, or the server hung up) rather than the
         * transport dying — a clean end is never auto-reconnected.
         */
        data class Disconnected(val reason: String, val clean: Boolean = false) : State()
    }

    val state = MutableStateFlow<State>(State.Connecting)

    /** Live grid dimensions, for chrome that shows cols x rows. */
    val dims = MutableStateFlow(cols to rows)

    val term = TerminalEmulator(
        cols, rows,
        onResponse = { sendText(it) }, // DA/DSR/CPR replies go straight back out
        onBell = {},
        basePalette = basePalette,
        initialFg = initialFg,
        initialBg = initialBg,
    )

    /** Rows scrolled back from the live bottom; 0 = following output. */
    val scrollOffset = MutableStateFlow(0)

    fun feedRemote(bytes: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            val before = term.screen.scrollbackSize
            term.write(bytes, offset, length)
            // Crossing into or out of the alternate screen (tmux, vim, htop…) means
            // whatever line we thought was being typed — and whatever was selected —
            // belongs to a different world; reset both so stale text can't linger.
            if (term.usingAlt != wasAltScreen) {
                wasAltScreen = term.usingAlt
                resetLine()
                _commandDraft.value = ""
                selection.value = null
            }
            val grew = term.screen.scrollbackSize - before
            if (grew > 0) {
                // While the user is scrolled up, grow the offset by however many lines
                // were just evicted into scrollback so the viewport stays put instead
                // of drifting under new output.
                if (scrollOffset.value > 0) {
                    scrollOffset.value =
                        (scrollOffset.value + grew).coerceAtMost(term.screen.scrollbackSize)
                }
                // The selection is pinned to its text, so it slides back with it; if
                // the text it covered has been evicted past scrollback, let it go.
                selection.value?.let { sel ->
                    val anchor = sel.anchor.copy(row = sel.anchor.row - grew)
                    val focus = sel.focus.copy(row = sel.focus.row - grew)
                    val floor = -term.screen.scrollbackSize
                    selection.value =
                        if (anchor.row < floor && focus.row < floor) null
                        else Selection(clampCell(anchor), clampCell(focus))
                }
            }
        }
    }

    /** Scroll the viewport by [deltaRows] (positive = toward older history). The
     *  selection survives — it lives in buffer space, not viewport space. */
    fun scrollBy(deltaRows: Int) {
        val max = synchronized(lock) { term.screen.scrollbackSize }
        val next = (scrollOffset.value + deltaRows).coerceIn(0, max)
        if (next != scrollOffset.value) scrollOffset.value = next
    }

    fun scrollToBottom() {
        if (scrollOffset.value != 0) scrollOffset.value = 0
    }

    fun sendText(text: String) {
        onOutput?.invoke(text.toByteArray(Charsets.UTF_8))
    }

    // ---- Command-line tracking (smart autofill) ------------------------------------
    // We reconstruct the line being typed from the bytes the user sends, so the
    // suggestion strip can offer past commands that continue it. This is fed only by
    // genuine user input ([trackInput] from the UI + [paste]) — never by DA/DSR/mouse
    // replies, which would otherwise poison it with escape sequences.

    private val lineBuf = StringBuilder()
    /** Mirrors term.usingAlt so [feedRemote] can spot the screen switching. */
    private var wasAltScreen = false
    /** False once editing goes non-linear (arrows, tab-complete): we stop trusting our
     *  reconstruction and blank the draft rather than suggest against a wrong prefix. */
    private var lineTrusted = true

    private val _commandDraft = MutableStateFlow("")
    /** The command currently on the line, or "" when empty/unknown. */
    val commandDraft: StateFlow<String> = _commandDraft

    /** Fired with a finished line when the user presses Enter at a prompt. */
    var onCommandSubmitted: ((String) -> Unit)? = null

    /** Feed user-originated bytes through the line reconstructor. */
    fun trackInput(sent: String) {
        var i = 0
        while (i < sent.length) {
            val c = sent[i]
            when {
                c == '\r' || c == '\n' -> { commitLine(); i++ }
                c == '\u0003' || c == '\u0015' -> { resetLine(); i++ }   // ^C, ^U
                c == '\u0017' -> { deleteWord(); i++ }                    // ^W
                c == '\u007f' || c == '\b' -> {
                    if (lineBuf.isNotEmpty()) lineBuf.deleteCharAt(lineBuf.length - 1)
                    i++
                }
                c == '\u001b' -> { lineTrusted = false; i = skipEscape(sent, i) } // arrows/edits
                c == '\t' -> { lineTrusted = false; i++ }                 // remote completion
                c.code < 0x20 -> i++                                      // other controls: skip
                else -> { if (lineTrusted) lineBuf.append(c); i++ }
            }
        }
        _commandDraft.value = if (lineTrusted) lineBuf.toString() else ""
    }

    private fun commitLine() {
        if (lineTrusted) {
            val cmd = lineBuf.toString().trim()
            if (cmd.isNotEmpty()) onCommandSubmitted?.invoke(cmd)
        }
        resetLine()
    }

    private fun resetLine() {
        lineBuf.setLength(0)
        lineTrusted = true
    }

    private fun deleteWord() {
        while (lineBuf.isNotEmpty() && lineBuf.last() == ' ') lineBuf.deleteCharAt(lineBuf.length - 1)
        while (lineBuf.isNotEmpty() && lineBuf.last() != ' ') lineBuf.deleteCharAt(lineBuf.length - 1)
    }

    /** Advance past an escape sequence starting at [start] (points at ESC). */
    private fun skipEscape(s: String, start: Int): Int {
        var i = start + 1
        if (i < s.length && (s[i] == '[' || s[i] == 'O')) {   // CSI / SS3: run to a final byte
            i++
            while (i < s.length && s[i].code !in 0x40..0x7e) i++
            if (i < s.length) i++
        } else if (i < s.length) {
            i++   // ESC + single char (Meta-key)
        }
        return i
    }

    // ---- Selection (buffer space: negative rows reach into scrollback) --------------

    data class Selection(val anchor: TextSelection.Cell, val focus: TextSelection.Cell)

    /** Live selection for the renderer to tint and the copy affordance to read.
     *  Cells are in selection space (row 0 = top of the live grid, negative rows =
     *  scrollback), so the selection stays glued to its text while the view scrolls. */
    val selection = MutableStateFlow<Selection?>(null)

    /** True while the remote app is tracking the mouse (any DECSET 9/1000/1002/1003). */
    val mouseActive: Boolean get() = synchronized(lock) { term.mouseMode != 0 }

    private fun clampCell(cell: TextSelection.Cell): TextSelection.Cell =
        TextSelection.Cell(
            cell.row.coerceIn(-term.screen.scrollbackSize, term.rows - 1),
            cell.col.coerceIn(0, (term.cols - 1).coerceAtLeast(0)),
        )

    fun selectWordAt(cell: TextSelection.Cell) {
        selection.value = synchronized(lock) {
            val c = clampCell(cell)
            val line = term.screen.relativeLine(c.row)
            val range = TextSelection.wordAt(line, c.col)
            Selection(TextSelection.Cell(c.row, range.first), TextSelection.Cell(c.row, range.last))
        }
    }

    /** Select everything there is: the whole scrollback plus the live screen. */
    fun selectAll() {
        selection.value = synchronized(lock) {
            Selection(
                TextSelection.Cell(-term.screen.scrollbackSize, 0),
                TextSelection.Cell(term.rows - 1, (term.cols - 1).coerceAtLeast(0)),
            )
        }
    }

    fun startSelection(cell: TextSelection.Cell) {
        val c = synchronized(lock) { clampCell(cell) }
        selection.value = Selection(c, c)
    }

    fun extendSelection(focus: TextSelection.Cell) {
        val c = synchronized(lock) { clampCell(focus) }
        selection.value = selection.value?.copy(focus = c)
    }

    fun clearSelection() {
        selection.value = null
    }

    /** Copy the selection as plain text (wrapped lines joined), or null if none. */
    fun copySelection(): String? {
        val sel = selection.value ?: return null
        return synchronized(lock) {
            TextSelection.extract(term.screen, sel.anchor, sel.focus)
        }
    }

    // ---- Paste & mouse reporting ---------------------------------------------------

    /** Paste text to the remote, bracketed-guarded when the app asked for it. */
    fun paste(text: String) {
        if (text.isEmpty()) return
        trackInput(text) // pasted text counts toward the current command line
        val wrapped = synchronized(lock) { KeyEncoder.paste(text, term.bracketedPaste) }
        sendText(wrapped)
    }

    private fun emitMouse(
        event: MouseEncoder.Event,
        button: MouseEncoder.Button,
        cell: TextSelection.Cell,
        held: MouseEncoder.Button? = null,
    ) {
        val bytes = synchronized(lock) {
            MouseEncoder.encode(
                term.mouseMode, term.mouseSgr, event, button,
                cell.col, cell.row, heldButton = held,
            )
        } ?: return
        sendText(bytes)
    }

    fun mouseClick(cell: TextSelection.Cell) {
        emitMouse(MouseEncoder.Event.PRESS, MouseEncoder.Button.LEFT, cell)
        emitMouse(MouseEncoder.Event.RELEASE, MouseEncoder.Button.LEFT, cell)
    }

    fun mouseDown(cell: TextSelection.Cell) =
        emitMouse(MouseEncoder.Event.PRESS, MouseEncoder.Button.LEFT, cell)

    fun mouseDrag(cell: TextSelection.Cell) =
        emitMouse(MouseEncoder.Event.MOVE, MouseEncoder.Button.LEFT, cell, held = MouseEncoder.Button.LEFT)

    fun mouseUp(cell: TextSelection.Cell) =
        emitMouse(MouseEncoder.Event.RELEASE, MouseEncoder.Button.LEFT, cell)

    fun mouseWheel(up: Boolean, cell: TextSelection.Cell) =
        emitMouse(
            MouseEncoder.Event.PRESS,
            if (up) MouseEncoder.Button.WHEEL_UP else MouseEncoder.Button.WHEEL_DOWN,
            cell,
        )

    /** Renderer-driven resize: grid first, then the PTY. */
    fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        val changed = synchronized(lock) {
            val c = term.cols != cols || term.rows != rows
            term.cellWidthPx = cellWidthPx
            term.cellHeightPx = cellHeightPx
            if (c) term.resize(cols, rows)
            c
        }
        if (changed) {
            clearSelection() // the old cells no longer mean anything at the new geometry
            scrollToBottom()
            dims.value = cols to rows
            onResize?.invoke(cols, rows)
        }
    }
}
