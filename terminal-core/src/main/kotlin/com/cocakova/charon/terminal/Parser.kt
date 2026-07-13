package com.cocakova.charon.terminal

/**
 * Receives parsed actions from [Parser]. Implemented by the terminal emulator.
 *
 * The [CsiParams] instance passed to [csiDispatch]/[dcsHook] is owned and reused by the
 * parser: consume it during the call, never retain it.
 */
interface ParserSink {
    /** A printable code point (already UTF-8 decoded, wcwidth not yet applied). */
    fun print(codePoint: Int)

    /** A C0 (0x00-0x1F) or C1 (0x80-0x9F) control executed outside sequences. */
    fun execute(control: Int)

    /**
     * A completed CSI sequence. [collected] holds private markers and intermediates in
     * arrival order ("?" for `CSI ? 25 h`, "" for plain, "$" for DECRQM, …).
     */
    fun csiDispatch(params: CsiParams, collected: String, final: Char)

    /** A completed escape sequence ("M" for RI, "7"/"8" for DECSC/DECRC, "(0" charset, …). */
    fun escDispatch(collected: String, final: Char)

    /** A completed OSC string, terminated by BEL or ST. Payload excludes the terminator. */
    fun oscDispatch(payload: String)

    /** DCS start: same shape as CSI; data follows via [dcsPut] until [dcsUnhook]. */
    fun dcsHook(params: CsiParams, collected: String, final: Char)
    fun dcsPut(codePoint: Int)
    fun dcsUnhook()
}

/**
 * The VT500-series parser: Paul Flo Williams' state machine
 * (https://vt100.net/emu/dec_ansi_parser), operating on decoded code points.
 *
 * Total by construction — no input sequence may throw or grow memory without bound
 * (OSC/DCS payloads are capped). Feed it code points from [Utf8Decoder].
 */
class Parser(private val sink: ParserSink) {

    private enum class State {
        GROUND,
        ESCAPE,
        ESCAPE_INTERMEDIATE,
        CSI_ENTRY,
        CSI_PARAM,
        CSI_INTERMEDIATE,
        CSI_IGNORE,
        OSC_STRING,
        DCS_ENTRY,
        DCS_PARAM,
        DCS_INTERMEDIATE,
        DCS_PASSTHROUGH,
        DCS_IGNORE,
        SOS_PM_APC_STRING,
    }

    private var state = State.GROUND
    private val params = CsiParams()
    private val collected = StringBuilder()
    private val oscPayload = StringBuilder()
    private var inDcsPassthrough = false

    companion object {
        /** Cap on OSC payload length; beyond this the rest of the string is dropped. */
        const val MAX_OSC_LENGTH = 65536
        private const val MAX_COLLECTED = 8
    }

