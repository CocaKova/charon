package com.cocakova.charon.presentation.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cocakova.charon.data.db.IdentityEntity
import com.cocakova.charon.data.repository.HostDraft
import com.cocakova.charon.fleet.Hail
import com.cocakova.charon.fleet.HailTarget
import com.cocakova.charon.presentation.components.DropdownChoice
import com.cocakova.charon.presentation.components.ReadonlyDropdownField
import com.cocakova.charon.ssh.MooringOffer
import com.cocakova.charon.theme.CharonMono
import com.cocakova.charon.theme.Styx

/**
 * The hail line on the Dock: type `user@host[:port]` and cast off without mooring
 * anything. A ferry for hire — but the ferryman still meets every host's key.
 */
@Composable
fun HailBar(
    enabled: Boolean,
    onHail: (HailTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val target = remember(text) { Hail.parse(text) }
    val wrong = text.isNotBlank() && target == null
    fun go() {
        val t = target ?: return
        onHail(t)
        text = ""
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.trim() },
            enabled = enabled,
            singleLine = true,
            placeholder = { Text("hail a ferry — user@host:port", color = Styx.mist) },
            supportingText = if (wrong) {
                { Text("user@host, a port after a colon", color = Styx.mist) }
            } else null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { go() }),
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "⛵",
            style = MaterialTheme.typography.titleMedium,
            color = if (target != null) MaterialTheme.colorScheme.background else Styx.mist,
            modifier = Modifier
                .clip(MaterialTheme.shapes.medium)
                .background(if (target != null) Styx.water else MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = enabled && target != null) { go() }
                .semantics { contentDescription = "hail this ferry" }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * The hail itself: who to cross as, and how. Nothing here is saved — the crossing
 * is unmoored, the password lives only as long as the crossing — and the line under
 * the title says plainly that the host's key is still met at the door.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HailSheet(
    target: HailTarget,
    identities: List<IdentityEntity>,
    onDismiss: () -> Unit,
    onCross: (HostDraft) -> Unit,
) {
    var address by rememberSaveable(target) { mutableStateOf(target.address) }
    var identityId by rememberSaveable(target) { mutableStateOf<String?>(null) }
    var password by remember(target) { mutableStateOf("") }
    val parsed = remember(address) { Hail.parse(address) }
    val ready = parsed?.user != null && (identityId != null || password.isNotEmpty())
    val focus = remember { FocusRequester() }
    LaunchedEffect(target) {
        // The address is usually right already (typed or linked); the secret isn't.
        runCatching { focus.requestFocus() }
    }

    fun cross() {
        val t = parsed ?: return
        val user = t.user ?: return
        onCross(
            HostDraft(
                id = null,
                name = "",
                host = t.host,
                port = t.port,
                username = user,
                password = password,
                identityId = identityId,
            ),
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("hail a ferry", style = MaterialTheme.typography.titleMedium, color = Styx.water)
            Text(
                "an unmoored crossing — nothing is saved, and the ferryman still meets the host's key",
                style = MaterialTheme.typography.bodySmall,
                color = Styx.mist,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim() },
                label = { Text("user@host:port") },
                singleLine = true,
                isError = address.isNotBlank() && (parsed == null || parsed.user == null),
                supportingText = when {
                    parsed == null && address.isNotBlank() -> {
                        { Text("user@host, a port after a colon") }
                    }
                    parsed != null && parsed.user == null -> {
                        { Text("who crosses? put a user before the @") }
                    }
                    else -> null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = CharonMono),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            val selected = identities.find { it.id == identityId }
            ReadonlyDropdownField(
                value = selected?.name ?: "password",
                label = "cross with",
                choices = listOf(DropdownChoice("password") { identityId = null }) +
                    identities.map { id ->
                        DropdownChoice(id.name + if (id.biometricGated) "  ·  fingerprint" else "") {
                            identityId = id.id
                        }
                    },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (identityId == null) "password" else "password (if the key is refused)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (ready) cross() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = { cross() }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                Text("cross unmoored")
            }
        }
    }
}

/**
 * An unmoored crossing came home. One quiet line, once: moor it (the edit sheet,
 * filled in) or let it drift. Nothing is saved until you say so.
 */
@Composable
fun MooringOfferRow(
    offer: MooringOffer,
    onMoor: () -> Unit,
    onDrift: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, Styx.waterDeep, MaterialTheme.shapes.medium)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                offer.address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                "came home unmoored",
                style = MaterialTheme.typography.bodySmall,
                color = Styx.mist,
            )
        }
        Text(
            "moor it",
            style = MaterialTheme.typography.labelLarge,
            color = Styx.water,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onMoor)
                .padding(horizontal = 10.dp, vertical = 12.dp),
        )
        Text(
            "✕",
            style = MaterialTheme.typography.labelLarge,
            color = Styx.mist,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDrift)
                .semantics { contentDescription = "let it drift" }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}
