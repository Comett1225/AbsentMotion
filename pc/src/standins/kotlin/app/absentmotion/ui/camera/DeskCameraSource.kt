package app.absentmotion.ui.camera

import app.absentmotion.desk.DeskOs
import java.io.File

data class CameraDevice(val id: String, val name: String)

class CameraDevices(val video: List<CameraDevice>, val audio: List<CameraDevice>)

class CameraInput(val video: CameraDevice, val audio: CameraDevice?, val mac: Boolean = DeskOs.mac, val windows: Boolean = DeskOs.windows) {
    val fake: Boolean get() = video.id == FAKE
    val separateAudio: Boolean get() = audio != null && !mac && !windows || fake && audio != null

    fun args(): List<String> = when {
        fake -> listOf("-re", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30") + if (audio != null) listOf("-re", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000") else emptyList()
        mac -> listOf("-f", "avfoundation", "-i", "${video.id}:${audio?.id ?: "none"}")
        windows -> listOf("-f", "dshow", "-rtbufsize", "200M", "-i", "video=${video.id}" + (audio?.let { ":audio=${it.id}" } ?: ""))
        else -> listOf("-f", "v4l2", "-i", video.id) + if (audio != null) listOf("-f", "pulse", "-i", audio.id) else emptyList()
    }

    val audioMap: String? get() = when {
        audio == null -> null
        separateAudio -> "1:a"
        else -> "0:a"
    }

    companion object {
        const val FAKE = "fake"
    }
}

object DeskCameraSource {
    val fakeRequested: Boolean get() = System.getProperty("am.fakeCamera") == "true" || System.getenv("AM_FAKE_CAMERA") == "1"
    val fakeDevices = CameraDevices(listOf(CameraDevice(CameraInput.FAKE, "Test pattern")), listOf(CameraDevice(CameraInput.FAKE, "Test tone")))

    private val avfoundationLine = Regex("""\[(\d+)] (.+)$""")
    private val dshowLine = Regex(""""(.+)" \((video|audio|none)\)""")

    fun parseAvfoundation(text: String): CameraDevices {
        val video = ArrayList<CameraDevice>()
        val audio = ArrayList<CameraDevice>()
        var section: MutableList<CameraDevice>? = null
        for (line in text.lineSequence()) {
            when {
                "AVFoundation video devices" in line -> section = video
                "AVFoundation audio devices" in line -> section = audio
                else -> avfoundationLine.find(line.trimEnd())?.let { m -> section?.add(CameraDevice(m.groupValues[1], m.groupValues[2].trim())) }
            }
        }
        return CameraDevices(video.filterNot { it.name.startsWith("Capture screen") }, audio)
    }

    fun parseDshow(text: String): CameraDevices {
        val video = ArrayList<CameraDevice>()
        val audio = ArrayList<CameraDevice>()
        var section = ""
        for (line in text.lineSequence()) {
            if ("Alternative name" in line) continue
            if ("DirectShow video devices" in line) section = "video"
            else if ("DirectShow audio devices" in line) section = "audio"
            dshowLine.find(line)?.let { m ->
                val kind = m.groupValues[2].takeIf { it != "none" } ?: section
                val device = CameraDevice(m.groupValues[1], m.groupValues[1])
                if (kind == "video") video.add(device) else if (kind == "audio") audio.add(device)
            }
        }
        return CameraDevices(video, audio)
    }

