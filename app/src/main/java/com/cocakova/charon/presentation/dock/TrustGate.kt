package com.cocakova.charon.presentation.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cocakova.charon.ssh.PendingTrust
import com.cocakova.charon.ssh.TrustRequest
import com.cocakova.charon.theme.Styx

/**
 * The trust gate. First meeting = a sheet: the ferryman shows his token, the
 * fingerprint is the toll. A changed key = the full red screen: passage refused
 * unless the user explicitly replaces the ledger entry.
 */
@Composable
fun TrustGate(pending: PendingTrust) {
    when (val request = pending.request) {
        is TrustRequest.FirstMeeting -> FerrymanSheet(request, pending)
        is TrustRequest.Changed -> FerrymanChangedScreen(request, pending)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FerrymanSheet(request: TrustRequest.FirstMeeting, pending: PendingTrust) {
    ModalBottomSheet(
        onDismissRequest = { pending.resolve(false) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "meeting the ferryman",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "first crossing to ${request.host}${if (request.port != 22) ":${request.port}" else ""} — " +
                    "verify the toll before you board.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
            FingerprintPlaque(label = request.keyType, fingerprint = request.fingerprint)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { pending.resolve(false) },
                    modifier = Modifier.weight(1f),
                ) { Text("refuse") }
                Button(
                    onClick = { pending.resolve(true) },
                    modifier = Modifier.weight(1f),
                ) { Text("pay the toll") }
            }
        }
    }
}

@Composable
private fun FerrymanChangedScreen(request: TrustRequest.Changed, pending: PendingTrust) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Styx.ember)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            "THE FERRYMAN\nHAS CHANGED",
            style = MaterialTheme.typography.titleLarge,
            color = Styx.night,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "${request.host}${if (request.port != 22) ":${request.port}" else ""} presented a " +
                "different ${request.keyType} key than the one on the ledger. If you did not " +
                "reinstall or rekey this server, someone may be intercepting the crossing.",
            style = MaterialTheme.typography.bodyMedium,
            color = Styx.night,
        )
        Spacer(Modifier.height(24.dp))
        FingerprintPlaque(
            label = "on the ledger",
            fingerprint = request.knownFingerprint,
            dark = true,
        )
        Spacer(Modifier.height(12.dp))
        FingerprintPlaque(
            label = "presented now",
            fingerprint = request.fingerprint,
            dark = true,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { pending.resolve(false) },
            colors = ButtonDefaults.buttonColors(
                containerColor = Styx.night,
                contentColor = Styx.bone,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("refuse passage") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { pending.resolve(true) },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Styx.night),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("I rekeyed this server — replace the ledger entry") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FingerprintPlaque(label: String, fingerprint: String, dark: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Styx.night)
            .border(
                1.dp,
                if (dark) Styx.night else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp),
            )
            .padding(14.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = Styx.mist,
        )
        Text(
            fingerprint,
            style = MaterialTheme.typography.bodyMedium,
            color = Styx.bone,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
