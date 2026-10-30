package app.absentmotion

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.absentmotion.ui.theme.Palette
import app.absentmotion.ui.theme.ThemeFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ThemeTest {
    private fun rejected(text: String): String {
        try { ThemeFile.parse(text) } catch (e: IllegalArgumentException) { return e.message.orEmpty() }
        fail("accepted: $text")
        return ""
    }

    private fun assertOnlyChanged(palette: Palette, vararg changed: String) {
        val base = Palette.AbsentMotion.toMap()
        for ((key, colour) in palette.toMap()) {
            if (key in changed) assertNotEquals("$key should differ", base[key], colour) else assertEquals("$key should be the default", base[key], colour)
        }
    }

    @Test fun validFileSetsEveryColourItNames() {
        val all = Palette.Names.joinToString(",") { "\"$it\":\"#102030\"" }
        val theme = ThemeFile.parse("""{"name":"  Slate  ",$all}""")
        assertEquals("Slate", theme.name)
        assertTrue(theme.palette.toMap().values.all { it == Color(0xFF102030) })
    }

    @Test fun sixAndEightDigitColours() {
        val theme = ThemeFile.parse("""{"TopBar":"#FF8800","PressEditor":"#801A2B3C","Accent":"#abcdef"}""")
        assertEquals(Color(0xFFFF8800), theme.palette.TopBar)
        assertEquals(Color(0x801A2B3C), theme.palette.PressEditor)
        assertEquals(Color(0xFFABCDEF), theme.palette.Accent)
        assertNull(theme.name)
    }

    @Test fun aFileSavedWithAByteOrderMarkIsRead() {
        assertEquals(Color(0xFF010203), ThemeFile.parse("\uFEFF{\"TopBar\":\"#010203\"}").palette.TopBar)
    }

    @Test fun missingKeysTakeTheDefaultPalette() {
        val theme = ThemeFile.parse("""{"TopBar":"#010203","OnDark":"#040506"}""")
        assertEquals(Color(0xFF010203), theme.palette.TopBar)
        assertOnlyChanged(theme.palette, "TopBar", "OnDark")
    }

    @Test fun unknownKeysAreIgnored() {
        val theme = ThemeFile.parse("""{"name":"x","author":"me","Nope":"not a colour","Accent":"#123456","topbar":"#000000"}""")
        assertOnlyChanged(theme.palette, "Accent")
        assertEquals(Color(0xFF123456), theme.palette.Accent)
    }

    @Test fun badColoursRejectTheWholeFile() {
        for (bad in listOf("\"#12345\"", "\"#1234567\"", "\"#GGGGGG\"", "\"123456\"", "\"red\"", "\"#+12345\"", "\"\"", "123456", "null", "true", "[]", "{}")) {
            val message = rejected("""{"Accent":"#123456","TopBar":$bad}""")
            assertTrue("$bad: $message", "TopBar" in message)
        }
    }

    @Test fun notAThemeFileIsRejected() {
        rejected("")
        rejected("not json")
        rejected("[1,2,3]")
        rejected("""{"name":"only a name"}""")
        rejected("""{"Nope":"#000000","Other":"#ffffff"}""")
        rejected("""{"TopBar":"#000000"""")
    }

    @Test fun colourParserIsStrict() {
        assertEquals(Color(0xFF000000), ThemeFile.parseColour("#000000"))
        assertEquals(Color(0x00FFFFFF), ThemeFile.parseColour("#00FFFFFF"))
        assertNull(ThemeFile.parseColour("000000"))
        assertNull(ThemeFile.parseColour("#000"))
        assertNull(ThemeFile.parseColour(" #000000"))
        assertNull(ThemeFile.parseColour("#-00000"))
    }

    @Test fun namesAreTheFieldsAndAreUnique() {
        assertEquals(Palette.Names, Palette.AbsentMotion.toMap().keys.toList())
        assertEquals(Palette.Names.size, Palette.Names.toSet().size)
        assertTrue("name" !in Palette.Names)
    }

    @Test fun builtInPalettesAreListedAndDiffer() {
        assertEquals(listOf("AbsentMotion", "Daylight"), Palette.BuiltIn.map { it.first })
        assertNotEquals(Palette.AbsentMotion, Palette.Daylight)
        assertTrue(Palette.Daylight.TopBar.luminance() > 0.5f)
        assertTrue(Palette.AbsentMotion.TopBar.luminance() < 0.5f)
    }

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test fun builtInPalettesAreReadable() {
        for ((name, p) in Palette.BuiltIn) {
            val editorSurfaces = listOf(p.TopBar, p.PreviewBg, p.TimelineBg, p.Panel, p.PanelInset, p.Button, p.Field, p.Well)
            for (surface in editorSurfaces) {
                for ((label, text) in listOf("OnDark" to p.OnDark, "OnDarkDim" to p.OnDarkDim, "Caption" to p.Caption, "Accent" to p.Accent, "AccentGlow" to p.AccentGlow, "Warning" to p.Warning, "Cool" to p.Cool))
                    assertTrue("$name: $label on $surface is ${contrast(text, surface)}", contrast(text, surface) >= 4.5f)
                for ((label, graphic) in listOf("Faint" to p.Faint, "AccentLit" to p.AccentLit, "Gold" to p.Gold))
                    assertTrue("$name: $label on $surface is ${contrast(graphic, surface)}", contrast(graphic, surface) >= 3f)
            }
            for (surface in listOf(p.FieldHi, p.Chosen, p.KeyDigit, p.KeySymbol, p.Deepest, p.AccentDeep, p.CoolDeep))
                assertTrue("$name: OnDark on $surface", contrast(p.OnDark, surface) >= 4.5f)
            for (surface in listOf(p.HomeBg, p.Card, p.SheetBg, p.HomePanel, p.HomeDrawer)) {
                for ((label, text) in listOf("Navy" to p.Navy, "HomeInk" to p.HomeInk, "HomeMid" to p.HomeMid, "HomeDim" to p.HomeDim))
                    assertTrue("$name: $label on $surface is ${contrast(text, surface)}", contrast(text, surface) >= 4.5f)
                assertTrue("$name: HomeAccent on $surface", contrast(p.HomeAccent, surface) >= 3f)
            }
            assertTrue("$name: OnAccent on Accent", contrast(p.OnAccent, p.Accent) >= 4.5f)
            assertTrue("$name: OnNavy on Navy", contrast(p.OnNavy, p.Navy) >= 7f)
            assertTrue("$name: OnNavyAccent on Navy", contrast(p.OnNavyAccent, p.Navy) >= 4.5f)
            assertTrue("$name: OnNavy on NavyHi", contrast(p.OnNavy, p.NavyHi) >= 7f)
            assertTrue("$name: OnBar on LayerBar", contrast(p.OnBar, p.LayerBar) >= 4.5f)
            assertTrue("$name: OnBar on TextBar", contrast(p.OnBar, p.TextBar) >= 4.5f)
            assertTrue("$name: OnAudioBar on AudioBar", contrast(p.OnAudioBar, p.AudioBar) >= 4.5f)
            assertTrue("$name: OnScrim on Scrim", contrast(p.OnScrim, p.Scrim) >= 7f)
            assertFalse("$name: the Scrim must stay dark", p.Scrim.luminance() > 0.1f)
            assertFalse("$name: Navy must stay dark", p.Navy.luminance() > 0.1f)
        }
    }
}
