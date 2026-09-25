package com.cocakova.charon.presentation.terminal

import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.cocakova.charon.terminal.input.KeyEncoder

/**
 * Android [KeyEvent]s in the vocabulary of [KeyEncoder]: which terminal key a
 * keycode is, and — when the remote has pushed Kitty keyboard flags — the whole
 * event (key, modifiers, press/repeat/release) encoded the way the protocol wants.
 * The legacy path in [TerminalInputView] only borrows [special]; everything else
 * here runs only while the protocol is on.
 */
internal object HardwareKeys {

    /** The functional key a keycode names, or null for text keys and the unmapped. */
    fun special(keyCode: Int): KeyEncoder.Key? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyEncoder.Key.ENTER
        KeyEvent.KEYCODE_DEL -> KeyEncoder.Key.BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> KeyEncoder.Key.DELETE
        KeyEvent.KEYCODE_TAB -> KeyEncoder.Key.TAB
        KeyEvent.KEYCODE_ESCAPE -> KeyEncoder.Key.ESCAPE
        KeyEvent.KEYCODE_DPAD_UP -> KeyEncoder.Key.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> KeyEncoder.Key.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> KeyEncoder.Key.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> KeyEncoder.Key.RIGHT
        KeyEvent.KEYCODE_MOVE_HOME -> KeyEncoder.Key.HOME
        KeyEvent.KEYCODE_MOVE_END -> KeyEncoder.Key.END
        KeyEvent.KEYCODE_PAGE_UP -> KeyEncoder.Key.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> KeyEncoder.Key.PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> KeyEncoder.Key.INSERT
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            KeyEncoder.Key.entries[KeyEncoder.Key.F1.ordinal + (keyCode - KeyEvent.KEYCODE_F1)]
        else -> null
    }

    /**
     * Encode [event] under the Kitty keyboard protocol ([flags] must be
     * [KeyEncoder.kittyActive]), or null when it sends nothing. Releases arrive
     * here too (from `onKeyUp`) and only produce bytes when flag 2 asked for them.
     */
    fun encodeKitty(keyCode: Int, event: KeyEvent, flags: Int): String? {
        var mods = mods(event)
        val action = when {
            event.action == KeyEvent.ACTION_UP -> KeyEncoder.KeyAction.RELEASE
            event.repeatCount > 0 -> KeyEncoder.KeyAction.REPEAT
            else -> KeyEncoder.KeyAction.PRESS
        }
        special(keyCode)?.let { return KeyEncoder.encodeKitty(it, mods, flags, action) }
        extra(keyCode)?.let { return KeyEncoder.encodeKitty(it, mods, flags, action) }

        val key = event.getUnicodeChar(0).takeIf { it > 0 && it and KeyCharacterMap.COMBINING_ACCENT == 0 }
            ?: return null // dead keys and keys with no character: nothing to name
        val shifted = event.getUnicodeChar(KeyEvent.META_SHIFT_ON)
            .takeIf { it > 0 && it and KeyCharacterMap.COMBINING_ACCENT == 0 } ?: 0
        // AltGr (right Alt alone) that the layout turns into a character — `@` on
        // AltGr+Q in German — is typing, not an Alt chord.
        val meta = event.metaState
        if (meta and KeyEvent.META_ALT_RIGHT_ON != 0 && meta and KeyEvent.META_ALT_LEFT_ON == 0 &&
            mods and (KeyEncoder.MOD_CTRL or KeyEncoder.MOD_SUPER) == 0
        ) {
            val withAltGr = event.getUnicodeChar(meta)
            val without = event.getUnicodeChar(meta and KeyEvent.META_ALT_MASK.inv())
            if (withAltGr > 0 && withAltGr and KeyCharacterMap.COMBINING_ACCENT == 0 && withAltGr != without) {
                mods = mods and KeyEncoder.MOD_ALT.inv()
            }
        }
        // Ctrl/Alt/Super chords type nothing; otherwise the text is what the layout
        // produces with Shift, Caps Lock (and AltGr) applied.
        val text = if (mods and (KeyEncoder.MOD_CTRL or KeyEncoder.MOD_ALT or KeyEncoder.MOD_SUPER) != 0) {
            null
        } else {
            event.getUnicodeChar(meta)
                .takeIf { it > 0 && it and KeyCharacterMap.COMBINING_ACCENT == 0 }
                ?.let { String(Character.toChars(it)) }
        }
        return KeyEncoder.encodeKittyText(
            key = key,
            mods = mods,
            flags = flags,
            text = text,
            shifted = if (shifted != key) shifted else 0,
            baseLayout = usLayout(keyCode),
            action = action,
        )
    }

    /** The protocol's modifier bits. Android's Meta is the Windows/⌘ key — kitty's super. */
    private fun mods(event: KeyEvent): Int {
        var m = 0
        if (event.isShiftPressed) m = m or KeyEncoder.MOD_SHIFT
        if (event.isAltPressed) m = m or KeyEncoder.MOD_ALT
        if (event.isCtrlPressed) m = m or KeyEncoder.MOD_CTRL
        if (event.isMetaPressed) m = m or KeyEncoder.MOD_SUPER
        if (event.isCapsLockOn) m = m or KeyEncoder.MOD_CAPS_LOCK
        if (event.isNumLockOn) m = m or KeyEncoder.MOD_NUM_LOCK
        return m
    }

    private fun extra(keyCode: Int): KeyEncoder.ExtraKey? = when (keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEncoder.ExtraKey.LEFT_SHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEncoder.ExtraKey.RIGHT_SHIFT
        KeyEvent.KEYCODE_CTRL_LEFT -> KeyEncoder.ExtraKey.LEFT_CONTROL
        KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEncoder.ExtraKey.RIGHT_CONTROL
        KeyEvent.KEYCODE_ALT_LEFT -> KeyEncoder.ExtraKey.LEFT_ALT
        KeyEvent.KEYCODE_ALT_RIGHT -> KeyEncoder.ExtraKey.RIGHT_ALT
        KeyEvent.KEYCODE_META_LEFT -> KeyEncoder.ExtraKey.LEFT_SUPER
        KeyEvent.KEYCODE_META_RIGHT -> KeyEncoder.ExtraKey.RIGHT_SUPER
        KeyEvent.KEYCODE_CAPS_LOCK -> KeyEncoder.ExtraKey.CAPS_LOCK
        KeyEvent.KEYCODE_NUM_LOCK -> KeyEncoder.ExtraKey.NUM_LOCK
        KeyEvent.KEYCODE_SCROLL_LOCK -> KeyEncoder.ExtraKey.SCROLL_LOCK
        KeyEvent.KEYCODE_SYSRQ -> KeyEncoder.ExtraKey.PRINT_SCREEN
        KeyEvent.KEYCODE_BREAK -> KeyEncoder.ExtraKey.PAUSE
        else -> null
    }

    /**
     * The key's character on a US PC-101 layout — the protocol's "base layout key",
     * which lets a shortcut bound to Ctrl+Q fire from the same physical key on
     * AZERTY. Android keycodes name physical US positions (the layout lives in the
     * KeyCharacterMap), so the keycode alone answers it.
     */
    private fun usLayout(keyCode: Int): Int = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 'a'.code + (keyCode - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0'.code + (keyCode - KeyEvent.KEYCODE_0)
        KeyEvent.KEYCODE_GRAVE -> '`'.code
        KeyEvent.KEYCODE_MINUS -> '-'.code
        KeyEvent.KEYCODE_EQUALS -> '='.code
        KeyEvent.KEYCODE_LEFT_BRACKET -> '['.code
        KeyEvent.KEYCODE_RIGHT_BRACKET -> ']'.code
        KeyEvent.KEYCODE_BACKSLASH -> '\\'.code
        KeyEvent.KEYCODE_SEMICOLON -> ';'.code
        KeyEvent.KEYCODE_APOSTROPHE -> '\''.code
        KeyEvent.KEYCODE_COMMA -> ','.code
        KeyEvent.KEYCODE_PERIOD -> '.'.code
        KeyEvent.KEYCODE_SLASH -> '/'.code
        KeyEvent.KEYCODE_SPACE -> ' '.code
        else -> 0
    }
}
