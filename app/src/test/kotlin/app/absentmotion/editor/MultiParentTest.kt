package app.absentmotion.editor

import app.absentmotion.model.XmlNode
import app.absentmotion.sceneOf
import app.absentmotion.shapeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiParentTest {
    private fun layer(id: Long, parent: Long?, location: String) =
        shapeNode(id = id, start = 0, end = 1000, parent = parent,
            children = listOf(XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to location))))))

    @Test fun everyChosenLayerLinksToTheParentAndStaysInPlace() {
        val s = sceneOf(layer(1, null, "100,50,0"), layer(2, null, "300,200,0"), layer(3, null, "40,80,0"))
        val (out, skipped) = s.parented(listOf(2L, 3L), 1L)
        assertEquals(0, skipped)
        for (id in listOf(2L, 3L)) {
            assertEquals("1", out.layer(id)!!.node["parent"])
            val before = s.place(s.layer(id)!!, 500)
            val after = out.place(out.layer(id)!!, 500)
            assertEquals(before.x, after.x, 1e-2f)
            assertEquals(before.y, after.y, 1e-2f)
        }
    }

    @Test fun aLayerTheParentIsLinkedToIsSkippedAndNoneUnlinks() {
        val s = sceneOf(layer(1, null, "100,50,0"), layer(2, 1, "10,0,0"), layer(3, null, "40,80,0"))
        val (out, skipped) = s.parented(listOf(1L, 3L), 2L)
        assertEquals(1, skipped)
        assertNull(out.layer(1)!!.node["parent"])
        assertEquals("2", out.layer(3)!!.node["parent"])
        val (cleared, none) = out.parented(listOf(2L, 3L), null)
        assertEquals(0, none)
        assertNull(cleared.layer(2)!!.node["parent"])
        assertNull(cleared.layer(3)!!.node["parent"])
    }
}
