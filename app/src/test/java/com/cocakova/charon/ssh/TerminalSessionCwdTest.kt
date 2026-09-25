package com.cocakova.charon.ssh

import com.cocakova.charon.terminal.ShellCwd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The soundings ledger: an OSC 7 report is trusted only while it can still be true.
 * Enter at a primary-screen prompt forgets it (the next rigged prompt re-sounds it;
 * an onward ssh or a REPL never will), tmux on the alternate screen keeps it (tmux
 * only forwards changes), and crossing screens forgets it unless the new world
 * reported its own in the same burst.
 */
class TerminalSessionCwdTest {

    private val esc = Char(0x1b).toString()
    private val bel = Char(0x07).toString()

    private fun TerminalSession.feed(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feedRemote(b, 0, b.size)
    }

    private fun osc7(url: String) = "$esc]7;$url$bel"

    @Test
    fun `a rigged prompt's report becomes the session's cwd`() {
        val s = TerminalSession("t")
        s.feed(osc7("file://spark/home/j") + "j@spark:~$ ")
        assertEquals(ShellCwd("spark", "/home/j"), s.cwd.value)
    }

    @Test
    fun `Enter at a primary prompt forgets it until the next prompt re-sounds it`() {
        val s = TerminalSession("t")
        s.feed(osc7("file://spark/home/j"))
        s.trackInput("cd src\r")
        assertNull(s.cwd.value)
        s.feed(osc7("file://spark/home/j/src"))
        assertEquals("/home/j/src", s.cwd.value?.path)
    }

    @Test
    fun `an onward ssh never re-sounds, so the old cwd stays forgotten`() {
        val s = TerminalSession("t")
        s.feed(osc7("file://spark/home/j"))
        s.trackInput("ssh blackpearl\r")
        s.feed("Welcome to blackpearl\r\nj@blackpearl:~$ ") // an unrigged far shell
        assertNull(s.cwd.value)
    }

    @Test
    fun `inside tmux Enter keeps the cwd — tmux forwards only changes`() {
        val s = TerminalSession("t")
        s.feed("$esc[?1049h" + osc7("file://spark/home/j")) // attach, path in the same burst
        assertEquals("/home/j", s.cwd.value?.path)
        s.trackInput("ls\r")
        assertEquals("/home/j", s.cwd.value?.path)
    }

    @Test
    fun `tmux's empty report clears it`() {
        val s = TerminalSession("t")
        s.feed("$esc[?1049h" + osc7("file://spark/home/j"))
        s.feed("$esc]7;$bel") // switched to a pane that never reported
        assertNull(s.cwd.value)
    }

    @Test
    fun `crossing screens forgets a cwd the new world didn't report`() {
        val s = TerminalSession("t")
        s.feed(osc7("file://spark/home/j"))
        s.feed("$esc[?1049h") // vim, or tmux without the osc7 feature
        assertNull(s.cwd.value)
    }

    @Test
    fun `leaving tmux keeps the outer prompt's report from the same burst`() {
        val s = TerminalSession("t")
        s.feed("$esc[?1049h")
        s.feed("$esc[?1049l[detached]\r\n" + osc7("file://spark/srv") + "$ ")
        assertEquals("/srv", s.cwd.value?.path)
    }

    @Test
    fun `a dropped transport forgets it`() {
        val s = TerminalSession("t")
        s.feed(osc7("file://spark/home/j"))
        s.forgetCwd()
        assertNull(s.cwd.value)
    }
}
