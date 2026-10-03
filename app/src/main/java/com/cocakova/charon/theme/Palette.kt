package com.cocakova.charon.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * The river's colors, named by role rather than by hue, so every screen reads the
 * same water whether it's night on the Styx or daybreak over the harbor. UI code
 * reaches these through [Styx] and never names a literal color: that is what lets
 * one palette swap carry the whole app between themes.
 */
@Immutable
data class CharonPalette(
    /** The deepest ground — app background. OLED black at night, warm paper by day. */
    val night: Color,
    /** Surfaces resting on the ground: cards, sheets. */
    val abyss: Color,
    /** Raised surfaces: chips, pills, field backgrounds. */
    val depth: Color,
    /** The water's glow — primary accent. */
    val water: Color,
    /** Dimmed water: quiet accents, idle glows. */
    val waterDeep: Color,
    /** The ferryman's coin — gold accent. */
    val coin: Color,
    /** Primary text. Bone-white at night, ink by day. */
    val bone: Color,
    /** Secondary text. */
    val mist: Color,
    /** Errors, mismatched keys, lost crossings. */
    val ember: Color,
)

/** Night on the Styx — the palette Charon was born with. */
val NightPalette = CharonPalette(
    night = StyxBlack,
    abyss = AbyssInk,
    depth = DepthSlate,
    water = StyxTeal,
    waterDeep = DeepTeal,
    coin = ObolGold,
    bone = BoneWhite,
    mist = MistGrey,
    ember = WarnEmber,
)

/** Daybreak over the harbor — warm paper, deep water, gold ink. Every foreground
 *  role is chosen to hold contrast against the paper that the night palette gets
 *  for free against black. */
val DaybreakPalette = CharonPalette(
    night = Color(0xFFEFEBE2),      // harbor paper
    abyss = Color(0xFFF7F4EC),      // cards float brighter than the ground
    depth = Color(0xFFE2DCCC),      // chips and field backgrounds
    water = Color(0xFF0E6E5E),      // the water runs deep by day
    waterDeep = Color(0xFF5B8E83),
    coin = Color(0xFF8F6914),       // gold ink, not gold light
    bone = Color(0xFF1F2B33),       // ink
    mist = Color(0xFF5A6B74),
    ember = Color(0xFFB5371F),
)

val LocalCharonPalette = staticCompositionLocalOf { NightPalette }

/** Night or daybreak, asked of the ground rather than by identity: a palette the
 *  livery has re-watered is a copy, and still the same sky. */
val CharonPalette.isNight: Boolean get() = night == NightPalette.night

/**
 * The palette re-watered by the livery's accent (its cursor colour): the river,
 * the waterline, the glow and every water-coloured control take the livery's hue,
 * and the coin, the ember and the grounds stay the sky's own. Null keeps the Styx.
 * By day the accent is inked toward the text colour until it reads on paper.
 */
fun CharonPalette.wateredBy(accent: Color?): CharonPalette {
    if (accent == null) return this
    var water: Color = accent
    if (!isNight) {
        var i = 0
        while (contrast(water, night) < 4.5f && i < 10) { water = lerp(water, bone, 0.15f); i++ }
    }
    return copy(water = water, waterDeep = lerp(water, night, if (isNight) 0.45f else 0.35f))
}

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/**
 * The one way UI code names a color. `Styx.water`, `Styx.coin`, `Styx.bone` —
 * each resolves against whichever palette [CharonTheme] is currently providing.
 */
object Styx {
    val palette: CharonPalette
        @Composable @ReadOnlyComposable get() = LocalCharonPalette.current

    val night: Color @Composable @ReadOnlyComposable get() = palette.night
    val abyss: Color @Composable @ReadOnlyComposable get() = palette.abyss
    val depth: Color @Composable @ReadOnlyComposable get() = palette.depth
    val water: Color @Composable @ReadOnlyComposable get() = palette.water
    val waterDeep: Color @Composable @ReadOnlyComposable get() = palette.waterDeep
    val coin: Color @Composable @ReadOnlyComposable get() = palette.coin
    val bone: Color @Composable @ReadOnlyComposable get() = palette.bone
    val mist: Color @Composable @ReadOnlyComposable get() = palette.mist
    val ember: Color @Composable @ReadOnlyComposable get() = palette.ember
}
