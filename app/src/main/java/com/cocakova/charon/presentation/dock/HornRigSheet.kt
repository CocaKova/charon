package com.cocakova.charon.presentation.dock

import com.cocakova.charon.theme.Hulls
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cocakova.charon.data.db.HostEntity
import com.cocakova.charon.presentation.components.BrailleSpinner
import com.cocakova.charon.ssh.HornRig
import com.cocakova.charon.theme.Styx
import kotlinx.coroutines.launch

/**
 * Rigging a mooring for the horn, with the traveller's say-so: ask the far shore
 * which shell it logs in with, then show exactly which file gets exactly which
 * lines — nothing is written until "rig it". A shore already rigged says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HornRigSheet(
    host: HostEntity,
    onErrand: suspend (HostEntity, String) -> Result<String>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var shell by remember { mutableStateOf<HornRig.Shell?>(null) }
    var status by remember { mutableStateOf<String?>("asking the far shore which shell it speaks") }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var done by remember { mutableStateOf<HornRig.Outcome?>(null) }

    LaunchedEffect(host.id) {
        onErrand(host, HornRig.PROBE)
            .onSuccess { out ->
                val s = HornRig.shellOf(out)
                if (s == null) {
                    problem = "this shore logs in with ${out.trim().ifEmpty { "an unnamed shell" }} — " +
                        "rig it by hand from docs/HORN.md"
                } else {
                    shell = s
                }
            }
            .onFailure { problem = "couldn't ask: ${it.message ?: "the errand failed"}" }
        status = null
        busy = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("rig ${host.displayName} for the horn", style = MaterialTheme.typography.titleMedium, color = Styx.water)
            Spacer(Modifier.height(6.dp))
            Text(
                "a rigged shell tells Charon where each prompt starts, when a command ends and how, " +
                    "and where it stands — the horn, the duration whispers, prompt hops and path completion.",
                style = MaterialTheme.typography.bodySmall,
                color = Styx.mist,
            )
            Spacer(Modifier.height(12.dp))
            status?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrailleSpinner(color = Styx.water)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Styx.mist)
                }
            }
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Styx.ember) }
            val s = shell
            if (s != null) {
                Text(
                    "these lines go at the end of ${s.rcFile}:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Styx.bone,
                )
                Spacer(Modifier.height(6.dp))
                SelectionContainer(
                    Modifier
                        .heightIn(max = 260.dp)
                        .clip(Hulls.card)
                        .background(MaterialTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        HornRig.appended(s).trim('\n'),
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 15.sp),
                        color = Styx.water,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "only if the first marker line isn't there already. To take it out, delete from " +
                        "the first marker to the second. New shells hear it; this one needs `source ${s.rcFile}`.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Styx.mist,
                )
                done?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (it) {
                            HornRig.Outcome.RIGGED -> "rigged — the next shell on ${host.displayName} sounds the horn"
                            HornRig.Outcome.ALREADY -> "already rigged — nothing was written"
                            HornRig.Outcome.FAILED -> "the shore refused the errand — nothing changed"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it == HornRig.Outcome.FAILED) Styx.ember else Styx.water,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(if (done != null) "done" else "not now", color = Styx.mist) }
                if (s != null && done == null) {
                    Spacer(Modifier.padding(4.dp))
                    Button(
                        onClick = {
                            busy = true
                            status = "rigging ${s.rcFile}"
                            scope.launch {
                                done = onErrand(host, HornRig.installCommand(s))
                                    .map { HornRig.outcomeOf(it) }
                                    .getOrDefault(HornRig.Outcome.FAILED)
                                status = null
                                busy = false
                            }
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Styx.water, contentColor = Styx.night),
                    ) { Text("rig it") }
                }
            }
        }
    }
}
