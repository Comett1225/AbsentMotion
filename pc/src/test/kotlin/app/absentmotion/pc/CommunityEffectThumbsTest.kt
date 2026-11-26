package app.absentmotion.pc

import app.absentmotion.effects.readAddedEffectEntries
import app.absentmotion.engine.NativeEngine
import app.absentmotion.model.XmlNode
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

class CommunityEffectThumbsTest {
    private val side = 384
    private val hero = 720
    private val fps = 15
    private val frames = 30
    private val lengthMs = frames * 1000 / fps

    @Test fun rendersCommunityEffectFrames() {
        val packs = System.getProperty("am.effectThumbs")?.let(::File)
        assumeTrue("pass -PeffectThumbs=<community effects folder> -PeffectThumbsOut=<folder> to run", packs != null)
        val out = File(requireNotNull(System.getProperty("am.effectThumbsOut")) { "pass -PeffectThumbsOut=<folder>" }).canonicalFile
        val work = Files.createTempDirectory("community-thumbs").toFile()
        try {
            val city = File(System.getProperty("am.assets"), "presets/placeholders/city.webp")
            val grid = work.resolve("grid.png").also { ImageIO.write(grid(), "png", it) }
            NativeEngine().use { engine ->
                check(engine.setPictureFile(CITY, city.path) && engine.setPictureFile(GRID, grid.path))
                for (item in packs!!.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
                    val file = item.listFiles().orEmpty().firstOrNull { it.extension == "abmfx" || (it.extension == "json" && it.name != "item.json") } ?: continue
                    val effects = file.inputStream().use { readAddedEffectEntries(file.name, it, work) }.map { it.second.getOrThrow() }
                    for (effect in effects) {
                        val folder = out.resolve("${item.name}/${effect.json.getString("id")}").also { it.deleteRecursively(); check(it.mkdirs()) }
                        check(engine.loadEffect(effect.definition.runtimeXml()).substringBefore('\n') == "ok") { "${effect.definition.key} does not load" }
                        val text = effect.definition.category == "text" || "text" in effect.definition.key
                        val small = effect.definition.category in setOf("move", "repeat")
                        val node = effect.definition.instance().let { node ->
                            node.copy(children = node.children.map { property ->
                                val param = effect.definition.params.firstOrNull { it.id == property["name"] && it.id.lowercase() == "uprogress" && it.kind == "float" }
                                if (param == null) property else XmlNode("property", mapOf("name" to param.id, "type" to "float"),
                                    listOf(XmlNode("kf", mapOf("t" to "0", "v" to "${param.min}")), XmlNode("kf", mapOf("t" to "1", "v" to "${param.max}"))))
                            })
                        }
                        engine.setScene(scene(null, text, small))
                        val plain = requireNotNull(engine.still(0.0, side, side))
                        engine.setScene(scene(node, text, small))
                        val shots = List(frames) { requireNotNull(engine.still(it * 1000.0 / fps, side, side)) }
                        shots.forEachIndexed { index, rgba -> ImageIO.write(image(rgba, side), "png", folder.resolve("f%02d.png".format(index))) }
                        val details = shots.map(::detail)
                        val floor = 0.75 * (details + detail(plain)).max()
                        val best = shots.indices.filter { details[it] >= floor }.ifEmpty { shots.indices.toList() }.maxBy { change(shots[it], plain) }
                        ImageIO.write(image(requireNotNull(engine.still(best * 1000.0 / fps, hero, hero)), hero), "png", folder.resolve("hero.png"))
                        folder.resolve("best.txt").writeText("$best\n")
                        engine.loadEffect(XmlNode("effect", mapOf("id" to effect.definition.id, "remove" to "true")).toXml())
                    }
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun scene(effect: XmlNode?, text: Boolean, small: Boolean): String {
        val c = side / 2
        fun location() = XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "$c,$c,0"))))
        fun picture(id: Int, uri: String, half: Int, fx: XmlNode?) = XmlNode("shape", linkedMapOf("id" to "$id", "startTime" to "0", "endTime" to "${lengthMs - 1}",
            "s" to ".rect", "fillType" to "media", "fillImage" to uri, "mediaFillMode" to "fill"),
            listOfNotNull(location(), XmlNode("property", mapOf("name" to "size", "type" to "vec2", "value" to "$half,$half")), fx))
        val layers = when {
            text -> listOf(picture(1, GRID, c, null), XmlNode("text", linkedMapOf("id" to "2", "startTime" to "0", "endTime" to "${lengthMs - 1}", "fillType" to "color",
                "size" to "18", "wrapWidth" to "${side - 48}", "align" to "center", "font" to "googlefonts?name=Montserrat&weight=700"),
                listOfNotNull(location(), XmlNode("fillColor", mapOf("value" to "#ffffffff")), XmlNode("content", text = "TEXT"), effect)))
            small -> listOf(picture(1, GRID, c, null), picture(2, CITY, side / 4, effect))
            else -> listOf(picture(2, CITY, c, effect))
        }
        return XmlNode("scene", linkedMapOf("title" to "thumb", "width" to "$side", "height" to "$side", "fps" to "$fps", "totalTime" to "$lengthMs", "bgcolor" to "#00000000"), layers).toXml()
    }

    private fun grid(): BufferedImage = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB).also { image ->
        val g = image.createGraphics()
        g.color = Color(38, 40, 50)
        g.fillRect(0, 0, side, side)
        g.color = Color(78, 82, 98)
        g.stroke = BasicStroke(2f)
        for (k in 0..side step 32) { g.drawLine(k, 0, k, side); g.drawLine(0, k, side, k) }
        g.dispose()
    }

    private fun image(rgba: ByteArray, size: Int) = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { image ->
        for (i in 0 until size * size) {
            val p = i * 4
            fun channel(k: Int) = rgba[p + k].toInt() and 255
            image.setRGB(i % size, i / size, (channel(3) shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2))
        }
    }

    private fun grey(rgba: ByteArray, i: Int, k: Int): Double {
        val a = (rgba[i + 3].toInt() and 255) / 255.0
        return (rgba[i + k].toInt() and 255) + 128 * (1 - a)
    }

    private fun detail(rgba: ByteArray): Double {
        val lum = DoubleArray(rgba.size / 4) { 0.299 * grey(rgba, it * 4, 0) + 0.587 * grey(rgba, it * 4, 1) + 0.114 * grey(rgba, it * 4, 2) }
        val mean = lum.average()
        return sqrt(lum.sumOf { (it - mean) * (it - mean) } / lum.size)
    }

    private fun change(a: ByteArray, b: ByteArray): Double =
        (0 until a.size / 4).sumOf { i -> (0..2).sumOf { k -> abs(grey(a, i * 4, k) - grey(b, i * 4, k)) } } / (a.size / 4 * 3.0)

    private companion object {
        const val CITY = "community-thumb-city"
        const val GRID = "community-thumb-grid"
    }
}
