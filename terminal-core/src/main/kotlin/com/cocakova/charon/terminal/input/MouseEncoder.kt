package com.cocakova.charon.terminal.input

/**
 * Encodes pointer events into the byte sequences terminals expect, in either the
 * modern SGR (1006) form or the legacy X10/normal form. Mode-aware: returns null
 * when the active tracking mode does not report that event (e.g. a release under
 * X10 mode 9, or motion under plain 1000), so the caller can simply drop it.
 *
 * Pure functions — the Android layer maps touch onto these. Columns and rows are
 * 0-based here; the wire form is 1-based, converted internally.
 */
object MouseEncoder {

    const val ESC = "\u001B"

    enum class Button { LEFT, MIDDLE, RIGHT, WHEEL_UP, WHEEL_DOWN }
    enum class Event { PRESS, RELEASE, MOVE }

    /** Tracking modes, matching TerminalEmulator.mouseMode. */
    const val OFF = 0
    const val X10 = 9        // press only, no modifiers, no release
    const val NORMAL = 1000  // press + release
    const val BUTTON = 1002  // + motion while a button is held
    const val ANY = 1003     // + all motion

    /**
     * @param mode one of [X10]/[NORMAL]/[BUTTON]/[ANY]
     * @param sgr  the 1006 SGR extended form (preferred; unbounded coordinates)
     * @param button which button, or the wheel direction
     * @param col 0-based column, [row] 0-based row (clamped to the wire's range)
     * @param heldButton for [Event.MOVE], the button being dragged (null = no button)
     * @return the bytes to send, or null if this event isn't reported in [mode]
     */
    fun encode(
        mode: Int,
        sgr: Boolean,
        event: Event,
        button: Button,
        col: Int,
        row: Int,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrl: Boolean = false,
        heldButton: Button? = null,
    ): String? {
        if (mode == OFF) return null
        val wheel = button == Button.WHEEL_UP || button == Button.WHEEL_DOWN

        when (event) {
            Event.PRESS -> {} // every tracking mode reports a press
            Event.RELEASE -> {
                if (wheel) return null            // wheels have no release
                if (mode == X10) return null      // X10 reports press only
            }
            Event.MOVE -> {
                if (mode != BUTTON && mode != ANY) return null
                if (mode == BUTTON && heldButton == null) return null // needs a held button
            }
        }

        val base = when (button) {
            Button.LEFT -> 0
            Button.MIDDLE -> 1
            Button.RIGHT -> 2
            Button.WHEEL_UP -> 64
            Button.WHEEL_DOWN -> 65
        }
        var cb = base
        if (shift) cb += 4
        if (alt) cb += 8
        if (ctrl) cb += 16
        if (event == Event.MOVE) {
            cb += 32
            // Motion carries the held button in its low bits; no button = 3 ("none").
            if (heldButton == null) cb += 3
        }

        val x = col + 1
        val y = row + 1

        return if (sgr) {
            // Release keeps the button code and flips the terminator to lowercase m.
            val terminator = if (event == Event.RELEASE) 'm' else 'M'
            "$ESC[<$cb;$x;$y$terminator"
        } else {
            // Legacy: single-byte fields offset by 32, capped at 255.
            val legacyCb = if (event == Event.RELEASE && !wheel) {
                // Normal-mode release reports button 3, preserving modifier/motion bits.
                (cb and 0b1_1100) or 0b11
            } else {
                cb
            }
            val cbByte = (legacyCb + 32).coerceAtMost(255)
            val xByte = (x + 32).coerceIn(32, 255)
            val yByte = (y + 32).coerceIn(32, 255)
            "$ESC[M${cbByte.toChar()}${xByte.toChar()}${yByte.toChar()}"
        }
    }
}
