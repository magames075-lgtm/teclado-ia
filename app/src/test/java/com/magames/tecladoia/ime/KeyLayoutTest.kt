package com.magames.tecladoia.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLayoutTest {
    private val width = 1080f
    private val keyH = 162f   // 54dp @ 3x
    private val gap = 9f
    private val pad = 12f

    private fun page(mode: KbMode, enter: String = "⏎") =
        KeyLayout.build(mode, enter).also { assertTrue(KeyLayout.layout(it, width, keyH, gap, pad)) }

    @Test fun everyPageIsLaidOutAndTappable() {
        for (mode in KbMode.values()) for (enter in listOf("⏎", "Enviar", "🔍", "")) {
            val rows = page(mode, enter)
            assertTrue("$mode/$enter invalid", KeyLayout.isValid(rows))
            assertTrue(KeyLayout.heightFor(rows, keyH, pad) > 0f)
            for (r in rows) for (k in r) {
                assertTrue("$mode key ${k.label} outside view", k.left >= 0f && k.right <= width)
                assertSame("$mode tap on ${k.label}", k, KeyLayout.keyAt(rows, k.centerX, k.centerY, gap))
            }
        }
    }

    /** The v0.2.0 bug: a freshly built page that is not laid out has 0x0 keys (black, untouchable). */
    @Test fun unlaidPageIsDetectedAsInvalid() {
        val rows = KeyLayout.build(KbMode.SYMBOLS, "⏎")
        assertFalse(KeyLayout.isValid(rows))
        assertEquals(null, KeyLayout.keyAt(rows, 500f, 300f, gap))
    }

    @Test fun pageSwitchKeysRoundTrip() {
        fun switchKey(mode: KbMode, code: Int) = page(mode).flatten().firstOrNull { it.code == code }
        assertNotNull("letters -> ?123", switchKey(KbMode.LETTERS, KeyLayout.CODE_SYMBOLS))
        assertNotNull("?123 -> =\\<", switchKey(KbMode.SYMBOLS, KeyLayout.CODE_SYMBOLS2))
        assertNotNull("?123 -> ABC", switchKey(KbMode.SYMBOLS, KeyLayout.CODE_LETTERS))
        assertNotNull("=\\< -> ?123", switchKey(KbMode.SYMBOLS2, KeyLayout.CODE_SYMBOLS))
        assertNotNull("=\\< -> ABC", switchKey(KbMode.SYMBOLS2, KeyLayout.CODE_LETTERS))
    }

    @Test fun symbolKeysCarryText() {
        for (mode in listOf(KbMode.SYMBOLS, KbMode.SYMBOLS2)) for (k in page(mode).flatten()) {
            if (k.code == KeyLayout.CODE_TEXT) assertTrue("${k.label} has no text", !k.text.isNullOrEmpty())
        }
    }

    @Test fun zeroWidthDoesNotLayout() {
        val rows = KeyLayout.build(KbMode.LETTERS, "⏎")
        assertFalse(KeyLayout.layout(rows, 0f, keyH, gap, pad))
    }
}
