package com.cocakova.charon.terminal

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Where the shell stands — the working directory a rigged prompt reports through
 * OSC 7 (`ESC ] 7 ; file://host/path ST`, the convention VTE, WezTerm, kitty and
 * tmux share). [host] is whatever the URL named ("" for `file:///path`); it matters
 * because a shell on a machine you ssh'd onward to reports *its* paths, not ours.
 *
 * Parsing is strict where guessing would be dangerous: the path is percent-decoded
 * to UTF-8 and handed to a probe on the host, so a report that doesn't decode
 * cleanly is refused whole rather than half-believed.
 */
data class ShellCwd(val host: String, val path: String) {

    companion object {
        /** Longer than any real path; past this a report is noise, not a place. */
        private const val MAX_PATH = 4096

        /** Read an OSC 7 payload (the text after `7;`), or null if it isn't one. */
        fun parse(payload: String): ShellCwd? {
            if (!payload.regionMatches(0, "file:", 0, 5, ignoreCase = true)) return null
            val rest = payload.substring(5)
            val (rawHost, rawPath) = when {
                rest.startsWith("//") -> {
                    val slash = rest.indexOf('/', 2)
                    if (slash < 0) return null            // an authority with no path
                    rest.substring(2, slash) to rest.substring(slash)
                }
                rest.startsWith("/") -> "" to rest        // file:/path — no authority at all
                else -> return null
            }
            val host = decode(rawHost) ?: return null
            val path = decode(rawPath) ?: return null
            if (path.length > MAX_PATH || !path.startsWith("/")) return null
            // A control character in a path is either an attack or a broken
            // encoder; neither should reach a probe.
            if (path.any { it.code < 0x20 || it.code == 0x7F } || host.any { it.code < 0x20 }) {
                return null
            }
            return ShellCwd(host, path.trimEnd('/').ifEmpty { "/" })
        }

        /**
         * Percent-decode to UTF-8. Characters a lenient shell left unencoded are
         * taken as themselves; a broken escape or bytes that aren't UTF-8 → null.
         */
        private fun decode(s: String): String? {
            if ('%' !in s) return s
            val bytes = ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    if (i + 2 >= s.length) return null      // escape cut short
                    val hi = hex(s[i + 1])
                    val lo = hex(s[i + 2])
                    if (hi < 0 || lo < 0) return null
                    bytes.write(hi * 16 + lo)
                    i += 3
                } else {
                    // One code point (a surrogate pair stays whole) as its UTF-8 bytes.
                    val end = i + Character.charCount(s.codePointAt(i))
                    bytes.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
                    i = end
                }
            }
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }

        /** ASCII hex only — `Character.digit` would take a fullwidth or Arabic-Indic digit. */
        private fun hex(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}
