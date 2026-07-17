package com.cocakova.charon.presentation.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cocakova.charon.data.db.IdentityEntity
import com.cocakova.charon.data.repository.HostDraft
import com.cocakova.charon.presentation.components.DropdownChoice
import com.cocakova.charon.presentation.components.ReadonlyDropdownField
import com.cocakova.charon.theme.MistGrey
import com.cocakova.charon.theme.StyxTeal
import com.cocakova.charon.theme.WarnEmber

/**
 * Add/edit a mooring — the v0.1 quick-connect form, docked. "cross" sails without
 * saving; "save & cross" moors the host first. Passwords are sealed by the vault
 * before storage; editing an existing host with a blank password keeps the old one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditSheet(
    target: EditTarget,
    identities: List<IdentityEntity>,
    harbors: List<String>,
    onDismiss: () -> Unit,
    onCross: (HostDraft) -> Unit,
    onSaveAndCross: (HostDraft) -> Unit,
    onDelete: (String) -> Unit,
) {
    val existing = (target as? EditTarget.Existing)?.host
    var name by rememberSaveable(target) { mutableStateOf(existing?.name ?: "") }
    var host by rememberSaveable(target) { mutableStateOf(existing?.host ?: "") }
    var port by rememberSaveable(target) { mutableStateOf(existing?.port?.toString() ?: "22") }
    var username by rememberSaveable(target) { mutableStateOf(existing?.username ?: "") }
    var password by rememberSaveable(target) { mutableStateOf("") }
    var identityId by rememberSaveable(target) { mutableStateOf(existing?.identityId) }
    var harbor by rememberSaveable(target) { mutableStateOf(existing?.harbor ?: "") }
    var harborMenu by remember { mutableStateOf(false) }
    var colorHex by rememberSaveable(target) { mutableStateOf(existing?.colorHex) }
    var startupCommand by rememberSaveable(target) { mutableStateOf(existing?.startupCommand ?: "") }
    var autoReconnect by rememberSaveable(target) { mutableStateOf(existing?.autoReconnect ?: true) }

    val hasStoredPassword = existing?.passwordSealed != null
    val ready = host.isNotBlank() && username.isNotBlank() &&
        (identityId != null || password.isNotBlank() || hasStoredPassword)

    fun draft() = HostDraft(
        id = existing?.id,
        name = name,
        host = host,
        port = port.toIntOrNull() ?: 22,
        username = username,
        password = password,
        identityId = identityId,
        harbor = harbor,
        colorHex = colorHex,
        startupCommand = startupCommand,
        autoReconnect = autoReconnect,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                if (existing == null) "new crossing" else "edit mooring",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("host") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("user") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            val selectedIdentity = identities.find { it.id == identityId }
            ReadonlyDropdownField(
                value = selectedIdentity?.name ?: "password only",
                label = "identity",
                choices = listOf(
                    DropdownChoice("password only") { identityId = null },
                ) + identities.map { identity ->
                    DropdownChoice(
                        identity.name + if (identity.biometricGated) "  ·  fingerprint" else "",
                    ) { identityId = identity.id }
                },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = {
                    Text(if (hasStoredPassword) "password (stored — blank keeps it)" else "password")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            // Harbor: the collapsible group this mooring docks under. Editable — type
            // a new harbor or pick one already in the fleet.
            val harborMatches = harbors.filter {
                it.isNotBlank() && it != harbor && it.contains(harbor, ignoreCase = true)
            }
            ExposedDropdownMenuBox(
                expanded = harborMenu && harborMatches.isNotEmpty(),
                onExpandedChange = { harborMenu = it },
            ) {
                OutlinedTextField(
                    value = harbor,
                    onValueChange = { harbor = it; harborMenu = true },
                    label = { Text("harbor (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = harborMenu && harborMatches.isNotEmpty(),
                    onDismissRequest = { harborMenu = false },
                ) {
                    harborMatches.forEach { existing ->
                        DropdownMenuItem(
                            text = { Text(existing) },
                            onClick = { harbor = existing; harborMenu = false },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "lantern",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LanternHues.forEach { hue ->
                    LanternSwatch(
                        hue = hue,
                        selected = colorHex == hue,
                        onClick = { colorHex = hue },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = startupCommand,
                onValueChange = { startupCommand = it },
                label = { Text("startup command (optional)") },
                placeholder = { Text("tmux new -As main", color = MistGrey) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "auto-reconnect",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "redial when the crossing drops",
                        style = MaterialTheme.typography.bodySmall,
                        color = MistGrey,
                    )
                }
                Switch(checked = autoReconnect, onCheckedChange = { autoReconnect = it })
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onCross(draft()) },
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                ) { Text("cross") }
                Button(
                    onClick = { onSaveAndCross(draft()) },
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                ) { Text(if (existing == null) "save & cross" else "update & cross") }
            }
            if (existing != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { onDelete(existing.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("release this mooring", color = WarnEmber) }
            }
        }
    }
}

/**
 * A lantern hue choice. The null hue shows a hollow ring (the default dimmed glow);
 * the selected swatch wears a teal halo.
 */
@Composable
private fun LanternSwatch(
    hue: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val fill = lanternColor(hue)
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.border(2.dp, StyxTeal, CircleShape)
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .then(
                    if (hue == null) Modifier.border(1.5.dp, MistGrey, CircleShape)
                    else Modifier.background(fill)
                ),
        )
    }
}
