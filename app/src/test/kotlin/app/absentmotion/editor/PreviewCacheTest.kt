package app.absentmotion.editor

import app.absentmotion.model.Scene
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewCacheTest {
    private val mib = 1L shl 20

    @Test fun theBudgetIsAShareOfTheMemoryClassWithAHardCap() {
        assertEquals(256 * mib * 15 / 100, previewCacheBudget(256, lowRam = false, compatible = false))
        assertEquals(256 * mib * 12 / 100, previewCacheBudget(256, lowRam = false, compatible = true))
        assertEquals(128 * mib * 8 / 100, previewCacheBudget(128, lowRam = true, compatible = false))
        assertEquals(256 * mib, previewCacheBudget(4096, lowRam = false, compatible = false))
        assertTrue(previewCacheBudget(0, lowRam = true, compatible = true) > 0)
    }

    @Test fun theFrameSizeFollowsThePreviewQualityAndNeverUpscales() {
        assertEquals(540 to 960, cacheFrameSize(1080, 1920, 0.5f, 1))
        assertEquals(270 to 480, cacheFrameSize(1080, 1920, 0.5f, 2))
        assertEquals(135 to 240, cacheFrameSize(1080, 1920, 0.5f, 4))
        assertEquals(1080 to 1920, cacheFrameSize(1080, 1920, 3f, 1))
        assertEquals(1920 to 1080, cacheFrameSize(3840, 2160, 1f, 1))
        assertEquals(960 to 540, cacheFrameSize(3840, 2160, 0f, 2))
    }

    @Test fun theWholeProjectIsRenderedWhenItFits() {
        assertEquals(0..299, renderRange(299, 120, 300))
        assertEquals(0..299, renderRange(299, 0, 10_000))
        assertEquals(0..0, renderRange(0, 0, 5))
    }

    @Test fun aLongProjectIsRenderedFromThePlayheadUpToTheBudget() {
        assertEquals(120..219, renderRange(899, 120, 100))
        assertEquals(800..899, renderRange(899, 880, 100))
        assertEquals(0..99, renderRange(899, -5, 100))
        assertEquals(50..50, renderRange(899, 50, 0))
    }

    @Test fun theFrameEstimateLeavesRoomForEveryFrame() {
        val budget = 38 * mib
        val frames = framesThatFit(budget, 620, 1100)
        assertTrue(frames in 300..700)
        assertEquals(1, framesThatFit(10, 4000, 4000))
    }

    @Test fun theStoreKeepsFramesInOrderAndStopsAtTheBudget() {
        val store = FrameStore(40, 25)
        assertFalse(39 in store)
        assertTrue(store.add(ByteArray(10) { 1 }))
        assertTrue(store.add(ByteArray(10) { 2 }))
        assertFalse(store.add(ByteArray(10) { 3 }))
        assertEquals(2, store.count)
        assertEquals(20L, store.bytes)
        assertEquals(40, store.first)
        assertEquals(41, store.last)
        assertTrue(40 in store && 41 in store)
        assertFalse(42 in store)
        assertEquals(2.toByte(), store[41]!![0])
        assertNull(store[42])
        assertNull(store[39])
    }

    @Test fun anyEditQualityOrEmojiChangeOutdatesTheCacheAndUndoBringsItBack() {
        val xml = """<scene width="100" height="100" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="999"><fillColor value="#ffff0000"/></shape></scene>"""
        val scene = Scene.parse(xml)
        val key = CacheKey("p", scene, 1, false)
        val edited = Scene(scene.node.copy(attrs = scene.node.attrs + ("bgcolor" to "#ff00ff00")))
        assertEquals(key, CacheKey("p", Scene.parse(xml), 1, false))
        assertNotEquals(key, CacheKey("p", edited, 1, false))
        assertNotEquals(key, CacheKey("p", scene, 2, false))
        assertNotEquals(key, CacheKey("p", scene, 1, true))
        assertNotEquals(key, CacheKey("q", scene, 1, false))
        assertEquals(key, CacheKey("p", Scene(edited.node.copy(attrs = edited.node.attrs - "bgcolor")), 1, false))
    }

    @Test fun aReplacedPictureOrNewFontOutdatesTheCache() {
        val scene = Scene.parse("""<scene width="100" height="100" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="999" fillType="media" fillImage="file:///a.png"/></scene>""")
        assertNotEquals(CacheKey("p", scene, 1, false, 3), CacheKey("p", scene, 1, false, 4))
        assertEquals(CacheKey("p", scene, 1, false, 4), CacheKey("p", Scene(scene.node), 1, false, 4))
    }

    @Test fun decodedPlaybackFramesAreChargedToTheBudget() {
        assertEquals(38 * mib - 5 * 4L * 1000 * 500, storeBudget(38 * mib, 1000, 500))
        assertEquals(10 * mib / 4, storeBudget(10 * mib, 1920, 1080))
    }

    @Test fun opaquePixelsStayAndTransparentOnesShowTheChecker() {
        val opaque = byteArrayOf(10, 20, 30, -1, 40, 50, 60, -1)
        assertFalse(flattenOnChecker(opaque, 2, 1, 1f))
        assertArrayEquals(byteArrayOf(10, 20, 30, -1, 40, 50, 60, -1), opaque)

        val clear = ByteArray(2 * 1 * 4)
        assertTrue(flattenOnChecker(clear, 2, 1, 1f))
        assertEquals(0xe5, clear[0].toInt() and 0xff)
        assertEquals(0xcc, clear[4].toInt() and 0xff)
        assertEquals(-1, clear[3].toInt())
        assertEquals(-1, clear[7].toInt())

        val half = byteArrayOf(255.toByte(), 0, 0, 128.toByte())
        flattenOnChecker(half, 1, 1, 25f)
        assertEquals((255 * 128 + 0xe5 * 127 + 127) / 255, half[0].toInt() and 0xff)
        assertEquals((0xe5 * 127 + 127) / 255, half[1].toInt() and 0xff)
    }

    @Test fun framesStoredOnDiskComeBackIntactAndTheFileGoesAwayOnClose() {
        val file = java.io.File.createTempFile("preview-cache-", ".bin")
        val store = FrameStore(10, 10, file)
        assertTrue(store.add(byteArrayOf(1, 2, 3)))
        assertTrue(store.add(byteArrayOf(4, 5)))
        assertFalse(store.add(byteArrayOf(6, 7, 8, 9, 10, 11)))
        assertEquals(2, store.count)
        assertEquals(11, store.last)
        assertArrayEquals(byteArrayOf(1, 2, 3), store[10])
        assertArrayEquals(byteArrayOf(4, 5), store[11])
        assertNull(store[12])
        store.close()
        assertFalse(file.exists())
    }

    @Test fun diskBudgetKeepsAReserveFreeAndHasACap() {
        val mib = 1L shl 20
        assertEquals(0L, diskBudget(512 * mib))
        assertEquals(256 * mib, diskBudget(2048 * mib))
        assertEquals(1024 * mib, diskBudget(100_000 * mib))
    }
}
