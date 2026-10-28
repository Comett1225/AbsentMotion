package app.absentmotion.ui.editor

import androidx.compose.ui.geometry.Offset
import app.absentmotion.model.CropBox
import org.junit.Assert.assertEquals
import org.junit.Test

class CropHandleTest {
    private val layer = listOf(Offset(100f, 100f), Offset(500f, 100f), Offset(500f, 400f), Offset(100f, 400f))
    private val box = CropBox(0.25f, 0f, 0.25f, 0f)

    @Test fun handlesSitOnTheCropNotOnTheLayer() {
        val h = cropHandles(layer, box)
        assertEquals(Offset(200f, 100f), h[0])
        assertEquals(Offset(400f, 400f), h[2])
        assertEquals(Offset(400f, 250f), h[5])
    }

    @Test fun theNearestHandleWinsWithinThirtyDp() {
        assertEquals(0, cropGrabAt(layer, box, Offset(175f, 110f), 1f))
        assertEquals(7, cropGrabAt(layer, box, Offset(180f, 250f), 1f))
        assertEquals(-1, cropGrabAt(layer, box, Offset(150f, 250f), 1f))
    }

    @Test fun insideTheCropMovesItAndTheDimmedPartDoesNothing() {
        assertEquals(CROP_INSIDE, cropGrabAt(layer, box, Offset(300f, 200f), 1f))
        assertEquals(-1, cropGrabAt(layer, box, Offset(120f, 200f), 1f))
    }

    @Test fun aTurnedLayerKeepsLeftAndRightOnItsOwnSides() {
        val turned = listOf(layer[1], layer[2], layer[3], layer[0])
        assertEquals(Offset(500f, 175f), cropHandles(turned, box)[0])
    }
}
