package app.absentmotion.pc

import android.content.Context
import android.net.Uri
import app.absentmotion.audio.audioDeclaration
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskOs
import app.absentmotion.desk.DeskSpeech
import app.absentmotion.desk.SpeechCommands
import app.absentmotion.desk.SpeechEngine
import app.absentmotion.desk.SpeechKind
import app.absentmotion.platform.Speech
import app.absentmotion.ui.editor.SpeechVoice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import javax.sound.sampled.AudioSystem
import kotlin.math.abs

class DeskSpeechTest {
    private val dir = File("build/speech-test").absoluteFile.also { it.mkdirs() }
    private val sentence = "Hello, this is a short test of the speech system. It should take a few seconds to say."

    init {
        System.setProperty("am.data", File("build/speech-data").absolutePath)
    }

    private fun voices() = runBlocking { DeskSpeech.voices() }

    private fun voice(): SpeechVoice = voices().let { all ->
        all.firstOrNull { it.language.startsWith("English") && it.name in setOf("Samantha", "Daniel", "Alex", "David", "Zira") } ?: all.firstOrNull { it.language.startsWith("English") } ?: all.first()
    }

    private fun make(name: String, text: String = sentence, rate: Float = 1f, pitch: Float = 1f, voice: SpeechVoice = voice()): File =
        File(dir, name).also { out -> runBlocking { DeskSpeech.toFile(text, voice, rate, pitch, out) } }

