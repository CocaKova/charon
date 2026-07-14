package com.cocakova.charon.presentation.dock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cocakova.charon.data.db.IdentityEntity
import com.cocakova.charon.data.repository.HostDraft
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
    var identityMenu by remember { mutableStateOf(false) }

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
            ExposedDropdownMenuBox(
                expanded = identityMenu,
                onExpandedChange = { identityMenu = !identityMenu },
            ) {
                OutlinedTextField(
                    value = selectedIdentity?.name ?: "password only",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("identity") },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = identityMenu,
                    onDismissRequest = { identityMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("password only") },
                        onClick = { identityId = null; identityMenu = false },
                    )
                    identities.forEach { identity ->
                        DropdownMenuItem(
                            text = { Text(identity.name + if (identity.biometricGated) "  ·  fingerprint" else "") },
                            onClick = { identityId = identity.id; identityMenu = false },
                        )
                    }
                }
            }
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
