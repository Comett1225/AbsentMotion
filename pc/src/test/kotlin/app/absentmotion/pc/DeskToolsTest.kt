package app.absentmotion.pc

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskScale
import app.absentmotion.tools.ExtractChoice
import app.absentmotion.tools.GifChoice
import app.absentmotion.tools.MAX_GIF_US
import app.absentmotion.tools.TrimRange
import app.absentmotion.tools.extractAudio
import app.absentmotion.tools.fullRange
import app.absentmotion.tools.gifSizeEstimate
import app.absentmotion.tools.keyframeTimes
import app.absentmotion.tools.makeGif
import app.absentmotion.tools.planAudio
import app.absentmotion.tools.planGif
import app.absentmotion.tools.planTrim
import app.absentmotion.tools.readPicked
import app.absentmotion.tools.saveToGallery
import app.absentmotion.tools.shareCompressed
import app.absentmotion.tools.trimVideo
import app.absentmotion.ui.home.ExtractAudioScreen
import app.absentmotion.ui.home.GifScreen
import app.absentmotion.ui.home.TrimScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.abs

@OptIn(ExperimentalTestApi::class)
class DeskToolsTest {
    private val video = File("../testdata/media/test-video.mp4").canonicalFile
    private val shots = File("build/ui-shots/tools").also { it.mkdirs() }

