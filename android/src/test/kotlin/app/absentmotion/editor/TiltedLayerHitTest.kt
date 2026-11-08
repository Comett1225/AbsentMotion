package app.absentmotion.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

class TiltedLayerHitTest {
    @Before fun needsTheHostEngine() = Assume.assumeTrue("build it with tools/pivot.sh --lib", PivotEngine.loaded)

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    private fun tilted(random: Random): String {
        val z = -1500f + random.nextFloat() * 3000f
        val scale = 1f + random.nextFloat() * 29f
        return """<scene width="540" height="960" fps="30"><shape id="1" startTime="0" endTime="1000" s=".rect" fillType="color" fillColor="#ffff0000"><transform>""" +
            """<location value="270,480,${f(z)}"/><scale value="${f(scale)},${f(scale)}"/><rotation value="${f(random.nextFloat() * 360f - 180f)}"/>""" +
            """<tilt value="${f(random.nextFloat() * 178f - 89f)},${f(random.nextFloat() * 178f - 89f)}"/></transform><property name="size" type="vec2" value="40,25"/></shape></scene>"""
    }

    private fun inverse(m: FloatArray): DoubleArray {
        val a = m.map { it.toDouble() }
        val c = doubleArrayOf(
            a[4] * a[8] - a[5] * a[7], a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            a[5] * a[6] - a[3] * a[8], a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            a[3] * a[7] - a[4] * a[6], a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        val det = a[0] * c[0] + a[1] * c[3] + a[2] * c[6]
        return DoubleArray(9) { c[it] / det }
    }

    @Test fun tappingWhereATiltedLayerIsBehindTheCameraDoesNotSelectIt() {
        val random = Random(9)
        var ghosts = 0
        var hits = 0
        repeat(80) {
            val xml = tilted(random)
            val inv = inverse(PivotEngine.nativePlaces(xml, longArrayOf(1), doubleArrayOf(500.0))!!.copyOfRange(0, 9))
            val points = FloatArray(400) { if (it % 2 == 0) random.nextFloat() * 540f else random.nextFloat() * 960f }
            val found = PivotEngine.nativeHits(xml, 500.0, points)!!
            for (i in found.indices) {
                if (found[i] != 1L) continue
                hits++
                if (inv[6] * points[2 * i] + inv[7] * points[2 * i + 1] + inv[8] < 0) ghosts++
            }
        }
        assertTrue(hits > 1000)
        assertEquals(0, ghosts)
    }
}
