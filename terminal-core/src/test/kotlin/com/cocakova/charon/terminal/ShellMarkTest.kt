package com.cocakova.charon.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OSC 133 semantic prompts: the emulator relays marks, invisibly to the grid. */
class ShellMarkTest {

    private fun collect(vararg feeds: String): List<Pair<Char, Int?>> {
        val term = TerminalEmulator(80, 24)
        val marks = mutableListOf<Pair<Char, Int?>>()
        term.onShellMark = { kind, extra -> marks += kind to extra }
        feeds.forEach { term.write(it) }
        return marks
    }

    @Test
    fun `D with an exit code relays both, BEL or ST terminated`() {
        assertEquals(
            listOf('D' to 0, 'D' to 127),
            collect("\u001b]133;D;0\u0007", "\u001b]133;D;127\u001b\\"),
        )
    }

    @Test
    fun `bare marks relay with no extra`() {
        assertEquals(
            listOf('A' to null, 'B' to null, 'C' to null, 'D' to null),
            collect("\u001b]133;A\u0007\u001b]133;B\u0007\u001b]133;C\u0007\u001b]133;D\u0007"),
        )
    }

    @Test
    fun `marks leave no trace on the grid`() {
        val term = TerminalEmulator(80, 24)
        term.onShellMark = { _, _ -> }
        term.write("ok\u001b]133;D;0\u0007!")
        assertEquals("ok!", term.screen.line(0).toText().trimEnd())
    }

    @Test
    fun `a mark split across writes still lands`() {
        val term = TerminalEmulator(80, 24)
        var seen = false
        term.onShellMark = { kind, extra -> seen = kind == 'D' && extra == 3 }
        term.write("\u001b]133;")
        term.write("D;3\u0007")
        assertTrue(seen)
    }

    @Test
    fun `unwired emulator ignores marks quietly`() {
        val term = TerminalEmulator(80, 24)
        term.write("\u001b]133;D;0\u0007still here")
        assertEquals("still here", term.screen.line(0).toText().trimEnd())
    }
}
