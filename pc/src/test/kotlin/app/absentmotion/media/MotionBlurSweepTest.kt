package app.absentmotion.media

import android.content.Context
import android.net.Uri
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.Smoothing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MotionBlurSweepTest {
    private val out = File("build/motion-blur-sweep").absoluteFile.also { it.mkdirs() }
    private val context = object : Context() {
        override fun getExternalFilesDir(type: String?): File = out
    }
    private val image = Uri.fromFile(File("../testdata/media/test-image.jpg").absoluteFile).toString()
    private val video = Uri.fromFile(File("../testdata/media/test-video.mp4").absoluteFile).toString()
    private val blur = """<effect id="absent.motionblur" hidden="false"/>"""

    private fun scene(body: String) = """<scene title="mb" width="1080" height="1920" fps="30" totalTime="1000" bgcolor="#ff000000">""" +
        """<media uri="$image" type="image/jpeg" width="1280" height="720"/><media uri="$video" type="video/mp4" width="1280" height="720" duration="5000000" fps="25"/>""" +
        body + "</scene>"

    private fun layer(id: Int, transform: String, size: String = "300,200", fill: String = "image", extra: String = "", attrs: String = "") =
        """<shape id="$id" startTime="0" endTime="999" ${when (fill) {
            "image" -> "fillType=\"media\" fillImage=\"$image\" mediaFillMode=\"stretch\""
            "video" -> "fillType=\"media\" fillVideo=\"$video\" outTime=\"1000\" mediaFillMode=\"stretch\""
            else -> "fillType=\"color\""
        }} s=".rect"$attrs><transform>$transform</transform><fillColor value="#ffffffff"/><property name="size" type="vec2" value="$size"/>$extra</shape>"""

    private val moving = """<location><kf t="0" v="300,960,0"/><kf t="1" v="780,960,0"/></location>"""

    private val cases = linkedMapOf(
        "zoomed full-screen image" to scene(layer(1, """<location value="540,960,0"/><scale><kf t="0" v="1.2,1.2"/><kf t="1" v="1.8,1.8"/></scale>""", "540,960", extra = blur)),
        "zoomed full-screen video" to scene(layer(1, """<location value="540,960,0"/><scale><kf t="0" v="1.2,1.2"/><kf t="1" v="1.8,1.8"/></scale>""", "540,960", "video", blur)),
        "full-screen video panning" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", blur)),
        "rotating" to scene(layer(1, """<location value="540,960,0"/><rotation><kf t="0" v="0"/><kf t="1" v="90"/></rotation>""", extra = blur)),
        "auto-shake" to scene(layer(1, """<location value="540,960,0"/>""", extra = """<effect id="absent.autoshake" hidden="false"><property name="magnitude" type="float" value="200"/><property name="frequency" type="float" value="8"/></effect>$blur""")),
        "moving parent" to scene("""<nullobj id="9" startTime="0" endTime="999"><transform>$moving</transform></nullobj>""" +
            layer(1, """<location value="0,0,0"/>""", extra = blur, attrs = " parent=\"9\"")),
        "blurred group" to scene("""<embedScene id="10" label="Group" startTime="0" endTime="999"><transform>$moving</transform><scene width="1080" height="1920" fps="30" totalTime="1000" bgcolor="#00000000">""" +
            layer(11, """<location value="540,960,0"/>""") + """</scene>$blur</embedScene>"""),
        "blurred layer in a group" to scene("""<embedScene id="10" label="Group" startTime="0" endTime="999"><transform><location value="540,960,0"/><scale value="0.8,0.8"/></transform><scene width="1080" height="1920" fps="30" totalTime="1000" bgcolor="#00000000">""" +
            layer(11, moving, extra = blur) + "</scene></embedScene>"),
        "screen blend" to scene(layer(1, moving, extra = blur, attrs = " blending=\"screen\"")),
        "glow under the blur" to scene(layer(1, moving, extra = """<effect id="absent.glow" hidden="false"/>$blur""")),
        "off the bottom right" to scene(layer(1, """<location><kf t="0" v="900,1700,0"/><kf t="1" v="1100,1900,0"/></location>""", "400,400", extra = blur)),
        "off the top left" to scene(layer(1, """<location><kf t="0" v="-100,-100,0"/><kf t="1" v="200,200,0"/></location>""", "400,400", extra = blur)),
        "huesaturation + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.huesaturation" hidden="false"/>$blur""")),
        "huesaturation + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.huesaturation" hidden="false"/>$blur""")),
        "blur + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.blur" hidden="false"/>$blur""")),
        "blur + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.blur" hidden="false"/>$blur""")),
        "directionalblur + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.directionalblur" hidden="false"/>$blur""")),
        "directionalblur + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.directionalblur" hidden="false"/>$blur""")),
        "wavewarp + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.wavewarp" hidden="false"/>$blur""")),
        "wavewarp + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.wavewarp" hidden="false"/>$blur""")),
        "tiles + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.tiles" hidden="false"/>$blur""")),
        "tiles + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.tiles" hidden="false"/>$blur""")),
        "pixelate + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.pixelate" hidden="false"/>$blur""")),
        "pixelate + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.pixelate" hidden="false"/>$blur""")),
        "retrodither + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.retrodither" hidden="false"/>$blur""")),
        "retrodither + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.retrodither" hidden="false"/>$blur""")),
        "vignette + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.vignette" hidden="false"/>$blur""")),
        "vignette + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.vignette" hidden="false"/>$blur""")),
        "mirror + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.mirror" hidden="false"/>$blur""")),
        "mirror + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.mirror" hidden="false"/>$blur""")),
        "tile + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.tile" hidden="false"/>$blur""")),
        "tile + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.tile" hidden="false"/>$blur""")),
        "crop + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.crop" hidden="false"/>$blur""")),
        "crop + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.crop" hidden="false"/>$blur""")),
        "offset + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.offset" hidden="false"/>$blur""")),
        "offset + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.offset" hidden="false"/>$blur""")),
        "rastertransform + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.rastertransform" hidden="false"/>$blur""")),
        "rastertransform + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.rastertransform" hidden="false"/>$blur""")),
        "chromaticzoomblur + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.chromaticzoomblur" hidden="false"/>$blur""")),
        "chromaticzoomblur + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.chromaticzoomblur" hidden="false"/>$blur""")),
        "lensblur + motion blur" to scene(layer(1, moving, extra = """<effect id="absent.lensblur" hidden="false"/>$blur""")),
        "lensblur + motion blur on full-screen video" to scene(layer(1, """<location><kf t="0" v="440,960,0"/><kf t="1" v="640,960,0"/></location><scale value="1.3,1.3"/>""", "540,960", "video", """<effect id="absent.lensblur" hidden="false"/>$blur""")),
        "fast and wide" to scene(layer(1, """<location><kf t="0" v="-1500,960,0"/><kf t="1" v="2500,960,0"/></location>""", "540,500", extra = blur)),
    )

    private data class Look(val left: Int, val top: Int, val right: Int, val bottom: Int, val lit: Int, val cx: Double, val cy: Double)

    private fun look(pixels: ByteArray, w: Int, h: Int): Look {
        var left = w; var top = h; var right = -1; var bottom = -1; var lit = 0; var sx = 0.0; var sy = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            val v = maxOf(pixels[i].toInt() and 0xff, pixels[i + 1].toInt() and 0xff, pixels[i + 2].toInt() and 0xff)
            if (v > 24) { lit++; sx += x; sy += y; left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y) }
        }
        return Look(left, top, right, bottom, lit, if (lit > 0) sx / lit else 0.0, if (lit > 0) sy / lit else 0.0)
    }

    @Test fun fullBlurMatchesTheQuickBlurEverywhere() = runBlocking {
        val engine = EngineThread(0xff000000.toInt(), context)
        val report = StringBuilder()
        var wrong = 0
        try {
            for ((name, xml) in cases) for ((w, h) in listOf(540 to 960)) {
                val fast = engine.exportSession(xml, Smoothing(), 30) { previewStill(500, w, h, false) } ?: continue
                val full = engine.exportSession(xml, Smoothing(), 30) { previewStill(500, w, h, true) } ?: continue
                val export = engine.exportSession(xml, Smoothing(), 30) { still(500, w, h) } ?: continue
                val a = look(fast, w, h)
                for ((kind, px) in listOf("paused" to full, "export" to export)) {
                    val b = look(px, w, h)
                    val ok = a.lit == 0 && b.lit == 0 || (kotlin.math.abs(a.cx - b.cx) <= w * 0.03 && kotlin.math.abs(a.cy - b.cy) <= h * 0.03 &&
                        b.lit >= a.lit * 0.7 && b.lit <= a.lit * 1.4)
                    if (!ok) {
                        wrong++
                        File(out, "${name.replace(' ', '_')}-${w}-$kind.rgba").writeBytes(px)
                        File(out, "${name.replace(' ', '_')}-${w}-fast.rgba").writeBytes(fast)
                    }
                    report.append("$name ${w}x$h $kind ${if (ok) "ok" else "WRONG"} fast=$a $kind=$b\n")
                }
            }
        } finally { engine.close() }
        println(report)
        assertTrue(report.toString(), wrong == 0)
    }
}