    fun feed(codePoint: Int) {
        // "Anywhere" transitions take priority over per-state handling.
        when (codePoint) {
            0x18, 0x1A -> { // CAN / SUB: abort any sequence, execute, back to ground
                abortDcsIfActive()
                sink.execute(codePoint)
                toGround()
                return
            }
            0x1B -> {
                // ESC inside an OSC string may be the start of ST (ESC \); the OSC
                // dispatch happens when the ST completes, via escDispatch below.
                if (state == State.OSC_STRING) {
                    state = State.ESCAPE
                    collected.setLength(0)
                    return
                }
                abortDcsIfActive()
                clear()
                state = State.ESCAPE
                return
            }
            0x9C -> { // ST (8-bit)
                when (state) {
                    State.OSC_STRING -> dispatchOsc()
                    State.DCS_PASSTHROUGH -> abortDcsIfActive()
                    else -> {}
                }
                toGround()
                return
            }
            0x90 -> { abortDcsIfActive(); clear(); state = State.DCS_ENTRY; return }
            0x9B -> { abortDcsIfActive(); clear(); state = State.CSI_ENTRY; return }
            0x9D -> { abortDcsIfActive(); beginOsc(); return }
            0x98, 0x9E, 0x9F -> { abortDcsIfActive(); state = State.SOS_PM_APC_STRING; return }
            in 0x80..0x9F -> { // remaining C1 controls execute from anywhere
                abortDcsIfActive()
                sink.execute(codePoint)
                toGround()
                return
            }
        }

        when (state) {
            State.GROUND -> when (codePoint) {
                in 0x00..0x1F -> if (codePoint != 0x7F) sink.execute(codePoint)
                0x7F -> {} // DEL is ignored
                else -> sink.print(codePoint)
            }

            State.ESCAPE -> when (codePoint) {
                in 0x00..0x1F -> sink.execute(codePoint)
                in 0x20..0x2F -> { collect(codePoint); state = State.ESCAPE_INTERMEDIATE }
                0x50 -> { clear(); state = State.DCS_ENTRY }
                0x58, 0x5E, 0x5F -> state = State.SOS_PM_APC_STRING
                0x5B -> { clear(); state = State.CSI_ENTRY }
                0x5C -> { // ST via ESC \ — terminates an OSC begun before this ESC
                    if (oscActive) dispatchOsc()
                    toGround()
                }
                0x5D -> beginOsc()
                in 0x30..0x7E -> { sink.escDispatch(collected.toString(), codePoint.toChar()); toGround() }
                0x7F -> {}
                else -> toGround() // non-ASCII after ESC: not a valid sequence
            }

            State.ESCAPE_INTERMEDIATE -> when (codePoint) {
                in 0x00..0x1F -> sink.execute(codePoint)
                in 0x20..0x2F -> collect(codePoint)
                in 0x30..0x7E -> { sink.escDispatch(collected.toString(), codePoint.toChar()); toGround() }
                0x7F -> {}
                else -> toGround()
            }

            State.CSI_ENTRY -> when (codePoint) {
                in 0x00..0x1F -> sink.execute(codePoint)
                in 0x20..0x2F -> { collect(codePoint); state = State.CSI_INTERMEDIATE }
                in 0x30..0x39 -> { params.digit(codePoint - 0x30); state = State.CSI_PARAM }
                0x3A -> { params.nextSubParam(); state = State.CSI_PARAM }
                0x3B -> { params.nextParam(); state = State.CSI_PARAM }
                in 0x3C..0x3F -> { collect(codePoint); state = State.CSI_PARAM }
                in 0x40..0x7E -> { dispatchCsi(codePoint); toGround() }
                0x7F -> {}
                else -> toGround()
            }

            State.CSI_PARAM -> when (codePoint) {
                in 0x00..0x1F -> sink.execute(codePoint)
                in 0x30..0x39 -> params.digit(codePoint - 0x30)
                0x3A -> params.nextSubParam()
                0x3B -> params.nextParam()
                in 0x3C..0x3F -> state = State.CSI_IGNORE // private marker after params: malformed
                in 0x20..0x2F -> { collect(codePoint); state = State.CSI_INTERMEDIATE }
                in 0x40..0x7E -> { dispatchCsi(codePoint); toGround() }
                0x7F -> {}
                else -> toGround()
            }

            State.CSI_INTERMEDIATE -> when (codePoint) {
                in 0x00..0x1F -> sink.execute(codePoint)
                in 0x20..0x2F -> collect(codePoint)
                in 0x30..0x3F -> state = State.CSI_IGNORE
                in 0x40..0x7E -> { dispatchCsi(codePoint); toGround() }
                0x7F -> {}
                else -> toGround()
            }

            State.CSI_IGNORE -> when (codePoint) {
                in 0x00..0x1F -> sink.execute(codePoint)
                in 0x40..0x7E -> toGround()
                else -> {} // swallow
            }

            State.OSC_STRING -> when (codePoint) {
                0x07 -> { dispatchOsc(); toGround() } // BEL terminator (xterm)
                in 0x00..0x1F -> {} // other C0 ignored inside OSC
                else -> if (oscPayload.length < MAX_OSC_LENGTH) oscPayload.appendCodePoint(codePoint)
            }

            State.DCS_ENTRY -> when (codePoint) {
                in 0x00..0x1F, 0x7F -> {} // ignored
                in 0x20..0x2F -> { collect(codePoint); state = State.DCS_INTERMEDIATE }
                in 0x30..0x39 -> { params.digit(codePoint - 0x30); state = State.DCS_PARAM }
                0x3A -> { params.nextSubParam(); state = State.DCS_PARAM }
                0x3B -> { params.nextParam(); state = State.DCS_PARAM }
                in 0x3C..0x3F -> { collect(codePoint); state = State.DCS_PARAM }
                in 0x40..0x7E -> hookDcs(codePoint)
                else -> toGround()
            }

            State.DCS_PARAM -> when (codePoint) {
                in 0x00..0x1F, 0x7F -> {}
                in 0x30..0x39 -> params.digit(codePoint - 0x30)
                0x3A -> params.nextSubParam()
                0x3B -> params.nextParam()
                in 0x3C..0x3F -> state = State.DCS_IGNORE
                in 0x20..0x2F -> { collect(codePoint); state = State.DCS_INTERMEDIATE }
                in 0x40..0x7E -> hookDcs(codePoint)
                else -> toGround()
            }

            State.DCS_INTERMEDIATE -> when (codePoint) {
                in 0x00..0x1F, 0x7F -> {}
                in 0x20..0x2F -> collect(codePoint)
                in 0x30..0x3F -> state = State.DCS_IGNORE
                in 0x40..0x7E -> hookDcs(codePoint)
                else -> toGround()
            }

            State.DCS_PASSTHROUGH -> when (codePoint) {
                0x7F -> {}
                else -> sink.dcsPut(codePoint)
            }

            State.DCS_IGNORE, State.SOS_PM_APC_STRING -> {
                // Swallow everything until ST/CAN/SUB/ESC (handled by "anywhere" above).
            }
        }
    }

    fun feed(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            feed(cp)
            i += Character.charCount(cp)
        }
    }

    // An OSC string is open (possibly empty). Cleared on dispatch or abort, so a stray
    // ST never re-dispatches stale payload, and a sequence that interrupts an OSC
    // (e.g. ESC ] … ESC [ m) aborts it instead of leaking it.
    private var oscActive = false

    private fun beginOsc() {
        oscPayload.setLength(0)
        oscActive = true
        state = State.OSC_STRING
    }

    private fun dispatchOsc() {
        sink.oscDispatch(oscPayload.toString())
        oscPayload.setLength(0)
        oscActive = false
    }

    private fun abortOsc() {
        oscPayload.setLength(0)
        oscActive = false
    }

    private fun dispatchCsi(final: Int) {
        sink.csiDispatch(params, collected.toString(), final.toChar())
    }

    private fun hookDcs(final: Int) {
        inDcsPassthrough = true
        sink.dcsHook(params, collected.toString(), final.toChar())
        state = State.DCS_PASSTHROUGH
    }

    private fun abortDcsIfActive() {
        if (inDcsPassthrough) {
            inDcsPassthrough = false
            sink.dcsUnhook()
        }
    }

    private fun collect(codePoint: Int) {
        if (collected.length < MAX_COLLECTED) collected.append(codePoint.toChar())
    }

    private fun clear() {
        params.clear()
        collected.setLength(0)
    }

    private fun toGround() {
        abortDcsIfActive()
        if (oscActive) abortOsc()
        state = State.GROUND
    }
}
