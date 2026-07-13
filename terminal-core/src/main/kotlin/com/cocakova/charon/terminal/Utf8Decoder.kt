package com.cocakova.charon.terminal

/**
 * Streaming UTF-8 decoder (WHATWG algorithm): bytes in, code points out. Total —
 * malformed input becomes U+FFFD, never an exception. State survives across feed()
 * calls, so multi-byte sequences split across network reads decode correctly.
 */
class Utf8Decoder(private val onCodePoint: (Int) -> Unit) {

    private var needed = 0
    private var seen = 0
    private var codePoint = 0
    private var lowerBoundary = 0x80
    private var upperBoundary = 0xBF

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        for (i in offset until offset + length) feed(bytes[i])
    }

    fun feed(byte: Byte) {
        val b = byte.toInt() and 0xFF
        if (needed == 0) {
            when (b) {
                in 0x00..0x7F -> onCodePoint(b)
                in 0xC2..0xDF -> start(1, b and 0x1F)
                0xE0 -> { start(2, b and 0x0F); lowerBoundary = 0xA0 }
                in 0xE1..0xEC, 0xEE, 0xEF -> start(2, b and 0x0F)
                0xED -> { start(2, b and 0x0F); upperBoundary = 0x9F }
                0xF0 -> { start(3, b and 0x07); lowerBoundary = 0x90 }
                in 0xF1..0xF3 -> start(3, b and 0x07)
                0xF4 -> { start(3, b and 0x07); upperBoundary = 0x8F }
                else -> onCodePoint(REPLACEMENT) // stray continuation or invalid lead
            }
            return
        }
        if (b in lowerBoundary..upperBoundary) {
            lowerBoundary = 0x80
            upperBoundary = 0xBF
            codePoint = (codePoint shl 6) or (b and 0x3F)
            if (++seen == needed) {
                val cp = codePoint
                reset()
                onCodePoint(cp)
            }
        } else {
            // Maximal-subpart replacement: emit one U+FFFD for the broken sequence,
            // then reprocess this byte as a fresh lead byte.
            reset()
            onCodePoint(REPLACEMENT)
            feed(byte)
        }
    }

    /** Call at end-of-stream to flush a dangling partial sequence as U+FFFD. */
    fun finish() {
        if (needed != 0) {
            reset()
            onCodePoint(REPLACEMENT)
        }
    }

    private fun start(bytesNeeded: Int, initial: Int) {
        needed = bytesNeeded
        seen = 0
        codePoint = initial
    }

    private fun reset() {
        needed = 0
        seen = 0
        codePoint = 0
        lowerBoundary = 0x80
        upperBoundary = 0xBF
    }

    companion object {
        const val REPLACEMENT = 0xFFFD
    }
}
