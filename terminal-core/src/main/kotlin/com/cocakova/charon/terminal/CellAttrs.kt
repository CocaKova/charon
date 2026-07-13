package com.cocakova.charon.terminal

/**
 * Cell attributes packed into a single Long — one per grid cell, so this layout is
 * the hot path for memory and copies.
 *
 * Layout (LSB..MSB):
 *   bits  0..23  fg color payload (0xRRGGBB when RGB mode, palette index 0..255 when palette mode)
 *   bits 24..25  fg mode: 0 = default, 1 = palette, 2 = rgb
 *   bits 26..49  bg color payload
 *   bits 50..51  bg mode
 *   bits 52..63  style flags
 */
object CellAttrs {
    const val MODE_DEFAULT = 0
    const val MODE_PALETTE = 1
    const val MODE_RGB = 2

    private const val COLOR_MASK = 0xFFFFFFL
    private const val MODE_MASK = 0x3L
    private const val FG_COLOR_SHIFT = 0
    private const val FG_MODE_SHIFT = 24
    private const val BG_COLOR_SHIFT = 26
    private const val BG_MODE_SHIFT = 50

    const val BOLD = 1L shl 52
    const val FAINT = 1L shl 53
    const val ITALIC = 1L shl 54
    const val UNDERLINE = 1L shl 55
    const val BLINK = 1L shl 56
    const val INVERSE = 1L shl 57
    const val INVISIBLE = 1L shl 58
    const val STRIKETHROUGH = 1L shl 59

    /** First cell of a double-width glyph. */
    const val WIDE = 1L shl 60

    /** The spacer cell hiding behind a wide glyph. */
    const val WIDE_CONTINUATION = 1L shl 61

    /** DECSCA guarded-area flag. */
    const val PROTECTED = 1L shl 62

    /** Default fg on default bg, no styles. */
    const val DEFAULT = 0L

    fun withFgRgb(attrs: Long, rgb: Int): Long =
        setColor(attrs, FG_COLOR_SHIFT, FG_MODE_SHIFT, MODE_RGB, rgb.toLong() and COLOR_MASK)

    fun withFgPalette(attrs: Long, index: Int): Long =
        setColor(attrs, FG_COLOR_SHIFT, FG_MODE_SHIFT, MODE_PALETTE, index.toLong() and 0xFFL)

    fun withDefaultFg(attrs: Long): Long =
        setColor(attrs, FG_COLOR_SHIFT, FG_MODE_SHIFT, MODE_DEFAULT, 0L)

    fun withBgRgb(attrs: Long, rgb: Int): Long =
        setColor(attrs, BG_COLOR_SHIFT, BG_MODE_SHIFT, MODE_RGB, rgb.toLong() and COLOR_MASK)

    fun withBgPalette(attrs: Long, index: Int): Long =
        setColor(attrs, BG_COLOR_SHIFT, BG_MODE_SHIFT, MODE_PALETTE, index.toLong() and 0xFFL)

    fun withDefaultBg(attrs: Long): Long =
        setColor(attrs, BG_COLOR_SHIFT, BG_MODE_SHIFT, MODE_DEFAULT, 0L)

    fun fgMode(attrs: Long): Int = ((attrs ushr FG_MODE_SHIFT) and MODE_MASK).toInt()
    fun fgColor(attrs: Long): Int = ((attrs ushr FG_COLOR_SHIFT) and COLOR_MASK).toInt()
    fun bgMode(attrs: Long): Int = ((attrs ushr BG_MODE_SHIFT) and MODE_MASK).toInt()
    fun bgColor(attrs: Long): Int = ((attrs ushr BG_COLOR_SHIFT) and COLOR_MASK).toInt()

    fun hasStyle(attrs: Long, flag: Long): Boolean = attrs and flag != 0L
    fun withStyle(attrs: Long, flag: Long): Long = attrs or flag
    fun withoutStyle(attrs: Long, flag: Long): Long = attrs and flag.inv()

    private fun setColor(attrs: Long, colorShift: Int, modeShift: Int, mode: Int, payload: Long): Long {
        val cleared = attrs and (COLOR_MASK shl colorShift).inv() and (MODE_MASK shl modeShift).inv()
        return cleared or (payload shl colorShift) or (mode.toLong() shl modeShift)
    }
}
