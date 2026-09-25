package com.cocakova.charon.terminal

/**
 * The deep colors and the marked passages: the attributes a cell rarely carries,
 * packed into a second Long that lives in a lazily-allocated side array on [Line]
 * (`Line.ext`). [CellAttrs] is full to the brim, and widening every cell for an
 * undercurl or a hyperlink would tax the whole scrollback for what a handful of
 * cells wear — so a line that never meets one never allocates one. Zero means
 * "nothing extra", and that is what every plain cell reads.
 *
 * Layout (LSB..MSB):
 *   bits  0..2   underline style: 0 = plain single (the [CellAttrs.UNDERLINE] flag's
 *                own default, so plain `SGR 4` costs nothing), else [UL_DOUBLE] ..
 *                [UL_DASHED] — the SGR `4:n` numbers themselves
 *   bits  3..4   underline color mode ([CellAttrs.MODE_DEFAULT] = follow the fg)
 *   bits  5..28  underline color payload (0xRRGGBB or palette index)
 *   bits 32..47  hyperlink id into the screen's [HyperlinkTable] (0 = no link)
 *
 * The underline itself stays switched by [CellAttrs.UNDERLINE]; the style bits only
 * say *how* it's drawn. A cell's style is read through [underlineStyle], which needs
 * both words.
 */
object CellExt {
    const val UL_NONE = 0
    const val UL_SINGLE = 1
    const val UL_DOUBLE = 2
    const val UL_CURLY = 3
    const val UL_DOTTED = 4
    const val UL_DASHED = 5

    private const val STYLE_MASK = 0x7L
    private const val UL_MODE_SHIFT = 3
    private const val UL_COLOR_SHIFT = 5
    private const val MODE_MASK = 0x3L
    private const val COLOR_MASK = 0xFFFFFFL
    private const val LINK_SHIFT = 32
    private const val LINK_MASK = 0xFFFFL

    /** The largest link id the field can carry. */
    const val MAX_LINK_ID = 0xFFFF

    /** Everything SGR owns (style + color) — what `SGR 0` wipes; the link survives it. */
    const val UNDERLINE_BITS: Long =
        STYLE_MASK or (MODE_MASK shl UL_MODE_SHIFT) or (COLOR_MASK shl UL_COLOR_SHIFT)

    /** How the cell is underlined: [UL_NONE] unless [CellAttrs.UNDERLINE] is set. */
    fun underlineStyle(attrs: Long, ext: Long): Int {
        if (attrs and CellAttrs.UNDERLINE == 0L) return UL_NONE
        val s = (ext and STYLE_MASK).toInt()
        return if (s == 0) UL_SINGLE else s
    }

    /** Record a `4:n` style; single (and anything unknown) folds to the free 0. */
    fun withUnderlineStyle(ext: Long, style: Int): Long {
        val stored = if (style in UL_DOUBLE..UL_DASHED) style.toLong() else 0L
        return (ext and STYLE_MASK.inv()) or stored
    }

    fun ulMode(ext: Long): Int = ((ext ushr UL_MODE_SHIFT) and MODE_MASK).toInt()
    fun ulColor(ext: Long): Int = ((ext ushr UL_COLOR_SHIFT) and COLOR_MASK).toInt()

    fun withUlRgb(ext: Long, rgb: Int): Long = setUl(ext, CellAttrs.MODE_RGB, rgb.toLong() and COLOR_MASK)
    fun withUlPalette(ext: Long, index: Int): Long = setUl(ext, CellAttrs.MODE_PALETTE, index.toLong() and 0xFFL)
    fun withDefaultUl(ext: Long): Long = setUl(ext, CellAttrs.MODE_DEFAULT, 0L)

    fun linkId(ext: Long): Int = ((ext ushr LINK_SHIFT) and LINK_MASK).toInt()

    fun withLink(ext: Long, id: Int): Long =
        (ext and (LINK_MASK shl LINK_SHIFT).inv()) or ((id.toLong() and LINK_MASK) shl LINK_SHIFT)

    private fun setUl(ext: Long, mode: Int, payload: Long): Long {
        val cleared = ext and (MODE_MASK shl UL_MODE_SHIFT).inv() and (COLOR_MASK shl UL_COLOR_SHIFT).inv()
        return cleared or (mode.toLong() shl UL_MODE_SHIFT) or (payload shl UL_COLOR_SHIFT)
    }
}
