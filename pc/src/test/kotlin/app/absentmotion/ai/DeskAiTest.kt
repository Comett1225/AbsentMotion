package app.absentmotion.ai

import android.content.Context
import android.net.Uri
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskProbe
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import kotlinx.coroutines.launch
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

    @Test fun enhancedPicturesKeepSmoothEdgesWithoutDarkFringes() {
        val clear = 0x00000000
        val red = 0xFFFF0000.toInt()
        val input = intArrayOf(red, clear, red, red)
        bleedUnderClear(input, 2, 2)
        assertEquals(0x00FF0000, input[1])
        val output = IntArray(16) { 0xFF123456.toInt() }
        scaledAlpha(intArrayOf(0xFF000000.toInt(), clear, 0xFF000000.toInt(), clear), 2, 2, 2, output)
        assertEquals(listOf(255, 191, 64, 0), (0 until 4).map { output[it] ushr 24 })
        assertEquals(0x123456, output[1] and 0xFFFFFF)
    }

    @Test fun cancellingAModelDownloadIsACancelNotAFailure() = runBlocking {
        val closed = java.util.concurrent.CountDownLatch(1)
        val open = modelConnection
        modelConnection = { url ->
            object : java.net.HttpURLConnection(java.net.URL(url)) {
                override fun connect() {}
                override fun usingProxy() = false
                override fun disconnect() = closed.countDown()
                override fun getResponseCode() = HTTP_OK
                override fun getInputStream() = object : java.io.InputStream() {
                    private var sent = false
                    override fun read() = throw java.io.IOException()
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (!sent) { sent = true; return 1_000 }
                        closed.await(60, java.util.concurrent.TimeUnit.SECONDS)
                        throw java.io.IOException("closed")
                    }
                }
            }
        }
        val dir = java.nio.file.Files.createTempDirectory("ai-cancel").toFile()
        try {
            val receiving = kotlinx.coroutines.CompletableDeferred<Unit>()
            var thrown: Throwable? = null
            val file = ModelFile("model.bin", "https://example.invalid/model.bin", 200_000, "0".repeat(64))
            val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                try { fetch(file, File(dir, "model.bin"), {}) { receiving.complete(Unit) } } catch (t: Throwable) { thrown = t }
            }
            kotlinx.coroutines.withTimeout(10_000) { receiving.await() }
            job.cancel()
            job.join()
            assertTrue("$thrown", thrown is kotlinx.coroutines.CancellationException)
        } finally {
            closed.countDown()
            modelConnection = open
            dir.deleteRecursively()
        }
    }

    @Test fun oldCaptionModelsAreRemovedAtStartAndNewOnesKept() {
        val dir = java.nio.file.Files.createTempDirectory("captions").toFile()
        try {
            val app = object : Context() { override val filesDir: File get() = dir }
            val models = File(dir, "models").apply { mkdirs() }
            val old = listOf("ggml-base-q5_1.bin", "ggml-base-q8_0.bin").map { File(models, it).apply { writeText("old") } }
            val kept = listOf(CaptionModel.name(false), CaptionModel.name(true)).map { File(models, it).apply { writeText("new") } }
            assertEquals(12L, CaptionModel.storedBytes(app))
            AiModel.dropReplaced(app)
            assertTrue(old.none { it.exists() })
            assertTrue(kept.all { it.exists() })
        } finally { dir.deleteRecursively() }
    }

    @Test fun captionsRunModel() = runBlocking {
        val model = File("../build/ai-test/ggml-small-q8_0.bin")
        assumeTrue(model.isFile && model.length() == CaptionModel.BYTES)
        val words = Whisper.transcribe(model, CaptionModel.heads(false), FloatArray(16_000), true) { }
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
        val process = ProcessBuilder(app.absentmotion.Judge.ffmpeg, "-nostdin", "-v", "error", "-y", "-i", media.resolve("test-video.mp4").path, "-an", "-c:v", "copy", input.path).start()
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
            assumeReadable(output.media.file)
            for (at in listOf(100L, 1100L, 2100L, 3100L, 4100L)) {
                val expected = DeskMedia.frame(input, video.durationUs / 1000 - at, 128)!!
                val wrong = DeskMedia.frame(input, at, 128)!!
                val actual = DeskMedia.frame(output.media.file, at, 128)!!
                assertTrue("frame at $at ms", distance(expected, actual) < distance(wrong, actual))
            }
        } finally { output.media.file.delete() }
    }
}
