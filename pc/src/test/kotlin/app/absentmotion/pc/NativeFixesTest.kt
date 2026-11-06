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
            "-Dam.native=" + System.getProperty("am.native"), "-cp", System.getProperty("java.class.path"), NativeFixesChild::class.java.name, mode, path)
        val process = ProcessBuilder(command).redirectErrorStream(true).apply { environment().putAll(env) }.start()
        val text = process.inputStream.bufferedReader().readText()
        assertEquals(text, 0, process.waitFor())
        return text.lines().filter { it.startsWith("RESULT ") }.map { it.removePrefix("RESULT ") }
    }

    private fun ffmpeg(vararg args: String) =
        check(ProcessBuilder(listOf(DeskMedia.ffmpeg, "-v", "error", "-y") + args).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() == 0)

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
