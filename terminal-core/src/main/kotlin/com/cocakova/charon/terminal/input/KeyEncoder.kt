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

    // ------------------------------------------------------------------ kitty

    /*
     * The Kitty keyboard protocol (progressive enhancement). A remote program pushes
     * flags onto the emulator's stack (`TerminalEmulator.kittyKeyboardFlags`); while
     * [kittyActive], keys go out through [encodeKitty] / [encodeKittyText] instead of
     * the legacy functions above, which stay byte-for-byte what they always were.
     * The rules are a port of kitty's own encoder (key_encoding.c), not a reading of
     * the prose alone — where the two could be read differently, kitty is what every
     * client library (crossterm, libtermkey, fish) is tested against.
     */

    /** Flag bits, as pushed by `CSI > flags u`. */
    const val KITTY_DISAMBIGUATE = 0b1
    const val KITTY_EVENT_TYPES = 0b10
    const val KITTY_ALTERNATE_KEYS = 0b100
    const val KITTY_ALL_KEYS_AS_ESCAPES = 0b1000
    const val KITTY_ASSOCIATED_TEXT = 0b10000

    /** Modifier bits; the wire carries `1 + mods`. */
    const val MOD_SHIFT = 0b1
    const val MOD_ALT = 0b10
    const val MOD_CTRL = 0b100
    const val MOD_SUPER = 0b1000
    const val MOD_HYPER = 0b10000
    const val MOD_META = 0b100000
    const val MOD_CAPS_LOCK = 0b1000000
    const val MOD_NUM_LOCK = 0b10000000
    private const val LOCK_MASK = MOD_CAPS_LOCK or MOD_NUM_LOCK

    /** Event type; the wire carries `ordinal + 1` (1 press, 2 repeat, 3 release). */
    enum class KeyAction { PRESS, REPEAT, RELEASE }

    /** Keys with no legacy encoding that the protocol can still name (`CSI code u`). */
    enum class ExtraKey(val code: Int, val isModifier: Boolean = false) {
        CAPS_LOCK(57358, true), SCROLL_LOCK(57359, true), NUM_LOCK(57360, true),
        PRINT_SCREEN(57361), PAUSE(57362), MENU(57363),
        LEFT_SHIFT(57441, true), LEFT_CONTROL(57442, true), LEFT_ALT(57443, true),
        LEFT_SUPER(57444, true), LEFT_META(57446, true),
        RIGHT_SHIFT(57447, true), RIGHT_CONTROL(57448, true), RIGHT_ALT(57449, true),
        RIGHT_SUPER(57450, true), RIGHT_META(57452, true),
    }

    /**
     * True when [flags] change how any key is encoded. Alternate keys (4) and
     * associated text (16) only reshape sequences the other bits produce, so on
     * their own the legacy encoders still apply — as in kitty.
     */
    fun kittyActive(flags: Int): Boolean =
        flags and (KITTY_DISAMBIGUATE or KITTY_EVENT_TYPES or KITTY_ALL_KEYS_AS_ESCAPES) != 0

    /**
     * A functional key under the kitty protocol, or null when the event sends
     * nothing (a release nobody asked to hear). [mods] is the full modifier set,
     * lock bits included; [Key.BACK_TAB] is Shift+Tab. DECCKM no longer applies:
     * once enhanced, the arrows are always `CSI A`, never `SS3 A`.
     */
    fun encodeKitty(key: Key, mods: Int, flags: Int, action: KeyAction = KeyAction.PRESS): String? {
        var m = mods
        val k = if (key == Key.BACK_TAB) Key.TAB.also { m = m or MOD_SHIFT } else key
        val disambiguate = flags and KITTY_DISAMBIGUATE != 0
        val eventTypes = flags and KITTY_EVENT_TYPES != 0
        val allKeys = flags and KITTY_ALL_KEYS_AS_ESCAPES != 0
        if (action == KeyAction.RELEASE && !eventTypes) return null

        // Enter, Tab and Backspace keep their legacy bytes (and never report a
        // release) until "all keys as escapes" — so `reset` can still be typed
        // after a program dies with the mode on. Escape keeps its byte unless
        // disambiguation is on. Lock modifiers alone don't make a key "modified".
        if (!allKeys && m and LOCK_MASK.inv() == 0) {
            val legacy = when (k) {
                Key.ENTER -> "\r"
                Key.TAB -> "\t"
                Key.BACKSPACE -> "\u007F"
                Key.ESCAPE -> if (!disambiguate && m == 0) ESC else null
                else -> null
            }
            if (legacy != null) return if (action == KeyAction.RELEASE) null else legacy
        }

        val (number, final) = when (k) {
            Key.ESCAPE -> 27 to 'u'
            Key.ENTER -> 13 to 'u'
            Key.TAB, Key.BACK_TAB -> 9 to 'u'
            Key.BACKSPACE -> 127 to 'u'
            Key.INSERT -> 2 to '~'
            Key.DELETE -> 3 to '~'
            Key.LEFT -> 1 to 'D'
            Key.RIGHT -> 1 to 'C'
            Key.UP -> 1 to 'A'
            Key.DOWN -> 1 to 'B'
            Key.PAGE_UP -> 5 to '~'
            Key.PAGE_DOWN -> 6 to '~'
            Key.HOME -> 1 to 'H'
            Key.END -> 1 to 'F'
            Key.F1 -> 1 to 'P'
            Key.F2 -> 1 to 'Q'
            Key.F3 -> 13 to '~' // CSI R would read as a cursor-position report
            Key.F4 -> 1 to 'S'
            Key.F5 -> 15 to '~'
            Key.F6 -> 17 to '~'
            Key.F7 -> 18 to '~'
            Key.F8 -> 19 to '~'
            Key.F9 -> 20 to '~'
            Key.F10 -> 21 to '~'
            Key.F11 -> 23 to '~'
            Key.F12 -> 24 to '~'
        }
        return kittyCsi(number, final, m, action, eventTypes)
    }

    /** A key with no legacy encoding: modifier keys only under flag 8, the rest always. */
    fun encodeKitty(key: ExtraKey, mods: Int, flags: Int, action: KeyAction = KeyAction.PRESS): String? {
        if (!kittyActive(flags)) return null
        if (key.isModifier && flags and KITTY_ALL_KEYS_AS_ESCAPES == 0) return null
        if (action == KeyAction.RELEASE && flags and KITTY_EVENT_TYPES == 0) return null
        return kittyCsi(key.code, 'u', mods, action, flags and KITTY_EVENT_TYPES != 0)
    }

    /**
     * A text-producing key under the kitty protocol.
     *
     * - [key]: the key's *unshifted* code point in the active layout (`a`, not `A`)
     * - [text]: what the key types with its modifiers applied (`A` for Shift+a);
     *   null/empty when a modifier like Ctrl or Alt means it types nothing
     * - [shifted]: its shifted code point (`A`), 0 if unknown
     * - [baseLayout]: the code point of the same physical key on a US PC-101
     *   layout, 0 if unknown (it's only sent when it differs from [key])
     *
     * Plain typing stays plain text unless flag 8 asks for every key as an escape;
     * the escape form carries the modifiers so Ctrl+I is not Tab and Ctrl+[ is not
     * Esc. Returns null when nothing should be sent.
     */
    fun encodeKittyText(
        key: Int,
        mods: Int,
        flags: Int,
        text: String? = null,
        shifted: Int = 0,
        baseLayout: Int = 0,
        action: KeyAction = KeyAction.PRESS,
    ): String? {
        val disambiguate = flags and KITTY_DISAMBIGUATE != 0
        val eventTypes = flags and KITTY_EVENT_TYPES != 0
        val alternates = flags and KITTY_ALTERNATE_KEYS != 0
        val allKeys = flags and KITTY_ALL_KEYS_AS_ESCAPES != 0
        val embedText = flags and KITTY_ASSOCIATED_TEXT != 0

        val hasText = !text.isNullOrEmpty() && text.codePointAt(0).let { it >= 0x20 && it != 0x7F }
        if (key == 0 && !hasText) return null
        if (!allKeys && hasText && action != KeyAction.RELEASE) return text
        if (action == KeyAction.RELEASE && !eventTypes) return null

        val addActions = eventTypes && action != KeyAction.PRESS
        val base = if (baseLayout != key) baseLayout else 0
        val addAlternates = alternates && ((shifted > 0 && mods and MOD_SHIFT != 0) || base > 0)
        val addText = embedText && hasText && action != KeyAction.RELEASE

        if (!addActions && !addAlternates && !addText) {
            if (mods == 0) {
                return if (allKeys) kittyCsi(key, 'u', 0, action, eventTypes) else String(Character.toChars(key))
            }
            // Event types alone leave modified text keys on their legacy encodings.
            if (!disambiguate && !allKeys) legacyPrintable(key, shifted, mods)?.let { return it }
        }

        val sb = StringBuilder(ESC).append('[').append(key)
        if (addAlternates) {
            sb.append(':')
            if (mods and MOD_SHIFT != 0 && shifted > 0) sb.append(shifted)
            if (base > 0) sb.append(':').append(base)
        }
        val second = mods != 0 || addActions
        if (second || addText) {
            sb.append(';')
            if (second) sb.append(mods + 1)
            if (addActions) sb.append(':').append(action.ordinal + 1)
        }
        if (addText) {
            val t = text!!
            var i = 0
            while (i < t.length) {
                val cp = t.codePointAt(i)
                sb.append(if (i == 0) ';' else ':').append(cp)
                i += Character.charCount(cp)
            }
        }
        return sb.append('u').toString()
    }

    /**
     * One character from a path that only knows characters — the soft keyboard, the
     * accessory row — pressed with [mods] (sticky Ctrl/Alt). Control characters are
     * the keys they stand for (CR is Enter, DEL is Backspace), and an upper-case
     * letter is Shift plus its lower-case key, which is all a character can tell us
     * about the physical key behind it.
     */
    fun encodeKittyChar(ch: Char, mods: Int, flags: Int): String {
        val special = when (ch) {
            '\r' -> Key.ENTER
            '\t' -> Key.TAB
            '\u007F', '\b' -> Key.BACKSPACE
            '\u001B' -> Key.ESCAPE
            else -> null
        }
        if (special != null) return encodeKitty(special, mods, flags) ?: ch.toString()
        if (ch < ' ') return ch.toString() // a bare control code has no key to name
        val upper = ch in 'A'..'Z'
        val key = if (upper) ch.lowercaseChar() else ch
        val m = if (upper) mods or MOD_SHIFT else mods
        // Ctrl or Alt consume the text, as a real keyboard's would.
        val text = if (m and (MOD_CTRL or MOD_ALT) == 0) ch.toString() else null
        return encodeKittyText(key.code, m, flags, text = text, shifted = if (upper) ch.code else 0)
            ?: ch.toString()
    }

    /** `CSI number ; mods:event final`, dropping a lone `1` and empty fields. */
    private fun kittyCsi(number: Int, final: Char, mods: Int, action: KeyAction, eventTypes: Boolean): String {
        val addActions = eventTypes && action != KeyAction.PRESS
        val second = mods != 0 || addActions
        val sb = StringBuilder(ESC).append('[')
        if (number != 1 || second) sb.append(number)
        if (second) {
            sb.append(';').append(mods + 1)
            if (addActions) sb.append(':').append(action.ordinal + 1)
        }
        return sb.append(final).toString()
    }

    /** kitty's legacy fallback for a modified ASCII key (the event-types-only corner). */
    private fun legacyPrintable(key: Int, shifted: Int, mods: Int): String? {
        if (key !in 0x20..0x7E) return null
        var k = key.toChar()
        val all = mods and LOCK_MASK.inv()
        var m = all
        if (m and MOD_SHIFT != 0 && shifted in 0x21..0x7E && shifted != key &&
            (m and MOD_CTRL == 0 || k !in 'a'..'z')
        ) {
            k = shifted.toChar()
            m = m and MOD_SHIFT.inv()
        }
        return when {
            all == MOD_SHIFT -> k.toString()
            m == MOD_ALT -> ESC + k
            m == MOD_CTRL -> ctrl(k)
            m == (MOD_CTRL or MOD_ALT) -> ctrl(k)?.let { ESC + it }
            else -> null
        }
    }

    /**
     * Wrap pasted text for the remote: bracketed-paste guards when the app requested
     * them, with CR line endings either way (terminals expect CR for Enter).
     */
    fun paste(text: String, bracketed: Boolean): String {
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        return if (bracketed) "$ESC[200~$normalized$ESC[201~" else normalized
    }
}
