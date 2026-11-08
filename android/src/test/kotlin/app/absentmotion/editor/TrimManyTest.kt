package app.absentmotion.editor

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrimManyTest {
    private fun layer(id: Long, start: Int, end: Int) = Layer(XmlNode("shape", linkedMapOf("id" to "$id", "label" to "L$id", "startTime" to "$start", "endTime" to "$end")))

    private fun scene(vararg layers: Layer) = Scene.create("t", 100, 100, 30, 0).withLayers(layers.toList())

    @Test fun everyChosenClipUnderThePlayheadLosesItsStart() {
        val s = scene(layer(1, 0, 2999), layer(2, 500, 2499), layer(3, 2000, 2999), layer(4, 0, 2999))
        val trimmed = s.withTrims(listOf(1, 2, 3), 30, s.frameTime(30), true)!!
        assertEquals(1000, trimmed.layer(1)!!.startTime)
        assertEquals(1000, trimmed.layer(2)!!.startTime)
        assertEquals(s.layer(3), trimmed.layer(3))
        assertEquals(s.layer(4), trimmed.layer(4))
        assertEquals(listOf(2999, 2499), listOf(1L, 2L).map { trimmed.layer(it)!!.endTime })
    }

    @Test fun everyChosenClipUnderThePlayheadLosesItsEnd() {
        val s = scene(layer(1, 0, 2999), layer(2, 500, 2499), layer(3, 0, 800))
        val trimmed = s.withTrims(listOf(1, 2, 3), 30, s.frameTime(30), false)!!
        assertEquals(listOf(s.frameEnd(30), s.frameEnd(30)), listOf(1L, 2L).map { trimmed.layer(it)!!.endTime })
        assertEquals(s.layer(3), trimmed.layer(3))
    }

    @Test fun nothingToTrimChangesNothing() {
        val s = scene(layer(1, 0, 999), layer(2, 2000, 2999))
        assertNull(s.withTrims(listOf(1, 2), 0, s.frameTime(0), true))
        assertNull(s.withTrims(emptyList(), 40, s.frameTime(40), false))
        assertNull(s.withTrims(listOf(99), 15, s.frameTime(15), true))
    }
}
