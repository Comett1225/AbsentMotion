package app.absentmotion.ui.editor

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewFrameTest {
    @Test fun theOverlayAndThePreviewShareTheSameOriginWithAReservedColumn() {
        val (scale, dx, dy) = previewFrame(1280f, 352f, 384f, 1080f, 1920f, 1f, Offset.Zero)
        assertEquals(352f / 1920f, scale, 1e-4f)
        assertEquals(0f, dy, 0.01f)
        assertEquals(minOf(640f, 896f - 1080f * scale / 2f) - 540f * scale, dx, 0.01f)
    }

    @Test fun withoutAReserveTheSceneIsCentred() {
        val (scale, dx, _) = previewFrame(1000f, 500f, 0f, 1000f, 1000f, 1f, Offset.Zero)
        assertEquals(0.5f, scale, 1e-4f)
        assertEquals(250f, dx, 0.01f)
    }

    @Test fun aScaleOfZeroCanGrowAgain() {
        assertEquals(0.02f, grownScale(0f, 2f), 1e-5f)
        assertEquals(0f, grownScale(0f, 0.5f), 0f)
        assertEquals(3f, grownScale(1.5f, 2f), 1e-5f)
    }
}
