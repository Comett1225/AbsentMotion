package app.absentmotion.pc

import app.absentmotion.effects.readAddedEffectEntries
import app.absentmotion.engine.NativeEngine
import app.absentmotion.model.XmlNode
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

class ModderPackCheckTest {
    private val side = 256
    private val hero = 720

    @Test fun convertedEffectsLoadAndRender() {
        val pack = System.getProperty("am.modderPack")?.let(::File)
        assumeTrue("pass -PmodderPack=<folder of effect .json> -PmodderPackOut=<folder> to run", pack != null)
        val out = File(requireNotNull(System.getProperty("am.modderPackOut")) { "pass -PmodderPackOut=<folder>" }).canonicalFile
        val renders = out.resolve("renders").also { it.mkdirs() }
        val meta = pack!!.resolve("meta.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: JSONObject()
        val work = Files.createTempDirectory("modder-pack").toFile()
        val lines = mutableListOf<String>()
        val failed = mutableListOf<String>()
        try {
            NativeEngine().use { engine ->
                check(engine.setPictureFile(CITY, File(System.getProperty("am.assets"), "presets/placeholders/city.webp").path))
                check(engine.setPictureFile(STAR, work.resolve("star.png").also { ImageIO.write(star(), "png", it) }.path))
                val plain = listOf(CITY, STAR).map { picture ->
                    engine.setScene(scene(null, picture))
                    requireNotNull(engine.still(T, side, side)).also { ImageIO.write(image(it, side), "png", renders.resolve("_input.$picture.png")) }
                }
                for (file in pack.listFiles().orEmpty().filter { it.extension == "json" && it.name != "meta.json" }.sortedBy { it.name }) {
                    val key = file.nameWithoutExtension
                    val info = meta.optJSONObject(key) ?: JSONObject()
                    val problem = runCatching { check(engine, file, key, info, plain, renders, work) }.getOrElse { "error: ${it.message}" }
                    lines += "$key\t${problem ?: "ok"}"
                    if (problem != null) failed += key
                }
            }
        } finally {
            work.deleteRecursively()
        }
        out.resolve("check.tsv").writeText(lines.joinToString("\n", postfix = "\n"))
        check(failed.isEmpty()) { "${failed.size} of ${lines.size} effects failed, see ${out.resolve("check.tsv")}: ${failed.joinToString()}" }
    }

    private fun check(engine: NativeEngine, file: File, key: String, info: JSONObject, plain: List<ByteArray>, renders: File, work: File): String? {
        val effect = file.inputStream().use { readAddedEffectEntries(file.name, it, work) }.single().second
            .getOrElse { return "parse: ${it.message ?: it.javaClass.simpleName}" }
        val definition = effect.definition
        val loaded = engine.loadEffect(definition.runtimeXml())
        if (loaded.substringBefore('\n') != "ok") return "compile: " + loaded.replace('\n', ' ').replace('\t', ' ').take(600)
        try {
            val node = definition.instance()
            val changedId = info.optString("change").takeIf { it.isNotEmpty() }
            val changedNode = changedId?.let { id ->
                val type = definition.params.first { it.id == id }.type
                node.copy(children = node.children.map {
                    if (it["name"] == id) XmlNode("property", mapOf("name" to id, "type" to type, "value" to info.getString("changeValue"))) else it
                })
            }
            fun render(effect: XmlNode, picture: String, name: String): ByteArray {
                engine.setScene(scene(effect, picture))
                return requireNotNull(engine.still(T, side, side)) { "no picture" }.also { ImageIO.write(image(it, side), "png", renders.resolve("$key$name.png")) }
            }
            val base = listOf(render(node, CITY, ""), render(node, STAR, ".star"))
            val changed = changedNode?.let { listOf(render(it, CITY, ".changed"), render(it, STAR, ".star.changed")) }
            engine.setScene(scene(node))
            engine.still(T, hero, hero)?.let { ImageIO.write(image(it, hero), "png", renders.resolve("$key.hero.png")) }
            val same = { a: List<ByteArray>, b: List<ByteArray>, limit: Double -> a.indices.all { change(a[it], b[it]) < limit } }
            return when {
                base[0].indices.step(4).all { base[0][it + 3].toInt() == 0 } -> "transparent"
                !info.optBoolean("flatOk") && detail(base[0]) < 0.5 -> "flat colour"
                !info.optBoolean("generator") && same(base, plain, 0.25) && (changed == null || same(changed, plain, 0.25)) -> "no visible change"
                changed != null && !info.optBoolean("changeMayMatch") && same(changed, base, 0.05) -> "changed setting ($changedId) does nothing"
                else -> null
            }
        } finally {
            engine.loadEffect(XmlNode("effect", mapOf("id" to definition.id, "remove" to "true")).toXml())
        }
    }

    private fun scene(effect: XmlNode?, picture: String = CITY): String {
        val c = PROJECT / 2
        val layer = XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "4000",
            "s" to ".rect", "fillType" to "media", "fillImage" to picture, "mediaFillMode" to "fill"),
            listOfNotNull(XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "$c,$c,0")))),
                XmlNode("property", mapOf("name" to "size", "type" to "vec2", "value" to "$c,$c")), effect))
        return XmlNode("scene", linkedMapOf("title" to "check", "width" to "$PROJECT", "height" to "$PROJECT", "fps" to "30", "totalTime" to "4000",
            "bgcolor" to "#00000000"), listOf(layer)).toXml()
    }

    private fun star(): BufferedImage = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB).also { image ->
        fun points(outer: Double, inner: Double) = java.awt.Polygon().apply {
            for (k in 0 until 32) {
                val r = if (k % 2 == 0) outer else inner
                val a = Math.PI * k / 16 - Math.PI / 2
                addPoint((256 + r * Math.cos(a)).toInt(), (256 + r * Math.sin(a)).toInt())
            }
        }
        val g = image.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        val outer = points(222.0, 140.0)
        g.color = java.awt.Color(214, 120, 120)
        g.stroke = java.awt.BasicStroke(11f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
        g.drawPolygon(outer)
        g.color = java.awt.Color(250, 250, 250)
        g.fillPolygon(outer)
        g.color = java.awt.Color(70, 140, 230)
        g.fillPolygon(points(98.0, 57.0))
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
        const val CITY = "modder-pack-city"
        const val STAR = "modder-pack-star"
        const val PROJECT = 1080
        const val T = 1000.0
    }
}
