package app.absentmotion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpLanguageTest {
    private val root = "file:///android_asset/help/"
    private val have = setOf("help/ar/index.html", "help/ar/settings.html", "help/ar/effects/blur.html", "help/es/index.html")
    private val exists: (String) -> Boolean = { it in have }

    @Test fun englishAndMissingLanguagesUseTheEnglishRoot() {
        assertEquals(root + "index.html", helpUrl("en", "index.html") { true })
        assertEquals(root + "settings.html", helpUrl("tr", "settings.html") { false })
        assertEquals(root + "index.html", helpUrl("xx", "index.html") { true })
    }

    @Test fun aTranslatedPageIsChosenWhenItExists() {
        assertEquals(root + "ar/settings.html", helpUrl("ar", "settings.html", exists))
        assertEquals(root + "ar/effects/blur.html", helpUrl("ar", "effects/blur.html", exists))
        assertEquals(root + "ar/settings.html#language", helpUrl("ar", "settings.html#language", exists))
    }

    @Test fun aPageMissingFromTheLanguageFallsBackToEnglish() {
        assertEquals(root + "export.html", helpUrl("es", "export.html", exists))
        assertEquals(root + "effects/blur.html#x", helpUrl("es", "effects/blur.html#x", exists))
    }

    @Test fun unsafePagesAreReplacedByTheContents() {
        assertEquals(root + "ar/index.html", helpUrl("ar", "../../x.html") { true })
        assertEquals(root + "ar/index.html", helpUrl("ar", "/data/x.html") { true })
        assertEquals(root + "ar/index.html", helpUrl("ar", "https://example.invalid/") { true })
        assertEquals(root + "index.html", helpUrl("ar", "../../x.html") { false })
        assertEquals(root + "index.html", helpUrl("en", "%2e%2e/x.html") { true })
        listOf("../../x.html", "/data/x.html", "https://example.invalid/", "a/../../b.html").forEach { assertTrue(helpAllowed(helpUrl("ar", it) { true })) }
    }

    @Test fun theOpenPageSurvivesALanguageSwitch() {
        assertEquals("settings.html#language", helpPage(root + "ar/settings.html#language"))
        assertEquals("effects/blur.html", helpPage(root + "effects/blur.html"))
        assertEquals("effects/blur.html", helpPage(root + "pt-BR/effects/blur.html"))
        assertEquals("index.html", helpPage(root + "index.html"))
        assertNull(helpPage("https://example.invalid/"))
        assertNull(helpPage(null))
    }

    @Test fun switchingBackAndForthKeepsThePage() {
        val page = helpPage(helpUrl("ar", "settings.html", exists))!!
        assertEquals(root + "settings.html", helpUrl("en", page, exists))
        assertEquals(root + "es/index.html", helpUrl("es", helpPage(root + "ar/index.html")!!, exists))
    }

    @Test fun onlyHelpFilesAreAllowed() {
        assertTrue(helpAllowed(root + "ar/index.html"))
        assertFalse(helpAllowed(root + "../databases/x"))
        assertFalse(helpAllowed(root + "%2e%2e/x"))
        assertFalse(helpAllowed("file:///data/data/app/x"))
        assertFalse(helpAllowed("https://example.invalid/"))
    }
}
