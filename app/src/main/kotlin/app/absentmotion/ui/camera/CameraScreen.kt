package app.absentmotion.ui.camera

import android.os.Build
import android.Manifest
import androidx.annotation.StringRes
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.ui.editor.panels.buttonLabel

private enum class Flash(@StringRes val label: Int, val mode: Int) {
    Off(R.string.camera_off, ImageCapture.FLASH_MODE_OFF), Auto(R.string.camera_auto, ImageCapture.FLASH_MODE_AUTO), On(R.string.camera_on, ImageCapture.FLASH_MODE_ON),
}

private val Timers = listOf(0, 3, 10)
private val RecordRed = Color(0xFFE53935)

private val KeptVideo = setOf(
    VideoRecordEvent.Finalize.ERROR_NONE, VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
    VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED, VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
    VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
)

internal fun cameraRatio(aspect: Float, portrait: Boolean): Int {
    fun cover(camera: Float) = minOf(aspect / camera, camera / aspect)
    val wide = if (portrait) 9f / 16f else 16f / 9f
    val standard = if (portrait) 3f / 4f else 4f / 3f
    return if (cover(wide) > cover(standard)) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
}

private class CameraUi {
    var video by mutableStateOf(false)
    var front by mutableStateOf(false)
    var flash by mutableStateOf(Flash.Off)
    var torch by mutableStateOf(false)
    var timer by mutableIntStateOf(0)
    var grid by mutableStateOf(false)
    var recording by mutableStateOf<Recording?>(null)
    var paused by mutableStateOf(false)
    var elapsedNs by mutableLongStateOf(0L)
    var countdown by mutableIntStateOf(0)
    var counting: Job? = null
    var busy by mutableStateOf(false)
    var closed = false
}

@Composable
fun CameraScreen(aspect: Float, onClose: () -> Unit, onCaptured: (Uri, Boolean) -> Unit) {
    val ui = remember { CameraUi() }
    fun back() {
        val counting = ui.counting
        when {
            ui.recording != null -> ui.recording?.stop()
            counting != null -> counting.cancel()
            else -> onClose()
        }
    }
    Dialog(::back, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let {
                val bars = WindowCompat.getInsetsController(it, it.decorView)
                bars.isAppearanceLightStatusBars = false
                bars.isAppearanceLightNavigationBars = false
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Permitted(onClose) { mic -> CameraBody(ui, aspect, mic, onClose, onCaptured) }
        }
    }
}

internal fun cameraBlocked(granted: Boolean, rationale: Boolean, refusedBefore: Boolean): Boolean = !granted && !rationale && refusedBefore

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun Permitted(onClose: () -> Unit, content: @Composable (mic: Boolean) -> Unit) {
    val context = LocalContext.current
    fun has(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
    var camera by remember { mutableStateOf(has(Manifest.permission.CAMERA)) }
    var mic by remember { mutableStateOf(has(Manifest.permission.RECORD_AUDIO)) }
    var blocked by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("camera", Context.MODE_PRIVATE) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        camera = has(Manifest.permission.CAMERA)
        mic = has(Manifest.permission.RECORD_AUDIO)
        val rationale = context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
        blocked = cameraBlocked(camera, rationale, prefs.getBoolean("refused", false))
        prefs.edit().putBoolean("refused", !camera).apply()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        camera = has(Manifest.permission.CAMERA)
        mic = has(Manifest.permission.RECORD_AUDIO)
    }
    if (camera) {
        content(mic)
        return
    }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(CameraIcons.Camera, null, Modifier.size(48.dp), tint = Color.White)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.camera_access), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(if (blocked) R.string.camera_permission_blocked else R.string.camera_permission_explain),
            Modifier.widthIn(max = 420.dp), color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Am.Accent).clickable {
                if (blocked) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                else request.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }.padding(horizontal = 24.dp),
            Alignment.Center,
        ) { Text(stringResource(if (blocked) R.string.camera_open_settings else R.string.camera_allow), color = Am.OnAccent, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
        Box(Modifier.height(48.dp).clickable(onClick = onClose).padding(horizontal = 24.dp), Alignment.Center) {
            Text(stringResource(R.string.action_close), color = Color.White, fontSize = 16.sp)
        }
    }
}

