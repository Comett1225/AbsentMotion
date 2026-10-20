package app.absentmotion

import app.absentmotion.model.Easing
import app.absentmotion.model.EasingCatalog
import app.absentmotion.model.handles
import app.absentmotion.model.reversed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasingHandlesTest {
    private val ranges: Map<String, List<ClosedFloatingPointRange<Float>?>> = mapOf(
        "cubicBezier" to listOf(0f..1f, -2f..3f, 0f..1f, -2f..3f),
        "bounce" to listOf(0.02f..1f, 0f..0.95f),
        "elastic" to listOf(0.02f..0.98f, null, 0f..1f, 0f..1f),
        "cyclic" to listOf(0.02f..1f, 0f..1f, 0.02f..0.98f, 0f..1f, null),
        "random" to List(6) { 0f..1f },
        "step" to listOf(0.02f..1f, 0f..1f),
        "elasticStep" to listOf(0.02f..1f, 0f..1f),
    )

    private val presets = EasingCatalog.families.flatten().map { it.easing }

    private val starts = presets + listOf(
        Easing.parse("random 0.3 0.7 0.1"),
        Easing("bounce", listOf(5f, -3f)),
        Easing("elastic", listOf(0f, 9f, 4f, -1f)),
        Easing("cyclic", listOf(-1f, 3f, 1f, 2f, 7f)),
        Easing("step", listOf(0f, 2f)),
        Easing("elasticStep", listOf(3f, -2f)),
        Easing("cubicBezier", listOf(1.5f, -4f, -1f, 5f)),
        Easing.parse("local cubicBezier 0.2 0.1 0.3 0.9"),
        Easing.parse("local step 0.1 0.5"),
    )

    private val grid = listOf(-1e9f, -5f, -0.5f, 0f, 0.07f, 0.33f, 0.5f, 0.77f, 1f, 1.5f, 5f, 1e9f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)

    private fun reverse(e: Easing) = e.copy(prefixes = e.prefixes + "reverse")

    private fun assertClose(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals("number $it of $actual", expected[it], actual[it], 1e-4f) }
    }

    private fun assertLegal(start: Easing, result: Easing, what: String) {
        val type = if (start.type == "linear") "cubicBezier" else start.type
        assertEquals(what, type, result.type)
        assertEquals(what, start.prefixes, result.prefixes)
        val legal = ranges.getValue(type)
        assertEquals(what, legal.size, result.p.size)
        result.p.forEachIndexed { i, v ->
            assertTrue("$what: number $i is $v", v.isFinite())
            legal[i]?.let { assertTrue("$what: number $i is $v, outside $it", v in it) }
        }
        assertEquals(what, result, Easing.parse(result.toString()))
    }

    @Test fun everyHandleGivesALegalEasingOfItsTypeForAnyDragPosition() {
        for (start in starts + Easing.Linear) for (e in listOf(start, reverse(start))) {
            val hs = e.handles()
            assertTrue("$e has ${hs.size} handles", hs.size in 1..5)
            hs.forEachIndexed { i, h ->
                assertTrue("$e handle $i at ${h.x}, ${h.y}", h.x.isFinite() && h.y.isFinite() && h.x in 0f..1f)
                for (x in grid) for (y in grid) {
                    val moved = h.moved(x, y)
                    assertLegal(e, moved, "$e handle $i to $x, $y")
                    assertEquals(hs.size, moved.handles().size)
                }
            }
        }
    }

    @Test fun grabbingAHandleWithoutMovingItKeepsTheCurve() {
        for (start in presets.filter { it.type != "linear" } + Easing.parse("local step 0.1 0.5")) for (e in listOf(start, reverse(start))) {
            e.handles().forEachIndexed { i, h ->
                val moved = h.moved(h.x, h.y)
                assertEquals("$e handle $i", e.p.size, moved.p.size)
                e.p.indices.forEach { assertEquals("$e handle $i number $it", e.p[it], moved.p[it], 1e-4f) }
            }
        }
        val old = Easing.parse("random 0.3 0.7 0.1")
        old.handles().forEach { h -> assertClose(listOf(0.3f, 0.7f, 0.1f, 0.5f, 0.5f, 0f), h.moved(h.x, h.y).p) }
    }

    @Test fun aStraightLineBecomesABezierWhenAHandleIsPulled() {
        val (first, second) = Easing.Linear.handles()
        assertTrue(first.moved(first.x, first.y).isLinear)
        assertEquals(Easing("cubicBezier", listOf(0.3f, 0.6f, 1f, 1f)), first.moved(0.3f, 0.6f))
        assertEquals(Easing("cubicBezier", listOf(0f, 0f, 0.8f, 0.2f)), second.moved(0.8f, 0.2f))
    }

    @Test fun handlesFollowTheFinger() {
        val bezier = Easing("cubicBezier", listOf(0.42f, 0f, 0.58f, 1f))
        val h = bezier.handles()[1].moved(0.2f, 0.9f).handles()[1]
        assertEquals(0.2f, h.x, 1e-6f); assertEquals(0.9f, h.y, 1e-6f)
        val bounce = Easing("bounce", listOf(0.3f, 0.8f)).handles()[0].moved(0.3f, 0.4f)
        assertEquals(0.375f, bounce.p[0], 1e-5f); assertEquals(0.6f, bounce.p[1], 1e-5f)
        bounce.handles()[0].let { assertEquals(0.3f, it.x, 1e-5f); assertEquals(0.4f, it.y, 1e-5f) }
        assertEquals(0.4f, Easing("step", listOf(0.25f, 0f)).handles()[0].moved(0.5f, 0.3f).p[0], 1e-6f)
    }

    @Test fun aReversedCurveShowsItsHandlesMirroredAndTakesMirroredDrags() {
        for (e in presets.filter { it.type != "linear" }) {
            val plain = e.handles()
            val mirrored = reverse(e).handles()
            assertEquals(plain.size, mirrored.size)
            plain.zip(mirrored).forEach { (a, b) ->
                assertEquals(1 - a.x, b.x, 1e-6f); assertEquals(1 - a.y, b.y, 1e-6f)
                assertEquals(a.fromX?.let { 1 - it }, b.fromX); assertEquals(a.fromY?.let { 1 - it }, b.fromY)
                assertClose(a.moved(0.3f, 0.6f).p, b.moved(0.7f, 0.4f).p)
                assertEquals(listOf("reverse"), b.moved(0.7f, 0.4f).prefixes)
            }
        }
        val twice = Easing.parse("reverse reverse bounce 0.3 0.8").handles()[0]
        val plain = Easing.parse("bounce 0.3 0.8").handles()[0]
        assertEquals(plain.x, twice.x); assertEquals(plain.y, twice.y)
    }

    @Test fun reverseButton() {
        assertNull(Easing.Linear.reversed())
        assertNull(Easing("cubicBezier", listOf(0f, 0f, 1f, 1f)).reversed())
        assertNull(Easing("cubicBezier", listOf(0.42f, 0f, 0.58f, 1f)).reversed())
        val easeOut = Easing("cubicBezier", listOf(0.42f, 0f, 1f, 1f)).reversed()!!
        assertTrue(EasingCatalog.matches(easeOut, Easing("cubicBezier", listOf(0f, 0f, 0.58f, 1f))))
        assertEquals(emptyList<String>(), easeOut.prefixes)
        assertEquals("reverse bounce 0.3 0.8", Easing.parse("bounce 0.3 0.8").reversed().toString())
        assertEquals("bounce 0.3 0.8", Easing.parse("reverse bounce 0.3 0.8").reversed().toString())
        assertEquals("cubicBezier 0.1 0.2 0.3 0.4", Easing.parse("reverse cubicBezier 0.1 0.2 0.3 0.4").reversed().toString())
        assertEquals("local reverse step 0.25 0.0", Easing.parse("local step 0.25 0").reversed().toString())
    }

    @Test fun reversingTwiceGivesTheCurveBack() {
        for (e in starts + Easing.parse("cubicBezier 0.1 0.7 0.2 0.95")) {
            val once = e.reversed() ?: continue
            val back = once.reversed()!!
            assertEquals(e.type, back.type)
            assertEquals(e.prefixes, back.prefixes)
            e.p.indices.forEach { assertEquals("$e", e.p[it], back.p[it], 1e-6f) }
        }
    }

    @Test fun everyPresetNamesARealStringResource() {
        for (preset in EasingCatalog.families.flatten()) assertTrue("${preset.easing}", preset.label != 0)
        assertEquals(app.absentmotion.R.string.keyframe_easing_cubic_bezier, EasingCatalog.presetOf(Easing.Linear).label)
    }
}
