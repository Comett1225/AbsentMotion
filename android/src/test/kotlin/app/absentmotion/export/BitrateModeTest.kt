package app.absentmotion.export

import android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
import android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
import android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitrateModeTest {
    private fun supports(vararg modes: Int): (Int) -> Boolean = { it in modes }

    @Test fun theSliderAsksForVbrAndAnExactBitrateForCbr() {
        assertEquals(BITRATE_MODE_VBR, bitrateMode(false, supports(BITRATE_MODE_CQ, BITRATE_MODE_VBR, BITRATE_MODE_CBR)))
        assertEquals(BITRATE_MODE_CBR, bitrateMode(true, supports(BITRATE_MODE_CQ, BITRATE_MODE_VBR, BITRATE_MODE_CBR)))
    }

    @Test fun aMissingModeFallsBackWithoutConstantQuality() {
        assertEquals(BITRATE_MODE_VBR, bitrateMode(true, supports(BITRATE_MODE_VBR)))
        assertEquals(BITRATE_MODE_CBR, bitrateMode(false, supports(BITRATE_MODE_CQ, BITRATE_MODE_CBR)))
        assertNull(bitrateMode(false, supports(BITRATE_MODE_CQ)))
        assertNull(bitrateMode(true, supports()))
    }

    @Test fun theBitrateIsKeptInsideWhatTheEncoderTakes() {
        assertEquals(2_000_000, encoderBitrate(2_000_000, 1..40_000_000))
        assertEquals(64_000, encoderBitrate(10_000, 64_000..40_000_000))
        assertEquals(40_000_000, encoderBitrate(90_000_000, 1..40_000_000))
        assertEquals(500_000, encoderBitrate(500_000, 0..0))
    }

    @Test fun anExactBitrateWinsOverTheQualitySlider() {
        val slider = ExportSettings(1080, 30, quality = 0.5f)
        assertEquals((1920.0 * 1080 * 30 * 0.03 * Math.pow(10.0, 0.5)).toInt().toDouble(), videoBitrate(1920, 1080, slider).toDouble(), 1.0)
        assertEquals(4_500_000, videoBitrate(1920, 1080, slider.copy(bitrate = 4_500_000)))
        assertEquals(4_000_000, videoBitrate(1920, 1080, slider.copy(bitrate = 4_500_000), 1..4_000_000))
        assertEquals(slider.copy(bitrate = 0), settingsFromJson(settingsToJson(slider.copy(bitrate = 4_500_000)), slider))
    }
}
