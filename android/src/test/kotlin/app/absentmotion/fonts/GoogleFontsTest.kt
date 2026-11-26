package app.absentmotion.fonts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoogleFontsTest {
    private val list = ")]}'\n" + """{"familyMetadataList":[
        {"family":"ABeeZee","category":"Sans Serif","fonts":["400","400i"]},
        {"family":"Lobster","category":"Display","fonts":["400"]},
        {"family":"Zilla Slab","category":"Serif","fonts":["300","500","500i","700"]},
        {"family":"Roboto","category":"Sans Serif","fonts":["400"]},
        {"family":"Dancing Script","category":"Handwriting","fonts":["500"]},
        {"category":"Serif","fonts":["400"]},
        {"family":"NoFonts"}]}"""

    private fun entry(family: String, category: Int = 0) = FontEntry(FontName("googlefonts", family), category, emptySet())

    @Test fun parsingStripsThePrefixAndToleratesMissingFields() {
        val families = GoogleFonts.parse(list)
        assertEquals(listOf("ABeeZee", "Lobster", "Zilla Slab", "Roboto", "Dancing Script", "NoFonts"), families.map { it.family })
        assertEquals(1, families[0].category)
        assertEquals(setOf(400), families[0].italics)
        assertEquals(setOf(300, 500, 700), families[2].weights)
        assertEquals(0, families.last().category)
        assertEquals(400, families.last().regular.weight)
        assertEquals(500, families[4].regular.weight)
        assertTrue(GoogleFonts.parse("not json").isEmpty())
        assertTrue(GoogleFonts.parse("").isEmpty())
    }

    @Test fun closestWeightToRegularIsPicked() {
        assertEquals(500, GoogleFamily("X", 1, setOf(300, 500, 700), emptySet()).regular.weight)
        assertEquals(300, GoogleFamily("X", 1, setOf(100, 300), emptySet()).regular.weight)
    }

    @Test fun aStyleGoogleLacksFallsBackToTheNearestOne() {
        val anton = GoogleFamily("Anton", 3, setOf(400), emptySet())
        assertEquals(FontName("googlefonts", "Anton", 400), anton.nearest(700, false))
        assertEquals(FontName("googlefonts", "Anton", 400), anton.nearest(400, true))
        val oswald = GoogleFamily("Oswald", 1, setOf(200, 300, 400, 500, 600, 700), emptySet())
        assertEquals(700, oswald.nearest(900, false)!!.weight)
        val lora = GoogleFamily("Lora", 2, setOf(400, 700), setOf(400, 700))
        assertEquals(FontName("googlefonts", "Lora", 700, true), lora.nearest(800, true))
        assertEquals(FontName("googlefonts", "Lora", 700), lora.nearest(600, false))
        assertEquals(FontName("googlefonts", "X", 400, true), GoogleFamily("X", 1, emptySet(), setOf(400)).nearest(700, false))
        assertEquals(null, GoogleFamily("X", 1, emptySet(), emptySet()).nearest(400, false))
    }

    @Test fun importedThenAppThenDeviceThenGoogleWithoutDuplicates() {
        val google = GoogleFonts.parse(list)
        val result = GoogleFonts.arrange(listOf(entry("Zilla.ttf", 100)), FontCatalog.entries.filter { it.name.family == "Roboto" }.take(1),
            listOf(entry("Dancing Script"), entry("Roboto")), google, "", FontSource.All)
        assertTrue(result.google.isEmpty())
        val searched = GoogleFonts.arrange(listOf(FontEntry(FontName("imported", "Zilla.ttf"), 100, emptySet())), FontCatalog.entries.filter { it.name.family == "Roboto" }.take(1),
            listOf(entry("Dancing Script"), entry("Roboto")), google, "zilla", FontSource.All)
        assertEquals(listOf("Zilla.ttf"), searched.local.map { it.name.family })
        assertEquals(listOf("Zilla Slab"), searched.google.map { it.family })
        val all = GoogleFonts.arrange(listOf(FontEntry(FontName("imported", "Rob.ttf"), 100, emptySet())), FontCatalog.entries.filter { it.name.family == "Roboto" }.take(1),
            listOf(entry("Dancing Script"), entry("Roboto")), google, "r", FontSource.All)
        assertEquals(listOf("Rob.ttf", "Roboto", "Dancing Script"), all.local.map { it.name.family })
        assertEquals(listOf("Lobster"), all.google.map { it.family })
        assertTrue(all.google.none { it.family == "Roboto" || it.family == "Dancing Script" })
    }

    @Test fun chipsShowOneSourceAtATime() {
        val google = GoogleFonts.parse(list)
        val imported = listOf(FontEntry(FontName("imported", "Lob.ttf"), 100, emptySet()))
        val app = FontCatalog.entries.filter { it.name.family == "Lobster" }.take(1)
        val device = listOf(entry("Lobster Two"))
        fun run(source: FontSource) = GoogleFonts.arrange(imported, app, device, google, "lob", source)
        assertEquals(listOf("Lob.ttf"), run(FontSource.Imported).local.map { it.name.family })
        assertTrue(run(FontSource.Imported).google.isEmpty())
        assertEquals(app.map { it.name.family }, run(FontSource.App).local.map { it.name.family })
        assertEquals(listOf("Lobster Two"), run(FontSource.Device).local.map { it.name.family })
        assertTrue(run(FontSource.Device).google.isEmpty())
        assertTrue(run(FontSource.Google).local.isEmpty())
        assertTrue(run(FontSource.All).google.isEmpty())
    }

    @Test fun offlineMeansNoGoogleSectionAndNothingIsSentFromTheQuery() {
        val result = GoogleFonts.arrange(emptyList(), FontCatalog.entries.take(3), emptyList(), emptyList(), FontCatalog.entries.first().name.family, FontSource.All)
        assertTrue(result.google.isEmpty())
        assertTrue(result.local.isNotEmpty())
    }

    @Test fun cacheIsReusedForSevenDaysThenRefreshedAndKeptOffline() {
        val cache = File.createTempFile("google-fonts", ".json").also { it.delete() }
        try {
            var fetches = 0
            val day = 24L * 60 * 60 * 1000
            val start = 1_000_000_000_000L
            assertEquals(6, GoogleFonts.load(cache, start) { fetches++; list }.size)
            assertEquals(1, fetches)
            assertEquals(6, GoogleFonts.load(cache, start + 6 * day) { fetches++; error("offline") }.size)
            assertEquals(1, fetches)
            assertEquals(6, GoogleFonts.load(cache, start + 8 * day) { fetches++; error("offline") }.size)
            assertEquals(2, fetches)
            assertEquals(1, GoogleFonts.load(cache, start + 9 * day) { fetches++; """{"familyMetadataList":[{"family":"Only","fonts":["400"]}]}""" }.size)
            assertEquals(3, fetches)
            assertTrue(GoogleFonts.load(File("/nonexistent/none.json"), start) { error("offline") }.isEmpty())
        } finally { cache.delete() }
    }
}
