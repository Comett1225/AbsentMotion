package app.absentmotion.editor

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.sceneOf
import app.absentmotion.shapeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleLinkTest {
    private fun prop(name: String, vararg keys: Pair<Float, String>) = XmlNode(name, mapOf("value" to keys[0].second))

    private fun layer(id: Long, vararg props: XmlNode) =
        shapeNode(id = id, start = 0, end = 1000, children = listOf(XmlNode("transform", children = props.toList())))

    @Test fun theScaleLinkIsSavedOnTheLayerAndUndone() {
        val s = sceneOf(layer(1, prop("scale", 0f to "2,0.5")))
        val unlinked = s.update(1) { it.withScaleLinked(false) }
        assertFalse(unlinked.layer(1)!!.scaleLinked)
        val reopened = Scene.parse(unlinked.toXml())
        assertFalse(reopened.layer(1)!!.scaleLinked)
        assertEquals(listOf(2f, 0.5f), reopened.layer(1)!!.scale.toList())
        assertNull(reopened.update(1) { it.withScaleLinked(true) }.layer(1)!!.node["scaleLinked"])
        var current = s
        val history = EditHistory({ current }) { next, _ -> current = next }
        history.edit(null, unlinked)
        history.edit("size-1", current.update(1) { it.withTransform("scale", 3f, 0.5f) })
        history.endGesture()
        history.undo()
        assertFalse(current.layer(1)!!.scaleLinked)
        history.undo()
        assertTrue(current.layer(1)!!.scaleLinked)
        history.redo()
        assertFalse(current.layer(1)!!.scaleLinked)
    }
}
