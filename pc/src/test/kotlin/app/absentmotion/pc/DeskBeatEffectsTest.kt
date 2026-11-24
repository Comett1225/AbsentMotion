package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import app.absentmotion.pc.engine.DesktopNative
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

class DeskBeatEffectsTest {
    private val out = File("build/beat-effects").canonicalFile
    private val root = File("..").canonicalFile
    private val beats = listOf("beatpulse", "beatzoom", "beatshake", "beatrotate", "beatglow", "beatblur", "beatflash", "beatglitch", "beatrgbsplit")

    private fun picture(): String {
        out.mkdirs()
        val image = BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = Color(220, 60, 60); fillRect(0, 0, 600, 800); dispose() }
        val file = File(out, "red.png")
        ImageIO.write(image, "png", file)
        return file.toURI().toString().replaceFirst("file:/", "file:///")
    }

    private fun scene(engine: DesktopEngine, uri: String, id: String, marks: Boolean, width: Int, height: Int): Scene {
        val def = engine.definitions.first { it.key == id }
        val props = def.params.joinToString("") { """<property name="${it.id}" type="${it.type}" value="${it.default}"/>""" }
        return Scene.parse("""<scene width="$width" height="$height" fps="30" totalTime="2000" bgcolor="#ff000000">${if (marks) """<bookmark t="0"/><bookmark t="500"/>""" else ""}
<media uri="$uri" filename="red.png" title="red.png" type="image/png" width="600" height="800"/>
<shape id="1" startTime="0" endTime="2000" fillType="media" fillImage="$uri" mediaFillMode="fill" s=".rect"><transform><location value="${width / 2},${height / 2},0"/></transform>
<property name="size" type="vec2" value="${width / 4},${width / 3}"/><effect id="absent.$id" hidden="false">$props</effect></shape></scene>""")
    }

    private fun drawn(pixel: (Int, Int) -> Int, w: Int, h: Int): FloatArray {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            val p = pixel(x, y)
            if ((p shr 16 and 255) > 60 || (p shr 8 and 255) > 60 || (p and 255) > 60) { minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
        }
        return floatArrayOf((minX + maxX + 1) / 2f, (minY + maxY + 1) / 2f, (maxX - minX + 1).toFloat(), (maxY - minY + 1).toFloat())
    }

    private fun check(label: String, box: FloatArray, cx: Float, cy: Float, w: Float, h: Float) {
        val text = "$label: drawn centre ${box[0]},${box[1]} size ${box[2]}x${box[3]}, layer centre $cx,$cy size ${w}x$h"
        assertTrue(text, abs(box[0] - cx) <= w * 0.12f && abs(box[1] - cy) <= h * 0.04f)
        assertTrue(text, box[2] >= w * 0.8f && box[3] >= h * 0.8f && box[2] <= w * 1.6f && box[3] <= h * 1.6f)
    }

    @Test fun everyBeatEffectKeepsThePictureWhereTheLayerIs() {
        val uri = picture()
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            for ((width, height) in listOf(1080 to 1920, 1920 to 1080, 720 to 720)) for (id in beats) for (marks in listOf(false, true)) {
                engine.setScene(scene(engine, uri, id, marks, width, height), null)
                val ow = width / 3; val oh = height / 3
                for (time in listOf(0, 100, 600)) {
                    val image = engine.render(time, ow, oh)
                    check("$id ${width}x$height marks=$marks t=$time", drawn(image::getRGB, ow, oh), ow / 2f, oh / 2f, width / 2f / 3, width * 2f / 3 / 3)
                }
            }
        }
    }

    @Test fun beatEffectsStayOnTheLayerInAZoomedAndPannedPreview() {
        val uri = picture()
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            val handle = DesktopEngine::class.java.getDeclaredField("handle").apply { isAccessible = true }.getLong(engine)
            val w = 440; val h = 811
            val bitmap = org.jetbrains.skia.Bitmap().apply { allocPixels(org.jetbrains.skia.ImageInfo(w, h, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL, org.jetbrains.skia.ColorSpace.sRGB)) }
            for (id in listOf("beatpulse", "beatzoom", "beatrotate")) for ((scale, dx, dy) in listOf(Triple(0.37f, 20f, 50f), Triple(0.6f, -105f, -170f))) {
                engine.setScene(scene(engine, uri, id, true, 1080, 1920), null)
                val pixels = bitmap.peekPixels()!!
                assertTrue(DesktopNative.renderPreview(handle, 100.0, w, h, pixels.addr, pixels.rowBytes.toLong(), scale, dx, dy, 0xff000000.toInt(), 0L, 8, false, 1, 0f, 0, null, floatArrayOf(), 1f))
                val bytes = bitmap.readPixels()!!
                val box = drawn({ x, y -> val i = (y * w + x) * 4; ((bytes[i + 2].toInt() and 255) shl 16) or ((bytes[i + 1].toInt() and 255) shl 8) or (bytes[i].toInt() and 255) }, w, h)
                check("$id preview scale $scale", box, dx + 540 * scale, dy + 960 * scale, 540 * scale, 720 * scale)
            }
        }
    }
}
