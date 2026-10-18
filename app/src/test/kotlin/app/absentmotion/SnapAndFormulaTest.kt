package app.absentmotion

import app.absentmotion.engine.SnapGuide
import app.absentmotion.export.Codec
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.videoBitrate
import app.absentmotion.ui.editor.panels.Snap
import app.absentmotion.ui.editor.panels.SnapAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapAndFormulaTest {
    private val line = SnapGuide(true, 100f, false)

    @Test fun travelIsPassedOnOneToOneBetweenSnapsAndHeldAtOne() {
        val axis = SnapAxis(listOf(Snap(100f, line)))
        assertEquals(50f, axis.map(50f), 1e-4f)
        assertEquals(100f, axis.map(100f), 1e-4f)
        assertEquals(100f, axis.map(112f), 1e-4f)
        assertEquals(100f, axis.map(125f), 1e-4f)
        assertEquals(105f, axis.map(130f), 1e-4f)
        assertEquals(-40f, axis.map(-40f), 1e-4f)
        assertEquals(listOf(line), axis.guidesAt(100f))
        assertTrue(axis.guidesAt(101f).isEmpty())
    }

    @Test fun aSnapAtZeroHoldsTheLayerOnBothSidesOfTheStart() {
        val axis = SnapAxis(listOf(Snap(0f, line), Snap(-60f, line), Snap(80f, line)))
        assertEquals(0f, axis.map(0f), 1e-4f)
        assertEquals(0f, axis.map(12f), 1e-4f)
        assertEquals(0f, axis.map(-12f), 1e-4f)
        assertEquals(7.5f, axis.map(20f), 1e-4f)
        assertEquals(-60f, axis.map(-85f), 1e-4f)
        assertEquals(-57.5f, axis.map(-70f), 1e-4f)
    }

    private fun mbps(w: Int, h: Int, fps: Int, quality: Float = 0.5f, codec: Codec = Codec.H264) =
        videoBitrate(w, h, ExportSettings(minOf(w, h), fps, codec, quality)) / 1e6

    @Test fun bitrateIsPixelsPerSecondTimesBitsPerPixel() {
        assertEquals(5.90, mbps(1920, 1080, 30), 0.01)
        assertEquals(10.0, mbps(1920, 1080, 30, 1f) / mbps(1920, 1080, 30, 0f), 1e-3)
        assertEquals(2 * mbps(1920, 1080, 30), mbps(1920, 1080, 60), 1e-3)
        assertEquals(2 * mbps(1280, 720, 30), mbps(2560, 720, 30), 1e-3)
        assertEquals(0.6 * mbps(1920, 1080, 30), mbps(1920, 1080, 30, codec = Codec.Hevc), 1e-3)
        assertTrue(mbps(1920, 1080, 30, codec = Codec.Av1) < mbps(1920, 1080, 30, codec = Codec.Hevc))
        assertTrue(mbps(1920, 1080, 30, codec = Codec.Apv) > 5 * mbps(1920, 1080, 30))
        val steps = (0..20).map { mbps(1920, 1080, 30, it / 20f) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b > a })
        assertTrue(mbps(3840, 2160, 60, 1f, Codec.Apv) in 100.0..2000.0)
    }
}
