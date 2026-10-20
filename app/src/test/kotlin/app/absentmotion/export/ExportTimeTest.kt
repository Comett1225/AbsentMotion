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
    }

    @Test fun recordsSurviveJson() {
        val records = listOf(ExportRecord(Codec.Vp9, 720, 1280, 60, 120, 9000, FrameInterpolation.Blend))
        assertEquals(records, recordsFromJson(JSONArray(recordsToJson(records).toString())))
    }

    @Test fun labelsRound() {
        assertEquals(txt(R.string.export_time_few_seconds), timeLabel(4_000))
        assertEquals(UiText.plural(R.plurals.export_time_seconds, 45, "45"), timeLabel(44_000))
        assertEquals(UiText.plural(R.plurals.export_time_minutes, 3, "3"), timeLabel(170_000))
        assertEquals(txt(R.string.export_time_hours, "1.5"), timeLabel(5_400_000))
    }
}
