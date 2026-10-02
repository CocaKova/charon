package com.cocakova.charon.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The latency lantern's stopwatch, and the seam a redial leaves in the wake. */
class TerminalSessionLanternTest {

    private var now = 10_000_000_000L
    private fun session() = TerminalSession(label = "t", cols = 40, rows = 5, clock = { now })

    private fun TerminalSession.remote(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feedRemote(b, 0, b.size)
    }

    private fun ms(n: Long) { now += n * 1_000_000 }

    @Test
    fun `a keystroke into quiet water times its echo`() {
        val s = session()
        s.remote("$ ")
        ms(1000)
        s.trackInput("l")
        ms(42)
        s.remote("l")
        assertEquals(42, s.echoMs.value)
        ms(1000)
        s.trackInput("s")
        ms(102)
        s.remote("s")
        // Smoothed: most of the old reading, some of the new.
        assertEquals((42 * 0.7 + 102 * 0.3).toInt(), s.echoMs.value)
    }

    @Test
    fun `a keystroke during a flood times nothing`() {
        val s = session()
        s.remote("flood")
        ms(50)
        s.trackInput("x")
        ms(5)
        s.remote("more flood")
        assertEquals(0, s.echoMs.value)
    }

    @Test
    fun `a silent prompt (a password) is not a round trip`() {
        val s = session()
        s.remote("Password: ")
        ms(1000)
        s.trackInput("h")
        ms(9_000)
        s.remote("\r\nwelcome")
        assertEquals(0, s.echoMs.value)
    }

    @Test
    fun `a seam hangs where the redial landed, above a fresh line and below a shared one`() {
        val s = session()
        s.remote("old output\r\n")
        s.markSeam(atMillis = 1_000, adriftMs = 8_000)
        val seam = s.term.screen.line(1).seam
        assertNotNull(seam)
        assertEquals(8_000, seam!!.adriftMs)
        assertTrue(!seam.below)
        s.remote("$ ")
        s.markSeam(atMillis = 2_000, adriftMs = 1_000)
        assertTrue(s.term.screen.line(1).seam!!.below)
        // A clear takes it with the line.
        s.remote("\u001b[2J")
        assertNull(s.term.screen.line(1).seam)
    }
}
