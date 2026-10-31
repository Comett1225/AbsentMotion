package app.absentmotion

import app.absentmotion.editor.EditHistory
import app.absentmotion.model.CURVE_FASTEST
import app.absentmotion.model.CURVE_GAP
import app.absentmotion.model.CURVE_MOST_POINTS
import app.absentmotion.model.CURVE_SLOWEST
import app.absentmotion.model.CurvePoint
import app.absentmotion.model.FASTEST_SPEED
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.SLOWEST_SPEED
import app.absentmotion.model.SpeedRamp
import app.absentmotion.model.SpeedTicks
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.curve
import app.absentmotion.model.curveHeight
import app.absentmotion.model.curveSpeed
import app.absentmotion.model.flatCurve
import app.absentmotion.model.keyframes
import app.absentmotion.model.lengthWithCurve
import app.absentmotion.model.moved
import app.absentmotion.model.normalSpeed
import app.absentmotion.model.ramp
import app.absentmotion.model.rulerTick
import app.absentmotion.model.snappedToOne
import app.absentmotion.model.speedCurve
import app.absentmotion.model.steppedSpeed
import app.absentmotion.model.tickSpeed
import app.absentmotion.model.trimmedTo
import app.absentmotion.model.withKeyframes
import app.absentmotion.model.withNormalSpeed
import app.absentmotion.model.withPointNear
import app.absentmotion.model.withSpeedCurve
import app.absentmotion.model.withVideoSpeedMap
import app.absentmotion.model.without
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class SpeedCurveTest {
    private fun span(layer: Layer) = VideoTiming(layer).let { it.mediaMs((layer.endTime - layer.startTime).toDouble()) - it.mediaMs(0.0) }

    private fun linear(vararg points: Pair<Float, Float>) = points.map { (u, speed) -> CurvePoint(u, speed, null) }

    @Test fun theRulerReachesEverySpeedInAFewTurns() {
        assertEquals(170, SpeedTicks.size)
        assertEquals(SLOWEST_SPEED, SpeedTicks.first(), 0f)
        assertEquals(FASTEST_SPEED, SpeedTicks.last(), 0f)
        assertTrue(SpeedTicks.zipWithNext().all { (a, b) -> b > a })
        assertTrue(SpeedTicks.all { abs(it * 20 - Math.round(it * 20)) < 1e-3f })
        for (tick in 1..SpeedTicks.size) assertEquals(tick, rulerTick(tickSpeed(tick)))
        val majors = mapOf(5 to 0.25f, 20 to 1f, 40 to 2f, 45 to 2.5f, 70 to 5f, 75 to 6f, 95 to 10f, 100 to 12.5f, 115 to 20f, 120 to 25f, 145 to 50f, 150 to 60f, 170 to 100f)
        for ((tick, speed) in majors) assertEquals("tick $tick", speed, tickSpeed(tick), 1e-5f)
        assertEquals(1, rulerTick(0.01f))
        assertEquals(170, rulerTick(500f))
        assertEquals(2.35f, steppedSpeed(2.34f), 1e-6f)
        assertEquals(SLOWEST_SPEED, steppedSpeed(0.01f), 0f)
        assertEquals(FASTEST_SPEED, steppedSpeed(250f), 0f)
    }

    @Test fun aCurveBecomesSpeedKeysInSourceTimeAndSetsTheNewLength() {
        val layer = videoLayer(1000, 4999, inTime = 500)
        val points = linear(0f to 1f, 0.5f to 4f, 1f to 1f)
        val curved = layer.withSpeedCurve(points)
        val keys = curved.node.child("speedMap")!!.keyframes
        keys.map { it.t / 1e6 }.zip(listOf(500.0, 2499.5, 4499.0)).forEach { (got, want) -> assertEquals(want, got, 1e-3) }
        assertEquals(listOf("1", "4", "1"), keys.map { it.v })
        assertEquals(span(layer), span(curved), 1.0)
        assertEquals(2 * (2 * 1999.5 / 5), (curved.endTime - curved.startTime).toDouble(), 1.0)
        assertEquals(curved.endTime - curved.startTime, layer.lengthWithCurve(points))
        assertEquals(layer.startTime, curved.startTime)
    }

    @Test fun existingKeyframesOpenAsACustomCurve() {
        val map = XmlNode("speedMap").withKeyframes(listOf(Keyframe(500e6f, "2"), Keyframe(1500e6f, "0.5", "linear"), Keyframe(2500e6f, "2", "linear")))
        val layer = videoLayer(0, 2000, inTime = 500).withVideoSpeedMap(map)
        val curve = layer.speedCurve()!!
        curve.map { it.u }.zip(listOf(0f, 0.5f, 1f)).forEach { (got, want) -> assertEquals(want, got, 1e-4f) }
        assertEquals(listOf(2f, 0.5f, 2f), curve.map { it.speed })
        assertEquals(listOf(null, "linear", "linear"), curve.map { it.ease })
        assertNull(curve.ramp())
        assertNull(videoLayer(0, 2000).speedCurve())

        val cut = layer.trimmedTo(0, 600).speedCurve()!!
        assertEquals(2, cut.size)
        assertEquals(2f, cut.first().speed, 0f)
        assertEquals(0.875f, cut.last().speed, 1e-3f)
        assertNull(cut.last().ease)
    }

    @Test fun aCurveWrittenAndReadBackIsTheSameCurve() {
        val layer = videoLayer(1000, 6000, inTime = 0)
        val points = linear(0f to 2.5f, 0.3f to 0.3f, 0.62f to 1f, 1f to 4f)
        val back = layer.withSpeedCurve(points).speedCurve()!!
        assertEquals(points.size, back.size)
        points.zip(back).forEach { (a, b) ->
            assertEquals(a.u, b.u, 1e-3f)
            assertEquals(a.speed, b.speed, 0f)
            assertEquals(a.ease, b.ease)
        }
        val montage = SpeedRamp.Montage.curve
        assertEquals(SpeedRamp.Montage, montage.ramp())
        assertNull(montage.moved(2, 0.5f, 0.8f).ramp())
        assertNull(linear(*SpeedRamp.Montage.points.toTypedArray()).ramp())
        val plain = layer.withSpeedCurve(null)
        assertNull(plain.speedCurve())
        assertEquals(1f, VideoTiming(plain).speed(0.0), 0f)
    }

    @Test fun dragsSnapToOneAndStayInOrder() {
        val reach = ln(1.1f)
        assertEquals(1f, snappedToOne(1.06f, reach), 0f)
        assertEquals(1f, snappedToOne(0.93f, reach), 0f)
        assertEquals(1.2f, snappedToOne(1.2f, reach), 0f)
        assertEquals(0.5f, curveHeight(1f), 1e-6f)
        assertEquals(0f, curveHeight(CURVE_SLOWEST), 1e-6f)
        assertEquals(1f, curveHeight(CURVE_FASTEST), 1e-6f)
        assertEquals(1f, curveHeight(50f), 1e-6f)
        for (speed in listOf(0.1f, 0.35f, 1f, 2.5f, 10f)) assertEquals(speed, curveSpeed(curveHeight(speed)), 1e-4f)

        val points = flatCurve(1f).map { it.copy(ease = null) }
        val pulled = points.moved(2, 0.9f, 3f)
        assertEquals(0.75f - CURVE_GAP, pulled[2].u, 1e-6f)
        assertEquals(3f, pulled[2].speed, 0f)
        assertEquals(points.filterIndexed { i, _ -> i != 2 }, pulled.filterIndexed { i, _ -> i != 2 })
        assertEquals(0f, points.moved(0, 0.4f, 2f)[0].u, 0f)
        assertEquals(1f, points.moved(4, 0.2f, 2f)[4].u, 0f)
        assertEquals(points, points.moved(9, 0.5f, 2f))
    }

    @Test fun pointsAreAddedOnTheCurveAndOnlyMiddlePointsAreDeleted() {
        val ramp = linear(0f to 1f, 1f to 3f)
        val (added, index) = ramp.withPointNear(0.5f)!!
        assertEquals(1, index)
        assertEquals(0.5f, added[1].u, 0f)
        assertEquals(2f, added[1].speed, 1e-6f)
        assertNull(added[1].ease)
        assertEquals("linear", linear(0f to 1f, 1f to 3f).map { it.copy(ease = "linear") }.withPointNear(0.3f)!!.first[1].ease)
        val (middle, at) = ramp.withPointNear(0.01f)!!
        assertEquals(0.5f, middle[at].u, 0f)
        assertEquals(ramp, added.without(1))
        assertEquals(ramp, ramp.without(0))
        assertEquals(ramp, ramp.without(1))
        var many = flatCurve(1f).map { it.copy(ease = null) }
        while (true) many = many.withPointNear(-1f)?.first ?: break
        assertEquals(CURVE_MOST_POINTS, many.size)
        assertTrue(many.zipWithNext().all { (a, b) -> b.u - a.u >= CURVE_GAP })
    }

    @Test fun normalSpeedScalesACurveAndKeepsItsShape() {
        val layer = videoLayer(0, 4000, inTime = 0).withSpeedCurve(linear(0f to 1f, 0.5f to 3f, 1f to 1f))
        val average = layer.normalSpeed()
        assertEquals(span(layer) / (layer.endTime - layer.startTime), average.toDouble(), 1e-3)
        val doubled = layer.withNormalSpeed(average * 2)
        assertEquals(listOf("2", "6", "2"), doubled.node.child("speedMap")!!.keyframes.map { it.v })
        assertEquals((layer.endTime - layer.startTime) / 2.0, (doubled.endTime - doubled.startTime).toDouble(), 1.0)
        assertEquals(span(layer), span(doubled), 1.0)
        val capped = layer.withNormalSpeed(FASTEST_SPEED)
        assertEquals(FASTEST_SPEED, capped.node.child("speedMap")!!.keyframes.maxOf { it.v.toFloat() }, 1e-3f)

        val plain = videoLayer(0, 4000).withNormalSpeed(2.5f)
        assertEquals("2.5", plain.node["speed"])
        assertEquals(1600, plain.endTime - plain.startTime)
        assertEquals(2.5f, plain.normalSpeed(), 0f)
    }

    @Test fun aWholeCurveDragIsOneUndoStep() {
        val clip = videoLayer(0, 3000, inTime = 0)
        val start = sceneOf(clip.node)
        var current = start
        val history = EditHistory({ current }) { next, _ -> current = next }
        var dragged = flatCurve(1f).map { it.copy(ease = null) }
        val lengths = (1..20).map { step ->
            dragged = dragged.moved(2, 0.5f, 1f + step * 0.2f)
            current.layer(1)!!.lengthWithCurve(dragged)
        }
        assertTrue(lengths.zipWithNext().all { (a, b) -> b < a })
        assertEquals(start, current)
        assertFalse(history.canUndo)
        history.edit(null, current.update(1) { it.withSpeedCurve(dragged) })
        assertEquals(lengths.last(), current.layer(1)!!.let { it.endTime - it.startTime })
        history.undo()
        assertEquals(start, current)
        assertFalse(history.canUndo)
    }
}
