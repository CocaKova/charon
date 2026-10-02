package com.cocakova.charon.terminal

/**
 * The questions a program asks the terminal over DCS, answered honestly:
 *
 * - **DECRQSS** (`DCS $ q Pt ST`): "what is this setting right now?" — the pen
 *   (`m`), the scroll region (`r`), the cursor shape (` q`), protection (`"q`) and
 *   the conformance level (`"p`). Anything else: `DCS 0 $ r ST`, "not a setting".
 * - **XTGETTCAP** (`DCS + q <hex names> ST`): "what would terminfo say?" — the
 *   handful of capabilities modern TUIs probe for (truecolor, styled underlines,
 *   synchronized output, clipboard, cursor shapes), one answer per name, unknown
 *   names refused by name so a program never waits on silence.
 */
internal object TermQueries {

    private const val DCS = "\u001BP"
    private const val ST = "\u001B\\"
    private const val ESC = "\u001B"

    fun decrqss(request: String, term: TerminalEmulator): String {
        val answer = when (request) {
            "m" -> term.penSgr() + "m"
            "r" -> term.scrollRegion.let { (top, bottom) -> "${top + 1};${bottom + 1}r" }
            " q" -> "${term.cursorStyle} q"
            "\"q" -> "0\"q"
            "\"p" -> "62;1\"p"
            else -> null
        }
        return if (answer != null) "${DCS}1\$r$answer$ST" else "${DCS}0\$r$ST"
    }

    /** Booleans answer with the bare name; strings and numbers with name=value. */
    private val caps: Map<String, String?> = mapOf(
        "TN" to "xterm-256color",
        "name" to "xterm-256color",
        "Co" to "256",
        "colors" to "256",
        "RGB" to "8/8/8",
        "Tc" to null,
        "bce" to null,
        "kbs" to "\u007F",
        "Ms" to "$ESC]52;%p1%s;%p2%s\u0007",
        "Ss" to "$ESC[%p1%d q",
        "Se" to "$ESC[2 q",
        "Smulx" to "$ESC[4:%p1%dm",
        "Setulc" to "$ESC[58:2::%p1%{65536}%/%d:%p1%{256}%/%{255}%&%d:%p1%{255}%&%d%;m",
        "Sync" to "$ESC[?2026%?%p1%{1}%-%tl%eh%;",
    )

    fun xtgettcap(request: String): List<String> {
        if (request.isEmpty()) return emptyList()
        return request.split(';').take(MAX_NAMES).map { hexName ->
            val name = unhex(hexName)
            when {
                name == null || name !in caps -> "${DCS}0+r$hexName$ST"
                else -> {
                    val value = caps[name]
                    if (value == null) "${DCS}1+r$hexName$ST"
                    else "${DCS}1+r$hexName=${hex(value)}$ST"
                }
            }
        }
    }

    /** The pen as an SGR parameter string, `0` first so applying it is idempotent. */
    fun sgrOf(attrs: Long, ul: Long): String {
        val p = ArrayList<String>(8)
        p += "0"
        if (CellAttrs.hasStyle(attrs, CellAttrs.BOLD)) p += "1"
        if (CellAttrs.hasStyle(attrs, CellAttrs.FAINT)) p += "2"
        if (CellAttrs.hasStyle(attrs, CellAttrs.ITALIC)) p += "3"
        if (CellAttrs.hasStyle(attrs, CellAttrs.UNDERLINE)) {
            val style = CellExt.underlineStyle(attrs, ul)
            p += if (style == CellExt.UL_SINGLE) "4" else "4:$style"
        }
        if (CellAttrs.hasStyle(attrs, CellAttrs.BLINK)) p += "5"
        if (CellAttrs.hasStyle(attrs, CellAttrs.INVERSE)) p += "7"
        if (CellAttrs.hasStyle(attrs, CellAttrs.INVISIBLE)) p += "8"
        if (CellAttrs.hasStyle(attrs, CellAttrs.STRIKETHROUGH)) p += "9"
        color(CellAttrs.fgMode(attrs), CellAttrs.fgColor(attrs), 30, 90, "38")?.let { p += it }
        color(CellAttrs.bgMode(attrs), CellAttrs.bgColor(attrs), 40, 100, "48")?.let { p += it }
        when (CellExt.ulMode(ul)) {
            CellAttrs.MODE_PALETTE -> p += "58:5:${CellExt.ulColor(ul)}"
            CellAttrs.MODE_RGB -> CellExt.ulColor(ul).let { p += "58:2::${(it shr 16) and 0xFF}:${(it shr 8) and 0xFF}:${it and 0xFF}" }
        }
        return p.joinToString(";")
    }

    private fun color(mode: Int, value: Int, base: Int, bright: Int, extended: String): String? =
        when (mode) {
            CellAttrs.MODE_PALETTE -> when (value) {
                in 0..7 -> "${base + value}"
                in 8..15 -> "${bright + value - 8}"
                else -> "$extended;5;$value"
            }
            CellAttrs.MODE_RGB -> "$extended;2;${(value shr 16) and 0xFF};${(value shr 8) and 0xFF};${value and 0xFF}"
            else -> null
        }

    private fun hex(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) append("%02X".format(b.toInt() and 0xFF))
    }

    private fun unhex(h: String): String? {
        if (h.isEmpty() || h.length % 2 != 0 || h.length > 128) return null
        val bytes = ByteArray(h.length / 2)
        for (i in bytes.indices) {
            bytes[i] = h.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
        }
        return String(bytes, Charsets.UTF_8)
    }

    private const val MAX_NAMES = 32
}