    private fun prepare(name: String) {
        System.setProperty("am.data", File("build/tools-$name-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("user.home", File("build/tools-$name-home").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("am.exports", File("build/tools-$name-exports").also { it.deleteRecursively() }.absolutePath)
        DeskScale.set(100)
    }

    @org.junit.After fun forgetThePick() { System.clearProperty("am.pick") }

    private fun near(want: Double, got: Number, delta: Double) = org.junit.Assert.assertEquals(want, got.toDouble(), delta)

    private fun probe(file: File): JSONObject {
        val process = ProcessBuilder(DeskMedia.ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", file.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val json = JSONObject(process.inputStream.bufferedReader().readText())
        process.waitFor()
        return json
    }

    private fun JSONObject.stream(type: String): JSONObject? = getJSONArray("streams").let { list -> (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.getString("codec_type") == type } }

    private fun JSONObject.seconds(): Double = getJSONObject("format").getString("duration").toDouble()

    private fun counted(file: File): Int {
        val process = ProcessBuilder(DeskMedia.ffprobe, "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", file.path)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return process.inputStream.bufferedReader().readText().trim().toInt().also { process.waitFor() }
    }

    private fun frameAt(file: File, index: Int): BufferedImage {
        val process = ProcessBuilder(DeskMedia.ffmpeg, "-v", "error", "-i", file.path, "-vf", "select=eq(n\\,$index)", "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return ImageIO.read(process.inputStream.readBytes().inputStream()).also { process.waitFor() }
    }

    private fun counter(image: BufferedImage): Int {
        var value = 0
        for (bit in 0 until 10) {
            val x = ((90 + 120 * bit) * image.width / 1280.0).toInt()
            val y = (50 * image.height / 720.0).toInt()
            val p = image.getRGB(x, y)
            val light = ((p shr 16 and 255) + (p shr 8 and 255) + (p and 255)) / 3 > 128
            if (light) value = value or (1 shl bit)
        }
        return value
    }

    @Test fun soundIsExtractedAsM4aAndMp3() = runBlocking {
        prepare("sound")
        val context = android.content.Desk.context
        val picked = readPicked(context, Uri.fromFile(video))!!
        assertTrue(picked.source.audio && picked.source.audioAac && picked.source.video)
        val seen = mutableListOf<Float>()
        val copy = extractAudio(context, picked, planAudio(picked.source, ExtractChoice(false, 0))) { seen += it }
        val copied = probe(copy.file)
        assertEquals("audio/mp4", copy.mime)
        assertTrue(copy.file.name.endsWith("-audio.m4a"))
        assertEquals("aac", copied.stream("audio")!!.getString("codec_name"))
        assertEquals(null, copied.stream("video"))
        assertEquals(5.0, copied.seconds(), 0.1)
        val again = extractAudio(context, picked, planAudio(picked.source, ExtractChoice(false, 192))) {}
        val encoded = probe(again.file)
        assertEquals("aac", encoded.stream("audio")!!.getString("codec_name"))
        assertEquals(5.0, encoded.seconds(), 0.15)
        val lowest = if (app.absentmotion.export.DeskEncoders.audio(app.absentmotion.export.AudioCodec.Aac, 192).firstOrNull() == "aac") 1_000 else 150_000
        assertTrue("bit rate ${encoded.stream("audio")!!.optString("bit_rate")}", encoded.stream("audio")!!.getString("bit_rate").toInt() in lowest..230_000)
        val mp3 = extractAudio(context, picked, planAudio(picked.source, ExtractChoice(true, 128))) {}
        val sound = probe(mp3.file)
        assertEquals("audio/mpeg", mp3.mime)
        assertTrue(mp3.file.name.endsWith(".mp3"))
        assertEquals("mp3", sound.stream("audio")!!.getString("codec_name"))
        assertEquals(5.0, sound.seconds(), 0.2)
        assertTrue(mp3.file.length() in 60_000..100_000)
        assertEquals("Music/AbsentMotion", saveToGallery(context, mp3))
        assertTrue(File("build/tools-sound-exports/Music/AbsentMotion", mp3.file.name).length() == mp3.file.length())
        shareCompressed(context, mp3)
        println("SOUND copy=${copy.file.length()} aac=${again.file.length()} mp3=${mp3.file.length()} progress=${seen.size}")
    }

    @Test fun aPictureOrASilentFileGivesNoAudio() = runBlocking {
        prepare("nosound")
        val context = android.content.Desk.context
        val silent = File("build/tools-nosound-silent.mp4").also { it.delete() }
        ProcessBuilder(DeskMedia.ffmpeg, "-v", "error", "-y", "-i", video.path, "-an", "-c:v", "copy", silent.path).start().waitFor()
        val picked = readPicked(context, Uri.fromFile(silent))!!
        assertFalse(picked.source.audio)
        val failed = runCatching { extractAudio(context, picked, planAudio(picked.source, ExtractChoice())) {} }.exceptionOrNull()
        assertNotNull(failed)
        assertEquals("This video has no sound.", failed!!.message)
    }

    @Test fun gifIsMadeFromTheWholeVideo() = runBlocking {
        prepare("gif")
        val context = android.content.Desk.context
        val picked = readPicked(context, Uri.fromFile(video))!!
        val job = planGif(picked.source, picked.rotation, GifChoice(480, 15, true), fullRange(picked.source.durationUs, MAX_GIF_US))
        assertEquals(listOf(480, 270, 15, 75), listOf(job.width, job.height, job.fps, job.frames))
        val seen = mutableListOf<Float>()
        val made = makeGif(context, picked, job) { seen += it }
        assertEquals("image/gif", made.mime)
        assertEquals("test-video.gif", made.file.name)
        assertEquals(75, made.frames)
        assertTrue(seen.isNotEmpty() && seen.last() <= 0.99f && seen.zipWithNext().all { it.first <= it.second })
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        ImageIO.createImageInputStream(made.file).use { input ->
            reader.input = input
            assertEquals(75, reader.getNumImages(true))
            assertEquals(480 to 270, reader.getWidth(0) to reader.getHeight(0))
            val head = String(made.file.readBytes(), 0, 64, Charsets.ISO_8859_1)
            val loop = head.indexOf("NETSCAPE2.0")
            assertTrue("loop block", loop > 0)
            assertEquals(listOf(3, 1, 0, 0, 0), (0 until 5).map { made.file.readBytes()[loop + 11 + it].toInt() })
            val delays = (0 until 75).map { i ->
                val tree = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0")
                generateSequence(tree.firstChild) { it.nextSibling }.first { it.nodeName == "GraphicControlExtension" }.let { (it as IIOMetadataNode).getAttribute("delayTime").toInt() }
            }
            near(500.0, delays.sum(), 8.0)
            val first = reader.read(0)
            val want = frameAt(video, 0)
            var diff = 0L
            for (y in 0 until 270 step 3) for (x in 0 until 480 step 3) {
                val a = first.getRGB(x, y)
                val b = want.getRGB(x * want.width / 480, y * want.height / 270)
                diff += abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))
            }
            val mean = diff / (160.0 * 90 * 3)
            assertTrue("first frame differs by $mean", mean < 12)
            for (k in listOf(0, 10, 37, 74)) assertEquals("gif frame $k", 2.0 * k, counter(reader.read(k)).toDouble(), 1.0)
            ImageIO.write(reader.read(37), "png", File(shots, "gif-frame-37.png"))
        }
        val estimate = gifSizeEstimate(context, picked, job)
        assertNotNull(estimate)
        val real = made.file.length()
        assertTrue("estimate $estimate real $real", estimate!! in (real * 0.4).toLong()..(real * 2.5).toLong())
        println("GIF real=$real estimate=$estimate")
    }

    @Test fun gifCanPlayOnceAndUseAPart() = runBlocking {
        prepare("gifpart")
        val context = android.content.Desk.context
        val picked = readPicked(context, Uri.fromFile(video))!!
        val job = planGif(picked.source, picked.rotation, GifChoice(320, 10, false), TrimRange(1_000_000, 3_000_000))
        assertEquals(listOf(320, 180, 10, 20), listOf(job.width, job.height, job.fps, job.frames))
        val made = makeGif(context, picked, job) {}
        val bytes = made.file.readBytes()
        assertTrue(String(bytes, 0, 6) == "GIF89a")
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        ImageIO.createImageInputStream(made.file).use { input ->
            reader.input = input
            assertEquals(20, reader.getNumImages(true))
            assertEquals(320 to 180, reader.getWidth(0) to reader.getHeight(0))
            assertEquals(30.0, counter(reader.read(0)).toDouble(), 1.0)
            assertEquals(30.0 + 3 * 19, counter(reader.read(19)).toDouble(), 1.0)
        }
    }

    @Test fun gifAndTrimFollowThePhoneRotation() = runBlocking {
        prepare("rotated")
        val context = android.content.Desk.context
        val turned = File("build/tools-rotated-source.mp4").also { it.delete() }
        assertEquals(0, ProcessBuilder(DeskMedia.ffmpeg, "-v", "error", "-y", "-display_rotation:v", "90", "-i", video.path, "-c", "copy", turned.path).start().waitFor())
        val picked = readPicked(context, Uri.fromFile(turned))!!
        assertEquals(270, picked.rotation)
        val job = planGif(picked.source, picked.rotation, GifChoice(480, 10, true), TrimRange(1_000_000, 2_000_000))
        assertEquals(270 to 480, job.width to job.height)
        val gif = makeGif(context, picked, job) {}
        val got = ImageIO.read(gif.file)
        assertEquals(270 to 480, got.width to got.height)
        val want = frameAt(turned, 30)
        assertEquals(720 to 1280, want.width to want.height)
        var diff = 0L
        for (y in 0 until 480 step 3) for (x in 0 until 270 step 3) {
            val a = got.getRGB(x, y)
            val b = want.getRGB(x * want.width / 270, y * want.height / 480)
            diff += abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))
        }
        assertTrue("orientation differs by ${diff / (90.0 * 160 * 3)}", diff / (90.0 * 160 * 3) < 25)
        val keys = keyframeTimes(context, picked.uri)
        val copy = trimVideo(context, picked, planTrim(TrimRange(1_000_000, 3_000_000), keys, true)) {}
        val facts = probe(copy.file)
        assertEquals(2.0, facts.seconds(), 0.15)
        val side = facts.stream("video")!!.optJSONArray("side_data_list")
        assertTrue("rotation kept: $side", side != null && side.toString().contains("90"))
        val exact = trimVideo(context, picked, planTrim(TrimRange(1_500_000, 3_000_000), keys, false)) {}
        val exactSide = probe(exact.file).stream("video")!!
        assertEquals(1280 to 720, exactSide.getInt("width") to exactSide.getInt("height"))
        assertTrue("rotation kept after re-encode: ${exactSide.optJSONArray("side_data_list")}", exactSide.optJSONArray("side_data_list").toString().contains("90"))
    }

