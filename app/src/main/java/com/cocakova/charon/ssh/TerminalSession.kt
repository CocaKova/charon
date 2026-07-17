package com.cocakova.charon.ssh

import com.cocakova.charon.cargo.CargoLading
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
    /** The livery's cursor colour; the renderer draws the block in it. */
    val cursorColor: Int = 0x3ECFB2,
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
            lastOutputAt = System.nanoTime()
            // The remote spoke: whatever keystroke was waiting on its echo has been
            // answered, one way or another — the line is not reading in secret.
            if (echoPending != 0L) {
                echoPending = 0L
                echoSeen = true
            }
            // Crossing into or out of the alternate screen (tmux, vim, htop…) means
            // whatever line we thought was being typed — and whatever was selected —
            // belongs to a different world; reset both so stale text can't linger.
            if (term.usingAlt != wasAltScreen) {
                wasAltScreen = term.usingAlt
                resetLine()
                _commandDraft.value = ""
                selection.value = null
                endToll()
                _cargo.value = null
            }
            val grew = term.screen.scrollbackSize - before
            // The toll: release when the prompt line moves on (Enter answered, the
            // attempt failed, the program printed past it), then arm whenever the
            // cursor rests at the end of a secret-asking prompt — release and re-arm
            // can land in one burst ("Sorry, try again." plus a fresh prompt).
            if (!term.usingAlt) {
                if (_toll.value != null && (
                        term.cursorY != tollRow || grew > 0 ||
                            term.screen.line(tollRow).toText() != tollPrompt
                        )
                ) {
                    endToll()
                }
                if (_toll.value == null && looksLikeSecretPrompt()) armToll()
            }
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

    // ---- The toll (hidden input) -----------------------------------------------------
    // When the remote reads a secret — sudo, ssh, su, read -s — nothing typed may touch
    // the autofill draft or the command history. Two nets, both structural: the
    // password-prompt grammar arms the toll before the first keystroke, and the echo
    // net (a keystroke the remote never answers) catches prompts in any language.

    enum class TollPhase { ASKED, PAID }

    private val _toll = MutableStateFlow<TollPhase?>(null)
    /** Non-null while a secret is being read; PAID for the beat after Enter. */
    val toll: StateFlow<TollPhase?> = _toll

    private val _tollPulse = MutableStateFlow(0)
    /** Ticks once per hidden keystroke — animation fuel only, never a length gauge. */
    val tollPulse: StateFlow<Int> = _tollPulse

    private var tollRow = -1
    private var tollPrompt = ""

    /** Nanotime of the last remote byte; idle timers (echo net, cargo) read this. */
    @Volatile var lastOutputAt: Long = System.nanoTime()
        private set

    /** Nanotime of the first printable keystroke still waiting for any remote
     *  answer on this line, or 0. The UI's echo net watches it: unanswered for
     *  long enough means the remote is reading in secret. */
    @Volatile var echoPending: Long = 0L
        private set
    private var echoSeen = false

    private fun looksLikeSecretPrompt(): Boolean {
        val line = term.screen.line(term.cursorY).toText()
        if (term.cursorX < line.length) return false   // cursor must rest at the end
        val t = line.trimEnd()
        if (!t.endsWith(":")) return false
        val lower = t.lowercase()
        return "password" in lower || "passphrase" in lower
    }

    private fun armToll() {
        tollRow = term.cursorY
        tollPrompt = term.screen.line(tollRow).toText()
        _tollPulse.value = 0
        _toll.value = TollPhase.ASKED
    }

    private fun endToll() {
        if (_toll.value == null) return
        _toll.value = null
        tollRow = -1
        tollPrompt = ""
    }

    /**
     * The echo net's verdict, delivered by the UI timer: a keystroke went out and
     * the remote said nothing back. Whatever was reconstructed so far was never
     * echoed — it is a secret, so it is forgotten, and the toll is armed.
     */
    fun markHiddenInput() {
        synchronized(lock) {
            if (_toll.value != null || term.usingAlt) return
            armToll()
            lineBuf.setLength(0)
            lineTrusted = false
            _commandDraft.value = ""
        }
    }

    // ---- The horn (command completion) -----------------------------------------------
    // OSC 133 semantic prompts, when the shell is rigged for them (docs/HORN.md):
    // Enter starts a voyage, C refines its start time to when output actually began,
    // D ends it with the exit code. The SessionManager decides whether the horn
    // sounds (long enough, app away); unrigged shells simply never emit D.

    private class Voyage(val command: String, val submittedAt: Long) {
        @Volatile var startedAt: Long? = null
    }

    @Volatile private var voyage: Voyage? = null

    /** Fired (from the reader thread) when a rigged shell reports a command done. */
    var onCommandDone: ((command: String, exitCode: Int?, durationMs: Long) -> Unit)? = null

    init {
        term.onShellMark = { kind, extra ->
            val now = System.nanoTime()
            when (kind) {
                'C' -> voyage?.startedAt = now
                'D' -> voyage?.let { v ->
                    voyage = null
                    val durationMs = (now - (v.startedAt ?: v.submittedAt)) / 1_000_000
                    onCommandDone?.invoke(v.command, extra, durationMs)
                }
                // A/B (prompt start/end) are accepted but carry no meaning yet —
                // prompt-jump in scrollback will want them later.
                else -> {}
            }
        }
    }

    // ---- The lading (package installs) -----------------------------------------------

    data class Cargo(val manager: String, val since: Long = System.nanoTime())

    private val _cargo = MutableStateFlow<Cargo?>(null)
    /** Armed when a submitted command invokes a package manager; the UI gleans the
     *  screen for its output grammar while this holds. */
    val cargo: StateFlow<Cargo?> = _cargo

    fun endCargo() {
        _cargo.value = null
    }

    /** The bottom [n] rows of the live screen as plain text (the cargo glean). */
    fun tailText(n: Int): List<String> = synchronized(lock) {
        ((term.rows - n).coerceAtLeast(0) until term.rows).map { term.screen.line(it).toText() }
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
        if (_toll.value != null) {
            trackHidden(sent)
            return
        }
        var i = 0
        while (i < sent.length) {
            val c = sent[i]
            when {
                c == '\r' || c == '\n' -> { commitLine(); i++ }
                c == '\u0003' -> { resetLine(); _cargo.value = null; voyage = null; i++ } // ^C sinks lading + voyage
                c == '\u0015' -> { resetLine(); i++ }                      // ^U
                c == '\u0017' -> { deleteWord(); i++ }                     // ^W
                c == '\u007f' || c == '\b' -> {
                    if (lineBuf.isNotEmpty()) lineBuf.deleteCharAt(lineBuf.length - 1)
                    i++
                }
                c == '\u001b' -> { lineTrusted = false; i = skipEscape(sent, i) } // arrows/edits
                c == '\t' -> { lineTrusted = false; i++ }                 // remote completion
                c.code < 0x20 -> i++                                      // other controls: skip
                else -> {
                    if (lineTrusted) lineBuf.append(c)
                    // First unanswered printable on this line arms the echo net.
                    if (!echoSeen && echoPending == 0L) echoPending = System.nanoTime()
                    i++
                }
            }
        }
        _commandDraft.value = if (lineTrusted) lineBuf.toString() else ""
    }

    /** The toll is up: keystrokes feed the coin's pulse and nothing else. */
    private fun trackHidden(sent: String) {
        var i = 0
        while (i < sent.length) {
            val c = sent[i]
            when {
                c == '\r' || c == '\n' -> { _toll.value = TollPhase.PAID; resetLine(); i++ }
                c == '\u0003' -> { endToll(); resetLine(); _cargo.value = null; i++ } // rite abandoned
                c == '\u007f' || c == '\b' -> {
                    if (_tollPulse.value > 0) _tollPulse.value--
                    i++
                }
                c == '\u001b' -> i = skipEscape(sent, i)
                c.code < 0x20 -> i++
                else -> { _tollPulse.value++; i++ }
            }
        }
    }

    private fun commitLine() {
        // Deliberately NOT gated on the alternate screen: tmux runs its shells
        // there, and tmux auto-attach is the default workflow. CommandGate (at the
        // recording end) is what keeps non-command lines out of history.
        if (lineTrusted) {
            val cmd = lineBuf.toString().trim()
            if (cmd.isNotEmpty()) {
                onCommandSubmitted?.invoke(cmd)
                CargoLading.match(cmd)?.let { _cargo.value = Cargo(it) }
                voyage = Voyage(cmd, System.nanoTime())
            }
        }
        resetLine()
    }

    private fun resetLine() {
        lineBuf.setLength(0)
        lineTrusted = true
        echoPending = 0L
        echoSeen = false
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
