package app.absentmotion.desk

import android.net.Uri
import app.absentmotion.R
import app.absentmotion.export.ExportError
import app.absentmotion.export.exportCheck
import app.absentmotion.i18n.txt
import app.absentmotion.platform.Speech
import app.absentmotion.ui.editor.SpeechVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class SpeechKind { Say, PowerShell, Espeak }

internal class SpeechEngine(val kind: SpeechKind, val exe: String)

internal class SpeechRun(val command: List<String>, val env: Map<String, String> = emptyMap(), val tempo: Float = 1f, val pitch: Float = 1f)

internal object SpeechCommands {
    const val SAMPLE_RATE = 44100
    private const val WORDS_PER_MINUTE = 175
    private const val SLOWEST = 0.5f
    private const val FASTEST = 2f

    private val windowsList = listOf(
        "\$ErrorActionPreference='Stop'",
        "[Console]::OutputEncoding=New-Object Text.UTF8Encoding(\$false)",
        "Add-Type -AssemblyName System.Speech",
        "\$s=New-Object System.Speech.Synthesis.SpeechSynthesizer",
        "foreach(\$v in \$s.GetInstalledVoices()){if(\$v.Enabled){\$v.VoiceInfo.Name+[char]9+\$v.VoiceInfo.Culture.Name}}",
    ).joinToString(";")

    private val windowsSpeak = listOf(
        "\$ErrorActionPreference='Stop'",
        "Add-Type -AssemblyName System.Speech",
        "\$s=New-Object System.Speech.Synthesis.SpeechSynthesizer",
        "\$s.SelectVoice(\$env:AM_VOICE)",
        "\$s.SetOutputToWaveFile(\$env:AM_OUT)",
        "\$s.Speak([IO.File]::ReadAllText(\$env:AM_TEXT,[Text.Encoding]::UTF8))",
        "\$s.Dispose()",
    ).joinToString(";")

    private val macLine = Regex("^(.+?)\\s+([a-z]{2,3}(?:[_-][A-Za-z0-9]{2,4})?)\\s+#")
    private val macQualifier = Regex("\\s*\\(.*\\)$")
    private val whitespace = Regex("\\s+")

    fun pick(mac: Boolean, windows: Boolean, find: (String) -> String?): SpeechEngine? = when {
        mac -> find("say")?.let { SpeechEngine(SpeechKind.Say, it) }
        windows -> SpeechEngine(SpeechKind.PowerShell, find("powershell.exe") ?: "powershell.exe")
        else -> (find("espeak-ng") ?: find("espeak"))?.let { SpeechEngine(SpeechKind.Espeak, it) }
    }

    private fun powershell(engine: SpeechEngine, script: String, env: Map<String, String>, tempo: Float = 1f, pitch: Float = 1f) = SpeechRun(
        listOf(engine.exe, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))),
        env, tempo, pitch,
    )

    fun listing(engine: SpeechEngine): SpeechRun = when (engine.kind) {
        SpeechKind.Say -> SpeechRun(listOf(engine.exe, "-v", "?"))
        SpeechKind.PowerShell -> powershell(engine, windowsList, emptyMap())
        SpeechKind.Espeak -> SpeechRun(listOf(engine.exe, "--voices"))
    }

    fun speak(engine: SpeechEngine, voice: String, rate: Float, pitch: Float, text: File, out: File): SpeechRun {
        val speed = rate.coerceIn(SLOWEST, FASTEST)
        val tone = pitch.coerceIn(SLOWEST, FASTEST)
        return when (engine.kind) {
            SpeechKind.Say -> SpeechRun(
                listOf(engine.exe, "-v", voice) + (if (speed > 1f) listOf("-r", "${(WORDS_PER_MINUTE * speed).roundToInt()}") else emptyList()) + listOf("-o", out.path, "-f", text.path),
                tempo = min(speed, 1f), pitch = tone,
            )
            SpeechKind.PowerShell -> powershell(engine, windowsSpeak, mapOf("AM_VOICE" to voice, "AM_OUT" to out.path, "AM_TEXT" to text.path), speed, tone)
            SpeechKind.Espeak -> SpeechRun(listOf(
                engine.exe, "-v", voice, "-s", "${(WORDS_PER_MINUTE * speed).roundToInt().coerceIn(80, 450)}", "-p", "${(50 * tone).roundToInt().coerceIn(0, 99)}",
                "-w", out.path, "-f", text.path,
            ))
        }
    }

    fun filter(pitch: Float, tempo: Float): String? {
        val steps = mutableListOf<String>()
        if (pitch != 1f) steps += listOf("aresample=$SAMPLE_RATE", "asetrate=${(SAMPLE_RATE * pitch).roundToInt()}", "aresample=$SAMPLE_RATE", "atempo=${number(1f / pitch)}")
        if (tempo != 1f) steps += "atempo=${number(tempo)}"
        return steps.joinToString(",").ifEmpty { null }
    }

    fun convert(ffmpeg: String, input: File, run: SpeechRun, out: File): List<String> =
        listOf(ffmpeg, "-nostdin", "-v", "error", "-y", "-i", input.path) + (filter(run.pitch, run.tempo)?.let { listOf("-af", it) } ?: emptyList()) +
            listOf("-ar", "$SAMPLE_RATE", "-ac", "1", "-c:a", "pcm_s16le", "-f", "wav", out.path)

    fun parse(kind: SpeechKind, output: String): List<SpeechVoice> {
        val lines = output.trimStart('\uFEFF').lines().map { it.trim() }.filter { it.isNotEmpty() }
        val found = when (kind) {
            SpeechKind.Say -> lines.mapNotNull { line ->
                macLine.find(line)?.destructured?.let { (name, code) -> SpeechVoice(name, name.replace(macQualifier, "").ifEmpty { name }, language(code, name)) }
            }
            SpeechKind.PowerShell -> lines.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2 || parts[0].isBlank()) null
                else SpeechVoice(parts[0].trim(), parts[0].trim().removePrefix("Microsoft ").removeSuffix(" Desktop"), language(parts[1].trim(), parts[0].trim()))
            }
            SpeechKind.Espeak -> lines.mapNotNull { line ->
                val columns = line.split(whitespace)
                if (columns.size < 4 || columns[0].toIntOrNull() == null) null
                else columns[3].replace('_', ' ').let { name -> SpeechVoice(columns[1], name, language(columns[1], name)) }
            }
        }
        return found.distinctBy { it.id }.sortedWith(compareBy({ it.language }, { it.name }))
    }

    private fun language(code: String, fallback: String): String {
        val tag = code.replace('_', '-')
        return Locale.forLanguageTag(tag).displayName.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) } ?: fallback
    }

    private fun number(value: Float) = String.format(Locale.ROOT, "%.5f", value)
}

