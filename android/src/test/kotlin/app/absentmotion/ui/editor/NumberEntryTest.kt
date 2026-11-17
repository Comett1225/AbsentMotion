package app.absentmotion.ui.editor

import app.absentmotion.ui.editor.panels.formatPlain
import app.absentmotion.ui.editor.panels.keypadChar
import app.absentmotion.ui.editor.panels.keypadNumber
import app.absentmotion.ui.editor.panels.keypadOutside
import app.absentmotion.ui.editor.panels.keypadRange
import app.absentmotion.ui.editor.panels.keypadValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberEntryTest {
    @Test fun thePadShowsWhatWasTypedWithoutFloatNoise() {
        assertEquals("500.7", formatPlain(500.7f))
        assertEquals("20", formatPlain(Math.toDegrees(Math.toRadians(20.0).toFloat().toDouble()).toFloat() + 0.000013f))
        assertEquals("0.25", formatPlain(0.25f))
        assertEquals("-12.5", formatPlain(-12.5f))
        assertEquals("0", formatPlain(-0.000001f))
        assertEquals("1080", formatPlain(1080f))
        assertEquals("0.00012", formatPlain(0.00012f))
    }

    @Test fun latinArabicAndPersianDigitsParse() {
        assertEquals(12.5f, keypadNumber("12.5"))
        assertEquals(12.5f, keypadNumber("\u0661\u0662\u066B\u0665"))
        assertEquals(12.5f, keypadNumber("\u06F1\u06F2\u066B\u06F5"))
        assertEquals(-3f, keypadNumber("\u2212\u06F3"))
        assertEquals(1234f, keypadNumber("\u0661\u066C\u0662\u0663\u0664"))
        assertEquals(7f, keypadNumber("\u200F7\u00A0"))
    }

    @Test fun bengaliDevanagariThaiAndFullWidthDigitsParse() {
        assertEquals(12.5f, keypadNumber("\u09E7\u09E8.\u09EB"))
        assertEquals(12.5f, keypadNumber("\u0967\u0968.\u096B"))
        assertEquals(12.5f, keypadNumber("\u0E51\u0E52.\u0E55"))
        assertEquals(12.5f, keypadNumber("\uFF11\uFF12.\uFF15"))
        assertEquals('7', keypadChar('\u09ED'))
    }

    @Test fun commaWorksAsTheDecimalPoint() {
        assertEquals(0.75f, keypadNumber("0,75"))
        assertEquals(-0.5f, keypadNumber("-,5"))
        assertNull(keypadNumber("1,2,3"))
        assertNull(keypadNumber("1.2.3"))
    }

    @Test fun badTextKeepsTheOldValue() {
        assertNull(keypadNumber(""))
        assertNull(keypadNumber("-"))
        assertNull(keypadNumber("5-"))
        assertNull(keypadNumber("abc"))
        assertNull(keypadNumber("1e9"))
        assertEquals(40f, keypadValue("x", 40f, 0f, 100f))
    }

    @Test fun typedValuesAreClampedToTheAllowedRange() {
        assertEquals(100f, keypadValue("250", 40f, 0f, 100f))
        assertEquals(0f, keypadValue("-5", 40f, 0f, 100f))
        assertEquals(-5000f, keypadValue("-5000", 0f, -Float.MAX_VALUE, Float.MAX_VALUE))
        assertTrue(keypadOutside("250", 0f, 100f))
        assertFalse(keypadOutside("50", 0f, 100f))
        assertFalse(keypadOutside("", 0f, 100f))
    }

    @Test fun rangeTextOnlyForRealLimits() {
        assertEquals("0\u2013100%", keypadRange(0f, 100f, "%"))
        assertEquals("-1.5\u20132.25", keypadRange(-1.5f, 2.25f, ""))
        assertNull(keypadRange(-Float.MAX_VALUE, Float.MAX_VALUE, ""))
        assertNull(keypadRange(0f, Float.MAX_VALUE, ""))
    }

    @Test fun hardwareKeysMapToKeypadKeys() {
        assertEquals('4', keypadChar('\u0664'))
        assertEquals('9', keypadChar('\u06F9'))
        assertEquals('.', keypadChar(','))
        assertEquals('-', keypadChar('\u2212'))
        assertNull(keypadChar('a'))
    }

    @Test fun typingNeedsASecondTapSoonAfterTheFirstOrAKeyPress() {
        val taps = app.absentmotion.ui.editor.panels.TapPair()
        fun tap(down: Long, up: Long): Boolean { taps.pressed(down); taps.released(up); return taps.second(300) }
        assertFalse(tap(1_000, 1_080))
        assertTrue(tap(1_250, 1_320))
        assertFalse(tap(1_400, 1_450))
        assertFalse(tap(2_000, 2_060))
        assertTrue(tap(2_300, 2_350))
        assertTrue(taps.second(300))
        assertFalse(tap(3_000, 3_050))
        assertTrue(taps.second(300))
        assertFalse(tap(3_100, 3_150))
        taps.pressed(3_200)
        taps.cancelled()
        assertTrue(taps.second(300))
        assertFalse(tap(3_400, 3_450))
    }
}