private fun mediaValues(mime: String, extension: String) = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, "AbsentMotion_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + extension)
    put(MediaStore.MediaColumns.MIME_TYPE, mime)
    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/AbsentMotion")
}

@SuppressLint("MissingPermission", "ClickableViewAccessibility")
@Composable
private fun CameraBody(ui: CameraUi, aspect: Float, mic: Boolean, onClose: () -> Unit, onCaptured: (Uri, Boolean) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val executor = remember { ContextCompat.getMainExecutor(context) }
    val controller = remember { LifecycleCameraController(context) }
    var lenses by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var tap by remember { mutableStateOf(Offset.Zero) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var focusCount by remember { mutableIntStateOf(0) }
    var blink by remember { mutableIntStateOf(0) }

    DisposableEffect(owner) {
        controller.bindToLifecycle(owner)
        controller.initializationFuture.addListener({
            val back = runCatching { controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
            val front = runCatching { controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
            if (!back && front) ui.front = true
            lenses = back to front
        }, executor)
        val zoomWatch = Observer<ZoomState> {
            zoom = it.zoomRatio
            hasFlash = controller.cameraInfo?.hasFlashUnit() == true
        }
        val focusWatch = Observer<androidx.camera.view.TapToFocusInfo> {
            if (it.focusState == CameraController.TAP_TO_FOCUS_STARTED) { focusAt = tap; focusCount++ }
        }
        controller.zoomState.observe(owner, zoomWatch)
        controller.tapToFocusInfoState.observe(owner, focusWatch)
        onDispose {
            ui.closed = true
            ui.counting?.cancel()
            ui.recording?.stop()
            controller.zoomState.removeObserver(zoomWatch)
            controller.tapToFocusInfoState.removeObserver(focusWatch)
            controller.unbind()
        }
    }
    LaunchedEffect(ui.video) {
        controller.setEnabledUseCases(if (ui.video) CameraController.VIDEO_CAPTURE else CameraController.IMAGE_CAPTURE)
    }
    LaunchedEffect(ui.front) {
        controller.cameraSelector = if (ui.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }
    LaunchedEffect(ui.flash) { controller.imageCaptureFlashMode = ui.flash.mode }
    LaunchedEffect(ui.torch, ui.video, hasFlash) { if (hasFlash) controller.enableTorch(ui.torch && ui.video) }
    LaunchedEffect(focusCount) { delay(1200); focusAt = null }

    fun failed(@StringRes text: Int) = Toast.makeText(context, context.getString(text), Toast.LENGTH_LONG).show()

    fun takePhoto() {
        ui.busy = true
        blink++
        val options = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mediaValues("image/jpeg", "jpg")).build()
        try {
            controller.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = output.savedUri
                    if (uri == null) { ui.busy = false; failed(R.string.camera_error_save_photo) } else if (!ui.closed) onCaptured(uri, false)
                }

                override fun onError(e: ImageCaptureException) {
                    ui.busy = false
                    Log.w("AMCamera", "photo failed", e)
                    failed(R.string.camera_error_photo)
                }
            })
        } catch (e: RuntimeException) {
            ui.busy = false
            Log.w("AMCamera", "cannot take a photo", e)
            failed(R.string.camera_error_photo)
        }
    }

    fun record() {
        val audio = if (mic && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) AudioConfig.create(true)
            else AudioConfig.AUDIO_DISABLED
        val options = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(mediaValues("video/mp4", "mp4")).build()
        ui.recording = try {
            controller.startRecording(options, audio, executor) { event ->
                when (event) {
                    is VideoRecordEvent.Status -> ui.elapsedNs = event.recordingStats.recordedDurationNanos
                    is VideoRecordEvent.Pause -> ui.paused = true
                    is VideoRecordEvent.Resume -> ui.paused = false
                    is VideoRecordEvent.Finalize -> {
                        ui.recording = null
                        ui.paused = false
                        ui.elapsedNs = 0
                        val uri = event.outputResults.outputUri
                        if (uri != Uri.EMPTY && event.error in KeptVideo) { ui.busy = true; if (!ui.closed) onCaptured(uri, true) }
                        else {
                            Log.w("AMCamera", "video failed ${event.error}", event.cause)
                            failed(R.string.camera_error_save_video)
                        }
                    }
                }
            }
        } catch (e: RuntimeException) {
            Log.w("AMCamera", "cannot record", e)
            failed(R.string.camera_error_start_recording)
            null
        }
    }

    fun shutter() {
        val counting = ui.counting
        when {
            ui.recording != null -> ui.recording?.stop()
            counting != null -> counting.cancel()
            ui.busy -> Unit
            else -> ui.counting = scope.launch {
                try {
                    for (s in ui.timer downTo 1) {
                        ui.countdown = s
                        delay(1000)
                    }
                    ui.countdown = 0
                    if (ui.video) record() else takePhoto()
                } finally {
                    ui.countdown = 0
                    ui.counting = null
                }
            }
        }
    }

    val recording = ui.recording != null
    val locked = recording || ui.countdown > 0
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clickable { if (recording) ui.recording?.stop() else onClose() }, Alignment.Center) {
                Icon(ChromeIcons.CloseThick, stringResource(R.string.camera_close), Modifier.size(26.dp), tint = Color.White)
            }
            Box(Modifier.weight(1f), Alignment.Center) {
                if (recording) Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).alpha(if (ui.paused) 0.4f else 1f).background(RecordRed, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    val s = ui.elapsedNs / 1_000_000_000L
                    Text(String.format(Locale.US, "%02d:%02d", s / 60, s % 60), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
            val flashIcon = if (ui.video) (if (ui.torch) CameraIcons.Flash else CameraIcons.FlashOff) else if (ui.flash == Flash.Off) CameraIcons.FlashOff else CameraIcons.Flash
            TopButton(flashIcon, stringResource(if (ui.video) (if (ui.torch) R.string.camera_on else R.string.camera_off) else ui.flash.label), stringResource(R.string.camera_flash), enabled = hasFlash) {
                if (ui.video) ui.torch = !ui.torch else ui.flash = Flash.entries[(ui.flash.ordinal + 1) % Flash.entries.size]
            }
            TopButton(if (ui.timer == 0) CameraIcons.TimerOff else CameraIcons.Timer, if (ui.timer == 0) stringResource(R.string.camera_off) else "${ui.timer} s", stringResource(R.string.camera_timer), enabled = !locked) {
                ui.timer = Timers[(Timers.indexOf(ui.timer) + 1) % Timers.size]
            }
            TopButton(CameraIcons.Grid, stringResource(if (ui.grid) R.string.camera_on else R.string.camera_off), stringResource(R.string.camera_grid)) { ui.grid = !ui.grid }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
            val ratio = cameraRatio(aspect, maxHeight > maxWidth)
            LaunchedEffect(ratio) {
                val strategy = if (ratio == AspectRatio.RATIO_16_9) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                val selector = ResolutionSelector.Builder().setAspectRatioStrategy(strategy).build()
                controller.previewResolutionSelector = selector
                controller.imageCaptureResolutionSelector = selector
            }
            val boxWidth = minOf(maxWidth, maxHeight * aspect)
            val boxHeight = boxWidth / aspect
            Box(Modifier.size(boxWidth, boxHeight).clip(RoundedCornerShape(4.dp))) {
                AndroidView({ ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        keepScreenOn = true
                        setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP) tap = Offset(e.x, e.y); false }
                        this.controller = controller
                    }
                }, Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    if (ui.grid) {
                        val line = Color.White.copy(alpha = 0.45f)
                        for (i in 1..2) {
                            drawLine(line, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.dp.toPx())
                            drawLine(line, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.dp.toPx())
                        }
                    }
                    focusAt?.let { drawCircle(Color.White, 34.dp.toPx(), it, style = Stroke(2.dp.toPx())) }
                }
                if (ui.countdown > 0) Text("${ui.countdown}", Modifier.align(Alignment.Center), color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold)
                if (zoom > 1.01f) Text(
                    String.format(Locale.US, "%.1fx", zoom),
                    Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    color = Color.White, fontSize = 14.sp,
                )
                if (ui.video && !mic) Text(
                    stringResource(R.string.no_sound_microphone_access_is_off),
                    Modifier.align(Alignment.TopCenter).padding(10.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    color = Color.White, fontSize = 13.sp,
                )
                if (lenses?.let { !it.first && !it.second } == true) Text(
                    stringResource(R.string.no_camera_was_found_on_this), Modifier.align(Alignment.Center).padding(16.dp), color = Color.White, fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
                Blink(blink)
            }
        }
        Row(Modifier.fillMaxWidth().height(40.dp).alpha(if (locked) 0.35f else 1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ModeTab(stringResource(R.string.camera_photo), !ui.video, !locked) { ui.video = false }
            Spacer(Modifier.width(24.dp))
            ModeTab(stringResource(R.string.camera_video), ui.video, !locked) { ui.video = true }
        }
        Row(Modifier.fillMaxWidth().height(104.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp), Alignment.Center) {
                if (recording) Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
                        .clickable { if (ui.paused) ui.recording?.resume() else ui.recording?.pause() },
                    Alignment.Center,
                ) { Icon(if (ui.paused) ChromeIcons.Play else ChromeIcons.Pause, stringResource(if (ui.paused) R.string.camera_resume_recording else R.string.camera_pause_recording), Modifier.size(26.dp), tint = Color.White) }
            }
            Shutter(ui.video, recording, ui.busy || ui.countdown > 0, ready = recording || lenses?.let { it.first || it.second } == true, noCamera = lenses?.let { !it.first && !it.second } == true, ::shutter)
            val canSwitch = lenses?.let { it.first && it.second } == true && !locked
            Box(
                Modifier.size(64.dp).alpha(if (canSwitch) 1f else 0.35f).clip(CircleShape).clickable(enabled = canSwitch) { ui.front = !ui.front },
                Alignment.Center,
            ) { Icon(CameraIcons.Switch, stringResource(R.string.camera_switch), Modifier.size(30.dp), tint = Color.White) }
        }
    }
}

