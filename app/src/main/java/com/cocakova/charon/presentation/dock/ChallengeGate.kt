package com.cocakova.charon.presentation.dock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cocakova.charon.ssh.PendingChallenge
import com.cocakova.charon.theme.Styx

/**
 * The far shore's own question (keyboard-interactive): a one-time code, a PAM
 * password, whatever the server asks. Masked unless the server says the answer may
 * show. The answer goes to the wire and nowhere else — never stored, never logged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengeGate(pending: PendingChallenge) {
    val c = pending.challenge
    var answer by remember(pending) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(pending) { runCatching { focus.requestFocus() } }
    ModalBottomSheet(
        onDismissRequest = { pending.answer(null) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "the far shore asks",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                c.host + if (c.name.isNotBlank()) " — ${c.name.trim()}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Styx.mist,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (c.instruction.isNotBlank()) {
                Text(
                    c.instruction.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                label = { Text(c.prompt.trim().ifEmpty { "answer" }) },
                singleLine = true,
                visualTransformation = if (c.echo) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (c.echo) KeyboardType.Text else KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { pending.answer(answer) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { pending.answer(null) }, modifier = Modifier.weight(1f)) {
                    Text("turn back")
                }
                Button(onClick = { pending.answer(answer) }, modifier = Modifier.weight(1f)) {
                    Text("answer")
                }
            }
        }
    }
}