object DeskSpeech {
    private val engine: SpeechEngine? by lazy { SpeechCommands.pick(DeskOs.mac, DeskOs.windows, ::find) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var known: List<SpeechVoice>? = null
    @Volatile private var playing: Job? = null

    val noVoices: Int get() = if (engine == null && DeskOs.linux) R.string.voice_install_espeak else R.string.voice_none_offline

    private fun find(name: String): String? =
        (System.getenv("PATH").orEmpty().split(File.pathSeparatorChar) + listOf("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "/snap/bin"))
            .filter { it.isNotBlank() }.map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.path

    suspend fun voices(): List<SpeechVoice> {
        known?.let { return it }
        val found = engine ?: return emptyList()
        return withContext(Dispatchers.IO) {
            val listing = File.createTempFile("amspeech", ".txt")
            try {
                val run = SpeechCommands.listing(found)
                if (exec(run, listing, 60_000L)) SpeechCommands.parse(found.kind, listing.readText(Charsets.UTF_8)).also { if (it.isNotEmpty()) known = it } else emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            } finally {
                listing.delete()
            }
        }
    }

    fun preview(text: String, voice: SpeechVoice, rate: Float, pitch: Float) {
        if (engine == null) return
        playing?.cancel()
        playing = scope.launch {
            val file = File.createTempFile("amspeech", ".wav")
            try {
                render(text, voice, rate, pitch, file)
                play(file)
            } catch (_: Exception) {
            } finally {
                file.delete()
            }
        }
    }

    fun stop() {
        playing?.cancel()
        playing = null
    }

    suspend fun toFile(text: String, voice: SpeechVoice, rate: Float, pitch: Float, out: File): Uri {
        render(text, voice, rate, pitch, out)
        return Uri.fromFile(out)
    }

    internal suspend fun render(text: String, voice: SpeechVoice, rate: Float, pitch: Float, out: File) = withContext(Dispatchers.IO) {
        val found = engine ?: throw ExportError(txt(noVoices))
        val spoken = text.take(Speech.MAX_CHARS)
        val input = File.createTempFile("amspeech", ".txt")
        val raw = File.createTempFile("amspeech", if (found.kind == SpeechKind.Say) ".aiff" else ".wav")
        try {
            input.writeText(spoken, Charsets.UTF_8)
            val run = SpeechCommands.speak(found, voice.id, rate, pitch, input, raw)
            exportCheck(exec(run, null, 30_000L + 25L * spoken.length) && raw.length() > 44) { txt(R.string.platform_error_voice) }
            val convert = SpeechRun(SpeechCommands.convert(DeskMedia.ffmpeg, raw, run, out))
            exportCheck(exec(convert, null, 60_000L + 25L * spoken.length) && out.length() > 44) { txt(R.string.platform_error_voice) }
        } catch (e: Throwable) {
            out.delete()
            throw if (e is IOException) ExportError(txt(R.string.platform_error_voice), e) else e
        } finally {
            input.delete()
            raw.delete()
        }
    }

    private suspend fun exec(run: SpeechRun, output: File?, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(run.command)
        builder.environment().putAll(run.env)
        builder.redirectOutput(output?.let { ProcessBuilder.Redirect.to(it) } ?: ProcessBuilder.Redirect.DISCARD)
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        val process = builder.start()
        process.outputStream.close()
        try {
            runInterruptible { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) } && process.exitValue() == 0
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    internal suspend fun play(file: File) {
        AudioSystem.getAudioInputStream(file).use { input ->
            val format = input.format
            val line = AudioSystem.getSourceDataLine(format)
            try {
                line.open(format, format.frameSize * (format.sampleRate / 5).toInt())
                line.start()
                val chunk = ByteArray(format.frameSize * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(chunk)
                    if (n < 0) break
                    line.write(chunk, 0, n)
                }
                val end = System.nanoTime() + 1_200_000_000L
                while (line.available() < line.bufferSize && System.nanoTime() < end) delay(20)
            } finally {
                line.stop()
                line.flush()
                line.close()
            }
        }
    }
}
