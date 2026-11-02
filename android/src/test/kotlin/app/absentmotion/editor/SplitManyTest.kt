package app.absentmotion.editor

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.canSplitAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitManyTest {
    private fun layer(id: Long, start: Int, end: Int, mask: Boolean = false) = Layer(XmlNode(
        "shape", linkedMapOf("id" to "$id", "label" to "L$id", "startTime" to "$start", "endTime" to "$end") + (if (mask) mapOf("clippingMask" to "true") else emptyMap()),
    ))

    private fun scene(vararg layers: Layer) = Scene.create("t", 100, 100, 30, 0).withLayers(layers.toList())

    @Test fun everyChosenClipUnderThePlayheadIsSplitAndTheOthersAreLeftAlone() {
        val s = scene(layer(1, 0, 2999), layer(2, 500, 2499), layer(3, 2000, 2999), layer(4, 0, 2999))
        val split = s.withSplits(listOf(1, 2, 3), 30, s.frameTime(30))!!
        assertEquals(6, split.layers.size)
        assertEquals(999, split.layer(1)!!.endTime)
        assertEquals(999, split.layer(2)!!.endTime)
        assertEquals(s.layer(3), split.layer(3))
        assertEquals(s.layer(4), split.layer(4))
        val seconds = split.layers.filter { it.id !in 1L..4L }
        assertEquals(listOf(1000 to 2999, 1000 to 2499), seconds.map { it.startTime to it.endTime }.sortedByDescending { it.second })
        assertEquals(seconds.size, seconds.map { it.id }.toSet().size)
    }

    @Test fun nothingUnderThePlayheadChangesNothing() {
        val s = scene(layer(1, 0, 999), layer(2, 2000, 2999))
        assertNull(s.withSplits(listOf(1, 2), 40, s.frameTime(40)))
        assertNull(s.withSplits(listOf(1, 2), 0, s.frameTime(0)))
        assertNull(s.withSplits(emptyList(), 40, s.frameTime(40)))
        assertNull(s.withSplits(listOf(99), 40, s.frameTime(40)))
    }

    @Test fun aBaseAndItsMaskBothChosenAreSplitOnlyOnce() {
        val s = scene(layer(1, 0, 2999), layer(2, 0, 2999, mask = true), layer(3, 0, 2999))
        val split = s.withSplits(listOf(1, 2, 3), 30, s.frameTime(30))!!
        assertEquals(6, split.layers.size)
        assertEquals(listOf(999, 999, 999), listOf(1L, 2L, 3L).map { split.layer(it)!!.endTime })
        assertEquals(listOf(1000, 1000, 1000), split.layers.filter { it.id > 3 }.map { it.startTime })
    }

    @Test fun oneChosenClipSplitsLikeBefore() {
        val s = scene(layer(1, 0, 2999), layer(2, 0, 2999))
        val split = s.withSplits(listOf(2), 30, s.frameTime(30))!!
        assertEquals(3, split.layers.size)
        assertEquals(s.layer(1), split.layer(1))
        assertEquals(999, split.layer(2)!!.endTime)
    }

    @Test fun aClipCanBeSplitOnlyStrictlyInsideIt() {
        val s = scene(layer(1, 0, 2999))
        val clip = s.layer(1)!!
        assertFalse(s.canSplitAt(clip, 0))
        assertTrue(s.canSplitAt(clip, 1))
        assertTrue(s.canSplitAt(clip, 89))
        assertFalse(s.canSplitAt(clip, 90))
        assertFalse(s.canSplitAt(clip, 500))
    }
}
