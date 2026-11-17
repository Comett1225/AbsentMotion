package app.absentmotion.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ExportClockTest {
    private class Tick(val at: Double, val truth: Double, val shown: Int?)

    private val frame = 1.0 / 30

    private fun ns(seconds: Double) = (seconds * 1e9).toLong()

    private fun frames(costs: List<Double>, setup: Double = 1.0, stallAt: Int = -1, stall: Double = 0.0): List<Pair<Double, Float>> {
        var t = setup
        val drawn = costs.mapIndexed { k, cost ->
            t += cost
            if (k == stallAt) t += stall
            t to (k + 1f) / costs.size * 0.98f
        }
        return drawn + ((t + (t - setup) * 0.02 / 0.98) to 1f)
    }

    private fun download(bytes: Double, perSecond: Double, chunk: Int, pauseEvery: Double = 0.0, pause: Double = 0.0): List<Pair<Double, Float>> {
        var t = 0.0
        var got = 0.0
        var since = 0.0
        val out = ArrayList<Pair<Double, Float>>()
        while (got < bytes) {
            got = minOf(bytes, got + chunk)
            t += chunk / perSecond
            since += chunk / perSecond
            if (pauseEvery > 0 && since >= pauseEvery) { t += pause; since = 0.0 }
            out += t to (got / bytes).toFloat()
        }
        return out
    }

    private fun aiClips(clips: Int, graphics: Int, clip: Int) = List(clips) { List(graphics) { 0.03 } + List(clip) { k -> if (k % 2 == 0) 0.6 else 0.03 } }.flatten()

    private fun watch(samples: List<Pair<Double, Float>>, plannedMs: Long?, from: Double = 0.0, skipsStalls: Boolean = true): List<Tick> {
        val clock = ExportClock(plannedMs, ns(from), skipsStalls)
        val end = samples.last().first
        val ticks = ArrayList<Tick>()
        var next = 0
        var at = from + 0.25
        while (at < end) {
            while (next < samples.size && samples[next].first <= at) {
                clock.add(samples[next].second, ns(samples[next].first))
                next++
            }
            ticks += Tick(at, end - at, clock.secondsLeft(ns(at)))
            at += 0.25
        }
        return ticks
    }

    private fun plan(samples: List<Pair<Double, Float>>, times: Double = 1.0) = (samples.last().first * times * 1000).toLong()

    private fun List<Tick>.at(seconds: Double) = first { it.at >= seconds - 1e-9 }

    private fun List<Tick>.from(seconds: Double) = filter { it.at >= seconds - 1e-9 }

    private fun List<Tick>.steps() = mapNotNull { it.shown }.zipWithNext { a, b -> b - a }

    private fun List<Tick>.climbAboveTruth() = zipWithNext { a, b -> if (b.shown!! > a.shown!! && b.shown > b.truth + 1) b.shown - a.shown else 0 }.sum()

    private fun assertWithin(fraction: Double, ticks: List<Tick>, slack: Double = 1.5) = ticks.forEach {
        val shown = it.shown
        assertTrue("at %.2f s: %s s shown, %.1f s left".format(it.at, shown, it.truth), shown != null && abs(shown - it.truth) <= maxOf(fraction * it.truth, slack))
    }

    @Test fun thePlanCountsDownUntilTheExportReportsAndTheEndIsZero() {
        val clock = ExportClock(60_000, 0)
        assertEquals(60, clock.secondsLeft(0))
        assertEquals(50, clock.secondsLeft(ns(10.0)))
        assertNull(ExportClock(null, 0).secondsLeft(ns(1.0)))
        clock.add(1f, ns(12.0))
        assertEquals(0, clock.secondsLeft(ns(12.5)))
    }

    @Test fun aSteadyExportIsRightFromTheStartAndOnlyCountsDown() {
        val ticks = watch(frames(List(1800) { frame }), 62_000)
        assertWithin(0.05, ticks.from(2.0))
        assertTrue(ticks.steps().all { it <= 0 })
    }

    @Test fun aWrongPlanGivesWayToTheMeasuredSpeedWithinSeconds() {
        val samples = frames(List(1800) { frame })
        for (times in listOf(0.5, 2.0)) assertWithin(0.1, watch(samples, plan(samples, times)).from(11.0))
    }

    @Test fun slowFirstFramesDoNotPoisonTheRate() {
        val ticks = watch(frames(List(10) { 5 * frame } + List(1790) { frame }), 62_000)
        assertWithin(0.25, ticks.from(2.0))
        assertWithin(0.1, ticks.from(8.0))
    }

    @Test fun aSlowdownToHalfSpeedStopsTheCountWithinSecondsAndIsFollowedWithoutJumps() {
        val ticks = watch(frames(List(900) { frame } + List(900) { 2 * frame }), 62_000)
        val before = ticks.at(31.0).shown!!
        assertTrue(ticks.at(34.0).shown!! >= before - 1)
        assertTrue(ticks.at(43.0).shown!! > before)
        assertWithin(0.3, ticks.from(45.0))
        assertTrue(ticks.steps().all { it <= 1 })
    }

    @Test fun aHeavyMiddleSectionIsFollowedAndThenForgotten() {
        val ticks = watch(frames(List(720) { frame } + List(360) { 3 * frame } + List(720) { frame }), 62_000)
        assertTrue(ticks.at(40.0).shown!! > ticks.at(25.0).shown!! + 20)
        assertWithin(0.0, ticks.from(78.0), slack = 5.0)
        assertTrue(ticks.steps().all { it <= 1 })
    }

    @Test fun alternatingCheapAndHeavySectionsDoNotSwingTheTimeUpAndDown() {
        val costs = (0 until 19).flatMap { k -> (if (k % 2 == 0) 0.03 else 0.075).let { cost -> List((5 / cost).toInt()) { cost } } }
        val samples = frames(costs)
        for (times in listOf(1.0, 0.5, 2.0)) {
            val ticks = watch(samples, plan(samples, times))
            assertTrue(ticks.climbAboveTruth() <= 20)
            assertTrue(ticks.steps().all { it <= 3 })
            val errors = ticks.from(15.0).filter { it.truth > 10 }.map { abs(it.shown!! - it.truth) / it.truth }
            assertTrue("mean error ${errors.average()}", errors.average() <= 0.1)
        }
    }

    @Test fun anAiSmoothClipBetweenGraphicsRaisesTheTimeOnceInsteadOfSwinging() {
        val samples = frames(List(600) { 0.03 } + aiClips(1, 0, 600) + List(600) { 0.03 })
        val ticks = watch(samples, plan(samples))
        assertTrue(ticks.climbAboveTruth() <= 30)
        assertTrue(ticks.steps().all { it <= 10 })
        assertTrue(ticks.from(90.0).steps().all { it <= 0 })
    }

    @Test fun framesSlowerThanASecondCountDownWithoutDipsOrJumps() {
        for (cost in listOf(3.0, 1.5, 0.6)) {
            val samples = frames(List(120) { cost }, setup = 2.0)
            val ticks = watch(samples, plan(samples))
            assertWithin(0.05, ticks.from(3.0))
            assertTrue(ticks.steps().all { it <= 0 })
        }
    }

    @Test fun framesThatTurnSlowerThanTheStallLimitStillCountInFull() {
        for (slow in listOf(List(100) { 1.0 }, List(50) { 2.0 })) {
            val samples = frames(List(900) { 0.033 } + slow)
            assertWithin(0.3, watch(samples, plan(samples)).from(90.0).filter { it.truth >= 25 })
        }
    }

    @Test fun aVeryShortExportStaysWithinASecond() {
        watch(frames(List(45) { frame }, setup = 0.3), 1_800).forEach { assertTrue(abs(it.shown!! - it.truth) <= 1.0) }
    }

    @Test fun aStallIsHeldAndDoesNotInflateTheRest() {
        val ticks = watch(frames(List(1800) { frame }, stallAt = 900, stall = 4.0), 62_000)
        assertWithin(0.2, ticks.from(2.0))
        assertTrue(ticks.steps().all { it <= 1 })
        assertTrue(ticks.steps().count { it > 0 } <= 2)
    }

    @Test fun aDownloadAndTheExportAfterItEachGetTheirOwnTime() {
        val download = download(11_300_000.0, 1_000_000.0, 64 * 1024)
        val fetched = download.last().first
        val shown = watch(download, null, skipsStalls = false)
        assertNull(shown.first().shown)
        shown.from(2.0).forEach { assertTrue(abs(it.shown!! - it.truth) <= 1.0) }
        val export = frames(List(1800) { frame }).map { (t, done) -> t + fetched to done }
        assertWithin(0.05, watch(export, 62_000, from = fetched).from(fetched + 2.0))
    }

    @Test fun aStalledDownloadShowsNoTimeInsteadOfZeroOrHours() {
        val burst = (1..20).map { k -> 0.05 * k to 0.0004f * k / 20 } + listOf(1.2 to 0.00045f, 1.3 to 0.0005f)
        val clock = ExportClock(null, 0, skipsStalls = false)
        burst.forEach { (t, done) -> clock.add(done, ns(t)) }
        assertNull(clock.secondsLeft(ns(5.0)))
        assertNull(clock.secondsLeft(ns(40.0)))
        clock.add(0.0006f, ns(41.0))
        assertNull(clock.secondsLeft(ns(41.5)))
    }

    @Test fun pausesInADownloadCountAsTimeLeft() {
        val ticks = watch(download(60_000_000.0, 2_000_000.0, 16 * 1024, pauseEvery = 1.0, pause = 1.0), null, skipsStalls = false)
        assertWithin(0.15, ticks.from(15.0))
    }

    @Test fun anEncoderRetryStartsTheMeasurementAgain() {
        val clock = ExportClock(62_000, 0)
        frames(List(1800) { frame }).take(300).forEach { (t, done) -> clock.add(done, ns(t)) }
        clock.add(0f, ns(11.0))
        val again = frames(List(1800) { 2 * frame }, setup = 12.0)
        again.filter { it.first <= 32.0 }.forEach { (t, done) -> clock.add(done, ns(t)) }
        val truth = again.last().first - 32.0
        val left = clock.secondsLeft(ns(32.0))!!
        assertTrue("$left s shown, $truth s left", abs(left - truth) <= 0.1 * truth)
    }
}
