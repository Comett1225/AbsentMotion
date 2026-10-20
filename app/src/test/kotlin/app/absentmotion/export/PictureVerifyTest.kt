package app.absentmotion.export

import app.absentmotion.R
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import app.absentmotion.model.Scene
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureVerifyTest {
    private fun gif(frames: Int, fps: Int, width: Int = 6, height: Int = 4): ByteArray {
        val out = ByteArrayOutputStream()
        val pixels = ByteArray(width * height * 4) { if (it % 4 == 3) 255.toByte() else (it * 37).toByte() }
        GifWriter(out, width, height).apply { repeat(frames) { frame(pixels, (it + 1) * 1000L / fps) }; finish() }
        return out.toByteArray()
    }

    private fun facts(bytes: ByteArray) = gifFacts(ByteArrayInputStream(bytes))

    private val sevenAt12 = PictureExpectation(6, 4, 7, 7 * 1_000_000L / 12, 1_000_000L / 12)

    @Test fun aWrittenGifIsReadBack() {
        val read = facts(gif(7, 12))!!
        assertEquals(6, read.width)
        assertEquals(4, read.height)
        assertEquals(7, read.frames)
        assertEquals(580_000L, read.durationUs)
        assertTrue(read.complete)
    }

    @Test fun aCutGifIsIncomplete() {
        val bytes = gif(7, 12)
        val noTrailer = facts(bytes.copyOf(bytes.size - 1))!!
        assertFalse(noTrailer.complete)
        assertEquals(7, noTrailer.frames)
        val half = facts(bytes.copyOf(bytes.size / 2))!!
        assertFalse(half.complete)
        assertTrue("${half.frames}", half.frames in 1..6)
    }

    @Test fun otherFilesAreNotGifs() {
        assertNull(facts(byteArrayOf()))
        assertNull(facts(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 13, 10, 26, 10, 0, 0, 0, 13)))
        assertNull(facts("GIF89a".toByteArray()))
    }

    @Test fun aMatchingGifPasses() {
        assertEquals(emptyList<UiText>(), judgePicture(6, 4, facts(gif(7, 12)), sevenAt12))
    }

    @Test fun wrongSizeFramesOrLengthAreReported() {
        assertEquals(listOf(txt(R.string.export_verify_size, "8x4", "6x4")), judgePicture(8, 4, facts(gif(7, 12)), sevenAt12))
        assertEquals(
            listOf(txt(R.string.export_verify_frames, "5", "7"), txt(R.string.export_verify_duration, "0.420", "0.583")),
            judgePicture(6, 4, facts(gif(5, 12)), sevenAt12),
        )
        assertEquals(listOf(txt(R.string.export_verify_duration, "1.170", "0.583")), judgePicture(6, 4, facts(gif(7, 6)), sevenAt12))
    }

    @Test fun anUnreadableOrCutGifDoesNotPass() {
        assertEquals(listOf(txt(R.string.export_verify_picture)), judgePicture(6, 4, null, sevenAt12))
        val bytes = gif(7, 12)
        assertEquals(listOf(txt(R.string.export_verify_picture)), judgePicture(6, 4, facts(bytes.copyOf(bytes.size - 1)), sevenAt12))
    }

    @Test fun stillPicturesOnlyNeedTheirSize() {
        val still = PictureExpectation(1080, 1920)
        assertFalse(still.animated)
        assertEquals(emptyList<UiText>(), judgePicture(1080, 1920, null, still))
        assertEquals(listOf(txt(R.string.export_verify_size, "1920x1080", "1080x1920")), judgePicture(1920, 1080, null, still))
    }

    @Test fun expectationsFollowTheExportSettings() {
        val scene = Scene(Scene.create("t", 1080, 1920, 30, 0).node.with("totalTime", "2000"))
        assertEquals(PictureExpectation(360, 640, 31, 2_066_666, 66_666), pictureExpectationFor(ExportKind.Gif, scene, ExportSettings(360, 15)))
        assertEquals(PictureExpectation(1080, 1920), pictureExpectationFor(ExportKind.Frame, scene, ExportSettings(360, 30)))
        assertEquals(PictureExpectation(1080, 1920), pictureExpectationFor(ExportKind.FrameAs, scene, ExportSettings(360, 30, image = ImageKind.Jpeg)))
        assertNull(pictureExpectationFor(ExportKind.Video, scene, ExportSettings(360, 30)))
        assertNull(pictureExpectationFor(ExportKind.Sequence, scene, ExportSettings(360, 30)))
    }
}
