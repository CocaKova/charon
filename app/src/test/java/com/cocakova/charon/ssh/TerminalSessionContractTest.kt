package com.cocakova.charon.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session's half of the modern contract: focus, the bell, yanks, cursor shape. */
class TerminalSessionContractTest {

    private val esc = Char(0x1B).toString()
    private val bel = Char(0x07).toString()
    private var now = 5_000_000_000L

    private fun session(): Pair<TerminalSession, MutableList<String>> {
        val sent = mutableListOf<String>()
        val s = TerminalSession("me@host", clock = { now })
        s.onOutput = { sent += String(it, Charsets.UTF_8) }
        return s to sent
    }

    private fun TerminalSession.feed(text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        feedRemote(b, 0, b.size)
    }

    @Test
    fun focusIsReportedOnlyToProgramsThatAsked() {
        val (s, sent) = session()
        s.focusChanged(true)
        assertTrue(sent.isEmpty())
        s.feed("$esc[?1004h")
        s.focusChanged(true)
        s.focusChanged(true)
        s.focusChanged(false)
        assertEquals(listOf("$esc[I", "$esc[O"), sent)
    }

    @Test
    fun focusReportsNeverTouchTheTypedLine() {
        val (s, _) = session()
        s.feed("$esc[?1004h")
        s.trackInput("ls -l")
        s.focusChanged(true)
        assertEquals("ls -l", s.commandDraft.value)
    }

    @Test
    fun aBellFloodRingsOnce() {
        val (s, _) = session()
        s.feed(bel.repeat(50))
        assertEquals(1L, s.bell.value)
        now += 100_000_000L
        s.feed(bel)
        assertEquals(1L, s.bell.value)
        now += 500_000_000L
        s.feed(bel)
        assertEquals(2L, s.bell.value)
    }

    @Test
    fun aYankWaitsForConsent() {
        val (s, sent) = session()
        assertNull(s.clipboardOffer.value)
        s.feed("$esc]52;c;eWFuaw==$bel")
        assertEquals("yank", s.clipboardOffer.value)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun theCursorShapeIsPublished() {
        val (s, _) = session()
        s.feed("$esc[6 q")
        assertEquals(6, s.cursorStyle.value)
    }

    @Test
    fun theMouseModeIsReadable() {
        val (s, _) = session()
        s.feed("$esc[?1002h")
        assertEquals(1002, s.mouseMode)
    }
}
