package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PageCurlFollowTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun scene(effect: String) = Scene.parse(
        """<scene title="c" width="720" height="720" fps="30" totalTime="2000" bgcolor="#ff000000">
          <shape id="1" label="c" startTime="0" endTime="2000" fillType="color" s=".circle">$effect
          <property name="size" type="vec2" value="100,100" /></shape></scene>""",
    )

    private fun curl(scene: Scene): XmlNode = scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }

    @Test fun anAlightPageCurlKeepsCurlingTheWholeLayerBox() {
        val result = migration.migrate(scene("""<effect id="com.alightcreative.effects.pagecurl" locallyApplied="true">
            <property name="progress" type="float" value="0.3" /><property name="angle" type="float" value="38" /></effect>"""))
        val c = curl(result.scene)
        assertEquals("absent.pagecurl", c["id"])
        assertEquals("false", c.children.single { it["name"] == "follow" }["value"])
    }

    @Test fun olderProjectsKeepTheBoxAndNewPageCurlsFollowTheShape() {
        val follow = catalog.find("absent.pagecurl")!!.params.single { it.id == "follow" }
        assertEquals("false", follow.missingDefault)
        assertEquals("true", follow.default)
    }
}
