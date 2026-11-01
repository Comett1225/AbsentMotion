package app.absentmotion.pc.media

import app.absentmotion.model.*
import app.absentmotion.pc.platform.DesktopPlatform
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import kotlin.math.floor

class AudioMixer(scene: Scene, base: File?, repo: File? = null, private val processChanged: (Process?) -> Unit = {}) : AutoCloseable {
    companion object { const val RATE = 48000 }
    private val folder = repo?.resolve("pc/build")?.takeIf { it.isDirectory }?.let { java.nio.file.Files.createTempDirectory(it.toPath(), "audio-").toFile() } ?: java.nio.file.Files.createTempDirectory("am-audio-").toFile()
    private val files = mutableMapOf<File, Pcm?>()
    private val gainRef = PropRef(PropRef.Kind.Child, "gain")
    private class Clip(val track: LayerTrack, val timing: VideoTiming, val pcm: Pcm?, val blip: TextBlipSynth.Blips?) {
        var blipStart = -1
        var blipSamples = FloatArray(0)
        fun sampleBlip(frame: Int): Float {
            val value = blip ?: return 0f
            if (frame < 0 || frame >= TextBlipSynth.samples(value.times)) return 0f
            if (frame < blipStart || frame >= blipStart + blipSamples.size) {
                blipStart = frame / 4096 * 4096
                blipSamples = TextBlipSynth.render(value.times, value.voice, value.volume, value.pitch, value.variation, value.seed, blipStart, minOf(4096, TextBlipSynth.samples(value.times) - blipStart))
            }
            return blipSamples[frame - blipStart]
        }
    }
    private val clips: List<Clip>

    init {
        try {
            clips = scene.layerTracks().flatMap { track ->
                val layer = track.layer
                val gain = layer.element(gainRef)
                if (gain != null && !gain.isKeyframed && (gain["value"]?.finiteFloat() ?: 1f) <= 0f) return@flatMap emptyList<Clip>()
                val uri = if (layer.kind == "audio") layer.node["src"] else layer.mediaFill?.takeIf { it.video }?.uri
                val declared = uri?.let { scene.media(it) ?: track.scene.media(it) }
                val pcm = if (uri == null || declared?.node?.get("audio") == "none") null else {
                    val file = resolve(uri, base, repo) ?: error("Audio file not found: $uri")
                    val key = file.canonicalFile
                    if (files.containsKey(key)) files[key] else decode(file).also { files[key] = it }
                }
                val timing = VideoTiming(layer)
                listOfNotNull(pcm?.let { Clip(track, timing, it, null) }, TextBlipSynth.blips(layer)?.let { Clip(track, timing, null, it) })
            }
        } catch (error: Throwable) { close(); throw error }
    }

    private fun resolve(uri: String, base: File?, repo: File?): File? = runCatching {
        val file = if (uri.startsWith("file:")) File(URI(uri)) else File(uri)
        listOfNotNull(file.takeIf { it.isAbsolute }, base?.resolve(uri), repo?.resolve(uri)).firstOrNull { it.isFile }
    }.getOrNull()

    private fun decode(file: File): Pcm? {
        val probe = ProcessBuilder(DesktopPlatform.executable("ffprobe"), "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=index", "-of", "csv=p=0", file.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        processChanged(probe)
        val stream = probe.inputStream.bufferedReader().readText().trim()
        val probeExit = probe.waitFor()
        processChanged(null)
        check(probeExit == 0) { "Could not inspect audio: ${file.name}" }
        if (stream.isEmpty()) return null
        val output = File.createTempFile("audio-", ".pcm", folder)
        val process = ProcessBuilder(DesktopPlatform.executable("ffmpeg"), "-v", "error", "-i", file.absolutePath, "-map", "0:a:0", "-vn", "-ac", "2", "-ar", "$RATE", "-f", "f32le", "-y", output.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        processChanged(process)
        val exit = process.waitFor()
        processChanged(null)
        check(exit == 0) { "Could not decode audio: ${file.name}" }
        return Pcm(output)
    }

    private class Pcm(file: File) : AutoCloseable {
        private val input = RandomAccessFile(file, "r")
        val frames = input.length() / 8
        private val bytes = ByteArray(4096 * 8)
        private var first = -1L
        private var size = 0
        fun sample(frame: Long, channel: Int): Float {
            if (frame < 0 || frame >= frames) return 0f
            if (frame < first || frame >= first + size) {
                first = frame / 4096 * 4096
                input.seek(first * 8)
                size = input.read(bytes).coerceAtLeast(0) / 8
            }
            val at = ((frame - first) * 8 + channel * 4).toInt()
            val bits = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or ((bytes[at + 2].toInt() and 255) shl 16) or (bytes[at + 3].toInt() shl 24)
            return Float.fromBits(bits)
        }
        override fun close() = input.close()
    }

    fun mix(startFrame: Long, count: Int): FloatArray {
        require(count in 0..RATE * 10)
        val out = FloatArray(count * 2)
        for (clip in clips) {
            val layer = clip.track.layer
            val duration = (layer.endTime - layer.startTime).coerceAtLeast(1).toFloat()
            val inputStart = (layer.loopStart ?: layer.node["inTime"]?.toIntOrNull() ?: 0).toDouble()
            val inputEnd = layer.node["outTime"]?.toDoubleOrNull() ?: (clip.pcm?.frames?.times(1000.0 / RATE) ?: Double.POSITIVE_INFINITY)
            var gain = 1f
            var lastGain = -64
            for (i in 0 until count) {
                val project = (startFrame + i) * 1000.0 / RATE
                val local = clip.track.timeAt(project, true, false) ?: continue
                if (local.rate <= 0) continue
                val relative = local.ms - layer.startTime
                if (i - lastGain >= 64) { gain = (layer.element(gainRef)?.valueAt((relative / duration).toFloat())?.finiteFloat() ?: 1f).coerceIn(0f, 1.5f); lastGain = i }
                if (clip.blip != null) {
                    val value = clip.sampleBlip((relative * TextBlipSynth.RATE / 1000).toInt()) * gain
                    out[i * 2] += value
                    out[i * 2 + 1] += value
                } else {
                    var media = clip.timing.mediaMs(relative)
                    if (layer.node["loop"] == "true" && inputEnd.isFinite()) {
                        val span = (inputEnd - inputStart).coerceAtLeast(1.0)
                        media = inputStart + ((media - inputStart) % span + span) % span
                    }
                    if (media < inputStart || media >= inputEnd) continue
                    val sample = media * RATE / 1000
                    val frame = floor(sample).toLong()
                    val fraction = (sample - frame).toFloat()
                    val pcm = clip.pcm ?: continue
                    for (channel in 0..1) out[i * 2 + channel] += (pcm.sample(frame, channel) * (1 - fraction) + pcm.sample(frame + 1, channel) * fraction) * gain
                }
            }
        }
        for (i in out.indices) {
            val x = out[i]
            if (kotlin.math.abs(x) > .8f) out[i] = kotlin.math.sign(x) * (.8f + .2f * kotlin.math.tanh((kotlin.math.abs(x) - .8f) / .2f))
        }
        return out
    }

    override fun close() { files.values.filterNotNull().forEach { it.close() }; folder.deleteRecursively() }
}
