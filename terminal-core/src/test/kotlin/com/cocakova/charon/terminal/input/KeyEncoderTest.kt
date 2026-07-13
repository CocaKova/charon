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

    @Test
    fun pasteNormalizesNewlinesAndBrackets() {
        assertEquals("a\rb\rc", KeyEncoder.paste("a\r\nb\nc", bracketed = false))
        assertEquals("$E[200~hi\r$E[201~", KeyEncoder.paste("hi\n", bracketed = true))
    }
}
