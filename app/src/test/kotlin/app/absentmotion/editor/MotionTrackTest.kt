package app.absentmotion.editor

import app.absentmotion.media.LumaFrame
import app.absentmotion.media.PointTracker
import app.absentmotion.media.TrackResult
import app.absentmotion.model.Layer
import app.absentmotion.model.Pt
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatFloats
import app.absentmotion.model.keyframes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MotionTrackTest {
    private fun scene(vararg layers: XmlNode) = Scene(Scene.create("t", 1080, 1920, 30, 0).node.copy(children = layers.toList()))

    private fun video(id: Long = 1, attrs: Map<String, String> = emptyMap(), mode: String = "fill", size: Pair<Float, Float> = 192f to 108f,
                      location: FloatArray = floatArrayOf(540f, 960f, 0f), rotation: Float = 0f, scale: Float = 1f): XmlNode =
        Layer(XmlNode("shape", linkedMapOf("id" to "$id", "startTime" to "0", "endTime" to "2000", "fillType" to "media", "fillVideo" to "v.mp4",
            "mediaFillMode" to mode, "s" to ".rect", "outTime" to "4000") + attrs))
            .withTransform("location", *location).withTransform("rotation", rotation).withTransform("scale", scale, scale)
            .withProp("size", "vec2", size.first, size.second).node

    private fun near(expected: Pt, actual: Pt, tolerance: Float = 1e-3f) {
        assertEquals("x of $actual", expected.x, actual.x, tolerance)
        assertEquals("y of $actual", expected.y, actual.y, tolerance)
    }

    @Test fun aTwoPointSimilarityMapsBothPointsAndReportsItsAngleAndScale() {
        val from = listOf(Pt(10f, 20f), Pt(110f, 20f))
        val r = Math.toRadians(30.0)
        val to = from.map { p -> Pt((1.5 * (p.x * cos(r) - p.y * sin(r)) + 7).toFloat(), (1.5 * (p.x * sin(r) + p.y * cos(r)) - 3).toFloat()) }
        val s = Sim.between(from, to)
        from.zip(to).forEach { (a, b) -> near(b, s.map(a), 1e-3f) }
        assertEquals(30.0, s.degrees, 1e-4)
        assertEquals(1.5, s.scale, 1e-6)
        val one = Sim.between(listOf(Pt(1f, 1f)), listOf(Pt(4f, -1f)))
        assertEquals(0.0, one.degrees, 0.0)
        near(Pt(13f, 8f), one.map(Pt(10f, 10f)))
        val chained = Sim.between(from, to) * one
        near(s.map(one.map(Pt(5f, 6f))), chained.map(Pt(5f, 6f)))
    }

    @Test fun videoPixelsMapThroughTheLayerTransform() {
        val s = scene(video(rotation = 90f, scale = 2f))
        val m = s.videoToScene(s.layer(1)!!, 0, 1920, 1080)!!
        near(Pt(540f, 960f), m.map(Pt(959.5f, 539.5f)))
        near(Pt(756f, 576f), m.map(Pt(-0.5f, -0.5f)))
        near(Pt(-0.5f, -0.5f), m.inverse()!!.map(Pt(756f, 576f)))
    }

    @Test fun fillModesCropOrLetterboxTheFrame() {
        val square = scene(video(size = 100f to 100f)).layer(1)!!
        assertEquals(listOf(-200f, -100f, 200f, 100f), square.mediaRect(200, 100)!!.toList())
        val fit = scene(video(mode = "fit", size = 100f to 100f)).layer(1)!!
        assertEquals(listOf(-100f, -50f, 100f, 50f), fit.mediaRect(200, 100)!!.toList())
        val stretch = scene(video(mode = "stretch", size = 100f to 100f)).layer(1)!!
        assertEquals(listOf(-100f, -100f, 100f, 100f), stretch.mediaRect(200, 100)!!.toList())
        val s = scene(video(size = 100f to 100f, location = floatArrayOf(0f, 0f, 0f)))
        near(Pt(-200f, 0f), s.videoToScene(s.layer(1)!!, 0, 200, 100)!!.map(Pt(-0.5f, 49.5f)))
    }

    @Test fun aParentedVideoMapsLikeTheSameWorldTransform() {
        val parent = Layer(XmlNode("nullobj", linkedMapOf("id" to "9", "startTime" to "0", "endTime" to "2000")))
            .withTransform("location", 540f, 960f, 0f).withTransform("rotation", 90f).node
        val child = video(attrs = mapOf("parent" to "9"), location = floatArrayOf(0f, 0f, 0f), scale = 2f)
        val s = scene(parent, child)
        val m = s.videoToScene(s.layer(1)!!, 0, 1920, 1080)!!
        near(Pt(756f, 576f), m.map(Pt(-0.5f, -0.5f)), 1e-2f)
    }

    @Test fun framesFollowTheSpeedAndTrimOfTheClip() {
        val stamps = LongArray(240) { it * 1_000_000L / 30 }
        val fast = scene(video(attrs = mapOf("speed" to "2")))
        assertEquals(listOf(stamps[0], stamps[2], stamps[4], stamps[6]), fast.framePts(fast.layer(1)!!, listOf(0, 1, 2, 3), stamps))
        val trimmed = scene(video(attrs = mapOf("speed" to "2", "inTime" to "1000")))
        assertEquals(listOf(stamps[30], stamps[32]), trimmed.framePts(trimmed.layer(1)!!, listOf(0, 1), stamps))
        val runs = fast.trackRuns(fast.layer(1)!!, 10, TrackRange.Whole)
        assertEquals(10, runs[0].first())
        assertEquals(fast.lastShownFrame(2000), runs[0].last())
        assertEquals(listOf(10, 9, 8), runs[1].take(3))
        assertEquals(0, runs[1].last())
    }

    @Test fun skippedSourceFramesAreFilledInAndRepeatsDropped() {
        val stamps = LongArray(100) { it * 10L }
        assertEquals(listOf(0L, 10L, 20L, 30L, 40L), sourceOrder(listOf(0L, 0L, 20L, 40L), stamps))
        assertEquals(listOf(40L, 30L, 20L), sourceOrder(listOf(40L, 20L), stamps))
        assertEquals(listOf(0L, 900L), sourceOrder(listOf(0L, 900L), stamps, maxGap = 30))
    }

    @Test fun simplifiedKeysStayWithinTheTolerance() {
        val times = FloatArray(61) { it / 60f }
        val line = List(61) { floatArrayOf(it * 2f, 5f) }
        assertEquals(listOf(0, 60), simplifyKeys(times, line, 0.5f))
        val bend = List(61) { floatArrayOf(if (it <= 30) it * 2f else 60f + (it - 30) * -1f, 0f) }
        assertEquals(listOf(0, 30, 60), simplifyKeys(times, bend, 0.5f))
        val wave = List(61) { floatArrayOf((40 * sin(it / 6.0)).toFloat()) }
        val kept = simplifyKeys(times, wave, 0.5f)
        assertTrue(kept.size in 4..40)
        for (i in times.indices) {
            val b = kept.first { it >= i }
            val a = kept.last { it <= i }
            val expected = if (a == b) wave[a][0] else wave[a][0] + (wave[b][0] - wave[a][0]) * (times[i] - times[a]) / (times[b] - times[a])
            assertTrue(abs(expected - wave[i][0]) <= 0.5f)
        }
    }

    @Test fun anglesAreUnwrappedAcrossTheHalfTurn() {
        assertEquals(listOf(170.0, 190.0, 200.0, 370.0), unwrapDegrees(listOf(170.0, -170.0, -160.0, 10.0)))
    }

    private fun texture(x: Int, y: Int): Int = (128 + 60 * sin(x * 0.37 + y * 0.11) + 50 * cos(y * 0.29 - x * 0.07) + ((x * 7919 + y * 104729) % 23)).toInt().coerceIn(0, 255)

    private fun frame(dx: Int, dy: Int, width: Int = 64, height: Int = 48) =
        LumaFrame(width, height, ByteArray(width * height) { i -> texture(i % width - dx, i / width - dy).toByte() }, factor = 2)

    private class BlockMatcher(start: List<Pt>, val failOn: Int = -1) : PointTracker {
        private var positions = start
        private var template: List<IntArray>? = null
        private var calls = 0
        override fun track(luma: LumaFrame): TrackResult {
            calls++
            fun at(x: Int, y: Int) = luma.pixels[y.coerceIn(0, luma.height - 1) * luma.width + x.coerceIn(0, luma.width - 1)].toInt() and 0xFF
            fun patch(p: Pt) = IntArray(81) { at(Math.round(p.x) + it % 9 - 4, Math.round(p.y) + it / 9 - 4) }
            val reference = template ?: positions.map(::patch).also { template = it; return TrackResult(positions, false) }
            positions = positions.mapIndexed { i, p ->
                val best = (-4..4).flatMap { dy -> (-4..4).map { dx -> dx to dy } }.minBy { (dx, dy) ->
                    patch(Pt(p.x + dx, p.y + dy)).zip(reference[i]).sumOf { (a, b) -> (a - b) * (a - b) }
                }
                Pt(p.x + best.first, p.y + best.second)
            }
            return TrackResult(positions, calls == failOn)
        }
        override fun close() {}
    }

    @Test fun trackingFollowsAMovingPatternInMediaPixels() = runBlocking {
        val frames = (0 until 8).toList()
        val pts = frames.map { it * 1000L }
        val lumas = frames.associate { it * 1000L to frame(it, it) }
        val outcome = trackFrames(frames, pts, pts.toLongArray(), listOf(Pt(60f, 40f)), 128, 96,
            read = { batch, deliver -> batch.forEach { deliver(it, lumas.getValue(it)) } }, tracker = { BlockMatcher(it) })
        assertNull(outcome.lostAt)
        assertEquals(frames, outcome.steps.map { it.frame })
        outcome.steps.forEach { near(Pt(60f + 2 * it.frame, 40f + 2 * it.frame), it.points.single()) }
    }

    @Test fun trackingStopsAtTheFirstLostFrame() = runBlocking {
        val frames = (20 downTo 13).toList()
        val pts = frames.map { it * 1000L }
        val lumas = frames.associate { it * 1000L to frame(20 - it, 0) }
        val stamps = (0..30).map { it * 1000L }.toLongArray()
        val outcome = trackFrames(frames, pts, stamps, listOf(Pt(60f, 40f)), 128, 96,
            read = { batch, deliver -> batch.forEach { deliver(it, lumas.getValue(it)) } }, tracker = { BlockMatcher(it, failOn = 5) })
        assertEquals(16, outcome.lostAt)
        assertEquals(listOf(20, 19, 18, 17), outcome.steps.map { it.frame })
        near(Pt(68f, 40f), outcome.lostPoints.single())
    }

    @Test fun aNewNullFollowsTheTrackWithSimplifiedKeys() {
        val s = scene(video())
        val layer = s.layer(1)!!
        val steps = (0..20).map { TrackStep(it, listOf(Pt(100f + 3 * it, 200f))) }
        val motion = s.motionOf(layer, steps, 1920, 1080, Sim())
        val start = s.videoToScene(layer, 0, 1920, 1080)!!.map(Pt(100f, 200f))
        val apply = TrackApply(5, Place(start.x, start.y, 0f, 0f, 1f, 1f, 0f), rotateScale = false)
        val out = s.add(trackNull(5, layer, start, "Track 1")).withTrack(apply, motion)
        val keys = out.layer(5)!!.node.child("transform")!!.child("location")!!.keyframes
        assertEquals(2, keys.size)
        for (f in listOf(0, 7, 20)) near(s.videoToScene(layer, f, 1920, 1080)!!.map(Pt(100f + 3 * f, 200f)), Pt(out.layerAt(5, s.frameStart(f))!!.location[0], out.layerAt(5, s.frameStart(f))!!.location[1]), 1e-2f)
        assertNull(out.layer(5)!!.node.child("transform")!!.child("rotation"))
    }

    @Test fun anAttachedChildKeepsItsOffsetAndFollowsRotationInItsParentSpace() {
        val holder = Layer(XmlNode("nullobj", linkedMapOf("id" to "7", "startTime" to "0", "endTime" to "2000")))
            .withTransform("location", 100f, 50f, 0f).withTransform("rotation", 30f).node
        val sticker = Layer(XmlNode("shape", linkedMapOf("id" to "8", "startTime" to "0", "endTime" to "2000", "parent" to "7", "s" to ".rect")))
            .withTransform("location", 400f, 0f, 0f).node
        val s = scene(video(), holder, sticker)
        val layer = s.layer(1)!!
        val turn = { f: Int -> Math.toRadians(2.0 * f) }
        val steps = (0..15).map { f ->
            listOf(Pt(800f, 500f), Pt(1100f, 500f)).map { p -> Pt((960 + (p.x - 960) * cos(turn(f)) - (p.y - 540) * sin(turn(f))).toFloat(), (540 + (p.x - 960) * sin(turn(f)) + (p.y - 540) * cos(turn(f))).toFloat()) }
                .let { TrackStep(f, it) }
        }
        val motion = s.motionOf(layer, steps, 1920, 1080, Sim())
        val origin = s.place(s.layer(8)!!, s.frameStart(0))
        val out = s.withTrack(TrackApply(8, origin, rotateScale = true), motion)
        for (f in listOf(0, 5, 15)) {
            val world = out.place(out.layer(8)!!, s.frameStart(f))
            val expected = motion.getValue(f).map(Pt(origin.x, origin.y))
            near(expected, Pt(world.x, world.y), 0.75f)
            assertEquals(origin.rot + 2f * f, world.rot, 0.15f)
        }
        assertEquals("7", out.layer(8)!!.node["parent"])
    }

    @Test fun keysOutsideTheTrackedRangeAreKept() {
        val target = Layer(XmlNode("nullobj", linkedMapOf("id" to "3", "startTime" to "0", "endTime" to "2000"))).node
            .withChild("transform", XmlNode("transform", children = listOf(XmlNode("location", children = listOf(
                XmlNode("kf", linkedMapOf("t" to "0.9", "v" to formatFloats(floatArrayOf(1f, 2f, 0f)))),
                XmlNode("kf", linkedMapOf("t" to "0.1", "v" to formatFloats(floatArrayOf(5f, 5f, 0f)))),
            )))))
        val s = scene(video(), target)
        val motion = (0..10).associateWith { Sim(tx = it.toDouble()) }
        val out = s.withTrack(TrackApply(3, Place(0f, 0f, 0f, 0f, 1f, 1f, 0f), rotateScale = false), motion)
        val keys = out.layer(3)!!.node.child("transform")!!.child("location")!!.keyframes
        assertTrue(keys.any { abs(it.t - 0.9f) < 1e-6f })
        assertTrue(keys.none { abs(it.t - 0.1f) < 1e-6f })
    }
}
