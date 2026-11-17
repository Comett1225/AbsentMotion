package app.absentmotion.pc

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.fonts.FontName
import app.absentmotion.library.AppSettings
import app.absentmotion.ui.theme.Accents
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Palette
import app.absentmotion.ui.theme.UiFontTheme
import app.absentmotion.ui.theme.UiFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

@OptIn(ExperimentalTestApi::class)
class DeskAppearanceTest {
    private val fonts = File("../shared/assets/fonts")

    @org.junit.After fun restore() {
        System.setProperty("am.systemDark", "false")
        Am.palette = Palette.AbsentMotion
    }

    @Test fun accentAndFontAreSavedAndComeBackAfterARestart() {
        val dir = File("build/appearance-data").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", dir.absolutePath)
        System.setProperty("am.systemDark", "false")
        val app = DeskAppState()
        val settings = app.settings
        assertNull(settings.accent)
        assertNull(settings.uiFont)
        assertEquals(Palette.AbsentMotion, Am.palette)
        val blue = Accents.Presets[0]
        val inter = FontName("googlefonts", "Inter").xml
        settings.accent = blue
        settings.uiFont = inter
        assertEquals(Color(blue), Am.palette.Accent)
        assertEquals(Color(blue), Am.palette.HomeAccent)
        settings.theme = "Daylight"
        assertEquals(Palette.Daylight.TopBar, Am.palette.TopBar)
        assertEquals(Color(blue), Am.palette.Accent)
        val stored = Properties().apply { File(dir, "prefs/settings.properties").inputStream().use(::load) }
        assertEquals(blue, stored.getProperty("accent").toLong(16).toInt())
        assertEquals(inter, stored.getProperty("uiFont"))
        Am.palette = Palette.AbsentMotion
        val again = AppSettings(app.context)
        assertEquals(blue, again.accent)
        assertEquals(inter, again.uiFont)
        assertEquals(Color(blue), Am.palette.Accent)
        again.accent = null
        again.uiFont = null
        assertEquals(Palette.Daylight, Am.palette)
    }

    @Test fun closingTheCustomPickerWithoutAChangeKeepsTheDefaultAccent() {
        val dir = File("build/appearance-picker").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", dir.absolutePath)
        val settings = DeskAppState().settings
        val before = Am.palette
        runSkikoComposeUiTest(size = Size(600f, 1000f)) {
            setContent { Column { app.absentmotion.ui.home.EditorSettings(settings) } }
            waitForIdle()
            onAllNodesWithText("Default").onFirst().performClick()
            waitForIdle()
            onNodeWithText("Custom…").performClick()
            waitForIdle()
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
        }
        assertNull(settings.accent)
        assertEquals(before, Am.palette)
    }

    private fun shot(wrap: Boolean, content: @Composable () -> Unit): IntArray {
        var pixels = IntArray(0)
        runSkikoComposeUiTest(size = Size(520f, 160f)) {
            setContent { if (wrap) UiFontTheme(null, content) else content() }
            waitForIdle()
            val image = onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
            pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        }
        return pixels
    }

    @Test fun theDefaultFontLooksExactlyLikeBefore() {
        val ui: @Composable () -> Unit = {
            Column {
                Text("Settings Привіт हिन्दी বাংলা العربية 日本語")
                TextButton({}) { Text("OK Cancel") }
            }
        }
        val before = shot(false, ui)
        assertTrue(before.isNotEmpty())
        assertTrue(before.contentEquals(shot(true, ui)))
    }