    private fun probe(file: File): JSONObject {
        val p = ProcessBuilder(DeskMedia.ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", file.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val text = p.inputStream.readBytes().decodeToString()
        check(p.waitFor() == 0) { "ffprobe failed on ${file.name}" }
        return JSONObject(text)
    }

    private fun seconds(file: File) = probe(file).getJSONObject("format").getDouble("duration")

    private fun crossings(file: File): Double {
        val p = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-i", file.path, "-f", "s16le", "-ac", "1", "-ar", "44100", "-").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        val n = bytes.size / 2
        var last = 0
        var count = 0
        for (i in 0 until n) {
            val v = (bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)
            if (abs(v) > 300) { if ((v > 0) != (last > 0) && last != 0) count++; last = v }
        }
        return count.toDouble() / n
    }

    private fun installed() = assumeTrue("no speech engine on this computer", voices().isNotEmpty())

    @Test fun voicesAreListedWithNamesAndLanguages() {
        installed()
        val all = voices()
        assertTrue(all.isNotEmpty())
        assertEquals(all.size, all.map { it.id }.toSet().size)
        all.forEach {
            assertTrue(it.id.isNotBlank())
            assertTrue("name of ${it.id}", it.name.isNotBlank())
            assertTrue("language of ${it.id}", it.language.isNotBlank())
        }
        println("voices: ${all.size}, languages: ${all.map { it.language }.distinct().size}, first: ${all.first()}")
    }

    @Test fun shortSentenceBecomesValidAudio() {
        installed()
        val file = make("sentence.wav")
        val info = probe(file)
        val stream = info.getJSONArray("streams").getJSONObject(0)
        assertEquals("audio", stream.getString("codec_type"))
        assertEquals("pcm_s16le", stream.getString("codec_name"))
        assertEquals(44100, stream.getString("sample_rate").toInt())
        assertEquals(1, stream.getInt("channels"))
        val duration = info.getJSONObject("format").getDouble("duration")
        println("sentence duration: $duration s")
        assertTrue("duration $duration", duration in 2.0..12.0)
    }

    @Test fun speedChangesTheDuration() {
        installed()
        val slow = seconds(make("slow.wav", rate = 0.5f))
        val normal = seconds(make("normal.wav"))
        val fast = seconds(make("fast.wav", rate = 2f))
        println("slow $slow normal $normal fast $fast")
        assertTrue("slow $slow normal $normal", slow > normal * 1.6)
        assertTrue("fast $fast normal $normal", fast < normal * 0.75)
    }

    @Test fun pitchKeepsTheLengthAndMovesThePitch() {
        installed()
        val flat = make("flat.wav")
        val high = make("high.wav", pitch = 1.5f)
        val low = make("low.wav", pitch = 0.7f)
        val base = seconds(flat)
        println("pitch durations: low ${seconds(low)} flat $base high ${seconds(high)}; crossings low ${crossings(low)} flat ${crossings(flat)} high ${crossings(high)}")
        assertTrue(abs(seconds(high) - base) < base * 0.15)
        assertTrue(abs(seconds(low) - base) < base * 0.15)
        assertTrue(crossings(high) > crossings(flat) * 1.2)
        assertTrue(crossings(low) < crossings(flat) * 0.9)
    }

    @Test fun oddCharactersAreSpokenNotParsed() {
        installed()
        val file = make("odd.wav", "-v -r 500 café — “quoted” \$HOME `ls` 你好 \"end\"")
        assertTrue(seconds(file) > 1.0)
    }

    @Test fun longestTextWorks() {
        installed()
        val text = "$sentence ".repeat(60).take(Speech.MAX_CHARS)
        val file = make("long.wav", text)
        val duration = seconds(file)
        println("longest text: $duration s, ${file.length() / 1024} KiB")
        assertTrue("duration $duration", duration > 120)
    }

    @Test fun cancellingStopsTheEngineAndLeavesNoFile() {
        installed()
        val out = File(dir, "cancelled.wav")
        val text = "$sentence ".repeat(60).take(Speech.MAX_CHARS)
        val started = System.nanoTime()
        runBlocking {
            val job = launch { DeskSpeech.toFile(text, voice(), 1f, 1f, out) }
            delay(150)
            withTimeout(3000) { job.cancelAndJoinNow() }
        }
        assertFalse(out.exists())
        println("cancelled in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    private suspend fun kotlinx.coroutines.Job.cancelAndJoinNow() { cancel(); join() }

    @Test fun javaSoundCanOpenTheFileForPreview() {
        installed()
        val file = make("playable.wav")
        AudioSystem.getAudioInputStream(file).use {
            assertEquals(44100f, it.format.sampleRate, 0f)
            assertEquals(16, it.format.sampleSizeInBits)
            assertEquals(1, it.format.channels)
            assertTrue(it.frameLength > 44100)
        }
    }

    @Test fun editorFlowAcceptsTheFileAsAnAudioLayer() {
        installed()
        val file = make("layer.wav")
        val media = runBlocking { audioDeclaration(Context(), Uri.fromFile(file)) }
        assertNotNull(media)
        val node = media!!.node
        println("declaration: ${node.attrs}")
        assertTrue(node.attrs["duration"]!!.toLong() > 1_000_000L)
        assertTrue(node.attrs["type"]!!.startsWith("audio/"))
    }

    private fun silence(name: String, seconds: Double) = File(dir, name).also {
        val p = ProcessBuilder(DeskMedia.ffmpeg, "-nostdin", "-v", "error", "-y", "-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono", "-t", "$seconds", "-c:a", "pcm_s16le", it.path).start()
        check(p.waitFor() == 0)
    }

    @Test fun silentFilePlaysToTheEndAndStopsOnCancel() {
        val short = silence("quiet-short.wav", 0.4)
        val long = silence("quiet-long.wav", 20.0)
        val ran = runCatching { runBlocking { withTimeout(8000) { DeskSpeech.play(short) } } }
        assumeTrue("no audio output on this computer: ${ran.exceptionOrNull()}", ran.isSuccess)
        runBlocking {
            val started = System.nanoTime()
            val job = launch(kotlinx.coroutines.Dispatchers.IO) { DeskSpeech.play(long) }
            delay(400)
            withTimeout(2000) { job.cancelAndJoinNow() }
            println("playback cancelled after ${(System.nanoTime() - started) / 1_000_000} ms")
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2500)
        }
    }

    private val say = SpeechEngine(SpeechKind.Say, "/usr/bin/say")
    private val shell = SpeechEngine(SpeechKind.PowerShell, "powershell.exe")
    private val espeak = SpeechEngine(SpeechKind.Espeak, "/usr/bin/espeak-ng")
    private val text = File("in.txt")
    private val out = File("out.aiff")

    @Test fun enginePickFollowsTheSystem() {
        assertEquals("/usr/bin/say", SpeechCommands.pick(true, false) { "/usr/bin/$it" }!!.exe)
        assertNull(SpeechCommands.pick(true, false) { null })
        assertEquals("powershell.exe", SpeechCommands.pick(false, true) { null }!!.exe)
        assertEquals("C:\\ps\\powershell.exe", SpeechCommands.pick(false, true) { "C:\\ps\\$it" }!!.exe)
        assertEquals("/usr/bin/espeak-ng", SpeechCommands.pick(false, false) { "/usr/bin/$it" }!!.exe)
        assertEquals("/usr/bin/espeak", SpeechCommands.pick(false, false) { if (it == "espeak") "/usr/bin/$it" else null }!!.exe)
        assertNull(SpeechCommands.pick(false, false) { null })
    }

    @Test fun macCommandsUseSayWithAFileAndNativeFastSpeech() {
        assertEquals(listOf("/usr/bin/say", "-v", "?"), SpeechCommands.listing(say).command)
        val normal = SpeechCommands.speak(say, "Daniel (English (UK))", 1f, 1f, text, out)
        assertEquals(listOf("/usr/bin/say", "-v", "Daniel (English (UK))", "-o", out.path, "-f", text.path), normal.command)
        assertEquals(1f, normal.tempo, 0f)
        val fast = SpeechCommands.speak(say, "Samantha", 1.5f, 1f, text, out)
        assertEquals(listOf("/usr/bin/say", "-v", "Samantha", "-r", "263", "-o", out.path, "-f", text.path), fast.command)
        assertEquals(1f, fast.tempo, 0f)
        val slow = SpeechCommands.speak(say, "Samantha", 0.6f, 1.2f, text, out)
        assertFalse("-r" in slow.command)
        assertEquals(0.6f, slow.tempo, 0f)
        assertEquals(1.2f, slow.pitch, 0f)
        assertEquals("350", SpeechCommands.speak(say, "x", 9f, 1f, text, out).command[SpeechCommands.speak(say, "x", 9f, 1f, text, out).command.indexOf("-r") + 1])
    }

    @Test fun windowsCommandsCarryNoUserTextAndTheScriptIsWellFormed() {
        val run = SpeechCommands.speak(shell, "Microsoft Zira Desktop", 1.5f, 0.8f, File("C:\\t\\in.txt"), File("C:\\t\\raw.wav"))
        assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand"), run.command.take(6))
        assertEquals(7, run.command.size)
        val script = String(Base64.getDecoder().decode(run.command[6]), Charsets.UTF_16LE)
        listOf("Add-Type -AssemblyName System.Speech", "SelectVoice(\$env:AM_VOICE)", "SetOutputToWaveFile(\$env:AM_OUT)", "ReadAllText(\$env:AM_TEXT", "Dispose()", "ErrorActionPreference='Stop'").forEach {
            assertTrue("script lacks $it: $script", it in script)
        }
        assertFalse("Zira" in script)
        assertEquals(mapOf("AM_VOICE" to "Microsoft Zira Desktop", "AM_OUT" to "C:\\t\\raw.wav", "AM_TEXT" to "C:\\t\\in.txt"), run.env)
        assertEquals(1.5f, run.tempo, 0f)
        assertEquals(0.8f, run.pitch, 0f)
        val list = SpeechCommands.listing(shell)
        assertTrue("GetInstalledVoices()" in String(Base64.getDecoder().decode(list.command[6]), Charsets.UTF_16LE))
        assertTrue(list.env.isEmpty())
    }

    @Test fun linuxCommandsMapSpeedAndPitchToEspeak() {
        assertEquals(listOf("/usr/bin/espeak-ng", "--voices"), SpeechCommands.listing(espeak).command)
        val normal = SpeechCommands.speak(espeak, "en-gb", 1f, 1f, text, out)
        assertEquals(listOf("/usr/bin/espeak-ng", "-v", "en-gb", "-s", "175", "-p", "50", "-w", out.path, "-f", text.path), normal.command)
        assertEquals(1f, normal.tempo, 0f)
        assertEquals(1f, normal.pitch, 0f)
        val slow = SpeechCommands.speak(espeak, "de", 0.5f, 0.5f, text, out).command
        assertEquals(listOf("88", "25"), listOf(slow[slow.indexOf("-s") + 1], slow[slow.indexOf("-p") + 1]))
        val fast = SpeechCommands.speak(espeak, "de", 2f, 2f, text, out).command
        assertEquals(listOf("350", "99"), listOf(fast[fast.indexOf("-s") + 1], fast[fast.indexOf("-p") + 1]))
        val wild = SpeechCommands.speak(espeak, "de", 50f, -3f, text, out).command
        assertEquals(listOf("350", "25"), listOf(wild[wild.indexOf("-s") + 1], wild[wild.indexOf("-p") + 1]))
    }

    @Test fun conversionFiltersKeepLengthWhenOnlyPitchMoves() {
        assertNull(SpeechCommands.filter(1f, 1f))
        assertEquals("aresample=44100,asetrate=66150,aresample=44100,atempo=0.66667", SpeechCommands.filter(1.5f, 1f))
        assertEquals("atempo=0.60000", SpeechCommands.filter(1f, 0.6f))
        assertEquals("aresample=44100,asetrate=22050,aresample=44100,atempo=2.00000,atempo=1.50000", SpeechCommands.filter(0.5f, 1.5f))
        val run = SpeechCommands.speak(say, "Samantha", 0.6f, 1.5f, text, out)
        val command = SpeechCommands.convert("ffmpeg", out, run, File("final.wav"))
        assertEquals(listOf("ffmpeg", "-nostdin", "-v", "error", "-y", "-i", out.path, "-af"), command.take(8))
        assertEquals(listOf("-ar", "44100", "-ac", "1", "-c:a", "pcm_s16le", "-f", "wav", "final.wav"), command.takeLast(9))
        assertFalse("-af" in SpeechCommands.convert("ffmpeg", out, SpeechCommands.speak(espeak, "en", 1f, 1f, text, out), File("final.wav")))
    }

    @Test fun macVoiceListIsParsedWithQualifiersAndDuplicates() {
        val listing = """
            Albert              en_US    # Hello! My name is Albert.
            Bad News            en_US    # Hello! My name is Bad News.
            Daniel (English (UK)) en_GB    # Hello! My name is Daniel.
            Daniel (English (UK)) en_GB    # Hello! My name is Daniel.
            Eddy (German (Germany)) de_DE    # Hallo! Ich heiße Eddy.
            Flo (English (US))  en_US    # Hello! My name is Flo.
            Kanya               th_TH    # สวัสดี
            Eddy (Chinese (China mainland)) zh_CN    # 你好！我叫Eddy。
        """.trimIndent()
        val found = SpeechCommands.parse(SpeechKind.Say, listing)
        assertEquals(7, found.size)
        assertEquals(setOf("Albert", "Bad News", "Daniel (English (UK))", "Eddy (German (Germany))", "Flo (English (US))", "Kanya", "Eddy (Chinese (China mainland))"), found.map { it.id }.toSet())
        val daniel = found.first { it.id.startsWith("Daniel") }
        assertEquals("Daniel", daniel.name)
        assertEquals("English (United Kingdom)", daniel.language)
        assertEquals("German (Germany)", found.first { it.name == "Eddy" && it.id.contains("German") }.language)
        assertEquals("English (United States)", found.first { it.name == "Flo" }.language)
        assertEquals(listOf("Albert", "Bad News", "Flo"), found.filter { it.language == "English (United States)" }.map { it.name })
    }

    @Test fun windowsVoiceListIsParsed() {
        val listing = "\uFEFFMicrosoft David Desktop\ten-US\r\nMicrosoft Hazel Desktop\ten-GB\r\nMicrosoft Huihui Desktop\tzh-CN\r\n\r\n"
        val found = SpeechCommands.parse(SpeechKind.PowerShell, listing)
        assertEquals(listOf("David", "Hazel", "Huihui"), found.map { it.name }.sortedBy { it })
        assertEquals("Microsoft David Desktop", found.first { it.name == "David" }.id)
        assertEquals("English (United States)", found.first { it.name == "David" }.language)
        assertEquals("English (United Kingdom)", found.first { it.name == "Hazel" }.language)
        assertTrue(SpeechCommands.parse(SpeechKind.PowerShell, "").isEmpty())
    }

    @Test fun espeakVoiceListIsParsed() {
        val listing = """
            Pty Language       Age/Gender VoiceName          File                 Other Languages
             5  af              --/M      Afrikaans          gmw/af               
             5  en-gb           --/M      English_(Great_Britain) gmw/en-GB            (en 2)
             2  en-us           --/M      English_(America)  gmw/en-US            (en 3)
             5  de              --/M      German             gmw/de               
             5  cmn             --/M      Chinese_(Mandarin,_latin_as_English) sit/cmn            (zh-cmn 5)(zh 5)
        """.trimIndent()
        val found = SpeechCommands.parse(SpeechKind.Espeak, listing)
        assertEquals(5, found.size)
        val gb = found.first { it.id == "en-gb" }
        assertEquals("English (Great Britain)", gb.name)
        assertEquals("English (United Kingdom)", gb.language)
        assertEquals("German", found.first { it.id == "de" }.language)
        assertEquals("Afrikaans", found.first { it.id == "af" }.language)
        assertEquals("Chinese (Mandarin, latin as English)", found.first { it.id == "cmn" }.name)
        assertTrue(SpeechCommands.parse(SpeechKind.Espeak, "").isEmpty())
    }

    @Test fun missingEngineOnlyEverMeansNoVoices() {
        assumeTrue(DeskOs.linux)
        if (voices().isEmpty()) assertEquals(app.absentmotion.R.string.voice_install_espeak, DeskSpeech.noVoices)
    }
}
