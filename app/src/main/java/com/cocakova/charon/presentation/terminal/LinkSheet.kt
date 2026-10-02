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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

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

    /**
     * A web link to the far shore's own loopback (`npm run dev`'s localhost:5173):
     * the host to dial from over there and the port, else null. Such a link means
     * nothing on the phone until a channel carries it across.
     */
    fun loopback(uri: String): Pair<String, Int>? {
        val scheme = scheme(uri) ?: return null
        val host = host(uri) ?: return null
        val target = when (host.lowercase(Locale.ROOT)) {
            "localhost", "0.0.0.0" -> "localhost"
            "[::1]" -> "::1"
            else -> host.takeIf { it.startsWith("127.") && it.count { c -> c == '.' } == 3 } ?: return null
        }
        val authority = uri.substring(scheme.length + 3).takeWhile { it != '/' && it != '?' && it != '#' }
            .substringAfterLast('@')
        val portText = if (authority.startsWith("[")) authority.substringAfter("]", "").removePrefix(":")
        else authority.substringAfter(':', "")
        val port = when {
            portText.isNotEmpty() -> portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            scheme == "https" -> 443
            else -> 80
        }
        return target to port
    }

    /** [uri] pointed at this phone's own localhost:[port], path and query kept. */
    fun onPhone(uri: String, port: Int): String {
        val scheme = scheme(uri) ?: return uri
        val afterSlashes = uri.substring(scheme.length + 3)
        val authorityEnd = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) afterSlashes.length else it }
        return "$scheme://localhost:$port" + afterSlashes.substring(authorityEnd)
    }

    /** The path a `file://` link names (`ls --hyperlink` writes file://host/path), decoded. */
    fun filePath(uri: String): String? {
        if (scheme(uri) != "file") return null
        val rest = uri.substring("file:".length)
        val path = when {
            rest.startsWith("//") -> rest.substring(2).let { a -> a.indexOf('/').let { if (it < 0) return null else a.substring(it) } }
            rest.startsWith("/") -> rest
            else -> return null
        }.substringBefore('#').substringBefore('?')
        return percentDecode(path)?.takeIf { it.startsWith("/") && it.none { c -> c.isISOControl() } }
    }

    private fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val b = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(b); i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8)); i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
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
    /** Carry the far shore's localhost:port to this phone; the phone's port, or why not. Null = no live crossing. */
    onForwardLocal: (suspend (host: String, port: Int) -> Result<Int>)? = null,
    /** Open the hold (SFTP) at a path on the far shore. Null = no crossing to open it on. */
    onOpenHold: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val opens = remember(sighting.uri) { LinkPolicy.opens(sighting.uri) }
    val host = remember(sighting.uri) { LinkPolicy.host(sighting.uri) }
    var note by remember(sighting.uri) { mutableStateOf<String?>(null) }
    val loopback = remember(sighting.uri) { LinkPolicy.loopback(sighting.uri) }
    val holdPath = remember(sighting.uri) { LinkPolicy.filePath(sighting.uri) }
    val scope = rememberCoroutineScope()
    var carrying by remember(sighting.uri) { mutableStateOf(false) }

    fun openOutward(uri: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE),
            )
            onDismiss()
        } catch (_: ActivityNotFoundException) {
            note = "no app aboard can open this"
        } catch (_: SecurityException) {
            note = "no app aboard can open this"
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                if (sighting.linkId == 0) "a passage in plain sight" else "a marked passage",
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
            if (loopback != null && onForwardLocal != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "this localhost is the far shore's — Charon can carry port ${loopback.second} across " +
                        "to this phone and open it here",
                    style = MaterialTheme.typography.bodySmall,
                    color = Styx.mist,
                )
            } else if (holdPath != null && onOpenHold != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "a path on the far shore — open the hold there",
                    style = MaterialTheme.typography.bodySmall,
                    color = Styx.mist,
                )
            } else if (!opens) {
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
                when {
                    loopback != null && onForwardLocal != null -> {
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                carrying = true
                                scope.launch {
                                    onForwardLocal(loopback.first, loopback.second)
                                        .onSuccess { phonePort -> openOutward(LinkPolicy.onPhone(sighting.uri, phonePort)) }
                                        .onFailure { note = it.message ?: "the channel could not be charted" }
                                    carrying = false
                                }
                            },
                            enabled = !carrying,
                        ) { Text(if (carrying) "carrying…" else "carry & open") }
                    }
                    holdPath != null && onOpenHold != null -> {
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onOpenHold(holdPath); onDismiss() }) { Text("open the hold") }
                    }
                    opens -> {
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { openOutward(sighting.uri) }) { Text("open") }
                    }
                }
            }
        }
    }
}
