package com.cocakova.charon.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class Utf8DecoderTest {

    private fun decode(vararg bytes: Int): List<Int> {
        val out = mutableListOf<Int>()
        val dec = Utf8Decoder { out.add(it) }
        dec.feed(bytes.map { it.toByte() }.toByteArray())
        return out
    }

    private fun decodeString(s: String): List<Int> {
        val out = mutableListOf<Int>()
        val dec = Utf8Decoder { out.add(it) }
        dec.feed(s.toByteArray(Charsets.UTF_8))
        return out
    }

    @Test
    fun ascii() {
        assertEquals(listOf(0x68, 0x69), decodeString("hi"))
    }

    @Test
    fun twoByte() {
        assertEquals(listOf(0xE9), decodeString("é"))
    }

    @Test
    fun threeByte() {
        assertEquals(listOf(0x20AC), decodeString("€"))
    }

    @Test
    fun fourByte() {
        assertEquals(listOf(0x1F600), decodeString("😀"))
    }

    @Test
    fun sequenceSplitAcrossFeeds() {
        val out = mutableListOf<Int>()
        val dec = Utf8Decoder { out.add(it) }
        for (b in "😀".toByteArray(Charsets.UTF_8)) dec.feed(b)
        assertEquals(listOf(0x1F600), out)
    }

    @Test
    fun overlongTwoByteRejected() {
        // C0 AF is the classic overlong '/': invalid lead + stray continuation
        assertEquals(listOf(0xFFFD, 0xFFFD), decode(0xC0, 0xAF))
    }

    @Test
    fun overlongThreeByteRejected() {
        // E0 80 80: continuation below E0's raised lower boundary, then two strays
        assertEquals(listOf(0xFFFD, 0xFFFD, 0xFFFD), decode(0xE0, 0x80, 0x80))
    }

    @Test
    fun surrogateEncodingRejected() {
        // ED A0 80 would be U+D800
        assertEquals(listOf(0xFFFD, 0xFFFD, 0xFFFD), decode(0xED, 0xA0, 0x80))
    }

    @Test
    fun beyondMaxCodePointRejected() {
        assertEquals(0x10FFFF, decode(0xF4, 0x8F, 0xBF, 0xBF).single())
        assertEquals(listOf(0xFFFD, 0xFFFD, 0xFFFD, 0xFFFD), decode(0xF4, 0x90, 0x80, 0x80))
    }

    @Test
    fun truncatedSequenceThenAscii() {
        // broken € missing its last byte: one replacement, then the 'A' survives
        assertEquals(listOf(0xFFFD, 0x41), decode(0xE2, 0x82, 0x41))
    }

    @Test
    fun finishFlushesDanglingPartial() {
        val out = mutableListOf<Int>()
        val dec = Utf8Decoder { out.add(it) }
        dec.feed(byteArrayOf(0xE2.toByte(), 0x82.toByte()))
        assertEquals(emptyList(), out)
        dec.finish()
        assertEquals(listOf(0xFFFD), out)
    }

    @Test
    fun mixedStreamRoundTripsLikeJavaDecoder() {
        val s = "ls --color ✓ αβγ 漢字 🚀 done"
        assertEquals(s.codePoints().toArray().toList(), decodeString(s))
    }
}
