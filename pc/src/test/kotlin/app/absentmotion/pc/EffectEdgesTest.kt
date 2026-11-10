package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.pc.engine.DesktopEngine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectEdgesTest {
    private val root = File(System.getProperty("am.repo"))
    private val sample = "testdata/media/test-image.jpg"

    private fun scene(effect: XmlNode?) = Scene(XmlNode("scene", mapOf("width" to "1080", "height" to "1080", "fps" to "30", "totalTime" to "2999", "bgcolor" to "#ffff00ff"), listOf(
        XmlNode("media", mapOf("uri" to sample, "type" to "image/jpeg", "width" to "1600", "height" to "1200")),
        XmlNode("shape", mapOf("id" to "1", "label" to "Image", "startTime" to "0", "endTime" to "2999", "s" to ".rect", "fillType" to "media", "fillImage" to sample, "mediaFillMode" to "fill"),
            listOf(XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "540.3,540.7,0")))), XmlNode("property", mapOf("name" to "size", "type" to "vec2", "value" to "400,300"))) + listOfNotNull(effect)))))

    private fun background(p: Int) = (p shr 16 and 255) > 225 && (p shr 8 and 255) < 40 && (p and 255) > 225

    @Test fun gridAndTileEffectsCoverTheWholeLayerAndStayInsideIt() {
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            val ids = listOf("pixelate", "mosaic", "pixeltiles", "ascii", "halftone", "tileshift", "tilerotate", "hexagontileshift", "hexagontilerotate")
            for (id in ids) {
                val def = engine.definitions.single { it.key == id }
                val effect = def.instance().let { node ->
                    if (id == "pixelate") node.copy(children = node.children.map { if (it["name"] == "blockSize") it.with("value", "70") else it }) else node
                }
                engine.setScene(scene(effect), root)
                val image = engine.render(500, 1080, 1080)
                var holes = 0
                var outside = 0
                for (y in 200 until 880) for (x in 100 until 980) {
                    val p = image.getRGB(x, y)
                    if (x in 143..937 && y in 244..837 && background(p)) holes++
                    if ((x < 138 || x > 943 || y < 238 || y > 844) && !background(p)) outside++
                }
                assertTrue("$id leaves $holes empty pixels inside the layer", holes == 0)
                assertTrue("$id draws $outside pixels outside the layer", outside == 0)
            }
        }
    }
}
