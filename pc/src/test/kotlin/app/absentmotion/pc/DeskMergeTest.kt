package app.absentmotion.pc

import android.net.Uri
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.Judge
import app.absentmotion.desk.DeskScale
import app.absentmotion.tools.estimateMerge
import app.absentmotion.tools.mergeVideos
import app.absentmotion.tools.planMerge
import app.absentmotion.tools.readMergeClip
import app.absentmotion.ui.home.MergeScreen
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskMergeTest {
    private val video = File("../testdata/media/test-video.mp4").canonicalFile
    private val context get() = android.content.Desk.context

    private fun prepare(name: String) {
        System.setProperty("am.data", File("build/merge-$name-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("user.home", File("build/merge-$name-home").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("am.exports", File("build/merge-$name-exports").also { it.deleteRecursively() }.absolutePath)
        DeskScale.set(100)
    }

    @org.junit.After fun forget() { System.clearProperty("am.pick") }

    private fun probe(file: File): JSONObject {
        val process = ProcessBuilder(Judge.ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", file.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return JSONObject(process.inputStream.bufferedReader().readText()).also { process.waitFor() }
    }

    private fun JSONObject.stream(type: String): JSONObject? = getJSONArray("streams").let { list -> (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.getString("codec_type") == type } }

    private fun counted(file: File): Int {
        val process = ProcessBuilder(Judge.ffprobe, "-v", "error", "-count_packets", "-select_streams", "v:0", "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", file.path)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return process.inputStream.bufferedReader().readText().trim().toInt().also { process.waitFor() }
    }

    private fun silentSmall(): File = File("build/merge-small.mp4").absoluteFile.also { out ->
        out.delete()
        val process = ProcessBuilder(Judge.ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=480x640:r=24:d=2", "-c:v", "libx264", "-pix_fmt", "yuv420p", out.path)
            .redirectErrorStream(true).start()
        process.inputStream.readBytes()
        assertEquals(0, process.waitFor())
    }

    @Test fun matchingVideosAreJoinedWithoutReEncoding() = runBlocking {
        prepare("copy")
        val clips = listOf(video, video).map { readMergeClip(context, Uri.fromFile(it))!! }
        val job = planMerge(clips.map { it.facts })
        assertTrue(job.copy)
        assertEquals(video.length() * 2, estimateMerge(clips.map { it.facts }, job))
        val made = mergeVideos(context, clips, job) {}
        assertEquals("test-video-merged.mp4", made.file.name)
        val facts = probe(made.file)
        val source = probe(video)
        assertEquals("h264", facts.stream("video")!!.getString("codec_name"))
        assertEquals("aac", facts.stream("audio")!!.getString("codec_name"))
        assertEquals(1280 to 720, facts.stream("video")!!.getInt("width") to facts.stream("video")!!.getInt("height"))
        assertEquals(10.0, facts.getJSONObject("format").getDouble("duration"), 0.15)
        assertEquals(2 * counted(video), counted(made.file))
        val sourceRate = source.stream("video")!!.getString("bit_rate").toDouble()
        val mergedRate = facts.stream("video")!!.getString("bit_rate").toDouble()
        assertEquals(1.0, mergedRate / sourceRate, 0.03)
        assertEquals(1.0, made.file.length().toDouble() / (2 * video.length()), 0.03)
        println("MERGE copy size=${made.file.length()} source=${video.length()} rate=$mergedRate sourceRate=$sourceRate")
    }

    @Test fun differentVideosAreReEncodedAndASilentClipGetsSilence() = runBlocking {
        prepare("encode")
        val small = silentSmall()
        val clips = listOf(video, small).map { readMergeClip(context, Uri.fromFile(it))!! }
        assertFalse(clips[1].picked.source.audio)
        val job = planMerge(clips.map { it.facts })
        assertFalse(job.copy)
        assertEquals(1280 to 720, job.width to job.height)
        assertEquals(30, job.fps)
        val seen = mutableListOf<Float>()
        val made = mergeVideos(context, clips, job) { seen += it }
        val facts = probe(made.file)
        val picture = facts.stream("video")!!
        assertEquals("h264", picture.getString("codec_name"))
        assertEquals(1280 to 720, picture.getInt("width") to picture.getInt("height"))
        assertEquals(7.0, facts.getJSONObject("format").getDouble("duration"), 0.2)
        val sound = facts.stream("audio")!!
        assertEquals("aac", sound.getString("codec_name"))
        assertEquals(7.0, sound.getString("duration").toDouble(), 0.2)
        assertEquals(7.0, picture.getString("duration").toDouble(), 0.2)
        assertTrue(seen.isNotEmpty())
        val turned = mergeVideos(context, clips.reversed(), planMerge(clips.reversed().map { it.facts })) {}
        val first = probe(turned.file).stream("video")!!
        assertEquals(480 to 640, first.getInt("width") to first.getInt("height"))
        println("MERGE encode size=${made.file.length()} estimate=${estimateMerge(clips.map { it.facts }, job)}")
    }

    private fun ComposeUiTest.settle(ms: Int = 400) {
        repeat(ms / 100) { mainClock.advanceTimeBy(100); Thread.sleep(40) }
    }

    private fun ComposeUiTest.until(text: String, seconds: Int = 60) = waitUntil(timeoutMillis = seconds * 1000L) { settle(200); onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun theMergeScreenAddsMovesAndJoinsVideos() {
        prepare("ui")
        val small = silentSmall()
        val shots = File("build/ui-shots/tools").also { it.mkdirs() }
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            setContent { MergeScreen {} }
            settle(600)
            until("Add at least two videos.")
            System.setProperty("am.pick", video.path + File.pathSeparator + small.path)
            onAllNodesWithText("Add videos").onLast().performClick()
            until("merge-small.mp4")
            until("re-encoded")
            ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", shots.resolve("merge-two.png"))
            onAllNodesWithContentDescription("Move down")[0].performClick()
            settle(400)
            onAllNodesWithContentDescription("Remove")[1].performClick()
            settle(400)
            until("Add at least two videos.")
            System.setProperty("am.pick", video.path)
            onAllNodesWithText("Add videos").onLast().performClick()
            waitUntil(timeoutMillis = 30_000) { settle(200); onAllNodesWithText("test-video.mp4").fetchSemanticsNodes().size == 1 && onAllNodesWithText("merge-small.mp4").fetchSemanticsNodes().size == 1 }
            onAllNodesWithText("Merge").filter(hasClickAction()).onLast().performClick()
            until("Save", 120)
            settle(800)
            onAllNodesWithText("Save").onLast().performClick()
            until("Saved", 20)
        }
        val saved = File("build/merge-ui-exports").walkTopDown().filter { it.isFile && it.name.endsWith("-merged.mp4") }.toList()
        assertEquals(1, saved.size)
        assertEquals("merge-small-merged.mp4", saved.first().name)
        assertEquals(7.0, probe(saved.first()).getJSONObject("format").getDouble("duration"), 0.2)
    }
}
