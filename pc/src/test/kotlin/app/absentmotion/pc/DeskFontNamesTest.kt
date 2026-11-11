package app.absentmotion.pc

import app.absentmotion.desk.DeskAppState
import app.absentmotion.fonts.FontLibrary
import app.absentmotion.fonts.FontName
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskFontNamesTest {
    private val saved = System.getProperty("am.data")

    @After fun restore() {
        if (saved == null) System.clearProperty("am.data") else System.setProperty("am.data", saved)
    }

    @Test fun fontNamesWithSpacesAndOtherScriptsKeepTheirFamily() {
        assertEquals(FontName("imported", "My Font.ttf"), FontName.parse("imported?name=My Font.ttf"))
        assertEquals(FontName("imported", "Шрифт Bold.otf"), FontName.parse("imported?name=Шрифт Bold.otf"))
        assertEquals(FontName("googlefonts", "Open Sans", 700, true), FontName.parse("googlefonts?name=Open Sans&weight=700&italic=1"))
        assertEquals(FontName("imported", "100% Font.ttf"), FontName.parse("imported?name=100% Font.ttf"))
        assertEquals("My Font.ttf", android.net.Uri.parse("file:///tmp/My%20Font.ttf").lastPathSegment)
    }

    @Test fun severalImportedFontsWithSpacesCanBeUsedAndDeleted() {
        val root = File("build/font-names-data").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", root.absolutePath)
        val library = FontLibrary(DeskAppState().context)
        val source = File("../shared/assets/fonts").walk().first { it.isFile && it.extension == "ttf" }
        val picked = listOf("My Font", "Second Font Bold", "Третий шрифт").map { name ->
            File(root, "picked/$name.ttf").also { it.parentFile.mkdirs(); source.copyTo(it, overwrite = true); it.appendBytes(name.toByteArray()) }
        }
        val names = runBlocking { picked.map { library.import(android.net.Uri.fromFile(it)) } }
        assertEquals(picked.map { it.name }.toSet(), library.importedFonts().map { it.family }.toSet())
        for (font in library.importedFonts()) {
            assertNotNull(font.xml, library.localFile(font.xml))
            assertEquals(font, FontName.parse(font.xml))
        }
        names.forEach { library.deleteImported(it.xml) }
        assertTrue(library.importedFonts().toString(), library.importedFonts().isEmpty())
    }
}
