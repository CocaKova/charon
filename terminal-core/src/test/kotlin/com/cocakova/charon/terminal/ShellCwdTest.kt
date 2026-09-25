package com.cocakova.charon.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** OSC 7: the shell reports where it stands; the emulator keeps the latest word. */
class ShellCwdTest {

    // ---- the URL itself ------------------------------------------------------------

    @Test
    fun `host and path split at the first slash past the authority`() {
        assertEquals(ShellCwd("spark", "/home/jonny/src"), ShellCwd.parse("file://spark/home/jonny/src"))
    }

    @Test
    fun `percent escapes decode, UTF-8 sequences included, either hex case`() {
        assertEquals("/tmp/a b%c", ShellCwd.parse("file://h/tmp/a%20b%25c")?.path)
        assertEquals("/srv/café", ShellCwd.parse("file://h/srv/caf%C3%A9")?.path)
        assertEquals("/srv/café", ShellCwd.parse("file://h/srv/caf%c3%a9")?.path)
        assertEquals("/it's", ShellCwd.parse("file://h/it%27s")?.path)
    }

    @Test
    fun `raw characters a lenient shell leaves unencoded pass through`() {
        assertEquals("/srv/café dir", ShellCwd.parse("file://h/srv/café dir")?.path)
    }

    @Test
    fun `a missing host is the empty string`() {
        assertEquals(ShellCwd("", "/srv"), ShellCwd.parse("file:///srv"))
        assertEquals(ShellCwd("", "/srv"), ShellCwd.parse("file:/srv"))
    }

    @Test
    fun `the scheme is case-insensitive`() {
        assertEquals(ShellCwd("h", "/x"), ShellCwd.parse("FILE://h/x"))
    }

    @Test
    fun `a trailing slash is dropped but the root stays the root`() {
        assertEquals("/home/j", ShellCwd.parse("file://h/home/j/")?.path)
        assertEquals("/", ShellCwd.parse("file://h/")?.path)
    }

    @Test
    fun `malformed reports are refused, never guessed at`() {
        listOf(
            "",
            "/just/a/path",
            "http://h/x",
            "kitty-shell-cwd://h/x",
            "file://host-without-path",
            "file:relative/path",
            "file://h/a%2",        // truncated escape
            "file://h/a%zz",       // not hex
            "file://h/a%FF",       // not UTF-8
            "file://h/a%C3",       // UTF-8 cut short
            "file://h/a%00b",      // NUL can't be in a path
            "file://h/a%0Ab",      // nor a newline we'd then quote into a probe
            "file://h/" + "x".repeat(5000),
        ).forEach { assertNull("should refuse: ${it.take(40)}", ShellCwd.parse(it)) }
    }

    // ---- through the emulator --------------------------------------------------------

    private fun term() = TerminalEmulator(80, 24)

    @Test
    fun `BEL or ST terminated, the latest report wins`() {
        val t = term()
        t.write("\u001b]7;file://h/one\u0007")
        assertEquals(ShellCwd("h", "/one"), t.cwd)
        t.write("\u001b]7;file://h/two\u001b\\")
        assertEquals(ShellCwd("h", "/two"), t.cwd)
    }

    @Test
    fun `a report split across writes still lands`() {
        val t = term()
        t.write("\u001b]7;file://h/ho")
        t.write("me/j\u0007")
        assertEquals("/home/j", t.cwd?.path)
    }

    @Test
    fun `a malformed report leaves the last good one standing`() {
        val t = term()
        t.write("\u001b]7;file://h/good\u0007\u001b]7;file://h/bad%zz\u0007")
        assertEquals("/good", t.cwd?.path)
    }

    @Test
    fun `an empty report clears it — tmux saying the active pane has no path`() {
        val t = term()
        val seen = mutableListOf<ShellCwd?>()
        t.onCwd = { seen += it }
        t.write("\u001b]7;file://h/x\u0007")
        t.write("\u001b]7;\u0007")
        assertNull(t.cwd)
        assertEquals(listOf(ShellCwd("h", "/x"), null), seen)
    }

    @Test
    fun `the relay fires only for reports it believes`() {
        val t = term()
        val seen = mutableListOf<ShellCwd?>()
        t.onCwd = { seen += it }
        t.write("\u001b]7;nonsense\u0007\u001b]7;file:///srv\u0007")
        assertEquals(listOf<ShellCwd?>(ShellCwd("", "/srv")), seen)
    }

    @Test
    fun `reports leave no trace on the grid`() {
        val t = term()
        t.write("ok\u001b]7;file://h/x\u0007!")
        assertEquals("ok!", t.screen.line(0).toText().trimEnd())
    }

    @Test
    fun `garbage after OSC 7 never throws`() {
        val t = term()
        val rnd = Random(7)
        repeat(2_000) {
            val junk = CharArray(rnd.nextInt(0, 40)) { rnd.nextInt(0x20, 0x250).toChar() }
            t.write("\u001b]7;" + (if (rnd.nextBoolean()) "file://" else "") + String(junk) + "\u0007")
            t.write("\u001b]7;file://h/%" + String(junk) + "\u001b\\")
        }
        t.write("\u001b]7;file://h/after\u0007")
        assertEquals("/after", t.cwd?.path)
    }
}
