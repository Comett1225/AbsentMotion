package app.absentmotion.ai

import app.absentmotion.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BestCutoutTest {
    private val store = mutableMapOf<String, String>()
    private fun choice() = CutoutChoice({ store["q"] }) { store["q"] = it }

    @Test fun theFirstUseAsksOnceAndTheAnswerIsKept() {
        assertTrue(choice().asks(offered = true))
        assertNull(choice().saved)
        choice().choose(CutoutQuality.Best)
        assertFalse(choice().asks(offered = true))
        assertEquals(CutoutQuality.Best, choice().quality(offered = true))
        assertEquals("Best", store["q"])
    }

    @Test fun settingsCanSwitchTheAnswerBothWays() {
        choice().choose(CutoutQuality.Best)
        choice().choose(CutoutQuality.Standard)
        assertEquals(CutoutQuality.Standard, choice().quality(offered = true))
        choice().choose(CutoutQuality.Best)
        assertEquals(CutoutQuality.Best, choice().quality(offered = true))
        assertFalse(choice().asks(offered = true))
    }

    @Test fun withoutBestQualityNothingIsAskedAndStandardIsUsed() {
        assertFalse(choice().asks(offered = false))
        assertEquals(CutoutQuality.Standard, choice().quality(offered = false))
        choice().choose(CutoutQuality.Best)
        assertEquals(CutoutQuality.Standard, choice().quality(offered = false))
    }

    @Test fun anUnknownStoredValueCountsAsNotAnswered() {
        store["q"] = "Ultra"
        assertNull(choice().saved)
        assertTrue(choice().asks(offered = true))
        assertEquals(CutoutQuality.Standard, choice().quality(offered = true))
    }

    private class Calls {
        val log = mutableListOf<String>()
    }

    private fun run(quality: CutoutQuality, calls: Calls, install: () -> Unit = {}, best: () -> String = { "best" }): CutoutResult<String> = runBlocking {
        bestOrStandard(quality, { calls.log += "install"; install() }, { calls.log += "best"; best() }) { calls.log += "standard"; "standard" }
    }

    @Test fun standardNeverDownloadsOrRunsBestQuality() {
        val calls = Calls()
        val result = run(CutoutQuality.Standard, calls)
        assertEquals("standard", result.value)
        assertNull(result.note)
        assertEquals(listOf("standard"), calls.log)
    }

    @Test fun bestQualityInstallsThenRuns() {
        val calls = Calls()
        val result = run(CutoutQuality.Best, calls)
        assertEquals("best", result.value)
        assertNull(result.note)
        assertEquals(listOf("install", "best"), calls.log)
    }

    @Test fun aFailedDownloadFallsBackToStandardAndSaysSo() {
        for (failure in listOf(IOException("offline"), IllegalStateException("The AI model download failed"))) {
            val calls = Calls()
            val result = run(CutoutQuality.Best, calls, install = { throw failure })
            assertEquals("standard", result.value)
            assertEquals(R.string.cutout_fallback_download, result.note)
            assertEquals(listOf("install", "standard"), calls.log)
        }
    }

    @Test fun aFailedRunFallsBackToStandardAndSaysSo() {
        for (failure in listOf(IllegalStateException("model failed"), OutOfMemoryError(), UnsatisfiedLinkError("absent_ai"))) {
            val calls = Calls()
            val result = run(CutoutQuality.Best, calls, best = { throw failure })
            assertEquals("standard", result.value)
            assertEquals(R.string.cutout_fallback_failed, result.note)
            assertEquals(listOf("install", "best", "standard"), calls.log)
        }
    }

    @Test fun cancellingStopsWithoutFallingBack() {
        for (where in listOf("install", "best")) {
            val calls = Calls()
            val stop = { throw CancellationException("cancelled") }
            val outcome = runCatching { if (where == "install") run(CutoutQuality.Best, calls, install = stop) else run(CutoutQuality.Best, calls, best = stop) }
            assertTrue(outcome.exceptionOrNull() is CancellationException)
            assertFalse("standard" in calls.log)
        }
    }

    @Test fun theLargeNetworkSizeNeedsAGpuAndPlentyOfMemory() {
        assertEquals(CUTOUT_SIDE, cutoutSide(16_000_000_000L, gpu = false))
        assertEquals(CUTOUT_SIDE, cutoutSide(8_000_000_000L, gpu = true))
        assertEquals(CUTOUT_LARGE_SIDE, cutoutSide(12_000_000_000L, gpu = true))
        assertEquals(CUTOUT_SIDE, cutoutSide(0L, gpu = true))
    }

    @Test fun theCutoutModelIsOneWeightsFileWithTwoSizes() {
        val model = AiModel.Cutout
        assertEquals(listOf(CUTOUT_PARAM_768, CUTOUT_PARAM_1024, "absent-cutout.bin"), model.files.map { it.name })
        assertEquals(CUTOUT_PARAM_768, model.param)
        assertEquals("absent-cutout.bin", model.bin)
        assertTrue(model.files.all { it.url == MODEL_RELEASE_2 + it.name })
        assertTrue(MODEL_RELEASE_2.endsWith("/releases/download/ai-models-2/"))
        assertEquals(19_350L + 19_356L + 64_875_088L, model.bytes)
    }
}
