package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class AlightBlendTest {
    private fun pixel(mode: String, background: String, colour: String, opacity: Float, premul: Boolean): IntArray {
        val source = if (premul) """ blendSource="premul"""" else ""
        val scene = Scene.parse(
            """<scene width="40" height="40" fps="30" totalTime="1000" bgcolor="#ff$background">
            <shape id="1" startTime="0" endTime="1000" fillType="color" blending="$mode"$source s=".rect"><transform><location value="20,20,0"/><opacity value="$opacity"/></transform>
            <property name="size" type="vec2" value="20,20"/><fillColor value="#ff$colour"/></shape></scene>"""
        )
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(scene, null)
            val argb = engine.render(0, 40, 40).getRGB(20, 20)
            return intArrayOf(argb shr 16 and 255, argb shr 8 and 255, argb and 255)
        }
    }

    private fun near(expected: IntArray, actual: IntArray, slack: Int) = expected.indices.all { abs(expected[it] - actual[it]) <= slack }

    @Test fun alightHardAndSoftLightBlendThePremultipliedColourWithOpacityTwice() {
        val measured = listOf(
            listOf("hard-light", "c06040", "4080c0", 0.5f, intArrayOf(156, 84, 60)),
            listOf("hard-light", "c06040", "4080c0", 0.25f, intArrayOf(181, 91, 61)),
            listOf("hard-light", "e0e0c0", "e0d020", 0.5f, intArrayOf(217, 214, 150)),
            listOf("soft-light", "e0e0c0", "4080c0", 0.5f, intArrayOf(203, 210, 186)),
            listOf("soft-light", "c06040", "e0d020", 0.5f, intArrayOf(189, 94, 57)),
        )
        for ((mode, background, colour, opacity, alight) in measured) {
            val shown = pixel(mode as String, background as String, colour as String, opacity as Float, true)
            assertTrue("$mode $background $colour $opacity: ${shown.toList()}", near(alight as IntArray, shown, 4))
        }
    }

    @Test fun projectsWithoutTheImportMarkKeepTheirBlend() {
        val plain = pixel("hard-light", "c06040", "4080c0", 0.5f, false)
        assertTrue(plain.toList().toString(), near(intArrayOf(144, 96, 112), plain, 3))
    }
}
