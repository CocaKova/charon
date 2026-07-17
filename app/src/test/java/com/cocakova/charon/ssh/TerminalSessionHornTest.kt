package com.cocakova.charon.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voyage ledger behind the horn: Enter starts a voyage, OSC 133 D ends it with
 * the exit code, ^C abandons it, and a D with no voyage (or an unrigged shell's
 * silence) sounds nothing.
 */
class TerminalSessionHornTest {

    private class Done(val command: String, val exit: Int?, val durationMs: Long)

    private fun session(onDone: (Done) -> Unit): TerminalSession =
        TerminalSession(label = "test@styx").apply {
            onCommandDone = { c, e, d -> onDone(Done(c, e, d)) }
        }

    private fun TerminalSession.remote(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feedRemote(b, 0, b.size)
    }

    @Test
    fun `a rigged shell's D ends the voyage with command and exit code`() {
        var done: Done? = null
        val s = session { done = it }
        s.trackInput("make -j8")
        s.trackInput("\r")
        s.remote("make -j8\r\nlots of output\r\n")
        s.remote("\u001b]133;D;2\u0007")
        assertEquals("make -j8", done?.command)
        assertEquals(2, done?.exit)
        assertTrue(done!!.durationMs >= 0)
    }

    @Test
    fun `C refines the start without ending anything`() {
        var done: Done? = null
        val s = session { done = it }
        s.trackInput("sleep 5\r")
        s.remote("\u001b]133;C\u0007")
        assertNull(done)
        s.remote("\u001b]133;D;0\u0007")
        assertEquals("sleep 5", done?.command)
        assertEquals(0, done?.exit)
    }

    @Test
    fun `ctrl-C abandons the voyage — the late D sounds nothing`() {
        var done: Done? = null
        val s = session { done = it }
        s.trackInput("sleep 999\r")
        s.trackInput("\u0003")
        s.remote("\u001b]133;D;130\u0007")
        assertNull(done)
    }

    @Test
    fun `a D with no voyage pending is ignored`() {
        var done: Done? = null
        val s = session { done = it }
        s.remote("\u001b]133;D;0\u0007")
        assertNull(done)
    }

    @Test
    fun `a new commit replaces the old voyage`() {
        val dones = mutableListOf<Done>()
        val s = session { dones += it }
        s.trackInput("first\r")
        s.trackInput("second\r")
        s.remote("\u001b]133;D;0\u0007")
        assertEquals(listOf("second"), dones.map { it.command })
    }
}
