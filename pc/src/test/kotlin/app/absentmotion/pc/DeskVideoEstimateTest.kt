package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.ai.AiSmooth
import app.absentmotion.audio.OfflineMix
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.Codec
import app.absentmotion.export.Container
import app.absentmotion.export.DeskEncoders
import app.absentmotion.export.ExportDebug
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.MotionBlur
import app.absentmotion.export.SmoothLevel
import app.absentmotion.export.audioCodecsFor
import app.absentmotion.export.estimateBytes
import app.absentmotion.export.exportVideo
import app.absentmotion.export.measureVideoBytes
import app.absentmotion.export.videoCodecsFor
import app.absentmotion.export.videoSizeSample
import app.absentmotion.model.Scene
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class DeskVideoEstimateTest {
    private val repo = File("..").canonicalFile

    private fun scene(ms: Int, moving: Boolean): Scene {
        var xml = repo.resolve("testdata/scenes/media.xml").readText()
            .replace(Regex("totalTime=\"\\d+\""), "totalTime=\"$ms\"")
            .replace(Regex("endTime=\"4999\""), "endTime=\"$ms\"")
            .replace("\"testdata/", "\"" + repo.resolve("testdata").toURI().toString().replaceFirst("file:/", "file:///") + "/")
        if (moving) {
            val video = Regex("(?s)<shape id=\"8004\".*?</shape>").find(xml)!!.value
            xml = xml.replace(video, (0 until (ms + 1) / 5000).joinToString("\n") { i ->
                video.replace("id=\"8004\"", "id=\"${9000 + i}\"").replace("startTime=\"0\"", "startTime=\"${i * 5000}\"").replace("endTime=\"$ms\"", "endTime=\"${i * 5000 + 4999}\"")
            })
        }
        return Scene.parse(xml)
    }

    @Test fun shortProjectsAreEncodedWholeAndLongOnesFromTheirStartAndSpreadSeconds() {
        assertNull(videoSizeSample(300, 30))
        assertNull(videoSizeSample(240 + 5 * 30, 30))
        val sample = videoSizeSample(900, 30)!!
        assertEquals(240 + 10 * 30, sample.size)
        assertTrue(sample.copyOf(240).contentEquals(IntArray(240) { it }))
        val spread = sample.copyOfRange(240, sample.size)
        assertTrue(spread.toList() == spread.toSortedSet().toList() && spread.first() >= 240 && spread.last() < 900)
        assertTrue(spread.toList().chunked(30).all { it.first() % 30 == 0 && it.zipWithNext().all { (a, b) -> b == a + 1 } })
        assertEquals(240 + 5 * 30, videoSizeSample(450, 30)!!.size)
        assertEquals(240 + 5 * 60, videoSizeSample(60 * 60 * 60 * 3, 60, 1920L * 1080)!!.size)
        assertEquals(240 + 5 * 60, videoSizeSample(60 * 60 * 60, 60, 3840L * 2160)!!.size)
        assertEquals(240 + 11 * 30, videoSizeSample(30 * 60 * 60, 30, 1280L * 720)!!.size)
        assertEquals(250 + 10 * 25, videoSizeSample(25 * 30, 25)!!.size)
    }

    @Test fun theSampledSizeIsCloseToTheExportedSize() = runBlocking {
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        val dir = File("build/video-estimate").also { it.deleteRecursively(); it.mkdirs() }
        val model = File(System.getenv("HOME") ?: "", "Library/Application Support/AbsentMotion/files/models/ai/rife-v4.26-large")
        val linked = Desk.context.filesDir.resolve("models/ai/rife-v4.26-large")
        val lines = ArrayList<String>()
        val off = ArrayList<String>()
        suspend fun check(name: String, scene: Scene, s: ExportSettings, sound: Boolean, tolerance: Double = 0.15) {
            val started = System.nanoTime()
            val estimate = measureVideoBytes(engine, scene, s, dir, sound)
            val measureMs = (System.nanoTime() - started) / 1_000_000
            val file = dir.resolve("full-$name.${s.container.ext}")
            val mix = if (sound) OfflineMix.of(Desk.context, scene, 2, s.audio.effectiveRate) else null
            val exportStarted = System.nanoTime()
            RandomAccessFile(file, "rw").use { exportVideo(engine, scene, scene.toXml(), s, it.fd, mix) {} }
            val exportMs = (System.nanoTime() - exportStarted) / 1_000_000
            val real = file.length()
            file.delete()
            val ratio = estimate.toDouble() / real
            val formula = estimateBytes(ExportKind.Video, scene, s, sound).toDouble() / real
            lines += "ESTIMATE $name: estimate $estimate real $real ratio ${"%.3f".format(ratio)} formula ${"%.3f".format(formula)} measure $measureMs ms export $exportMs ms"
            if (ratio !in 1 - tolerance..1 + tolerance) off += "$name ${"%.3f".format(ratio)}"
        }
        try {
            val moving = scene(14999, moving = true)
            val freeze = scene(14999, moving = false)
            for (container in Container.entries) for (codec in videoCodecsFor(container)) {
                if (DeskEncoders.video(codec).isEmpty()) { lines += "SKIP ${codec.name}-${container.name}: no ${codec.label} encoder on this computer"; continue }
                val s = ExportSettings(1080, 30, codec, container = container).let { it.copy(audio = it.audio.copy(codec = audioCodecsFor(container).last())) }
                check("${codec.name}-${container.name}", moving, s, sound = true)
            }
            for (codec in listOf(Codec.H264, Codec.Hevc)) check("${codec.name}-freeze", freeze, ExportSettings(1080, 30, codec), sound = true)
            ExportDebug.refuseHardware = true
            try {
                for (codec in listOf(Codec.H264, Codec.Hevc)) check("${codec.name}-software", moving, ExportSettings(720, 30, codec), sound = false)
            } finally {
                ExportDebug.refuseHardware = false
            }
            for (method in FrameInterpolation.entries.filter { it != FrameInterpolation.Ai }) {
                check("H264-60-${method.name}", moving, ExportSettings(720, 60, Codec.H264, interpolation = method), sound = false)
                check("H264-60-${method.name}-freeze", freeze, ExportSettings(720, 60, Codec.H264, interpolation = method), sound = false, tolerance = 0.5)
            }
            check("H264-60-MotionBlur", moving, ExportSettings(720, 60, Codec.H264, motionBlur = MotionBlur.Medium), sound = false)
            val ai = ExportSettings(720, 60, Codec.H264, interpolation = FrameInterpolation.Ai, interpolationLevel = SmoothLevel.High)
            check("H264-60-AiNoModel", moving, ai, sound = false)
            if (System.getenv("AM_AI_ESTIMATE") == null) lines += "SKIP H264-60-AiModel: set AM_AI_ESTIMATE=1 to export with the AI Smooth High model too (about 12 minutes)"
            else if (model.resolve("flownet.bin").isFile) {
                linked.parentFile.mkdirs()
                Files.deleteIfExists(linked.toPath())
                Files.createSymbolicLink(linked.toPath(), model.toPath())
                try {
                    if (AiSmooth.installed(Desk.context, SmoothLevel.High)) check("H264-60-AiModel", moving, ai, sound = false)
                    else lines += "SKIP H264-60-AiModel: the AI Smooth High model on this computer is another version"
                } finally { Files.deleteIfExists(linked.toPath()) }
            } else lines += "SKIP H264-60-AiModel: the AI Smooth High model is not installed on this computer"
            check("H264-40s", scene(39999, moving = true), ExportSettings(720, 30, Codec.H264), sound = true)
        } finally {
            lines.forEach(::println)
            File("build/video-estimate-ratios.txt").writeText(lines.joinToString("\n"))
            dir.deleteRecursively()
            engine.close()
        }
        assertTrue("off by more than 15%: $off", off.isEmpty())
    }
}
