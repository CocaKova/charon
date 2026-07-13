package com.cocakova.charon.presentation.terminal

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.cocakova.charon.terminal.input.KeyEncoder

/**
 * The raw-mode IME anchor (the proven terminal approach): a focusable View whose
 * InputConnection advertises TYPE_NULL, which makes soft keyboards deliver plain key
 * events instead of composing text. IMEs that commit text anyway (some do) still work
 * via the commitText fallback. Hardware keyboards land in [onKeyDown] with full
 * Ctrl/Alt handling.
 */
class TerminalInputView(context: Context) : View(context) {

    /** Sink for encoded terminal input. */
    var onInput: ((String) -> Unit)? = null

    /** DECCKM state provider, wired to the live emulator. */
    var appCursorKeys: () -> Boolean = { false }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                onInput?.invoke(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) {
                    onInput?.invoke(KeyEncoder.encode(KeyEncoder.Key.BACKSPACE))
                }
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val encoded = encodeKeyEvent(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        onInput?.invoke(encoded)
        return true
    }

    private fun encodeKeyEvent(keyCode: Int, event: KeyEvent): String? {
        val app = appCursorKeys()
        val special = when (keyCode) {
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
        if (special != null) return KeyEncoder.encode(special, appCursorKeys = app)

        val ch = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv() and KeyEvent.META_ALT_MASK.inv())
        if (ch == 0) return null
        val base = ch.toChar()
        var out = when {
            event.isCtrlPressed -> KeyEncoder.ctrl(base) ?: return null
            else -> base.toString()
        }
        if (event.isAltPressed) out = KeyEncoder.alt(out)
        return out
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
}
