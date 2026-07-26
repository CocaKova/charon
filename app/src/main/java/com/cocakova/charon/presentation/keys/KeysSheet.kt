package com.cocakova.charon.presentation.keys

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cocakova.charon.data.db.HostEntity
import com.cocakova.charon.data.db.IdentityEntity
import com.cocakova.charon.data.repository.BioCancelled
import com.cocakova.charon.theme.Styx
import kotlinx.coroutines.launch

private enum class KeyForm { NONE, FORGE, IMPORT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysSheet(
    identities: List<IdentityEntity>,
    hosts: List<HostEntity>,
    onDismiss: () -> Unit,
    onForge: suspend (String, Boolean) -> Unit,
    onImport: suspend (String, String, String?, Boolean) -> Unit,
    onRelease: suspend (String) -> Unit,
    onGrant: suspend (HostEntity, IdentityEntity) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var form by rememberSaveable { mutableStateOf(KeyForm.NONE) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var granting by remember { mutableStateOf<IdentityEntity?>(null) }
    var releasing by remember { mutableStateOf<IdentityEntity?>(null) }

    fun launchOperation(success: String, operation: suspend () -> Unit) {
        scope.launch {
            busy = true
            status = null
            try {
                operation()
                form = KeyForm.NONE
                status = success
            } catch (_: BioCancelled) {
                status = null
            } catch (t: Throwable) {
                status = t.message ?: t.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("keys of passage", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Text("Private halves stay sealed here. The courier carries only the public key.",
                style = MaterialTheme.typography.bodySmall, color = Styx.mist)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { form = if (form == KeyForm.FORGE) KeyForm.NONE else KeyForm.FORGE },
                    enabled = !busy, modifier = Modifier.weight(1f),
                ) { Text("forge ed25519") }
                OutlinedButton(
                    onClick = { form = if (form == KeyForm.IMPORT) KeyForm.NONE else KeyForm.IMPORT },
                    enabled = !busy, modifier = Modifier.weight(1f),
                ) { Text("import key") }
            }
            if (form == KeyForm.FORGE) {
                ForgeForm(!busy) { name, bio ->
                    launchOperation("key forged") { onForge(name, bio) }
                }
            }
            if (form == KeyForm.IMPORT) {
                ImportForm(!busy) { name, key, pass, bio ->
                    launchOperation("key imported") { onImport(name, key, pass, bio) }
                }
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("key ") || it == "passage granted")
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()
            if (identities.isEmpty()) {
                Text("No keys yet. Forge one or paste an OpenSSH private key.",
                    color = Styx.mist, modifier = Modifier.padding(vertical = 12.dp))
            }
            identities.forEach { identity ->
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .25f),
                    shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(identity.name, style = MaterialTheme.typography.bodyLarge)
                                Text(identity.keyType + if (identity.biometricGated) "  ·  fingerprint" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { releasing = identity }, enabled = !busy) {
                                Icon(Icons.Outlined.DeleteOutline, "release key", tint = Styx.mist)
                            }
                        }
                        Text(identity.fingerprint, style = MaterialTheme.typography.bodySmall,
                            color = Styx.mist)
                        Row {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(identity.publicLine))
                                status = "public key copied"
                            }, enabled = !busy) {
                                Icon(Icons.Outlined.ContentCopy, null)
                                Text(" copy public")
                            }
                            TextButton(onClick = { granting = identity }, enabled = !busy) {
                                Icon(Icons.Outlined.Send, null)
                                Text(" grant passage")
                            }
                        }
                    }
                }
            }
        }
    }

    granting?.let { identity ->
        AlertDialog(
            onDismissRequest = { granting = null },
            title = { Text("carry “${identity.name}”") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val eligible = hosts.filter { it.passwordSealed != null }
                    if (eligible.isEmpty()) Text("Save a password on a mooring first.")
                    eligible.forEach { host ->
                        OutlinedButton(onClick = {
                            granting = null
                            launchOperation("passage granted") { onGrant(host, identity) }
                        }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                            Text("${host.displayName}  ·  ${host.address}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { granting = null }) { Text("cancel") } },
        )
    }

    releasing?.let { identity ->
        AlertDialog(
            onDismissRequest = { releasing = null },
            title = { Text("release “${identity.name}”?") },
            text = { Text("Hosts using it will return to password authentication.") },
            confirmButton = {
                TextButton(onClick = {
                    releasing = null
                    launchOperation("key released") { onRelease(identity.id) }
                }) { Text("release", color = Styx.ember) }
            },
            dismissButton = { TextButton(onClick = { releasing = null }) { Text("keep") } },
        )
    }
}

@Composable
private fun ForgeForm(enabled: Boolean, onSubmit: (String, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var bio by rememberSaveable { mutableStateOf(true) }
    FormSurface {
        OutlinedTextField(name, { name = it }, label = { Text("key name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        BiometricRow(bio) { bio = it }
        Button({ onSubmit(name, bio) }, enabled = enabled && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text("forge this key") }
    }
}

@Composable
private fun ImportForm(
    enabled: Boolean,
    onSubmit: (String, String, String?, Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var bio by rememberSaveable { mutableStateOf(true) }
    FormSurface {
        OutlinedTextField(name, { name = it }, label = { Text("key name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text("private key") },
            minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("passphrase (if encrypted)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth())
        BiometricRow(bio) { bio = it }
        Button({ onSubmit(name, key, pass.takeIf(String::isNotEmpty), bio) },
            enabled = enabled && name.isNotBlank() && key.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text("seal this key") }
    }
}

@Composable
private fun FormSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f),
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content)
    }
}

@Composable
private fun BiometricRow(checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("fingerprint gate")
            Text("require approval whenever this key crosses",
                style = MaterialTheme.typography.bodySmall, color = Styx.mist)
        }
        Switch(checked, onChecked)
    }
}

