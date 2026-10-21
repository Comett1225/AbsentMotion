package app.absentmotion.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRulesTest {
    @Test fun onlyVideosAbove1080pOrWithLongGopsGetACopy() {
        assertFalse(ProxyRules.needed(1920, 1080, 1_000_000, hdr = false))
        assertFalse(ProxyRules.needed(1080, 1920, 1_000_000, hdr = false))
        assertTrue(ProxyRules.needed(3840, 2160, 1_000_000, hdr = false))
        assertTrue(ProxyRules.needed(2160, 3840, 1_000_000, hdr = false))
        assertTrue(ProxyRules.needed(2560, 1080, 1_000_000, hdr = false))
        assertTrue(ProxyRules.needed(1440, 1440, 1_000_000, hdr = false))
        assertFalse(ProxyRules.needed(1280, 720, 2_000_000, hdr = false))
        assertTrue(ProxyRules.needed(1280, 720, 2_000_001, hdr = false))
        assertTrue(ProxyRules.needed(640, 360, Long.MAX_VALUE, hdr = false))
        assertFalse(ProxyRules.needed(3840, 2160, 10_000_000, hdr = true))
        assertFalse(ProxyRules.needed(0, 0, 10_000_000, hdr = false))
    }

    @Test fun copiesAre540pEvenAndKeepTheShape() {
        assertEquals(960 to 540, ProxyRules.size(3840, 2160))
        assertEquals(540 to 960, ProxyRules.size(2160, 3840))
        assertEquals(1280 to 540, ProxyRules.size(2560, 1080))
        assertEquals(640 to 360, ProxyRules.size(640, 360))
        val (w, h) = ProxyRules.size(1001, 3001)
        assertTrue(w % 2 == 0 && h % 2 == 0 && w == 540)
        assertEquals(3001.0 / 1001, h.toDouble() / w, 0.01)
    }

    @Test fun theCacheKeyFollowsTheFileVersion() {
        val uri = "content://media/external/video/media/42"
        val first = ProxyRules.key(uri, "1:2:3000:17.5")
        assertEquals(first, ProxyRules.key(uri, "1:2:3000:17.5"))
        assertEquals(32, first!!.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(first, ProxyRules.key(uri, "1:2:3001:18.0"))
        assertNotEquals(first, ProxyRules.key("content://media/external/video/media/43", "1:2:3000:17.5"))
        assertNull(ProxyRules.key(uri, null))
    }

    @Test fun aCopyIsUsedOnlyWhenEveryFrameKeepsItsTime() {
        val source = longArrayOf(0, 33_333, 66_666, 100_000)
        assertTrue(ProxyRules.sameFrames(source, longArrayOf(0, 33_333, 66_667, 100_000)))
        assertFalse(ProxyRules.sameFrames(source, longArrayOf(0, 33_333, 66_666)))
        assertFalse(ProxyRules.sameFrames(source, longArrayOf(10_000, 43_333, 76_666, 110_000)))
    }

    @Test fun theLongestGopIncludesTheTail() {
        assertEquals(1_000_000L, longestSyncGap(listOf(0L, 1_000_000L, 2_000_000L), 2_900_000L))
        assertEquals(5_000_000L, longestSyncGap(listOf(0L), 5_000_000L))
        assertEquals(3_000_000L, longestSyncGap(listOf(4_000_000L, 0L, 1_000_000L), 4_500_000L))
        assertEquals(Long.MAX_VALUE, longestSyncGap(emptyList(), 1_000_000L))
    }

    @Test fun errorMessagesNameTheSourceFrameNotItsMicroseconds() {
        val stamps = longArrayOf(0L, 33_366L, 66_733L, 2_502_500L)
        assertEquals("0", stamps.frameNumber(0L))
        assertEquals("3", stamps.frameNumber(2_502_500L))
        assertEquals("2", stamps.frameNumber(50_000L))
    }

    @Test fun aFailedCopyIsTriedAgainAFewTimesUnlessItsFramesDiffered() {
        assertEquals(0, ProxyRules.failures(null))
        assertEquals(2, ProxyRules.failures("2"))
        assertEquals(1, ProxyRules.failures("java.lang.IllegalStateException: insufficient resources"))
        assertTrue(ProxyRules.failures(ProxyRules.FRAMES_DIFFER) >= ProxyRules.MAX_TRIES)
    }
}
