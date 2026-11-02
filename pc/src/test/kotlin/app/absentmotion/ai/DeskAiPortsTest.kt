package app.absentmotion.ai

import android.content.Context
import android.net.Uri
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskProbe
import app.absentmotion.export.SmoothLevel
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.Smoothing
import app.absentmotion.engine.EngineThread
import app.absentmotion.model.Scene
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class DeskAiPortsTest {
    private val context = object : Context() {
        override val filesDir: File get() = File("../build/ai-model-test/files").absoluteFile.also { it.mkdirs() }
    }
    private val media = File("../testdata/media").absoluteFile

    private fun tinyVideo(): Pair<Layer, MediaDeclaration> {
        val file = File("build/ai-input-small.mp4").absoluteFile
        val process = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-i", media.resolve("test-video.mp4").path,
            "-t", "0.8", "-vf", "scale=64:36,fps=5", "-c:v", "mpeg4", "-c:a", "aac", file.path).start()
        check(process.waitFor() == 0)
        val uri = Uri.fromFile(file).toString()
        val layer = Layer(XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "800", "fillType" to "media", "fillVideo" to uri, "outTime" to "800", "s" to ".rect")))
        return layer to MediaDeclaration(XmlNode("media", mapOf("uri" to uri, "width" to "64", "height" to "36", "fps" to "5", "duration" to "800000", "type" to "video/mp4")))
    }

    @Test fun enhanceVideoWritesUpscaledClip() = runBlocking {
        assumeTrue(AiModel.Cartoon.installed(context))
        val (layer, info) = tinyVideo()
        val output = AiEnhance.run(context, layer, info, EnhanceOptions(EnhanceMode.Upscale2, cartoon = true)) { _, _ -> }
        try {
            val stream = DeskProbe.of(output.file).streams.first { it.type == "video" }
            assertTrue(stream.width == 128 && stream.height == 72)
            assertTrue(DeskProbe.of(output.file).audio != null)
            assertTrue(DeskMedia.frame(output.file, 200, 128) != null)
        } finally { output.file.delete() }
    }

    @Test fun aiSmoothWritesInterpolatedClip() = runBlocking {
        assumeTrue(AiModel.SmoothLow.installed(context))
        val (layer, info) = tinyVideo()
        val engine = EngineThread(0xff000000.toInt(), context)
        val result = try { ClipSmooth.run(context, engine, layer, info, 10, Smoothing(FrameInterpolation.Ai, SmoothLevel.Low)) { _, _ -> } } finally { engine.close() }
        try {
            val video = DeskProbe.of(result.file).streams.first { it.type == "video" }
            assertTrue(video.fps >= 9.0)
            assertTrue(DeskProbe.of(result.file).audio != null)
            assertTrue(DeskMedia.frame(result.file, 300, 64) != null)
        } finally { result.file.delete() }
    }

    @Test fun flowAndBlendSmoothVideo() = runBlocking {
        val (layer, info) = tinyVideo()
        for (method in listOf(FrameInterpolation.Blend, FrameInterpolation.Flow)) {
            val result = desktopClipSmooth(context, layer, info, 10, Smoothing(method, SmoothLevel.Low)) { _, _ -> }
            try {
                val video = DeskProbe.of(result.file).streams.first { it.type == "video" }
                assertTrue(video.fps >= 9.0)
                assertTrue(video.durationUs >= 700_000)
                assertTrue(DeskMedia.frame(result.file, 600, 64) != null)
                fun pixels(at: Long): IntArray {
                    val image = DeskMedia.frame(result.file, at, 64)!!
                    return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                }
                val before = pixels(0)
                val middle = pixels(100)
                val after = pixels(200)
                assertTrue(!middle.contentEquals(before) && !middle.contentEquals(after))
            } finally { result.file.delete() }
        }
    }

    @Test fun aiInterpolationChangesExportFrame() = runBlocking {
        assumeTrue(AiModel.SmoothLow.installed(context))
        val (layer, info) = tinyVideo()
        val uri = info.uri
        val xml = """<scene title="x" width="64" height="36" totalTime="800" fps="10" bgcolor="#ff000000"><media uri="$uri" type="video/mp4" width="64" height="36" duration="800000" fps="5"/><shape id="1" startTime="0" endTime="800" fillType="media" fillVideo="$uri" outTime="800" mediaFillMode="stretch" s=".rect"><transform><location value="32,18,0"/></transform><property name="size" type="vec2" value="32,18"/></shape></scene>"""
        val engine = EngineThread(0xff000000.toInt(), context)
        try {
            val plain = engine.exportSession(xml, Smoothing(), 10) { still(100, 64, 36) }!!
            val smooth = engine.exportSession(xml, Smoothing(FrameInterpolation.Ai, SmoothLevel.Low), 10) { still(100, 64, 36) }!!
            assertTrue(!plain.contentEquals(smooth))
        } finally { engine.close() }
    }

    @Test fun aiInterpolationUsesAdjacentThirtyFpsFrames() = runBlocking {
        assumeTrue(AiModel.SmoothLow.installed(context))
        val file = File("build/ai-input-thirty.mp4").absoluteFile
        val process = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-i", media.resolve("test-video.mp4").path,
            "-t", "0.2", "-vf", "scale=64:36", "-c:v", "mpeg4", "-an", file.path).start()
        check(process.waitFor() == 0)
        val uri = Uri.fromFile(file).toString()
        val xml = """<scene title="x" width="64" height="36" totalTime="200" fps="60" bgcolor="#ff000000"><media uri="$uri" type="video/mp4" width="64" height="36" duration="200000" fps="30"/><shape id="1" startTime="0" endTime="200" fillType="media" fillVideo="$uri" outTime="200" mediaFillMode="stretch" s=".rect"><transform><location value="32,18,0"/></transform><property name="size" type="vec2" value="32,18"/></shape></scene>"""
        val engine = EngineThread(0xff000000.toInt(), context)
        try {
            val plain = engine.exportSession(xml, Smoothing(), 60) { still(17, 64, 36) }!!
            val smooth = engine.exportSession(xml, Smoothing(FrameInterpolation.Ai, SmoothLevel.Low), 60) { still(17, 64, 36) }!!
            assertTrue(!plain.contentEquals(smooth))
        } finally { engine.close(); file.delete() }
    }

    @Test fun macFacesHandlesImageWithoutFaces() {
        assumeTrue(app.absentmotion.platform.PlatformFeatures.aiFaces)
        val image = ImageIO.read(media.resolve("test-image.jpg"))
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        assertTrue(desktopFaces(pixels, image.width, image.height, 0).isEmpty())
    }

    @Test fun videoMatteEncodesMask() = runBlocking {
        val file = media.resolve("test-video.mp4")
        val uri = Uri.fromFile(file).toString()
        val info = DeskProbe.of(file).streams.first { it.type == "video" }
        val layer = Layer(XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "500", "fillType" to "media", "fillVideo" to uri, "inTime" to "200", "outTime" to "600", "s" to ".rect")))
        val declaration = MediaDeclaration(XmlNode("media", mapOf("uri" to uri, "width" to "${info.width}", "height" to "${info.height}", "fps" to "${info.fps}", "duration" to "${info.durationUs}")))
        val matte = VideoBackground.run(context, layer, declaration, 64, emptyList()) { _, _ -> }
        try {
            val frame = DeskMedia.frame(matte.media.file, 300, 128)!!
            val values = frame.getRGB(0, 0, frame.width, frame.height, null, 0, frame.width).map { it and 255 }
            assertTrue(values.max() - values.min() > 8)
        } finally { matte.media.file.delete() }
    }

    @Test fun bestCutoutProducesAlpha() = runBlocking {
        assumeTrue(AiModel.Cutout.installed(context))
        val result = BestCutout.run(context, Uri.fromFile(media.resolve("test-image.jpg")).toString(), null, 256) { _, _ -> }
        try {
            val image = ImageIO.read(result.file)
            val values = image.getRGB(0, 0, image.width, image.height, null, 0, image.width).map { it ushr 24 }
            assertTrue(values.max() - values.min() > 8)
        } finally { result.file.delete() }
    }

    @Test fun rifeInterpolatesFrames() = runBlocking {
        assumeTrue(AiModel.SmoothLow.installed(context))
        val input = media.resolve("test-video.mp4")
        fun frame(ms: Long): IntArray {
            val image = DeskMedia.frame(input, ms, 64)!!
            return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        }
        val a = frame(0)
        val b = frame(200)
        val image = DeskMedia.frame(input, 0, 64)!!
        AiSmooth.open(context, SmoothLevel.Low, false).use { net ->
            val output = net.between(a, b, image.width, image.height, 0.5f)
            assertTrue(output.size == a.size)
            assertTrue(output.indices.any { output[it] != a[it] && output[it] != b[it] })
        }
    }

    @Test fun rifeMediumAndHighRun() = runBlocking {
        assumeTrue(AiModel.SmoothMedium.installed(context) && AiModel.SmoothHigh.installed(context))
        val image = DeskMedia.frame(media.resolve("test-video.mp4"), 0, 32)!!
        val first = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val nextImage = DeskMedia.frame(media.resolve("test-video.mp4"), 200, 32)!!
        val second = nextImage.getRGB(0, 0, nextImage.width, nextImage.height, null, 0, nextImage.width)
        for (level in listOf(SmoothLevel.Medium, SmoothLevel.High)) AiSmooth.open(context, level, false).use { net ->
            val output = net.between(first, second, image.width, image.height, 0.5f)
            assertTrue(output.size == first.size && output.indices.any { output[it] != first[it] && output[it] != second[it] })
        }
    }

    @Test fun enhanceDetailAndGrainRun() = runBlocking {
        assumeTrue(AiModel.Detail.installed(context) && AiModel.Grain.installed(context))
        val image = DeskMedia.frame(media.resolve("test-video.mp4"), 0, 32)!!
        val input = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        AiNet.open(context, AiModel.Detail, AiModel.Grain, 0.5f, false).use { net ->
            val output = net.upscale(input, image.width, image.height, 1)
            assertTrue(output.size == input.size && output.toSet().size > 8)
        }
    }

    @Test fun enhanceUpscalesFrame() = runBlocking {
        assumeTrue(AiModel.Cartoon.installed(context))
        val image = DeskMedia.frame(media.resolve("test-video.mp4"), 0, 32)!!
        val input = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        AiNet.open(context, AiModel.Cartoon, gpu = false).use { net ->
            val output = net.upscale(input, image.width, image.height, 2)
            assertTrue(output.size == input.size * 4)
            assertTrue(output.toSet().size > 8)
        }
    }
}
