package com.cocakova.charon.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** OSC 133 soundings hung on their lines, and the far side's calls and progress. */
class CommandMarkTest {

    private val esc = "\u001b"
    private fun osc(body: String) = "$esc]$body\u0007"

    private fun command(t: TerminalEmulator, cmd: String, output: String, exit: Int) {
        t.write(osc("133;A") + "$ " + osc("133;B") + cmd + "\r\n" + osc("133;C") + output + "\r\n" + osc("133;D;$exit"))
    }

    @Test
    fun `a command's marks hang on its prompt line and its first output line`() {
        val t = TerminalEmulator(40, 10)
        command(t, "make", "building", 2)
        val prompt = t.screen.line(0).promptMark
        assertNotNull(prompt)
        assertSame(prompt, t.screen.line(1).outputMark)
        assertEquals(2, prompt.inputCol)
        assertTrue(prompt.ran && prompt.finished && prompt.failed)
        assertEquals(2, prompt.exitCode)
        assertSame(prompt, t.lastFinished)
        assertNull(t.currentCommand)
    }

    @Test
    fun `an empty Enter finishes nothing worth a whisper`() {
        val t = TerminalEmulator(40, 10)
        t.write(osc("133;A") + "$ " + osc("133;B") + "\r\n" + osc("133;D;0"))
        val m = t.screen.line(0).promptMark!!
        assertNull(m.whisper())
    }

    @Test
    fun `whispers say how it ended and how long it took`() {
        val m = CommandMark(1).apply { ran = true; finished = true; exitCode = 0; durationMs = 134_000 }
        assertEquals("✓ 2m14s", m.whisper())
        m.exitCode = 2; m.durationMs = 8_400
        assertEquals("✕ exit 2 · 8s", m.whisper())
        m.exitCode = 0; m.durationMs = 300
        assertEquals("✓", m.whisper())
        assertEquals("1h05m", CommandMark.duration(3_900_000))
    }

    @Test
    fun `marks ride into scrollback with their lines and die with a clear`() {
        val t = TerminalEmulator(20, 3, 50)
        command(t, "ls", "a", 0)
        val id = t.screen.line(0).promptMark!!.id
        t.write("\r\n\r\n\r\n\r\n")
        assertEquals(id, t.screen.relativeLine(-t.screen.scrollbackSize + 0).promptMark?.id)
        t.write("$esc[2J")
        for (r in 0 until 3) assertNull(t.screen.line(r).promptMark)
    }

    @Test
    fun `on the alternate screen the command is followed but nothing hangs`() {
        val t = TerminalEmulator(20, 5)
        t.write("$esc[?1049h")
        command(t, "ls", "a", 0)
        for (r in 0 until 5) assertNull(t.screen.line(r).promptMark)
        assertNotNull(t.lastFinished)
    }

    @Test
    fun `OSC 9 and 777 are calls, 9 semicolon 4 is progress, other ConEmu forms are left alone`() {
        val t = TerminalEmulator(20, 5)
        val calls = ArrayList<Pair<String?, String>>()
        val progress = ArrayList<Pair<Int, Int?>>()
        t.onNotify = { title, body -> calls += title to body }
        t.onProgress = { s, v -> progress += s to v }
        t.write(osc("9;build finished"))
        t.write(osc("777;notify;tests;42 passed"))
        t.write(osc("9;4;1;37"))
        t.write(osc("9;4;3"))
        t.write(osc("9;4;0;0"))
        t.write(osc("9;1;500"))     // ConEmu sleep: not a call
        t.write(osc("9;4;9;10"))    // no such state
        t.write(osc("777;other;x"))
        assertEquals(listOf<Pair<String?, String>>(null to "build finished", "tests" to "42 passed"), calls)
        assertEquals(listOf(1 to 37, 3 to null, 0 to 0), progress)
    }
}
