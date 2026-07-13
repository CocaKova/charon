package com.cocakova.charon.presentation.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cocakova.charon.ssh.TerminalSession
import com.cocakova.charon.terminal.input.KeyEncoder

@Composable
fun TerminalScreen(
    session: TerminalSession,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsState()
    var ctrlArmed by remember { mutableStateOf(false) }
    var inputView by remember { mutableStateOf<TerminalInputView?>(null) }

    // Sticky one-shot Ctrl applies to whatever text comes next (IME or accessory).
    fun sendText(text: String) {
        if (ctrlArmed && text.length == 1) {
            ctrlArmed = false
            KeyEncoder.ctrl(text[0])?.let { session.sendText(it) } ?: session.sendText(text)
        } else {
            session.sendText(text)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            TerminalView(
                session = session,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(session) {
                        detectTapGestures(onTap = { inputView?.showKeyboard() })
                    },
            )
            // Invisible 1dp anchor that owns the IME connection.
            AndroidView(
                factory = { ctx ->
                    TerminalInputView(ctx).apply {
                        onInput = { sendText(it) }
                        appCursorKeys = { session.term.cursorKeysApp }
                    }.also { inputView = it }
                },
                modifier = Modifier.size(1.dp),
            )
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
                session.sendText(KeyEncoder.encode(key, appCursorKeys = session.term.cursorKeysApp))
            },
            onText = { sendText(it) },
        )
    }
}
