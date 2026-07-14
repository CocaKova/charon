package com.cocakova.charon.ssh

import com.cocakova.charon.terminal.TerminalEmulator
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
        data class Disconnected(val reason: String) : State()
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

    fun feedRemote(bytes: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            term.write(bytes, offset, length)
        }
    }

    fun sendText(text: String) {
        onOutput?.invoke(text.toByteArray(Charsets.UTF_8))
    }

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
            dims.value = cols to rows
            onResize?.invoke(cols, rows)
        }
    }
}
