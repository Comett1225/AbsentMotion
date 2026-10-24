package app.absentmotion.ui.editor

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
    @Test fun latinArabicAndPersianDigitsParse() {
        assertEquals(12.5f, keypadNumber("12.5"))
        assertEquals(12.5f, keypadNumber("\u0661\u0662\u066B\u0665"))
        assertEquals(12.5f, keypadNumber("\u06F1\u06F2\u066B\u06F5"))
        assertEquals(-3f, keypadNumber("\u2212\u06F3"))
        assertEquals(1234f, keypadNumber("\u0661\u066C\u0662\u0663\u0664"))
        assertEquals(7f, keypadNumber("\u200F7\u00A0"))
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
}
