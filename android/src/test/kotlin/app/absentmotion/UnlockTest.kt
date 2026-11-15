package app.absentmotion

import app.absentmotion.export.Codec
import app.absentmotion.export.DeviceReport
import app.absentmotion.export.EncoderCaps
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.ProbeSizes
import app.absentmotion.export.SizePoint
import app.absentmotion.export.SmoothLevel
import app.absentmotion.export.Smoothing
import app.absentmotion.export.StorageStatus
import app.absentmotion.export.Support
import app.absentmotion.export.codecChoices
import app.absentmotion.export.exportBlock
import app.absentmotion.export.exportRisks
import app.absentmotion.export.resolutionChoices
import app.absentmotion.export.savable
import app.absentmotion.export.settingsFromJson
import app.absentmotion.export.settingsToJson
import app.absentmotion.i18n.txt
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnlockTest {
    private val gl = Tier.openGl
    private val medium = floatArrayOf(4f, 5f, 6f, 1f, 0.15f, 4f)
    private val safe = ExportSettings(1080, 30, interpolation = FrameInterpolation.Blend, interpolationLevel = SmoothLevel.Low)
    private val encoder = EncoderCaps("enc", "video/avc", true, 64..1920, 64..1920, 2, 2,
        ProbeSizes.filter { (w, h) -> w <= 1920 && h <= 1920 }.map { (w, h) -> SizePoint(w, h, 60.0) }, 1..100_000_000, listOf(1, 8))
    private val report = DeviceReport("k", listOf(encoder, EncoderCaps("aac", "audio/mp4a-latm", false, sampleRates = listOf(44100, 48000), maxChannels = 2)))
    private val check: (ExportSettings) -> Support = { report.check(it, it.shortSide, it.shortSide * 16 / 9) }

    @Before fun weakPhone() { Tier.openGl = true }
    @After fun restore() { Tier.openGl = gl; Tier.unlocked = emptySet() }

    @Test fun aConfirmationCoversOneRunAndIsAskedAgainForTheNext() {
        val unlock = Unlock()
        val risks = listOf(Tier.risk(Feature.AiSmooth)!!, Risk("4K", txt(R.string.unlock_text)))
        assertEquals(risks, unlock.pending(risks))
        unlock.confirm(risks.take(1))
        assertEquals(risks.drop(1), unlock.pending(risks))
        unlock.confirm(risks)
        assertTrue(unlock.pending(risks).isEmpty())
        unlock.spend()
        assertEquals(risks, unlock.pending(risks))
    }

    @Test fun everyLeftOutFeatureIsOfferedWithAWarningAndRunsOnlyWhileUnlocked() = runBlocking {
        for (feature in Feature.entries) {
            assertFalse(Tier.has(feature))
            assertTrue(Tier.choice(feature, feature).risky)
            assertEquals(feature, Tier.risk(feature)?.key)
            assertFalse(Tier.runs(feature))
            assertTrue(Tier.unlocking(listOf(feature)) { Tier.runs(feature) })
            assertFalse(Tier.runs(feature))
        }
        Tier.openGl = false
        assertEquals(!Tier.compatible, Feature.entries.all { Tier.has(it) && Tier.risk(it) == null && !Tier.choice(it, it).risky })
    }

    @Test fun opticalFlowHighUsesItsOwnSettingsOnlyWhileUnlocked() = runBlocking {
        val high = Smoothing(FrameInterpolation.Flow, SmoothLevel.High)
        val lowered = high.flowParams(medium)
        assertEquals(2f, lowered[3])
        assertEquals(medium[0], lowered[0])
        assertArrayEquals(floatArrayOf(2f, 6f, 8f, 2f, 0.15f, 4f), Tier.unlocking(listOf(Feature.FlowHigh)) { high.flowParams(medium) }, 0f)
        assertArrayEquals(lowered, high.flowParams(medium), 0f)
    }

    @Test fun exportsNameEachRiskAndPlainSettingsHaveNone() {
        val ai = safe.copy(interpolation = FrameInterpolation.Ai, interpolationLevel = SmoothLevel.High)
        val flow = safe.copy(interpolation = FrameInterpolation.Flow, interpolationLevel = SmoothLevel.High)
        assertTrue(exportRisks(ExportKind.Video, safe, Support.Supported).isEmpty())
        assertTrue(exportRisks(ExportKind.Video, flow.copy(interpolationLevel = SmoothLevel.Medium), Support.Supported).isEmpty())
        assertEquals(listOf<Any>(Feature.AiSmooth), exportRisks(ExportKind.Gif, ai, Support.Supported).map { it.key })
        assertEquals(listOf<Any>(Feature.FlowHigh), exportRisks(ExportKind.Sequence, flow, Support.Supported).map { it.key })
        assertTrue(exportRisks(ExportKind.Audio, ai, Support.Supported).isEmpty())
        Tier.openGl = false
        assertEquals(!Tier.compatible, exportRisks(ExportKind.Video, ai, Support.Supported).isEmpty())
    }

    @Test fun aSizeTheEncoderDoesNotListCanBeTriedButAMissingEncoderCannot() {
        val big = check(ExportSettings(2160, 30)) as Support.Unsupported
        assertTrue(big.risky)
        assertNull(exportBlock(StorageStatus.Enough, big))
        assertEquals(1, exportRisks(ExportKind.Video, ExportSettings(2160, 30), big).size)
        assertTrue(exportRisks(ExportKind.Gif, ExportSettings(2160, 30), big).isEmpty())
        assertTrue(resolutionChoices(ExportSettings(1080, 30), check).first { it.value.shortSide == 2160 }.let { it.risky && !it.enabled })
        val none = check(ExportSettings(1080, 30, Codec.Hevc)) as Support.Unsupported
        assertFalse(none.risky)
        assertEquals(txt(R.string.export_block_unsupported), exportBlock(StorageStatus.Enough, none))
        assertTrue(exportRisks(ExportKind.Video, ExportSettings(1080, 30, Codec.Hevc), none).isEmpty())
        assertTrue(codecChoices(ExportSettings(1080, 30), check).first { it.value == Codec.Hevc }.let { !it.risky && !it.enabled })
    }

    @Test fun anUnlockedChoiceIsNeverSavedAsTheDefault() {
        val ai = safe.copy(interpolation = FrameInterpolation.Ai, interpolationLevel = SmoothLevel.High, quality = 0.9f)
        assertEquals(safe.copy(quality = 0.9f), savable(ai, safe, Support.Supported))
        val flow = safe.copy(interpolation = FrameInterpolation.Flow, interpolationLevel = SmoothLevel.High, fps = 60)
        assertEquals(safe.copy(fps = 60), savable(flow, safe, Support.Supported))
        val big = safe.copy(shortSide = 2160, quality = 0.2f)
        assertEquals(safe.copy(quality = 0.2f), savable(big, safe, check(big)))
        val plain = safe.copy(shortSide = 720, interpolation = FrameInterpolation.Flow, interpolationLevel = SmoothLevel.Medium)
        assertEquals(plain, savable(plain, safe, check(plain)))
        assertEquals(FrameInterpolation.Blend, settingsFromJson(JSONObject(settingsToJson(ai).toString()), safe).interpolation)
        Tier.openGl = false
        assertEquals(if (Tier.compatible) safe.copy(quality = 0.9f) else ai, savable(ai, safe, Support.Supported))
    }
}
