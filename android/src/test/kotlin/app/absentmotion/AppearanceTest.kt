package app.absentmotion

import androidx.compose.ui.graphics.Color
import app.absentmotion.fonts.FontName
import app.absentmotion.ui.theme.Accents
import app.absentmotion.ui.theme.Palette
import app.absentmotion.ui.theme.UiFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class AppearanceTest {
    private val dark = Color(0xFF17181B)
    private val accentKeys = setOf("Accent", "AccentLit", "AccentGlow", "AccentDeep", "OnAccent", "HomeAccent", "OnNavyAccent")

    @Test fun theDefaultAccentLeavesEveryPaletteExactlyAsItWas() {
        for ((_, palette) in Palette.BuiltIn) assertSame(palette, Accents.apply(palette, null))
    }

    @Test fun textOnTheAccentPicksBlackOrWhiteForContrast() {
        assertEquals(dark, Accents.on(Color(0xFFFFD65C)))
        assertEquals(dark, Accents.on(Color.White))
        assertEquals(Color.White, Accents.on(Color(0xFF1F2F8F)))
        assertEquals(Color.White, Accents.on(Color.Black))
        for (preset in Accents.Presets) {
            val c = Color(preset)
            val picked = Accents.on(c)
            val other = if (picked == dark) Color.White else dark
            assertTrue(Accents.contrast(c, picked) >= Accents.contrast(c, other))
            assertTrue("preset ${preset.toUInt().toString(16)} text contrast", Accents.contrast(c, picked) >= 4.5f)
        }
    }

    @Test fun aChosenAccentReachesEveryAccentColourInLightAndDark() {
        for ((name, palette) in Palette.BuiltIn) for (preset in Accents.Presets + 0xFFFFEE00.toInt() + 0xFF202060.toInt()) {
            val c = Color(preset)
            val themed = Accents.apply(palette, preset)
            assertEquals(c, themed.Accent)
            assertEquals(c, themed.HomeAccent)
            assertEquals(Accents.on(c), themed.OnAccent)
            val before = palette.toMap()
            for ((key, colour) in themed.toMap()) if (key !in accentKeys) assertEquals("$name $key", before[key], colour)
            for (key in listOf("AccentLit", "AccentGlow", "AccentDeep", "OnNavyAccent")) {
                val shade = themed.toMap().getValue(key)
                val old = before.getValue(key)
                fun light(x: Color) = (maxOf(x.red, x.green, x.blue) + minOf(x.red, x.green, x.blue)) / 2f
                assertTrue("$name $key keeps its lightness", abs(light(shade) - light(old)) < 0.02f)
            }
        }
    }

    @Test fun theUiFontFallsBackToTheDefaultWhenItsFileIsGone() {
        val regular = File("Inter-Regular.ttf")
        val bold = File("Inter-Bold.ttf")
        val files = mapOf(400 to regular, 700 to bold)
        assertEquals(emptyList<Pair<File, Int>>(), UiFonts.files(null) { regular })
        assertEquals(emptyList<Pair<File, Int>>(), UiFonts.files(FontName("imported", "gone.ttf")) { null })
        assertEquals(listOf(regular to 400, bold to 700), UiFonts.files(FontName("googlefonts", "Inter")) { files[it.weight] })
        assertEquals(listOf(regular to 400), UiFonts.files(FontName("googlefonts", "Bebas Neue")) { regular })
        assertEquals(listOf(regular to 400), UiFonts.files(FontName("imported", "mine.ttf")) { files[it.weight] })
        assertEquals(listOf(regular to 400), UiFonts.files(FontName("googlefonts", "Inter", 700, italic = true)) { if (it.weight == 400 && !it.italic) regular else null })
    }

    @Test fun everyBundledFontIsOfferedOnceAtRegularWeight() {
        val fonts = UiFonts.bundled()
        assertEquals(50, fonts.size)
        assertEquals(fonts.size, fonts.map { it.family }.toSet().size)
        assertTrue(fonts.all { it.source == "googlefonts" && it.weight == 400 && !it.italic })
        assertTrue(fonts.any { it.family == "Hind" } && fonts.any { it.family == "Inter" })
    }
}
