package app.absentmotion.compat

import app.absentmotion.model.Scene
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OldAlightImportTest {
    private fun scene(attrs: String, spill: Boolean, soft: Boolean = true) = Scene.parse(
        """<scene width="100" height="100" $attrs><shape id="1" s=".rect">""" +
            """<effect id="absent.backdrop"><property name="mix" type="float" value="0" />${if (spill) """<property name="spill" type="bool" value="true" />""" else ""}</effect>""" +
            """<effect id="absent.exposure"><property name="stops" type="float" value="1" />${if (soft) """<property name="soft" type="bool" value="true" />""" else ""}</effect>""" +
            "</shape></scene>")

    @Test fun onlyOldAlightImportsGetTheTipAndOnlyOnce() {
        assertTrue(scene("""amver="1028425" am="app.absentmotion"""", spill = false).oldAlightImport())
        assertTrue(scene("""am="com.alightcreative.motion/5.0.273"""", spill = true, soft = false).oldAlightImport())
        assertFalse(scene("""amver="1028425" am="app.absentmotion"""", spill = true).oldAlightImport())
        assertFalse(scene("""am="app.absentmotion"""", spill = false).oldAlightImport())
        val old = scene("""amver="1028425"""", spill = false)
        assertFalse(old.withReimportTipShown().oldAlightImport())
        assertTrue(old.fromAlight)
        assertFalse(scene("""am="app.absentmotion"""", spill = true).fromAlight)
    }

    @Test fun aProjectMadeInTheAppIsNotAnAlightImport() {
        assertFalse(Scene.create("t", 100, 100, 30, 0).fromAlight)
        assertFalse(scene("""amver="1002592" am="app.absentmotion"""", spill = false, soft = false).oldAlightImport())
        assertTrue(scene("""amver="1002592" am="app.absentmotion" alightImport="true"""", spill = false).oldAlightImport())
    }

    @Test fun alightImportsWithLegacyScaleTilesGetTheTip() {
        fun tiles(sizing: String) = Scene.parse("""<scene width="100" height="100" amver="1028425" am="app.absentmotion"><shape id="1" s=".rect">""" +
            """<effect id="absent.tiles"><property name="sizing" type="float" value="$sizing" /></effect></shape></scene>""")
        assertTrue(tiles("1.000000").oldAlightImport())
        assertFalse(tiles("2.000000").oldAlightImport())
    }

    @Test fun alightImportsWithClassicStripesGetTheTip() {
        fun stripes(style: String) = Scene.parse("""<scene width="100" height="100" amver="1028425" am="app.absentmotion"><shape id="1" s=".rect">""" +
            """<effect id="absent.stripes"><property name="count" type="float" value="4" />$style</effect></shape></scene>""")
        assertTrue(stripes("").oldAlightImport())
        assertFalse(stripes("""<property name="style" type="float" value="1" />""").oldAlightImport())
    }
}
