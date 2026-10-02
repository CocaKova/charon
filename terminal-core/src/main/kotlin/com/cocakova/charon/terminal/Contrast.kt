package com.cocakova.charon.terminal

import kotlin.math.pow

/**
 * The minimum-contrast floor: a foreground the program chose that all but vanishes
 * against the ground it sits on (ANSI white on Daybreak's paper is about 1.1 : 1) is
 * walked toward ink — darker on a light ground, lighter on a dark one — just until
 * it reads. Hue is kept; a colour that already reads is left exactly as it was, and
 * text drawn in its own background colour (deliberately hidden) stays hidden.
 */
object Contrast {

    /** WCAG relative luminance of a 0xRRGGBB colour, 0..1. */
    fun luminance(rgb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(rgb shr 16 and 0xFF) + 0.7152 * channel(rgb shr 8 and 0xFF) + 0.0722 * channel(rgb and 0xFF)
    }

    /** WCAG contrast ratio between two colours, 1..21. */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [fg] lifted to at least [min] : 1 against [bg]; unchanged when it already reads. */
    fun floor(fg: Int, bg: Int, min: Double): Int {
        val f = fg and 0xFFFFFF
        val b = bg and 0xFFFFFF
        if (f == b || ratio(f, b) >= min) return f
        val toward = if (luminance(b) > 0.4) 0x000000 else 0xFFFFFF
        var lo = 0.0
        var hi = 1.0
        repeat(12) {
            val mid = (lo + hi) / 2
            if (ratio(mix(f, toward, mid), b) >= min) hi = mid else lo = mid
        }
        return mix(f, toward, hi)
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(shift: Int): Int {
            val x = a shr shift and 0xFF
            val y = b shr shift and 0xFF
            return (x + (y - x) * t).toInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
