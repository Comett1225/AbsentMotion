package app.absentmotion.ui.camera

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskOs
import app.absentmotion.platform.Hint
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.home.DeskButton
import app.absentmotion.ui.home.DeskDialogHeader
import app.absentmotion.ui.home.DeskDropdown
import app.absentmotion.ui.home.DeskModal
import app.absentmotion.ui.home.deskPress
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class CameraPhase { Starting, Live, NoCamera, Failed }

class DeskCameraController(private val ffmpeg: String, private val folder: File, private val scope: CoroutineScope) {
    var devices by mutableStateOf<CameraDevices?>(null)
        private set
    var video by mutableStateOf<CameraDevice?>(null)
        private set
    var audio by mutableStateOf<CameraDevice?>(null)
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var phase by mutableStateOf(CameraPhase.Starting)
        private set
    var problem by mutableStateOf("")
        private set
    var recording by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var startedAt by mutableLongStateOf(0L)
        private set
    @Volatile private var latest: ByteArray? = null
    @Volatile private var session: DeskCameraSession? = null
    @Volatile private var generation = 0
    private var encoder: List<String> = emptyList()
    private var target: File? = null

    fun open() {
        scope.launch {
            val found = withContext(Dispatchers.IO) { encoder = DeskCameraSource.videoEncoder(DeskCameraSource.encoders(ffmpeg)); DeskCameraSource.list(ffmpeg) }
            devices = found
            video = found.video.firstOrNull()
            audio = found.audio.firstOrNull()
            if (video == null) phase = CameraPhase.NoCamera else begin(null)
        }
    }

    fun choose(device: CameraDevice) {
        if (recording || busy) return
        video = device
        scope.launch { begin(null) }
    }

    private suspend fun begin(record: File?) {
        val chosen = video ?: return
        val old = session
        session = null
        generation++
        val mine = generation
        old?.stop()
        if (old != null) delay(350)
        if (record == null) { phase = CameraPhase.Starting; frame = null; latest = null }
        try {
            val input = CameraInput(chosen, if (record != null) audio else null)
            session = DeskCameraSession(DeskCameraSource.command(ffmpeg, input, record, encoder), { bytes -> if (generation == mine) show(bytes) }) { _, log ->
                if (generation == mine && session?.stopping != true) scope.launch { fail(log, record != null) }
            }
        } catch (e: java.io.IOException) {
            problem = e.message.orEmpty()
            phase = CameraPhase.Failed
        }
    }

    private fun show(bytes: ByteArray) {
        latest = bytes
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()?.let { frame = it; phase = CameraPhase.Live }
    }

    private fun fail(log: String, wasRecording: Boolean) {
        problem = log
        if (wasRecording) {
            recording = false
            target?.delete()
            target = null
        }
        phase = CameraPhase.Failed
    }

    fun retry() { scope.launch { begin(null) } }

    fun photo(): File? {
        val bytes = latest ?: return null
        folder.mkdirs()
        return File(folder, "photo-${System.currentTimeMillis()}.jpg").also { it.writeBytes(bytes) }
    }

    fun startRecording() {
        if (recording || busy || phase != CameraPhase.Live) return
        folder.mkdirs()
        val file = File(folder, "video-${System.currentTimeMillis()}.mp4")
        target = file
        recording = true
        startedAt = System.currentTimeMillis()
        scope.launch { begin(file) }
    }

    suspend fun stopRecording(): File? {
        val file = target ?: return null
        busy = true
        try {
            val active = session
            session = null
            generation++
            withContext(Dispatchers.IO) { active?.finish() }
            recording = false
            target = null
            val kept = file.takeIf { it.isFile && it.length() > 0 }
            if (kept == null) file.delete()
            begin(null)
            return kept
        } finally { busy = false }
    }

    fun close() {
        generation++
        session?.stop()
        session = null
        if (recording) { target?.delete(); target = null; recording = false }
    }
}

