package app.absentmotion.effects

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectSearchRankTest {
    @Test fun theEffectNamedExactlyLikeTheSearchComesFirst() {
        val names = listOf("Dark Glow", "Edge Glow", "Glow", "Glow Scan", "Light Glow")
        assertEquals(listOf("Glow", "Glow Scan", "Dark Glow", "Edge Glow", "Light Glow"),
            names.sortedWith(compareBy({ searchRank("glow", it) }, { it.lowercase() })))
    }

    @Test fun capitalsAndSpacesDoNotMatter() {
        assertEquals(0, searchRank("  GLOW ", "Glow"))
        assertEquals(1, searchRank("zoom", "Zoom Blur"))
        assertEquals(2, searchRank("blur", "Zoom Blur"))
    }
}
