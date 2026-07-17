package com.cocakova.charon.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cocakova.charon.theme.CharonMono
import com.cocakova.charon.theme.MistGrey
import com.cocakova.charon.theme.StyxTeal

/**
 * The app's one small choice pill — mode toggles at the helm (abc/raw), the fleet
 * sheet's two waters, and whatever chooses next. One visual language: mono label,
 * the selected pill filled with its colour (teal by default, gold for the wilder
 * choice), black text on the fill, mist on the rest.
 */
@Composable
fun ChoicePill(
    label: String,
    selected: Boolean,
    selectedColor: Color = StyxTeal,
    onClick: () -> Unit,
) {
    Text(
        label,
        fontFamily = CharonMono,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MistGrey,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) selectedColor
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
