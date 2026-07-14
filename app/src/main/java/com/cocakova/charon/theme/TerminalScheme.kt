package com.cocakova.charon.theme

/**
 * A terminal color scheme: default fg/bg, cursor, and the themed ANSI 16.
 * The 256-color cube and grays stay canonical (see terminal-core Palette).
 */
class TerminalScheme(
    val name: String,
    val fg: Int,
    val bg: Int,
    val cursor: Int,
    val ansi16: IntArray,
)

/** Curated schemes. Obol theme packs extend this list (v1.1). */
object TerminalSchemes {
    /**
     * "Styx" — the default. The river palette pulled through the ANSI 16:
     * cyan is the water's glow, yellow the ferryman's coin, red the warning
     * ember. Everything tuned for OLED black.
     */
    val STYX = TerminalScheme(
        name = "Styx",
        fg = 0xE6EDF3,      // BoneWhite
        bg = 0x000000,      // StyxBlack
        cursor = 0x3ECFB2,  // StyxTeal
        ansi16 = intArrayOf(
            0x0A0F14, // 0 black — AbyssInk, a breath above the void
            0xE0563E, // 1 red — WarnEmber
            0x4CBF8B, // 2 green — riverweed
            0xD9A441, // 3 yellow — ObolGold
            0x5B93C9, // 4 blue — moonlit water
            0xB48EC7, // 5 magenta — asphodel
            0x3ECFB2, // 6 cyan — StyxTeal
            0xC7D1D9, // 7 white — worn bone
            0x55636D, // 8 bright black — mist over the water
            0xF07A5F, // 9 bright red
            0x66DDA4, // 10 bright green
            0xF0BE64, // 11 bright yellow
            0x7FB2E8, // 12 bright blue
            0xCEA6E0, // 13 bright magenta
            0x5FE8CC, // 14 bright cyan
            0xE6EDF3, // 15 bright white — BoneWhite
        ),
    )
}
