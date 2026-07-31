package com.cocakova.charon.presentation.terminal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import com.cocakova.charon.terminal.Apparition
import com.cocakova.charon.terminal.ApparitionPlacement
import com.cocakova.charon.terminal.ImageFormat
import com.cocakova.charon.terminal.TerminalEmulator
import kotlin.math.roundToInt

/**
 * Shades made visible: the app half of the apparition pipeline.
 *
 * `terminal-core` decided which cells an image claims and kept its bytes; here they
 * become pixels. Decoding is cached by image identity — not merely by id, since a
 * sender may re-transmit under an id it already used — and bounded, because a phone
 * has a heap and a scrollback can hold a lot of pictures.
 */
class ApparitionCache(maxBytes: Int = DEFAULT_BITMAP_BUDGET) {

    private class Entry(val source: Apparition, val bitmap: Bitmap)

    private val cache = object : LruCache<Long, Entry>(maxBytes) {
        override fun sizeOf(key: Long, value: Entry): Int = value.bitmap.byteCount
    }

    /** The decoded bitmap for [image], or null if its bytes would not hold shape. */
    fun bitmapFor(image: Apparition): Bitmap? {
        val hit = cache.get(image.id)
        // Identity, not equality: a re-transmitted id must not draw the old picture.
        if (hit != null && hit.source === image) return hit.bitmap
        val decoded = decode(image) ?: return null
        cache.put(image.id, Entry(image, decoded))
        return decoded
    }

    fun clear() = cache.evictAll()

    private fun decode(image: Apparition): Bitmap? = try {
        when (image.format) {
            ImageFormat.ENCODED -> decodeEncoded(image)
            ImageFormat.RGB -> decodeRaw(image, channels = 3)
            ImageFormat.RGBA -> decodeRaw(image, channels = 4)
        }
    } catch (_: Throwable) {
        // A hostile or truncated payload must cost a missing picture, not a session.
        null
    }

    private fun decodeEncoded(image: Apparition): Bitmap? {
        // Sample down on the way in: nobody needs a 12-megapixel bitmap to fill a
        // few hundred pixels of phone screen, and the decode is where the heap goes.
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(image.pixelWidth, image.pixelHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, opts)
    }

    private fun decodeRaw(image: Apparition, channels: Int): Bitmap? {
        val w = image.pixelWidth
        val h = image.pixelHeight
        if (w <= 0 || h <= 0) return null
        if (image.bytes.size.toLong() < w.toLong() * h * channels) return null
        val pixels = IntArray(w * h)
        var src = 0
        for (i in 0 until w * h) {
            val r = image.bytes[src].toInt() and 0xFF
            val g = image.bytes[src + 1].toInt() and 0xFF
            val b = image.bytes[src + 2].toInt() and 0xFF
            val a = if (channels == 4) image.bytes[src + 3].toInt() and 0xFF else 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            src += channels
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun sampleSizeFor(w: Int, h: Int): Int {
        var sample = 1
        var longest = maxOf(w, h)
        while (longest / sample > MAX_DECODED_EDGE) sample *= 2
        return sample
    }

    private companion object {
        const val DEFAULT_BITMAP_BUDGET = 48 * 1024 * 1024
        const val MAX_DECODED_EDGE = 2048
    }
}

/** One apparition found on the glass, with the screen rectangle it occupies. */
class ApparitionHit(
    val image: Apparition,
    val placement: ApparitionPlacement,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * Every apparition visible at [scrollOffset], in draw order (lowest z first).
 *
 * The walk is over visible rows only: an image is anchored to the line its top-left
 * cell sits on, so finding it is the same walk the text renderer already does.
 * Rows above the glass are consulted too — an image can start off-screen and hang
 * down into view — but only as far back as the tallest placement could reach.
 */
fun visibleApparitions(
    term: TerminalEmulator,
    scrollOffset: Int,
    cellWidth: Float,
    cellHeight: Float,
): List<ApparitionHit> {
    var found: MutableList<ApparitionHit>? = null
    val history = term.screen.scrollbackSize
    // How many rows of history sit above the glass — the walk may not step past them.
    val above = minOf(history - scrollOffset.coerceIn(0, history), MAX_OVERHANG)
    for (row in -above until term.rows) {
        val line = term.screen.viewLine(scrollOffset, row)
        val placements = line.apparitions ?: continue
        for (p in placements) {
            val image = term.apparitions.store[p.imageId] ?: continue
            val list = found ?: ArrayList<ApparitionHit>(2).also { found = it }
            list += ApparitionHit(
                image = image,
                placement = p,
                left = p.startCol * cellWidth,
                top = row * cellHeight,
                right = (p.startCol + p.cols) * cellWidth,
                bottom = (row + p.rows) * cellHeight,
            )
        }
    }
    val list = found ?: return emptyList()
    if (list.size > 1) list.sortBy { it.placement.zIndex }
    return list
}

/** The apparition under a point in grid space, topmost first. */
fun apparitionAt(hits: List<ApparitionHit>, x: Float, y: Float): ApparitionHit? =
    hits.lastOrNull { it.contains(x, y) }

/**
 * Draw the shades beneath the text pass. The crop the sender asked for is honoured,
 * and the destination is the exact cell rectangle — a terminal image is grid-aligned
 * or it is nothing.
 */
fun drawApparitions(
    canvas: Canvas,
    hits: List<ApparitionHit>,
    cache: ApparitionCache,
    paint: Paint,
    src: Rect,
    dst: Rect,
) {
    for (hit in hits) {
        val bitmap = cache.bitmapFor(hit.image) ?: continue
        // The placement's crop is in the image's own pixels; the decode may have been
        // sampled down, so scale the crop into the bitmap we actually hold.
        val scaleX = bitmap.width.toFloat() / hit.image.pixelWidth
        val scaleY = bitmap.height.toFloat() / hit.image.pixelHeight
        val p = hit.placement
        val sx = (p.srcX * scaleX).roundToInt().coerceIn(0, bitmap.width)
        val sy = (p.srcY * scaleY).roundToInt().coerceIn(0, bitmap.height)
        val sw = if (p.srcW > 0) (p.srcW * scaleX).roundToInt() else bitmap.width - sx
        val sh = if (p.srcH > 0) (p.srcH * scaleY).roundToInt() else bitmap.height - sy
        if (sw <= 0 || sh <= 0) continue
        src.set(sx, sy, (sx + sw).coerceAtMost(bitmap.width), (sy + sh).coerceAtMost(bitmap.height))
        dst.set(
            hit.left.roundToInt(), hit.top.roundToInt(),
            hit.right.roundToInt(), hit.bottom.roundToInt(),
        )
        canvas.drawBitmap(bitmap, src, dst, paint)
    }
}

/** How far above the glass a placement may start and still hang into view. */
private const val MAX_OVERHANG = 200