    @Test fun anExactTrimBetweenTwoFramesKeepsOne() = runBlocking {
        prepare("onefps")
        val context = android.content.Desk.context
        val slow = File("build/tools-one-fps.mp4").also { it.delete() }
        assertEquals(0, ProcessBuilder(DeskMedia.ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=1", "-f", "lavfi", "-i", "sine=frequency=440",
            "-t", "5", "-pix_fmt", "yuv420p", slow.path).start().waitFor())
        val picked = readPicked(context, Uri.fromFile(slow))!!
        val made = trimVideo(context, picked, planTrim(TrimRange(1_200_000, 1_500_000), LongArray(0), false)) {}
        assertEquals(1, counted(made.file))
        assertEquals(0.3, probe(made.file).stream("audio")!!.getString("duration").toDouble(), 0.05)
    }

    @Test fun gifCancelStopsAndLeavesNothing() = runBlocking {
        prepare("gifcancel")
        val context = android.content.Desk.context
        val picked = readPicked(context, Uri.fromFile(video))!!
        val job = planGif(picked.source, picked.rotation, GifChoice(720, 24, true), fullRange(picked.source.durationUs, MAX_GIF_US))
        val work = async(Dispatchers.Default) { makeGif(context, picked, job) {} }
        delay(700)
        work.cancel()
        val outcome = runCatching { work.await() }.exceptionOrNull()
        assertTrue("cancelled: $outcome", outcome is CancellationException)
        assertFalse(File(app.absentmotion.tools.compressFolder(context), "test-video.gif").exists())
    }

