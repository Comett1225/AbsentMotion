package app.absentmotion.export

import app.absentmotion.R
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportTimeTest {
    private val probe = ProbeResult(Codec.H264, 1080, 1920, 31, 600, 100)

    @Test fun nothingKnownGivesNoEstimate() {
        assertNull(estimateExportMs(emptyList(), null, Codec.H264, 1080, 1920, 30, 300))
    }

    @Test fun probeScalesWithPixelsFramesAndCodec() {
        assertEquals(5100L, estimateExportMs(emptyList(), probe, Codec.H264, 1080, 1920, 30, 300))
        assertEquals(6600L, estimateExportMs(emptyList(), probe, Codec.Hevc, 1080, 1920, 30, 300))
        assertEquals(1350L, estimateExportMs(emptyList(), probe, Codec.H264, 540, 960, 30, 300))
    }

    @Test fun historyOfTheSameCodecWins() {
        val history = listOf(
            ExportRecord(Codec.H264, 1080, 1920, 30, 300, 6400),
            ExportRecord(Codec.Hevc, 1080, 1920, 30, 300, 100_000),
        )
        assertEquals(12700L, estimateExportMs(history, probe, Codec.H264, 1080, 1920, 30, 600))
    }

    @Test fun farAwayHistoryFallsBackToTheProbe() {
        val history = listOf(ExportRecord(Codec.H264, 256, 256, 30, 60, 1000))
        assertEquals(5100L, estimateExportMs(history, probe, Codec.H264, 1080, 1920, 30, 300))
        assertEquals(5900L, estimateExportMs(history, null, Codec.H264, 512, 512, 30, 120))
    }

    @Test fun otherCodecsAreScaledAndNearestSizesPreferred() {
        val history = listOf(
            ExportRecord(Codec.H264, 1080, 1920, 30, 300, 6400),
            ExportRecord(Codec.H264, 180, 320, 30, 300, 100_000),
        )
        assertEquals(15850L, estimateExportMs(history.take(1), probe, Codec.Av1, 1080, 1920, 30, 300))
        assertEquals(12500L, estimateExportMs(history, null, Codec.H264, 1080, 1920, 60, 600))
    }

    @Test fun longExportsWeighMoreThanShortOnes() {
        val history = listOf(
            ExportRecord(Codec.H264, 1080, 1920, 30, 30, 1000),
            ExportRecord(Codec.H264, 1080, 1920, 30, 900, 18000),
        )
        assertEquals(18700L, estimateExportMs(history, null, Codec.H264, 1080, 1920, 30, 930))
    }

    @Test fun interpolationRecordsAreKeptApart() {
        val history = listOf(
            ExportRecord(Codec.H264, 1080, 1920, 30, 300, 6400),
            ExportRecord(Codec.H264, 1080, 1920, 30, 300, 60400, FrameInterpolation.Flow),
        )
        assertEquals(60400L, estimateExportMs(history, probe, Codec.H264, 1080, 1920, 30, 300, FrameInterpolation.Flow))
        assertEquals(6400L, estimateExportMs(history, probe, Codec.H264, 1080, 1920, 30, 300))
        assertEquals(5100L, estimateExportMs(history.take(1), probe, Codec.H264, 1080, 1920, 30, 300, FrameInterpolation.Ai))
        assertNull(estimateExportMs(history.take(1), null, Codec.H264, 1080, 1920, 30, 300, FrameInterpolation.Ai))
    }

    @Test fun smoothedFramesAreSampledWhenTheDrawingTimeIsMeasured() {
        assertEquals(listOf(0, 0, 100, 200), drawSamples(300, FrameInterpolation.Off))
        assertEquals(listOf(0, 1, 100, 101, 200, 201), drawSamples(300, FrameInterpolation.Ai))
        assertEquals(listOf(0, 0, 0, 0), drawSamples(1, FrameInterpolation.Off))
        assertEquals(listOf(0, 0, 0, 0, 0, 0), drawSamples(1, FrameInterpolation.Flow))
        assertEquals(63.0, drawingMs(listOf(400.0, 14.0, 63.0, 66.0), FrameInterpolation.Off), 0.0)
        assertEquals(57.75, drawingMs(listOf(400.0, 40.0, 70.0, 45.0, 66.0, 50.0), FrameInterpolation.Flow), 1e-9)
    }

    @Test fun aGifOfAHeavyProjectIsNotEstimatedFromLighterExports() {
        val sample = GifSample(1000, listOf(10.0, 12.0, 11.0), listOf(60.0, 55.0, 70.0, 58.0, 62.0))
        assertEquals(300L + 204 * 60 + 204 * 11, gifTimeMs(204, sample, 3488))
        assertEquals(50_000L + 204 * 11, gifTimeMs(204, sample, 50_000))
        assertEquals(300L + 204 * 60 + 204 * 11, gifTimeMs(204, sample, null))
    }

    @Test fun encodersSlowerThanTheExportSetTheShortestTime() {
        fun encoder(hardware: Boolean, fps: Double) = EncoderCaps("e", "video/avc", hardware, 64..4096, 64..4096, 2, 2, listOf(SizePoint(1080, 1920, fps)))
        assertEquals(32000L, encoderFloorMs(encoder(false, 30.0), 1080, 1920, 60, 960))
        assertEquals(10000L, encoderFloorMs(encoder(false, 30.0), 1080, 1920, 30, 300))
        assertEquals(20000L, encoderFloorMs(encoder(true, 30.0), 1080, 1920, 60, 600))
        assertNull(encoderFloorMs(encoder(true, 120.0), 1080, 1920, 60, 600))
        assertNull(encoderFloorMs(null, 1080, 1920, 60, 600))
    }

    @Test fun sizeEstimatesLearnTheMedianOfRecentExports() {
        assertEquals(1.0, sizeRatio(emptyList()), 0.0)
        assertEquals(0.25, sizeRatio(listOf(100L to 25L)), 1e-9)
        assertEquals(0.3, sizeRatio(listOf(100L to 90L, 100L to 20L, 100L to 30L, 100L to 40L, 100L to 25L, 100L to 35L)), 1e-9)
        assertEquals(4.0, sizeRatio(listOf(10L to 1000L)), 0.0)
    }

    @Test fun recordsSurviveJson() {
        val records = listOf(ExportRecord(Codec.Vp9, 720, 1280, 60, 120, 9000, FrameInterpolation.Blend))
        assertEquals(records, recordsFromJson(JSONArray(recordsToJson(records).toString())))
    }

    @Test fun labelsRound() {
        assertEquals(txt(R.string.export_time_few_seconds), timeLabel(4_000))
        assertEquals(UiText.plural(R.plurals.export_time_seconds, 45, "45"), timeLabel(44_000))
        assertEquals(UiText.plural(R.plurals.export_time_minutes, 3, "3"), timeLabel(170_000))
        val locale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.UK)
            assertEquals(UiText.plural(R.plurals.export_time_hours_count, 1, "1"), timeLabel(3_600_000))
            assertEquals(UiText.plural(R.plurals.export_time_hours_count, 2, "1.5"), timeLabel(5_400_000))
            assertEquals(UiText.plural(R.plurals.export_time_hours_count, 12, "12"), timeLabel(43_000_000))
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("es"))
            assertEquals(UiText.plural(R.plurals.export_time_hours_count, 2, "1,5"), timeLabel(5_400_000))
        } finally {
            java.util.Locale.setDefault(locale)
        }
    }

    @Test fun timeLeftStartsFromThePlanAndFollowsTheMeasuredPace() {
        assertEquals(60, exportSecondsLeft(0.0, 0f, 60_000))
        assertEquals(50, exportSecondsLeft(10.0, 0.005f, 60_000))
        assertEquals(null, exportSecondsLeft(1.0, 0f, null))
        assertEquals(30, exportSecondsLeft(30.0, 0.5f, 10_000))
        assertEquals(0, exportSecondsLeft(5.0, 1f, 60_000))
    }
}
