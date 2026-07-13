package com.cocakova.charon.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cocakova.charon.R

// Monospace-forward: Charon is a terminal first. JetBrains Mono is the terminal
// face AND the UI face — one voice, character-grid to the bone.
val CharonMono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

val CharonTypography = Typography(
    bodyLarge = TextStyle(fontFamily = CharonMono, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = CharonMono, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = CharonMono, fontSize = 11.sp),
    labelLarge = TextStyle(fontFamily = CharonMono, fontSize = 13.sp, letterSpacing = 1.5.sp),
    labelMedium = TextStyle(fontFamily = CharonMono, fontSize = 12.sp),
    titleLarge = TextStyle(fontFamily = CharonMono, fontSize = 22.sp, letterSpacing = 6.sp),
    titleMedium = TextStyle(fontFamily = CharonMono, fontSize = 16.sp, letterSpacing = 2.sp),
)
