package com.cocakova.charon.terminal

import java.util.zip.Inflater

/**
 * Apparitions — the shades made visible. Images the remote sent, still in their wire
 * form, and the rectangles of grid they were asked to occupy.
 *
 * `terminal-core` stays pure JVM: nothing here decodes pixels. The bytes and their
 * measured size live here, where the protocol logic can be tested headlessly; the
 * renderer turns them into bitmaps.
 */

/** How an [Apparition]'s bytes are laid out. */
enum class ImageFormat {
    /** A container the platform decoder understands (PNG, JPEG, GIF, WebP). */
    ENCODED,

    /** Raw 8-bit RGB, [Apparition.pixelWidth] * [Apparition.pixelHeight] * 3 bytes. */
    RGB,

    /** Raw 8-bit RGBA. */
    RGBA,
}

/** One image, addressable by id for as long as the store keeps it. */
class Apparition(
    val id: Long,
    val format: ImageFormat,
    val bytes: ByteArray,
    val pixelWidth: Int,
    val pixelHeight: Int,
) {
    val byteSize: Int get() = bytes.size
}

/**
 * One drawing of an [Apparition] onto the grid, anchored to the [Line] it starts on
 * so it scrolls with the text and dies when that line is cleared or rolls out of
 * scrollback.
 */
class ApparitionPlacement(
    val imageId: Long,
    val placementId: Long,
    /** Column of the top-left cell, within the anchor line. */
    val startCol: Int,
    val cols: Int,
    val rows: Int,
    /** Source crop in pixels; a width or height of 0 means "to the far edge". */
    val srcX: Int = 0,
    val srcY: Int = 0,
    val srcW: Int = 0,
    val srcH: Int = 0,
    val zIndex: Int = 0,
)

/**
 * Images the remote has transmitted, held under a byte budget. A phone has no room
 * for an unbounded gallery, so the least recently drawn shade sinks first — its
 * placements simply stop drawing, which is exactly what a desktop terminal does when
 * you scroll an image out of its history.
 */
class ApparitionStore(private val maxBytes: Long = DEFAULT_BUDGET) {

    private val images = LinkedHashMap<Long, Apparition>(16, 0.75f, true)

    /** Kitty's client-chosen "image number" → the id we filed it under. */
    private val numbers = HashMap<Long, Long>()

    var byteSize: Long = 0L
        private set

    /** Bumped on every change, so decoded-bitmap caches know to look again. */
    var generation: Long = 0L
        private set

    val count: Int get() = images.size

    fun put(image: Apparition, number: Long = 0L) {
        images.remove(image.id)?.let { byteSize -= it.byteSize }
        images[image.id] = image
        byteSize += image.byteSize
        if (number != 0L) numbers[number] = image.id
        evict()
        generation++
    }

    operator fun get(id: Long): Apparition? = images[id]

    fun idForNumber(number: Long): Long? = numbers[number]

    fun remove(id: Long) {
        images.remove(id)?.let {
            byteSize -= it.byteSize
            numbers.entries.removeAll { e -> e.value == id }
            generation++
        }
    }

    fun clear() {
        if (images.isEmpty()) return
        images.clear()
        numbers.clear()
        byteSize = 0
        generation++
    }

    /** The largest free id, so a transmission without an id gets one of its own. */
    fun nextAnonymousId(): Long {
        var candidate = ANON_BASE
        while (images.containsKey(candidate)) candidate++
        return candidate
    }

    private fun evict() {
        // access-ordered map: the eldest entry is the least recently used
        val it = images.entries.iterator()
        while (byteSize > maxBytes && it.hasNext()) {
            val e = it.next()
            byteSize -= e.value.byteSize
            numbers.entries.removeAll { n -> n.value == e.key }
            it.remove()
        }
    }

    companion object {
        /** Enough for a screen's worth of photos without pressuring a phone's heap. */
        const val DEFAULT_BUDGET = 24L * 1024 * 1024

        /** Ids we hand out ourselves start well above anything a client picks. */
        private const val ANON_BASE = 0x4000_0000L
    }
}

