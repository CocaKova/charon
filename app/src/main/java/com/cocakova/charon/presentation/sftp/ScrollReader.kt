package com.cocakova.charon.presentation.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cocakova.charon.ssh.RemoteEntry
import com.cocakova.charon.theme.Styx
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * The scroll — a remote text file read where it lies. No download, no picker, no
 * handing off to another app: the hold opens a channel of its own, draws the
 * first [MAX_SCROLL_BYTES] aboard, and shows them. Markdown gets a lantern-light
 * rendering ([MdBlock]); everything else is shown exactly as written. Anything
 * with a NUL byte in it is refused as cargo rather than scrawled on the screen.
 *
 * Read-only by design: the ferryman carries, he doesn't edit. Carrying ashore is
 * one tap away in the bar for anything the reader turns out to be worth keeping.
 */

/** How much of a scroll comes aboard. Past this, the reader says so and stops. */
internal const val MAX_SCROLL_BYTES = 512 * 1024

internal sealed interface ScrollContent {
    data object Loading : ScrollContent
    data class Read(val text: String, val truncated: Boolean, val bytes: Long) : ScrollContent
    data class Refused(val reason: String) : ScrollContent
}

/** Extensions that read as text. Anything else needs the sheet's *read aboard*. */
private val TEXT_EXTENSIONS = setOf(
    "md", "markdown", "mdx", "rst", "adoc", "org", "txt", "text", "log", "note",
    "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg", "conf", "config", "properties",
    "env", "csv", "tsv", "xml", "svg", "html", "htm", "css", "scss", "sass", "less",
    "sh", "bash", "zsh", "fish", "ps1", "bat", "awk", "sed", "vim", "el", "lisp",
    "py", "pyi", "rb", "pl", "pm", "lua", "tcl", "r", "jl", "php",
    "c", "h", "cc", "cpp", "cxx", "hpp", "m", "mm", "cs", "java", "kt", "kts", "gradle",
    "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "svelte", "go", "rs", "swift", "dart",
    "scala", "clj", "ex", "exs", "erl", "hs", "ml", "nim", "zig", "v", "sql", "graphql",
    "proto", "tf", "tfvars", "nix", "cmake", "mk", "make", "pro", "spec", "rules",
    "patch", "diff", "lock", "sum", "service", "timer", "socket", "target", "mount",
    "desktop", "gitignore", "gitattributes", "gitmodules", "editorconfig", "dockerignore",
    "srt", "vtt", "ovpn", "pub", "pem", "crt", "csr", "asc", "sig", "tex", "bib", "ass",
)

/** Bare names with no extension that are text all the same. */
private val TEXT_NAMES = setOf(
    "readme", "license", "licence", "copying", "changelog", "authors", "contributors",
    "notice", "todo", "install", "news", "makefile", "gnumakefile", "dockerfile",
    "containerfile", "vagrantfile", "jenkinsfile", "procfile", "brewfile", "rakefile",
    "gemfile", "podfile", "cargo", "hosts", "fstab", "crontab", "passwd", "group",
    "shadow", "sudoers", "issue", "motd", "environment", "known_hosts", "authorized_keys",
    "config", "hostname", "resolv", "profile", "bashrc", "zshrc", "inputrc", "vimrc",
)

private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdx")

/**
 * Does this entry read as a scroll on a tap? Extension first; then bare names
 * that are text by convention; then dotfiles, which are text almost by
 * definition. Everything else keeps the old behavior — tap opens the cargo
 * sheet — because a tap on `/usr/bin/ls` shouldn't paint the screen with it.
 */
internal fun readsAsText(entry: RemoteEntry): Boolean {
    if (entry.isDir) return false
    val name = entry.name.lowercase(Locale.US)
    val ext = name.substringAfterLast('.', "")
    if (ext.isNotEmpty() && ext != name && ext in TEXT_EXTENSIONS) return true
    if (name.startsWith('.') && !name.drop(1).contains('.')) return true // .bashrc, .gitignore
    return name.substringBeforeLast('.') in TEXT_NAMES || name in TEXT_NAMES
}

internal fun isMarkdown(entry: RemoteEntry): Boolean =
    entry.name.substringAfterLast('.', "").lowercase(Locale.US) in MARKDOWN_EXTENSIONS

/**
 * Draw a scroll off the wire, capped and sniffed. Blocks — call off-main. The
 * stream is *not* closed here; the caller owns it (and the channel under it).
 */
internal fun readScroll(input: InputStream): ScrollContent {
    val sink = ByteArrayOutputStream()
    val chunk = ByteArray(32 * 1024)
    var total = 0
    while (total < MAX_SCROLL_BYTES) {
        val n = input.read(chunk, 0, minOf(chunk.size, MAX_SCROLL_BYTES - total))
        if (n < 0) break
        sink.write(chunk, 0, n)
        total += n
    }
    // One byte past the cap tells us whether the scroll runs on.
    val truncated = total >= MAX_SCROLL_BYTES && input.read() >= 0
    val bytes = sink.toByteArray()
    if (bytes.take(8_000).any { it == 0.toByte() }) {
        return ScrollContent.Refused("this is cargo, not a scroll — carry it ashore to open it")
    }
    var text = String(bytes, Charsets.UTF_8)
    // A truncated read almost certainly cut a line in half; end on a whole one.
    if (truncated) text = text.substringBeforeLast('\n', text)
    return ScrollContent.Read(text, truncated, total.toLong())
}

