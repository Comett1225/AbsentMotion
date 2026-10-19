package app.absentmotion

import app.absentmotion.export.ExportKind
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.defaultSettings
import app.absentmotion.export.frameTimeMs
import app.absentmotion.model.FramePair
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.exportFrameMs
import app.absentmotion.model.framePair
import app.absentmotion.model.needsFrameInterpolation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

class FrameInterpolationTest {
    private fun rounded(fps: Double, count: Int, first: Long = 0) = LongArray(count) { first + (it * 1e6 / fps).roundToLong() }
    private fun truncated(fps: Double, count: Int) = LongArray(count) { (it * 1e6 / fps).toLong() }

    private fun scene(fps: Int) = Scene.create("t", 1920, 1080, fps, 0)

    private fun clip(
        start: Int = 0, end: Int = 59_999, inTime: Int = 0, outTime: Int = 60_000, speed: String? = null, loop: Boolean = false,
        speedMap: XmlNode? = null,
    ): Layer {
        val attrs = linkedMapOf("id" to "1", "startTime" to "$start", "endTime" to "$end", "fillType" to "media",
            "fillVideo" to "content://clip", "inTime" to "$inTime", "outTime" to "$outTime", "s" to ".rect")
        speed?.let { attrs["speed"] = it }
        if (loop) attrs["loop"] = "true"
        return Layer(XmlNode("shape", attrs, listOfNotNull(speedMap)))
    }

    private fun Scene.at(frame: Int, fps: Int = this.fps) = exportFrameMs(frameTimeMs(this, fps, frame), fps)

    private fun Layer.pairAt(scene: Scene, frame: Int, stamps: LongArray, fps: Int = scene.fps) = framePair(scene.at(frame, fps), scene, stamps)

    private fun Layer.needsAt(scene: Scene, frame: Int, stamps: LongArray, fps: Int) = needsFrameInterpolation(scene.at(frame, fps), scene, stamps, fps)

    private fun assertPair(a: Long, b: Long, t: Float, actual: FramePair) {
        assertEquals(a, actual.a)
        assertEquals(b, actual.b)
        assertEquals(t, actual.t, 1e-3f)
    }

    @Test fun exportFrameTimesAreRecoveredExactlyAndInterpolationStartsOff() {
        val thirty = scene(30)
        for (fps in listOf(10, 24, 25, 30, 48, 50, 60)) for (k in 0 until 600) assertEquals(k * 1000.0 / fps, thirty.at(k, fps), 1e-9)
        for (kind in ExportKind.entries) assertEquals(FrameInterpolation.Off, defaultSettings(kind, thirty).interpolation)
    }

    @Test fun thirtyFramesIntoASixtyFrameExportGetHalfwayFrames() {
        val project = scene(30)
        for (stamps in listOf(rounded(30.0, 400), truncated(30.0, 400))) {
            for (k in 0 until 400) {
                assertTrue(clip().needsAt(project, k, stamps, 60))
                val pair = clip().pairAt(project, k, stamps, 60)
                if (k % 2 == 0) assertPair(stamps[k / 2], stamps[k / 2], 0f, pair) else assertPair(stamps[k / 2], stamps[k / 2 + 1], 0.5f, pair)
            }
        }
        val forty = rounded(40.0, 400)
        assertTrue(clip().needsAt(project, 1, forty, 60))
        assertPair(forty[0], forty[1], 2 / 3f, clip().pairAt(project, 1, forty, 60))
        assertPair(forty[2], forty[2], 0f, clip().pairAt(project, 3, forty, 60))
    }

