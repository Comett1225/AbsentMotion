package app.absentmotion.audio

import app.absentmotion.ui.editor.matchesSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SoundLibraryTest {
    private val names = soundCollator(Locale.ENGLISH)
    private fun sound(title: String, date: Long = 0, ms: Long = 1000, album: String = "", artist: String = "", folder: String = "", name: String = "$title.mp3") =
        DeviceAudio("content://media/$title/$date/$ms", title, ms, date, name, album, artist, folder)

    private val library = listOf(
        sound("beta", 3, 9000, "Zed", "Mia", "Music/Rock"),
        sound("Alpha", 1, 2000, "Zed", "", "Music/Rock"),
        sound("Éclair", 2, 5000, "", "Bo", "Download"),
        sound("alpha", 4, 2000, "Air", "Mia", "Music/Rock"),
    )

    @Test fun nameSortIgnoresCaseAndAccentsAndCanFlip() {
        val az = library.sortedSounds(SoundView(SoundSort.Name, descending = false), names).map { it.title }
        assertEquals(listOf("Alpha", "alpha", "beta", "Éclair"), az)
        val za = library.sortedSounds(SoundView(SoundSort.Name, descending = true), names).map { it.title }
        assertEquals(listOf("Éclair", "beta"), za.take(2))
    }

    @Test fun numbersInNamesSortByValue() {
        val tracks = listOf("Track 10", "track 2", "10-minutes", "2nd", "Track 1", "Track 02b").map { sound(it) }
        assertEquals(listOf("2nd", "10-minutes", "Track 1", "track 2", "Track 02b", "Track 10"),
            tracks.sortedSounds(SoundView(SoundSort.Name, false), names).map { it.title })
    }

    @Test fun dateAndDurationSortBothWays() {
        assertEquals(listOf(4L, 3L, 2L, 1L), library.sortedSounds(SoundView(SoundSort.Added, true), names).map { it.date })
        assertEquals(listOf(1L, 2L, 3L, 4L), library.sortedSounds(SoundView(SoundSort.Added, false), names).map { it.date })
        val shortest = library.sortedSounds(SoundView(SoundSort.Length, false), names).map { it.title }
        assertEquals(listOf("Alpha", "alpha", "Éclair", "beta"), shortest)
        assertEquals("beta", library.sortedSounds(SoundView(SoundSort.Length, true), names).first().title)
    }

    @Test fun shelvesAreSortedWithUnknownLast() {
        val albums = library.shelves(SoundView(SoundSort.Name, false, SoundGroup.Albums), names)
        assertEquals(listOf("Air", "Zed", ""), albums.map { it.key })
        assertEquals(listOf("Alpha", "beta"), albums[1].sounds.map { it.title })
        val artists = library.shelves(SoundView(SoundSort.Name, true, SoundGroup.Artists), names)
        assertEquals(listOf("Mia", "Bo", ""), artists.map { it.key })
        val folders = library.shelves(SoundView(SoundSort.Added, true, SoundGroup.Folders), names)
        assertEquals(listOf("Download", "Rock"), folders.map { it.label })
        assertEquals(listOf(4L, 3L, 1L), folders[1].sounds.map { it.date })
    }

    @Test fun fiveThousandTracksSortQuickly() {
        val many = List(5000) { sound("Track ${(it * 7919) % 5000}", it.toLong(), (it % 300) * 1000L, "Album ${it % 40}", "Artist ${it % 90}", "Music/F${it % 25}") }
        val started = System.nanoTime()
        val sorted = many.sortedSounds(SoundView(SoundSort.Name, false), names)
        val shelves = many.shelves(SoundView(SoundSort.Length, true, SoundGroup.Artists), names)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(5000, sorted.size)
        assertEquals(90, shelves.size)
        assertEquals(5000, shelves.sumOf { it.sounds.size })
        assertTrue("took $millis ms", millis < 2000)
    }

    @Test fun searchMatchesTitleArtistAndFileName() {
        fun find(query: String) = library.filter { matchesSearch(query, it.title, it.artist, it.name, it.album) }.map { it.title }
        assertEquals(listOf("Éclair"), find("eclair"))
        assertEquals(listOf("beta", "alpha"), find("mia"))
        assertEquals(listOf("beta"), find("beta.mp3"))
        assertEquals(4, find("").size)
    }

    @Test fun viewSurvivesSavingAndBadText() {
        val view = SoundView(SoundSort.Length, false, SoundGroup.Folders)
        assertEquals(view, SoundView.parse(view.text))
        assertEquals(SoundView(), SoundView.parse("nonsense"))
        assertEquals(SoundView(), SoundView.parse(null))
    }
}
