package com.magames.tecladoia.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
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
        const val CODE_SHIFT = KeyLayout.CODE_SHIFT
        const val CODE_SYMBOLS = KeyLayout.CODE_SYMBOLS
        const val CODE_LETTERS = KeyLayout.CODE_LETTERS
        const val CODE_ENTER = KeyLayout.CODE_ENTER
        const val CODE_DELETE = KeyLayout.CODE_DELETE
        const val CODE_MIC = KeyLayout.CODE_MIC
        const val CODE_SYMBOLS2 = KeyLayout.CODE_SYMBOLS2
        const val CODE_TEXT = KeyLayout.CODE_TEXT
        const val CODE_SPACE = KeyLayout.CODE_SPACE
        private const val TAG = "TecladoIA"

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

    /**
     * Changing the page MUST rebuild and re-lay-out the keys right away. Before v0.2.1 only
     * rebuild happened (layout ran in onSizeChanged, which does not fire because every page has
     * the same height), so "?123" showed a black keyboard with untouchable 0x0 keys.
     */
    var mode = Mode.LETTERS
        set(v) {
            val old = field
            field = v
            if (!applyRows()) {
                Log.e(TAG, "layout for $v failed, falling back to letters")
                field = if (v == Mode.LETTERS) old else Mode.LETTERS
                applyRows()
            }
        }
    var micActive = false
        set(v) { field = v; invalidate() }
    var enterLabel = "⏎"
        set(v) { field = v.ifEmpty { "⏎" }; applyRows() }
    var hapticsEnabled = true

    private var rows: List<List<Key>> = KeyLayout.build(KbMode.LETTERS, "⏎")

    private fun kbMode(m: Mode) = when (m) {
        Mode.LETTERS -> KbMode.LETTERS
        Mode.SYMBOLS -> KbMode.SYMBOLS
        Mode.SYMBOLS2 -> KbMode.SYMBOLS2
    }

    /**
     * Builds the rows for the current mode and lays them out immediately. Returns false when the
     * page could not be built/laid out (the previous rows, or letters, stay on screen).
     */
    private fun applyRows(): Boolean {
        val ok = try {
            val newRows = KeyLayout.build(kbMode(mode), enterLabel)
            val laidOut = KeyLayout.layout(newRows, width.toFloat(), keyHeight, gap, pad)
            if (laidOut && !KeyLayout.isValid(newRows)) {
                false
            } else {
                // not laid out yet (width 0): onSizeChanged / onDraw will lay it out
                popupOptions = emptyList()
                pressedKey = null
                repeatKey = null
                rows = newRows
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "building keyboard page failed", t)
            false
        }
        requestLayout()
        invalidate()
        return ok
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = KeyLayout.heightFor(rows, keyHeight, pad).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layoutKeys() }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (rows.any { r -> r.any { !it.isLaidOut } }) layoutKeys()
    }

    private fun layoutKeys() {
        try {
            KeyLayout.layout(rows, width.toFloat(), keyHeight, gap, pad)
        } catch (t: Throwable) {
            Log.e(TAG, "layoutKeys failed", t)
        }
    }

    // ---------------- drawing ----------------

    private fun displayLabel(k: Key): String {
        if (k.code != CODE_TEXT || mode != Mode.LETTERS) return k.label
        return if (shift != Shift.OFF) k.label.uppercase(Locale("pt", "BR")) else k.label
    }

    private val keyRect = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        // Safety net: never draw a page whose keys have no bounds (that is what looked "black").
        if (width > 0 && rows.any { r -> r.any { !it.isLaidOut } }) layoutKeys()
        try {
            drawKeys(canvas)
            drawPopup(canvas)
        } catch (t: Throwable) {
            Log.e(TAG, "draw failed on $mode", t)
            textPaint.color = Color.WHITE
            smallText.color = Color.WHITE
            if (mode != Mode.LETTERS) post { mode = Mode.LETTERS }
        }
    }

    private fun drawKeys(canvas: Canvas) {
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
            keyRect.set(k.left, k.top, k.right, k.bottom)
            canvas.drawRoundRect(keyRect, radius, radius, keyPaint)
            val label = displayLabel(k)
            val p = if (label.length > 2 || k.code == CODE_SPACE) smallText else textPaint
            p.color = if (k.code == CODE_SHIFT && shift == Shift.ON) Color.parseColor("#B794F6") else Color.WHITE
            val ty = k.centerY - (p.descent() + p.ascent()) / 2
            canvas.drawText(if (k.code == CODE_SPACE) "Português (BR)" else label, k.centerX, ty, p)
            p.color = Color.WHITE
            if (mode == Mode.LETTERS && k.code == CODE_TEXT) {
                val hint = ACCENTS[k.label]?.lastOrNull { it.length == 1 && it[0].isDigit() }
                if (hint != null) canvas.drawText(hint, k.right - px(4f), k.top + px(12f), hintPaint)
            }
        }
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
        if (popupOptions.isEmpty()) return
        popupCellW = k.width.coerceAtLeast(px(34f))
        val total = popupCellW * popupOptions.size
        var left = k.left
        if (left + total > width - pad) left = (width - pad - total).coerceAtLeast(pad)
        val top = (k.top - keyHeight).coerceAtLeast(0f)
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

    private fun keyAt(x: Float, y: Float): Key? = KeyLayout.keyAt(rows, x, y, gap)

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
                        val sel = if (popupCellW <= 0f) 0
                            else ((x - popupRect.left) / popupCellW).toInt().coerceIn(0, popupOptions.size - 1)
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
                        val opt = popupOptions.getOrNull(popupSelected) ?: popupOptions[0]
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