    @Test fun keyframesAreListed() = runBlocking {
        prepare("keys")
        val keys = keyframeTimes(android.content.Desk.context, Uri.fromFile(video))
        assertEquals(listOf(0L, 1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L), keys.toList().take(5).map { Math.round(it / 1000.0) * 1000 })
        assertEquals(6, keys.size)
        near(4_466_667.0, keys.last(), 2_000.0)
    }

    @Test fun trimCopiesBetweenKeyframesWithoutReEncoding() = runBlocking {
        prepare("trimcopy")
        val context = android.content.Desk.context
        val picked = readPicked(context, Uri.fromFile(video))!!
        val keys = keyframeTimes(context, picked.uri)
        val job = planTrim(TrimRange(1_000_000, 4_000_000), keys, true)
        assertTrue(job.copy)
        val made = trimVideo(context, picked, job) {}
        assertEquals("video/mp4", made.mime)
        assertEquals("test-video-trimmed.mp4", made.file.name)
        val facts = probe(made.file)
        assertEquals("h264", facts.stream("video")!!.getString("codec_name"))
        assertEquals("aac", facts.stream("audio")!!.getString("codec_name"))
        assertEquals(1280 to 720, facts.stream("video")!!.getInt("width") to facts.stream("video")!!.getInt("height"))
        assertEquals(3.0, facts.seconds(), 0.12)
        near(90.0, counted(made.file), 2.0)
        assertEquals(30, counter(frameAt(made.file, 0)))
        assertEquals(119.0, counter(frameAt(made.file, counted(made.file) - 1)).toDouble(), 1.0)
        println("TRIM copy size=${made.file.length()} frames=${counted(made.file)}")
    }

    @Test fun trimReEncodesExactlyBetweenAnyTwoTimes() = runBlocking {
        prepare("trimexact")
        val context = android.content.Desk.context
        val picked = readPicked(context, Uri.fromFile(video))!!
        val keys = keyframeTimes(context, picked.uri)
        val job = planTrim(TrimRange(1_500_000, 3_500_000), keys, true)
        assertFalse("1.5 s is not on a keyframe", job.copy)
        val seen = mutableListOf<Float>()
        val made = trimVideo(context, picked, job) { seen += it }
        val facts = probe(made.file)
        assertEquals("h264", facts.stream("video")!!.getString("codec_name"))
        assertEquals("aac", facts.stream("audio")!!.getString("codec_name"))
        assertEquals(1280 to 720, facts.stream("video")!!.getInt("width") to facts.stream("video")!!.getInt("height"))
        assertEquals(2.0, facts.seconds(), 0.15)
        near(60.0, counted(made.file), 3.0)
        assertEquals(45.0, counter(frameAt(made.file, 0)).toDouble(), 1.0)
        assertEquals(104.0, counter(frameAt(made.file, counted(made.file) - 1)).toDouble(), 2.0)
        assertTrue(seen.isNotEmpty() && seen.all { it in 0f..1f })
        val whole = trimVideo(context, picked, planTrim(TrimRange(0, picked.source.durationUs), keys, false)) {}
        assertEquals(5.0, probe(whole.file).seconds(), 0.15)
        println("TRIM exact size=${made.file.length()} frames=${counted(made.file)} whole=${whole.file.length()}")
    }

