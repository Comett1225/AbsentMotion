package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.pc.engine.DesktopEngine
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil

fun renderCheck() {
    val root = File(System.getProperty("am.repo"))
    val output = File(System.getProperty("am.output"))
    val assets = File(System.getProperty("am.assets"))
    output.mkdirs()
    DesktopEngine(assets, root).use { engine ->
        val sample = "testdata/media/test-image.jpg"
        fun scene(effect: XmlNode?, text: Boolean): Scene {
            val body = if (text) XmlNode("text", mapOf("id" to "1", "label" to "Text", "startTime" to "0", "endTime" to "2999", "size" to "30", "font" to "googlefonts?name=Roboto&weight=700", "wrapWidth" to "400", "align" to "center", "fillType" to "color"), listOf(
                XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "256,192,0")))), XmlNode("fillColor", mapOf("value" to "#ffffffff")), XmlNode("content", text = "Absent Motion")) + listOfNotNull(effect))
            else XmlNode("shape", mapOf("id" to "1", "label" to "Image", "startTime" to "0", "endTime" to "2999", "s" to ".rect", "fillType" to "media", "fillImage" to sample, "mediaFillMode" to "fill"), listOf(
                XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "256,192,0")))), XmlNode("property", mapOf("name" to "size", "type" to "vec2", "value" to "450,320"))) + listOfNotNull(effect))
            return Scene(XmlNode("scene", mapOf("width" to "512", "height" to "384", "fps" to "30", "totalTime" to "2999", "bgcolor" to "#ff14161a"), listOf(XmlNode("media", mapOf("uri" to sample, "type" to "image/jpeg")), body)))
        }
        fun frame(effect: XmlNode?, text: Boolean): BufferedImage {
            engine.setScene(scene(effect, text), root)
            return engine.render(500, 256, 192)
        }
        val baseImage = frame(null, false).getRGB(0, 0, 256, 192, null, 0, 256)
        val baseText = frame(null, true).getRGB(0, 0, 256, 192, null, 0, 256)
        val columns = 5
        val rows = ceil(engine.definitions.size.toDouble() / columns).toInt()
        val sheet = BufferedImage(columns * 256, rows * 216, BufferedImage.TYPE_INT_RGB)
        val graphics = sheet.createGraphics()
        graphics.font = Font("SansSerif", Font.PLAIN, 12)
        val identical = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()
        var rendered = 0
        engine.definitions.forEachIndexed { index, def ->
            val x = index % columns * 256
            val y = index / columns * 216
            val text = def.category == "text"
            runCatching { frame(def.instance(), text) }.onSuccess { image ->
                rendered++
                if (image.getRGB(0, 0, 256, 192, null, 0, 256).contentEquals(if (text) baseText else baseImage)) identical += def.key
                graphics.drawImage(image, x, y, null)
            }.onFailure { failed[def.key] = it.message ?: it.javaClass.simpleName }
            graphics.color = Color(35, 37, 43)
            graphics.fillRect(x, y + 192, 256, 24)
            graphics.color = Color.WHITE
            graphics.drawString(def.key, x + 5, y + 208)
        }
        graphics.dispose()
        ImageIO.write(sheet, "png", output.resolve("effects-contact-sheet.png"))
        val report = "Definitions: ${engine.definitions.size}\nLoaded: ${engine.definitions.size - engine.loadErrors.size}\nRendered: $rendered\nLoad failures: ${engine.loadErrors}\nRender failures: $failed\nIdentical to plain: $identical\n"
        output.resolve("render-check.txt").writeText(report)
        println(report)
    }
}

fun sceneCheck() {
    val root = File(System.getProperty("am.repo"))
    val output = File(System.getProperty("am.output"))
    val assets = File(System.getProperty("am.assets"))
    output.mkdirs()
    DesktopEngine(assets, root).use { engine ->
        listOf("text", "shapes", "styles", "media").forEach { name ->
            val source = root.resolve("testdata/scenes/$name.xml")
            val scene = Scene.parse(source.readText())
            val roundTrip = Scene.parse(scene.toXml())
            check(scene.node.copy(attrs = scene.node.attrs - "modifiedTime") == roundTrip.node.copy(attrs = roundTrip.node.attrs - "modifiedTime"))
            engine.setScene(roundTrip, source.parentFile)
            val scale = minOf(1.0, 1200.0 / maxOf(scene.width, scene.height))
            val width = (scene.width * scale).toInt().coerceAtLeast(1)
            val height = (scene.height * scale).toInt().coerceAtLeast(1)
            ImageIO.write(engine.render(500, width, height), "png", output.resolve("scene-$name.png"))
            println("Rendered $name: ${width}x$height")
        }
    }
}
