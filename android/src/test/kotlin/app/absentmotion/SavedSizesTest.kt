package app.absentmotion

import app.absentmotion.library.SavedSize
import app.absentmotion.library.SavedSizes
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedSizesTest {
    @Test
    fun newestFirstAndNamesTrimmed() {
        val list = SavedSizes.with(SavedSizes.with(emptyList(), "A", 100, 50), "  Banner  ", 1080, 550)
        assertEquals(listOf(SavedSize("Banner", 1080, 550), SavedSize("A", 100, 50)), list)
    }

    @Test
    fun blankNameFallsBackToTheSize() {
        assertEquals("1080 × 550", SavedSizes.with(emptyList(), "   ", 1080, 550).single().name)
    }

    @Test
    fun sameSizeReplacesTheOldEntry() {
        val list = SavedSizes.with(SavedSizes.with(emptyList(), "Old", 1080, 550), "New", 1080, 550)
        assertEquals(listOf(SavedSize("New", 1080, 550)), list)
    }

    @Test
    fun capsAtTwentyDroppingTheOldest() {
        var list = emptyList<SavedSize>()
        for (i in 1..25) list = SavedSizes.with(list, "s$i", 100 + i, 100)
        assertEquals(SavedSizes.MAX, list.size)
        assertEquals("s25", list.first().name)
        assertEquals("s6", list.last().name)
    }

    @Test
    fun renameAndDelete() {
        val list = SavedSizes.with(SavedSizes.with(emptyList(), "A", 100, 50), "B", 200, 50)
        val a = list.last()
        assertEquals("Z", SavedSizes.renamed(list, a, " Z ").last().name)
        assertEquals(list, SavedSizes.renamed(list, a, "  "))
        assertEquals(listOf(list.first()), SavedSizes.without(list, a))
    }

    @Test
    fun roundTripsAndSurvivesGarbage() {
        val list = SavedSizes.with(emptyList(), "Quote \" and ünï", 1080, 550)
        assertEquals(list, SavedSizes.decode(SavedSizes.encode(list)))
        assertEquals(emptyList<SavedSize>(), SavedSizes.decode("not json"))
        assertEquals(emptyList<SavedSize>(), SavedSizes.decode(null))
        assertEquals(listOf(SavedSize("ok", 100, 200)), SavedSizes.decode("[{\"w\":1},{\"n\":\"ok\",\"w\":100,\"h\":200}]"))
    }
}
