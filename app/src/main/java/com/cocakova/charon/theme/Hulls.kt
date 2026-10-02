package com.cocakova.charon.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Hulls: the three shapes everything aboard is cut from, so a host card and the
 * button beside it are the same boat. Chips and rows are small craft, cards and
 * fields and buttons are the ferry, pills are buoys.
 */
object Hulls {
    /** Rows, bar actions, small controls. */
    val chip = RoundedCornerShape(8.dp)

    /** Cards, buttons, fields, panels. */
    val card = RoundedCornerShape(12.dp)

    /** Pills and floating readouts. */
    val pill = RoundedCornerShape(50)
}

/** Material's own shape roles, cut from the same hulls. */
val CharonShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = Hulls.chip,
    medium = Hulls.card,
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
