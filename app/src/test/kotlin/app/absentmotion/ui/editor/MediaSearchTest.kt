package app.absentmotion.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSearchTest {
    private data class Item(val name: String, val album: String)

    private val library = listOf(
        Item("IMG_0001.jpg", "Camera"), Item("Screenshot_20261020.png", "Screenshots"), Item("clip.mp4", "WhatsApp Video"),
        Item("Café del Mar.mp3", "Chill Out"), Item("Beach_trip_2024.mp4", "Camera"),
    )

    private fun names(query: String) = library.filter { matchesSearch(query, it.name, it.album) }.map { it.name }

    @Test fun anEmptyQueryKeepsEverything() {
        assertEquals(library.size, names("").size)
        assertEquals(library.size, names("   ").size)
    }

    @Test fun everyWordHasToAppearInTheNameOrTheAlbum() {
        assertEquals(listOf("Beach_trip_2024.mp4"), names("beach 2024"))
        assertEquals(listOf("Beach_trip_2024.mp4"), names("camera beach"))
        assertEquals(listOf("IMG_0001.jpg", "Beach_trip_2024.mp4"), names("camera"))
        assertEquals(emptyList<String>(), names("beach 2023"))
    }

    @Test fun caseAndAccentsAreIgnored() {
        assertEquals(listOf("Café del Mar.mp3"), names("CAFE"))
        assertEquals(listOf("Screenshot_20261020.png"), names("  SCREEN  "))
        assertTrue(matchesSearch("café", "cafe"))
    }

    @Test fun albumsAndFileNamesBothMatch() {
        assertEquals(listOf("clip.mp4"), names("whatsapp"))
        assertEquals(listOf("clip.mp4"), names("clip"))
        assertFalse(matchesSearch("holiday", "IMG_0001.jpg", "Camera"))
    }
}