/**
 * Just enough container sniffing to know how big an encoded image is without
 * decoding it. The wire protocols let the sender omit the pixel size and expect the
 * terminal to work it out, and the emulator needs it to reserve grid cells.
 */
object ImageHeader {

    /** Pixel size of an encoded image, or null if the container isn't recognised. */
    fun measure(b: ByteArray): Pair<Int, Int>? = when {
        isPng(b) -> be32(b, 16) to be32(b, 20)
        isGif(b) -> le16(b, 6) to le16(b, 8)
        isJpeg(b) -> measureJpeg(b)
        isWebp(b) -> measureWebp(b)
        else -> null
    }

    private fun isPng(b: ByteArray) = b.size >= 24 &&
        b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
        b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    private fun isGif(b: ByteArray) = b.size >= 10 &&
        b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()

    private fun isJpeg(b: ByteArray) = b.size >= 4 &&
        b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun isWebp(b: ByteArray) = b.size >= 30 &&
        b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
        b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
        b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
        b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    /** Walk the marker chain to the first frame header (SOF0..SOF15, minus DHT/JPG/DAC). */
    private fun measureJpeg(b: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 3 < b.size) {
            if (b[i] != 0xFF.toByte()) { i++; continue }
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }              // fill byte
            if (marker == 0xD8 || marker in 0xD0..0xD9) { i += 2; continue } // no payload
            val len = be16(b, i + 2)
            if (len < 2) return null
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                if (i + 9 >= b.size) return null
                return be16(b, i + 7) to be16(b, i + 5) // width, height (height comes first)
            }
            if (marker == 0xDA) return null                    // scan data: no header found
            i += 2 + len
        }
        return null
    }

    private fun measureWebp(b: ByteArray): Pair<Int, Int>? = when {
        b[12] == 'V'.code.toByte() && b[15] == 'X'.code.toByte() -> // VP8X extended
            (le24(b, 24) + 1) to (le24(b, 27) + 1)
        b[12] == 'V'.code.toByte() && b[15] == ' '.code.toByte() -> // VP8 lossy
            (le16(b, 26) and 0x3FFF) to (le16(b, 28) and 0x3FFF)
        b[12] == 'V'.code.toByte() && b[15] == 'L'.code.toByte() -> { // VP8L lossless
            val bits = le32(b, 21)
            ((bits and 0x3FFF) + 1) to (((bits ushr 14) and 0x3FFF) + 1)
        }
        else -> null
    }

    private fun be16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun be32(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le24(b: ByteArray, i: Int) = le16(b, i) or ((b[i + 2].toInt() and 0xFF) shl 16)
    private fun le32(b: ByteArray, i: Int) = le24(b, i) or ((b[i + 3].toInt() and 0xFF) shl 24)
}

/**
 * Base64 for the wire. Written out rather than taken from `java.util.Base64`, which
 * needs API 26 and Charon sails from 24.
 */
object WireBase64 {

    private val TABLE = IntArray(256) { -1 }.also { t ->
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        alphabet.forEachIndexed { i, c -> t[c.code] = i }
        t['-'.code] = 62 // URL-safe spellings, accepted for good measure
        t['_'.code] = 63
    }

    /** Decodes [s], skipping whitespace and padding. Returns null on a bad character. */
    fun decode(s: CharSequence): ByteArray? {
        val out = ByteArray(s.length / 4 * 3 + 3)
        var n = 0
        var acc = 0
        var bits = 0
        for (ch in s) {
            if (ch == '=' || ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t') continue
            val v = if (ch.code < 256) TABLE[ch.code] else -1
            if (v < 0) return null
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[n++] = ((acc ushr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(n)
    }
}

/** zlib-deflated payloads (`o=z` in the Kitty protocol). Null if the stream is bad. */
internal fun inflateZlib(data: ByteArray, limit: Int): ByteArray? {
    val inflater = Inflater()
    return try {
        inflater.setInput(data)
        val buf = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream(data.size * 3)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            }
            out.write(buf, 0, n)
            if (out.size() > limit) return null
        }
        out.toByteArray()
    } catch (_: Exception) {
        null
    } finally {
        inflater.end()
    }
}
