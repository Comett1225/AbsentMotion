package app.absentmotion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolOrderTest {
    @Test fun emptyOrBlankRestoresDefaultOrder() {
        assertEquals(DefaultToolOrder, toolOrder(emptyList()))
        assertEquals(DefaultToolOrder, toolOrder(listOf("", "   ")))
    }

    @Test fun importerAppendsToAnOldSavedOrder() {
        val old = listOf("downloader", "enhance", "compress", "gif", "trim", "extract")
        assertEquals(old + "importer" + "send", toolOrder(old))
        val custom = listOf("importer", "send") + old
        assertEquals(custom, toolOrder(custom))
    }

    @Test fun unknownIdsAreDroppedAndMissingIdsAppendInDefaultOrder() {
        assertEquals(
            listOf("downloader", "enhance", "compress", "trim", "extract", "gif", "importer", "send"),
            toolOrder(listOf("unknown", "downloader", "future_tool", "enhance", "")),
        )
    }

    @Test fun duplicateIdsKeepFirstOccurrence() {
        assertEquals(
            listOf("gif", "trim", "compress", "extract", "downloader", "enhance", "importer", "send"),
            toolOrder(listOf("gif", "trim", "gif", "compress", "trim")),
        )
    }

    @Test fun movedToolOrderSwapsWithinBounds() {
        assertEquals(
            listOf("trim", "compress", "extract", "gif", "downloader", "enhance", "importer", "send"),
            movedToolOrder(DefaultToolOrder, 0, 1),
        )
        assertEquals(
            listOf("downloader", "compress", "trim", "extract", "gif", "enhance", "importer", "send"),
            movedToolOrder(DefaultToolOrder, 4, 0),
        )
        assertEquals(DefaultToolOrder, movedToolOrder(DefaultToolOrder, 0, -1))
        assertEquals(DefaultToolOrder, movedToolOrder(DefaultToolOrder, 7, 8))
        assertEquals(DefaultToolOrder, movedToolOrder(DefaultToolOrder, 2, 2))
    }
}
