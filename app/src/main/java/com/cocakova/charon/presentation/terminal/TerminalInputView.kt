package com.cocakova.charon.presentation.terminal

import android.content.Context
import android.graphics.Rect
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.cocakova.charon.BuildConfig
import com.cocakova.charon.terminal.input.KeyEncoder

/**
 * The IME anchor with two personalities:
 *
 * **PREDICTIVE (default)** — advertises a real multiline text editor, which is what
 * makes IMEs light up glide/swipe typing, voice input, and autocomplete. No terminal
 * does this well; it's the leg up. The IME speaks composing-text protocol; we
 * translate each composition update into terminal bytes as a common-prefix diff
 * (DELs for the retracted tail, then the new tail) — the remote line editor plays
 * the role of the text field, so the user sees live feedback and swiped words land
 * whole. The editable is cleared after every commit: the terminal is the truth, the
 * IME always starts the next word fresh.
 *
 * **RAW** — `TYPE_NULL`: plain key events, zero IME interference. The classic
 * terminal approach, kept as the escape hatch for misbehaving IMEs and for TUIs
 * where composing-diff backspaces would misbehave (accessory-row toggle).
 *
 * Hardware keyboards land in [onKeyDown] with full Ctrl/Alt handling either way.
 */
class TerminalInputView(context: Context) : View(context) {

    enum class Mode { PREDICTIVE, RAW }

    /** Sink for encoded terminal input. */
    var onInput: ((String) -> Unit)? = null

    /** DECCKM state provider, wired to the live emulator. */
    var appCursorKeys: () -> Boolean = { false }

    var mode: Mode = Mode.PREDICTIVE
        set(value) {
            if (field == value) return
            field = value
            // Re-negotiate the input connection so the IME sees the new editor type.
            imm().restartInput(this)
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        logInput { "focus ${if (gainFocus) "gained" else "LOST"}" }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        logInput { "input connection created, mode=$mode" }
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return if (mode == Mode.PREDICTIVE) {
            // Plain multiline text, no AUTO_CORRECT flag: suggestions and gestures
            // stay available, but the IME shouldn't hard-replace words on space.
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            PredictiveConnection()
        } else {
            outAttrs.inputType = InputType.TYPE_NULL
            RawConnection()
        }
    }

    /** Raw personality: keys arrive as KeyEvents; stray commits still honored. */
    private inner class RawConnection : BaseInputConnection(this, false) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            logInput { "raw commitText len=${text.length}" }
            sendToTerminal(text.toString())
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength.coerceAtLeast(1)) {
                onInput?.invoke(KeyEncoder.encode(KeyEncoder.Key.BACKSPACE))
            }
            return true
        }
    }

    /**
     * Predictive personality: a real (full-editor) connection whose editable exists
     * only to satisfy IME surrounding-text queries mid-word. What we've already
     * relayed for the current composition is tracked in [relayed]; every composing
     * update sends the diff. Commits flush the diff and reset the editable.
     */
    private inner class PredictiveConnection : BaseInputConnection(this, true) {

        private var relayed = ""

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            val t = text.toString()
            logInput { "compose len=${t.length}" }
            sendDiff(relayed, t)
            relayed = t
            return super.setComposingText(text, newCursorPosition)
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val t = text.toString()
            logInput { "commit len=${t.length}" }
            sendDiff(relayed, t)
            relayed = ""
            val handled = super.commitText(text, newCursorPosition)
            // The word is on the wire; the next one starts from a clean field.
            getEditable()?.clear()
            return handled
        }

        override fun finishComposingText(): Boolean {
            // Composition ends as-is: everything relayed stands, nothing to retract.
            relayed = ""
            val handled = super.finishComposingText()
            getEditable()?.clear()
            return handled
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            logInput { "deleteSurrounding $beforeLength" }
            repeat(beforeLength.coerceAtLeast(1)) {
                onInput?.invoke(KeyEncoder.encode(KeyEncoder.Key.BACKSPACE))
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        private fun sendDiff(old: String, new: String) {
            var common = 0
            val max = minOf(old.length, new.length)
            while (common < max && old[common] == new[common]) common++
            // Never split a surrogate pair at the diff point.
            if (common > 0 && Character.isHighSurrogate(old[common - 1])) common--
            val retract = if (common < old.length) old.codePointCount(common, old.length) else 0
            if (retract > 0) {
                onInput?.invoke(KeyEncoder.encode(KeyEncoder.Key.BACKSPACE).repeat(retract))
            }
            if (new.length > common) sendToTerminal(new.substring(common))
        }
    }

    private fun sendToTerminal(text: String) {
        if (text.isEmpty()) return
        onInput?.invoke(text.replace('\n', '\r'))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val encoded = encodeKeyEvent(keyCode, event)
        logInput { "keyDown $keyCode -> ${if (encoded == null) "unhandled" else "${encoded.length}ch"}" }
        if (encoded == null) return super.onKeyDown(keyCode, event)
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
        imm().showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        logInput { "showKeyboard: isFocused=$isFocused hasWindowFocus=${hasWindowFocus()}" }
    }

    private fun imm(): InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    // Event names only, never key content: typed text includes remote passwords.
    private inline fun logInput(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d("CharonInput", message())
    }
}