@Composable
private fun Blink(count: Int) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(count) {
        if (count == 0) return@LaunchedEffect
        shown = true
        delay(90)
        shown = false
    }
    if (shown) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
}

@Composable
private fun TopButton(icon: ImageVector, caption: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.size(56.dp).alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, description, Modifier.size(22.dp), tint = Color.White)
        Text(caption, color = Color.White, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.height(40.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp), Alignment.Center) {
        Text(label, color = if (selected) Am.Accent else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Shutter(video: Boolean, recording: Boolean, waiting: Boolean, ready: Boolean, noCamera: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (recording) R.string.stop else if (video) R.string.camera_start_recording else R.string.camera_take_photo)
    Box(
        Modifier.size(76.dp).alpha(if (noCamera) 0.35f else 1f).border(4.dp, Color.White, CircleShape).padding(8.dp).clip(CircleShape).buttonLabel(label).clickable(enabled = ready, onClick = onClick)
            .then(if (recording) Modifier else Modifier.alpha(if (waiting) 0.6f else 1f)),
        Alignment.Center,
    ) {
        when {
            recording -> Box(Modifier.size(26.dp).background(RecordRed, RoundedCornerShape(5.dp)))
            video -> Box(Modifier.fillMaxSize().background(RecordRed, CircleShape))
            else -> Box(Modifier.fillMaxSize().background(Color.White, CircleShape))
        }
    }
}
