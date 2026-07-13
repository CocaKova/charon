package com.cocakova.charon.terminal

import kotlin.random.Random
import kotlin.test.Test

/**
 * Totality fuzz: the decoder+parser pipeline must never throw on ANY byte stream.
 * Seeded, so failures reproduce.
 */
class ParserFuzzTest {

    private object NoopSink : ParserSink {
        override fun print(codePoint: Int) {}
        override fun execute(control: Int) {}
        override fun csiDispatch(params: CsiParams, collected: String, final: Char) {}
        override fun escDispatch(collected: String, final: Char) {}
        override fun oscDispatch(payload: String) {}
        override fun dcsHook(params: CsiParams, collected: String, final: Char) {}
        override fun dcsPut(codePoint: Int) {}
        override fun dcsUnhook() {}
    }

    @Test
    fun randomBytesNeverThrow() {
        val rng = Random(20260713)
        repeat(50) {
            val parser = Parser(NoopSink)
            val decoder = Utf8Decoder { parser.feed(it) }
            decoder.feed(rng.nextBytes(65536))
            decoder.finish()
        }
    }

    @Test
    fun escapeSoupNeverThrows() {
        // Biased toward sequence-introducer bytes so the parser's deep states get hit
        val rng = Random(42)
        val menu = byteArrayOf(
            0x1B, 0x5B, 0x5D, 0x50, 0x07, 0x18, 0x1A,
            0x3B, 0x3A, 0x3F, 0x30, 0x39,
            0x6D, 0x48, 0x9B.toByte(), 0x9C.toByte(), 0x9D.toByte(),
            0x41, 0x7E, 0x20, 0x7F,
        )
        repeat(200) {
            val parser = Parser(NoopSink)
            val decoder = Utf8Decoder { parser.feed(it) }
            val bytes = ByteArray(16384) { menu[rng.nextInt(menu.size)] }
            decoder.feed(bytes)
            decoder.finish()
        }
    }

    @Test
    fun validUnicodeSoupNeverThrows() {
        val rng = Random(7)
        repeat(50) {
            val parser = Parser(NoopSink)
            val sb = StringBuilder()
            repeat(20000) {
                when (rng.nextInt(6)) {
                    0 -> sb.append('\u001B').append('[').append(rng.nextInt(100)).append('m')
                    1 -> sb.appendCodePoint(0x4E00 + rng.nextInt(1000)) // CJK
                    2 -> sb.appendCodePoint(0x1F300 + rng.nextInt(200)) // emoji
                    3 -> sb.append('\u001B').append(']').append(rng.nextInt(10))
                        .append(';').append('t').append('\u0007')
                    else -> sb.append('a' + rng.nextInt(26))
                }
            }
            parser.feed(sb.toString())
        }
    }
}
