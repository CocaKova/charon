package com.cocakova.charon.terminal.input

import com.cocakova.charon.terminal.input.MouseEncoder.Button
import com.cocakova.charon.terminal.input.MouseEncoder.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val ESC = "\u001B"

class MouseEncoderTest {

    private fun sgr(event: Event, button: Button, col: Int, row: Int, held: Button? = null) =
        MouseEncoder.encode(MouseEncoder.NORMAL, sgr = true, event, button, col, row, heldButton = held)

    @Test
    fun `sgr left press and release at origin`() {
        assertEquals("$ESC[<0;1;1M", sgr(Event.PRESS, Button.LEFT, 0, 0))
        assertEquals("$ESC[<0;1;1m", sgr(Event.RELEASE, Button.LEFT, 0, 0))
    }

    @Test
    fun `sgr coordinates are 1-based`() {
        assertEquals("$ESC[<2;6;3M", sgr(Event.PRESS, Button.RIGHT, 5, 2))
    }

    @Test
    fun `sgr wheel has no release`() {
        assertEquals(
            "$ESC[<64;1;1M",
            MouseEncoder.encode(MouseEncoder.NORMAL, true, Event.PRESS, Button.WHEEL_UP, 0, 0),
        )
        assertEquals(
            "$ESC[<65;3;2M",
            MouseEncoder.encode(MouseEncoder.NORMAL, true, Event.PRESS, Button.WHEEL_DOWN, 2, 1),
        )
        assertNull(
            MouseEncoder.encode(MouseEncoder.NORMAL, true, Event.RELEASE, Button.WHEEL_UP, 0, 0),
        )
    }

    @Test
    fun `sgr modifiers add their bits`() {
        // ctrl(16) + shift(4) + left(0) = 20
        assertEquals(
            "$ESC[<20;1;1M",
            MouseEncoder.encode(
                MouseEncoder.NORMAL, true, Event.PRESS, Button.LEFT, 0, 0,
                shift = true, ctrl = true,
            ),
        )
    }

    @Test
    fun `motion needs button-event mode and carries the held button`() {
        // 1000 never reports motion
        assertNull(sgr(Event.MOVE, Button.LEFT, 3, 3, held = Button.LEFT))
        // 1002 reports motion only with a held button: left(0)+motion(32) = 32
        assertEquals(
            "$ESC[<32;5;4M",
            MouseEncoder.encode(
                MouseEncoder.BUTTON, true, Event.MOVE, Button.LEFT, 4, 3, heldButton = Button.LEFT,
            ),
        )
        // 1002 with no held button: dropped
        assertNull(
            MouseEncoder.encode(
                MouseEncoder.BUTTON, true, Event.MOVE, Button.LEFT, 4, 3, heldButton = null,
            ),
        )
        // 1003 reports bare motion: none(3)+motion(32) = 35
        assertEquals(
            "$ESC[<35;5;4M",
            MouseEncoder.encode(
                MouseEncoder.ANY, true, Event.MOVE, Button.LEFT, 4, 3, heldButton = null,
            ),
        )
    }

    @Test
    fun `x10 mode is press-only, no release, no modifiers reported as bits only when set`() {
        assertEquals(
            "$ESC[<0;1;1M",
            MouseEncoder.encode(MouseEncoder.X10, true, Event.PRESS, Button.LEFT, 0, 0),
        )
        assertNull(
            MouseEncoder.encode(MouseEncoder.X10, true, Event.RELEASE, Button.LEFT, 0, 0),
        )
    }

    @Test
    fun `legacy press encodes offset-by-32 bytes`() {
        // left press at 0,0: cb=32(' '), x=33('!'), y=33('!')
        assertEquals(
            "$ESC[M !!",
            MouseEncoder.encode(MouseEncoder.NORMAL, false, Event.PRESS, Button.LEFT, 0, 0),
        )
    }

    @Test
    fun `legacy release reports button 3`() {
        // release button -> 3+32 = 35 ('#')
        assertEquals(
            "$ESC[M#!!",
            MouseEncoder.encode(MouseEncoder.NORMAL, false, Event.RELEASE, Button.LEFT, 0, 0),
        )
    }

    @Test
    fun `off mode reports nothing`() {
        assertNull(MouseEncoder.encode(MouseEncoder.OFF, true, Event.PRESS, Button.LEFT, 0, 0))
    }
}
