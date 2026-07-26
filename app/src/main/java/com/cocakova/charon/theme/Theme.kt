package com.cocakova.charon.theme

import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

private val CharonDarkScheme = darkColorScheme(
    primary = NightPalette.water,
    onPrimary = NightPalette.night,
    secondary = NightPalette.coin,
    onSecondary = NightPalette.night,
    tertiary = NightPalette.coin,
    background = NightPalette.night,
    onBackground = NightPalette.bone,
    surface = NightPalette.abyss,
    onSurface = NightPalette.bone,
    surfaceVariant = NightPalette.depth,
    onSurfaceVariant = NightPalette.mist,
    error = NightPalette.ember,
    // The container family too, or sheets and sliders fall back to Material's
    // lavender-tinted baseline neutrals instead of the river's own water.
    primaryContainer = Color(0xFF123A33),
    onPrimaryContainer = NightPalette.water,
    secondaryContainer = Color(0xFF33270E),
    onSecondaryContainer = NightPalette.coin,
    tertiaryContainer = Color(0xFF33270E),
    onTertiaryContainer = NightPalette.coin,
    surfaceContainerLowest = NightPalette.night,
    surfaceContainerLow = NightPalette.abyss,
    surfaceContainer = NightPalette.abyss,
    surfaceContainerHigh = NightPalette.depth,
    surfaceContainerHighest = NightPalette.depth,
    outline = NightPalette.mist,
    outlineVariant = NightPalette.depth,
)

private val CharonLightScheme = lightColorScheme(
    primary = DaybreakPalette.water,
    onPrimary = DaybreakPalette.abyss,
    secondary = DaybreakPalette.coin,
    onSecondary = DaybreakPalette.abyss,
    tertiary = DaybreakPalette.coin,
    background = DaybreakPalette.night,
    onBackground = DaybreakPalette.bone,
    surface = DaybreakPalette.abyss,
    onSurface = DaybreakPalette.bone,
    surfaceVariant = DaybreakPalette.depth,
    onSurfaceVariant = DaybreakPalette.mist,
    error = DaybreakPalette.ember,
    primaryContainer = Color(0xFFCBE5DC),
    onPrimaryContainer = Color(0xFF0A4A3F),
    secondaryContainer = Color(0xFFEBDFC2),
    onSecondaryContainer = Color(0xFF5C430D),
    tertiaryContainer = Color(0xFFEBDFC2),
    onTertiaryContainer = Color(0xFF5C430D),
    surfaceContainerLowest = Color(0xFFFBF9F4),
    surfaceContainerLow = DaybreakPalette.abyss,
    surfaceContainer = Color(0xFFF1EDE3),
    surfaceContainerHigh = Color(0xFFEAE5D8),
    surfaceContainerHighest = DaybreakPalette.depth,
    outline = DaybreakPalette.mist,
    outlineVariant = Color(0xFFCFC8B8),
)

/**
 * Which sky Charon sails under: follow the system, or pin night / daybreak from
 * the helm. Backed by the "sky" pref; [mode] is Compose state so a helm change
 * re-themes the whole app in place.
 */
object Sky {
    const val SYSTEM = "system"
    const val NIGHT = "night"
    const val DAYBREAK = "daybreak"

    var mode by mutableStateOf(SYSTEM)
        private set

    fun load(prefs: SharedPreferences) {
        mode = prefs.getString("sky", SYSTEM) ?: SYSTEM
    }

    fun set(prefs: SharedPreferences, value: String) {
        mode = value
        prefs.edit().putString("sky", value).apply()
    }
}

@Composable
fun CharonTheme(
    content: @Composable () -> Unit,
) {
    val darkTheme = when (Sky.mode) {
        Sky.NIGHT -> true
        Sky.DAYBREAK -> false
        else -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(
        LocalCharonPalette provides if (darkTheme) NightPalette else DaybreakPalette,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) CharonDarkScheme else CharonLightScheme,
            typography = CharonTypography,
            content = content,
        )
    }
}