@Composable
fun CameraScreen(aspect: Float, onClose: () -> Unit, onCaptured: (Uri, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val camera = remember { DeskCameraController(DeskMedia.ffmpeg, File(context.cacheDir, "camera"), scope) }
    var video by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { camera.open() }
    DisposableEffect(Unit) { onDispose { camera.close() } }
    LaunchedEffect(camera.recording) {
        seconds = 0
        while (camera.recording) { seconds = ((System.currentTimeMillis() - camera.startedAt) / 1000).toInt(); delay(250) }
    }
    fun shutter() {
        if (camera.phase != CameraPhase.Live) return
        if (!video) camera.photo()?.let { onCaptured(Uri.fromFile(it), false) }
        else if (camera.recording) scope.launch { camera.stopRecording()?.let { onCaptured(Uri.fromFile(it), true) } }
        else camera.startRecording()
    }
    DeskModal(onClose, 780.dp, onEnter = ::shutter, dismissOnScrim = false) {
        DeskDialogHeader(stringResource(R.string.camera), onClose)
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black), Alignment.Center) {
                camera.frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                when (camera.phase) {
                    CameraPhase.Starting -> Note(stringResource(R.string.desk_camera_starting))
                    CameraPhase.NoCamera -> Note(stringResource(R.string.desk_camera_none))
                    CameraPhase.Failed -> Column(Modifier.padding(24.dp), Arrangement.spacedBy(12.dp), Alignment.CenterHorizontally) {
                        Note(stringResource(R.string.desk_camera_failed))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (DeskOs.mac || DeskOs.windows) DeskButton(stringResource(R.string.camera_open_settings), { DeskOs.openCameraSettings() })
                            DeskButton(stringResource(R.string.home_save_failed_retry), camera::retry, primary = true)
                        }
                    }
                    CameraPhase.Live -> Unit
                }
                if (camera.recording) Row(Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
                    Text("%02d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60), Modifier.padding(start = 7.dp), color = Color.White, fontSize = 13.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val found = camera.devices
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeskDropdown(
                        camera.video?.name ?: stringResource(R.string.camera), found?.video.orEmpty(), { it.name }, camera.video, camera::choose, Modifier.fillMaxWidth(),
                        enabled = !camera.recording && (found?.video?.size ?: 0) > 1,
                    )
                    DeskDropdown(
                        camera.audio?.name ?: stringResource(R.string.desk_camera_no_sound), listOf<CameraDevice?>(null) + found?.audio.orEmpty(),
                        { it?.name ?: stringResource(R.string.desk_camera_no_sound) }, camera.audio, { camera.audio = it }, Modifier.fillMaxWidth(),
                        enabled = !camera.recording && video && found?.audio?.isNotEmpty() == true,
                    )
                }
                Row(Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Am.HomeLine, RoundedCornerShape(10.dp))) {
                    Mode(stringResource(R.string.camera_photo), !video, !camera.recording) { video = false }
                    Mode(stringResource(R.string.camera_video), video, !camera.recording) { video = true }
                }
                Spacer(Modifier.width(8.dp))
                val label = stringResource(if (!video) R.string.camera_take_photo else if (camera.recording) R.string.desk_camera_stop_recording else R.string.camera_start_recording)
                Hint(label) {
                    Box(
                        Modifier.size(64.dp).semantics { contentDescription = label }.alpha(if (camera.phase == CameraPhase.Live && !camera.busy) 1f else 0.4f).clip(CircleShape).border(3.dp, Am.Navy, CircleShape).deskPress(32.dp)
                            .clickable(enabled = camera.phase == CameraPhase.Live && !camera.busy, onClick = ::shutter).padding(7.dp),
                        Alignment.Center,
                    ) {
                        Box(Modifier.fillMaxSize().clip(if (camera.recording) RoundedCornerShape(10.dp) else CircleShape).background(if (video) Color(0xFFFF3B30) else Am.Navy))
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(horizontal = 24.dp), color = Color.White, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
}

@Composable
private fun Mode(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).background(if (selected) Am.Navy else Color.Transparent).deskPress().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        Alignment.Center,
    ) { Text(label, color = if (selected) Am.OnNavy else Am.HomeInk, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

internal fun cameraRatio(aspect: Float, portrait: Boolean): Int = 0

internal fun cameraBlocked(granted: Boolean, rationale: Boolean, refusedBefore: Boolean): Boolean = !granted && !rationale && refusedBefore
