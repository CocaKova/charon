package com.cocakova.charon.presentation.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.cocakova.charon.terminal.input.KeyEncoder

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
            .padding(horizontal = 4.dp, vertical = 4.dp),
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
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .height(36.dp)
            .clickable(onClick = onPress),
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
