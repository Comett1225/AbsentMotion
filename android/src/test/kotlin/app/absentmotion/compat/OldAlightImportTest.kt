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
}
