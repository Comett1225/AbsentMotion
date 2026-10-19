package app.absentmotion.editor

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElementSaveTest {
    private fun prop(name: String, vararg keys: Pair<Float, String>): XmlNode =
        if (keys.size == 1) XmlNode(name, mapOf("value" to keys[0].second))
        else XmlNode(name, children = keys.map { (t, v) -> XmlNode("kf", mapOf("t" to "$t", "v" to v)) })

    private fun layer(kind: String, id: Long, parent: Long?, props: List<XmlNode>, extra: List<XmlNode> = emptyList()) = XmlNode(
        kind, linkedMapOf("id" to "$id", "startTime" to "0", "endTime" to "1000") + (parent?.let { mapOf("parent" to "$it") } ?: emptyMap()),
        listOf(XmlNode("transform", children = props)) + extra,
    )

    private fun scene(vararg layers: XmlNode) = Scene(Scene.create("t", 400, 400, 30, 0).node.copy(children = layers.toList()))

    private fun group(vararg props: XmlNode, inner: List<XmlNode>) = layer("embedScene", 9, null, props.toList(), listOf(
        Scene(Scene.create("", 400, 400, 30, 0).node.with("retime", "off").with("totalTime", "1000")).node.copy(children = inner),
    ))

    private fun Layer.x(ms: Int) = at(ms, 30).location[0]
    private fun Layer.y(ms: Int) = at(ms, 30).location[1]

    @Test fun aParentedChildIsSavedWhereItWasSeen() {
        val host = scene(
            layer("nullobj", 1, null, listOf(prop("location", 0f to "100,0,0"), prop("scale", 0f to "2,2"))),
            layer("shape", 2, 1, listOf(prop("location", 0f to "20,100,0", 1f to "50,100,0"))),
        )
        val saved = Layer(host.elementNodes(host.layer(2)!!, floatArrayOf(10f, 150f, 200f, 250f)).single())
        assertNull(saved.node["parent"])
        assertEquals(130f, saved.x(0), 1e-3f)
        assertEquals(190f, saved.x(1000), 1e-3f)
        assertEquals(50f, saved.y(500), 1e-3f)
        assertEquals(2f, saved.scale[0], 1e-6f)
    }

    @Test fun aRotatedGroupKeepsItsRotation() {
        val host = scene(group(prop("location", 0f to "200,200,0"), prop("rotation", 0f to "90"),
            inner = listOf(layer("shape", 5, null, listOf(prop("location", 0f to "300,200,0"))))))
        val saved = Layer(host.elementNodes(host.layer(9)!!, null).single())
        assertEquals(200f, saved.x(0), 1e-3f)
        assertEquals(300f, saved.y(0), 1e-3f)
        assertEquals(90f, saved.transform("rotation", 0f)[0], 1e-3f)
    }

    @Test fun anAnimatedGroupBakesItsMotionIntoTheSavedLayers() {
        val host = scene(group(prop("location", 0f to "200,200,0", 1f to "300,200,0"), prop("scale", 0f to "1,1", 1f to "2,2"),
            inner = listOf(
                layer("nullobj", 4, null, listOf(prop("location", 0f to "220,200,0"))),
                layer("shape", 5, 4, listOf(prop("location", 0f to "10,0,0"))),
            )))
        val saved = host.elementNodes(host.layer(9)!!, null).map(::Layer).associateBy { it.id }
        val child = saved.getValue(5)
        assertNull(child.node["parent"])
        assertEquals(230f, child.x(0), 1e-3f)
        assertEquals(360f, child.x(1000), 1e-3f)
        assertEquals(2f, child.at(1000, 30).scale[0], 1e-3f)
        assertEquals(295f, child.x(500), 1e-2f)
    }
}
