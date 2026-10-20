package app.absentmotion.ui.editor

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineSnapTest {
    private val fps = 30

    @Test fun snapsToTheNearestTargetInsideTheZone() {
        val targets = setOf(30, 45, 90)
        assertEquals(30, TimelineMath.snapFrame(1010f, targets, fps, 20f))
        assertEquals(30, TimelineMath.snapFrame(990f, targets, fps, 20f))
        assertEquals(45, TimelineMath.snapFrame(1490f, targets, fps, 20f))
        assertNull(TimelineMath.snapFrame(1100f, targets, fps, 20f))
        assertNull(TimelineMath.snapFrame(1000f, emptySet(), fps, 20f))
    }

    @Test fun snappingIsFrameExact() {
        val targets = setOf(37)
        val exact = TimelineMath.frameTimeMs(37, fps)
        for (offset in -15..15) assertEquals(37, TimelineMath.snapFrame(exact + offset, targets, fps, 15f))
        assertNull(TimelineMath.snapFrame(exact + 15.5f, targets, fps, 15f))
    }

    @Test fun theZoneIsEightDpAtEveryZoom() {
        val targets = setOf(60)
        val at = TimelineMath.frameTimeMs(60, fps)
        fun zone(dpPerSecond: Float) = 8f / (dpPerSecond / 1000f)
        assertNull(TimelineMath.snapFrame(at + 30f, targets, fps, zone(1000f)))
        assertEquals(60, TimelineMath.snapFrame(at + 30f, targets, fps, zone(100f)))
        assertEquals(60, TimelineMath.snapFrame(at + 700f, targets, fps, zone(10f)))
    }

    @Test fun targetsAreThePlayheadBookmarksAndOtherLayersEdgesAndKeys() {
        val key = { t: String -> XmlNode("kf", mapOf("t" to t, "v" to "0,0,0")) }
        val moving = XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "2999"),
            listOf(XmlNode("transform", children = listOf(XmlNode("location", children = listOf(key("0"), key("0.3")))))))
        val other = XmlNode("shape", mapOf("id" to "2", "startTime" to "1000", "endTime" to "1999"),
            listOf(XmlNode("transform", children = listOf(XmlNode("location", children = listOf(key("0"), key("0.5")))))))
        val scene = Scene.create("t", 1080, 1080, fps, 0).let { Scene(it.node.copy(children = it.node.children + moving + other)) }
            .withBookmarks(listOf(2500))
        val targets = TimelineMath.snapTargets(scene, skip = 1, playhead = 12)
        assertEquals(setOf(12, 75, 30, 60, 45), targets)
        val all = TimelineMath.snapTargets(scene, skip = 0, playhead = 12)
        assertEquals(setOf(12, 75, 30, 60, 45, 0, 90, 27), all)
    }

    @Test fun edgesOffTheFrameGridSnapToTheFramesWhereTheClipAppearsAndDisappears() {
        val clip = XmlNode("shape", mapOf("id" to "2", "startTime" to "3482", "endTime" to "3981"))
        val scene = Scene.create("t", 1080, 1080, 60, 0).let { Scene(it.node.copy(children = it.node.children + clip)) }
        val targets = TimelineMath.snapTargets(scene, skip = 1, playhead = 0)
        assertEquals(setOf(0, 209, 239), targets)
        assertEquals(scene.firstShownFrame(3482), 209)
        assertEquals(scene.lastShownFrame(3981), 238)
    }
}
