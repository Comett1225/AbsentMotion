package app.absentmotion.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EffectSearchTest {
    private val catalog = EffectCatalog.parse { File("src/main/assets/effects", it).inputStream() }

    @Test fun namesComeFirstThenDescriptions() {
        assertEquals("Echo Keyframes", catalog.search("echo").first().name)
        assertEquals("Echo Keyframes", catalog.search("ECHO keyframes").single().name)
        val copies = catalog.search("copies").map { it.name }
        assertTrue(copies.toString(), "Echo Keyframes" in copies && "Repeat" in copies && "Grid Repeat" in copies)
        val glow = catalog.search("glow")
        assertTrue(glow.takeWhile { "glow" in it.name.lowercase() }.size == glow.count { "glow" in it.name.lowercase() })
        assertEquals(catalog.definitions.size, catalog.search("  ").size)
    }
}
