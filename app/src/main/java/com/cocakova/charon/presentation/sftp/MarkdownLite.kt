package com.cocakova.charon.presentation.sftp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Markdown by lantern-light: enough of the language to make a README legible on a
 * phone, none of the machinery. Block shape is read once ([markdownBlocks]);
 * inline marks — **bold**, *em*, `code`, [links](…), ~~struck~~ — are woven per
 * block against the live palette ([inlineMarkdown]), since colors are a theme
 * question and parsing isn't.
 *
 * Deliberately a subset: no HTML, no reference links, no footnotes. Anything it
 * can't read it shows as itself, the raw line — the honest failure mode for a
 * reader that never rewrites the file it's reading.
 */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    /** [marker] is already rendered ("•", "3.", "☐", "☑"); [tier] is nesting depth. */
    data class Bullet(val tier: Int, val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    /** Preformatted: fenced or indented code, and tables, which are ASCII art anyway. */
    data class Pre(val lang: String?, val body: String) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
private val BULLET = Regex("""^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$""")
private val RULE = Regex("""^ {0,3}([-*_])\s*(\1\s*){2,}$""")
private val SETEXT = Regex("""^ {0,3}(=+|-+)\s*$""")
private val FENCE = Regex("""^\s*(```|~~~)\s*(\S*)""")
private val TASK = Regex("""^\[([ xX])]\s+(.*)$""")

/** Split [text] into blocks. Cheap enough to run on a whole scroll at open. */
internal fun markdownBlocks(text: String): List<MdBlock> {
    val lines = text.split('\n').map { it.trimEnd('\r') }
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()

    fun flush() {
        if (para.isNotEmpty()) {
            out += MdBlock.Paragraph(para.toString())
            para.setLength(0)
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = FENCE.find(line)
        val heading = HEADING.find(line)
        val bullet = BULLET.find(line)
        when {
            fence != null && fence.groupValues[1].length == 3 -> {
                flush()
                val marker = fence.groupValues[1]
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) {
                    body.appendLine(lines[i])
                    i++
                }
                i++ // step over the closing fence (or past the end — same thing)
                out += MdBlock.Pre(fence.groupValues[2].ifBlank { null }, body.toString().trimEnd('\n'))
            }
            line.isBlank() -> { flush(); i++ }
            // An underline under a live paragraph is a heading; under nothing, a rule.
            SETEXT.matches(line) && para.isNotEmpty() -> {
                val level = if (line.trimStart().startsWith('=')) 1 else 2
                out += MdBlock.Heading(level, para.toString())
                para.setLength(0)
                i++
            }
            RULE.matches(line) -> { flush(); out += MdBlock.Rule; i++ }
            heading != null -> {
                flush()
                out += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
                i++
            }
            bullet != null -> {
                flush()
                val indent = bullet.groupValues[1].replace("\t", "  ").length
                val raw = bullet.groupValues[2]
                val rest = bullet.groupValues[3]
                val task = TASK.find(rest)
                out += if (task != null) {
                    MdBlock.Bullet(
                        tier = indent / 2,
                        marker = if (task.groupValues[1].isBlank()) "☐" else "☑",
                        text = task.groupValues[2],
                    )
                } else {
                    MdBlock.Bullet(
                        tier = indent / 2,
                        marker = if (raw.length > 1) raw else "•",
                        text = rest,
                    )
                }
                i++
            }
            line.trimStart().startsWith(">") -> {
                flush()
                val body = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    body.appendLine(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                out += MdBlock.Quote(body.toString().trim('\n'))
            }
            // A table is already aligned by hand — keep it preformatted rather than
            // pretend to lay out columns on a phone.
            line.trimStart().startsWith("|") -> {
                flush()
                val body = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    body.appendLine(lines[i].trim())
                    i++
                }
                out += MdBlock.Pre(null, body.toString().trimEnd('\n'))
            }
            // An indented line under a bullet continues that bullet — README prose
            // wraps this way constantly, and a stray fragment block reads as a bug.
            para.isEmpty() && line.first().isWhitespace() && out.lastOrNull() is MdBlock.Bullet -> {
                val open = out.removeAt(out.lastIndex) as MdBlock.Bullet
                out += open.copy(text = open.text + " " + line.trim())
                i++
            }
            // Indented code, but only where a paragraph isn't already running (an
            // indented continuation line belongs to its paragraph).
            para.isEmpty() && (line.startsWith("    ") || line.startsWith("\t")) -> {
                val body = StringBuilder()
                while (i < lines.size && (lines[i].startsWith("    ") || lines[i].startsWith("\t") || lines[i].isBlank())) {
                    if (lines[i].isBlank() && !hasMoreIndented(lines, i)) break
                    body.appendLine(lines[i].removePrefix("\t").removePrefix("    "))
                    i++
                }
                out += MdBlock.Pre(null, body.toString().trimEnd('\n'))
            }
            else -> {
                // Up to three leading spaces are decoration in markdown, and a
                // wrapped line that keeps them reads as a broken indent on a phone.
                if (para.isNotEmpty()) para.append('\n')
                para.append(line.trimStart())
                i++
            }
        }
    }
    flush()
    return out
}

/** Does an indented block continue past the blank line at [from]? */
private fun hasMoreIndented(lines: List<String>, from: Int): Boolean {
    var j = from
    while (j < lines.size && lines[j].isBlank()) j++
    return j < lines.size && (lines[j].startsWith("    ") || lines[j].startsWith("\t"))
}

/**
 * Weave the inline marks of one block. [depth] bounds recursion through nested
 * emphasis; past it, marks are left as the characters they are.
 */
internal fun inlineMarkdown(
    src: String,
    code: Color,
    codeBg: Color,
    link: Color,
    depth: Int = 0,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            c == '\\' && i + 1 < src.length -> { append(src[i + 1]); i += 2 }

            c == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(color = code, background = codeBg)) {
                        append(src.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }

            c == '[' -> {
                val close = src.indexOf(']', i + 1)
                val open = if (close > 0 && close + 1 < src.length && src[close + 1] == '(') close + 1 else -1
                val end = if (open > 0) src.indexOf(')', open + 1) else -1
                if (end > 0) {
                    val label = src.substring(i + 1, close).ifBlank { src.substring(open + 1, end) }
                    withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) {
                        append(inlineMarkdown(label, code, codeBg, link, depth + 1))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }

            depth < 3 && src.startsWith("~~", i) -> {
                val end = closingAt(src, i + 2, "~~")
                if (end > 0) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(inlineMarkdown(src.substring(i + 2, end), code, codeBg, link, depth + 1))
                    }
                    i = end + 2
                } else { append(c); i++ }
            }

            depth < 3 && emphasisOpens(src, i) && src.startsWith("$c$c", i) -> {
                val end = closingAt(src, i + 2, "$c$c")
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(inlineMarkdown(src.substring(i + 2, end), code, codeBg, link, depth + 1))
                    }
                    i = end + 2
                } else { append(c); i++ }
            }

            depth < 3 && emphasisOpens(src, i) -> {
                val end = closingAt(src, i + 1, c.toString())
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(inlineMarkdown(src.substring(i + 1, end), code, codeBg, link, depth + 1))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }

            else -> { append(c); i++ }
        }
    }
}

/**
 * Is the marker at [i] an emphasis opener, or just arithmetic and snake_case?
 * An opener is followed by something other than space, and — for `_` — doesn't
 * sit inside a word.
 */
private fun emphasisOpens(src: String, i: Int): Boolean {
    val c = src[i]
    if (c != '*' && c != '_') return false
    val run = if (src.startsWith("$c$c", i)) 2 else 1
    val next = src.getOrNull(i + run) ?: return false
    if (next.isWhitespace()) return false
    if (c == '_' && i > 0 && (src[i - 1].isLetterOrDigit() || src[i - 1] == '_')) return false
    return true
}

/** The next [token] that closes emphasis — one not preceded by whitespace. */
private fun closingAt(src: String, from: Int, token: String): Int {
    var j = from
    while (j < src.length) {
        val k = src.indexOf(token, j)
        if (k < 0) return -1
        if (k > from && !src[k - 1].isWhitespace()) return k
        j = k + token.length
    }
    return -1
}
