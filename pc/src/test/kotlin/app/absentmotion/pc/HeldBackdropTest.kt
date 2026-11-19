package app.absentmotion.pc

import android.content.Desk
import androidx.compose.ui.unit.IntSize
import app.absentmotion.engine.EngineThread
import app.absentmotion.engine.PreviewView
import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import app.absentmotion.pc.engine.DesktopNative
import app.absentmotion.pc.engine.NativeVideoSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class HeldBackdropTest {
    private fun scene(fps: Int) = Scene.parse(
        """<scene width="200" height="200" fps="30" totalTime="1000" bgcolor="#ff000000">
        <shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location><kf t="0" v="20,100,0"/><kf t="1" v="180,100,0"/></location></transform>
        <property name="size" type="vec2" value="40,200"/><fillColor value="#ffff0000"/></shape>
        <shape id="2" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="100,100,0"/></transform>
        <property name="size" type="vec2" value="200,200"/><fillColor value="#ffffffff"/>
        <effect id="absent.backdrop"/><effect id="absent.tile"/><effect id="absent.timequant"><property name="fps" type="float" value="$fps"/></effect></shape></scene>"""
    )

    private fun frames(fps: Int, times: List<Int>): List<List<Int>> {
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(scene(fps), null)
            return times.map { t -> engine.render(t, 200, 200).let { image -> (0 until 200 step 4).map { x -> image.getRGB(x, 50) } } }
        }
    }

    @Test fun copyBackgroundWithFrameRateHoldsTheBackgroundForEachStep() {
        val shown = frames(5, listOf(0, 33, 67, 100, 133, 167, 200))
        for (i in 1..5) assertEquals("frame $i", shown[0], shown[i])
        assertNotEquals(shown[0], shown[6])
    }

    @Test fun aPictureThatArrivesAfterTheFirstFrameShowsInTheHeldBackground() {
        val xml = Scene.parse(
            """<scene width="200" height="200" fps="30" totalTime="1000" bgcolor="#ff000000">
            <shape id="1" startTime="0" endTime="1000" fillType="media" fillImage="pic" mediaFillMode="stretch" s=".rect"><transform><location value="100,100,0"/></transform><property name="size" type="vec2" value="200,200"/></shape>
            <shape id="2" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="100,100,0"/></transform>
            <property name="size" type="vec2" value="200,200"/><fillColor value="#ffffffff"/>
            <effect id="absent.backdrop"/><effect id="absent.tile"/><effect id="absent.timequant"><property name="fps" type="float" value="5"/></effect></shape></scene>"""
        )
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            val handle = DesktopEngine::class.java.getDeclaredField("handle").apply { isAccessible = true }.getLong(engine)
            engine.setScene(xml, null)
            engine.render(0, 200, 200)
            DesktopNative.setPixels(handle, "pic", 2, 2, IntArray(4) { 0xffff0000.toInt() })
            assertEquals(0xffff0000.toInt(), engine.render(0, 200, 200).getRGB(100, 100))
        }
    }

    @Test fun aVideoThatOpensAfterTheFirstPreviewShowsInTheHeldBackground() = runBlocking {
        val file = File("../testdata/media/test-video.mp4").canonicalFile
        val xml = Scene.parse(
            """<scene width="320" height="180" fps="30" totalTime="1000" bgcolor="#ff000000">
            <shape id="1" startTime="0" endTime="1000" fillType="media" fillVideo="late.mp4" outTime="1000" mediaFillMode="stretch" s=".rect"><transform><location value="160,90,0"/></transform><property name="size" type="vec2" value="320,180"/></shape>
            <shape id="2" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="160,90,0"/></transform>
            <property name="size" type="vec2" value="320,180"/><fillColor value="#ffffffff"/>
            <effect id="absent.backdrop"/><effect id="absent.tile"/><effect id="absent.timequant"><property name="fps" type="float" value="5"/></effect></shape></scene>"""
        )
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        try {
            engine.effects.first { it.definitions.isNotEmpty() }
            engine.attachDesktop(this, 1, IntSize(320, 180))
            engine.setScene(xml)
            engine.awaitMedia()
            fun show(): Int {
                val serial = engine.setView(PreviewView(0, 1f, 0f, 0f, handles = 0))
                while (engine.rendered.value.serial != serial) Thread.sleep(1)
                return checkNotNull(engine.pixelAt(0.5f, 0.5f))
            }
            val blank = show()
            @Suppress("UNCHECKED_CAST")
            val videos = EngineThread::class.java.getDeclaredField("videos").apply { isAccessible = true }.get(engine) as HashMap<String, AutoCloseable>
            videos["late.mp4"] = NativeVideoSource(file)
            val until = System.nanoTime() + 5_000_000_000L
            var shown = show()
            while (shown == blank && System.nanoTime() < until) { Thread.sleep(20); shown = show() }
            assertNotEquals(Integer.toHexString(blank), blank, shown)
        } finally {
            engine.close()
        }
    }

    @Test fun aFrameRateAboveTheProjectRateKeepsTheBackgroundLive() {
        val shown = frames(60, listOf(0, 33))
        assertNotEquals(shown[0], shown[1])
    }
}
