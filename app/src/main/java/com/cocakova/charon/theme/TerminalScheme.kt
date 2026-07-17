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

    /**
     * "Ember" — the far shore's fires. Warm ash-and-flame over near-black; gold
     * cursor, reds that glow rather than alarm.
     */
    val EMBER = TerminalScheme(
        name = "Ember",
        fg = 0xE8DCC8,
        bg = 0x070302,
        cursor = 0xD9A441,
        ansi16 = intArrayOf(
            0x140B08, // 0 black — charred wood
            0xE0563E, // 1 red — the ember itself
            0x9BA65A, // 2 green — dry moss
            0xD9A441, // 3 yellow — coallight
            0xA8825F, // 4 blue's seat — driftwood (a warm scheme keeps warm)
            0xC27E6A, // 5 magenta — cooling iron
            0xC9A227, // 6 cyan's seat — brass
            0xD8CCB8, // 7 white — warm bone
            0x5C4A3E, // 8 bright black — smoke
            0xF07A5F, // 9 bright red
            0xB8C47A, // 10 bright green
            0xF0BE64, // 11 bright yellow
            0xC4A47E, // 12 bright blue
            0xE09A84, // 13 bright magenta
            0xE8C24A, // 14 bright cyan
            0xF2E8D8, // 15 bright white
        ),
    )

    /** "Moonwater" — the river under a cold moon. Blues and silver on deep night. */
    val MOONWATER = TerminalScheme(
        name = "Moonwater",
        fg = 0xD8E4F0,
        bg = 0x020408,
        cursor = 0x7FB2E8,
        ansi16 = intArrayOf(
            0x0A1018, // 0 black — deep water
            0xC96A7A, // 1 red — muted, moonlit
            0x6AC9A8, // 2 green — cold weed
            0xC9C06A, // 3 yellow — far lantern
            0x5B93C9, // 4 blue — the current
            0x9A8EC7, // 5 magenta — night iris
            0x6AB8C9, // 6 cyan — moon on ripples
            0xC4D1DE, // 7 white — silver
            0x3E4C5C, // 8 bright black — undertow
            0xE08A9A, // 9 bright red
            0x8AE0C4, // 10 bright green
            0xE0D88A, // 11 bright yellow
            0x7FB2E8, // 12 bright blue
            0xB8AEE8, // 13 bright magenta
            0x8AD4E0, // 14 bright cyan
            0xE4EDF4, // 15 bright white
        ),
    )

    /** "Asphodel" — the meadow of the shades. Violet dusk, soft greys, quiet. */
    val ASPHODEL = TerminalScheme(
        name = "Asphodel",
        fg = 0xE2DCEE,
        bg = 0x060410,
        cursor = 0xB48EC7,
        ansi16 = intArrayOf(
            0x120E1C, // 0 black — dusk soil
            0xD46A8E, // 1 red — dying rose
            0x7EBE8E, // 2 green — pale stems
            0xC7B26A, // 3 yellow — old gold
            0x7E8ED4, // 4 blue — twilight
            0xB48EC7, // 5 magenta — the asphodel
            0x8EB4C7, // 6 cyan — misted water
            0xCEC8DC, // 7 white — petal grey
            0x48405C, // 8 bright black — shade
            0xE88AAC, // 9 bright red
            0x9EDCAC, // 10 bright green
            0xE0CC8A, // 11 bright yellow
            0x9EACE8, // 12 bright blue
            0xCEA6E0, // 13 bright magenta
            0xACD4E0, // 14 bright cyan
            0xF0ECF8, // 15 bright white
        ),
    )

    /**
     * "Daybreak" — the rare crossing made in daylight. A light scheme for reading
     * in the sun: warm paper, ink that never quite reaches black.
     */
    val DAYBREAK = TerminalScheme(
        name = "Daybreak",
        fg = 0x2A3238,
        bg = 0xF2EFE8,
        cursor = 0x1C5F53,
        ansi16 = intArrayOf(
            0x3A424A, // 0 black — ink
            0xB03A2A, // 1 red
            0x3A7E4A, // 2 green
            0x9A7018, // 3 yellow — ochre
            0x2A5E9A, // 4 blue
            0x8E4A9A, // 5 magenta
            0x1C7E70, // 6 cyan — the river by day
            0xE8E2D4, // 7 white — paper shadow
            0x6A7278, // 8 bright black
            0xD4573E, // 9 bright red
            0x4A9E5E, // 10 bright green
            0xB8862A, // 11 bright yellow
            0x3E78BE, // 12 bright blue
            0xAE64BE, // 13 bright magenta
            0x2A9E8E, // 14 bright cyan
            0xFAF8F2, // 15 bright white
        ),
    )

    /** Every livery the helm offers, the default first. */
    val all: List<TerminalScheme> = listOf(STYX, EMBER, MOONWATER, ASPHODEL, DAYBREAK)

    /** Find a livery by name; unknown or absent names sail under Styx colours. */
    fun byName(name: String?): TerminalScheme = all.firstOrNull { it.name == name } ?: STYX
}