    @Test fun textFieldsUseTheChosenFontAndTheDefaultStaysIdentical() {
        val bangers = UiFonts.family(listOf(File(fonts, "Bangers/Bangers-Regular.ttf") to 400))!!
        val fields: @Composable () -> Unit = {
            Column {
                app.absentmotion.ui.editor.SearchField("Settings 1080", {}, Modifier.width(400.dp).height(40.dp), hint = "Search")
                app.absentmotion.ui.editor.DeskNameField("Project title", Modifier.width(400.dp)) {}
            }
        }
        fun layouts(family: FontFamily?): List<TextLayoutResult> {
            val found = ArrayList<TextLayoutResult>()
            runSkikoComposeUiTest(size = Size(520f, 160f)) {
                setContent { UiFontTheme(family, fields) }
                waitForIdle()
                onAllNodes(hasSetTextAction()).fetchSemanticsNodes().forEach { node -> node.config[SemanticsActions.GetTextLayoutResult].action!!(found) }
            }
            return found
        }
        val plain = layouts(null)
        val chosen = layouts(bangers)
        assertEquals(2, plain.size)
        assertEquals(2, chosen.size)
        plain.forEach { assertNull(it.layoutInput.style.fontFamily) }
        chosen.forEach { assertEquals(bangers, it.layoutInput.style.fontFamily) }
        plain.zip(chosen).forEach { (a, b) -> assertNotEquals(a.getLineRight(0), b.getLineRight(0)) }
        assertTrue(shot(false, fields).contentEquals(shot(true, fields)))
    }

    @Test fun everyTextFieldTakesTheAppFont() {
        val calls = listOf("../shared/kotlin", "src/standins").flatMap { File(it).walk().filter { file -> file.extension == "kt" }.toList() }.flatMap { file ->
            val text = file.readText()
            Regex("""\b(BasicTextField|OutlinedTextField)\(""").findAll(text).map { found ->
                var depth = 0
                val end = (found.range.last until text.length).first { i -> depth += when (text[i]) { '(' -> 1; ')' -> -1; else -> 0 }; depth == 0 }
                file.name to text.substring(found.range.first, end + 1)
            }.toList()
        }
        assertTrue(calls.size >= 25)
        calls.forEach { (name, call) ->
            val styled = call.contains(".withUiFont()") || call.contains("textStyle = Mixed")
            assertTrue("$name: $call", styled || call.startsWith("OutlinedTextField") && !call.contains("textStyle"))
        }
    }

    @Test fun charactersTheChosenFontLacksFallBackToTheDefault() {
        val bangers = UiFonts.family(UiFonts.files(FontName("googlefonts", "Bangers")) { File(fonts, "Bangers/Bangers-Regular.ttf").takeIf { it.isFile } })!!
        val hind = UiFonts.family(listOf(File(fonts, "Hind/Hind-Regular.ttf") to 400))!!
        val res = File("../shared/res")
        fun sample(folder: String) = Regex("<string name=\"desk_menu_previous_bookmark\">(.*?)</string>").find(File(res, "$folder/strings.xml").readText())!!.groupValues[1].filterNot { it.isWhitespace() }
        val lacking = listOf("values-hi", "values-bn", "values-ar", "values-fa", "values-ur", "values-th", "values-ja", "values-ko", "values-zh-rCN", "values-zh-rTW")
        val widths = HashMap<String, Pair<Int, Int>>()
        runSkikoComposeUiTest(size = Size(200f, 100f)) {
            setContent {
                val measurer = rememberTextMeasurer()
                fun width(text: String, family: FontFamily?) = measurer.measure(text, TextStyle(fontSize = 20.sp, fontFamily = family)).size.width
                for (folder in lacking + "values-uk") widths[folder] = width(sample(folder), null) to width(sample(folder), bangers)
                widths["latin"] = width("PreviousBookmark", null) to width("PreviousBookmark", bangers)
                widths["hind"] = width(sample("values-hi"), null) to width(sample("values-hi"), hind)
            }
            waitForIdle()
        }
        for (folder in lacking) {
            val (plain, chosen) = widths.getValue(folder)
            assertTrue("$folder renders", plain > 0)
            assertEquals("$folder falls back to the default font", plain, chosen)
        }
        assertTrue("Ukrainian still renders", widths.getValue("values-uk").second > 0)
        assertNotEquals("Bangers is used for Latin", widths.getValue("latin").first, widths.getValue("latin").second)
        assertNotEquals("a font with Devanagari keeps its own letters", widths.getValue("hind").first, widths.getValue("hind").second)
    }
}
