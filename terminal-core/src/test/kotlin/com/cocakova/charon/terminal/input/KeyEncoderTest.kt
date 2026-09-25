package com.cocakova.charon.terminal.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyEncoderTest {
    private val E = "\u001B"

    @Test
    fun arrowsFollowDecckm() {
        assertEquals("$E[A", KeyEncoder.encode(KeyEncoder.Key.UP))
        assertEquals("${E}OA", KeyEncoder.encode(KeyEncoder.Key.UP, appCursorKeys = true))
        assertEquals("$E[D", KeyEncoder.encode(KeyEncoder.Key.LEFT))
    }

    @Test
    fun functionAndNavKeys() {
        assertEquals("${E}OP", KeyEncoder.encode(KeyEncoder.Key.F1))
        assertEquals("$E[15~", KeyEncoder.encode(KeyEncoder.Key.F5))
        assertEquals("$E[24~", KeyEncoder.encode(KeyEncoder.Key.F12))
        assertEquals("$E[3~", KeyEncoder.encode(KeyEncoder.Key.DELETE))
        assertEquals("$E[6~", KeyEncoder.encode(KeyEncoder.Key.PAGE_DOWN))
    }

    @Test
    fun tabAndBackTab() {
        assertEquals("\t", KeyEncoder.encode(KeyEncoder.Key.TAB))
        assertEquals("$E[Z", KeyEncoder.encode(KeyEncoder.Key.BACK_TAB))
    }

    @Test
    fun backspaceDefaultsToDel() {
        assertEquals("\u007F", KeyEncoder.encode(KeyEncoder.Key.BACKSPACE))
        assertEquals("\u0008", KeyEncoder.encode(KeyEncoder.Key.BACKSPACE, backspaceSendsDel = false))
    }

    @Test
    fun ctrlMappings() {
        assertEquals("\u0003", KeyEncoder.ctrl('c'))
        assertEquals("\u0003", KeyEncoder.ctrl('C'))
        assertEquals("\u0000", KeyEncoder.ctrl(' '))
        assertEquals("\u001B", KeyEncoder.ctrl('['))
        assertEquals("\u001F", KeyEncoder.ctrl('_'))
        assertNull(KeyEncoder.ctrl('1'))
    }

    @Test
    fun altPrefixesEscape() {
        assertEquals("${E}f", KeyEncoder.alt("f"))
    }

    /**
     * Regression pin: the legacy table, every key in both DECCKM states, byte for
     * byte. The Kitty keyboard protocol lives beside this path, never inside it —
     * with no flags pushed, nothing a remote sees may change.
     */
    @Test
    fun legacyTableIsPinned() {
        val normal = mapOf(
            KeyEncoder.Key.UP to "$E[A", KeyEncoder.Key.DOWN to "$E[B",
            KeyEncoder.Key.RIGHT to "$E[C", KeyEncoder.Key.LEFT to "$E[D",
            KeyEncoder.Key.HOME to "$E[H", KeyEncoder.Key.END to "$E[F",
            KeyEncoder.Key.INSERT to "$E[2~", KeyEncoder.Key.DELETE to "$E[3~",
            KeyEncoder.Key.PAGE_UP to "$E[5~", KeyEncoder.Key.PAGE_DOWN to "$E[6~",
            KeyEncoder.Key.F1 to "${E}OP", KeyEncoder.Key.F2 to "${E}OQ",
            KeyEncoder.Key.F3 to "${E}OR", KeyEncoder.Key.F4 to "${E}OS",
            KeyEncoder.Key.F5 to "$E[15~", KeyEncoder.Key.F6 to "$E[17~",
            KeyEncoder.Key.F7 to "$E[18~", KeyEncoder.Key.F8 to "$E[19~",
            KeyEncoder.Key.F9 to "$E[20~", KeyEncoder.Key.F10 to "$E[21~",
            KeyEncoder.Key.F11 to "$E[23~", KeyEncoder.Key.F12 to "$E[24~",
            KeyEncoder.Key.ENTER to "\r", KeyEncoder.Key.TAB to "\t",
            KeyEncoder.Key.BACK_TAB to "$E[Z", KeyEncoder.Key.BACKSPACE to "\u007F",
            KeyEncoder.Key.ESCAPE to E,
        )
        assertEquals(KeyEncoder.Key.entries.toSet(), normal.keys)
        for ((key, bytes) in normal) assertEquals(bytes, KeyEncoder.encode(key), "$key")
        val app = normal + mapOf(
            KeyEncoder.Key.UP to "${E}OA", KeyEncoder.Key.DOWN to "${E}OB",
            KeyEncoder.Key.RIGHT to "${E}OC", KeyEncoder.Key.LEFT to "${E}OD",
            KeyEncoder.Key.HOME to "${E}OH", KeyEncoder.Key.END to "${E}OF",
        )
        for ((key, bytes) in app) assertEquals(bytes, KeyEncoder.encode(key, appCursorKeys = true), "$key app")
        // The chord helpers the hardware and sticky paths use in legacy.
        for (c in 'a'..'z') assertEquals((c - 'a' + 1).toChar().toString(), KeyEncoder.ctrl(c))
        assertEquals("\u001C", KeyEncoder.ctrl('\\'))
        assertEquals("\u001D", KeyEncoder.ctrl(']'))
        assertEquals("\u001E", KeyEncoder.ctrl('^'))
        assertEquals("\u001F", KeyEncoder.ctrl('/'))
        assertEquals("\u007F", KeyEncoder.ctrl('?'))
        assertEquals("\u0000", KeyEncoder.ctrl('@'))
        assertEquals("$E$E[A", KeyEncoder.alt(KeyEncoder.encode(KeyEncoder.Key.UP)))
    }

    @Test
    fun pasteNormalizesNewlinesAndBrackets() {
        assertEquals("a\rb\rc", KeyEncoder.paste("a\r\nb\nc", bracketed = false))
        assertEquals("$E[200~hi\r$E[201~", KeyEncoder.paste("hi\n", bracketed = true))
    }
}
