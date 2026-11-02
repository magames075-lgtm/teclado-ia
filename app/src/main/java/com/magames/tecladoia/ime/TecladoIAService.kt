package com.magames.tecladoia.ime

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import com.magames.tecladoia.Net
import com.magames.tecladoia.Prefs
import com.magames.tecladoia.settings.SettingsActivity
import com.magames.tecladoia.spell.LanguageToolClient
import com.magames.tecladoia.spell.SpellEngine
import com.magames.tecladoia.voice.VoiceInput

class TecladoIAService : InputMethodService(), KeyboardView.Listener, VoiceInput.Listener {

    private lateinit var prefs: Prefs
    private lateinit var keyboard: KeyboardView
    private lateinit var strip: LinearLayout
    private lateinit var voice: VoiceInput
    private val handler = Handler(Looper.getMainLooper())

    private val composing = StringBuilder()
    private var selEnd = 0
    private var suggestionsAllowed = true
    private var onlineCheckAllowed = true

    /** Last autocorrection, so backspace right after it restores the original word. */
    private var lastAutocorrect: Pair<String, String>? = null

    /** Pending LanguageTool match shown in the strip. */
    private var ltSnapshot: String? = null

    private var voiceUtterance = false
    private var voicePrefix = ""
    private var voiceCapitalize = false

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        voice = VoiceInput(this, this)
        SpellEngine.init(this) { handler.post { refreshSuggestions() } }
    }

    override fun onDestroy() {
        voice.destroy()
        super.onDestroy()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#17181C"))
        }
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1F2026"))
        }
        root.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f)))
        keyboard = KeyboardView(this, this)
        root.addView(keyboard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        showIdleStrip()
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.clear()
        lastAutocorrect = null
        ltSnapshot = null
        selEnd = info.initialSelEnd
        keyboard.hapticsEnabled = prefs.vibrate

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        val noSuggest = (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val isUrlOrEmail = cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_URI ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        suggestionsAllowed = cls == InputType.TYPE_CLASS_TEXT && !isPassword && !noSuggest && !isUrlOrEmail
        onlineCheckAllowed = suggestionsAllowed

        keyboard.mode = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> KeyboardView.Mode.SYMBOLS
            else -> KeyboardView.Mode.LETTERS
        }
        keyboard.enterLabel = enterLabelFor(info)
        updateAutoCaps()
        showIdleStrip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (voice.active) voice.stop()
        handler.removeCallbacks(ltRunnable)
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        composing.clear()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selEnd = newSelEnd
        // The user moved the cursor away from the word being composed: commit it as is.
        if (composing.isNotEmpty() && !voiceUtterance &&
            (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)
        ) {
            composing.clear()
            currentInputConnection?.finishComposingText()
            lastAutocorrect = null
            showIdleStrip()
        }
    }

    private fun enterLabelFor(info: EditorInfo): String {
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "⏎"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Ir"
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_SEND -> "Enviar"
            EditorInfo.IME_ACTION_NEXT -> "Próx."
            EditorInfo.IME_ACTION_DONE -> "OK"
            else -> "⏎"
        }
    }

    // ------------------------------------------------------------------ keys

    override fun onKey(code: Int, text: String?) {
        if (currentInputConnection == null) return
        if (voice.active && code != KeyboardView.CODE_MIC) voice.stop()
        if (code != KeyboardView.CODE_DELETE) {
            if (code != KeyboardView.CODE_SHIFT) lastAutocorrect = null
        }
        when (code) {
            KeyboardView.CODE_MIC -> { commitComposing(false); voice.toggle(); return }
            KeyboardView.CODE_SHIFT -> return
            KeyboardView.CODE_DELETE -> handleDelete()
            KeyboardView.CODE_ENTER -> handleEnter()
            KeyboardView.CODE_SPACE -> handleSeparator(" ")
            KeyboardView.CODE_TEXT -> {
                val t = text ?: return
                if (isWordChar(t)) {
                    composing.append(t)
                    setComposing()
                    if (keyboard.shift == KeyboardView.Shift.ON) keyboard.shift = KeyboardView.Shift.OFF
                } else {
                    handleSeparator(t)
                }
            }
        }
        if (code != KeyboardView.CODE_TEXT || composing.isEmpty()) updateAutoCaps()
        refreshSuggestions()
        scheduleOnlineCheck()
    }

    private fun isWordChar(t: String): Boolean {
        if (t.length != 1) return false
        val c = t[0]
        return c.isLetter() || ((c == '-' || c == '\'') && composing.isNotEmpty())
    }

    private fun setComposing() {
        val ic = currentInputConnection ?: return
        val word = composing.toString()
        val styled = SpannableString(word)
        styled.setSpan(UnderlineSpan(), 0, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ic.setComposingText(styled, 1)
    }

    /** Commits the word being typed, optionally applying autocorrect. Returns what was committed. */
    private fun commitComposing(allowAutocorrect: Boolean): String {
        val ic = currentInputConnection ?: return ""
        if (composing.isEmpty()) return ""
        val word = composing.toString()
        composing.clear()
        var out = word
        if (allowAutocorrect && prefs.autocorrect && suggestionsAllowed) {
            val fix = SpellEngine.check(word).autoFix
            if (fix != null && fix != word) out = fix
        }
        ic.commitText(out, 1)
        if (out != word) lastAutocorrect = word to out
        return out
    }

    private fun handleSeparator(sep: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        val hadWord = composing.isNotEmpty()
        lastAutocorrect = null
        commitComposing(allowAutocorrect = sep == " " || sep in ".,!?;:")
        if (sep == " " && !hadWord) {
            // double space -> ". "
            val prev = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (prev.length == 2 && prev[1] == ' ' && prev[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                return
            }
        }
        // no space before closing punctuation
        if (sep in ".,!?;:" && !hadWord) {
            val prev = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            if (prev == " ") ic.deleteSurroundingText(1, 0)
        }
        ic.commitText(sep, 1)
        lastAutocorrect?.let { lastAutocorrect = it.first to (it.second + sep) }
        ic.endBatchEdit()
    }

    private fun handleDelete() {
        val ic = currentInputConnection ?: return
        val undo = lastAutocorrect
        lastAutocorrect = null
        if (undo != null && composing.isEmpty()) {
            val (original, corrected) = undo
            val before = ic.getTextBeforeCursor(corrected.length, 0)?.toString()
            if (before == corrected) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(corrected.length, 0)
                ic.commitText(original, 1)
                ic.endBatchEdit()
                return
            }
        }
        if (composing.isNotEmpty()) {
            val last = composing.length - 1
            val cut = if (last > 0 && Character.isLowSurrogate(composing[last])) 2 else 1
            composing.setLength(composing.length - cut)
            if (composing.isEmpty()) ic.commitText("", 1) else setComposing()
            return
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        commitComposing(false)
        val info = currentInputEditorInfo
        val multiLine = info != null && (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = info == null || (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        when {
            multiLine && noAction -> ic.commitText("\n", 1)
            !noAction -> ic.performEditorAction(action)
            else -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
    }

    override fun onSpaceLongPress() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    private fun updateAutoCaps() {
        if (!::keyboard.isInitialized || keyboard.shift == KeyboardView.Shift.LOCK) return
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        if (keyboard.mode != KeyboardView.Mode.LETTERS) return
        val caps = info.inputType != InputType.TYPE_NULL && ic.getCursorCapsMode(info.inputType) != 0
        keyboard.shift = if (caps) KeyboardView.Shift.ON else KeyboardView.Shift.OFF
    }

    // ------------------------------------------------------------------ suggestion strip

    private fun stripText(label: String, bold: Boolean = false, color: Int = Color.WHITE, weight: Float = 1f, onClick: (() -> Unit)? = null): TextView =
        TextView(this).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6f), 0, dp(6f), 0)
            if (onClick != null) {
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { onClick() }
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        }

    private fun gearButton(): TextView = stripText("⚙", color = Color.parseColor("#9A9BA6"), weight = 0.4f) { openSettings() }

    private fun showIdleStrip() {
        if (!::strip.isInitialized) return
        strip.removeAllViews()
        val status = when {
            !SpellEngine.ready -> "Carregando dicionário…"
            prefs.offlineOnly -> "Teclado IA • modo offline"
            Net.isOnline(this) -> "Teclado IA • online"
            else -> "Teclado IA • offline"
        }
        strip.addView(stripText(status, color = Color.parseColor("#9A9BA6"), weight = 3f))
        strip.addView(gearButton())
    }

    private fun showSuggestions(words: List<String>, misspelled: Boolean, typed: String) {
        strip.removeAllViews()
        if (misspelled) {
            // typed word (keep as is) in red, then fixes
            strip.addView(stripText("“$typed”", color = Color.parseColor("#FF8A8A")) { pickSuggestion(typed, addSpace = true) })
        }
        for ((i, w) in words.take(3).withIndex()) {
            strip.addView(stripText(w, bold = misspelled && i == 0,
                color = if (misspelled && i == 0) Color.parseColor("#C4B5FD") else Color.WHITE) {
                pickSuggestion(w, addSpace = true)
            })
        }
        strip.addView(gearButton())
    }

    private fun refreshSuggestions() {
        if (!::strip.isInitialized || voice.active) return
        if (!suggestionsAllowed || composing.isEmpty()) {
            if (ltSnapshot == null) showIdleStrip()
            return
        }
        ltSnapshot = null
        val word = composing.toString()
        val r = SpellEngine.check(word)
        if (r.suggestions.isEmpty()) showIdleStrip() else showSuggestions(r.suggestions, r.misspelled, word)
    }

    private fun pickSuggestion(word: String, addSpace: Boolean) {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) return
        composing.clear()
        ic.commitText(if (addSpace) "$word " else word, 1)
        lastAutocorrect = null
        updateAutoCaps()
        showIdleStrip()
        scheduleOnlineCheck()
    }

    // ------------------------------------------------------------------ online check (LanguageTool)

    private val ltRunnable = Runnable { runOnlineCheck() }

    private fun scheduleOnlineCheck() {
        handler.removeCallbacks(ltRunnable)
        if (!onlineCheckAllowed || !prefs.onlineCheck || prefs.offlineOnly) return
        handler.postDelayed(ltRunnable, 800)
    }

    private fun runOnlineCheck() {
        if (voice.active || composing.isNotEmpty()) return  // wait until the word is finished
        if (!Net.isOnline(this)) return                      // offline: SymSpell already covers it
        val ic = currentInputConnection ?: return
        val text = ic.getTextBeforeCursor(LanguageToolClient.MAX_CHARS, 0)?.toString() ?: return
        if (text.isBlank()) return
        if (!LanguageToolClient.canRequestNow()) {
            // respect the free rate limit: try again a bit later
            handler.postDelayed(ltRunnable, LanguageToolClient.MIN_INTERVAL_MS)
            return
        }
        LanguageToolClient.check(text) { sent, matches -> onOnlineResult(sent, matches) }
    }

    private fun onOnlineResult(sent: String, matches: List<LanguageToolClient.Match>) {
        if (!::strip.isInitialized || voice.active || composing.isNotEmpty()) return
        val ic = currentInputConnection ?: return
        val now = ic.getTextBeforeCursor(sent.length, 0)?.toString()
        if (now != sent) return  // text changed meanwhile
        val m = matches.lastOrNull { it.replacements.isNotEmpty() && it.offset + it.length <= sent.length } ?: return
        ltSnapshot = sent
        strip.removeAllViews()
        val wrong = sent.substring(m.offset, m.offset + m.length)
        strip.addView(stripText("🌐 “$wrong”", color = Color.parseColor("#FFB86B"), weight = 1.2f) {
            android.widget.Toast.makeText(this, m.message, android.widget.Toast.LENGTH_LONG).show()
        })
        for (rep in m.replacements.take(2)) {
            strip.addView(stripText(rep, bold = true, color = Color.parseColor("#86EFAC")) { applyOnlineFix(sent, m, rep) })
        }
        strip.addView(stripText("✕", color = Color.parseColor("#9A9BA6"), weight = 0.4f) { ltSnapshot = null; showIdleStrip() })
    }

    private fun applyOnlineFix(sent: String, m: LanguageToolClient.Match, rep: String) {
        val ic = currentInputConnection ?: return
        ltSnapshot = null
        val now = ic.getTextBeforeCursor(sent.length, 0)?.toString()
        if (now != sent) { showIdleStrip(); return }
        val tail = sent.length - (m.offset + m.length)
        ic.beginBatchEdit()
        if (tail == 0) {
            ic.deleteSurroundingText(m.length, 0)
            ic.commitText(rep, 1)
        } else {
            val cursor = selEnd
            val start = cursor - sent.length + m.offset
            if (start >= 0) {
                ic.setSelection(start, start + m.length)
                ic.commitText(rep, 1)
                ic.setSelection(cursor + rep.length - m.length, cursor + rep.length - m.length)
            }
        }
        ic.endBatchEdit()
        showIdleStrip()
    }

    // ------------------------------------------------------------------ voice

    override fun onVoiceStatus(listening: Boolean, mode: VoiceInput.Mode?, message: String) {
        keyboard.micActive = listening
        if (listening) {
            strip.removeAllViews()
            strip.addView(stripText("🎤 $message", color = Color.parseColor("#FCA5A5"), weight = 3f))
            strip.addView(stripText("Parar", bold = true, weight = 1f) { voice.stop() })
        } else {
            if (voiceUtterance) {
                currentInputConnection?.finishComposingText()
                voiceUtterance = false
            }
            showIdleStrip()
        }
    }

    override fun onVoicePartial(text: String) {
        val ic = currentInputConnection ?: return
        if (!voiceUtterance) {
            voiceUtterance = true
            beginUtterance(ic)
        }
        ic.setComposingText(voicePrefix + formatVoice(text), 1)
    }

    override fun onVoiceFinal(text: String) {
        val ic = currentInputConnection ?: return
        if (!voiceUtterance) beginUtterance(ic)
        ic.commitText(voicePrefix + formatVoice(text), 1)
        voiceUtterance = false
        scheduleOnlineCheck()
    }

    private fun beginUtterance(ic: android.view.inputmethod.InputConnection) {
        val before = ic.getTextBeforeCursor(3, 0)?.toString().orEmpty()
        voicePrefix = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
        val trimmed = before.trimEnd()
        voiceCapitalize = trimmed.isEmpty() || trimmed.last() in ".!?\n"
    }

    /** Capitalizes at the start of a sentence (Vosk outputs lowercase). */
    private fun formatVoice(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        return if (voiceCapitalize) t.replaceFirstChar { it.titlecase() } else t
    }

    override fun onVoiceError(message: String, openSettings: Boolean) {
        if (!::strip.isInitialized) return
        strip.removeAllViews()
        strip.addView(stripText(message, color = Color.parseColor("#FCA5A5"), weight = 4f) {
            if (openSettings) openSettings() else showIdleStrip()
        })
        if (openSettings) strip.addView(stripText("Abrir", bold = true, weight = 1f) { openSettings() })
        handler.postDelayed({ if (!voice.active && composing.isEmpty()) showIdleStrip() }, 6000)
    }

    private fun openSettings() {
        val i = Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }
}
