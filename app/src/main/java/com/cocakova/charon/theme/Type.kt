package com.cocakova.charon.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// Monospace-forward: Charon is a terminal first. JetBrains Mono lands with the
// real renderer; until then the platform mono keeps the identity honest.
val CharonTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 1.5.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 22.sp, letterSpacing = 6.sp),
)
