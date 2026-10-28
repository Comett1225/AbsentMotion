package app.absentmotion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropTest {
    private fun assertBox(expected: CropBox, actual: CropBox) {
        listOf(expected.left to actual.left, expected.top to actual.top, expected.right to actual.right, expected.bottom to actual.bottom)
            .forEach { (e, a) -> assertEquals("$expected vs $actual", e, a, 1e-4f) }
    }

    @Test fun aCornerMovesItsTwoEdgesAndTheOppositeCornerStays() {
        assertBox(CropBox(0.1f, 0.2f, 0f, 0f), CropBox().dragged(0, 0.1f, 0.2f))
        assertBox(CropBox(0f, 0f, 0.3f, 0.1f), CropBox().dragged(2, -0.3f, -0.1f))
    }

    @Test fun anEdgeMovesOnlyItsOwnSide() {
        assertBox(CropBox(0f, 0f, 0.25f, 0f), CropBox().dragged(5, -0.25f, 0.4f))
        assertBox(CropBox(0f, 0.3f, 0f, 0f), CropBox().dragged(4, 0.2f, 0.3f))
        assertBox(CropBox(0.2f, 0f, 0f, 0f), CropBox().dragged(7, 0.2f, 0f))
        assertBox(CropBox(0f, 0f, 0f, 0.15f), CropBox().dragged(6, 0f, -0.15f))
    }

    @Test fun edgesNeverCrossSoTheCropIsNeverEmpty() {
        val box = CropBox().dragged(0, 2f, 2f)
        assertTrue(box.width >= CropBox.MIN - 1e-6f && box.height >= CropBox.MIN - 1e-6f)
        val pushed = CropBox(0.5f, 0f, 0.3f, 0f).dragged(1, -0.9f, 0f)
        assertEquals(0.5f, pushed.left, 1e-6f)
        assertEquals(CropBox.MIN, pushed.width, 1e-4f)
        assertBox(CropBox(0f, 0f, 0f, 0f), CropBox(0.2f, 0.2f, 0.2f, 0.2f).dragged(0, -1f, -1f).dragged(2, 1f, 1f))
    }

    @Test fun typedSidesAreClampedAgainstTheOppositeSide() {
        val box = CropBox(right = 0.4f).withSide(0, 0.9f)
        assertEquals(1f - CropBox.MIN - 0.4f, box.left, 1e-6f)
        assertEquals(0f, CropBox().withSide(1, -0.5f).top, 0f)
        val wild = CropBox(0.8f, 0.9f, 0.7f, 0.6f).clamped()
        assertTrue(wild.left + wild.right < 1f && wild.top + wild.bottom < 1f)
    }

    @Test fun movingKeepsTheSizeAndStaysInsideTheLayer() {
        val box = CropBox(0.2f, 0.2f, 0.3f, 0.4f)
        assertBox(CropBox(0.3f, 0.1f, 0.2f, 0.5f), box.moved(0.1f, -0.1f))
        assertBox(CropBox(0.5f, 0.6f, 0f, 0f), box.moved(5f, 5f))
        assertBox(CropBox(0f, 0f, 0.5f, 0.6f), box.moved(-5f, -5f))
    }

    @Test fun presetsFitTheLargestCentredCropOfThatRatio() {
        val square = CropBox.fit(cropRatio(1f, 1920f, 1080f))
        assertEquals(1080f / 1920f, square.width, 1e-4f)
        assertEquals(1f, square.height, 1e-6f)
        assertEquals(square.left, square.right, 1e-6f)
        val wide = CropBox.fit(cropRatio(16f / 9f, 1000f, 1000f))
        assertEquals(1f, wide.width, 1e-6f)
        assertEquals(9f / 16f, wide.height, 1e-4f)
        assertEquals(wide.top, wide.bottom, 1e-6f)
        assertBox(CropBox(), CropBox.fit(cropRatio(16f / 9f, 1920f, 1080f)))
    }

    @Test fun aLockedRatioSurvivesCornerAndEdgeDrags() {
        val k = cropRatio(1f, 1920f, 1080f)
        val start = CropBox.fit(k)
        for (handle in 0..7) {
            val box = start.dragged(handle, 0.07f, -0.05f, k)
            assertEquals("handle $handle", k, box.width / box.height, 1e-3f)
            assertTrue(box.left >= 0f && box.right >= 0f && box.top >= 0f && box.bottom >= 0f)
        }
        val grown = CropBox(0.4f, 0.4f, 0.4f, 0.4f).dragged(2, 1f, 1f, 2f)
        assertEquals(2f, grown.width / grown.height, 1e-3f)
        assertEquals(0.4f, grown.left, 1e-4f)
        assertEquals(0.4f, grown.top, 1e-4f)
    }

    @Test fun aLockedRatioOnAVeryWideOrTallLayerNeverThrowsAndStaysInsideTheLayer() {
        val starts = listOf(CropBox(), CropBox(0.3f, 0.2f, 0.1f, 0.4f), CropBox(0.02f, 0.9f, 0.5f, 0f))
        val drags = listOf(0.2f to 0.1f, -0.3f to -0.4f, 0f to 0.5f, 0.5f to 0f, -2f to 2f)
        for (ratio in listOf(cropRatio(1f, 200f, 1f), cropRatio(1f, 1f, 200f), cropRatio(16f / 9f, 5000f, 10f), cropRatio(1f, 10f, 5000f)))
            for (start in starts) for (handle in 0..7) for ((du, dv) in drags) {
                val box = start.dragged(handle, du, dv, ratio)
                val label = "ratio $ratio handle $handle from $start by $du,$dv gave $box"
                assertTrue(label, box.left >= 0f && box.top >= 0f && box.right >= 0f && box.bottom >= 0f)
                assertTrue(label, box.width >= CropBox.MIN - 1e-6f && box.height >= CropBox.MIN - 1e-6f && box.width <= 1f && box.height <= 1f)
            }
    }

    @Test fun cropValuesAreWrittenAndReadOnTheLayerAndKeyedTogether() {
        val effect = XmlNode("effect", mapOf("id" to CROP_ID), CropSides.map { XmlNode("property", mapOf("name" to it, "type" to "float", "value" to "0.000000")) })
        val layer = Layer(XmlNode("shape", mapOf("id" to "1"), listOf(effect)), 0.25f, 0.24f..0.26f)
        val cropped = layer.withCropBox(0, CropBox(0.1f, 0.2f, 0.3f, 0.4f))
        assertBox(CropBox(0.1f, 0.2f, 0.3f, 0.4f), cropped.cropBox())
        assertFalse(cropped.onCropKeyframe(0))
        val keyed = cropped.withCropKeyframes(0)
        assertTrue(keyed.onCropKeyframe(0))
        assertTrue(CropSides.all { side -> keyed.effects[0].effectProperty(side)!!.keyframeAt(keyed.snap) >= 0 })
        assertBox(CropBox(0.1f, 0.2f, 0.3f, 0.4f), keyed.cropBox())
        val unkeyed = keyed.withCropKeyframes(0)
        assertTrue(CropSides.none { side -> unkeyed.effects[0].effectProperty(side)!!.keyframeAt(unkeyed.snap) >= 0 })
        assertBox(CropBox(0.1f, 0.2f, 0.3f, 0.4f), unkeyed.cropBox())
        assertBox(CropBox(0.1f, 0.2f, 0.3f, 0.4f), Layer(XmlNode.parse(keyed.node.toXml()), 0.25f, 0.24f..0.26f).cropBox())
    }
}
