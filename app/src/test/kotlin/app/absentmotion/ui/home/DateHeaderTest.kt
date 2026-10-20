package app.absentmotion.ui.home

import app.absentmotion.i18n.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class DateHeaderTest {
    private val day = LocalDate.of(2026, 10, 20)
    private fun header(tag: String) = day.format(dateHeader(Locale.forLanguageTag(tag)))

    @Test fun englishKeepsItsFormat() {
        assertEquals("20 October 2026", header("en"))
        assertEquals("20 October 2026", header("en-US"))
    }

    @Test fun otherLanguagesNameTheMonthInTheirOwnWordsWithWesternDigits() {
        for (language in AppLanguage.entries - AppLanguage.English) {
            val text = header(language.tag)
            assertTrue("${language.tag}: $text", "2026" in text && "20" in text)
            assertTrue("${language.tag}: $text", text.none { Character.isDigit(it) && it !in '0'..'9' })
            assertNotEquals(language.tag, header("en"), text)
        }
        assertTrue(header("ar").contains("أكتوبر") || header("ar").contains("تشرين"))
        assertTrue(header("ru").contains("октября"))
    }
}
