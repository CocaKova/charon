package com.cocakova.charon.presentation.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cocakova.charon.BuildConfig
import com.cocakova.charon.CharonApp
import com.cocakova.charon.data.vault.Reliquary
import com.cocakova.charon.data.vault.ReliquaryCodec
import com.cocakova.charon.presentation.components.ChoicePill
import com.cocakova.charon.theme.MistGrey
import com.cocakova.charon.theme.ObolGold
import com.cocakova.charon.theme.StyxTeal
import com.cocakova.charon.theme.WarnEmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The reliquary: seal the whole fleet into one passphrase-locked `.charon` file,
 * or open one and let its records ashore. Import is two-beat — the seal breaks
 * into a *plan* (spoken plainly, nothing written), and only "let them ashore"
 * carries it out. This is the free answer to the sync subscription.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReliquarySheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CharonApp
    val reliquary = remember { Reliquary(app.db, app.keyVault, BuildConfig.VERSION_NAME) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var sealing by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Sealing.
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var sealedWord by remember { mutableStateOf<String?>(null) }
    var leftBehind by remember { mutableStateOf<List<String>>(emptyList()) }

    // Opening.
    var pickedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var openPass by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Reliquary.Preview?>(null) }
    var landedWord by remember { mutableStateOf<String?>(null) }
    var keysAshore by remember { mutableStateOf<List<String>>(emptyList()) }

    val stow = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            sealedWord = null
            runCatching {
                // Argon2 + AES off the main thread; biometric-gated keys may raise
                // their fingerprint prompts over the sheet along the way.
                val report = withContext(Dispatchers.Default) {
                    reliquary.export(pass.toCharArray())
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(report.bytes)
                    } ?: throw IllegalStateException("the file could not be written")
                }
                report
            }.onSuccess { r ->
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                sealedWord =
                    "sealed — ${r.moorings} moorings · ${r.keys} keys · ${r.ledger} ledger entries · " +
                        "${r.snippets} snippets · ${r.channels} channels"
                leftBehind = r.leftBehind
            }.onFailure { error = it.message ?: "the sealing failed" }
            busy = false
        }
    }

    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            preview = null
            landedWord = null
            error = null
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "the reliquary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = StyxTeal,
            )
            Text(
                "the whole fleet in one sealed file — moorings, keys of passage, " +
                    "the ferryman's ledger, snippets, charted channels",
                style = MaterialTheme.typography.bodySmall,
                color = MistGrey,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePill("seal the fleet", sealing) {
                    sealing = true; error = null
                }
                ChoicePill("open a reliquary", !sealing, selectedColor = ObolGold) {
                    sealing = false; error = null
                }
            }
            Spacer(Modifier.height(14.dp))

            if (sealing) {
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it; sealedWord = null },
                    label = { Text("passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass2,
                    onValueChange = { pass2 = it },
                    label = { Text("passphrase, again") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = pass2.isNotEmpty() && pass2 != pass,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "this passphrase is the only key — there is no recovery on the far shore",
                    style = MaterialTheme.typography.bodySmall,
                    color = MistGrey,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                        stow.launch("charon-fleet-$stamp.charon")
                    },
                    enabled = pass.length >= 4 && pass == pass2 && !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StyxTeal, contentColor = Color.Black,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "sealing…" else "seal & stow") }
                sealedWord?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = ObolGold)
                }
                if (leftBehind.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "left behind (fingerprint refused): ${leftBehind.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarnEmber,
                    )
                }
            } else {
                TextButton(onClick = { pick.launch(arrayOf("*/*")) }) {
                    Text(
                        pickedUri?.lastPathSegment?.substringAfterLast('/')
                            ?.substringAfterLast(':')
                            ?: "choose a reliquary…",
                        color = StyxTeal,
                    )
                }
                if (pickedUri != null && preview == null) {
                    OutlinedTextField(
                        value = openPass,
                        onValueChange = { openPass = it },
                        label = { Text("passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val uri = pickedUri ?: return@Button
                            scope.launch {
                                busy = true
                                error = null
                                runCatching {
                                    val bytes = withContext(Dispatchers.IO) {
                                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                            ?: throw IllegalStateException("the file could not be read")
                                    }
                                    withContext(Dispatchers.Default) {
                                        reliquary.preview(bytes, openPass.toCharArray())
                                    }
                                }.onSuccess { preview = it }
                                    .onFailure {
                                        error = when (it) {
                                            is ReliquaryCodec.ReliquaryException -> it.message
                                            else -> "could not read that file" +
                                                (it.message?.let { m -> " — $m" } ?: "")
                                        }
                                    }
                                busy = false
                            }
                        },
                        enabled = openPass.isNotEmpty() && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "breaking the seal…" else "break the seal") }
                }
                preview?.let { p ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "sealed ${p.exportedAt.take(10)} by Charon ${p.fromVersion}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MistGrey,
                    )
                    Spacer(Modifier.height(6.dp))
                    p.lines.forEach { line ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                line.what,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                buildString {
                                    append("${line.fresh} new")
                                    if (line.refreshed > 0) append(" · ${line.refreshed} refreshed")
                                    if (line.keptOurs > 0) append(" · ${line.keptOurs} kept")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (line.fresh + line.refreshed > 0) StyxTeal else MistGrey,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (landedWord == null) {
                        Button(
                            onClick = {
                                val plan = preview ?: return@Button
                                scope.launch {
                                    busy = true
                                    error = null
                                    runCatching { reliquary.land(plan) }
                                        .onSuccess { r ->
                                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                            landedWord = "${r.landed} records carried ashore"
                                            keysAshore = r.keysAshore
                                        }
                                        .onFailure { error = it.message ?: "the landing failed" }
                                    busy = false
                                }
                            },
                            enabled = p.anythingToLand && !busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ObolGold, contentColor = Color.Black,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    busy -> "landing…"
                                    p.anythingToLand -> "let them ashore"
                                    else -> "nothing new aboard"
                                },
                            )
                        }
                    } else {
                        Text(landedWord!!, style = MaterialTheme.typography.bodyMedium, color = ObolGold)
                        if (keysAshore.isNotEmpty()) {
                            Text(
                                "stayed aboard (fingerprint refused): ${keysAshore.joinToString()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = WarnEmber,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            if (busy) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    color = StyxTeal,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = WarnEmber)
            }
        }
    }
}
