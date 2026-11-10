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
        assertTrue(notes.sections.all { it.groups.isEmpty() && it.count == it.items.size })
    }

    @Test
    fun groupsHoldTheirItemsAndAddUpToTheSectionCount() {
        val notes = WhatsNew.parse(
            """
            # Notes

            ## Bug fixes
            - loose fix
            ### Updates
            - **One** - first
            - **Two** - second
              wraps on
            ### Editor
            * third

            ## Only empty groups
            ### Nothing here

            ## Plain
            - last
            """.trimIndent(),
        )
        assertEquals(listOf("Bug fixes", "Plain"), notes.sections.map { it.title })
        val fixes = notes.sections[0]
        assertEquals(listOf("loose fix"), fixes.items)
        assertEquals(listOf("Updates", "Editor"), fixes.groups.map { it.title })
        assertEquals(listOf("**One** - first", "**Two** - second wraps on"), fixes.groups[0].items)
        assertEquals(listOf("third"), fixes.groups[1].items)
        assertEquals(4, fixes.count)
        assertEquals(1, notes.sections[1].count)
        assertTrue(notes.sections[1].groups.isEmpty())
    }

    @Test
    fun the503NotesListEveryFixInGroups() {
        val notes = WhatsNew.parse(TestData.project.resolve("shared/assets/whats-new/5.0.3.md").readText())
        val fixes = notes.sections.single { it.title == "Bug fixes" }
        assertTrue(fixes.items.isEmpty())
        assertEquals(listOf("Updates", "Phone and Android", "Editor", "Effects and rendering", "Audio", "Export, import and projects", "AI tools", "Home and tools", "Desktop"), fixes.groups.map { it.title })
        assertEquals(listOf(16, 12, 32, 16, 2, 17, 11, 8, 2), fixes.groups.map { it.items.size })
        assertEquals(116, fixes.count)
        assertEquals(3, notes.sections.single { it.title == "New features" }.count)
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
            assertTrue(file.name, '`' !in text)
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
