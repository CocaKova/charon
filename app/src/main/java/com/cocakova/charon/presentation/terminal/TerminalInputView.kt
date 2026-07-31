package com.cocakova.charon.presentation.terminal

import android.content.Context
import android.graphics.Rect
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
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
 * whole. The editable mirrors the current line (reset on Enter), so the IME's
 * view of the field stays truthful — an IME that re-opens a finished word to
 * autocorrect it (setComposingRegion) is re-anchored into the diff instead of
 * having its correction re-typed wholesale onto the wire.
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

    /**
     * The toll is up: the predictive editor advertises a password field so the IME
     * drops suggestions, glide trails, and dictionary learning while a secret is
     * typed. RAW (TYPE_NULL) never exposes text to the IME, so it needs nothing.
     */
    var secure: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (mode == Mode.PREDICTIVE) imm().restartInput(this)
        }

    /** The live predictive connection, so out-of-band events can keep it honest. */
    private var predictive: PredictiveConnection? = null

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
            outAttrs.inputType = if (secure) {
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            // A fresh connection starts on an empty mirror. Leaving these at their
            // -1 default tells the IME "cursor unknown", and an IME that doesn't
            // know where the cursor is won't offer to replace the word under it —
            // the suggestion strip renders but tapping it does nothing.
            outAttrs.initialSelStart = 0
            outAttrs.initialSelEnd = 0
            outAttrs.initialCapsMode = 0
            PredictiveConnection().also { predictive = it }
        } else {
            predictive = null
            RawConnection()
        }
    }

    /**
     * Text just reached the line without passing through the IME — an autofill chip,
     * a snippet, a paste, an accessory key. Any composition the IME still holds now
     * describes a line that no longer exists; restart the connection so it starts
     * fresh instead of later committing the stale word on top of the inserted text.
     */
    fun textLandedOutsideIme() {
        val p = predictive ?: return
        if (p.holdsState()) {
            logInput { "out-of-band text: restarting input" }
            imm().restartInput(this)
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
     * Predictive personality: a real (full-editor) connection whose editable mirrors
     * the line being built, so IME surrounding-text queries answer truthfully. The
     * wire cursor lives at the end of the line; every IME edit lands there as a
     * common-prefix diff against [relayed] (+ [wireTail], committed text between the
     * composition and line end — an autocorrect's trailing space — replayed after
     * each diff). An edit whose geometry the wire cursor can't reach (mid-line, a
     * word already followed by more words) updates the editable only and then
     * restarts the connection — the one thing that must never happen is re-typing
     * text the terminal already has: that is how first words used to double.
     */
    private inner class PredictiveConnection : BaseInputConnection(this, true) {

        /** The whole IME-to-wire mapping, pure and unit-tested. */
        private val wire = PredictiveWire().apply {
            onNeedsResync = {
                logInput { "off-wire — resync scheduled" }
                post {
                    if (predictive === this@PredictiveConnection) {
                        imm().restartInput(this@TerminalInputView)
                    }
                }
            }
        }

        private var batchDepth = 0

        /** Set while an extracted-text monitor is live, so edits get pushed. */
        private var extractToken = -1

        fun holdsState(): Boolean = wire.holdsState(getEditable()?.length ?: 0)

        /** The line went to the remote (or died there): both mirrors start fresh. */
        fun lineReset() {
            wire.reset()
            getEditable()?.clear()
            report()
        }

        /** Mirror a wire-side backspace (IME sent a raw DEL key event). */
        fun mirrorBackspace() {
            val e = getEditable() ?: return
            // Composing IMEs retract through the composition instead.
            if (!wire.mirrorsRawBackspace(e.length)) return
            val cut = if (e.length >= 2 && Character.isSurrogatePair(e[e.length - 2], e[e.length - 1])) 2 else 1
            e.delete(e.length - cut, e.length)
            Selection.setSelection(e, e.length)
            report()
        }

        override fun beginBatchEdit(): Boolean {
            // BaseInputConnection answers false here, and an IME told its batch was
            // refused abandons composite edits — replacing the word under the cursor
            // is exactly such an edit. Accept them.
            batchDepth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (batchDepth > 0 && --batchDepth == 0) report()
            return batchDepth > 0
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            val handled = super.setComposingRegion(start, end)
            val e = getEditable() ?: return handled
            val lo = minOf(start, end).coerceIn(0, e.length)
            val hi = maxOf(start, end).coerceIn(0, e.length)
            wire.composingRegion(e.toString(), lo, hi)
            logInput { "composeRegion ${hi - lo}ch offWire=${wire.offWire}" }
            report()
            return handled
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            val t = text.toString()
            anchorIfLoose()
            logInput { "compose len=${t.length}${if (wire.offWire) " off-wire" else ""}" }
            emit(wire.compose(t))
            val handled = super.setComposingText(text, newCursorPosition)
            report()
            return handled
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val t = text.toString()
            anchorIfLoose()
            logInput { "commit len=${t.length}${if (wire.offWire) " off-wire" else ""}" }
            emit(wire.commit(t))
            val handled = super.commitText(text, newCursorPosition)
            settleMirror(t)
            report()
            return handled
        }

        override fun finishComposingText(): Boolean {
            // Composition ends as-is: everything relayed stands, nothing to retract.
            val handled = super.finishComposingText()
            wire.finishComposing()
            settleMirror("")
            report()
            return handled
        }

        /**
         * Purely informational — the text itself already arrived through
         * [commitText]. BaseInputConnection refuses it, and a refusal reads to the
         * IME as "this field can't be corrected", which turns off the very
         * suggestion-replacement we want. Accept it.
         */
        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            logInput { "deleteSurrounding $beforeLength/$afterLength${if (wire.offWire) " off-wire" else ""}" }
            relayDeletion { e, sel ->
                val from = (sel - beforeLength).coerceAtLeast(0)
                if (from < sel) Character.codePointCount(e, from, sel) else 0
            }
            val handled = super.deleteSurroundingText(beforeLength, afterLength)
            report()
            return handled
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            logInput { "deleteSurroundingCp $beforeLength/$afterLength${if (wire.offWire) " off-wire" else ""}" }
            relayDeletion { e, sel ->
                var remaining = beforeLength
                var i = sel
                while (remaining > 0 && i > 0) {
                    i -= if (i >= 2 && Character.isSurrogatePair(e[i - 2], e[i - 1])) 2 else 1
                    remaining--
                }
                beforeLength - remaining
            }
            val handled = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            report()
            return handled
        }

        /**
         * BaseInputConnection returns null here, which reads as "this editor has no
         * text" — an IME that can't read the field won't offer to replace a word in
         * it. The mirror is one line, so handing it over whole is cheap and true.
         */
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            val e = getEditable() ?: return null
            if (request != null && (flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR) != 0) {
                extractToken = request.token
            }
            return extracted(e)
        }

        private fun extracted(e: Editable): ExtractedText = ExtractedText().apply {
            text = e.toString()
            startOffset = 0
            partialStartOffset = -1
            partialEndOffset = -1
            selectionStart = Selection.getSelectionStart(e).coerceAtLeast(0)
            selectionEnd = Selection.getSelectionEnd(e).coerceAtLeast(0)
            flags = 0
        }

        /** A deletion of committed text before the cursor: backspaces, but only when
         *  the cursor is at the line's end — the only place the wire cursor is. */
        private inline fun relayDeletion(count: (Editable, Int) -> Int) {
            val e = getEditable() ?: return
            val sel = Selection.getSelectionEnd(e).let { if (it < 0) e.length else it }
            emit(wire.deleteBefore(e.toString(), sel, count(e, sel)))
        }

        /** A composition or commit with no span open lands at the IME's cursor;
         *  tell the wire where that is before it plans anything. */
        private fun anchorIfLoose() {
            val e = getEditable() ?: return
            if (wire.relayed.isNotEmpty() || getComposingSpanStart(e) >= 0) return
            val sel = Selection.getSelectionEnd(e).let { if (it < 0) e.length else it }
            wire.anchorAtCursor(e.toString(), sel)
        }

        private fun emit(op: PredictiveWire.Op) {
            if (op.backspaces > 0) {
                onInput?.invoke(KeyEncoder.encode(KeyEncoder.Key.BACKSPACE).repeat(op.backspaces))
            }
            if (op.insert.isNotEmpty()) sendToTerminal(op.insert)
        }

        /** After a commit the wire cursor sits at line end; snap the mirror's cursor
         *  there too so the next word starts where the wire can follow. A newline
         *  means the line went to the remote — start the mirror over. */
        private fun settleMirror(committed: String) {
            val e = getEditable() ?: return
            if (committed.contains('\n')) {
                e.clear()
            } else if (e.length > LINE_CAP) {
                // A runaway line (voice dictation marathons): keep the recent half
                // so surrounding-text queries stay cheap. Offsets shift; the IME
                // re-reads after the selection update.
                e.delete(0, e.length - LINE_CAP / 2)
            }
            Selection.setSelection(e, e.length)
        }

        /** The editor half of the IME contract: report selection/composing moves so
         *  the IME tracks the field instead of guessing. An IME that can't see its
         *  own committed text either re-commits it (doubled words) or gives up on
         *  replacing it (a suggestion tap that does nothing). Deferred to batch end. */
        private fun report() {
            if (batchDepth > 0) return
            val e = getEditable() ?: return
            imm().updateSelection(
                this@TerminalInputView,
                Selection.getSelectionStart(e), Selection.getSelectionEnd(e),
                getComposingSpanStart(e), getComposingSpanEnd(e),
            )
            if (extractToken >= 0) imm().updateExtractedText(this@TerminalInputView, extractToken, extracted(e))
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
        // Key events bypass the InputConnection; keep the predictive mirror honest.
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> predictive?.lineReset()
            KeyEvent.KEYCODE_DEL -> predictive?.mirrorBackspace()
        }
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

    /** Drop the soft keyboard and focus — the ferry's ashore, nothing left to type. */
    fun hideKeyboard() {
        imm().hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
        logInput { "hideKeyboard" }
    }

    private fun imm(): InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    // Event names only, never key content: typed text includes remote passwords.
    private inline fun logInput(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d("CharonInput", message())
    }

    private companion object {
        const val LINE_CAP = 1024
    }
}
