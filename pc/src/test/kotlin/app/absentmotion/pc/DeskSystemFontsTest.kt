package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.fonts.FontLibrary
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskSystemFontsTest {
    @Test fun installedFontsAreListedByTheirRealFamilyAndFoundInEveryWeight() {
        val library = FontLibrary(Desk.context)
        val fonts = library.deviceFonts()
        println("installed families: ${fonts.size}: " + fonts.take(40).joinToString { it.family })
        if (System.getProperty("os.name").orEmpty().startsWith("Mac")) {
            assertTrue(fonts.any { it.family == "Arial" })
            assertTrue(fonts.none { it.family == "Arial Bold" || it.family.startsWith(".") })
            val regular = library.localFile("googlefonts?name=Arial&weight=400")
            val bold = library.localFile("googlefonts?name=Arial&weight=700")
            println("Arial 400 -> $regular, 700 -> $bold")
            assertTrue(regular != null && bold != null && regular != bold)
        }
    }
}
