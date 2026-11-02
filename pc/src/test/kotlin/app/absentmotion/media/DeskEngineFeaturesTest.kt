package app.absentmotion.media

import android.content.Context
import android.net.Uri
import app.absentmotion.desk.DeskMedia
import app.absentmotion.engine.EngineThread
import app.absentmotion.engine.variableRate
import app.absentmotion.pc.engine.FfmpegVideoSource
import app.absentmotion.export.Smoothing
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.MotionBlur
import app.absentmotion.model.Pt
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskEngineFeaturesTest {
    private val out = File("build/engine-features").absoluteFile.also { it.mkdirs() }
    private val context = object : Context() {
        override fun getExternalFilesDir(type: String?): File = out
    }

    @Test fun modelFileDraws() = runBlocking {
        val model = out.resolve("triangle.obj").apply { writeText("v -1 -1 0\nv 1 -1 0\nv 0 1 0\nf 1 2 3\n") }
        val uri = Uri.fromFile(model).toString()
        val xml = """<scene title="Model" width="128" height="128" totalTime="1000" fps="30" bgcolor="#ff000000"><model id="1" startTime="0" endTime="1000" src="$uri"><transform><location value="64,64,0"/></transform><property name="size" type="float" value="48"/><property name="ambient" type="float" value="1"/></model></scene>"""
        val engine = EngineThread(0xff000000.toInt(), context)
        try {
            val pixels = engine.exportSession(xml) { still(0, 128, 128) }
            assertNotNull(pixels)
            assertTrue(pixels!!.indices.step(4).count { (pixels[it].toInt() and 255) > 20 } > 100)
        } finally { engine.close() }
    }

    @Test fun modelTextureDraws() = runBlocking {
        val texture = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 4) for (x in 0 until 4) texture.setRGB(x, y, 0xff0000)
        ImageIO.write(texture, "png", out.resolve("texture.png"))
        out.resolve("triangle.mtl").writeText("newmtl paint\nKd 1 1 1\nmap_Kd texture.png\n")
        val model = out.resolve("textured.obj").apply {
            writeText("mtllib triangle.mtl\nv -1 -1 0\nv 1 -1 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0.5 1\nusemtl paint\nf 1/1 2/2 3/3\n")
        }
        val uri = Uri.fromFile(model).toString()
        val xml = """<scene title="Model" width="128" height="128" totalTime="1000" fps="30" bgcolor="#ff000000"><model id="1" startTime="0" endTime="1000" src="$uri"><transform><location value="64,64,0"/></transform><property name="size" type="float" value="48"/><property name="ambient" type="float" value="1"/></model></scene>"""
        val engine = EngineThread(0xff000000.toInt(), context)
        try {
            val pixels = engine.exportSession(xml) { still(0, 128, 128) }!!
            assertTrue(pixels.indices.step(4).count { (pixels[it].toInt() and 255) > 100 && (pixels[it + 1].toInt() and 255) < 30 } > 100)
        } finally { engine.close() }
    }

    @Test fun exportBlendFlowAndBlurChangeVideo() = runBlocking {
        val video = out.resolve("smooth.mp4")
        val process = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-i", "../testdata/media/test-video.mp4",
            "-t", "0.8", "-vf", "scale=64:36,fps=5", "-c:v", "mpeg4", "-an", video.path).start()
        assertEquals(0, process.waitFor())
        val uri = Uri.fromFile(video).toString()
        val xml = """<scene title="x" width="64" height="36" totalTime="800" fps="10" bgcolor="#ff000000"><media uri="$uri" type="video/mp4" width="64" height="36" duration="800000" fps="5"/><shape id="1" startTime="0" endTime="800" fillType="media" fillVideo="$uri" outTime="800" mediaFillMode="stretch" s=".rect"><transform><location value="32,18,0"/></transform><property name="size" type="vec2" value="32,18"/></shape></scene>"""
        val engine = EngineThread(0xff000000.toInt(), context)
        try {
            val plain = engine.exportSession(xml, Smoothing(), 10) { still(100, 64, 36) }!!
            for (setting in listOf(Smoothing(FrameInterpolation.Blend), Smoothing(FrameInterpolation.Flow), Smoothing(blur = MotionBlur.High))) {
                val smooth = engine.exportSession(xml, setting, 10) { still(100, 64, 36) }!!
                assertTrue("$setting", !plain.contentEquals(smooth))
            }
        } finally { engine.close() }
    }

    @Test fun smoothingKeepsHigherRateSourceFrames() = runBlocking {
        val video = out.resolve("thirty.mp4")
        val process = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-i", "../testdata/media/test-video.mp4",
            "-t", "0.5", "-vf", "scale=64:36", "-c:v", "mpeg4", "-an", video.path).start()
        assertEquals(0, process.waitFor())
        val uri = Uri.fromFile(video).toString()
        val xml = """<scene title="x" width="64" height="36" totalTime="500" fps="10" bgcolor="#ff000000"><media uri="$uri" type="video/mp4" width="64" height="36" duration="500000" fps="30"/><shape id="1" startTime="0" endTime="500" fillType="media" fillVideo="$uri" outTime="500" mediaFillMode="stretch" s=".rect"><transform><location value="32,18,0"/></transform><property name="size" type="vec2" value="32,18"/></shape></scene>"""
        val engine = EngineThread(0xff000000.toInt(), context)
        try {
            val plain = engine.exportSession(xml, Smoothing(), 30) { still(67, 64, 36) }!!
            val smooth = engine.exportSession(xml, Smoothing(FrameInterpolation.Blend), 30) { still(67, 64, 36) }!!
            assertTrue(!plain.contentEquals(smooth))
        } finally { engine.close() }
    }

    @Test fun nestedVideoIsDrawnAndSmoothed() = runBlocking {
        val video = out.resolve("nested.mp4")
        val process = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-i", "../testdata/media/test-video.mp4",
            "-t", "0.8", "-vf", "scale=64:36,fps=5", "-c:v", "mpeg4", "-an", video.path).start()
        assertEquals(0, process.waitFor())
        val uri = Uri.fromFile(video).toString()
        val xml = """<scene title="x" width="64" height="36" totalTime="800" fps="10" bgcolor="#ff000000"><media uri="$uri" type="video/mp4" width="64" height="36" duration="800000" fps="5"/><embedScene id="2" startTime="0" endTime="800"><transform><location value="32,18,0"/></transform><scene width="64" height="36" totalTime="800" fps="10" retime="off" bgcolor="#00000000"><shape id="1" startTime="0" endTime="800" fillType="media" fillVideo="$uri" outTime="800" mediaFillMode="stretch" s=".rect"><transform><location value="32,18,0"/></transform><property name="size" type="vec2" value="32,18"/></shape></scene></embedScene></scene>"""
        val engine = EngineThread(0xff000000.toInt(), context)
        try {
            val plain = engine.exportSession(xml, Smoothing(), 10) { still(100, 64, 36) }!!
            val blend = engine.exportSession(xml, Smoothing(FrameInterpolation.Blend), 10) { still(100, 64, 36) }!!
            assertTrue(plain.indices.step(4).any { (plain[it].toInt() and 255) > 10 })
            assertTrue(!plain.contentEquals(blend))
        } finally { engine.close() }
    }

    @Test fun variableRateFramesAreDecodedAtTheirTimestamps() = runBlocking {
        val source = File("../testdata/media/test-video.mp4").absoluteFile
        val video = out.resolve("variable.mkv")
        val process = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-i", source.path, "-vf",
            "select=eq(n\\,0)+eq(n\\,3)+eq(n\\,12),scale=64:36", "-fps_mode", "vfr", "-c:v", "ffv1", video.path).start()
        assertEquals(0, process.waitFor())
        val uri = Uri.fromFile(video).toString()
        val timestamps = VideoSources.timestamps(context, uri)
        assertEquals(3, timestamps.size)
        assertTrue(timestamps.variableRate())
        FfmpegVideoSource(video, DeskMedia.ffmpeg, DeskMedia.ffprobe).use { reader ->
            val first = reader.frameExact(timestamps[0])!!.rgba
            val second = reader.frameExact(timestamps[1])!!.rgba
            val third = reader.frameExact(timestamps[2])!!.rgba
            assertTrue(!first.contentEquals(second) && !second.contentEquals(third))
        }
        val xml = """<scene title="x" width="64" height="36" totalTime="500" fps="20" bgcolor="#ff000000"><media uri="$uri" type="video/mp4" width="64" height="36" duration="500000" fps="6"/><shape id="1" startTime="0" endTime="500" fillType="media" fillVideo="$uri" outTime="500" mediaFillMode="stretch" s=".rect"><transform><location value="32,18,0"/></transform><property name="size" type="vec2" value="32,18"/></shape></scene>"""
        val engine = EngineThread(0xff000000.toInt(), context)
        try {
            val plain = engine.exportSession(xml, Smoothing(), 20) { still(250, 64, 36) }!!
            val blend = engine.exportSession(xml, Smoothing(FrameInterpolation.Blend), 20) { still(250, 64, 36) }!!
            assertTrue(!plain.contentEquals(blend))
        } finally { engine.close() }
    }

    @Test fun highRateVideoKeepsItsFrameRate() {
        val video = out.resolve("high-rate.mkv")
        val process = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=64x48:rate=240",
            "-frames:v", "24", "-c:v", "ffv1", video.path).start()
        assertEquals(0, process.waitFor())
        FfmpegVideoSource(video, DeskMedia.ffmpeg, DeskMedia.ffprobe).use { reader ->
            assertTrue(reader.info.fps > 200)
            val first = reader.frame(0)!!.rgba.copyOf()
            val later = reader.frame(25)!!.rgba.copyOf()
            assertTrue(!first.contentEquals(later))
        }
    }

    @Test fun datamoshChangesVideoAcrossFrames() = runBlocking {
        val source = File("../testdata/media/test-video.mp4").absoluteFile
        val uri = Uri.fromFile(source).toString()
        val prefix = """<scene title="x" width="64" height="36" totalTime="1000" fps="10" bgcolor="#ff000000"><media uri="$uri" type="video/mp4" width="1280" height="720" duration="5000000" fps="30"/><shape id="4" startTime="0" endTime="1000" fillType="media" fillVideo="$uri" outTime="1000" mediaFillMode="stretch" s=".rect"><transform><location value="32,18,0"/></transform><property name="size" type="vec2" value="32,18"/>"""
        val effect = """<effect id="absent.datamosh"><property name="start" type="float" value="0"/><property name="end" type="float" value="1"/><property name="decay" type="float" value="0"/></effect>"""
        val engine = EngineThread(0xff000000.toInt(), context)
        try {
            val plain = engine.exportSession(prefix + "</shape></scene>") { still(500, 64, 36) }!!
            val altered = engine.exportSession(prefix + effect + "</shape></scene>") { still(500, 64, 36) }!!
            assertTrue(!plain.contentEquals(altered))
        } finally { engine.close() }
    }

    @Test fun trackerFollowsDecodedMovingPatch() = runBlocking {
        repeat(5) { frame ->
            val image = BufferedImage(128, 96, BufferedImage.TYPE_INT_RGB)
            for (y in 20 until 76) for (x in 20 until 76) {
                val v = if ((x * 13 + y * 17 + (x * y) % 31) % 19 < 9) 0xffffff else 0x202020
                image.setRGB(x + frame * 4, y, v)
            }
            ImageIO.write(image, "png", out.resolve("track-$frame.png"))
        }
        val video = out.resolve("track.mkv")
        val process = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-framerate", "5", "-i", out.resolve("track-%d.png").path,
            "-c:v", "ffv1", video.path).start()
        assertEquals(0, process.waitFor())
        FrameReader(context, Uri.fromFile(video).toString()).use { reader ->
            MotionTracker(listOf(Pt(48f, 48f))).use { tracker ->
                var end: TrackResult? = null
                assertEquals(5, reader.read(reader.timestamps.toList()) { _, frame -> end = tracker.track(frame.luma(1)) })
                assertTrue(end != null && !end!!.lost)
                assertTrue("tracked ${end!!.points[0].x}", kotlin.math.abs(end!!.points[0].x - 64f) < 3f)
            }
        }
    }

    @Test fun transparentGifProducesAnimatedColourAndMatte() = runBlocking {
        val file = out.resolve("transparent.gif")
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        ImageIO.createImageOutputStream(file).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            repeat(3) { frame ->
                val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB)
                for (y in 12 until 36) for (x in 0 until 16) image.setRGB(x + 8 + frame * 16, y, 0xffff3300.toInt())
                val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null)
                val root = metadata.getAsTree(metadata.nativeMetadataFormatName) as IIOMetadataNode
                (root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode).apply {
                    setAttribute("delayTime", "10")
                    setAttribute("disposalMethod", "restoreToBackgroundColor")
                }
                metadata.setFromTree(metadata.nativeMetadataFormatName, root)
                writer.writeToSequence(IIOImage(image, null, metadata), null)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
        val result = gifVideo(context, Uri.fromFile(file))!!
        try {
            assertNotNull(result.matte)
            val first = DeskMedia.frame(result.files[0], 0, 64)!!
            val second = DeskMedia.frame(result.files[0], 200, 64)!!
            assertTrue(first.getRGB(16, 24) != second.getRGB(16, 24))
            val mask = DeskMedia.frame(result.files[1], 0, 64)!!
            assertTrue((mask.getRGB(16, 24) and 255) > 200)
            assertTrue((mask.getRGB(60, 0) and 255) < 30)
        } finally { result.files.forEach { it.delete() } }
    }
}
