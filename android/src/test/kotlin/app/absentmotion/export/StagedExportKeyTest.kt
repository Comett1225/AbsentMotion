package app.absentmotion.export

import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StagedExportKeyTest {
    private val xml = """<scene title="p" width="1080" height="1920" fps="30" totalTime="2000"><shape id="1" startTime="0" endTime="2000" /></scene>"""

    @Test fun theSameProjectAndSettingsGiveTheSameKeyLater() {
        val scene = Scene.parse(xml)
        val settings = ExportSettings(1080, 30)
        val first = StagedExports.key(scene, settings, ExportKind.Video, 0)
        Thread.sleep(5)
        assertEquals(first, StagedExports.key(Scene.parse(scene.toXml()), settings, ExportKind.Video, 0))
    }

    @Test fun aChangeInTheProjectOrTheSettingsGivesANewKey() {
        val scene = Scene.parse(xml)
        val settings = ExportSettings(1080, 30)
        val key = StagedExports.key(scene, settings, ExportKind.Video, 0)
        assertNotEquals(key, StagedExports.key(Scene.parse(xml.replace("endTime=\"2000\"", "endTime=\"1500\"")), settings, ExportKind.Video, 0))
        assertNotEquals(key, StagedExports.key(scene, settings.copy(fps = 60), ExportKind.Video, 0))
        assertNotEquals(key, StagedExports.key(scene, settings.copy(codec = Codec.Hevc), ExportKind.Video, 0))
    }
}
