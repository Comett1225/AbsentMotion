package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WipeMigrationTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun migrate(id: String, layer: String = """fillType="color"""", media: String = "", vararg props: Pair<String, String>): XmlNode {
        val effect = """<effect id="com.alightcreative.effects.$id">${props.joinToString("") { (k, v) -> """<property name="$k" value="$v" />""" }}</effect>"""
        val scene = Scene.parse("""<scene title="p" width="1080" height="1080" fps="30" totalTime="1000">$media<shape id="1" startTime="0" endTime="1000" $layer s=".rect">
            $effect<property name="size" type="vec2" value="110,82.5" /></shape></scene>""")
        return migration.migrate(scene).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
    }

    private fun XmlNode.numbers(name: String): List<Float> = children.first { it["name"] == name }["value"]!!.split(',').map { it.trim().toFloat() }

    private fun XmlNode.number(name: String): Float = numbers(name).first()

    private val wipe = arrayOf("progress" to "123.75", "offset" to "900", "centerPoint" to "40,-25", "feather" to "25", "direction" to "1", "repeatCount" to "4.75")

    @Test fun radialWipeKeepsAlightsFractionalWedgesAndTexturePixelCenter() {
        val picture = migrate("radialwipe", """fillType="media" fillImage="content://pic" mediaFillMode="fill"""", """<media uri="content://pic" width="1600" height="1200" orientation="0" />""", *wipe)
        assertEquals("absent.radialwipe", picture["id"])
        assertEquals(1f, picture.number("wedges"), 1e-6f)
        assertEquals(4.75f, picture.number("count"), 1e-6f)
        assertEquals(123.75f, picture.number("progress"), 1e-6f)
        assertEquals(25f, picture.number("feather"), 1e-6f)
        assertEquals(1f, picture.number("direction"), 1e-6f)
        assertEquals(0.5f + 40f * 0.1375f / 220, picture.numbers("center")[0], 1e-5f)
        assertEquals(0.5f - 25f * 0.1375f / 165, picture.numbers("center")[1], 1e-5f)
        val turned = migrate("radialwipe", """fillType="media" fillImage="content://pic" mediaFillMode="stretch"""", """<media uri="content://pic" width="1200" height="1600" orientation="90" />""", *wipe)
        assertEquals(0.5f + 40f * 0.1375f / 220, turned.numbers("center")[0], 1e-5f)
        val solid = migrate("radialwipe", props = wipe)
        assertEquals(0.5f + 40f / 220, solid.numbers("center")[0], 1e-5f)
        assertEquals(0.5f - 25f / 165, solid.numbers("center")[1], 1e-5f)
        assertEquals(1f, migrate("radialwipe").number("wedges"), 1e-6f)
        assertEquals("0.000000", catalog.find("absent.radialwipe")!!.params.single { it.id == "wedges" }.default)
    }

    @Test fun smoothEdgesFillsInAlightsDefaultStrength() {
        assertEquals(0.02f * 0.57f * 165f, migrate("smoothedges").number("blur"), 1e-4f)
        assertEquals(0.515f * 0.57f * 165f, migrate("smoothedges", props = arrayOf("strength" to "0.515")).number("blur"), 1e-3f)
    }
}
