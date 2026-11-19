package app.absentmotion.ui.editor

import androidx.compose.ui.geometry.Offset
import app.absentmotion.model.CropBox
import app.absentmotion.model.Scene
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    private fun picture(effect: String) = Scene.parse(
        """<scene width="600" height="500" totalTime="999" fps="30"><shape id="1" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="300,250,0" /></transform><property name="size" type="vec2" value="200,150" />$effect</shape></scene>""",
    ).layers.first()

    private fun crop(hidden: Boolean = false) =
        """<effect id="absent.crop"${if (hidden) " hidden=\"true\"" else ""}><property name="left" type="float" value="0.25" /><property name="top" type="float" value="0.1" /><property name="right" type="float" value="0.25" /><property name="bottom" type="float" value="0.3" /></effect>"""

    private fun flat(q: List<Offset>) = FloatArray(8) { if (it % 2 == 0) q[it / 2].x else q[it / 2].y }

    @Test fun theWholePictureComesBackFromTheCroppedFrameOfATurnedLayer() {
        val turned = listOf(layer[1], layer[2], layer[3], layer[0])
        val cropped = flat(cropCorners(turned, CropBox(0.25f, 0.1f, 0.25f, 0.3f)))
        assertArrayEquals(flat(turned), uncroppedQuad(cropped, picture(crop())), 0.01f)
    }

    @Test fun anUncroppedOrHiddenCropFrameIsTheWholePicture() {
        val whole = flat(layer)
        assertSame(whole, uncroppedQuad(whole, picture("")))
        assertSame(whole, uncroppedQuad(whole, picture(crop(hidden = true))))
    }
}
