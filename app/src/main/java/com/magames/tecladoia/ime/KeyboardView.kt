package com.magames.tecladoia.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale

/**
 * Lightweight canvas-drawn QWERTY pt-BR keyboard (no deprecated KeyboardView).
 * Long-press a key to get accents (á à â ã, é ê, í, ó ô õ, ú ü, ç…) and digits.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onKey(code: Int, text: String?)
        fun onSpaceLongPress()
    }

    companion object {
        const val CODE_SHIFT = -1
        const val CODE_SYMBOLS = -2
        const val CODE_LETTERS = -3
        const val CODE_ENTER = -4
        const val CODE_DELETE = -5
        const val CODE_MIC = -6
        const val CODE_SYMBOLS2 = -7
        const val CODE_TEXT = 0
        const val CODE_SPACE = 32

        private val ACCENTS = mapOf(
            "a" to listOf("á", "ã", "â", "à", "ä", "ª"),
            "e" to listOf("é", "ê", "è", "ë", "3"),
            "i" to listOf("í", "î", "ì", "ï", "8"),
            "o" to listOf("ó", "õ", "ô", "ò", "ö", "º", "9"),
            "u" to listOf("ú", "ü", "û", "ù", "7"),
            "c" to listOf("ç", "©"),
            "n" to listOf("ñ"),
            "q" to listOf("1"), "w" to listOf("2"), "r" to listOf("4"), "t" to listOf("5"),
            "y" to listOf("6", "ý"), "p" to listOf("0"),
            "s" to listOf("ß", "$"),
            "." to listOf("!", "?", ",", ";", ":", "…", "-", "'", "\""),
            "," to listOf("!", "?", ";", ":", "\"", "'"),
            "-" to listOf("_", "–", "—"),
            "R$" to listOf("$", "€", "£", "¢"),
        )
    }

    enum class Shift { OFF, ON, LOCK }
    enum class Mode { LETTERS, SYMBOLS, SYMBOLS2 }

    class Key(
        val label: String,
        val code: Int,
        val text: String? = null,
        val weight: Float = 1f,
        val special: Boolean = false,
    ) {
        val rect = RectF()
    }

    private val dp = context.resources.displayMetrics.density
    private fun px(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val keyHeight = px(54f)
    private val gap = px(3f)
    private val pad = px(4f)

    private val bgColor = Color.parseColor("#17181C")
    private val keyColor = Color.parseColor("#3A3B43")
    private val specialColor = Color.parseColor("#2A2B31")
    private val pressedColor = Color.parseColor("#5B5D6B")
    private val accentColor = Color.parseColor("#7C3AED")
    private val micActiveColor = Color.parseColor("#E5484D")

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = px(22f)
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = px(15f)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9A9BA6"); textAlign = Paint.Align.RIGHT; textSize = px(10f)
    }

    var shift = Shift.OFF
        set(v) { field = v; invalidate() }
    var mode = Mode.LETTERS
        set(v) { field = v; rows = buildRows(); requestLayout(); invalidate() }
    var micActive = false
        set(v) { field = v; invalidate() }
    var enterLabel = "⏎"
        set(v) { field = v; rows = buildRows(); layoutKeys(); invalidate() }
    var hapticsEnabled = true

    private var rows: List<List<Key>> = buildRows()

    private fun letterRow(s: String) = s.split(" ").map { Key(it, CODE_TEXT, it) }

    private fun bottomRow(switchLabel: String, switchCode: Int) = listOf(
        Key(switchLabel, switchCode, weight = 1.5f, special = true),
        Key(",", CODE_TEXT, ","),
        Key("🎤", CODE_MIC, special = true),
        Key("Português", CODE_SPACE, " ", weight = 4f),
        Key(".", CODE_TEXT, "."),
        Key(enterLabel, CODE_ENTER, weight = 1.5f, special = true),
    )

    private fun buildRows(): List<List<Key>> = when (mode) {
        Mode.LETTERS -> listOf(
            letterRow("q w e r t y u i o p"),
            letterRow("a s d f g h j k l ç"),
            listOf(Key("⇧", CODE_SHIFT, weight = 1.5f, special = true)) +
                letterRow("z x c v b n m") +
                listOf(Key("⌫", CODE_DELETE, weight = 1.5f, special = true)),
            bottomRow("?123", CODE_SYMBOLS),
        )
        Mode.SYMBOLS -> listOf(
            letterRow("1 2 3 4 5 6 7 8 9 0"),
            letterRow("@ # R$ _ & - + ( ) /"),
            listOf(Key("=\\<", CODE_SYMBOLS2, weight = 1.5f, special = true)) +
                letterRow("* \" ' : ; ! ?") +
                listOf(Key("⌫", CODE_DELETE, weight = 1.5f, special = true)),
            bottomRow("ABC", CODE_LETTERS),
        )
        Mode.SYMBOLS2 -> listOf(
            letterRow("~ ` | • √ π ÷ × ¶ ∆"),
            letterRow("€ £ ¥ ^ ° = { } [ ]"),
            listOf(Key("?123", CODE_SYMBOLS, weight = 1.5f, special = true)) +
                letterRow("\\ % © ® ™ < >") +
                listOf(Key("⌫", CODE_DELETE, weight = 1.5f, special = true)),
            bottomRow("ABC", CODE_LETTERS),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (pad * 2 + rows.size * keyHeight).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layoutKeys() }

    private fun layoutKeys() {
        val w = width.toFloat()
        if (w <= 0) return
        var y = pad
        for (row in rows) {
            val total = 10f
            val unit = (w - pad * 2) / total
            val rowWidth = row.sumOf { it.weight.toDouble() }.toFloat() * unit
            var x = pad + ((w - pad * 2) - rowWidth) / 2f
            for (k in row) {
                val kw = k.weight * unit
                k.rect.set(x + gap, y + gap, x + kw - gap, y + keyHeight - gap)
                x += kw
            }
            y += keyHeight
        }
    }

    // ---------------- drawing ----------------

    private fun displayLabel(k: Key): String {
        if (k.code != CODE_TEXT || mode != Mode.LETTERS) return k.label
        return if (shift != Shift.OFF) k.label.uppercase(Locale("pt", "BR")) else k.label
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val radius = px(6f)
        for (row in rows) for (k in row) {
            keyPaint.color = when {
                k === pressedKey || k === repeatKey -> pressedColor
                k.code == CODE_MIC && micActive -> micActiveColor
                k.code == CODE_SHIFT && shift == Shift.LOCK -> accentColor
                k.code == CODE_ENTER -> accentColor
                k.special -> specialColor
                else -> keyColor
            }
            canvas.drawRoundRect(k.rect, radius, radius, keyPaint)
            val label = displayLabel(k)
            val p = if (label.length > 2 || k.code == CODE_SPACE) smallText else textPaint
            if (k.code == CODE_SHIFT && shift == Shift.ON) {
                p.color = Color.parseColor("#B794F6")
            }
            val ty = k.rect.centerY() - (p.descent() + p.ascent()) / 2
            canvas.drawText(if (k.code == CODE_SPACE) "Português (BR)" else label, k.rect.centerX(), ty, p)
            p.color = Color.WHITE
            if (mode == Mode.LETTERS && k.code == CODE_TEXT) {
                val hint = ACCENTS[k.label]?.lastOrNull { it.length == 1 && it[0].isDigit() }
                if (hint != null) canvas.drawText(hint, k.rect.right - px(4f), k.rect.top + px(12f), hintPaint)
            }
        }
        drawPopup(canvas)
    }

    // ---------------- long-press popup ----------------

    private var popupOptions: List<String> = emptyList()
    private var popupSelected = 0
    private val popupRect = RectF()
    private var popupCellW = 0f
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun showPopup(k: Key) {
        val base = ACCENTS[k.label] ?: return
        val upper = shift != Shift.OFF && mode == Mode.LETTERS
        popupOptions = base.map { if (upper) it.uppercase(Locale("pt", "BR")) else it }
        popupSelected = 0
        popupCellW = k.rect.width().coerceAtLeast(px(34f))
        val total = popupCellW * popupOptions.size
        var left = k.rect.left
        if (left + total > width - pad) left = (width - pad - total).coerceAtLeast(pad)
        val top = (k.rect.top - keyHeight).coerceAtLeast(0f)
        popupRect.set(left, top, left + total, top + keyHeight - gap)
        invalidate()
    }

    private fun drawPopup(canvas: Canvas) {
        if (popupOptions.isEmpty()) return
        popupPaint.color = Color.parseColor("#F2F2F5")
        canvas.drawRoundRect(popupRect, px(8f), px(8f), popupPaint)
        for ((i, opt) in popupOptions.withIndex()) {
            val l = popupRect.left + i * popupCellW
            if (i == popupSelected) {
                popupPaint.color = accentColor
                canvas.drawRoundRect(RectF(l + gap, popupRect.top + gap, l + popupCellW - gap, popupRect.bottom - gap), px(6f), px(6f), popupPaint)
            }
            textPaint.color = if (i == popupSelected) Color.WHITE else Color.parseColor("#17181C")
            val ty = popupRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(opt, l + popupCellW / 2, ty, textPaint)
        }
        textPaint.color = Color.WHITE
    }

    // ---------------- touch ----------------

    private val handler = Handler(Looper.getMainLooper())
    private var pressedKey: Key? = null
    private var pressedPointer = -1
    private var longPressFired = false
    private var repeatKey: Key? = null
    private var lastShiftTap = 0L

    private val longPress = Runnable {
        val k = pressedKey ?: return@Runnable
        if (k.code == CODE_SPACE) {
            longPressFired = true
            listener.onSpaceLongPress()
            pressedKey = null
            invalidate()
            return@Runnable
        }
        if (ACCENTS.containsKey(k.label)) {
            longPressFired = true
            showPopup(k)
            haptic()
        }
    }

    private val repeat = object : Runnable {
        override fun run() {
            val k = repeatKey ?: return
            listener.onKey(k.code, k.text)
            handler.postDelayed(this, 50)
        }
    }

    private fun keyAt(x: Float, y: Float): Key? {
        for (row in rows) for (k in row) {
            if (x >= k.rect.left - gap && x <= k.rect.right + gap && y >= k.rect.top - gap && y <= k.rect.bottom + gap) return k
        }
        return null
    }

    private fun haptic() {
        if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // fast typing: a second finger commits the first key immediately
                if (pressedKey != null && !longPressFired) commitPressed()
                val idx = e.actionIndex
                val k = keyAt(e.getX(idx), e.getY(idx)) ?: return true
                pressedKey = k
                pressedPointer = e.getPointerId(idx)
                longPressFired = false
                haptic()
                if (k.code == CODE_DELETE) {
                    listener.onKey(k.code, null)
                    repeatKey = k
                    handler.postDelayed(repeat, 400)
                } else {
                    handler.postDelayed(longPress, 380)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (popupOptions.isNotEmpty()) {
                    val idx = e.findPointerIndex(pressedPointer)
                    if (idx >= 0) {
                        val x = e.getX(idx)
                        val sel = ((x - popupRect.left) / popupCellW).toInt().coerceIn(0, popupOptions.size - 1)
                        if (sel != popupSelected) { popupSelected = sel; invalidate() }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == pressedPointer) {
                    handler.removeCallbacks(longPress)
                    handler.removeCallbacks(repeat)
                    if (popupOptions.isNotEmpty()) {
                        val opt = popupOptions[popupSelected]
                        popupOptions = emptyList()
                        listener.onKey(CODE_TEXT, opt)
                    } else if (!longPressFired && repeatKey == null) {
                        commitPressed()
                    }
                    pressedKey = null
                    repeatKey = null
                    pressedPointer = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(repeat)
                popupOptions = emptyList()
                pressedKey = null; repeatKey = null; pressedPointer = -1
                invalidate()
            }
        }
        return true
    }

    private fun commitPressed() {
        val k = pressedKey ?: return
        handler.removeCallbacks(longPress)
        pressedKey = null
        when (k.code) {
            CODE_SHIFT -> {
                val now = System.currentTimeMillis()
                shift = when {
                    shift == Shift.OFF && now - lastShiftTap < 350 -> Shift.LOCK
                    shift == Shift.ON && now - lastShiftTap < 350 -> Shift.LOCK
                    shift == Shift.OFF -> Shift.ON
                    else -> Shift.OFF
                }
                lastShiftTap = now
                listener.onKey(k.code, null)
            }
            CODE_SYMBOLS -> mode = Mode.SYMBOLS
            CODE_SYMBOLS2 -> mode = Mode.SYMBOLS2
            CODE_LETTERS -> mode = Mode.LETTERS
            CODE_TEXT -> {
                val t = if (mode == Mode.LETTERS && shift != Shift.OFF) k.text?.uppercase(Locale("pt", "BR")) else k.text
                listener.onKey(CODE_TEXT, t)
            }
            else -> listener.onKey(k.code, k.text)
        }
    }
}
