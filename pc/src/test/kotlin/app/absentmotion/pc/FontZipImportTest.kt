package app.absentmotion.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import app.absentmotion.fonts.FontLibrary
import app.absentmotion.fonts.FontProblem
import app.absentmotion.fonts.MAX_FONT_BYTES
import app.absentmotion.platform.Context
import app.absentmotion.ui.editor.FontBrowser
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class FontZipImportTest {
    private val root = File("build/font-zip-data").also { it.deleteRecursively(); it.mkdirs() }
    init { System.setProperty("am.data", root.absolutePath) }
    private val font = File("../shared/assets/fonts").walk().first { it.isFile && it.extension == "ttf" }.readBytes()
    private val shots = File("/private/tmp/claude-501/-Users-jd/44da8514-9de8-4c0b-acc3-89282ac7865b/scratchpad/r519/b").also { it.mkdirs() }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>) = File(root, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> for ((entry, bytes) in entries) { zip.putNextEntry(ZipEntry(entry)); zip.write(bytes); zip.closeEntry() } }
    }

    @Test fun aZipImportsEveryFontInsideAndReportsTheRest() {
        val library = FontLibrary(Context())
        val second = font + byteArrayOf(1, 2, 3)
        val file = zip("two.zip", "Pack/Fonts/First.ttf" to font, "Pack/Second.otf" to second, "Pack/notes.txt" to "x".toByteArray(), "__MACOSX/._First.ttf" to font, "Pack/Broken.ttf" to ByteArray(200))
        val result = runBlocking { library.importAll(android.net.Uri.fromFile(file)) }
        assertEquals(2, result.fonts.size)
        assertEquals(listOf("Broken.ttf" to FontProblem.NotFont), result.failures)
        assertEquals(2, library.importedFonts().size)
        result.fonts.forEach { assertTrue(library.localFile(it.xml) != null) }
        val again = runBlocking { library.importAll(android.net.Uri.fromFile(file)) }
        assertEquals(0, again.fonts.size)
        assertTrue(again.failures.any { it.second == FontProblem.AlreadyImported })
    }

    @Test fun aZipWithNoFontsAndAnOversizedEntryGiveClearProblems() {
        val library = FontLibrary(Context())
        assertEquals(listOf("empty.zip" to FontProblem.NoFonts), runBlocking { library.importAll(android.net.Uri.fromFile(zip("empty.zip", "a.txt" to ByteArray(5)))) }.failures)
        assertEquals(listOf("big.zip" to FontProblem.ZipTooBig), runBlocking { library.importAll(android.net.Uri.fromFile(zip("big.zip", "Big.ttf" to ByteArray(MAX_FONT_BYTES + 1)))) }.failures)
    }

    @Test fun theDaFontRowEndsTheListAndOpensTheSearch() {
        val library = FontLibrary(Context())
        val opened = mutableListOf<String>()
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        for ((name, w, h, wide) in listOf(Triple4("phone", 360, 740, false), Triple4("desktop", 1280, 800, true))) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            opened.clear()
            setContent {
                CompositionLocalProvider(LocalWide provides wide, LocalUriHandler provides handler) {
                    Box(Modifier.fillMaxSize().background(Am.HomeBg)) { FontBrowser(library, {}, {}, { emptyList() }, emptyList()) }
                }
            }
            toRow()
            onNodeWithText("Download a font, then import the .zip here.", substring = true).assertExists()
            onNodeWithText("Find more fonts on DaFont").performClick()
            assertEquals(listOf("https://www.dafont.com/"), opened)
            onNodeWithContentDescription("Search").performClick()
            onNode(hasSetTextAction()).performTextInput("comic neue")
            waitForIdle()
            toRow()
            onNodeWithText("Find more fonts on DaFont").performClick()
            assertEquals("https://www.dafont.com/search.php?q=comic+neue", opened.last())
            waitForIdle()
            mainClock.advanceTimeBy(1000)
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(shots, "dafont-$name.png"))
        }
    }

    private fun ComposeUiTest.toRow() {
        onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Find more fonts on DaFont"))
        waitForIdle()
    }

    private data class Triple4(val a: String, val b: Int, val c: Int, val d: Boolean)
}
