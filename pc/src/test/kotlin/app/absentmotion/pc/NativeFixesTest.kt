package app.absentmotion.pc

import app.absentmotion.desk.DeskMedia
import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import app.absentmotion.pc.engine.DesktopNative
import app.absentmotion.pc.engine.NativeVideoSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeFixesTest {
    private val out = File("build/native-fixes").also { it.mkdirs() }

    private fun child(mode: String, path: String, env: Map<String, String>): List<String> {
        val command = listOf(File(System.getProperty("java.home"), "bin/java").path, "-Djava.awt.headless=true",
            "-Dam.native=" + System.getProperty("am.native"), "-Dam.mediaReader=" + System.getProperty("am.mediaReader"), "-cp", System.getProperty("java.class.path"), NativeFixesChild::class.java.name, mode, path)
        val process = ProcessBuilder(command).redirectErrorStream(true).apply { environment().putAll(env) }.start()
        val text = process.inputStream.bufferedReader().readText()
        assertEquals(text, 0, process.waitFor())
        return text.lines().filter { it.startsWith("RESULT ") }.map { it.removePrefix("RESULT ") }
    }

    private fun ffmpeg(vararg args: String) =
        check(ProcessBuilder(listOf(app.absentmotion.Judge.ffmpeg, "-v", "error", "-y") + args).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() == 0)

    @Test fun engineReadsAndWritesDotNumbersUnderACommaLocale() {
        assertEquals(listOf("matrix 0.5 0.25 540.5 960.25", "outline M -100.5 -100.5L 100.5 -100.5L 100.5 100.5L -100.5 100.5Z"),
            child("locale", "", mapOf("LANG" to "de_DE.UTF-8", "LC_ALL" to "de_DE.UTF-8", "AM_SOFTWARE_RENDER" to "1")))
    }

    @Test fun decodeErrorNearTheEndDoesNotFreezeTheLastFrames() {
        val clean = out.resolve("clean.mp4")
        val bad = out.resolve("bad-end.mp4")
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30:duration=4", "-c:v", "libx264", "-g", "60", "-bf", "2", "-pix_fmt", "yuv420p", clean.path)
        ffmpeg("-i", clean.path, "-c", "copy", "-bsf:v", "noise=amount=if(eq(n\\,117)\\,3\\,0)", bad.path)
        assertEquals(listOf("differ true"), child("corrupt", bad.absolutePath, mapOf("AM_VIDEO_SOFTWARE" to "1")))
    }

    @Test fun aSceneNestedTooDeepIsRefusedAndTheEngineCarriesOn() {
        fun nested(depth: Int) = "<scene width=\"100\" height=\"100\" fps=\"30\" totalTime=\"999\">" + "<a>".repeat(depth) + "</a>".repeat(depth) + "</scene>"
        val handle = DesktopNative.create()
        try {
            assertEquals(null, DesktopNative.setScene(handle, nested(500)))
            assertTrue(DesktopNative.setScene(handle, nested(200_000)) != null)
            assertEquals(null, DesktopNative.setScene(handle, nested(10)))
        } finally {
            DesktopNative.destroy(handle)
        }
    }

    @Test fun smoothingAndDatamoshRunWithoutAGraphicsChip() {
        assertEquals(listOf("gpu 0", "blend true", "flow true", "mosh 0 true true"), child("nogpu", "", mapOf("AM_SOFTWARE_RENDER" to "1")))
    }

    @Test fun twoLayersOnOneVideoKeepTheirOwnDecoders() {
        val file = File("../testdata/media/test-video.mp4").canonicalFile
        val source = NativeVideoSource(file)
        val handle = DesktopNative.create()
        val video = VideoDecodeTest()
        try {
            DesktopNative.setScene(handle, video.sceneXml(file, 1280, 720))
            fun timed(block: () -> Unit): Long = System.nanoTime().let { start -> block(); System.nanoTime() - start }
            source.showLayer(handle, file.path, 1, 0, true)
            val alone = timed { for (k in 1..60) source.showLayer(handle, file.path, 1, video.frameUs(k), true) }
            source.showLayer(handle, file.path, 1, 0, true)
            source.showLayer(handle, file.path, 2, video.frameUs(45), true)
            val both = timed {
                for (k in 1..30) {
                    source.showLayer(handle, file.path, 1, video.frameUs(k), true)
                    source.showLayer(handle, file.path, 2, video.frameUs(45 + k), true)
                }
            }
            println("[two layers] alone=${alone / 1_000_000}ms both=${both / 1_000_000}ms")
            assertTrue("alone=$alone both=$both", both < alone * 4)
        } finally {
            source.close()
            DesktopNative.destroy(handle)
        }
    }

    @Test fun errorDiffusionDitherRunsOnDesktop() {
        val root = File("..").canonicalFile
        fun frame(pattern: Int) = DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(Scene.parse("""<scene width="64" height="64" totalTime="999" fps="30" bgcolor="#ff000000"><shape id="1" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="32,32,0" /></transform><fillColor value="#ff808080" /><property name="size" type="vec2" value="24,24" /><effect id="absent.retrodither"><property name="pattern" type="int" value="$pattern" /><property name="pixel" type="float" value="1" /></effect></shape></scene>"""))
            engine.render(0, 64, 64).getRGB(0, 0, 64, 64, null, 0, 64)
        }
        assertFalse(frame(12).contentEquals(frame(6)))
    }

    @Test fun lightsInsideAGroupReachScatterPieces() {
        val root = File("..").canonicalFile
        fun frame(color: String) = DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(Scene.parse("""<scene width="200" height="200" totalTime="999" fps="30" bgcolor="#ff000000"><embedScene id="10" label="G" startTime="0" endTime="999"><transform><location value="100,100,0" /></transform><scene width="200" height="200" totalTime="999" fps="30" retime="off" bgcolor="#00000000"><light id="11" startTime="0" endTime="999" type="ambient"><transform><location value="100,100,0" /></transform><property name="color" type="color" value="$color" /><property name="intensity" type="float" value="1" /></light><shape id="12" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="100,100,0" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="60,60" /><effect id="absent.scatter3d" /></shape></scene></embedScene></scene>"""))
            engine.render(0, 200, 200).getRGB(0, 0, 200, 200, null, 0, 200)
        }
        assertFalse(frame("#ffff0000").contentEquals(frame("#ff0000ff")))
    }

    @Test fun aPointLightLightsScatterPiecesWhereTheyAre() {
        val root = File("..").canonicalFile
        val pixels = DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(Scene.parse("""<scene width="400" height="200" totalTime="999" fps="30" bgcolor="#ff000000"><light id="1" startTime="0" endTime="999" type="point"><transform><location value="330,100,-40" /></transform><property name="intensity" type="float" value="1" /><property name="falloff" type="float" value="100" /></light><shape id="2" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="50,100,0" /><pivot value="-150,0" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="300,60" /><effect id="absent.scatter3d" /></shape></scene>"""))
            engine.render(0, 400, 200).getRGB(0, 0, 400, 200, null, 0, 400)
        }
        fun green(from: Int, to: Int) = (90 until 110).sumOf { y -> (from until to).sumOf { x -> pixels[y * 400 + x] shr 8 and 255 } } / (20 * (to - from))
        val near = green(320, 340)
        val far = green(60, 80)
        assertTrue("near the light $near, far from it $far", near > 60 && near > far * 3)
    }

    @Test fun theOutlineFollowsTheTurnOfRasterExtrudeAndFlip() {
        val root = File("..").canonicalFile
        fun compare(effect: String, tilt: String) = DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(Scene.parse("""<scene width="400" height="400" totalTime="999" fps="30" bgcolor="#ff000000"><shape id="1" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="200,200,0" />$tilt</transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="160,100" />$effect</shape></scene>"""))
            val pixels = engine.render(0, 400, 400).getRGB(0, 0, 400, 400, null, 0, 400)
            var left = 400; var top = 400; var right = -1; var bottom = -1
            for (y in 0 until 400) for (x in 0 until 400) {
                val c = pixels[y * 400 + x]
                if (maxOf(c shr 16 and 255, c shr 8 and 255, c and 255) > 60) { left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1) }
            }
            val q = engine.quad(1, 0)!!
            val xs = (0..3).map { q[2 * it] }
            val ys = (0..3).map { q[2 * it + 1] }
            val label = "$effect $tilt: drawn $left,$top,$right,$bottom outline ${xs.minOrNull()},${ys.minOrNull()},${xs.maxOrNull()},${ys.maxOrNull()}"
            assertTrue(label, right > left)
            assertEquals(label, left.toFloat(), xs.minOrNull()!!, 4f)
            assertEquals(label, right.toFloat(), xs.maxOrNull()!!, 4f)
            assertEquals(label, top.toFloat(), ys.minOrNull()!!, 4f)
            assertEquals(label, bottom.toFloat(), ys.maxOrNull()!!, 4f)
        }
        val thin = """<property name="depth" type="float" value="0.01" /><property name="shading" type="float" value="0" /><property name="shine" type="float" value="0" />"""
        compare("""<effect id="absent.rasterextrude"><property name="rotX" type="float" value="0" /><property name="rotY" type="float" value="50" />$thin</effect>""", "")
        compare("""<effect id="absent.rasterextrude"><property name="rotX" type="float" value="25" /><property name="rotY" type="float" value="-30" /><property name="rotZ" type="float" value="15" /><property name="position" type="vec2" value="20,-15" /><property name="scale" type="float" value="0.8" />$thin</effect>""", "")
        compare("""<effect id="absent.rasterextrude"><property name="rotX" type="float" value="0" /><property name="rotY" type="float" value="0" />$thin</effect>""", """<tilt value="0,40" />""")
        compare("""<effect id="absent.flip"><property name="rotX" type="float" value="0" /><property name="rotY" type="float" value="45" /><property name="shading" type="float" value="0" /><property name="shine" type="float" value="0" /></effect>""", "")
        compare("""<effect id="absent.flip"><property name="rotX" type="float" value="10" /><property name="rotY" type="float" value="60" /><property name="pivot" type="vec2" value="80,0" /><property name="pivotDepth" type="float" value="0.3" /><property name="shading" type="float" value="0" /><property name="shine" type="float" value="0" /></effect>""", "")
    }

    private fun shot(xml: String, size: Int = 400, time: Int = 0): IntArray {
        val root = File("..").canonicalFile
        return DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(Scene.parse(xml))
            engine.render(time, size, size).getRGB(0, 0, size, size, null, 0, size)
        }
    }

    @Test fun aLayerWithAnEffectInsideAGroupIsNotCutAtTheGroupFrame() {
        fun scene(effect: String) = """<scene width="400" height="400" totalTime="999" fps="30" bgcolor="#ff000000"><embedScene id="10" label="G" startTime="0" endTime="999"><transform><location value="200,200,0" /><scale value="0.5,0.5" /></transform><scene width="400" height="400" totalTime="999" fps="30" retime="off" bgcolor="#00000000"><shape id="12" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="200,200,0" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="760,760" />$effect</shape></scene></embedScene></scene>"""
        val plain = shot(scene(""))
        val tinted = shot(scene("""<effect id="absent.invert"><property name="amount" type="float" value="0" /></effect>"""))
        assertEquals(plain[200 * 400 + 15] and 0xffffff, 0xffffff)
        assertEquals(plain[200 * 400 + 15], tinted[200 * 400 + 15])
    }

    @Test fun tilesInsideAShrunkGroupStillFillOnlyTheGroupFrame() {
        val pixels = shot("""<scene width="400" height="400" totalTime="999" fps="30" bgcolor="#ff000000"><embedScene id="10" label="G" startTime="0" endTime="999"><transform><location value="200,200,0" /><scale value="0.5,0.5" /></transform><scene width="400" height="400" totalTime="999" fps="30" retime="off" bgcolor="#00000000"><shape id="12" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="200,200,0" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100,100" /><effect id="absent.tiles"><property name="zoom" type="float" value="1" /></effect></shape></scene></embedScene></scene>""")
        assertEquals(0xffffff, pixels[200 * 400 + 120] and 0xffffff)
        assertEquals(0, pixels[200 * 400 + 20] and 0xffffff)
    }

    @Test fun aKeyframedPaletteKeepsItsColours() {
        val palette = "000000 ff0000 00ff00 0000ff"
        fun scene(property: String) = """<scene width="64" height="64" totalTime="999" fps="30" bgcolor="#ff000000"><shape id="1" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="32,32,0" /></transform><fillColor value="#ffd02020" /><property name="size" type="vec2" value="48,48" /><effect id="absent.palettemap"><property name="source" type="int" value="1" />$property</effect></shape></scene>"""
        val plain = shot(scene("""<property name="palette" type="string" value="$palette" />"""), 64)
        val keyed = shot(scene("""<property name="palette" type="string"><kf t="0" v="$palette" /></property>"""), 64)
        assertArrayEquals(plain, keyed)
    }

    @Test fun effectsSharedWithChildrenRunOnTheParentsClock() {
        val pixels = shot("""<scene width="200" height="200" totalTime="1000" fps="30" bgcolor="#ff000000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="50,100,0" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="40,40" /><effect id="absent.invert" applyToChildren="true"><property name="amount" type="float"><kf t="0" v="0" /><kf t="1" v="1" /></property></effect></shape><shape id="2" parent="1" startTime="500" endTime="1000" fillType="color" s=".rect"><transform><location value="100,0,0" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="40,40" /></shape></scene>""", 200, 500)
        val reds = pixels.map { it shr 16 and 255 }.filter { it > 20 }
        assertTrue("$reds", reds.isNotEmpty() && reds.all { it < 200 })
    }

    @Test fun aClippingMaskOverACopyBackgroundLayerKeepsTheBackground() {
        val pixels = shot("""<scene width="200" height="200" totalTime="999" fps="30" bgcolor="#ff000000"><shape id="1" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="100,100,0" /></transform><fillColor value="#ffff0000" /><property name="size" type="vec2" value="200,200" /></shape><shape id="2" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="100,100,0" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="120,120" /><effect id="absent.backdrop" /><effect id="absent.invert" /></shape><shape id="3" startTime="0" endTime="999" fillType="color" s=".rect" clippingMask="true"><transform><location value="100,100,0" /></transform><fillColor value="#ff0000ff" /><property name="size" type="vec2" value="20,20" /></shape></scene>""", 200)
        val inside = pixels[100 * 200 + 60]
        assertTrue(Integer.toHexString(inside), (inside shr 16 and 255) < 60 && (inside shr 8 and 255) > 200 && (inside and 255) > 200)
        assertEquals(0xff0000ff.toInt(), pixels[100 * 200 + 100])
    }

    @Test fun echoCopiesOfAMaskLayerMaskAsOne() {
        val pixels = shot("""<scene width="200" height="200" totalTime="1000" fps="30" bgcolor="#ff000000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="100,100,0" /></transform><fillColor value="#ffff0000" /><property name="size" type="vec2" value="200,200" /></shape><shape id="2" startTime="0" endTime="1000" fillType="color" s=".rect" blending="mask"><transform><location><kf t="0" v="20,100,0" /><kf t="1" v="180,100,0" /></location></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="20,20" /><effect id="absent.echo"><property name="count" type="float" value="2" /><property name="seconds" type="float" value="0.5" /></effect></shape></scene>""", 200, 990)
        assertEquals(255, pixels[100 * 200 + 100] shr 16 and 255)
    }

    @Test fun layerQuadFollowsTheCameraViewSwitchAfterAStill() {
        val handle = DesktopNative.create()
        try {
            DesktopNative.setScene(handle, """<scene width="400" height="400" totalTime="999" fps="30"><camera id="1" startTime="0" endTime="999" type="perspective"><transform><location value="260,200,-200" /></transform><fov value="60" /></camera><shape id="2" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="200,200,0" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="50,50" /></shape></scene>""")
            DesktopNative.setCameraView(handle, false)
            DesktopNative.layerMatrix(handle, 2, 0.0)
            val flat = DesktopNative.layerQuad(handle, 2, 0.0)!!
            DesktopNative.still(handle, 0.0, 40, 40, true)
            assertArrayEquals(flat, DesktopNative.layerQuad(handle, 2, 0.0)!!, 0.001f)
            DesktopNative.setCameraView(handle, true)
            assertFalse(flat.contentEquals(DesktopNative.layerQuad(handle, 2, 0.0)!!))
        } finally {
            DesktopNative.destroy(handle)
        }
    }

    @Test fun aTiltedCameraIsPickedWhereItLooks() {
        val handle = DesktopNative.create()
        try {
            DesktopNative.setScene(handle, """<scene width="400" height="400" totalTime="999" fps="30" depthOrder="true"><camera id="1" startTime="0" endTime="999" type="perspective"><transform><location value="200,200,-400" /><tilt value="40,0" /></transform><fov value="60" /></camera></scene>""")
            DesktopNative.setCameraView(handle, false)
            assertEquals(0L, DesktopNative.hitTest(handle, 200f, 100f, 0.0))
            assertEquals(1L, DesktopNative.hitTest(handle, 200f, 600f, 0.0))
            DesktopNative.setScene(handle, """<scene width="400" height="400" totalTime="999" fps="30" depthOrder="true"><camera id="1" startTime="0" endTime="999" type="perspective"><transform><location value="200,200,-400" /><tilt value="85,0" /></transform><fov value="60" /></camera></scene>""")
            assertEquals(1L, DesktopNative.hitTest(handle, 200f, 200f, 0.0))
        } finally {
            DesktopNative.destroy(handle)
        }
    }

    @Test fun shapeIconTurnsMediaAndWaitsForItsPicture() {
        val handle = DesktopNative.create()
        try {
            DesktopNative.setScene(handle, """<scene width="100" height="100" totalTime="999" fps="30"><media uri="pic" orientation="90" /></scene>""")
            val xml = """<scene width="100" height="100" totalTime="999" fps="30"><shape id="1" startTime="0" endTime="999" fillType="media" fillImage="pic" s=".rect"><transform><location value="50,50,0" /></transform><path-stroke direction="centered"><color value="#ffffffff" /><size value="2" /></path-stroke><property name="size" type="vec2" value="40,20" /></shape></scene>"""
            fun opaque(icon: ByteArray): Pair<Int, Int> {
                var (w, h) = 0 to 0
                for (y in 0 until 32) for (x in 0 until 32) if (icon[(y * 32 + x) * 4 + 3].toInt() != 0) { w = maxOf(w, x + 1); h = maxOf(h, y + 1) }
                val left = (0 until 32).first { x -> (0 until 32).any { y -> icon[(y * 32 + x) * 4 + 3].toInt() != 0 } }
                val top = (0 until 32).first { y -> (0 until 32).any { x -> icon[(y * 32 + x) * 4 + 3].toInt() != 0 } }
                return (w - left) to (h - top)
            }
            assertTrue(DesktopNative.shapeIcon(handle, xml, 32, -1)?.all { it.toInt() == 0 } ?: true)
            assertTrue(DesktopNative.setPixels(handle, "pic", 4, 2, IntArray(8) { 0xffff0000.toInt() }))
            val (w, h) = opaque(DesktopNative.shapeIcon(handle, xml, 32, -1)!!)
            assertTrue("w=$w h=$h", h > w)
        } finally {
            DesktopNative.destroy(handle)
        }
    }
}

