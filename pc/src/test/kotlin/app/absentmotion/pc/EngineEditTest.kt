package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class EngineEditTest {
    @Test fun transformChangesRenderedImage() {
        val root = File("..").canonicalFile
        val scene = Scene.parse(root.resolve("testdata/scenes/shapes.xml").readText())
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(scene, root.resolve("testdata/scenes"))
            val before = engine.render(500, 270, 480).getRGB(0, 0, 270, 480, null, 0, 270)
            val changed = scene.update(1001, 500) { it.withTransform("location", 600f, 600f, 0f) }
            engine.setScene(changed, root.resolve("testdata/scenes"))
            val after = engine.render(500, 270, 480).getRGB(0, 0, 270, 480, null, 0, 270)
            assertFalse(before.contentEquals(after))
        }
    }
}
