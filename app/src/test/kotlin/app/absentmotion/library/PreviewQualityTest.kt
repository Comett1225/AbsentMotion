package app.absentmotion.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewQualityTest {
    @Test fun theOldDraftSwitchBecomesHalf() {
        assertEquals(PreviewQuality.Half, PreviewQuality.of(null, draft = true))
        assertEquals(PreviewQuality.Full, PreviewQuality.of(null, draft = false))
    }

    @Test fun aSavedChoiceWinsOverTheOldSwitch() {
        assertEquals(PreviewQuality.Quarter, PreviewQuality.of("Quarter", draft = true))
        assertEquals(PreviewQuality.Full, PreviewQuality.of("Full", draft = true))
        assertEquals(PreviewQuality.Half, PreviewQuality.of("Half", draft = false))
    }

    @Test fun anUnknownValueFallsBackToTheOldSwitch() {
        assertEquals(PreviewQuality.Half, PreviewQuality.of("Eighth", draft = true))
        assertEquals(PreviewQuality.Full, PreviewQuality.of("", draft = false))
    }

    @Test fun eachChoiceDividesThePreviewResolution() {
        assertEquals(listOf(1, 2, 4), PreviewQuality.entries.map { it.divisor })
        for (q in PreviewQuality.entries) assertEquals(q, PreviewQuality.of(q.name, draft = false))
    }
}
