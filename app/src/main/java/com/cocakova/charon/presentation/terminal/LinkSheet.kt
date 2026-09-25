package com.cocakova.charon.presentation.terminal

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cocakova.charon.terminal.TextSelection
import com.cocakova.charon.theme.Styx
import java.util.Locale

/**
 * A marked passage under the finger: the OSC 8 link a tapped cell carries.
 * [linkId] is the table id (so the renderer can wash every cell of the link while
 * the sheet is up), [text] the visible words on the tapped row, [cell] where the
 * finger landed in selection space (the sheet's "select" falls back to the old
 * long-press word select from there).
 */
class LinkSighting(
    val uri: String,
    val linkId: Int,
    val text: String,
    val cell: TextSelection.Cell,
)

/**
 * Which marked passages may leave the terminal. A link is whatever the remote says
 * it is — untrusted by construction — so only schemes a browser or mail app should
 * receive open outward; everything else (file://, ssh://, intent:, javascript:, …)
 * is copy-only. Pure so it's unit-tested without a device.
 */
object LinkPolicy {
    private val OPENABLE = setOf("http", "https", "mailto")

    /** The URI's scheme, lower-cased, or null when there isn't a well-formed one. */
    fun scheme(uri: String): String? {
        val colon = uri.indexOf(':')
        if (colon <= 0) return null
        val s = uri.substring(0, colon)
        if (!s[0].isLetter() || s.any { !(it.isLetterOrDigit() || it == '+' || it == '-' || it == '.') }) return null
        return s.lowercase(Locale.ROOT)
    }

    /** The host an http(s) link leads to (userinfo and port stripped), else null. */
    fun host(uri: String): String? {
        val scheme = scheme(uri) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val rest = uri.substring(scheme.length + 1)
        if (!rest.startsWith("//")) return null
        val authority = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }
        val host = authority.substringAfterLast('@').let { h ->
            if (h.startsWith("[")) h.substringBefore(']') + "]" else h.substringBefore(':')
        }
        return host.takeIf { it.isNotEmpty() && it != "[]" }
    }

    /** True when [uri] may be handed to ACTION_VIEW. */
    fun opens(uri: String): Boolean {
        if (uri.any { it.isISOControl() || it.isWhitespace() }) return false
        val s = scheme(uri) ?: return false
        return when {
            s !in OPENABLE -> false
            s == "mailto" -> uri.length > s.length + 1
            else -> host(uri) != null
        }
    }
}

/**
 * The confirm sheet for a tapped link. Never opens silently: it shows the passage's
 * words, where it really leads (host up front, the full URI verbatim beneath — the
 * visible text is the remote's to choose, the target is what matters), then lets
 * the traveller open it, copy it, or fall back to selecting the words.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkSheet(
    sighting: LinkSighting,
    onSelectWords: (TextSelection.Cell) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val opens = remember(sighting.uri) { LinkPolicy.opens(sighting.uri) }
    val host = remember(sighting.uri) { LinkPolicy.host(sighting.uri) }
    var note by remember(sighting.uri) { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                "a marked passage",
                style = MaterialTheme.typography.titleMedium,
                color = Styx.water,
            )
            if (sighting.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "“${sighting.text.trim()}”",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Styx.mist,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (host != null) "leads to $host" else "leads to",
                style = MaterialTheme.typography.bodyMedium,
                color = Styx.bone,
            )
            Spacer(Modifier.height(4.dp))
            // The whole URI, verbatim and selectable — never truncated to something
            // that could read as a different destination.
            SelectionContainer(
                Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    sighting.uri,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Styx.water,
                )
            }
            if (!opens) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "only web and mail links open from the terminal — this one can be copied",
                    style = MaterialTheme.typography.bodySmall,
                    color = Styx.mist,
                )
            }
            note?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Styx.ember)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSelectWords(sighting.cell) }) {
                    Text("select", color = Styx.mist)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(sighting.uri))
                    onDismiss()
                }) { Text("copy", color = Styx.water) }
                if (opens) {
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(sighting.uri))
                                        .addCategory(Intent.CATEGORY_BROWSABLE),
                                )
                                onDismiss()
                            } catch (_: ActivityNotFoundException) {
                                note = "no app aboard can open this"
                            } catch (_: SecurityException) {
                                note = "no app aboard can open this"
                            }
                        },
                    ) { Text("open") }
                }
            }
        }
    }
}
