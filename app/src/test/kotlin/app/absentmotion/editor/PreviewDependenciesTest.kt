package app.absentmotion.editor

import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewDependenciesTest {
    private fun scene(first: String = "", second: String = "", extra: String = "") = Scene.parse(
        "<scene width=\"100\" height=\"100\" fps=\"30\" totalTime=\"5999\" $extra>" +
            "<shape id=\"1\" startTime=\"0\" endTime=\"999\" label=\"A\">$first</shape>" +
            "<shape id=\"2\" startTime=\"4000\" endTime=\"5999\" label=\"B\">$second</shape></scene>")

    @Test fun aLocalEditRetainsAnUnchangedEarlierSection() {
        val ranges = previewDirtyRanges(scene(), scene(second = "<fillColor value=\"#ff00ff00\"/>"))
        assertTrue(ranges.any { 140 in it })
        assertFalse(ranges.any { 10 in it })
    }

    @Test fun renamesBookmarksAndAudioEditsDoNotInvalidateVisualFrames() {
        val before = scene()
        val xml = before.node.toXml().replace("label=\"A\"", "label=\"Renamed\"")
            .replace("</scene>", "<bookmark t=\"500\"/><audio id=\"9\" startTime=\"0\" endTime=\"5000\"><gain value=\"0.5\"/></audio></scene>")
        assertTrue(previewDirtyRanges(before, Scene.parse(xml)).isEmpty())
    }

    @Test fun movingAClipInvalidatesBothItsFormerAndNewLocation() {
        val before = scene()
        val moved = Scene.parse(before.node.toXml().replace("startTime=\"4000\"", "startTime=\"2000\"").replace("endTime=\"5999\"", "endTime=\"3999\""))
        val ranges = previewDirtyRanges(before, moved)
        assertTrue(ranges.any { 90 in it })
        assertTrue(ranges.any { 150 in it })
        assertFalse(ranges.any { 10 in it })
    }

    @Test fun dimensionsResourcesAndUnknownDependenciesInvalidateConservatively() {
        val before = scene()
        assertEquals(listOf(0..before.lastFrame), previewDirtyRanges(before, Scene.parse(before.node.toXml().replace("width=\"100\"", "width=\"200\""))))
        val temporal = scene(first = "<effect id=\"absent.echo\"/>")
        assertEquals(listOf(0..temporal.lastFrame), previewDirtyRanges(temporal, scene(first = "<effect id=\"absent.echo\"/>", second = "<fillColor value=\"#ff00ff00\"/>")))
    }

    @Test fun preparationIsBoundedPrioritisesThePlayheadAndDoesNotDuplicate() {
        val order = previewOrder(900, 100, 30)
        assertEquals(100, order.first())
        assertEquals(listOf(100, 101, 99, 102, 98), order.take(5))
        assertEquals(order.size, order.toSet().size)
        assertEquals(41, order.size)
        assertTrue(previewOrder(3, 0, 60).all { it in 0..3 })
        assertEquals(listOf(0), previewOrder(0, -50, 0))
    }

    @Test fun sparseFramesAreIndependentAndEvictionIsBounded() {
        val store = FrameStore(0, 10)
        assertTrue(store.put(10, ByteArray(4)))
        assertTrue(store.put(100, ByteArray(4)))
        assertFalse(11 in store)
        assertFalse(store.put(30, ByteArray(4)))
        assertTrue(store.evictFarthest(12))
        assertTrue(10 in store)
        assertFalse(100 in store)
        assertTrue(store.put(30, ByteArray(4)))
        store.removeWhere { it in 20..40 }
        assertTrue(10 in store)
        assertFalse(30 in store)
        assertEquals(4L, store.bytes)
        store.close()
        assertFalse(10 in store)
        assertEquals(null, store[10])
        assertFalse(store.put(10, byteArrayOf(1)))
    }
}
