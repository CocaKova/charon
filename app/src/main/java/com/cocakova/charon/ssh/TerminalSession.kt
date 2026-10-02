package com.cocakova.charon.ssh

import com.cocakova.charon.BuildConfig
import com.cocakova.charon.cargo.CargoLading
import com.cocakova.charon.cargo.CargoWatch
import com.cocakova.charon.terminal.ShellCwd
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
    /** Monotonic nanoseconds; tests hand in their own so timing is theirs to drive. */
    private val clock: () -> Long = System::nanoTime,
) {
    val id: String = UUID.randomUUID().toString()

    /** Guards all access to [term]: the reader thread writes, the renderer reads. */
    val lock = Any()

    /** Bytes headed to the remote (SSH channel stdin). */
    var onOutput: ((ByteArray) -> Unit)? = null

    /** PTY window-change hook, wired by the SSH layer. The pixel size rides along:
     *  image tools size their output from the PTY before asking the terminal. */
    var onResize: ((cols: Int, rows: Int, widthPx: Int, heightPx: Int) -> Unit)? = null

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

    /** The remote's window title (OSC 0/2) — tmux with `set-titles on` keeps this at
     *  the current window's name, so the tab reads "vim" instead of user@host. Blank
     *  until the remote speaks one; the switcher falls back to [label]. */
    val title = MutableStateFlow("")

    val term = TerminalEmulator(
        cols, rows,
        onResponse = { sendText(it) }, // DA/DSR/CPR replies go straight back out
        onTitle = { title.value = it },
        onBell = { ring() },
        basePalette = basePalette,
        initialFg = initialFg,
        initialBg = initialBg,
        // XTVERSION answers with the real build: a tool that recognises Charon can
        // light up its graphics path instead of falling back to blocks of colour.
        versionName = BuildConfig.VERSION_NAME,
    )

    /** Rows scrolled back from the live bottom; 0 = following output. */
    val scrollOffset = MutableStateFlow(0)

    /** Bumped once per remote burst — the renderer's idle loop sleeps on this
     *  instead of riding the frame clock while nothing is arriving. */
    val outputTick = MutableStateFlow(0L)

    /** DECSCUSR as last set (1 = blinking block … 6 = steady bar); the renderer morphs to it. */
    val cursorStyle = MutableStateFlow(1)

    private val _bell = MutableStateFlow(0L)
    /** Ticks once per bell that got through the rate limit — a ripple and a tick each. */
    val bell: StateFlow<Long> = _bell
    private var bellAt = Long.MIN_VALUE

    /** A BEL flood (`yes $'\a'`, a broken prompt) must not become a buzzer. */
    private fun ring() {
        val now = clock()
        if (bellAt != Long.MIN_VALUE && now - bellAt < BELL_GAP_NS) return
        bellAt = now
        _bell.value += 1
    }

    /**
     * A yank the far shore asked to put on the clipboard (OSC 52) — waiting on the
     * traveller's consent, never applied by the session itself. Latest wins.
     */
    val clipboardOffer = MutableStateFlow<String?>(null)

    /** Focus as last reported to a program that asked (DECSET 1004), or null. */
    private var focusReported: Boolean? = null

    /**
     * This terminal gained or lost the traveller's eye (the tab, the app). Reported
     * as `CSI I` / `CSI O` only to a program that asked for focus events — vim
     * re-reads changed files on it, tmux passes it to the pane.
     */
    fun focusChanged(focused: Boolean) {
        val asked = synchronized(lock) { term.focusEvents }
        if (!asked) {
            focusReported = null
            return
        }
        if (focusReported == focused) return
        focusReported = focused
        sendText(if (focused) "\u001b[I" else "\u001b[O")
    }

    private val _keyLent = MutableStateFlow(0L)
    /** Ticks each time the lent key signed for the far shore (agent forwarding). */
    val keyLent: StateFlow<Long> = _keyLent

    /** Called from an agent channel's thread: the key just signed onward. */
    fun noteKeyLent() {
        _keyLent.value += 1
    }

    /** The mouse mode the remote asked for (0 off, else 9/1000/1002/1003). */
    val mouseMode: Int get() = synchronized(lock) { term.mouseMode }

    fun feedRemote(bytes: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            val before = term.screen.scrollbackSize
            cwdReported = false
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
                // The cargo, and whether this world's shell is rigged, stay behind
                // in the world we just left.
                cargoWatch.abandon()
                rigged = false
                publishCargo(clock())
                // The shell that reported the cwd is not the one in front of us now —
                // unless the new world spoke its own in this very burst (tmux on
                // attach, or the outer shell's prompt as tmux lets go).
                if (!cwdReported) _cwd.value = null
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
        // Outside the lock: waking the renderer must never hold up the reader.
        cursorStyle.value = term.cursorStyle
        outputTick.value += 1
    }

    /** Scroll the viewport by [deltaRows] (positive = toward older history). The
     *  selection survives — it lives in buffer space, not viewport space. */
    fun scrollBy(deltaRows: Int) {
        val max = synchronized(lock) { term.screen.scrollbackSize }
        val next = (scrollOffset.value + deltaRows).coerceIn(0, max)
        if (next != scrollOffset.value) scrollOffset.value = next
    }

    /** Rows of history above the glass right now. */
    fun historySize(): Int = synchronized(lock) { term.screen.scrollbackSize }

    fun scrollToBottom() {
        if (scrollOffset.value != 0) scrollOffset.value = 0
    }

    /** Put a selection-space row (negative = scrollback) at the top of the glass. */
    fun jumpToRow(row: Int) {
        val sb = synchronized(lock) { term.screen.scrollbackSize }
        val offset = (sb - (sb + row)).coerceIn(0, sb)
        if (scrollOffset.value != offset) scrollOffset.value = offset
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

    /** The [CommandMark] id of the command last reported done; the horn lands on it. */
    @Volatile var lastFinishedMarkId: Long = -1L
        private set

    /** When the running command's output began (C), on [clock]; null between commands. */
    private var outputStartedAt: Long? = null

    /**
     * A program on the far side asked to be heard (OSC 9 / OSC 777): title (null
     * for OSC 9) and body, as the program wrote them. Fired from the reader thread.
     */
    var onCall: ((title: String?, body: String) -> Unit)? = null

    /** Something a tab in the background should show: a command done, a call. */
    enum class SignalKind { DOCKED, AGROUND, CALL }
    class Signal(val kind: SignalKind, val seq: Long)

    private val _signal = MutableStateFlow<Signal?>(null)
    private var signalSeq = 0L
    /** The last thing worth a glance from another tab; the tab dot flashes on a new one. */
    val signal: StateFlow<Signal?> = _signal

    private fun raise(kind: SignalKind) {
        _signal.value = Signal(kind, ++signalSeq)
    }

    private val _sounded = MutableStateFlow(false)
    /** The shell has spoken OSC 133 at least once: prompt hops have somewhere to land. */
    val sounded: StateFlow<Boolean> = _sounded

    /** Selection-space rows (negative = scrollback) of every prompt still held, oldest first. */
    fun promptRows(): List<Int> = synchronized(lock) {
        val screen = term.screen
        val out = ArrayList<Int>()
        for (r in -screen.scrollbackSize until term.rows) {
            if (screen.relativeLine(r).promptMark != null) out += r
        }
        out
    }

    /**
     * Hop to the prompt above (older = true) or below the top of the glass, and put
     * it at the top. False when there's no prompt that way (an unrigged shell has none).
     */
    fun jumpToPrompt(older: Boolean): Boolean {
        val top = -scrollOffset.value
        val rows = promptRows()
        val target = if (older) rows.lastOrNull { it < top } else rows.firstOrNull { it > top }
        if (target == null) {
            if (!older) scrollToBottom()
            return false
        }
        jumpToRow(target)
        return true
    }

    /**
     * Land on a command by its mark: its prompt line at the top of the glass (the
     * command as typed, then its output), or the output's first line when the prompt
     * already rolled away. False when the mark is gone from history altogether.
     */
    fun landOnMark(markId: Long): Boolean {
        val row = synchronized(lock) {
            val screen = term.screen
            var found: Int? = null
            for (r in -screen.scrollbackSize until term.rows) {
                val line = screen.relativeLine(r)
                if (line.promptMark?.id == markId) { found = r; break }
                if (line.outputMark?.id == markId) { found = (r - 1).coerceAtLeast(-screen.scrollbackSize); break }
            }
            found
        } ?: return false
        selection.value = null
        jumpToRow(row)
        return true
    }

    init {
        term.onClipboard = { clipboardOffer.value = it }
        term.onNotify = { title, body ->
            raise(SignalKind.CALL)
            onCall?.invoke(title?.take(CALL_MAX), body.take(CALL_MAX))
        }
        // OSC 9;4: a program's own progress bar steers the barge — percent, error,
        // indeterminate — and clearing it brings the barge home.
        term.onProgress = { state, value ->
            val now = clock()
            cargoWatch.progress(state, value, now)
            publishCargo(now)
        }
        term.onCwd = {
            cwdReported = true
            _cwd.value = it
        }
        term.onShellMark = { kind, extra ->
            val now = clock()
            if (!_sounded.value) _sounded.value = true
            when (kind) {
                'A' -> outputStartedAt = null
                'C' -> {
                    voyage?.startedAt = now
                    outputStartedAt = now
                }
                'D' -> {
                    // The whisper's length: output-start to done, on this session's clock.
                    val finished = term.lastFinished
                    // A line typed and sent is a command that ran, even from a shell
                    // rigged without the C mark (the whisper then times from Enter).
                    if (finished != null && voyage != null) finished.ran = true
                    val began = outputStartedAt ?: voyage?.let { it.startedAt ?: it.submittedAt }
                    if (finished != null && began != null) finished.durationMs = (now - began) / 1_000_000
                    outputStartedAt = null
                    lastFinishedMarkId = finished?.id ?: -1L
                    if (finished != null && finished.ran && finished.durationMs >= SIGNAL_MIN_MS) {
                        raise(if (finished.failed) SignalKind.AGROUND else SignalKind.DOCKED)
                    }
                    // The shell is back at a prompt: whatever was hauling cargo has
                    // finished, and its exit code says whether it docked or ran aground.
                    rigged = true
                    cargoWatch.commandDone(extra, now)
                    publishCargo(now)
                    voyage?.let { v ->
                        voyage = null
                        val durationMs = (now - (v.startedAt ?: v.submittedAt)) / 1_000_000
                        onCommandDone?.invoke(v.command, extra, durationMs)
                    }
                }
                // A/B (prompt start/end) are accepted but carry no meaning yet —
                // prompt-jump in scrollback will want them later.
                else -> {}
            }
        }
    }

    // ---- Soundings (the shell's working directory) ----------------------------------
    // A rigged prompt reports its cwd via OSC 7 (docs/HORN.md); relative-path and
    // branch completion read it. It is trusted only while it can still be true: Enter
    // at a primary-screen prompt forgets it, because the next rigged prompt reports
    // afresh and anything else — an onward ssh, a REPL, an unrigged shell — never
    // will. On the alternate screen (tmux) Enter keeps it: tmux forwards the active
    // pane's path only when it *changes*, and says so with an empty report when the
    // pane has none.

    private val _cwd = MutableStateFlow<ShellCwd?>(null)
    /** The shell's working directory as last reported, or null when unknown. */
    val cwd: StateFlow<ShellCwd?> = _cwd

    /** An OSC 7 landed during the current [feedRemote] burst. */
    private var cwdReported = false

    /** The transport dropped: whatever shell reported the cwd went down with it. */
    fun forgetCwd() {
        _cwd.value = null
    }

    /**
     * The transport dropped (or is being redialed): the shell that reported the
     * cwd, ran the voyage and hauled the cargo went down with it. The redialed
     * shell sounds its own.
     */
    fun transportDropped() {
        forgetCwd()
        synchronized(lock) {
            rigged = false
            voyage = null
        }
        endCargo()
    }

    // ---- The lading (package installs) -----------------------------------------------
    // A submitted command that invokes a package manager arms the watch; while the UI
    // shows this session it ticks [cargoTick], which gleans the bottom rows whenever
    // new output has landed. Every end — the command finishing, ^C, the alternate
    // screen, the transport dropping — goes through [CargoWatch], so none of them can
    // leave the barge out on the water.

    private val cargoWatch = CargoWatch()

    private val _cargo = MutableStateFlow<CargoWatch.View?>(null)
    /** What the lading strip shows right now; null = no strip. */
    val cargo: StateFlow<CargoWatch.View?> = _cargo

    private val _cargoAwake = MutableStateFlow(false)
    /** True while the watch has anything left to show — the UI ticks only then. */
    val cargoAwake: StateFlow<Boolean> = _cargoAwake

    /** The package manager under watch, or null. */
    val cargoManager: String? get() = synchronized(lock) { cargoWatch.manager }

    /** [outputTick] at the last glean: no new bytes, nothing new to read. */
    private var cargoGleanTick = -1L

    /** This world's shell reports its commands finishing (an OSC 133 D was seen). */
    private var rigged = false

    /**
     * One beat of the lading, called by the UI every ~200 ms while [cargoAwake]:
     * glean the tail if output arrived since the last beat, let time pass, publish.
     * Returns whether to keep ticking.
     */
    fun cargoTick(now: Long = clock()): Boolean = synchronized(lock) {
        if (cargoWatch.armed) {
            val tick = outputTick.value
            if (tick != cargoGleanTick) {
                cargoGleanTick = tick
                cargoWatch.glean(CargoLading.glean(tailText(CARGO_TAIL_ROWS)), now)
            }
        }
        cargoWatch.voyageRunning = rigged && voyage != null
        cargoWatch.tick(now)
        publishCargo(now)
        cargoWatch.awake(now)
    }

    /** The barge goes, whatever it was doing (a dropped transport, a redial). */
    fun endCargo() {
        synchronized(lock) {
            cargoWatch.abandon()
            publishCargo(clock())
        }
    }

    private fun publishCargo(now: Long) {
        _cargo.value = cargoWatch.view(now)
        _cargoAwake.value = cargoWatch.awake(now)
    }

    /**
     * The live edge as plain text (the cargo glean), under [lock]: the [n] rows
     * ending at the cursor — where output is landing, even on a screen cleared just
     * before — plus the bottom row, where apt pins its progress bar.
     */
    private fun tailText(n: Int): List<String> {
        val end = term.cursorY.coerceIn(0, term.rows - 1)
        val rows = ((end - n + 1).coerceAtLeast(0)..end).mapTo(ArrayList(n + 1)) {
            term.screen.line(it).toText()
        }
        if (end < term.rows - 1) rows += term.screen.line(term.rows - 1).toText()
        return rows
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
                c == '\r' || c == '\n' -> {
                    commitLine()
                    if (!wasAltScreen) _cwd.value = null   // the next rigged prompt re-sounds it
                    i++
                }
                c == '\u0003' -> { resetLine(); abandonCargo(); voyage = null; i++ } // ^C sinks lading + voyage
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
                c == '\u0003' -> { endToll(); resetLine(); abandonCargo(); i++ } // rite abandoned
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
                watchCargo(cmd)
                voyage = Voyage(cmd, clock())
            }
        }
        resetLine()
    }

    /** A line went to the shell: arm the lading for a package manager, or let a
     *  finished one go. What is already on screen is seen, not sighted. */
    private fun watchCargo(cmd: String) {
        synchronized(lock) {
            val now = clock()
            val manager = CargoLading.match(cmd)
            if (manager != null) {
                cargoWatch.arm(manager, now)
                cargoWatch.glean(CargoLading.glean(tailText(CARGO_TAIL_ROWS)), now, seedOnly = true)
                cargoGleanTick = outputTick.value
            } else {
                cargoWatch.lineSubmitted(cmd, now)
            }
            publishCargo(now)
        }
    }

    private fun abandonCargo() {
        synchronized(lock) {
            cargoWatch.abandon()
            publishCargo(clock())
        }
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
        // Pasted text is not typing: a pasted block can carry prose or secrets that
        // pass every shape rule, so the history only ever learns keystrokes. The
        // line goes untrusted — no draft, no suggestions, nothing recorded on Enter.
        synchronized(lock) {
            if (_toll.value == null) {
                lineTrusted = false
                lineBuf.setLength(0)
                _commandDraft.value = ""
            }
        }
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
            // A pinch can change the cell size without changing the grid; the PTY
            // still needs telling, or image tools keep sizing to the old cells.
            val c = term.cols != cols || term.rows != rows ||
                term.cellWidthPx != cellWidthPx || term.cellHeightPx != cellHeightPx
            val grid = term.cols != cols || term.rows != rows
            term.cellWidthPx = cellWidthPx
            term.cellHeightPx = cellHeightPx
            if (grid) {
                term.resize(cols, rows)
                // The toll is pinned to its prompt's row, and a shrink carries that
                // row up the glass with the cursor (the keyboard rising to type the
                // password): follow it, or the next byte would read as "moved on".
                if (_toll.value != null && !term.usingAlt) {
                    tollRow = term.cursorY
                    tollPrompt = term.screen.line(tollRow).toText()
                }
            }
            c to grid
        }
        val (any, grid) = changed
        if (grid) {
            clearSelection() // the old cells no longer mean anything at the new geometry
            scrollToBottom()
            dims.value = cols to rows
        }
        if (any) onResize?.invoke(cols, rows, cols * cellWidthPx, rows * cellHeightPx)
    }

    private companion object {
        /** How many bottom rows the lading reads. */
        const val CARGO_TAIL_ROWS = 8

        /** The closest two bells may ring. */
        const val BELL_GAP_NS = 400_000_000L

        /** A command shorter than this finishing in another tab isn't worth a flash. */
        const val SIGNAL_MIN_MS = 5_000L

        /** A far-side call's title and body are cut to this before anyone sees them. */
        const val CALL_MAX = 240
    }
}
