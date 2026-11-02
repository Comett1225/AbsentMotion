package app.absentmotion.editor

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Test

class MaskCopyTest {
    private fun layer(id: Long, mask: Boolean = false) = Layer(XmlNode("shape",
        linkedMapOf("id" to "$id", "startTime" to "0", "endTime" to "999") + (if (mask) mapOf("clippingMask" to "true") else emptyMap())))

    private fun scene(vararg layers: Layer) = Scene.create("t", 100, 100, 30, 0).withLayers(layers.toList())

    private fun Scene.masks(vararg ids: Long) = layersToCopy(ids.toSet()).map { it.id to (it.node["clippingMask"] == "true") }

    @Test fun aCopiedMaskStaysAMaskOnlyWhenTheLayerItClipsIsCopiedWithIt() {
        val s = scene(layer(1), layer(2), layer(3, mask = true), layer(4, mask = true))
        assertEquals(listOf(3L to false), s.masks(3))
        assertEquals(listOf(2L to false, 3L to true, 4L to true), s.masks(2, 3, 4))
        assertEquals(listOf(2L to false, 4L to true), s.masks(2, 4))
        assertEquals(listOf(1L to false, 4L to false), s.masks(1, 4))
        assertEquals(listOf(1L to false), scene(layer(1, mask = true), layer(2)).masks(1))
    }
}
