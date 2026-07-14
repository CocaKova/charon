package com.cocakova.charon.terminal.input

/**
 * Encodes keys into the byte sequences a terminal sends. Pure functions — the Android
 * layer maps KeyEvents/IME input onto these.
 */
object KeyEncoder {

    const val ESC = "\u001B"

    enum class Key {
        UP, DOWN, RIGHT, LEFT,
        HOME, END, INSERT, DELETE, PAGE_UP, PAGE_DOWN,
        F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
        ENTER, TAB, BACK_TAB, BACKSPACE, ESCAPE,
    }

    /**
     * Encode a special key. [appCursorKeys] follows DECCKM; [backspaceSendsDel] is the
     * modern default (DEL 0x7F rather than BS 0x08).
     */
    fun encode(key: Key, appCursorKeys: Boolean = false, backspaceSendsDel: Boolean = true): String = when (key) {
        Key.UP -> if (appCursorKeys) "${ESC}OA" else "$ESC[A"
        Key.DOWN -> if (appCursorKeys) "${ESC}OB" else "$ESC[B"
        Key.RIGHT -> if (appCursorKeys) "${ESC}OC" else "$ESC[C"
        Key.LEFT -> if (appCursorKeys) "${ESC}OD" else "$ESC[D"
        Key.HOME -> if (appCursorKeys) "${ESC}OH" else "$ESC[H"
        Key.END -> if (appCursorKeys) "${ESC}OF" else "$ESC[F"
        Key.INSERT -> "$ESC[2~"
        Key.DELETE -> "$ESC[3~"
        Key.PAGE_UP -> "$ESC[5~"
        Key.PAGE_DOWN -> "$ESC[6~"
        Key.F1 -> "${ESC}OP"
        Key.F2 -> "${ESC}OQ"
        Key.F3 -> "${ESC}OR"
        Key.F4 -> "${ESC}OS"
        Key.F5 -> "$ESC[15~"
        Key.F6 -> "$ESC[17~"
        Key.F7 -> "$ESC[18~"
        Key.F8 -> "$ESC[19~"
        Key.F9 -> "$ESC[20~"
        Key.F10 -> "$ESC[21~"
        Key.F11 -> "$ESC[23~"
        Key.F12 -> "$ESC[24~"
        Key.ENTER -> "\r"
        Key.TAB -> "\t"
        Key.BACK_TAB -> "$ESC[Z"   // CBT shift-tab
        Key.BACKSPACE -> if (backspaceSendsDel) "\u007F" else "\u0008"
        Key.ESCAPE -> ESC
    }

    /**
     * Ctrl+char → C0 control, or null if the combination has no terminal meaning.
     * Handles a-z, A-Z, space (NUL), and the punctuation controls (@[\]^_?).
     */
    fun ctrl(char: Char): String? {
        val c = when (char) {
            in 'a'..'z' -> char - 'a' + 1
            in 'A'..'Z' -> char - 'A' + 1
            ' ', '@' -> 0
            '[' -> 27
            '\\' -> 28
            ']' -> 29
            '^' -> 30
            '_', '/' -> 31
            '?' -> 127
            else -> return null
        }
        return c.toChar().toString()
    }

    /** Alt/Meta prefixes ESC (the xterm metaSendsEscape behavior). */
    fun alt(text: String): String = ESC + text

    /**
     * Wrap pasted text for the remote: bracketed-paste guards when the app requested
     * them, with CR line endings either way (terminals expect CR for Enter).
     */
    fun paste(text: String, bracketed: Boolean): String {
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        return if (bracketed) "$ESC[200~$normalized$ESC[201~" else normalized
    }
}
