package com.cocakova.charon.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val CharonDarkScheme = darkColorScheme(
    primary = StyxTeal,
    onPrimary = StyxBlack,
    secondary = ObolGold,
    onSecondary = StyxBlack,
    tertiary = ObolGold,
    background = StyxBlack,
    onBackground = BoneWhite,
    surface = AbyssInk,
    onSurface = BoneWhite,
    surfaceVariant = DepthSlate,
    onSurfaceVariant = MistGrey,
    error = WarnEmber,
)

private val CharonLightScheme = lightColorScheme(
    primary = DeepTeal,
    secondary = ObolGold,
    error = WarnEmber,
)

@Composable
fun CharonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) CharonDarkScheme else CharonLightScheme,
        typography = CharonTypography,
        content = content,
    )
}
