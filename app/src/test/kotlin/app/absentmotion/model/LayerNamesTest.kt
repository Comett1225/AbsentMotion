package app.absentmotion.model

import app.absentmotion.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerNamesTest {
    private fun layer(kind: String, vararg attrs: Pair<String, String>) = Layer(XmlNode(kind, linkedMapOf(*attrs)))

    @Test fun knownKindsMapToTheirResources() {
        val expected = mapOf(
            "nullobj" to R.string.model_layer_null,
            "camera" to R.string.model_layer_camera,
            "drawing" to R.string.model_layer_drawing,
            "track" to R.string.model_layer_track,
            "model" to R.string.model_layer_model,
            "text" to R.string.add_quick_text,
            "audio" to R.string.add_tab_audio,
            "shape" to R.string.shape_name_shape,
            "svg" to R.string.model_layer_svg,
            "embedScene" to R.string.model_layer_group,
        )
        for ((kind, id) in expected) assertEquals(kind, id, layer(kind).nameResource())
    }

    @Test fun linkedSceneIsAnElementAndMediaFillsAreNamedByType() {
        assertEquals(R.string.model_layer_element, layer("embedScene", "link" to "x").nameResource())
        assertEquals(R.string.model_layer_video, layer("shape", "fillType" to "media", "fillVideo" to "v.mp4").nameResource())
        assertEquals(R.string.model_layer_image, layer("shape", "fillType" to "media", "fillImage" to "i.png").nameResource())
    }

    @Test fun unknownKindUsesGenericResource() {
        assertEquals(R.string.model_layer_layer, layer("mystery").nameResource())
    }

    @Test fun labelsAndTextContentArePreserved() {
        assertEquals("Mine", layer("shape", "label" to "Mine").shownName { "resolved" })
        assertEquals("", layer("text").label)
        assertEquals("resolved", layer("text").shownName { "resolved" })
        assertEquals("Hello", Layer(XmlNode("text", children = listOf(XmlNode("content", text = "Hello")))).shownName { "resolved" })
    }

    @Test fun unnamedLayerNeverShowsItsRawKind() {
        for (kind in listOf("nullobj", "camera", "embedScene", "drawing", "track", "model", "text", "audio", "shape", "svg", "mystery")) {
            val shown = layer(kind).shownName { "#$it" }
            assertNotEquals(kind, shown)
            assertTrue(shown.startsWith("#"))
        }
    }
}
