package app.absentmotion.ui.editor

import androidx.compose.ui.geometry.Offset
import app.absentmotion.model.CropBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropViewTest {
    private val layer = listOf(Offset(0f, 420f), Offset(1920f, 420f), Offset(1920f, 1500f), Offset(0f, 1500f))
    private val small = CropBox(0f, 0.386f, 0.784f, 0.415f)

    private fun onScreen(view: CanvasView, width: Float, height: Float, reserve: Float, sceneW: Float, sceneH: Float, points: List<Offset>): List<Offset> {
        val (scale, dx, dy) = previewFrame(width, height, reserve, sceneW, sceneH, view.zoom, view.pan)
        return points.map { it * scale + Offset(dx, dy) }
    }

    @Test fun aSmallCropFillsMostOfThePreviewAndSitsInItsMiddle() {
        val corners = cropCorners(layer, small)
        val view = cropView(1080f, 1500f, 0f, 1080f, 1920f, corners)!!
        val shown = onScreen(view, 1080f, 1500f, 0f, 1080f, 1920f, corners)
        assertEquals(1080f * CROP_FILL, shown[1].x - shown[0].x, 0.5f)
        assertEquals(540f, (shown[0].x + shown[2].x) / 2f, 0.5f)
        assertEquals(750f, (shown[0].y + shown[2].y) / 2f, 0.5f)
        assertTrue(view.zoom > 2f)
    }

    @Test fun aTallCropIsLimitedByTheHeight() {
        val corners = cropCorners(layer, CropBox(0.45f, 0f, 0.45f, 0f))
        val shown = onScreen(cropView(1080f, 600f, 0f, 1080f, 1920f, corners)!!, 1080f, 600f, 0f, 1080f, 1920f, corners)
        assertEquals(600f * CROP_FILL, shown[2].y - shown[0].y, 0.5f)
        assertTrue(shown[1].x - shown[0].x < 1080f * CROP_FILL)
    }

    @Test fun aCropLargerThanThePreviewZoomsOutUntilItsHandlesShow() {
        val big = listOf(Offset(-2000f, -500f), Offset(3000f, -500f), Offset(3000f, 2500f), Offset(-2000f, 2500f))
        val view = cropView(1080f, 1500f, 0f, 1080f, 1920f, big)!!
        val shown = onScreen(view, 1080f, 1500f, 0f, 1080f, 1920f, big)
        assertTrue(view.zoom < 1f)
        assertTrue(shown.all { it.x in 0f..1080f && it.y in 0f..1500f })
    }

    @Test fun aReservedSideKeepsTheCropInTheFreePart() {
        val corners = cropCorners(layer, small)
        val shown = onScreen(cropView(2000f, 1200f, 600f, 1080f, 1920f, corners)!!, 2000f, 1200f, 600f, 1080f, 1920f, corners)
        assertEquals(700f, (shown[0].x + shown[2].x) / 2f, 0.5f)
        assertTrue(shown.all { it.x in 0f..1400f })
    }

    @Test fun aTurnedLayerIsFramedByItsOuterBounds() {
        val turned = listOf(Offset(540f, 400f), Offset(900f, 760f), Offset(540f, 1120f), Offset(180f, 760f))
        val corners = cropCorners(turned, CropBox(0.4f, 0.4f, 0.4f, 0.4f))
        val shown = onScreen(cropView(1080f, 1500f, 0f, 1080f, 1920f, corners)!!, 1080f, 1500f, 0f, 1080f, 1920f, corners)
        assertEquals(1080f * CROP_FILL, shown.maxOf { it.x } - shown.minOf { it.x }, 0.5f)
    }

    @Test fun nothingToFrameWithoutASize() {
        assertNull(cropView(0f, 0f, 0f, 1080f, 1920f, layer))
    }

    @Test fun theGlideStartsAndEndsExactlyOnTheTwoViews() {
        val from = CanvasView(1f, Offset.Zero)
        val to = CanvasView(4.5f, Offset(330f, -12f))
        assertEquals(from.zoom, viewBetween(from, to, 0f).zoom, 1e-5f)
        assertEquals(from.pan, viewBetween(from, to, 0f).pan)
        assertEquals(to, viewBetween(from, to, 1f))
        val same = CanvasView(1f, Offset(100f, 40f))
        assertEquals(Offset(50f, 20f), viewBetween(from, same, 0.5f).pan)
    }

    @Test fun theGlideKeepsOnePointStillSoNothingSwingsAcrossTheScreen() {
        val from = CanvasView(1f, Offset.Zero)
        val to = CanvasView(4.5f, Offset(330f, -12f))
        val still = (to.pan * to.zoom - from.pan * from.zoom) / (to.zoom - from.zoom)
        val scene = Offset(540f, 960f) - still
        val at = { v: CanvasView -> onScreen(v, 1080f, 1500f, 0f, 1080f, 1920f, listOf(scene))[0] }
        val start = at(from)
        var last = from.zoom
        for (i in 1..10) {
            val v = viewBetween(from, to, i / 10f)
            assertEquals(start.x, at(v).x, 0.05f)
            assertEquals(start.y, at(v).y, 0.05f)
            assertTrue(v.zoom > last)
            last = v.zoom
        }
    }

    @Test fun framingNeverTouchesTheStoredCrop() {
        val from = small
        val view = cropView(1080f, 1500f, 0f, 1080f, 1920f, cropCorners(layer, small))!!
        val quadB = onScreen(view, 1080f, 1500f, 0f, 1080f, 1920f, layer)
        val quadC = onScreen(CanvasView(1f, Offset.Zero), 1080f, 1500f, 0f, 1080f, 1920f, layer)
        val grabB = cropHandles(quadB, from)[2]
        val grabC = cropHandles(quadC, from)[2]
        val dB = boxFraction(quadB, grabB + (quadB[1] - quadB[0]) * 0.05f) - boxFraction(quadB, grabB)
        val dC = boxFraction(quadC, grabC + (quadC[1] - quadC[0]) * 0.05f) - boxFraction(quadC, grabC)
        val a = from.dragged(2, dB.x, dB.y)
        val b = from.dragged(2, dC.x, dC.y)
        assertEquals(a.right, b.right, 1e-4f)
        assertEquals(a.bottom, b.bottom, 1e-4f)
        assertEquals(from.left, a.left)
        assertEquals(from.top, a.top)
    }
}