@Composable
internal fun ScrollReader(
    entry: RemoteEntry,
    content: ScrollContent,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPull: () -> Unit,
) {
    val markdown = isMarkdown(entry)
    var rendered by remember(entry.path) { mutableStateOf(markdown) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction("←", onBack)
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = Styx.water,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (content) {
                        ScrollContent.Loading -> "drawing it aboard…"
                        is ScrollContent.Refused -> "unread"
                        is ScrollContent.Read ->
                            humanBytes(content.bytes) +
                                if (content.truncated) " · first ${MAX_SCROLL_BYTES / 1024}K only" else " · read aboard"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (content is ScrollContent.Read && content.truncated) Styx.coin else Styx.mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (markdown && content is ScrollContent.Read) {
                BarAction(
                    label = if (rendered) "raw" else "read",
                    onClick = { rendered = !rendered },
                    tint = if (rendered) Styx.mist else Styx.coin,
                )
            }
            BarAction("⇣", onPull)
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)

        Box(Modifier.weight(1f)) {
            when (content) {
                ScrollContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Styx.water)
                }
                is ScrollContent.Refused -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        content.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Styx.mist,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.Center) {
                        Button(onClick = onRetry) { Text("try again") }
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = onPull) { Text("carry ashore") }
                    }
                }
                is ScrollContent.Read ->
                    if (rendered) RenderedScroll(content) else RawScroll(content)
            }
        }
    }
}

/** The scroll as written: monospace, wrapped, selectable, lazy by the chunk. */
@Composable
private fun RawScroll(content: ScrollContent.Read) {
    // Whole-file Text would compose a single enormous node; a chunk per screenful
    // keeps scrolling honest while selection still spans what's on screen.
    val chunks = remember(content.text) {
        content.text.split('\n').chunked(60).map { it.joinToString("\n") }
    }
    SelectionContainer {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        ) {
            items(chunks.size, key = { it }) { i ->
                Text(
                    chunks[i],
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = Styx.bone,
                )
            }
            if (content.truncated) item("…") { TruncationNote() }
        }
    }
}

/** The scroll read for meaning: markdown, lightly rendered. */
@Composable
private fun RenderedScroll(content: ScrollContent.Read) {
    val blocks = remember(content.text) { markdownBlocks(content.text) }
    SelectionContainer {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            items(blocks.size) { i -> MdBlockView(blocks[i]) }
            if (content.truncated) item("…") { TruncationNote() }
        }
    }
}

@Composable
private fun MdBlockView(block: MdBlock) {
    val code = Styx.coin
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val link = Styx.water
    when (block) {
        is MdBlock.Heading -> Column(Modifier.padding(top = if (block.level <= 2) 16.dp else 12.dp, bottom = 4.dp)) {
            Text(
                inlineMarkdown(block.text, code, codeBg, link),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = when (block.level) {
                        1 -> 20.sp
                        2 -> 17.sp
                        3 -> 15.sp
                        else -> 13.sp
                    },
                    fontWeight = FontWeight.Bold,
                    lineHeight = 26.sp,
                ),
                color = if (block.level == 1) Styx.water else Styx.bone,
            )
            if (block.level <= 2) {
                Spacer(Modifier.height(5.dp))
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }

        is MdBlock.Paragraph -> Text(
            inlineMarkdown(block.text, code, codeBg, link),
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            color = Styx.bone,
            modifier = Modifier.padding(vertical = 5.dp),
        )

        is MdBlock.Bullet -> Row(
            Modifier
                .fillMaxWidth()
                .padding(start = (block.tier * 14).dp, top = 2.dp, bottom = 2.dp),
        ) {
            Text(
                block.marker,
                style = MaterialTheme.typography.bodyMedium,
                color = Styx.water,
                modifier = Modifier.width(if (block.marker.length > 1) 26.dp else 16.dp),
            )
            Text(
                inlineMarkdown(block.text, code, codeBg, link),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                color = Styx.bone,
            )
        }

        is MdBlock.Quote -> Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = 6.dp),
        ) {
            // The bar runs the height of what it's quoting, however far that wraps.
            Box(Modifier.width(2.dp).fillMaxHeight().background(Styx.waterDeep))
            Text(
                inlineMarkdown(block.text, code, codeBg, link),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic, lineHeight = 20.sp),
                color = Styx.mist,
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        is MdBlock.Pre -> Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp)
                .clip(RoundedCornerShape(8.dp))
                // Same ground as an inline code span, so code reads as code at both
                // scales — and it holds its edge against the page in either theme.
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            block.lang?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Styx.mist)
                Spacer(Modifier.height(4.dp))
            }
            // Code keeps its own lines: no wrapping, scroll it sideways instead.
            Text(
                block.body,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = Styx.bone,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }

        MdBlock.Rule -> HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun TruncationNote() {
    Text(
        "— the scroll runs on past here; only the first ${MAX_SCROLL_BYTES / 1024}K came aboard. " +
            "Carry it ashore (⇣) for the whole of it.",
        style = MaterialTheme.typography.bodySmall,
        color = Styx.coin,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
    )
}
