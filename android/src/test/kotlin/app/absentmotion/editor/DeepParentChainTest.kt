package app.absentmotion.editor

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.sceneOf
import app.absentmotion.shapeNode
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Before
import org.junit.Test

class DeepParentChainTest {
    @Before fun needsTheHostEngine() = Assume.assumeTrue("build it with tools/pivot.sh --lib", PivotEngine.loaded)

    private fun link(id: Long) = shapeNode(id = id, parent = if (id > 1) id - 1 else null, end = 1000, attrs = mapOf("s" to ".rect", "fillType" to "color"),
        children = listOf(XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "10,0,0")))), XmlNode("property", linkedMapOf("name" to "size", "type" to "vec2", "value" to "20,20"))))

    @Test fun theEditorPlacesEveryLayerOfALongParentChainWhereTheEngineDrawsIt() {
        val s = sceneOf(*(1L..19L).map { link(it) }.toTypedArray())
        val xml = Scene.parse(s.toXml()).toXml()
        for (id in 1L..19L) {
            val engine = PivotEngine.nativePlaces(xml, longArrayOf(id), doubleArrayOf(500.0))!!
            assertEquals("layer $id", engine[9], s.place(s.layer(id)!!, 500).x, 0.01f)
        }
    }
}
