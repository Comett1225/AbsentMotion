package app.absentmotion.ui.home

import app.absentmotion.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    @Test
    fun readsTitleIntroSectionsAndItems() {
        val notes = WhatsNew.parse(
            """
            # What's new in 9.9

            First intro line
            goes on here.

            Second paragraph.

            ## New features
            - **One** - first
            - **Two** - second
              wraps onto a new line

            ## Empty

            ## Bug fixes
            * fixed a thing
            """.trimIndent(),
        )
        assertEquals("What's new in 9.9", notes.title)
        assertEquals(listOf("First intro line goes on here.", "Second paragraph."), notes.intro)
        assertEquals(listOf("New features", "Bug fixes"), notes.sections.map { it.title })
        assertEquals(listOf("**One** - first", "**Two** - second wraps onto a new line"), notes.sections[0].items)
        assertEquals(listOf("fixed a thing"), notes.sections[1].items)
    }

    @Test
    fun versionKeyIgnoresSuffixes() {
        assertEquals("5.0.0", WhatsNewNotes.key("5.0.0 pre"))
        assertEquals("4.4.1", WhatsNewNotes.key(" 4.4.1"))
        assertNull(WhatsNewNotes.key("pre"))
    }

    @Test
    fun shippedNotesParseAndUseKnownLinks() {
        val folder = TestData.project.resolve("shared/assets/whats-new")
        val files = folder.listFiles { file -> file.name.endsWith(".md") }.orEmpty()
        assertTrue(files.isNotEmpty())
        val link = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
        for (file in files) {
            assertNotNull(file.name, WhatsNewNotes.key(file.name.removeSuffix(".md")))
            val text = file.readText()
            val notes = WhatsNew.parse(text)
            assertTrue(file.name, notes.title.isNotBlank() && notes.sections.isNotEmpty())
            for (match in link.findAll(text)) assertNotNull(file.name + ": " + match.value, ReportLinks.BuiltIn.named(match.groupValues[2]))
        }
    }

    @Test fun aPatchVersionWithoutItsOwnNotesShowsTheReleaseNotesOnce() {
        val files = setOf("4.4.0", "5.0.0")
        assertEquals("5.0.0", WhatsNewNotes.notesFor("5.0.1", "4.4.1") { it in files })
        assertEquals(null, WhatsNewNotes.notesFor("5.0.1", "5.0.0") { it in files })
        assertEquals("5.0.0", WhatsNewNotes.notesFor("5.0.0", "4.4.1") { it in files })
        assertEquals(null, WhatsNewNotes.notesFor("5.1.0", "5.0.1") { it in files })
        assertEquals("5.0.2", WhatsNewNotes.notesFor("5.0.2", "5.0.1") { it in files + "5.0.2" })
    }
}