    @Test fun clipsAtOrAboveTheExportRateAreLeftAlone() {
        val project = scene(30)
        assertFalse(clip().needsAt(project, 5, rounded(60.0, 400), 60))
        assertFalse(clip().needsAt(project, 5, rounded(30.0, 400), 30))
        assertFalse(clip().needsAt(scene(60), 5, rounded(59.94, 400), 60))
        assertFalse(clip().needsAt(project, 5, rounded(29.97, 400), 30))
        assertFalse(clip(speed = "2").needsAt(project, 5, rounded(30.0, 400), 60))
        assertTrue(clip(speed = "1.5").needsAt(project, 5, rounded(30.0, 400), 60))
        assertTrue(clip(speed = "0.5").needsAt(project, 5, rounded(60.0, 400), 60))
        assertTrue(clip().needsAt(project, 5, rounded(24.0, 400), 30))
        assertTrue(clip().needsAt(project, 5, rounded(25.0, 400), 30))
        assertFalse(clip().needsAt(project, 5, longArrayOf(0), 60))
    }

    @Test fun aNormalSpeedClipAtTheProjectRateLandsOnWholeFrames() {
        val project = scene(30)
        for (stamps in listOf(rounded(30.0, 400), truncated(30.0, 400))) {
            for (frame in 0 until 300) assertPair(stamps[frame], stamps[frame], 0f, clip().pairAt(project, frame, stamps))
            val late = clip(start = project.frameStart(7))
            for (frame in 7 until 300) assertPair(stamps[frame - 7], stamps[frame - 7], 0f, late.pairAt(project, frame, stamps))
        }
    }

    @Test fun quarterSpeedStepsThroughQuarterFractions() {
        val project = scene(10)
        val stamps = rounded(10.0, 12)
        val layer = clip(end = 4499, outTime = 1200, speed = "0.25")
        for (frame in 0..44) {
            assertTrue(layer.needsAt(project, frame, stamps, 10))
            val k = frame / 4
            val step = frame % 4
            if (step == 0) assertPair(stamps[k], stamps[k], 0f, layer.pairAt(project, frame, stamps))
            else assertPair(stamps[k], stamps[k + 1], step / 4f, layer.pairAt(project, frame, stamps))
        }
    }

    @Test fun halfSpeedFromALaterStartAlternatesWholeAndHalfFrames() {
        val project = scene(30)
        val stamps = rounded(30.0, 200)
        val layer = clip(start = project.frameStart(1), speed = "0.5")
        assertEquals(33, layer.startTime)
        for (j in 0 until 120) {
            val m = j / 2
            if (j % 2 == 0) assertPair(stamps[m], stamps[m], 0f, layer.pairAt(project, 1 + j, stamps))
            else assertPair(stamps[m], stamps[m + 1], 0.5f, layer.pairAt(project, 1 + j, stamps))
        }
    }

    @Test fun aLowerSourceRateFallsBetweenFrames() {
        val project = scene(30)
        val film = rounded(24.0, 200)
        assertPair(film[0], film[1], 0.8f, clip().pairAt(project, 1, film))
        assertPair(film[1], film[2], 0.6f, clip().pairAt(project, 2, film))
        assertPair(film[4], film[4], 0f, clip().pairAt(project, 5, film))
    }

    @Test fun fasterThanNormalSpeedLandsOnWholeOrHalfFrames() {
        val project = scene(30)
        val stamps = rounded(30.0, 400)
        for (frame in 0 until 150) assertPair(stamps[2 * frame], stamps[2 * frame], 0f, clip(speed = "2").pairAt(project, frame, stamps))
        val faster = clip(speed = "1.5")
        for (frame in 0 until 150 step 2) assertPair(stamps[3 * frame / 2], stamps[3 * frame / 2], 0f, faster.pairAt(project, frame, stamps))
        for (frame in 1 until 150 step 2) assertPair(stamps[3 * frame / 2], stamps[3 * frame / 2 + 1], 0.5f, faster.pairAt(project, frame, stamps))
    }