object NativeFixesChild {
    @JvmStatic fun main(args: Array<String>) {
        val handle = DesktopNative.create()
        when (args[0]) {
            "locale" -> {
                val error = DesktopNative.setScene(handle, """<scene width="1080" height="1920" totalTime="2999" fps="30"><shape id="1" startTime="0" endTime="2999" fillType="color" s=".rect"><transform><location value="540.5,960.25,0" /><scale value="0.5,0.25" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100.5,100.5" /></shape></scene>""")
                check(error == null) { error.toString() }
                val m = DesktopNative.layerMatrix(handle, 1, 0.0)!!
                println("RESULT matrix ${m[0]} ${m[4]} ${m[2]} ${m[5]}")
                println("RESULT outline ${DesktopNative.layerOutline(handle, 1, 0.0)}")
            }
            "nogpu" -> {
                val w = 64
                val h = 36
                fun frame(shift: Int) = ByteArray(w * h * 4) { i -> if (i % 4 == 3) -1 else ((i / 4 % w + shift) * 3).toByte() }
                val flow = floatArrayOf(4f, 5f, 6f, 1f, 0.15f, 4f)
                println("RESULT gpu ${DesktopNative.gpuState()}")
                println("RESULT blend ${DesktopNative.interpolate(handle, "v", 1, w, h, frame(0), frame(6), null, null, null, 0.5f, 1, 0f, flow)}")
                println("RESULT flow ${DesktopNative.interpolate(handle, "v", 1, w, h, frame(0), frame(6), null, null, null, 0.5f, 2, 0.5f, flow)}")
                val from = DesktopNative.moshStart(handle, "v", 1, longArrayOf(0, 33_000), floatArrayOf(1f, 16f, 0.05f, 0.1f, 0.3f))
                println("RESULT mosh $from ${DesktopNative.moshFrame(handle, "v", 1, 0, w, h, frame(0))} ${DesktopNative.moshFrame(handle, "v", 1, 1, w, h, frame(6))}")
            }
            "corrupt" -> {
                val file = File(args[1])
                val video = VideoDecodeTest()
                val source = NativeVideoSource(file)
                check(DesktopNative.setScene(handle, video.sceneXml(file, 320, 240)) == null)
                val stills = listOf(116, 119).map { k ->
                    check(source.show(handle, file.absolutePath, video.frameUs(k), true) >= 0)
                    DesktopNative.still(handle, k * 1000.0 / 30, 320, 240, true)!!
                }
                println("RESULT differ ${!stills[0].contentEquals(stills[1])}")
                source.close()
            }
        }
        DesktopNative.destroy(handle)
        kotlin.system.exitProcess(0)
    }
}
