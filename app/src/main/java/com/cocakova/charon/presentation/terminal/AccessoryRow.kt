package com.cocakova.charon.presentation.terminal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cocakova.charon.terminal.input.KeyEncoder
import com.cocakova.charon.theme.CharonMono
import com.cocakova.charon.theme.StyxTeal

/**
 * The key row terminals on phones live or die by. v0.1 ships the essentials with a
 * sticky Ctrl (tap = one-shot); long-press variants, mod-lock, and the F-key page
 * arrive in the terminal-excellence milestone (v0.4).
 */
@Composable
fun AccessoryRow(
    ctrlArmed: Boolean,
    onToggleCtrl: () -> Unit,
    onKey: (KeyEncoder.Key) -> Unit,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccessoryKey("esc") { onKey(KeyEncoder.Key.ESCAPE) }
        AccessoryKey("tab") { onKey(KeyEncoder.Key.TAB) }
        AccessoryKey("ctrl", highlighted = ctrlArmed) { onToggleCtrl() }
        AccessoryKey("↑") { onKey(KeyEncoder.Key.UP) }
        AccessoryKey("↓") { onKey(KeyEncoder.Key.DOWN) }
        AccessoryKey("←") { onKey(KeyEncoder.Key.LEFT) }
        AccessoryKey("→") { onKey(KeyEncoder.Key.RIGHT) }
        AccessoryKey("-") { onText("-") }
        AccessoryKey("/") { onText("/") }
        AccessoryKey("|") { onText("|") }
        AccessoryKey("~") { onText("~") }
        AccessoryKey("pgup") { onKey(KeyEncoder.Key.PAGE_UP) }
        AccessoryKey("pgdn") { onKey(KeyEncoder.Key.PAGE_DOWN) }
        AccessoryKey("home") { onKey(KeyEncoder.Key.HOME) }
        AccessoryKey("end") { onKey(KeyEncoder.Key.END) }
    }
}

@Composable
private fun AccessoryKey(
    label: String,
    highlighted: Boolean = false,
    onPress: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // A quick sink-and-spring — the key feels mechanical, not painted.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "keyScale",
    )
    val container by animateColorAsState(
        targetValue = if (highlighted) StyxTeal else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(160),
        label = "keyColor",
    )
    val content by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(160),
        label = "keyContent",
    )

    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = StyxTeal),
                onClick = onPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = CharonMono,
            fontSize = 13.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
            color = content,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
