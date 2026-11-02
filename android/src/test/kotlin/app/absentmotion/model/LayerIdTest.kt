package app.absentmotion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerIdTest {
    @Test fun newIdsDoNotRepeatInABulkCreation() {
        val ids = List(200_000) { Layer.newId() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it in 10_000_000 until 99_999_999 })
    }

    @Test fun addingALayerWhoseIdIsTakenGivesItAFreshOne() {
        val clip = Layer(XmlNode("shape", linkedMapOf("id" to "42", "startTime" to "0", "endTime" to "999")))
        val s = Scene.create("t", 100, 100, 30, 0).add(clip).add(clip).add(clip)
        assertEquals(3, s.layers.map { it.id }.toSet().size)
        assertEquals(42L, s.layers.first().id)
        assertEquals(2, s.remove(s.layers.last().id).layers.size)
    }
}