    @Test fun trimCancelLeavesNothing() = runBlocking {
        prepare("trimcancel")
        val context = android.content.Desk.context
        val picked = readPicked(context, Uri.fromFile(video))!!
        val work = async(Dispatchers.Default) { trimVideo(context, picked, planTrim(TrimRange(500_000, 4_500_000), LongArray(0), false)) {} }
        delay(150)
        work.cancel()
        val outcome = runCatching { work.await() }
        assertTrue(outcome.isFailure)
        withContext(Dispatchers.IO) { delay(300) }
        assertFalse(File(app.absentmotion.tools.compressFolder(context), "test-video-trimmed.mp4").exists())
    }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    private fun ComposeUiTest.settle(ms: Int = 400) {
        repeat(ms / 100) { mainClock.advanceTimeBy(100); Thread.sleep(40) }
    }

    private fun ComposeUiTest.openTool(name: String, picked: Boolean) {
        val state = DeskAppState()
        setContent { DeskApp(state) }
        settle(800)
        onNodeWithText("Tools").performClick()
        settle(600)
        onNodeWithText(name).performClick()
        settle(800)
        if (!picked) return
        System.setProperty("am.pick", video.path)
        onNodeWithText("Choose a video").performClick()
        waitUntil(timeoutMillis = 30_000) { settle(200); onAllNodesWithText("test-video.mp4").fetchSemanticsNodes().isNotEmpty() }
        settle(2500)
    }

    private fun ComposeUiTest.press(text: String) = onAllNodesWithText(text).filter(hasClickAction()).onLast().performClick()

    private fun ComposeUiTest.until(text: String, seconds: Int = 60) = waitUntil(timeoutMillis = seconds * 1000L) { settle(200); onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun toolsTabListsTheNewTools() {
        prepare("tab")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val state = DeskAppState()
            setContent { DeskApp(state) }
            settle(800)
            onNodeWithText("Tools").performClick()
            settle(600)
            for (name in listOf("Compress", "Trim", "Extract Audio", "Make a GIF")) onNodeWithText(name).assertExists()
            shot("tools-tab-all")
            for (name in listOf("Trim", "Extract Audio", "Make a GIF")) {
                onNodeWithText(name).performClick()
                settle(800)
                onNodeWithText("Choose a video").assertExists()
                onNodeWithText("Choose a video first.").assertExists()
                shot("tools-" + name.lowercase().replace(' ', '-') + "-empty")
                app.absentmotion.desk.DeskBack.back()
                settle(600)
            }
        }
    }

