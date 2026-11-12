package app.absentmotion.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbSquareTest {
    private fun near(expected: ThumbSquare, actual: ThumbSquare) {
        assertEquals(expected.left, actual.left, 0.01f)
        assertEquals(expected.top, actual.top, 0.01f)
        assertEquals(expected.side, actual.side, 0.01f)
    }

    @Test fun theDefaultIsTheLargestCentredSquare() {
        near(ThumbSquare(420f, 0f, 1080f), CommunityDetails("t").square(1920, 1080))
        near(ThumbSquare(0f, 420f, 1080f), CommunityDetails("t").square(1080, 1920))
    }

    @Test fun theSquareStaysInsideThePicture() {
        near(ThumbSquare(840f, 0f, 1080f), ThumbSquare(0f, 0f, 1080f).moved(5000f, 300f, 1920, 1080))
        near(ThumbSquare(10f, 0f, 1080f), ThumbSquare(10f, 10f, 4000f).fitted(1920, 1080))
        near(ThumbSquare(0f, 0f, 216f), ThumbSquare(0f, 0f, 5f).fitted(1920, 1080))
    }

    @Test fun aCornerResizesAgainstTheOppositeCorner() {
        val start = ThumbSquare(800f, 200f, 400f)
        near(ThumbSquare(800f, 200f, 600f), start.dragged(2, 1400f, 700f, 1920, 1080))
        near(ThumbSquare(900f, 300f, 300f), start.dragged(0, 900f, 350f, 1920, 1080))
        near(ThumbSquare(800f, 200f, 880f), start.dragged(2, 3000f, 3000f, 1920, 1080))
    }

    @Test fun pinchingKeepsTheCentre() {
        near(ThumbSquare(850f, 250f, 300f), ThumbSquare(800f, 200f, 400f).scaled(0.75f, 1920, 1080))
        near(ThumbSquare(892f, 292f, 216f), ThumbSquare(800f, 200f, 400f).scaled(0.1f, 1920, 1080))
    }

    @Test fun theStoredSquareComesBackTheSame() {
        val square = ThumbSquare(300f, 100f, 500f)
        near(square, CommunityDetails("t").withSquare(square, 1920, 1080).square(1920, 1080))
    }

    @Test fun croppingCopiesTheChosenPixels() {
        val pixels = ByteArray(4 * 3 * 4) { (it / 4).toByte() }
        val (out, side) = cropSquare(pixels, 4, 3, ThumbSquare(1f, 1f, 2f))
        assertEquals(2, side)
        assertArrayEquals(byteArrayOf(5, 5, 5, 5, 6, 6, 6, 6, 9, 9, 9, 9, 10, 10, 10, 10), out)
    }
}
