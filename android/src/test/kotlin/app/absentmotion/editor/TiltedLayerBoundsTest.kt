package app.absentmotion.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

class TiltedLayerBoundsTest {
    @Before fun needsTheHostEngine() = Assume.assumeTrue("build it with tools/pivot.sh --lib", PivotEngine.loaded)

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    private fun tilted(random: Random): String {
        val z = -1500f + random.nextFloat() * 3000f
        val scale = 1f + random.nextFloat() * 29f
        return """<scene width="540" height="960" fps="30"><shape id="1" startTime="0" endTime="1000" s=".rect" fillType="color" fillColor="#ffff0000"><transform>""" +
            """<location value="270,480,${f(z)}"/><scale value="${f(scale)},${f(scale)}"/><rotation value="${f(random.nextFloat() * 360f - 180f)}"/>""" +
            """<tilt value="${f(random.nextFloat() * 178f - 89f)},${f(random.nextFloat() * 178f - 89f)}"/></transform><property name="size" type="vec2" value="40,25"/></shape></scene>"""
    }

    @Test fun aTiltedLayersBoundsHoldEveryPaintedPixelOrAreLeftOut() {
        val random = Random(8)
        val wrong = (1..300).map { tilted(random) }.filter { xml ->
            val b = PivotEngine.nativeLayerBounds(xml, 1, 500.0) ?: return@filter false
            val drawn = PivotEngine.nativeDrawn(xml, 500.0)!!
            val painted = drawn.indices.filter { drawn[it].toInt() != 0 }
            !b.all { kotlin.math.abs(it) < 100000f } || painted.any { i -> i % 540 < b[0] - 2 || i % 540 + 1 > b[2] + 2 || i / 540 < b[1] - 2 || i / 540 + 1 > b[3] + 2 }
        }
        assertEquals(wrong.take(1).toString(), 0, wrong.size)
    }
}
