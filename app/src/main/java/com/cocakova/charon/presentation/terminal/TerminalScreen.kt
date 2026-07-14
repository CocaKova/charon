package com.cocakova.charon.presentation.terminal

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cocakova.charon.ssh.TerminalSession
import com.cocakova.charon.terminal.input.KeyEncoder
import com.cocakova.charon.theme.MistGrey
import com.cocakova.charon.theme.ObolGold
import com.cocakova.charon.theme.StyxTeal
import com.cocakova.charon.theme.WarnEmber

@Composable
fun TerminalScreen(
    session: TerminalSession,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsState()
    val selection by session.selection.collectAsState()
    val scrollOffset by session.scrollOffset.collectAsState()
    val clipboard = LocalClipboardManager.current
    var ctrlArmed by remember { mutableStateOf(false) }
    var inputView by remember { mutableStateOf<TerminalInputView?>(null) }
    val inputFocus = remember { FocusRequester() }
    val prefs = LocalContext.current.getSharedPreferences("charon", Context.MODE_PRIVATE)
    // Pinch-zoomable font size, persisted; clamped to a legible band.
    var fontSizeSp by remember { mutableFloatStateOf(prefs.getFloat("font_size", 13f)) }
    LaunchedEffect(fontSizeSp) { prefs.edit().putFloat("font_size", fontSizeSp).apply() }
    var inputMode by remember {
        mutableStateOf(
            if (prefs.getString("input_mode", "predictive") == "raw") TerminalInputView.Mode.RAW
            else TerminalInputView.Mode.PREDICTIVE,
        )
    }
    LaunchedEffect(inputMode) {
        inputView?.mode = inputMode
        prefs.edit()
            .putString("input_mode", if (inputMode == TerminalInputView.Mode.RAW) "raw" else "predictive")
            .apply()
    }

    // Sticky one-shot Ctrl applies to whatever text comes next (IME or accessory).
    // Any keystroke also snaps the viewport back to the live bottom.
    fun sendText(text: String) {
        session.scrollToBottom()
        if (ctrlArmed && text.length == 1) {
            ctrlArmed = false
            KeyEncoder.ctrl(text[0])?.let { session.sendText(it) } ?: session.sendText(text)
        } else {
            session.sendText(text)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        SessionStatusStrip(session = session, state = state)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // The IME anchor fills the terminal area (a sane rect for cursor-anchor
            // queries) and sits under the Canvas. Focus is requested through Compose's
            // focus system — raw View.requestFocus() on an interop child loses the
            // focus-owner handoff and every key gets dropped upstream.
            AndroidView(
                factory = { ctx ->
                    TerminalInputView(ctx).apply {
                        onInput = { sendText(it) }
                        appCursorKeys = { session.term.cursorKeysApp }
                        mode = inputMode
                    }.also { inputView = it }
                },
                modifier = Modifier.fillMaxSize().focusRequester(inputFocus),
            )
            TerminalView(
                session = session,
                modifier = Modifier.fillMaxSize(),
                fontSizeSp = fontSizeSp,
                onRequestFocus = {
                    runCatching { inputFocus.requestFocus() }
                    inputView?.showKeyboard()
                },
                onZoom = { zoom ->
                    fontSizeSp = (fontSizeSp * zoom).coerceIn(8f, 32f)
                },
            )

            // Scrolled-back indicator: a pill anchored to the live edge. Tap to
            // return to the bottom (typing does the same).
            if (scrollOffset > 0) {
                Text(
                    "▼ live",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(ObolGold)
                        .clickable { session.scrollToBottom() }
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                )
            }

            // Copy affordance: a teal pill that surfaces while a selection holds.
            if (selection != null) {
                Text(
                    "copy",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(StyxTeal)
                        .clickable {
                            session.copySelection()?.let {
                                clipboard.setText(AnnotatedString(it))
                            }
                            session.clearSelection()
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (state is TerminalSession.State.Disconnected) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "returned to shore",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        (state as TerminalSession.State.Disconnected).reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                    Button(onClick = onDismiss) { Text("back") }
                }
            }
        }
        AccessoryRow(
            ctrlArmed = ctrlArmed,
            onToggleCtrl = { ctrlArmed = !ctrlArmed },
            onKey = { key ->
                session.scrollToBottom()
                session.sendText(KeyEncoder.encode(key, appCursorKeys = session.term.cursorKeysApp))
            },
            onText = { sendText(it) },
            onPaste = {
                session.scrollToBottom()
                clipboard.getText()?.text?.let { session.paste(it) }
            },
            rawInput = inputMode == TerminalInputView.Mode.RAW,
            onToggleInputMode = {
                inputMode = if (inputMode == TerminalInputView.Mode.RAW) {
                    TerminalInputView.Mode.PREDICTIVE
                } else {
                    TerminalInputView.Mode.RAW
                }
            },
        )
    }
}

/**
 * The slim chrome above the grid: state dot, who/where, live grid size. Termux has
 * no frame at all and Termius buries you in toolbar — Charon wears one thin band
 * of instrument panel. Later it becomes the session switcher.
 */
@Composable
private fun SessionStatusStrip(
    session: TerminalSession,
    state: TerminalSession.State,
) {
    val dims by session.dims.collectAsState()

    val dotColor by animateColorAsState(
        targetValue = when (state) {
            is TerminalSession.State.Connected -> StyxTeal
            is TerminalSession.State.Connecting -> ObolGold
            is TerminalSession.State.Disconnected -> WarnEmber
        },
        animationSpec = tween(400),
        label = "stateDot",
    )
    // The dot breathes while the crossing holds — a slow 2.6s swell, not a blinker.
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breatheAlpha",
    )
    val dotAlpha = if (state is TerminalSession.State.Connected) breathe else 1f

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = dotAlpha }
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                session.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${dims.first}×${dims.second}",
                style = MaterialTheme.typography.bodySmall,
                color = MistGrey,
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
