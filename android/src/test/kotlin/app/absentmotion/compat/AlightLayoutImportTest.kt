package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlightLayoutImportTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun import(body: String, am: String = "com.alightcreative.motion/5.0.273.1028425"): XmlNode =
        migration.migrate(Scene.parse("""<scene title="p" width="1280" height="720" fps="30" totalTime="3999" am="$am">$body</scene>""")).scene.node

    private fun XmlNode.layer(id: String): XmlNode = children.first { it["id"] == id }

    private fun XmlNode.location(): XmlNode = child("transform")!!.child("location")!!

    @Test fun pivotsKeepTheLayerWhereAlightDrawsIt() {
        val root = import(
            """<shape id="1" startTime="0" endTime="3999" s=".rect"><transform><location value="200,250,0" /><pivot value="0,100" /><scale value="2,2" /></transform></shape>
            <nullobj id="5" startTime="0" endTime="3999"><transform><location value="400,520,0" /><pivot value="100,0" /><rotation value="90" /></transform></nullobj>
            <shape id="6" parent="5" startTime="0" endTime="3999" s=".rect"><transform><location value="150,0,0" /></transform></shape>
            <shape id="7" startTime="0" endTime="3999" s=".rect"><transform><location><kf t="0" v="0,0,5" /><kf t="1" v="100,0,5" e="cubicBezier 0.0 1.0 1.0 1.0" /></location><pivot value="10,-20" /></transform></shape>
            <embedScene id="8" parent="5" startTime="0" endTime="3999"><transform><pivot value="-40,15" /></transform><scene width="1280" height="720" totalTime="3999" /></embedScene>""",
        )
        assertEquals("200.000000,350.000000,0.000000", root.layer("1").location()["value"])
        assertEquals("500.000000,520.000000,0.000000", root.layer("5").location()["value"])
        assertEquals("50.000000,0.000000,0.000000", root.layer("6").location()["value"])
        val keys = root.layer("7").location().keyframes
        assertEquals(listOf("10.000000,-20.000000,5.000000", "110.000000,-20.000000,5.000000"), keys.map { it.v })
        assertEquals("cubicBezier 0.0 1.0 1.0 1.0", keys[1].e)
        assertEquals("-140.000000,15.000000,0.000000", root.layer("8").location()["value"])
    }

    @Test fun projectsMadeInTheAppKeepTheirPivots() {
        val root = import("""<shape id="1" startTime="0" endTime="3999" s=".rect"><transform><location value="200,250,0" /><pivot value="0,100" /></transform></shape>""", "app.absentmotion")
        assertEquals("200,250,0", root.layer("1").location()["value"])
    }

    @Test fun groupsLongerThanTheirInsideHoldItsLastFrame() {
        fun group(attrs: String, inner: Int = 1999, retime: String = "off") = import(
            """<embedScene id="1" startTime="0" endTime="3999" $attrs><transform><location value="640,360,0" /></transform>
            <scene width="1280" height="720" totalTime="$inner" retime="$retime"><shape id="2" startTime="0" endTime="$inner" s=".rect" /></scene></embedScene>""",
        ).layer("1").child("scene")!!["retime"]
        assertEquals("freeze", group(""))
        assertEquals("off", group("", inner = 3999))
        assertEquals("off", group("speed=\"2\""))
        assertEquals("loop", group("", retime = "loop"))
        assertEquals("freeze", group("inTime=\"2500\"", inner = 5000))
    }

    @Test fun plainScreenLightenAndDarkenLayersSquareTheirOpacityLikeMultiply() {
        for (mode in listOf("screen", "lighten", "darken")) {
            val root = import("""<shape id="1" startTime="0" endTime="3999" blending="$mode" s=".rect"><transform><location value="0,0,0" /><opacity value="0.3" /></transform></shape>""")
            assertEquals(0.09f, root.layer("1").child("transform")!!.child("opacity")!!["value"]!!.toFloat(), 1e-5f)
        }
        val blurred = import("""<shape id="1" startTime="0" endTime="3999" blending="screen" s=".rect"><transform><location value="0,0,0" /><opacity value="0.3" /></transform><effect id="com.alightcreative.effects.gaussianblur"><property name="strength" type="float" value="0.5" /></effect></shape>""")
        assertEquals(0.3f, blurred.layer("1").child("transform")!!.child("opacity")!!["value"]!!.toFloat(), 1e-5f)
        val plain = import("""<shape id="1" startTime="0" endTime="3999" blending="hard-light" s=".rect"><transform><location value="0,0,0" /><opacity value="0.3" /></transform></shape>""")
        assertEquals(0.3f, plain.layer("1").child("transform")!!.child("opacity")!!["value"]!!.toFloat(), 1e-5f)
    }

    @Test fun plainHardAndSoftLightLayersBlendLikeAlight() {
        fun source(mode: String, effect: String = "", am: String = "com.alightcreative.motion/5.0.273.1028425") = import(
            """<shape id="1" startTime="0" endTime="3999" blending="$mode" s=".rect"><transform><location value="0,0,0" /><opacity value="0.5" /></transform>$effect</shape>""", am,
        ).layer("1")["blendSource"]
        assertEquals("premul", source("hard-light"))
        assertEquals("premul", source("soft-light"))
        assertEquals(null, source("overlay"))
        assertEquals(null, source("hard-light", """<effect id="com.alightcreative.effects.gaussianblur"><property name="strength" type="float" value="0.5" /></effect>"""))
        assertEquals(null, source("hard-light", am = "app.absentmotion"))
    }

    @Test fun olderImportsWithPivotsGetTheReimportTip() {
        val old = Scene.parse("""<scene width="100" height="100" amver="1028425" am="app.absentmotion"><shape id="1" s=".rect"><transform><pivot value="0,10" /></transform></shape></scene>""")
        assertTrue(old.oldAlightImport())
        val fresh = Scene(import("""<shape id="1" startTime="0" endTime="3999" s=".rect"><transform><location value="0,0,0" /><pivot value="0,10" /></transform></shape>"""))
        assertFalse(fresh.oldAlightImport())
    }
}
