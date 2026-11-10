package app.absentmotion.pc

import app.absentmotion.R
import app.absentmotion.tools.DeskVideoDownloader
import app.absentmotion.tools.DownloadProblem
import app.absentmotion.tools.Plan
import app.absentmotion.tools.SaveMode
import app.absentmotion.tools.qualities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeskDownloaderTest {
    @get:Rule val temp = TemporaryFolder()

    private val info = """{"title":"Sample clip","extractor_key":"Youtube","thumbnail":"http://example.invalid/t.jpg","duration":12.5,"formats":[
        {"format_id":"sb0","ext":"mhtml","vcodec":"images","acodec":"none","protocol":"mhtml"},
        {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":128,"tbr":128,"filesize":1000,"protocol":"https","audio_channels":2},
        {"format_id":"137","ext":"mp4","vcodec":"avc1.640028","acodec":"none","width":1920,"height":1080,"fps":30,"tbr":4000,"filesize":9000,"protocol":"https"},
        {"format_id":"136","ext":"mp4","vcodec":"avc1.4d401f","acodec":"none","width":1280,"height":720,"fps":30,"tbr":2000,"filesize":5000,"protocol":"https"}]}"""

    private fun script(body: String): File = temp.newFile("fake-yt-dlp").also {
        it.writeText("#!/bin/sh\n$body\n")
        it.setExecutable(true)
    }

    private val fake: File by lazy { fakeScript() }

    private fun fakeScript(): File = script(
        """
        case "${'$'}*" in
          *Fail*) echo "ERROR: This video is private" >&2; exit 1;;
          *Slow*) sleep 30; exit 0;;
          *--dump-single-json*) cat <<'JSON'
        ${info.replace("\n", " ")}
        JSON
          exit 0;;
        esac
        prev=""
        for a in "${'$'}@"; do [ "${'$'}prev" = "-o" ] && out="${'$'}a"; prev="${'$'}a"; done
        echo "[download] Destination: one"
        echo "[download]  50.0% of 10.00MiB at 1.00MiB/s ETA 00:05"
        echo "[download] 100% of 10.00MiB in 00:10"
        echo "[download] Destination: two"
        echo "[download]  50.0% of 1.00MiB at 1.00MiB/s ETA 00:01"
        echo "[download] 100% of 1.00MiB in 00:01"
        f=${'$'}(printf %s "${'$'}out" | sed 's/%(ext)s/mp4/')
        echo data > "${'$'}f"
        """.trimIndent(),
    )

    @Before fun unixOnly() = assumeFalse(System.getProperty("os.name").lowercase().startsWith("windows"))

    @Test fun infoIsParsedIntoFormats() = runBlocking {
        val result = DeskVideoDownloader.readInfo(fake, "https://example.invalid/watch")
        assertEquals("Sample clip", result.title)
        assertEquals("Youtube", result.site)
        assertEquals(listOf("http://example.invalid/t.jpg"), result.thumbnails)
        val item = result.items.single()
        assertEquals(3, item.formats.size)
        assertEquals(listOf(1080, 720), qualities(item, SaveMode.Both).map { it.side })
    }

    @Test fun downloadReportsProgressAndReturnsTheFile() = runBlocking {
        val item = DeskVideoDownloader.readInfo(fake, "u").items.single()
        val plan: Plan = qualities(item, SaveMode.Both).first().plan
        val seen = ArrayList<Float>()
        val file = DeskVideoDownloader.fetchTo(fake, "https://example.invalid/watch", 0, plan, SaveMode.Both, temp.newFolder("out"), "ffmpeg") { seen += it }
        assertEquals("item.mp4", file.name)
        assertTrue(file.length() > 0)
        assertEquals(1f, seen.last(), 0f)
        assertEquals(seen.sorted(), seen)
        assertTrue(seen.any { it in 0.2f..0.3f })
    }

    @Test fun errorTextBecomesAFriendlyProblem() {
        val e = runCatching { runBlocking { DeskVideoDownloader.readInfo(fake, "https://example.invalid/Fail") } }.exceptionOrNull() as DownloadProblem
        assertEquals(R.string.downloader_error_private, e.text)
    }

    @Test fun cancelStopsTheProcess() = runBlocking {
        val exe = fake
        val start = System.currentTimeMillis()
        val job = async(Dispatchers.Default) { DeskVideoDownloader.readInfo(exe, "https://example.invalid/Slow") }
        delay(600)
        job.cancel()
        val outcome = runCatching { job.await() }.exceptionOrNull()
        assertTrue(outcome.toString(), outcome is CancellationException)
        assertTrue("took ${System.currentTimeMillis() - start}", System.currentTimeMillis() - start < 10_000)
    }

    @Test fun missingOrUnstartableProgramIsReportedAsBlocked() {
        val missing = runCatching { runBlocking { DeskVideoDownloader.readInfo(File(temp.root, "none"), "u") } }.exceptionOrNull() as DownloadProblem
        assertEquals(R.string.downloader_engine_blocked, missing.text)
        val plain = temp.newFile("not-runnable").also { it.writeText("x") }
        val broken = runCatching { runBlocking { DeskVideoDownloader.readInfo(plain, "u") } }.exceptionOrNull() as DownloadProblem
        assertEquals(R.string.downloader_engine_blocked, broken.text)
    }

    @Test fun videoOnlyRemovesTheSoundFromAClipThatHasIt() = kotlinx.coroutines.runBlocking {
        val ffmpeg = app.absentmotion.desk.DeskMedia.ffmpeg
        val clip = File(temp.root, "both.mp4")
        val made = ProcessBuilder(ffmpeg, "-nostdin", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=64x36:rate=10", "-f", "lavfi", "-i", "sine=frequency=440",
            "-t", "1", "-c:v", "mpeg4", "-c:a", "aac", "-shortest", clip.path).redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() }
        org.junit.Assert.assertEquals(0, made)
        val silent = app.absentmotion.tools.withoutAudio(clip, ffmpeg)
        val probe = ProcessBuilder(ffmpeg, "-nostdin", "-hide_banner", "-i", silent.path).redirectErrorStream(true).start().let { p -> String(p.inputStream.readBytes()).also { p.waitFor() } }
        org.junit.Assert.assertTrue(probe, "Video:" in probe && "Audio:" !in probe)
    }
}
