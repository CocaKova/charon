package com.cocakova.charon.fleet

/**
 * Hailing a ferry: a crossing to a shore you haven't moored — `user@host`,
 * `user@host:2222`, `[fe80::1]:22`, or an `ssh://` link — parsed into a target the
 * Dock can sail to without saving anything. The host's key is still met by the
 * ferryman (TOFU) exactly like a mooring's; only the mooring itself is skipped.
 */
data class HailTarget(
    /** Null when the hail named no one; the sheet asks before casting off. */
    val user: String?,
    val host: String,
    val port: Int = 22,
) {
    /** How the Dock spells it back: user@host, :port when it isn't 22, IPv6 bracketed. */
    val address: String
        get() {
            val h = if (':' in host) "[$host]" else host
            return (user?.let { "$it@" } ?: "") + h + if (port != 22) ":$port" else ""
        }
}

object Hail {

    /**
     * Read a hail; null when it doesn't name a reachable shore. Accepts what
     * people paste: `ssh://user@host:port/` (RFC 3986 userinfo, percent-escapes,
     * and the draft-ietf `;fingerprint=` parameter, which is dropped — the
     * ferryman checks keys himself), plain `user@host[:port]`, and IPv6 in
     * brackets (a bare IPv6 address is taken whole, with no port).
     */
    fun parse(input: String): HailTarget? {
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = s.startsWith("ssh://", ignoreCase = true)
        if (uri) {
            s = s.substring("ssh://".length).trimEnd('/')
            if ('/' in s || '?' in s || '#' in s) return null
        }
        val at = s.lastIndexOf('@')
        var user: String? = if (at >= 0) s.substring(0, at) else null
        val rest = if (at >= 0) s.substring(at + 1) else s
        if (uri && user != null) {
            user = percentDecode(user.substringBefore(';')) ?: return null
        }
        if (user != null && (user.isEmpty() || user.any { it == ':' || it == '/' })) {
            if (user.isEmpty()) user = null else return null
        }

        var port = 22
        val host: String
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close < 0) return null
            host = rest.substring(1, close)
            val after = rest.substring(close + 1)
            if (after.isNotEmpty()) {
                if (!after.startsWith(":")) return null
                port = parsePort(after.substring(1)) ?: return null
            }
            if (':' !in host) return null // brackets are for IPv6
        } else {
            val colons = rest.count { it == ':' }
            when {
                colons == 0 -> host = rest
                colons == 1 -> {
                    host = rest.substringBefore(':')
                    port = parsePort(rest.substringAfter(':')) ?: return null
                }
                else -> host = rest // a bare IPv6 address: no room for a port
            }
        }
        if (host.isEmpty() || !host.all(::hostChar)) return null
        return HailTarget(user, host, port)
    }

    private fun parsePort(p: String): Int? =
        p.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isDigit) }
            ?.toInt()?.takeIf { it in 1..65535 }

    private fun hostChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' || c == ':' || c == '%'

    private fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(hex)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