    fun linuxDevices(sysfs: File = File("/sys/class/video4linux"), dev: File = File("/dev")): CameraDevices {
        val nodes = sysfs.listFiles { f -> f.name.startsWith("video") }.orEmpty().sortedBy { it.name.removePrefix("video").toIntOrNull() ?: Int.MAX_VALUE }
        val video = nodes.filter { node -> runCatching { node.resolve("index").readText().trim() == "0" }.getOrDefault(true) }.map { node ->
            CameraDevice(File(dev, node.name).invariantSeparatorsPath,runCatching { node.resolve("name").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: node.name)
        }
        return CameraDevices(video, if (video.isEmpty()) emptyList() else listOf(CameraDevice("default", "Default microphone")))
    }

    fun listCommand(ffmpeg: String, mac: Boolean, windows: Boolean): List<String>? = when {
        mac -> listOf(ffmpeg, "-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", "")
        windows -> listOf(ffmpeg, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy")
        else -> null
    }

    fun list(ffmpeg: String, mac: Boolean = DeskOs.mac, windows: Boolean = DeskOs.windows): CameraDevices {
        if (fakeRequested) return fakeDevices
        val command = listCommand(ffmpeg, mac, windows) ?: return linuxDevices()
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.outputStream.close()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (mac) parseAvfoundation(text) else parseDshow(text)
        }.getOrDefault(CameraDevices(emptyList(), emptyList()))
    }

    fun encoders(ffmpeg: String): Set<String> = runCatching {
        val process = ProcessBuilder(ffmpeg, "-hide_banner", "-encoders").redirectErrorStream(true).start()
        process.outputStream.close()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        Regex("""^\s*V\S*\s+(\S+)""", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toSet()
    }.getOrDefault(emptySet())

    fun videoEncoder(available: Set<String>, mac: Boolean = DeskOs.mac, windows: Boolean = DeskOs.windows): List<String> {
        val order = when { mac -> listOf("h264_videotoolbox", "libx264"); windows -> listOf("libx264", "h264_mf"); else -> listOf("libx264") }
        val chosen = order.firstOrNull { it in available } ?: "mpeg4"
        return when (chosen) {
            "h264_videotoolbox" -> listOf("-c:v", chosen, "-b:v", "10M", "-allow_sw", "1")
            "libx264" -> listOf("-c:v", chosen, "-preset", "veryfast", "-crf", "20")
            "h264_mf" -> listOf("-c:v", chosen, "-b:v", "10M")
            else -> listOf("-c:v", "mpeg4", "-q:v", "3")
        } + listOf("-pix_fmt", "yuv420p")
    }

    fun command(ffmpeg: String, input: CameraInput, record: File?, encoder: List<String>): List<String> {
        val out = ArrayList<String>()
        out += listOf(ffmpeg, "-hide_banner", "-loglevel", "error")
        out += input.args()
        out += listOf("-map", "0:v", "-an", "-vf", "fps=15,scale='min(1920,iw)':-2:flags=fast_bilinear", "-f", "image2pipe", "-c:v", "mjpeg", "-q:v", "4", "pipe:1")
        if (record != null) {
            out += listOf("-map", "0:v")
            input.audioMap?.let { out += listOf("-map", it) }
            out += encoder
            out += if (input.audio != null) listOf("-c:a", "aac", "-b:a", "160k") else listOf("-an")
            out += listOf("-y", record.path)
        }
        return out
    }
}

class JpegSplitter(private val onFrame: (ByteArray) -> Unit) {
    private var buffer = ByteArray(1 shl 18)
    private var size = 0

    fun feed(data: ByteArray, count: Int) {
        if (size + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + count))
        System.arraycopy(data, 0, buffer, size, count)
        size += count
        while (true) {
            val start = find(0xD8, 0)
            if (start < 0) {
                if (size > 1) { buffer[0] = buffer[size - 1]; size = 1 }
                return
            }
            val end = find(0xD9, start + 2)
            if (end < 0) {
                if (start > 0) { System.arraycopy(buffer, start, buffer, 0, size - start); size -= start }
                return
            }
            onFrame(buffer.copyOfRange(start, end + 2))
            System.arraycopy(buffer, end + 2, buffer, 0, size - end - 2)
            size -= end + 2
        }
    }

    private fun find(second: Int, from: Int): Int {
        var i = from
        while (i < size - 1) {
            if (buffer[i] == 0xFF.toByte() && buffer[i + 1] == second.toByte()) return i
            i++
        }
        return -1
    }
}

class DeskCameraSession(command: List<String>, private val onFrame: (ByteArray) -> Unit, private val onEnd: (Int, String) -> Unit) {
    private val process: Process = ProcessBuilder(command).start()
    private val notes = ArrayDeque<String>()
    @Volatile var frames = 0
        private set
    @Volatile var stopping = false
        private set

    init {
        Thread({
            val splitter = JpegSplitter { frames++; onFrame(it) }
            val buffer = ByteArray(1 shl 16)
            runCatching {
                process.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        splitter.feed(buffer, n)
                    }
                }
            }
        }, "am-camera").apply { isDaemon = true }.start()
        Thread({
            runCatching { process.errorStream.bufferedReader().forEachLine { line -> synchronized(notes) { notes.addLast(line); if (notes.size > 12) notes.removeFirst() } } }
        }, "am-camera-log").apply { isDaemon = true }.start()
        Thread({
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            onEnd(code, synchronized(notes) { notes.joinToString("\n") })
        }, "am-camera-wait").apply { isDaemon = true }.start()
    }

    fun finish(timeoutMs: Long = 8000): Boolean {
        stopping = true
        runCatching { process.outputStream.apply { write("q\n".toByteArray()); flush() } }
        val done = runCatching { process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!done) process.destroyForcibly()
        return done
    }

    fun stop() {
        stopping = true
        process.destroy()
    }
}