    @Test fun trimScreenShowsTheFilmstripAndFollowsTheButtons() {
        prepare("trimui")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            openTool("Trim", true)
            onNodeWithText("Fast").assertExists()
            onNodeWithText("Exact").assertExists()
            onAllNodesWithText("0:00.0").assertCountEquals(2)
            onNodeWithText("Length 0:05.0").assertExists()
            shot("trim-ready")
            onAllNodesWithContentDescription("Later")[0].performClick()
            settle(300)
            onNodeWithText("Length 0:04.0").assertExists()
            onAllNodesWithContentDescription("Earlier")[1].performClick()
            settle(300)
            onNodeWithText("Length 0:03.9").assertExists()
            onNodeWithText("Exact").performScrollTo().performClick()
            settle(300)
            onAllNodesWithContentDescription("Later")[0].performClick()
            settle(300)
            shot("trim-exact")
            onNodeWithText("Length 0:03.8").assertExists()
        }
    }

    private fun ComposeUiTest.seconds(label: String): Double {
        val text = onAllNodesWithText(label, substring = true).fetchSemanticsNodes().first().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        val clock = text.substringAfterLast(' ').split(':').map { it.toDouble() }
        return clock.fold(0.0) { total, part -> total * 60 + part }
    }

    @Test fun theHandlesFollowTheMouse() {
        prepare("drag")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            openTool("Trim", true)
            onNodeWithText("Exact").performScrollTo().performClick()
            settle(300)
            assertEquals(5.0, seconds("Length"), 0.01)
            onNodeWithTag("tool-strip").performMouseInput {
                moveTo(androidx.compose.ui.geometry.Offset(5f, height / 2f))
                press()
                moveTo(androidx.compose.ui.geometry.Offset(width * 0.4f, height / 2f))
                release()
            }
            settle(300)
            val afterStart = seconds("Length")
            assertTrue("start moved: length $afterStart", afterStart in 2.8..3.3)
            onNodeWithTag("tool-strip").performMouseInput {
                moveTo(androidx.compose.ui.geometry.Offset(width - 5f, height / 2f))
                press()
                moveTo(androidx.compose.ui.geometry.Offset(width * 0.8f, height / 2f))
                release()
            }
            settle(300)
            val afterEnd = seconds("Length")
            assertTrue("end moved: length $afterEnd", afterEnd in 1.7..2.3)
            shot("trim-dragged")
            onNodeWithTag("tool-strip").performMouseInput {
                moveTo(androidx.compose.ui.geometry.Offset(width * 0.95f, height / 2f))
                press()
                release()
            }
            settle(300)
            assertTrue("tap moved the end back: ${seconds("Length")}", seconds("Length") > afterEnd + 0.5)
        }
    }

    @Test fun trimButtonMakesAFileAndOffersSaveAndShare() {
        prepare("trimrun")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            openTool("Trim", true)
            onAllNodesWithContentDescription("Later")[0].performClick()
            settle(300)
            press("Trim")
            until("Save")
            settle(1500)
            shot("trim-done")
            onNodeWithText("Show in", substring = true).assertExists()
            onNodeWithText("Save").performClick()
            until("Saved", 20)
            val saved = File("build/tools-trimrun-exports").walkTopDown().filter { it.isFile && it.name.endsWith("-trimmed.mp4") }.toList()
            assertEquals(1, saved.size)
            near(4.0, probe(saved.first()).seconds(), 0.15)
            assertEquals("h264", probe(saved.first()).stream("video")!!.getString("codec_name"))
        }
    }

    @Test fun extractScreenChoosesFormatAndQuality() {
        prepare("extractui")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            openTool("Extract Audio", true)
            onNodeWithText("Original").assertExists()
            onNodeWithText("M4A").assertExists()
            shot("extract-ready")
            onNodeWithText("MP3").performClick()
            settle(500)
            onAllNodesWithText("Original").assertCountEquals(0)
            onNodeWithText("Best").performClick()
            settle(300)
            shot("extract-mp3")
            press("Extract")
            until("Save")
            settle(500)
            shot("extract-done")
            onNodeWithText("test-video-audio.mp3").assertExists()
            onNodeWithText("Save").performClick()
            until("Saved", 20)
            val saved = File("build/tools-extractui-exports").walkTopDown().filter { it.isFile && it.name.endsWith(".mp3") }.toList()
            assertEquals(1, saved.size)
            assertEquals("Music", saved.first().parentFile.parentFile.name)
        }
    }

    @Test fun gifScreenShowsAnEstimateAndMakesTheGif() {
        prepare("gifui")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            openTool("Make a GIF", true)
            waitUntil(timeoutMillis = 30_000) { settle(300); onAllNodesWithText("Checking the size…").fetchSemanticsNodes().isEmpty() }
            onNodeWithText("About", substring = true).assertExists()
            shot("gif-ready")
            onNodeWithText("320 px").performClick()
            settle(300)
            onNodeWithText("Once").performClick()
            settle(300)
            press("Make GIF")
            until("Save")
            settle(2500)
            shot("gif-done")
            val made = File(app.absentmotion.tools.compressFolder(android.content.Desk.context), "test-video.gif")
            assertEquals(320, javax.imageio.ImageIO.read(made).width)
        }
    }

    @Test fun aPictureIsRefusedPolitely() {
        prepare("picture")
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val state = DeskAppState()
            setContent { DeskApp(state) }
            settle(800)
            onNodeWithText("Tools").performClick()
            settle(600)
            onNodeWithText("Trim").performClick()
            settle(800)
            System.setProperty("am.pick", File("../testdata/media/test-image.jpg").canonicalPath)
            onNodeWithText("Choose a video").performClick()
            settle(1500)
            onNodeWithText("That is a picture. Choose a video.").assertExists()
            shot("trim-picture")
        }
    }
}
