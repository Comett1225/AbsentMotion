package app.absentmotion.ai

import android.content.Context
import android.net.Uri
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskProbe
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

class DeskAiTest {
    private val context = Context()
    private val media = File("../testdata/media").absoluteFile

    @Test fun captionsRunModel() = runBlocking {
        val model = File("../build/ai-test/ggml-base-q5_1.bin")
        assumeTrue(model.isFile && model.length() == CaptionModel.BYTES)
        val words = Whisper.transcribe(model, FloatArray(16_000), true) { }
        assertTrue(words.all { it.text.isNotBlank() })
    }

    @Test fun backgroundRemovalProducesAlpha() = runBlocking {
        assertTrue(BackgroundRemoval.AVAILABLE)
        val output = BackgroundRemoval.run(context, Uri.fromFile(media.resolve("test-image.jpg")).toString(), null, 256) { _, _ -> }
        try {
            val image = ImageIO.read(output.file)
            val alphas = image.getRGB(0, 0, image.width, image.height, null, 0, image.width).map { it ushr 24 }
            assertTrue(alphas.min() < 250)
            assertTrue(alphas.max() > alphas.min())
        } finally { output.file.delete() }
    }

    @Test fun noiseReductionWritesAudio() = runBlocking {
        val output = NoiseReduction.run(context, Uri.fromFile(media.resolve("test-audio.m4a")).toString(), NoiseSource.Voice) { _, _ -> }
        try {
            assertTrue(output.file.length() > 44)
            assertTrue(DeskProbe.of(output.file).audio != null)
        } finally { output.file.delete() }
    }

    @Test fun reverseWithoutAudio() = runBlocking {
        val input = File("build/ai-silent.mp4")
        val process = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-i", media.resolve("test-video.mp4").path, "-an", "-c:v", "copy", input.path).start()
        assertEquals(0, process.waitFor())
        val uri = Uri.fromFile(input.absoluteFile).toString()
        val layer = Layer(XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "3000", "fillType" to "media", "fillVideo" to uri, "outTime" to "3000")))
        val declaration = MediaDeclaration(XmlNode("media", mapOf("uri" to uri, "duration" to "5000000")))
        try {
            val result = desktopReverse(context, layer, declaration) { _, _ -> }
            try {
                assertTrue(DeskProbe.of(result.media.file).streams.any { it.type == "video" })
                assertTrue(DeskProbe.of(result.media.file).audio == null)
            } finally { result.media.file.delete() }
        } finally { input.delete() }
    }

    @Test fun reverseChangesFrameOrder() = runBlocking {
        val input = media.resolve("test-video.mp4")
        val uri = Uri.fromFile(input).toString()
        val video = DeskProbe.of(input).streams.first { it.type == "video" }
        val layer = Layer(XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "3000", "fillType" to "media", "fillVideo" to uri, "outTime" to "3000")))
        val declaration = MediaDeclaration(XmlNode("media", mapOf("uri" to uri, "duration" to "${video.durationUs}")))
        val output = desktopReverse(context, layer, declaration) { _, _ -> }
        try {
            assertEquals(video.durationUs, output.mirrorUs)
            assertTrue(DeskProbe.of(output.media.file).audio != null)
            fun distance(a: java.awt.image.BufferedImage, b: java.awt.image.BufferedImage): Long {
                var sum = 0L
                for (y in 0 until a.height) for (x in 0 until a.width) {
                    val p = a.getRGB(x, y); val q = b.getRGB(x, y)
                    for (shift in listOf(0, 8, 16)) sum += abs(((p shr shift) and 255) - ((q shr shift) and 255))
                }
                return sum
            }
            for (at in listOf(100L, 1100L, 2100L, 3100L, 4100L)) {
                val expected = DeskMedia.frame(input, video.durationUs / 1000 - at, 128)!!
                val wrong = DeskMedia.frame(input, at, 128)!!
                val actual = DeskMedia.frame(output.media.file, at, 128)!!
                assertTrue("frame at $at ms", distance(expected, actual) < distance(wrong, actual))
            }
        } finally { output.media.file.delete() }
    }
}
