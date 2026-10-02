package com.cocakova.charon.ssh

import java.security.MessageDigest

/**
 * A host key's sigil: its fingerprint walked as OpenSSH's "drunken bishop" over a
 * 16 × 8 field and drawn in braille — two rows of eight characters. The same key
 * always draws the same sigil; a rekeyed host draws a visibly different one, which
 * is easier to notice on a card than a changed run of base64.
 */
object HostSigil {
    private const val W = 16
    private const val H = 8

    fun braille(fingerprint: String): List<String> {
        val bytes = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
        val field = Array(H) { IntArray(W) }
        var x = W / 2
        var y = H / 2
        for (b in bytes) {
            var v = b.toInt() and 0xFF
            repeat(4) {
                x = (x + if (v and 1 != 0) 1 else -1).coerceIn(0, W - 1)
                y = (y + if (v and 2 != 0) 1 else -1).coerceIn(0, H - 1)
                field[y][x]++
                v = v shr 2
            }
        }
        return (0 until H / 4).map { row ->
            buildString {
                for (col in 0 until W / 2) {
                    var bits = 0
                    for (dy in 0 until 4) for (dx in 0 until 2) {
                        if (field[row * 4 + dy][col * 2 + dx] % 2 == 1) bits = bits or DOT[dy][dx]
                    }
                    append((0x2800 + bits).toChar())
                }
            }
        }
    }

    /** Braille dot bits by (row within the cell, column within the cell). */
    private val DOT = arrayOf(intArrayOf(0x01, 0x08), intArrayOf(0x02, 0x10), intArrayOf(0x04, 0x20), intArrayOf(0x40, 0x80))
}
