package com.cocakova.charon.presentation.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cocakova.charon.data.db.SnippetEntity
import com.cocakova.charon.theme.BoneWhite
import com.cocakova.charon.theme.MistGrey
import com.cocakova.charon.theme.ObolGold
import com.cocakova.charon.theme.StyxTeal
import com.cocakova.charon.theme.WarnEmber

/**
 * Rehearsed lines: the snippet bar. Lives in the suggestion strip's slot while the
 * command line is empty — start typing and the smart autofill takes the stage
 * instead; the two never fight. Tap a chip to type the command (Enter stays yours);
 * long-press to edit; `+` rehearses a new one. Termius locks snippets behind
 * Premium; Charon hands them out at the dock.
 */
@Composable
fun SnippetBar(
    snippets: List<SnippetEntity>,
    hostId: String?,
    onType: (String) -> Unit,
    onSave: (SnippetEntity) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<SnippetEntity?>(null) }
    var addingNew by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        snippets.forEach { s ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(
                        onClick = { onType(s.command) },
                        onLongClick = { editing = s },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "❯",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (s.hostId != null) ObolGold else StyxTeal,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    s.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = BoneWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            "+",
            style = MaterialTheme.typography.titleMedium,
            color = StyxTeal,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .clickable { addingNew = true }
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }

    if (editing != null || addingNew) {
        SnippetSheet(
            existing = editing,
            hostId = hostId,
            onSave = {
                onSave(it)
                editing = null
                addingNew = false
            },
            onDelete = { id ->
                onDelete(id)
                editing = null
            },
            onDismiss = { editing = null; addingNew = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnippetSheet(
    existing: SnippetEntity?,
    hostId: String?,
    onSave: (SnippetEntity) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var command by remember { mutableStateOf(existing?.command ?: "") }
    var thisHostOnly by remember { mutableStateOf(existing?.hostId != null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                if (existing == null) "rehearse a line" else "edit the line",
                style = MaterialTheme.typography.titleMedium,
                color = StyxTeal,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("name", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("command", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (hostId != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = thisHostOnly,
                        onCheckedChange = { thisHostOnly = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = ObolGold),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "this host only",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MistGrey,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (existing != null) {
                    TextButton(onClick = { onDelete(existing.id) }) {
                        Text("forget it", color = WarnEmber)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("cancel", color = MistGrey) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val now = System.currentTimeMillis()
                        onSave(
                            SnippetEntity(
                                id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                                name = name.trim(),
                                command = command.trimEnd('\n'),
                                hostId = if (thisHostOnly) hostId else null,
                                sortOrder = existing?.sortOrder ?: 0,
                                createdAt = existing?.createdAt ?: now,
                                lastModified = now,
                            ),
                        )
                    },
                    enabled = name.isNotBlank() && command.isNotBlank(),
                ) { Text("keep") }
            }
        }
    }
}
