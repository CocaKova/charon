package com.cocakova.charon.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The toll: while the remote reads a secret, nothing typed may reach the autofill
 * draft or the command history. Control characters are built from code points so
 * no raw byte ever sits in this source (the documented editor trap).
 */
class TerminalSessionTollTest {

    private val ctrlC = Char(0x03).toString()

    private fun TerminalSession.feed(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feedRemote(b, 0, b.size)
    }

    @Test
    fun sudoPasswordNeverReachesDraftOrHistory() {
        val session = TerminalSession("t")
        val committed = mutableListOf<String>()
        session.onCommandSubmitted = { committed += it }

        session.trackInput("sudo apt install moonlight-qt")
        assertEquals("sudo apt install moonlight-qt", session.commandDraft.value)
        session.trackInput("\r")
        session.feed("sudo apt install moonlight-qt\r\n[sudo] password for jonny: ")

        assertEquals(TerminalSession.TollPhase.ASKED, session.toll.value)
        assertEquals("apt", session.cargo.value?.manager)

        session.trackInput("hunter2")
        assertEquals("", session.commandDraft.value)
        assertEquals(7, session.tollPulse.value)

        session.trackInput("\r")
        assertEquals(TerminalSession.TollPhase.PAID, session.toll.value)
        session.feed("\r\n")
        assertNull(session.toll.value)

        assertEquals(listOf("sudo apt install moonlight-qt"), committed)
    }

    @Test
    fun wrongPasswordReArmsTheToll() {
        val session = TerminalSession("t")
        session.feed("[sudo] password for jonny: ")
        assertEquals(TerminalSession.TollPhase.ASKED, session.toll.value)
        session.trackInput("oops")
        session.trackInput("\r")
        session.feed("\r\nSorry, try again.\r\n[sudo] password for jonny: ")
        assertEquals(TerminalSession.TollPhase.ASKED, session.toll.value)
    }

    @Test
    fun ctrlCAbandonsTheRite() {
        val session = TerminalSession("t")
        session.feed("Password: ")
        assertEquals(TerminalSession.TollPhase.ASKED, session.toll.value)
        session.trackInput(ctrlC)
        assertNull(session.toll.value)
    }

    @Test
    fun backspaceDrainsThePulseButNotBelowZero() {
        val session = TerminalSession("t")
        session.feed("Enter passphrase for key '/home/jonny/.ssh/id_ed25519': ")
        assertEquals(TerminalSession.TollPhase.ASKED, session.toll.value)
        session.trackInput("ab")
        session.trackInput(Char(0x7f).toString().repeat(3))
        assertEquals(0, session.tollPulse.value)
    }

    @Test
    fun ordinaryColonLinesDoNotArm() {
        val session = TerminalSession("t")
        session.feed("total 12\r\nUsage: run one of the following:")
        assertNull(session.toll.value)
        session.feed("\r\njonny@spark:~$ ")
        assertNull(session.toll.value)
    }

    @Test
    fun echoNetForgetsUnechoedTyping() {
        val session = TerminalSession("t")
        session.feed("hidden> ")
        session.trackInput("se")
        assertNotEquals(0L, session.echoPending)
        assertEquals("se", session.commandDraft.value)

        session.markHiddenInput()
        assertEquals(TerminalSession.TollPhase.ASKED, session.toll.value)
        assertEquals("", session.commandDraft.value)

        val committed = mutableListOf<String>()
        session.onCommandSubmitted = { committed += it }
        session.trackInput("cret")
        session.trackInput("\r")
        assertEquals(emptyList<String>(), committed)
    }

    @Test
    fun echoCancelsThePendingProbe() {
        val session = TerminalSession("t")
        session.feed("jonny@spark:~$ ")
        session.trackInput("l")
        assertNotEquals(0L, session.echoPending)
        session.feed("l")
        assertEquals(0L, session.echoPending)
    }
}
