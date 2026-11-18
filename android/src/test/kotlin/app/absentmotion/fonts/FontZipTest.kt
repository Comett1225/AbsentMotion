package app.absentmotion.fonts

import app.absentmotion.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FontZipTest {
    private val font = File(TestData.project, "shared/assets/fonts/AbrilFatface/AbrilFatface-Regular.ttf").readBytes()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> for ((name, bytes) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        return out.toByteArray()
    }

    private fun problem(bytes: ByteArray): FontProblem? = try { zipFonts(bytes.inputStream()); null } catch (e: FontImportError) { e.problem }

    @Test fun fontsAtAnyDepthAreFoundAndJunkIsSkipped() {
        val found = zipFonts(zip(
            "Pack/Fonts/One.ttf" to font, "Two.OTF" to font, "readme.txt" to "hi".toByteArray(), "__MACOSX/Pack/._One.ttf" to font,
            "Pack/.hidden.ttf" to font, "a/b/c/Three.ttc" to font, "Pack/" to ByteArray(0),
        ).inputStream())
        assertEquals(listOf("One.ttf", "Two.OTF", "Three.ttc"), found.map { it.first })
        assertTrue(found.all { it.second.contentEquals(font) })
    }

    @Test fun aZipWithoutFontsGivesAnEmptyList() {
        assertEquals(emptyList<Any>(), zipFonts(zip("readme.txt" to "hi".toByteArray(), "img/a.png" to ByteArray(10)).inputStream()))
    }

    @Test fun anOversizedEntryAndTooManyFontsAreRefused() {
        assertEquals(FontProblem.ZipTooBig, problem(zip("Big.ttf" to ByteArray(MAX_FONT_BYTES + 1))))
        assertEquals(FontProblem.ZipTooBig, problem(zip("a.bin" to ByteArray(MAX_FONT_BYTES), "b.bin" to ByteArray(MAX_ZIP_BYTES - MAX_FONT_BYTES + 1))))
        assertEquals(FontProblem.ZipTooBig, problem(zip(*Array(MAX_ZIP_FONTS + 1) { "f$it.ttf" to ByteArray(10) })))
        assertEquals(null, problem(zip(*Array(MAX_ZIP_FONTS) { "f$it.ttf" to ByteArray(10) })))
    }

    @Test fun aBrokenZipIsCorrupted() {
        assertEquals(FontProblem.Corrupted, problem(zip("a.ttf" to font).copyOf(40)))
    }

    @Test fun zipsAreRecognisedByTheirHeader() {
        assertTrue(isZip(zip("a.ttf" to font)))
        assertTrue(!isZip(font))
    }

    @Test fun theDaFontLinkFollowsTheSearchText() {
        assertEquals("https://www.dafont.com/", daFontUrl(null))
        assertEquals("https://www.dafont.com/", daFontUrl("  "))
        assertEquals("https://www.dafont.com/search.php?q=comic+neue", daFontUrl(" comic neue "))
        assertEquals("https://www.dafont.com/search.php?q=a%26b%3Dc+%C3%A9", daFontUrl("a&b=c é"))
    }
}
