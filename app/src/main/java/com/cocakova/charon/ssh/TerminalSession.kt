package com.cocakova.charon.ssh

import com.cocakova.charon.terminal.TerminalEmulator
import com.cocakova.charon.terminal.TextSelection
import com.cocakova.charon.terminal.input.KeyEncoder
import com.cocakova.charon.terminal.input.MouseEncoder
import kotlinx.coroutines.flow.MutableStateFlow
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
            // While the user is scrolled up, grow the offset by however many lines
            // were just evicted into scrollback so the viewport stays put instead of
            // drifting under new output.
            if (scrollOffset.value > 0) {
                val grew = term.screen.scrollbackSize - before
                if (grew > 0) {
                    scrollOffset.value =
                        (scrollOffset.value + grew).coerceAtMost(term.screen.scrollbackSize)
                }
            }
        }
    }

    /** Scroll the viewport by [deltaRows] (positive = toward older history). */
    fun scrollBy(deltaRows: Int) {
        val max = synchronized(lock) { term.screen.scrollbackSize }
        val next = (scrollOffset.value + deltaRows).coerceIn(0, max)
        if (next != scrollOffset.value) {
            scrollOffset.value = next
            clearSelection() // selection cells were pinned to the old viewport
        }
    }

    fun scrollToBottom() {
        if (scrollOffset.value != 0) scrollOffset.value = 0
    }

    fun sendText(text: String) {
        onOutput?.invoke(text.toByteArray(Charsets.UTF_8))
    }

    // ---- Selection (visible grid; scrollback selection is a later cut) -------------

    data class Selection(val anchor: TextSelection.Cell, val focus: TextSelection.Cell)

    /** Live selection for the renderer to tint and the copy affordance to read. */
    val selection = MutableStateFlow<Selection?>(null)

    /** True while the remote app is tracking the mouse (any DECSET 9/1000/1002/1003). */
    val mouseActive: Boolean get() = synchronized(lock) { term.mouseMode != 0 }

    fun selectWordAt(cell: TextSelection.Cell) {
        selection.value = synchronized(lock) {
            val row = cell.row.coerceIn(0, term.rows - 1)
            val line = term.screen.viewLine(scrollOffset.value, row)
            val range = TextSelection.wordAt(line, cell.col.coerceIn(0, term.cols - 1))
            Selection(TextSelection.Cell(row, range.first), TextSelection.Cell(row, range.last))
        }
    }

    fun startSelection(cell: TextSelection.Cell) {
        selection.value = Selection(cell, cell)
    }

    fun extendSelection(focus: TextSelection.Cell) {
        selection.value = selection.value?.copy(focus = focus)
    }

    fun clearSelection() {
        selection.value = null
    }

    /** Copy the selection as plain text (wrapped lines joined), or null if none. */
    fun copySelection(): String? {
        val sel = selection.value ?: return null
        return synchronized(lock) {
            TextSelection.extract(term.screen, sel.anchor, sel.focus, scrollOffset.value)
        }
    }

    // ---- Paste & mouse reporting ---------------------------------------------------

    /** Paste text to the remote, bracketed-guarded when the app asked for it. */
    fun paste(text: String) {
        if (text.isEmpty()) return
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
