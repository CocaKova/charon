package com.cocakova.charon.presentation.terminal

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cocakova.charon.theme.CharonMono
import com.cocakova.charon.theme.Styx

/**
 * OSC 52 consent, per shore. A yank from tmux or nvim wants to reach the phone's
 * clipboard; the far side chose those bytes, so the first one from each host asks.
 * The answer can stand for that host ("always" / "never"), kept in prefs under the
 * crossing's user@host. Reads of the clipboard are never offered at all.
 */
object ClipboardConsent {
    const val ASK = 0
    const val ALWAYS = 1
    const val NEVER = 2

    private fun key(shore: String) = "osc52:$shore"

    fun of(prefs: SharedPreferences, shore: String): Int = prefs.getInt(key(shore), ASK)

    fun set(prefs: SharedPreferences, shore: String, value: Int) {
        prefs.edit().putInt(key(shore), value).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardSheet(
    shore: String,
    text: String,
    onAllowOnce: () -> Unit,
    onAlways: () -> Unit,
    onRefuse: () -> Unit,
    onNever: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onRefuse) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                "the far shore offers a yank",
                style = MaterialTheme.typography.titleMedium,
                color = Styx.water,
            )
            Text(
                "$shore wants to put ${text.length} character${if (text.length == 1) "" else "s"} on your clipboard",
                style = MaterialTheme.typography.bodySmall,
                color = Styx.mist,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text.take(PREVIEW_CHARS) + if (text.length > PREVIEW_CHARS) "…" else "",
                fontFamily = CharonMono,
                style = MaterialTheme.typography.bodySmall,
                color = Styx.bone,
                modifier = Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onNever) { Text("never from here", color = Styx.mist) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAlways) { Text("always", color = Styx.water) }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onAllowOnce) { Text("take it") }
            }
        }
    }
}

private const val PREVIEW_CHARS = 400
