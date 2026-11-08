package app.absentmotion.editor

import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

class EdgeValueEngineTest {
    @Before fun needsTheHostEngine() = Assume.assumeTrue("build it with tools/pivot.sh --lib", PivotEngine.loaded)

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    private fun Random.pick(vararg v: Float) = v[nextInt(v.size)]

    private fun Random.value(lo: Float, hi: Float, vararg special: Float) = if (nextInt(4) == 0) pick(*special) else lo + nextFloat() * (hi - lo)

    private fun Random.layer(id: Int, parent: Int?, tilted: Boolean): String {
        val start = if (nextInt(4) == 0) 0 else nextInt(500)
        val end = if (nextInt(7) == 0) start else start + 1 + nextInt(2000)
        val tilt = if (tilted) """<tilt value="${f(value(-90f, 90f, 0f, 90f, -90f, 89.9f, 180f))},${f(value(-90f, 90f, 0f, 90f, -90f))}"/>""" else ""
        return """<shape id="$id" startTime="$start" endTime="$end" s=".rect" fillType="color"${parent?.let { " parent=\"$it\"" } ?: ""}><transform>""" +
            """<location value="${f(value(-2000f, 3000f, 0f, 540f))},${f(value(-2000f, 3000f, 0f, 960f))},${f(value(-3000f, 3000f, 0f, -1000f, 2000f))}"/>""" +
            """<pivot value="${f(value(-500f, 500f, 0f, 10000f))},${f(value(-500f, 500f, 0f, -10000f))},0"/>""" +
            """<scale value="${f(value(-5f, 5f, 0f, 1f, -1f, 0.000001f, 100f))},${f(value(-5f, 5f, 0f, 1f, -1f, 0.000001f))}"/>""" +
            """<rotation value="${f(value(-720f, 720f, 0f, 90f, 720f, -540f))}"/>""" +
            """<skew value="${f(value(-1.5f, 1.5f, 0f, 1.2f))},${f(value(-1.5f, 1.5f, 0f, -1.2f))}"/>$tilt""" +
            """</transform><property name="size" type="vec2" value="${f(value(0f, 400f, 0f, 1f))},${f(value(0f, 400f, 0f, 1f))}"/></shape>"""
    }

    private fun Random.scene(): Pair<String, Int> {
        val n = 1 + nextInt(6)
        val tilted = nextBoolean()
        val layers = (1..n).joinToString("") { i -> layer(i, if (i > 1 && nextBoolean()) 1 + nextInt(i - 1) else null, tilted) }
        return """<scene width="${pick(1080f, 400f, 1f).toInt()}" height="${pick(1920f, 400f, 1f).toInt()}" fps="${pick(1f, 30f, 240f).toInt()}">$layers</scene>""" to n
    }

    @Test fun matricesPlacementsAndSelectionFramesStayFiniteAtExtremeValues() {
        val random = Random(20261108)
        repeat(400) {
            val (xml, n) = random.scene()
            val ids = (1..n).map { it.toLong() }
            val times = ids.map { random.nextInt(2600).toDouble() }
            val places = PivotEngine.nativePlaces(xml, ids.toLongArray(), times.toDoubleArray())!!
            assertTrue("not finite: $xml", places.all { it.isFinite() })
            for ((id, t) in ids.zip(times)) {
                val q = PivotEngine.nativeSelectionQuad(xml, id, t) ?: continue
                assertTrue("frame not finite: $xml", q.all { it.isFinite() })
            }
        }
    }

    @Test fun aParentWithZeroOrNegativeScaleKeepsEveryChildFinite() {
        for (scale in listOf("0,0", "0,1", "-1,-1", "0.000001,0.000001", "-3,0")) {
            val chain = (1..18).joinToString("") { i ->
                """<shape id="$i" startTime="0" endTime="1000" s=".rect" fillType="color"${if (i > 1) " parent=\"${i - 1}\"" else ""}><transform><location value="10,5,0"/>""" +
                    """<scale value="$scale"/><rotation value="45"/></transform><property name="size" type="vec2" value="40,40"/></shape>"""
            }
            val xml = """<scene width="400" height="400" fps="30">$chain</scene>"""
            val ids = (1L..18L).toList()
            val places = PivotEngine.nativePlaces(xml, ids.toLongArray(), DoubleArray(ids.size) { 500.0 })!!
            assertTrue("scale $scale", places.all { it.isFinite() })
        }
    }
}
