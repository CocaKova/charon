package com.cocakova.charon.ssh

import com.cocakova.charon.cargo.CargoWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prompt hops, the horn's landing, whisper lengths, tab signals, calls and progress. */
class TerminalSessionSoundingsTest {

    private var now = 0L
    private fun session() = TerminalSession(label = "t", cols = 30, rows = 5, clock = { now })

    private fun TerminalSession.remote(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feedRemote(b, 0, b.size)
    }

    private fun osc(body: String) = "\u001b]$body\u0007"

    private fun TerminalSession.run(cmd: String, lines: Int, exit: Int, tookMs: Long) {
        remote(osc("133;A") + "$ " + osc("133;B"))
        trackInput("$cmd\r")
        remote("$cmd\r\n" + osc("133;C"))
        now += tookMs * 1_000_000
        repeat(lines) { remote("out $it\r\n") }
        remote(osc("133;D;$exit"))
    }

    @Test
    fun `the whisper's length is output-start to done on the session clock`() {
        val s = session()
        s.run("make", 1, 0, 134_000)
        val mark = s.term.screen.relativeLine(s.promptRows().last()).promptMark!!
        assertEquals("✓ 2m14s", mark.whisper())
    }

    @Test
    fun `prompt hops walk the commands in history and the horn lands on its own`() {
        val s = session()
        s.run("one", 6, 0, 10)
        val firstId = s.lastFinishedMarkId
        s.run("two", 6, 1, 10)
        s.run("three", 6, 0, 10)
        s.remote(osc("133;A") + "$ ")
        val prompts = s.promptRows()
        assertEquals(4, prompts.size)

        // From the live edge, hop up: the newest prompt above the glass's top.
        assertTrue(s.jumpToPrompt(older = true))
        val firstHop = -s.scrollOffset.value
        assertTrue(firstHop in prompts && firstHop < 0)
        assertTrue(s.jumpToPrompt(older = true))
        assertTrue(-s.scrollOffset.value < firstHop)
        // Down again, then past the last one back to the live edge.
        assertTrue(s.jumpToPrompt(older = false))
        assertEquals(firstHop, -s.scrollOffset.value)

        s.scrollToBottom()
        assertTrue(s.landOnMark(firstId))
        assertEquals(prompts.first(), -s.scrollOffset.value)
        assertFalse(s.landOnMark(9_999))
    }

    @Test
    fun `a long command finishing raises a signal, a short one doesn't`() {
        val s = session()
        s.run("ls", 1, 0, 200)
        assertNull(s.signal.value)
        s.run("make", 1, 2, 6_000)
        assertEquals(TerminalSession.SignalKind.AGROUND, s.signal.value?.kind)
        s.run("make", 1, 0, 6_000)
        assertEquals(TerminalSession.SignalKind.DOCKED, s.signal.value?.kind)
    }

    @Test
    fun `a far-side call is relayed, trimmed, and flagged for the tab`() {
        val s = session()
        val heard = ArrayList<Pair<String?, String>>()
        s.onCall = { t, b -> heard += t to b }
        s.remote(osc("777;notify;ci;" + "x".repeat(500)))
        assertEquals("ci", heard.single().first)
        assertEquals(240, heard.single().second.length)
        assertEquals(TerminalSession.SignalKind.CALL, s.signal.value?.kind)
    }

    @Test
    fun `a program's own progress bar steers the barge and clearing it brings her home`() {
        val s = session()
        s.remote(osc("9;4;1;40"))
        assertEquals(CargoWatch.Phase.SAILING, s.cargo.value?.phase)
        assertEquals(40, s.cargo.value?.percent)
        s.remote(osc("9;4;1;100"))
        s.remote(osc("9;4;0"))
        assertEquals(CargoWatch.Phase.ASHORE, s.cargo.value?.phase)
    }
}
