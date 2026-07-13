package com.cocakova.charon.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ESC = "\u001B"
private const val BEL = "\u0007"
private const val CAN = "\u0018"
private const val SUB = "\u001A"
private const val ST = "$ESC\\"

/** Records every sink callback as a compact string for whole-stream assertions. */
private open class RecordingSink : ParserSink {
    val events = mutableListOf<String>()
    override fun print(codePoint: Int) {
        events += "print:" + String(Character.toChars(codePoint))
    }
    override fun execute(control: Int) {
        events += "exec:%02X".format(control)
    }
    override fun csiDispatch(params: CsiParams, collected: String, final: Char) {
        events += "csi:$collected$params$final"
    }
    override fun escDispatch(collected: String, final: Char) {
        events += "esc:$collected$final"
    }
    override fun oscDispatch(payload: String) {
        events += "osc:$payload"
    }
    override fun dcsHook(params: CsiParams, collected: String, final: Char) {
        events += "dcsHook:$collected$params$final"
    }
    override fun dcsPut(codePoint: Int) {
        events += "dcsPut:" + codePoint.toChar()
    }
    override fun dcsUnhook() {
        events += "dcsUnhook"
    }
}

class ParserTest {

    private fun run(input: String): List<String> {
        val sink = RecordingSink()
        Parser(sink).feed(input)
        return sink.events
    }

    @Test
    fun plainTextPrints() {
        assertEquals(listOf("print:h", "print:i"), run("hi"))
    }

    @Test
    fun c0ControlsExecute() {
        assertEquals(listOf("exec:0D", "exec:0A"), run("\r\n"))
    }

    @Test
    fun csiNoParams() {
        assertEquals(listOf("csi:m"), run("$ESC[m"))
    }

    @Test
    fun csiMultiParams() {
        assertEquals(listOf("csi:1;31m"), run("$ESC[1;31m"))
    }

    @Test
    fun csiEmptyFirstParam() {
        val sink = object : RecordingSink() {
            var row = -99
            var col = -99
            override fun csiDispatch(params: CsiParams, collected: String, final: Char) {
                row = params.get(0, 1)
                col = params.get(1, 1)
            }
        }
        Parser(sink).feed("$ESC[;5H")
        assertEquals(1, sink.row)
        assertEquals(5, sink.col)
    }

    @Test
    fun csiPrivateMarker() {
        assertEquals(listOf("csi:?1049h"), run("$ESC[?1049h"))
    }

    @Test
    fun csiIntermediate() {
        assertEquals(listOf("csi:!p"), run("$ESC[!p")) // DECSTR
    }

    @Test
    fun sgrColonSubparams() {
        var subs: List<Int>? = null
        val sink = object : RecordingSink() {
            override fun csiDispatch(params: CsiParams, collected: String, final: Char) {
                subs = (1..params.subCount(0)).map { params.sub(0, it, -1) }
            }
        }
        Parser(sink).feed("$ESC[38:2:10:20:30m")
        assertEquals(listOf(2, 10, 20, 30), subs)
    }

    @Test
    fun csiSplitAcrossFeeds() {
        val sink = RecordingSink()
        val p = Parser(sink)
        p.feed("$ESC[3")
        p.feed("8;5;196m")
        assertEquals(listOf("csi:38;5;196m"), sink.events)
    }

    @Test
    fun escDispatch() {
        assertEquals(listOf("esc:M"), run("${ESC}M")) // RI
        assertEquals(listOf("esc:7", "esc:8"), run("${ESC}7${ESC}8")) // DECSC/DECRC
    }

    @Test
    fun charsetDesignation() {
        assertEquals(listOf("esc:(0"), run("$ESC(0")) // G0 := DEC Special Graphics
    }

    @Test
    fun oscTerminatedByBel() {
        assertEquals(listOf("osc:0;my title"), run("$ESC]0;my title$BEL"))
    }

    @Test
    fun oscTerminatedBySt() {
        assertEquals(listOf("osc:2;abc"), run("$ESC]2;abc$ST"))
    }

    @Test
    fun oscInterruptedByCsiIsAborted() {
        // vim does this dance: an OSC gets cut off by a new sequence; the junk must not
        // leak into a later ST
        assertEquals(listOf("csi:m"), run("$ESC]0;junk$ESC[m"))
        assertEquals(emptyList(), run(ST)) // bare ST dispatches nothing
    }

    @Test
    fun canAbortsCsi() {
        assertEquals(listOf("exec:18", "print:m"), run("$ESC[12${CAN}m"))
    }

    @Test
    fun subAbortsOsc() {
        assertEquals(listOf("exec:1A"), run("$ESC]0;x$SUB"))
        assertEquals(emptyList(), run(ST))
    }

    @Test
    fun delIgnoredInGround() {
        assertEquals(listOf("print:a", "print:b"), run("ab"))
    }

    @Test
    fun eightBitC1Csi() {
        val sink = RecordingSink()
        val p = Parser(sink)
        p.feed(0x9B) // 8-bit CSI
        p.feed("5A")
        assertEquals(listOf("csi:5A"), sink.events)
    }

    @Test
    fun dcsHookPutUnhook() {
        assertEquals(
            listOf("dcsHook:q", "dcsPut:#", "dcsPut:1", "dcsUnhook"),
            run("${ESC}Pq#1$ST"),
        )
    }

    @Test
    fun dcsWithParamsAndIntermediate() {
        // DECRQSS: DCS $ q " p ST — collected "$", final 'q', payload "\"p"
        assertEquals(
            listOf("dcsHook:\$q", "dcsPut:\"", "dcsPut:p", "dcsUnhook"),
            run("${ESC}P\$q\"p$ST"),
        )
    }

    @Test
    fun paramOverflowIsSwallowedNotMerged() {
        var count = -1
        var last = -1
        val sink = object : RecordingSink() {
            override fun csiDispatch(params: CsiParams, collected: String, final: Char) {
                count = params.count
                last = params.get(params.count - 1, -1)
            }
        }
        val overload = (1..40).joinToString(";") { it.toString() }
        Parser(sink).feed("$ESC[${overload}m")
        assertEquals(CsiParams.MAX_PARAMS, count)
        // the 32nd param must be exactly 32 — params 33..40 swallowed, not merged in
        assertEquals(32, last)
    }

    @Test
    fun oscPayloadIsCapped() {
        var got = -1
        val sink = object : RecordingSink() {
            override fun oscDispatch(payload: String) { got = payload.length }
        }
        val p = Parser(sink)
        p.feed("$ESC]")
        val chunk = "x".repeat(8192)
        repeat(10) { p.feed(chunk) } // 80 KB > cap
        p.feed(BEL)
        assertEquals(Parser.MAX_OSC_LENGTH, got)
    }

    @Test
    fun printableUnicodePassesThrough() {
        assertEquals(listOf("print:漢", "print:🚀"), run("漢🚀"))
    }

    @Test
    fun escFollowedByGarbageRecovers() {
        val events = run("${ESC}éok") // ESC é — invalid; parser recovers, text prints
        assertTrue(events.containsAll(listOf("print:o", "print:k")))
    }

    @Test
    fun interleavedControlsInsideCsi() {
        // xterm executes C0 controls that arrive mid-sequence
        assertEquals(listOf("exec:0A", "csi:5B"), run("$ESC[5\nB"))
    }
}
