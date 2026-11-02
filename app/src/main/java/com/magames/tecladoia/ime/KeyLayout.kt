package com.magames.tecladoia.ime

/**
 * Pure-Kotlin keyboard layout (no android.* imports) so it can be unit tested on the JVM.
 *
 * Bug fixed in v0.2.1: switching pages (?123 / =\< / ABC) built brand-new [Key] objects whose
 * bounds were never computed (the view only laid keys out in onSizeChanged, which does not fire
 * when the page keeps the same height). Every key had a 0x0 rect, so the keyboard was drawn as
 * a plain dark background and no touch could hit a key. Rows are now always built *and* laid out
 * together via [KeyLayout.build] + [KeyLayout.layout].
 */
class Key(
    val label: String,
    val code: Int,
    val text: String? = null,
    val weight: Float = 1f,
    val special: Boolean = false,
) {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f

    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
    val isLaidOut get() = width > 0f && height > 0f

    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
    fun contains(x: Float, y: Float, slop: Float) =
        x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop
}

enum class KbMode { LETTERS, SYMBOLS, SYMBOLS2 }

object KeyLayout {
    const val CODE_SHIFT = -1
    const val CODE_SYMBOLS = -2
    const val CODE_LETTERS = -3
    const val CODE_ENTER = -4
    const val CODE_DELETE = -5
    const val CODE_MIC = -6
    const val CODE_SYMBOLS2 = -7
    const val CODE_TEXT = 0
    const val CODE_SPACE = 32

    private fun row(s: String) = s.split(" ").filter { it.isNotEmpty() }.map { Key(it, CODE_TEXT, it) }

    private fun bottomRow(switchLabel: String, switchCode: Int, enterLabel: String) = listOf(
        Key(switchLabel, switchCode, weight = 1.5f, special = true),
        Key(",", CODE_TEXT, ","),
        Key("🎤", CODE_MIC, special = true),
        Key("Português", CODE_SPACE, " ", weight = 4f),
        Key(".", CODE_TEXT, "."),
        Key(enterLabel.ifEmpty { "⏎" }, CODE_ENTER, weight = 1.5f, special = true),
    )

    fun build(mode: KbMode, enterLabel: String): List<List<Key>> = when (mode) {
        KbMode.LETTERS -> listOf(
            row("q w e r t y u i o p"),
            row("a s d f g h j k l ç"),
            listOf(Key("⇧", CODE_SHIFT, weight = 1.5f, special = true)) +
                row("z x c v b n m") +
                listOf(Key("⌫", CODE_DELETE, weight = 1.5f, special = true)),
            bottomRow("?123", CODE_SYMBOLS, enterLabel),
        )
        KbMode.SYMBOLS -> listOf(
            row("1 2 3 4 5 6 7 8 9 0"),
            row("@ # R$ _ & - + ( ) /"),
            listOf(Key("=\\<", CODE_SYMBOLS2, weight = 1.5f, special = true)) +
                row("* \" ' : ; ! ?") +
                listOf(Key("⌫", CODE_DELETE, weight = 1.5f, special = true)),
            bottomRow("ABC", CODE_LETTERS, enterLabel),
        )
        KbMode.SYMBOLS2 -> listOf(
            row("~ ` | • √ π ÷ × ¶ ∆"),
            row("€ £ ¥ ^ ° = { } [ ]"),
            listOf(Key("?123", CODE_SYMBOLS, weight = 1.5f, special = true)) +
                row("\\ % © ® ™ < >") +
                listOf(Key("⌫", CODE_DELETE, weight = 1.5f, special = true)),
            bottomRow("ABC", CODE_LETTERS, enterLabel),
        )
    }

    /** Height the keyboard needs for these rows. Never 0. */
    fun heightFor(rows: List<List<Key>>, keyHeight: Float, pad: Float): Float =
        pad * 2 + rows.size.coerceAtLeast(1) * keyHeight

    /**
     * Computes every key's bounds for the given view width. Returns false (and leaves keys
     * untouched) when the width is not known yet.
     */
    fun layout(rows: List<List<Key>>, width: Float, keyHeight: Float, gap: Float, pad: Float): Boolean {
        if (width <= 0f || rows.isEmpty()) return false
        val unit = (width - pad * 2) / 10f
        var y = pad
        for (r in rows) {
            val rowWidth = r.sumOf { it.weight.toDouble() }.toFloat() * unit
            var x = pad + ((width - pad * 2) - rowWidth) / 2f
            for (k in r) {
                val kw = k.weight * unit
                k.set(x + gap, y + gap, x + kw - gap, y + keyHeight - gap)
                x += kw
            }
            y += keyHeight
        }
        return true
    }

    /** True when every key has a label and a non-empty, laid-out area. */
    fun isValid(rows: List<List<Key>>): Boolean =
        rows.isNotEmpty() && rows.all { r -> r.isNotEmpty() && r.all { it.label.isNotEmpty() && it.isLaidOut } }

    fun keyAt(rows: List<List<Key>>, x: Float, y: Float, slop: Float): Key? {
        for (r in rows) for (k in r) if (k.contains(x, y, slop)) return k
        return null
    }
}
