package com.cocakova.charon.terminal

/**
 * The 256-color palette (xterm defaults): 16 ANSI + 6x6x6 cube + 24 grays.
 * Mutable because OSC 4 can redefine entries; OSC 104 resets.
 */
class Palette {
    private val colors = IntArray(256)

    init {
        reset()
    }

    operator fun get(index: Int): Int = colors[index and 0xFF]

    operator fun set(index: Int, rgb: Int) {
        colors[index and 0xFF] = rgb and 0xFFFFFF
    }

    fun reset() {
        DEFAULTS.copyInto(colors)
    }

    fun resetEntry(index: Int) {
        colors[index and 0xFF] = DEFAULTS[index and 0xFF]
    }

    companion object {
        private val ANSI16 = intArrayOf(
            0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
            0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
        )
        private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

        val DEFAULTS = IntArray(256).also { p ->
            ANSI16.copyInto(p)
            for (i in 16 until 232) {
                val v = i - 16
                val r = CUBE_LEVELS[v / 36]
                val g = CUBE_LEVELS[(v / 6) % 6]
                val b = CUBE_LEVELS[v % 6]
                p[i] = (r shl 16) or (g shl 8) or b
            }
            for (i in 232 until 256) {
                val v = 8 + (i - 232) * 10
                p[i] = (v shl 16) or (v shl 8) or v
            }
        }
    }
}
