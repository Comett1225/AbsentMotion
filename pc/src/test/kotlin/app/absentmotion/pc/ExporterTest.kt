package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.media.Exporter
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ExporterTest {
    @Test fun exportsAndVerifiesTwoSecondsOfVideo() {
        val folder = File("build/export-test").also { it.deleteRecursively(); it.mkdirs() }
        val scene = Scene.parse("""<scene width="160" height="90" fps="30" totalTime="1999" bgcolor="#ff8c4020"><shape id="1" startTime="0" endTime="1999" s=".rect" fillType="color"><transform><location value="80,45,0"/></transform><fillColor value="#ff00ff00"/><property name="size" type="vec2" value="50,50"/></shape></scene>""")
        val options = Exporter.Options(Exporter.Format.MP4, 160, 90, 30, 500_000, folder.resolve("movie.mp4"))
        val exporter = Exporter(File("../app/src/main/assets"), File(".."))
        val updates = mutableListOf<Int>()
        val result = exporter.export(scene, null, options) { updates += it.frame }
        assertEquals(60, result.frames)
        assertEquals(160, result.width)
        assertEquals(90, result.height)
        assertEquals(2.0, result.duration, .04)
        assertTrue(options.output.length() > 0)
        assertEquals(60, updates.last())
    }
}
