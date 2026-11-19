package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.pc.engine.DesktopEngine
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyedBorderTest {
    private val root = File(System.getProperty("am.repo"))
    private val folder = createTempDirectory("keyed-border").toFile().apply { deleteOnExit() }

    private fun picture(name: String, green: Boolean): String {
        val image = BufferedImage(240, 240, if (green) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        if (green) {
            g.color = Color(0x00ff00)
            g.fillRect(0, 0, 240, 240)
        }
        g.color = Color(0xd03030)
        g.fillOval(60, 40, 120, 160)
        g.dispose()
        return File(folder, name).also { ImageIO.write(image, "png", it); it.deleteOnExit() }.absolutePath
    }

    private fun value(name: String, value: String) = XmlNode(name, mapOf("value" to value))

    private fun scene(uri: String, extra: List<XmlNode>) = Scene(XmlNode("scene", mapOf("width" to "400", "height" to "400", "fps" to "30", "totalTime" to "1000", "bgcolor" to "#ff2040c0"), listOf(
        XmlNode("media", mapOf("uri" to uri, "type" to "image/png", "width" to "240", "height" to "240")),
        XmlNode("shape", mapOf("id" to "1", "label" to "Image", "startTime" to "0", "endTime" to "1000", "s" to ".rect", "fillType" to "media", "fillImage" to uri, "mediaFillMode" to "fill"),
            listOf(XmlNode("transform", children = listOf(value("location", "200,200,0"))), XmlNode("property", mapOf("name" to "size", "type" to "vec2", "value" to "120,120"))) + extra))))

    private fun differing(a: BufferedImage, b: BufferedImage): Int {
        var count = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y)
            val q = b.getRGB(x, y)
            if ((0..24 step 8).any { kotlin.math.abs((p shr it and 255) - (q shr it and 255)) > 48 }) count++
        }
        return count
    }

    private fun background(p: Int) = kotlin.math.abs((p shr 16 and 255) - 0x20) < 12 && kotlin.math.abs((p shr 8 and 255) - 0x40) < 12 && kotlin.math.abs((p and 255) - 0xc0) < 12

    @Test fun aChromaKeyedPictureGetsTheSameBorderAndShadowAsAnAlreadyTransparentOne() {
        val keyedUri = picture("green.png", true)
        val clearUri = picture("clear.png", false)
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            val key = engine.definitions.single { it.key == "chromakey" }.instance()
            fun render(uri: String, extra: List<XmlNode>): BufferedImage {
                engine.setScene(scene(uri, extra), root)
                return engine.render(0, 400, 400).let { frame -> BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().drawImage(frame, 0, 0, null) } }
            }
            val plain = render(clearUri, emptyList())
            val styles = listOf("outside", "inside", "centered").map { direction ->
                direction to XmlNode("border", mapOf("direction" to direction), listOf(value("color", "#ffffffff"), value("size", "12")))
            } + ("shadow" to XmlNode("shadow", children = listOf(value("color", "#ff000000"), value("size", "8"), value("offset", "10,10"))))
            for ((name, style) in styles) {
                val clear = render(clearUri, listOf(style))
                val keyed = render(keyedUri, listOf(key, style))
                assertTrue("$name does not show on the transparent picture", differing(clear, plain) > 200)
                val off = differing(keyed, clear)
                assertTrue("$name on the keyed picture differs from the transparent picture in $off pixels", off < 80)
                for ((x, y) in listOf(95 to 95, 300 to 100, 100 to 200, 305 to 305, 200 to 100)) {
                    assertTrue("$name fills the keyed-out background at $x,$y", background(keyed.getRGB(x, y)))
                }
            }
        }
    }
}