    @Test fun theSlowestSpeedBarelyMovesAndSnapsNearWholeFrames() {
        val project = scene(30)
        val stamps = rounded(30.0, 10)
        val layer = clip(speed = "0.001")
        assertTrue(layer.needsAt(project, 0, stamps, 30))
        assertPair(stamps[0], stamps[0], 0f, layer.pairAt(project, 0, stamps))
        assertPair(stamps[0], stamps[0], 0f, layer.pairAt(project, 4, stamps))
        assertPair(stamps[0], stamps[1], 0.5f, layer.pairAt(project, 500, stamps))
        assertPair(stamps[1], stamps[1], 0f, layer.pairAt(project, 1000, stamps))
        assertPair(stamps[1], stamps[2], 0.25f, layer.pairAt(project, 1250, stamps))
    }

    @Test fun aSpeedRampIsInterpolatedOnlyWhereItSlowsBelowTheExportRate() {
        val ramp = XmlNode("speedMap", children = listOf(
            XmlNode("kf", mapOf("t" to "0", "v" to "2")), XmlNode("kf", mapOf("t" to "1000000000", "v" to "0.25"))))
        val project = scene(30)
        val stamps = rounded(30.0, 200)
        val layer = clip(end = 9999, speedMap = ramp)
        assertFalse(layer.needsAt(project, 0, stamps, 30))
        assertTrue(layer.needsAt(project, 200, stamps, 30))
        val timing = VideoTiming(layer)
        var previous = -1.0
        for (frame in 0 until 240) {
            val pair = layer.pairAt(project, frame, stamps)
            val us = timing.mediaMs(frame * 1000.0 / 30) * 1000
            val shown = pair.a + pair.t.toDouble() * (pair.b - pair.a)
            assertTrue(pair.a <= pair.b && pair.t >= 0f && pair.t < 1f)
            assertTrue(pair.b - pair.a <= 33_334)
            assertTrue("frame $frame shows $shown for $us", abs(shown - us) <= 0.005 * 33_334 + 1)
            assertTrue(shown >= previous)
            previous = shown
        }
    }

    @Test fun aLoopingClipWrapsToItsStartAndBlendsTowardsTheRealNextFrame() {
        val project = scene(10)
        val stamps = rounded(10.0, 20)
        val layer = clip(outTime = 500, speed = "0.5", loop = true)
        assertPair(stamps[4], stamps[5], 0.5f, layer.pairAt(project, 9, stamps))
        assertPair(stamps[0], stamps[0], 0f, layer.pairAt(project, 10, stamps))
        assertPair(stamps[0], stamps[1], 0.5f, layer.pairAt(project, 11, stamps))
        assertPair(stamps[2], stamps[2], 0f, layer.pairAt(project, 24, stamps))
    }

    @Test fun pastTheTrimEndTheLastFrameBeforeItIsHeld() {
        val project = scene(10)
        val stamps = rounded(10.0, 20)
        val layer = clip(end = 9999, outTime = 300, speed = "0.5")
        assertPair(stamps[2], stamps[3], 0.5f, layer.pairAt(project, 5, stamps))
        for (frame in 6..30) assertPair(stamps[2], stamps[2], 0f, layer.pairAt(project, frame, stamps))
    }

    @Test fun theFirstAndLastSourceFramesAreHeld() {
        val project = scene(30)
        val late = rounded(30.0, 10, first = 40_000)
        val layer = clip(speed = "0.5")
        assertPair(late[0], late[0], 0f, layer.pairAt(project, 0, late))
        assertPair(late[0], late[0], 0f, layer.pairAt(project, 2, late))
        assertPair(late[9], late[9], 0f, layer.pairAt(project, 40, late))
        assertPair(late[9], late[9], 0f, clip(speed = "0.5", inTime = 290).pairAt(project, 3, late))
        assertPair(0, 0, 0f, layer.pairAt(project, 7, longArrayOf(0)))
    }

    @Test fun aNegativeSpeedIsNotPlayedBackwards() {
        val project = scene(30)
        val stamps = rounded(30.0, 50)
        val layer = clip(speed = "-2")
        val shown = (0 until 60).map { layer.pairAt(project, it, stamps).let { p -> p.a + p.t * (p.b - p.a) } }
        assertEquals(shown.sorted(), shown)
    }
}
