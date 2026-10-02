package com.cocakova.charon.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val E = "\u001B"
private const val BEL = "\u0007"
private const val ST = "\u001B\\"

/**
 * The modern contract, part 2: the queries and modes neovim, tmux and friends lean
 * on — DECRQM, synchronized output, DECSCUSR, OSC 52, DECRQSS and XTGETTCAP.
 */
class ModernContractTest {

    private class Rig {
        var now = 0L
        val responses = mutableListOf<String>()
        val clips = mutableListOf<String>()
        val term = TerminalEmulator(20, 5, 100, onResponse = { responses += it }, clock = { now }).also { t ->
            t.onClipboard = { clips += it }
        }
        fun feed(s: String) = term.write(s)
    }

    // ---------------------------------------------------------------- DECRQM

    @Test
    fun decrqmReportsSynchronizedOutput() {
        val r = Rig()
        r.feed("$E[?2026\$p")
        assertEquals("$E[?2026;2\$y", r.responses.last())
        r.feed("$E[?2026h$E[?2026\$p")
        assertEquals("$E[?2026;1\$y", r.responses.last())
    }

    @Test
    fun decrqmKnowsTheCommonModesAndRefusesStrangers() {
        val r = Rig()
        r.feed("$E[?25\$p")
        assertEquals("$E[?25;1\$y", r.responses.last())
        r.feed("$E[?1005\$p")
        assertEquals("$E[?1005;4\$y", r.responses.last())
        r.feed("$E[?31337\$p")
        assertEquals("$E[?31337;0\$y", r.responses.last())
        r.feed("$E[4h$E[4\$p")
        assertEquals("$E[4;1\$y", r.responses.last())
    }

    // ---------------------------------------------------- synchronized output

    @Test
    fun synchronizedOutputHoldsUntilReleased() {
        val r = Rig()
        r.feed("$E[?2026h")
        assertTrue(r.term.syncHolding())
        r.now += 50_000_000L
        assertTrue(r.term.syncHolding())
        r.feed("$E[?2026l")
        assertFalse(r.term.syncHolding())
    }

    @Test
    fun aFrameLeftOpenIsPaintedAnyway() {
        val r = Rig()
        r.feed("$E[?2026h")
        r.now += TerminalEmulator.SYNC_TIMEOUT_NANOS + 1
        assertFalse(r.term.syncHolding(), "a dead program must never freeze the glass")
    }

    @Test
    fun resetReleasesTheHold() {
        val r = Rig()
        r.feed("$E[?2026h${E}c")
        assertFalse(r.term.syncHolding())
    }

    // ---------------------------------------------------------------- DECSCUSR

    @Test
    fun cursorShapesAreKeptAndResetForgetsThem() {
        val r = Rig()
        r.feed("$E[6 q")
        assertEquals(6, r.term.cursorStyle)
        r.feed("$E[4 q")
        assertEquals(4, r.term.cursorStyle)
        r.feed("$E[9 q")
        assertEquals(1, r.term.cursorStyle, "an unknown shape falls back to the block")
        r.feed("$E[5 q${E}c")
        assertEquals(1, r.term.cursorStyle)
    }

    // ---------------------------------------------------------------- OSC 52

    @Test
    fun osc52YanksReachTheHostAndNothingElse() {
        val r = Rig()
        r.feed("$E]52;c;aGVsbG8gZmVycnk=$BEL")
        assertEquals(listOf("hello ferry"), r.clips)
        r.feed("$E]52;;w6lsYW4=$ST")
        assertEquals("élan", r.clips.last())
        assertTrue(r.responses.isEmpty())
    }

    @Test
    fun osc52ReadsAreNeverAnsweredAndJunkIsDropped() {
        val r = Rig()
        r.feed("$E]52;c;?$BEL")
        r.feed("$E]52;c;!!not base64!!$BEL")
        r.feed("$E]52;c;$BEL")
        assertTrue(r.clips.isEmpty())
        assertTrue(r.responses.isEmpty(), "the phone's clipboard is not the far shore's to read")
    }

    // ---------------------------------------------------------------- DECRQSS

    @Test
    fun decrqssReadsThePenBack() {
        val r = Rig()
        r.feed("$E[1;4:3;38;2;10;20;30;48;5;200m")
        r.feed("${E}P\$qm$ST")
        assertEquals("${E}P1\$r0;1;4:3;38;2;10;20;30;48;5;200m$ST", r.responses.last())
        r.feed("$E[0;31;102m${E}P\$qm$ST")
        assertEquals("${E}P1\$r0;31;102m$ST", r.responses.last())
    }

    @Test
    fun decrqssReadsRegionAndCursorShape() {
        val r = Rig()
        r.feed("$E[2;4r${E}P\$qr$ST")
        assertEquals("${E}P1\$r2;4r$ST", r.responses.last())
        r.feed("$E[5 q${E}P\$q q$ST")
        assertEquals("${E}P1\$r5 q$ST", r.responses.last())
    }

    @Test
    fun decrqssRefusesWhatItDoesNotKnow() {
        val r = Rig()
        r.feed("${E}P\$qx$ST")
        assertEquals("${E}P0\$r$ST", r.responses.last())
    }

    @Test
    fun aCancelledQueryAnswersNothing() {
        val r = Rig()
        r.feed("${E}P\$qm\u0018")
        assertTrue(r.responses.isEmpty())
    }

    // ---------------------------------------------------------------- XTGETTCAP

    private fun hex(s: String) = s.toByteArray().joinToString("") { "%02X".format(it) }

    @Test
    fun xtgettcapAnswersEachNameOnItsOwn() {
        val r = Rig()
        r.feed("${E}P+q${hex("Co")};${hex("Tc")};${hex("nope")}$ST")
        assertEquals(
            listOf(
                "${E}P1+r${hex("Co")}=${hex("256")}$ST",
                "${E}P1+r${hex("Tc")}$ST",
                "${E}P0+r${hex("nope")}$ST",
            ),
            r.responses,
        )
    }

    @Test
    fun xtgettcapNamesTheTerminfoEntry() {
        val r = Rig()
        r.feed("${E}P+q${hex("TN")}$ST")
        assertEquals("${E}P1+r${hex("TN")}=${hex("xterm-256color")}$ST", r.responses.single())
    }

    @Test
    fun xtgettcapSurvivesGarbage() {
        val r = Rig()
        r.feed("${E}P+qZZ;123$ST")
        assertEquals(2, r.responses.size)
        assertTrue(r.responses.all { it.startsWith("${E}P0+r") })
    }
}
