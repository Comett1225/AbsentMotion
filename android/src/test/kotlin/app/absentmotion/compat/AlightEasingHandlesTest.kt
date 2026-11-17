package app.absentmotion.compat

import app.absentmotion.model.Easing
import app.absentmotion.model.handles
import org.junit.Assert.assertEquals
import org.junit.Test

class AlightEasingHandlesTest {
    private class Drag(val handle: Int, val dx: Float, val dy: Float)

    private class Case(val easing: String, val pixelsPerUnit: Float, val handles: List<Pair<Float, Float>>, val drags: List<Drag>, val after: List<Float>, val tolerance: List<Float> = emptyList())

    private val swipes = listOf(28f to 34f, -34f to 26f, 24f to -38f, -26f to -30f, 36f to 14f)
    private fun drags(count: Int) = List(count) { Drag(it, swipes[it].first, swipes[it].second) }

    private val cases = listOf(
        Case("", 96f, listOf(0.25f to 0.25f, 0.75f to 0.75f), drags(2), listOf(0.306f, -0.104f, 0.681f, 0.479f)),
        Case("cubicBezier 0.42 0.0 1.0 1.0", 96f, listOf(0.42f to 0f, 1f to 1f), drags(2), listOf(0.477f, -0.354f, 0.931f, 0.729f)),
        Case("cubicBezier 0.0 0.0 0.58 1.0", 96f, listOf(0f to 0f, 0.58f to 1f), drags(2), listOf(0.057f, -0.354f, 0.511f, 0.729f)),
        Case("cubicBezier 0.42 0.0 0.58 1.0", 96f, listOf(0.42f to 0f, 0.58f to 1f), drags(2), listOf(0.477f, -0.354f, 0.511f, 0.729f)),
        Case("bounce 0.3 0.8", 317.9f, listOf(0.27f to 0.2f), drags(1), listOf(0.3375f, 0.892f)),
        Case("elastic 0.25 1.0 0.5 1.0", 203.7f, listOf(0f to 2f, 0.5f to 0.5f), drags(2), listOf(0.2152f, 1f, 0.372f, 0.833f)),
        Case("cyclic 0.2857143 0.0 0.5 0.0 0.0", 239.3f, listOf(0f to 1.5f, 0.1429f to 1f, 0.2857f to 0f, 1f to 0f), drags(4), listOf(0.3347f, 0.2f, 0.2571f, 0.1254f, 0f)),
        Case(
            "random 0.5 0.5 0.5 0.5 0.5 0.0", 317.9f, listOf(0f to 0.5f, 0.4f to 1f, 0.5f to 0.7f, 0.5f to 0f, 1f to 1f), drags(5),
            listOf(0.587f, 0.3f, 0.391f, 0.447f, 0.549f, 0.044f), listOf(0.003f, 0.02f, 0.004f, 0.003f, 0.003f, 0.003f),
        ),
        Case("step 0.25 0.0", 317.9f, listOf(0.25f to 0f, 0.25f to 1f), drags(2), listOf(0.3061f, 0f)),
        Case("elasticStep 0.2 0.5", 317.9f, listOf(0.2f to 0f, 1f to 0.5f), drags(2), listOf(0.2571f, 0.416f)),
    )

    @Test fun handlesSitWhereAlightDrawsThem() {
        for (c in cases) {
            val hs = Easing.parse(c.easing).handles()
            assertEquals(c.easing, c.handles.size, hs.size)
            c.handles.zip(hs).forEachIndexed { i, (expected, h) ->
                assertEquals("${c.easing} handle $i x", expected.first, h.x, 0.003f)
                assertEquals("${c.easing} handle $i y", expected.second, h.y, 0.003f)
            }
        }
    }

    @Test fun draggingGivesAlightsValues() {
        for (c in cases) {
            var e = Easing.parse(c.easing)
            for (d in c.drags) {
                val h = e.handles()[d.handle]
                e = h.moved((h.x + d.dx / 490f * h.dampingFactor).coerceIn(0f, 1f), h.y - d.dy / c.pixelsPerUnit * h.dampingFactor)
            }
            c.after.forEachIndexed { i, v -> assertEquals("${c.easing} -> $e number $i", v, e.p[i], c.tolerance.getOrElse(i) { 0.003f }) }
        }
    }

    @Test fun bounceReachesFullBouncesAtTheBottomAndElasticStrengthFollowsTheTopHandle() {
        val bounce = Easing.parse("bounce 0.3 0.8").handles().single()
        assertEquals(1f, bounce.moved(bounce.x, 0f).p[1], 1e-6f)
        assertEquals(0f, bounce.moved(bounce.x, 1f).p[1], 1e-6f)
        val top = Easing.parse("elastic 0.25 1.0 0.5 1.0").handles()[0]
        assertEquals(0.4f, top.moved(0f, 1.4f).p[3], 1e-6f)
        assertEquals(0f, top.moved(0f, 0.5f).p[3], 1e-6f)
        val white = Easing.parse("step 0.25 0.0").handles()[1]
        assertEquals(0.25f, white.fromX!!, 1e-6f)
        assertEquals(0.25f, white.fromY!!, 1e-6f)
        assertEquals(0.5f, white.moved(0.625f, 1f).p[1], 1e-6f)
    }
}
