package com.cocakova.charon.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Command tracking must keep working on the alternate screen: tmux runs its shells
 * there, and per-host tmux auto-attach is the default workflow. Keeping non-command
 * lines (editor text, chat prose) out of history is CommandGate's job at the
 * recording end, not the tracker's. Control characters are built from code points
 * so no raw byte sits in this source (the editor trap).
 */
class TerminalSessionTrackingTest {

    private val esc = Char(0x1b).toString()

    private fun TerminalSession.feed(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feedRemote(b, 0, b.size)
    }

    @Test
    fun linesCommitInsideTheAlternateScreen() {
        val session = TerminalSession("t")
        val committed = mutableListOf<String>()
        session.onCommandSubmitted = { committed += it }

        session.feed("$esc[?1049h") // tmux attaches: shell now lives on alt
        session.trackInput("ls -la")
        session.trackInput("\r")
        assertEquals(listOf("ls -la"), committed)
    }

    @Test
    fun crossingTheAltBoundaryResetsTheDraft() {
        val session = TerminalSession("t")
        val committed = mutableListOf<String>()
        session.onCommandSubmitted = { committed += it }

        session.trackInput("vim notes")   // half a line at the shell…
        session.feed("$esc[?1049h")       // …then vim takes the screen
        assertEquals("", session.commandDraft.value)
        session.feed("$esc[?1049l")       // and gives it back
        session.trackInput("ls")
        session.trackInput("\r")
        assertEquals(listOf("ls"), committed)
    }
}
