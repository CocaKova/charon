package com.cocakova.charon.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.cocakova.charon.theme.CharonMono
import kotlinx.coroutines.delay

/**
 * The braille wheel: wherever a spinner would be boring (DESIGN.md). It turns only
 * while it is on screen — something really is in flight whenever it shows.
 */
@Composable
fun BrailleSpinner(
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    label: String = "working",
) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(FRAME_MS)
            frame = (frame + 1) % FRAMES.length
        }
    }
    Text(
        FRAMES[frame].toString(),
        style = style,
        fontFamily = CharonMono,
        color = color,
        modifier = modifier.semantics { contentDescription = label },
    )
}

private const val FRAMES = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
private const val FRAME_MS = 90L
