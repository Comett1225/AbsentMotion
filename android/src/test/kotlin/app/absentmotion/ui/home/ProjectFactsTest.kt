package app.absentmotion.ui.home

import app.absentmotion.export.*
import app.absentmotion.model.Scene
import org.junit.Assert.*
import org.junit.Test

class ProjectFactsTest {
    @Test fun roundedPresetDimensionsKeepTheirAspectLabels() {
        for ((w, h) in listOf(540 to 676, 270 to 338, 180 to 226, 1080 to 1350)) assertEquals("4:5", aspectName(w, h))
        assertEquals("1:1", aspectName(1000, 1000))
        assertNull(aspectName(1000, 900))
    }

    @Test fun fileSizesPromoteRoundedUnitBoundaries() {
        assertEquals("1MB", formatBytes(1048575))
        assertEquals("0B", formatBytes(0))
        assertEquals("609B", formatBytes(609))
        assertEquals("190KB", formatBytes(190 * 1024 + 100))
        assertEquals("1.7MB", formatBytes(1782579))
        assertEquals("1023KB", formatBytes(1047552))
        assertEquals("1MB", formatBytes(1048550))
        assertEquals("1GB", formatBytes(1073741800))
        assertEquals("23MB", formatBytes(23929388))
        assertEquals("1KB", formatBytes(1024))
    }

    @Test fun longGifsHavePositiveAccurateEstimates() {
        val scene = Scene.create("p", 720, 1280, 30, 0).let { Scene(it.node.with("totalTime", "79999")) }
        val settings = ExportSettings(720, 30)
        assertEquals((720L * 1280 * frameCount(scene, 30) * GifBytesPerPixel).toLong(), estimateBytes(ExportKind.Gif, scene, settings, false))
    }
}
