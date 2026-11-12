package app.absentmotion.ui.export

import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.annotation.StringRes
import app.absentmotion.ui.home.DeskModals
import app.absentmotion.ui.home.deskPress
import app.absentmotion.export.verificationLine
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.asImageBitmap
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.deskVerticalScroll
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.ai.AiSmooth
import app.absentmotion.i18n.Ltr
import app.absentmotion.i18n.UiText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import app.absentmotion.i18n.text
import app.absentmotion.i18n.txt
import app.absentmotion.platform.Log
import app.absentmotion.model.mediaUris
import app.absentmotion.platform.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.produceState
import app.absentmotion.ui.home.Outdated
import app.absentmotion.ui.home.OutdatedHint
import app.absentmotion.export.GifSample
import app.absentmotion.export.frameCount
import app.absentmotion.export.gifTimeMs
import app.absentmotion.export.measureGif
import app.absentmotion.export.measureSequenceFrame
import app.absentmotion.export.measureVideoDraw
import app.absentmotion.export.withMissing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.absentmotion.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.AudioCodec
import app.absentmotion.export.AudioFormat
import app.absentmotion.export.AudioSettings
import app.absentmotion.export.Choice
import app.absentmotion.export.DeviceCapabilities
import app.absentmotion.export.ExportPrefs
import app.absentmotion.export.ExportRun
import app.absentmotion.export.NotifyAsk
import app.absentmotion.export.imageProblem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import app.absentmotion.export.withSquare
import app.absentmotion.export.square
import kotlinx.coroutines.flow.first
import app.absentmotion.export.ExportSizes
import app.absentmotion.export.ExportTimes
import app.absentmotion.export.StorageStatus
import app.absentmotion.export.Support
import app.absentmotion.export.ThermalLevel
import app.absentmotion.export.ThermalMonitor
import app.absentmotion.export.storageStatus
import app.absentmotion.export.ExportAudio
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportName
import app.absentmotion.export.randomNameAllowed
import app.absentmotion.export.ExportResult
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.Exporter
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.MotionBlur
import app.absentmotion.export.SmoothLevel
import app.absentmotion.export.GifFrameRates
import app.absentmotion.export.GifResolutions
import app.absentmotion.export.BackgroundBlack
import app.absentmotion.export.BackgroundWhite
import app.absentmotion.export.ImageKind
import app.absentmotion.export.ImageQualities
import app.absentmotion.export.MediaStoreExport
import app.absentmotion.export.VideoFrameRates
import app.absentmotion.export.VideoResolutions
import app.absentmotion.export.audioCodecChoices
import app.absentmotion.export.audioCodecsFor
import app.absentmotion.export.audioFormatChoices
import app.absentmotion.export.audioRate
import app.absentmotion.export.audioSummary
import app.absentmotion.export.betterChoice
import app.absentmotion.export.bitrateChoices
import app.absentmotion.export.fitBitrate
import app.absentmotion.export.channelsLabel
import app.absentmotion.export.codecChoices
import app.absentmotion.export.codecWarning
import app.absentmotion.export.containerChoices
import app.absentmotion.export.estimateBytes
import app.absentmotion.export.exportBlock
import app.absentmotion.export.exportHeatText
import app.absentmotion.export.exportSize
import app.absentmotion.export.formatBytes
import app.absentmotion.export.freeSpace
import app.absentmotion.export.khz
import app.absentmotion.export.listed
import app.absentmotion.export.optionNote
import app.absentmotion.export.resolutionChoices
import app.absentmotion.export.resolutionLabel
import app.absentmotion.export.sampleRateChoices
import app.absentmotion.export.shortName
import app.absentmotion.export.suggestionLabel
import app.absentmotion.export.exportSecondsLeft
import app.absentmotion.export.timeLabel
import app.absentmotion.export.videoBitrate
import app.absentmotion.export.withContainer
import app.absentmotion.platform.rememberStorageAccess
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.home.ReportDialog
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Motion
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.animatedColor
import app.absentmotion.ui.theme.enterFade
import app.absentmotion.ui.theme.presence
import app.absentmotion.ui.theme.presenceScrim
import app.absentmotion.library.TemplateStore
import app.absentmotion.audio.AudioClip
import app.absentmotion.audio.OfflineMix
import kotlinx.coroutines.CancellationException
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.aspectRatio
import app.absentmotion.export.problem
import app.absentmotion.export.communityBytes

private val Navy get() = Am.Navy
private val NoteGrey get() = Am.HomeDim
private val ValueGrey get() = Am.HomeDim
private val Line get() = Am.HomeLine
private val Scrim get() = Am.Scrim
private val Alert get() = Am.HomeAccent

private const val DESK = app.absentmotion.platform.PlatformFeatures.desktop
private val GroupGap = 18.dp
private val FieldGap = 6.dp
private const val Dimmed = 0.38f

private val SheetKinds = setOf(ExportKind.Gif, ExportKind.Sequence, ExportKind.FrameAs)

private sealed interface Phase {
    data object Idle : Phase
    class Running(val kind: ExportKind, val startNs: Long, val job: Job, val plannedMs: Long? = null) : Phase
    class Done(val kind: ExportKind, val result: ExportResult) : Phase
}

private class Failure(@StringRes val title: Int, val text: UiText, val action: UiText? = null, val proceed: (() -> Unit)? = null, val hint: Boolean = false)

@Composable
internal fun ExportScreen(scene: Scene, frame: Int, engine: EngineThread, appSettings: app.absentmotion.library.AppSettings, fromProjectList: Boolean, outdated: Outdated? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bundles = remember(appSettings.bundlesFolder) { app.absentmotion.platform.bundleStore(context, appSettings.bundlesFolder) }
    val exporter = remember(bundles) { Exporter(context, engine, MediaStoreExport(context), bundles = bundles) }
    val kinds = remember { listOf(ExportKind.Video, ExportKind.Audio, ExportKind.Package, ExportKind.Frame, ExportKind.FrameAs, ExportKind.Sequence, ExportKind.Gif) + if (fromProjectList) listOf(ExportKind.Xml) else emptyList() }
    var kind by remember { mutableStateOf(ExportKind.Video) }
    val exportName = remember(scene.title) { ExportName(scene.title) }
    val prefs = remember { ExportPrefs(context) }
    val settings = remember { mutableStateMapOf<ExportKind, ExportSettings>().apply { ExportKind.entries.forEach { put(it, prefs.load(it, scene)) } } }
    val stored = remember { HashMap(settings.toMap()) }
    val persist = { settings.forEach { (k, v) -> if (stored[k] != v) { prefs.save(k, v); stored[k] = v } } }
    LaunchedEffect(settings) {
        snapshotFlow { settings.toMap() }.collectLatest {
            delay(SaveDelayMs)
            persist()
        }
    }
    DisposableEffect(settings) { onDispose { persist() } }
    var page by remember { mutableStateOf<ExportKind?>(null) }
    var community by remember { mutableStateOf<app.absentmotion.export.CommunityDetails?>(null) }
    var lastPage by remember { mutableStateOf(ExportKind.Gif) }
    var audioPage by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf<UiText?>(null) }
    var lastPhase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var packageInfo by remember { mutableStateOf(false) }
    var asTemplate by remember { mutableStateOf(false) }
    var templateInfo by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Failure?>(null) }
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    page?.let { if (it in SheetKinds) lastPage = it }
    if (phase !is Phase.Idle) lastPhase = phase
    var progress by remember { mutableFloatStateOf(0f) }
    var note by remember { mutableStateOf<UiText?>(null) }
    DisposableEffect(engine) {
        engine.holdVideo()
        onDispose { engine.releaseVideo() }
    }
    val anyAudio = remember(scene) { ExportAudio.mixFor != null && AudioClip.of(scene).isNotEmpty() }
    val hasAudio by produceState(anyAudio, scene) {
        value = anyAudio
        if (anyAudio) value = withContext(Dispatchers.IO) { OfflineMix.audible(context, scene).isNotEmpty() }
    }
    val gifSettings = settings.getValue(ExportKind.Gif)
    val gifSample by produceState<GifSample?>(null, kind, scene, gifSettings, phase) {
        value = null
        if (kind == ExportKind.Gif && phase is Phase.Idle) {
            try { delay(MeasureDelayMs); value = measureGif(engine, scene, gifSettings) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("AMExport", "Cannot sample GIF size", e) }
        }
    }
    val videoSettings = settings.getValue(ExportKind.Video)
    val drawMs by produceState<Double?>(null, kind, scene, videoSettings.shortSide, videoSettings.smoothing, phase is Phase.Idle) {
        value = null
        if (kind == ExportKind.Video && phase is Phase.Idle) {
            try { delay(MeasureDelayMs); value = measureVideoDraw(engine, scene, videoSettings) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("AMExport", "Cannot sample the drawing time", e) }
        }
    }
    val sequenceSettings = settings.getValue(ExportKind.Sequence)
    val sequenceMs by produceState<Double?>(null, kind, scene, sequenceSettings.shortSide, sequenceSettings.smoothing, sequenceSettings.image, phase is Phase.Idle) {
        value = null
        if (kind == ExportKind.Sequence && phase is Phase.Idle) {
            try { delay(MeasureDelayMs); value = measureSequenceFrame(engine, scene, sequenceSettings) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("AMExport", "Cannot sample the drawing time", e) }
        }
    }
    var free by remember { mutableLongStateOf(freeSpace()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) free = freeSpace()
            if (event == Lifecycle.Event.ON_START) ExportRun.visible = true
            if (event == Lifecycle.Event.ON_STOP) ExportRun.visible = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); if (!ExportRun.running) ExportRun.visible = true }
    }
    LaunchedEffect(phase) { free = freeSpace() }

    val caps = remember { DeviceCapabilities.of(context) }
    val report by caps.report.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var reportTried by remember { mutableStateOf(false) }
    LaunchedEffect(caps) {
        try { caps.load() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w("AMExport", "Cannot read the encoders", e) }
    }
    val videoSize = exportSize(scene, settings.getValue(ExportKind.Video).shortSide, even = true)
    LaunchedEffect(report != null, videoSize) { if (report != null) caps.confirmSize(videoSize.first, videoSize.second) }
    val sizes = remember { ExportSizes(context) }
    val rawEstimate = if (kind == ExportKind.Gif) gifSample?.bytes ?: -1L else remember(kind, scene, settings.getValue(kind), hasAudio, report) {
        val s = settings.getValue(kind)
        val bitrates = if (kind == ExportKind.Video) exportSize(scene, s.shortSide, even = true).let { (w, h) ->
            report?.let { r -> r.encodeSize(s, w, h).let { (ew, eh) -> r.encoderFor(s.codec, ew, eh)?.bitrates } }
        } else null
        estimateBytes(kind, scene, s, hasAudio, bitrates)
    }
    val sizeKey = ExportSizes.key(kind, settings.getValue(kind))
    val estimate = remember(rawEstimate, sizeKey, phase is Phase.Idle) { if (rawEstimate < 0 || sizeKey == null) rawEstimate else (rawEstimate * sizes.ratio(sizeKey)).toLong() }
    val check: (ExportSettings) -> Support = remember(report, scene, hasAudio) { { caps.check(it, scene, hasAudio) } }
    val hasEncoder: (String) -> Boolean = remember(report) { { mime -> report?.encodersFor(mime)?.isNotEmpty() ?: true } }
    val bitrateFits: (ExportSettings, Boolean) -> Boolean = remember(report) { { s, audioOnly -> report?.takesBitrate(s, audioOnly) ?: true } }
    val thermal = remember { ThermalMonitor.of(context) }
    DisposableEffect(thermal) {
        thermal.start()
        onDispose { thermal.stop() }
    }
    val heat by thermal.state.collectAsState()
    val times = remember { ExportTimes(context) }
    val video = settings.getValue(ExportKind.Video)
    val support = remember(video, check) { check(video) }
    val better = remember(video, support, check) { betterChoice(video, support, check) }
    val warning = codecWarning(video, support)?.takeIf { it != acknowledged || support is Support.Unsupported }
    val current = settings.getValue(kind)
    val modelMs = remember(kind, current, report, heat.level, estimate, phase is Phase.Idle, gifSample, drawMs, sequenceMs) {
        when (kind) {
            ExportKind.Video -> times.estimateMs(current, scene, report?.probe, heat.level, report, drawMs)
            ExportKind.Gif -> gifSample?.let { gifTimeMs(frameCount(scene, current.fps), it, times.estimateMs(current, scene, report?.probe, heat.level)) }
            ExportKind.Sequence -> times.estimateMs(current, scene, report?.probe, heat.level, null, sequenceMs)
            ExportKind.Package -> estimate.coerceAtLeast(0) / 100_000
            ExportKind.Audio -> scene.totalTime / if (current.audioFormat.lossless) 20L else 14L
            else -> 0L
        }
    }
    val timeMs = remember(modelMs, kind, phase is Phase.Idle) { modelMs?.let { m -> timeKey(kind)?.let { (m * sizes.ratio(it)).toLong() } ?: m } }
    val storage = if (estimate < 0) StorageStatus.Enough else storageStatus(estimate, free)
    val audioOnly = settings.getValue(ExportKind.Audio)
    val audioProblem = remember(report, audioOnly) { caps.checkAudioOnly(audioOnly) }
    val blocked = if (checking) txt(R.string.export_calculating) else exportBlock(storage, when {
        kind == ExportKind.Video -> support
        kind == ExportKind.Audio && audioProblem != null -> Support.Unsupported(audioProblem, null)
        else -> Support.Supported
    })
    val explain: (UiText) -> Unit = { failure = Failure(R.string.export_not_available, it) }

    app.absentmotion.platform.ExportBars(dark = false, running = phase is Phase.Running)
    app.absentmotion.ui.theme.ThemeFade.Hold(phase is Phase.Running)
    BackHandler {
        when (phase) {
            is Phase.Running -> if (DESK) onClose() else (phase as? Phase.Running)?.job?.cancel()
            is Phase.Done -> phase = Phase.Idle
            Phase.Idle -> when {
                audioPage -> audioPage = false
                page != null -> page = null
                else -> onClose()
            }
        }
    }

    fun useBetter() {
        better?.let { settings[ExportKind.Video] = it }
    }

    val storageAccess = rememberStorageAccess()
    val notifyAsk = rememberNotifyAsk()

    fun start(ignoreMissing: Boolean = false, aiReady: Boolean = false) {
        if (phase !is Phase.Idle || checking || !storageAccess.ready { start(ignoreMissing, aiReady) }) return
        if (DESK && ExportRun.running) {
            failure = Failure(R.string.export_failed_title, txt(R.string.desk_export_busy))
            return
        }
        if ((kind == ExportKind.Video || kind == ExportKind.Audio) && report == null && !reportTried) {
            reportTried = true
            checking = true
            scope.launch {
                try { caps.load() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w("AMExport", "Cannot read the encoders", e) }
                checking = false
                start(ignoreMissing, aiReady)
            }
            return
        }
        if (kind in BackgroundKinds && !notifyAsk.ready { start(ignoreMissing, aiReady) }) return
        val s = settings.getValue(kind)
        val aiLevel = s.interpolationLevel.takeIf { s.interpolation == FrameInterpolation.Ai && kind in AiKinds }
        if (aiLevel != null && !aiReady && !AiSmooth.installed(context, aiLevel)) {
            failure = Failure(R.string.ai_smooth_download_title, txt(R.string.ai_smooth_download_text, formatBytes(AiSmooth.downloadBytes(aiLevel))),
                txt(R.string.ai_download_and_start), proceed = { start(ignoreMissing, true) })
            return
        }
        if (kind == ExportKind.Package) community?.problem(scene)?.let {
            failure = Failure(R.string.export_failed_title, it)
            return
        }
        if (scene.layers.isEmpty() && kind != ExportKind.Xml && kind != ExportKind.Package) {
            failure = Failure(R.string.export_failed_title, txt(R.string.export_failed_empty))
            return
        }
        if (kind == ExportKind.Audio && !hasAudio) {
            failure = Failure(R.string.export_nothing_title, NoSound)
            return
        }
        val fresh = check(s)
        if (kind == ExportKind.Video && fresh is Support.Unsupported) {
            val fix = betterChoice(s, fresh, check)
            failure = Failure(R.string.export_not_supported_title, codecWarning(s, fresh) ?: txt(R.string.export_block_unsupported), fix?.let { suggestionLabel(s, it) },
                fix?.let { { settings[ExportKind.Video] = it } })
            return
        }
        if (kind == ExportKind.Audio) caps.checkAudioOnly(s)?.let {
            failure = Failure(R.string.export_not_supported_title, it)
            return
        }
        free = freeSpace()
        if (kind != ExportKind.Gif && storageStatus(estimate, free) is StorageStatus.NotEnough) {
            failure = Failure(R.string.export_storage_title, (storageStatus(estimate, free) as StorageStatus.NotEnough).message)
            return
        }
        val chosen = kind
        val estimated = rawEstimate
        val modelled = modelMs
        prefs.save(chosen, s)
        val alsoTemplate = asTemplate
        val name = exportName.next(chosen)
        progress = 0f
        note = null
        val background = chosen in BackgroundKinds
        Log.i("AMExport", "start ${chosen.name.lowercase()} estimated ${timeMs ?: -1} ms, drawing ${drawMs ?: -1.0} ms per frame")
        val job = app.absentmotion.platform.exportScope(scope).launch {
            var outcome: Boolean? = null
            try {
                var exported = scene
                if (chosen != ExportKind.Xml) {
                    val missing = withContext(Dispatchers.IO) {
                        scene.node.mediaUris().filter { uri ->
                            try { context.contentResolver.openAssetFileDescriptor(app.absentmotion.platform.Uri.parse(uri), "r")?.close() == null } catch (e: Exception) { true }
                        }
                    }
                    if (missing.isNotEmpty() && !ignoreMissing) {
                        val names = missing.map { scene.media(it)?.filename?.ifEmpty { null } ?: it }
                        failure = Failure(R.string.export_missing_title, txt(R.string.export_missing_text, names.joinToString("\n")), txt(R.string.export_anyway), proceed = { start(true, aiReady) })
                        phase = Phase.Idle
                        return@launch
                    }
                    if (chosen != ExportKind.Package) exported = scene.withMissing(missing.toSet())
                }
                var predicted = estimated
                if (chosen == ExportKind.Gif) {
                    val measured = measureGif(engine, exported, s).bytes
                    predicted = measured
                    free = freeSpace()
                    if (storageStatus(measured, free) is StorageStatus.NotEnough) {
                        failure = Failure(R.string.export_storage_title, txt(R.string.export_storage_text))
                        phase = Phase.Idle
                        return@launch
                    }
                }
                if (aiLevel != null && !AiSmooth.installed(context, aiLevel)) AiSmooth.download(context, aiLevel) { progress = it }
                val began = System.nanoTime()
                val result = (if (background) Exporter(context.applicationContext, app.absentmotion.platform.exportEngine(engine), MediaStoreExport(context.applicationContext), bundles = bundles) else exporter).export(chosen, exported, s, frame, name, { note = it }, community.takeIf { chosen == ExportKind.Package }) { progress = it; ExportRun.progress = it }
                outcome = true
                if (background) app.absentmotion.platform.exportDone(result)
                phase = Phase.Done(chosen, result)
                timeKey(chosen)?.let { key -> modelled?.let { sizes.record(key, it, (System.nanoTime() - began) / 1_000_000) } }
                ExportSizes.key(chosen, s)?.let { key ->
                    withContext(Dispatchers.IO) {
                        val written = result.uris.sumOf { uri ->
                            runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } }.getOrNull() ?: 0L
                        }
                        sizes.record(key, predicted, written)
                    }
                }
                if (alsoTemplate) withContext(NonCancellable) {
                    try {
                        TemplateStore(context).add(scene, scene.frameStart(frame), engine)
                    } catch (e: Exception) {
                        Log.w("AMExport", "could not store the template", e)
                        app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.export_template_failed), app.absentmotion.platform.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: CancellationException) {
                phase = Phase.Idle
                throw e
            } catch (e: Throwable) {
                Log.e("AMExport", "export failed", e)
                outcome = false
                phase = Phase.Idle
                failure = Failure(R.string.export_failed_title, txt(R.string.library_text_value, e.message ?: e.toString()), hint = true)
            } finally {
                if (background) ExportRun.end(outcome)
            }
        }
        phase = Phase.Running(chosen, System.nanoTime(), job, timeMs)
        if (background) ExportRun.begin(context, runningTitle(chosen)) { job.cancel() }
        if (background && !ExportRun.running) phase = Phase.Idle
    }

    val info: @Composable () -> Unit = { InfoBlock(free, estimate, timeMs, storage) }
    val naming: @Composable (ExportKind) -> Unit = { FileNameField(exportName, it) }
    val card = !DESK && app.absentmotion.ui.editor.isTablet(androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp)
    val bar: @Composable () -> Unit = { ExportBar(enabled = phase == Phase.Idle, blocked = blocked, pill = card, onClick = ::start) }

    if (DESK) DeskExport(
        scene, frame, fromProjectList, engine, kinds, kind, { kind = it }, hasAudio, settings, check, hasEncoder, bitrateFits, explain, { help = true }, exportName,
        warning, support is Support.SlowSoftwareOnly, better?.let { suggestionLabel(video, it) }, ::useBetter, { acknowledged = warning },
        free, estimate, timeMs, storage, blocked, asTemplate, { asTemplate = it }, { templateInfo = true }, { packageInfo = true }, { BundleFields(scene, engine, appSettings, community) { community = it } },
        phase, progress, heat.level, note, { (phase as? Phase.Running)?.job?.cancel() }, { phase = Phase.Idle }, { (phase as? Phase.Done)?.let { share(context, it) } }, ::start, onClose,
    ) else Box(
        if (card) Modifier.fillMaxSize().background(Scrim).pointerInput(Unit) {}.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)).padding(24.dp).wrapContentSize()
            .widthIn(max = 520.dp).heightIn(max = 760.dp).enterFade(12.dp).clip(RoundedCornerShape(18.dp)).background(Am.SheetBg)
        else Modifier.fillMaxSize().background(Am.SheetBg).pointerInput(Unit) {}.enterFade(12.dp),
    ) {
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = SheetMaxWidth).fillMaxSize()) {
            TitleBar(stringResource(R.string.export_title), ExportIcons.Close, stringResource(R.string.cancel)) { (phase as? Phase.Running)?.job?.cancel(); onClose() }
            info()
            ScrollArea(Modifier.weight(1f), Am.SheetBg, PaddingValues(15.dp), 10.dp) {
                FileNameField(exportName, kind, options = false)
                kinds.forEachIndexed { i, k ->
                    val s = settings.getValue(k)
                    val silent = k == ExportKind.Audio && !hasAudio
                    OptionCard(
                        selected = kind == k, enabled = !silent, icon = k.icon(), title = stringResource(k.title()),
                        note = if (silent) stringResource(R.string.export_no_sound_short) else if (k == ExportKind.Package && community != null) stringResource(R.string.bundle_community) else optionNote(k, scene, s)?.text(),
                        right = when (k) { ExportKind.Video, ExportKind.Audio, ExportKind.Package, ExportKind.FrameAs, ExportKind.Sequence, ExportKind.Gif -> ExportIcons.ChevronRight; else -> null },
                        onSelect = { if (silent) explain(NoSound) else { kind = k; if (k == ExportKind.FrameAs) page = k } },
                        onRight = { if (silent) explain(NoSound) else { kind = k; page = k } },
                        modifier = Modifier.enterFade(10.dp, minOf(i, 6) * 30),
                        below = if (k == ExportKind.Video) { { ResolutionDropdown(s.shortSide, resolutionChoices(s, check), explain) { kind = k; settings[k] = s.copy(shortSide = it) } } } else null,
                    )
                }
            }
            TemplateToggle(asTemplate, { asTemplate = it }) { templateInfo = true }
            bar()
        }

        Presence(page == ExportKind.Video) {
            VideoPage(
                scene, video, hasAudio, check, info, bar, { naming(ExportKind.Video) },
                warning = warning, slow = support is Support.SlowSoftwareOnly, use = better?.let { suggestionLabel(video, it) },
                onUse = ::useBetter, onContinue = { acknowledged = warning },
                onChange = { settings[ExportKind.Video] = it }, onReason = explain, onAudio = { audioPage = true }, onHelp = { help = true },
                onBack = { page = null },
            )
        }
        Presence(audioPage) {
            AudioSettingsPage(video, hasAudio, hasEncoder, { bitrateFits(it, false) }, { settings[ExportKind.Video] = it }, explain) { audioPage = false }
        }
        Presence(page == ExportKind.Package) {
            BundlePage(scene, engine, appSettings, community, { community = it }, info, bar) { page = null }
        }
        Presence(page == ExportKind.Audio) {
            AudioOnlyPage(settings.getValue(ExportKind.Audio), hasEncoder, { bitrateFits(it, true) }, info, bar, { settings[ExportKind.Audio] = it }, explain) { page = null }
        }
        Presence(page in SheetKinds) {
            val shown = lastPage
            BoxWithConstraints(Modifier.fillMaxSize().presenceScrim(Color(0x66000000)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { page = null }) {
                Column(
                    Modifier.align(Alignment.BottomCenter).widthIn(max = SheetMaxWidth).fillMaxWidth().heightIn(max = maxHeight * 0.82f).presence(slide = 1f, fade = false)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Am.Card)
                        .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}
                        .navigationBarsPadding(),
                ) {
                    Text(stringResource(shown.title()), Modifier.padding(start = 15.dp, end = 15.dp, top = 18.dp, bottom = 4.dp), color = Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    ScrollArea(Modifier.weight(1f, fill = false), Am.Card, PaddingValues(start = 15.dp, end = 15.dp, top = 10.dp, bottom = 18.dp), GroupGap) {
                        naming(shown)
                        OptionsPage(shown, scene, settings.getValue(shown), explain, { help = true }) { settings[shown] = it }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                        TextButton({ page = null }) { Text(stringResource(R.string.ok), color = Am.Navy) }
                    }
                }
            }
        }

        Presence(phase !is Phase.Idle) {
            val shown = lastPhase
            val background = animatedColor(if (shown is Phase.Done) Am.Navy else Scrim)
            Box(Modifier.fillMaxSize().presenceScrim(background).pointerInput(Unit) {}, contentAlignment = Alignment.Center) {
                if (shown is Phase.Running) RunningPanel(shown, progress, heat.level, note) { shown.job.cancel() }
                else if (shown is Phase.Done) DoneScreen(shown.kind, shown.result, { phase = Phase.Idle }) { share(context, shown) }
            }
        }
    }

    if (packageInfo) {
        AlertDialog(
            onDismissRequest = { packageInfo = false },
            title = { Text(stringResource(R.string.project_bundle), color = Navy) },
            text = { Text(stringResource(R.string.one_absproj_package_with_the_project), color = Navy) },
            confirmButton = { TextButton({ packageInfo = false }) { Text(stringResource(R.string.ok), color = Navy) } },
            containerColor = Am.Card,
        )
    }
    if (templateInfo) {
        AlertDialog(
            onDismissRequest = { templateInfo = false },
            title = { Text(stringResource(R.string.template), color = Navy) },
            text = { Text(stringResource(R.string.also_keeps_the_project_with_its), color = Navy) },
            confirmButton = { TextButton({ templateInfo = false }) { Text(stringResource(R.string.ok), color = Navy) } },
            containerColor = Am.Card,
        )
    }
    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(stringResource(R.string.export_interpolation), color = Navy) },
            text = { SmoothingHelp(Navy, Modifier.heightIn(max = 420.dp)) },
            confirmButton = { TextButton({ help = false }) { Text(stringResource(R.string.ok), color = Navy) } },
            containerColor = Am.Card,
        )
    }
    var reporting by remember { mutableStateOf(false) }
    failure?.let { f ->
        if (reporting) ReportDialog(stringResource(f.title) + "\n" + f.text.text()) { reporting = false }
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(stringResource(f.title), color = Navy) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(f.text.text(), color = Navy)
                    if (f.hint) OutdatedHint(outdated, Modifier.padding(top = 8.dp))
                    if (f.hint) TextButton({ reporting = true }) { Text(stringResource(R.string.report_button), color = Navy) }
                }
            },
            confirmButton = { TextButton({ failure = null; f.proceed?.invoke() }) { Text(f.action?.text() ?: stringResource(R.string.ok), color = Navy) } },
            dismissButton = f.proceed?.let { { TextButton({ failure = null }) { Text(stringResource(R.string.cancel), color = Navy) } } },
            containerColor = Am.Card,
        )
    }
}

private val NoSound = txt(R.string.export_no_sound)

private val AiKinds = setOf(ExportKind.Video, ExportKind.Gif, ExportKind.Sequence)

@Composable
private fun AiSmoothHint(scene: Scene, s: ExportSettings, width: Int, height: Int) {
    if (s.interpolation != FrameInterpolation.Ai) return
    val gmac = remember(scene, s.interpolationLevel, s.fps, width, height) { AiSmooth.exportGmac(scene, s.interpolationLevel, s.fps, width, height) }
    Hint(aiSmoothNote(aiSmoothEstimate(gmac, s.interpolationLevel), s.interpolationLevel, clip = false))
}

@StringRes
private fun ExportKind.title() = when (this) {
    ExportKind.Video -> R.string.export_kind_video
    ExportKind.Package -> R.string.project_bundle
    ExportKind.Frame -> R.string.export_kind_frame_png
    ExportKind.FrameAs -> R.string.export_kind_frame_as
    ExportKind.Sequence -> R.string.export_kind_sequence
    ExportKind.Gif -> R.string.gif
    ExportKind.Xml -> R.string.export_kind_xml
    ExportKind.Audio -> R.string.export_kind_audio_only
}

private fun ExportKind.icon(): ImageVector? = when (this) {
    ExportKind.Video -> ExportIcons.Video
    ExportKind.Package -> ExportIcons.Package
    ExportKind.Frame, ExportKind.FrameAs -> ExportIcons.Frame
    ExportKind.Sequence -> ExportIcons.Sequence
    ExportKind.Gif -> null
    ExportKind.Xml -> ExportIcons.Xml
    ExportKind.Audio -> ChromeIcons.AudioTab
}

@Composable
private fun TitleBar(title: String, icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Am.Card).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.captionBar)).height(49.dp)) {
        Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(24.dp), tint = Navy)
        }
        Text(title, Modifier.align(Alignment.Center), color = Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(0.7.dp).background(Line))
    }
}

@Composable
private fun InfoBlock(free: Long, estimate: Long, timeMs: Long?, storage: StorageStatus) {
    val space = when (storage) {
        StorageStatus.Enough -> null
        is StorageStatus.Low -> stringResource(R.string.export_space_low)
        is StorageStatus.NotEnough -> stringResource(R.string.export_space_not_enough)
    }
    Column(Modifier.fillMaxWidth().background(Am.Card).padding(horizontal = 15.dp)) {
        InfoRow(stringResource(R.string.export_available_space), formatBytes(free) + (space?.let { " · $it" } ?: ""), space != null)
        Divider()
        InfoRow(stringResource(R.string.export_estimated_size), if (estimate < 0) stringResource(R.string.export_calculating) else formatBytes(estimate), false)
        Divider()
        InfoRow(stringResource(R.string.export_estimated_time), timeMs?.let { timeLabel(it).text() } ?: stringResource(if (estimate < 0) R.string.export_calculating else R.string.export_time_unknown), false)
    }
}

@Composable
private fun InfoRow(label: String, value: String, warn: Boolean) {
    Row(Modifier.fillMaxWidth().height(33.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Navy, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, Modifier.padding(start = 10.dp), color = animatedColor(if (warn) Alert else Navy), fontSize = 15.sp, style = Mixed,
            fontWeight = if (warn) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(0.7.dp).background(Line))
}

@Composable
private fun ScrollArea(modifier: Modifier, fade: Color, padding: PaddingValues, gap: Dp, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(padding), verticalArrangement = Arrangement.spacedBy(gap), content = content)
        val more by animateFloatAsState(if (scroll.canScrollForward) 1f else 0f, Motion.fast(), label = "more")
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp).graphicsLayer { alpha = more }
                .background(Brush.verticalGradient(listOf(fade.copy(alpha = 0f), fade.copy(alpha = 0.92f), fade))),
        ) {
            Icon(ExportIcons.ChevronDown, if (scroll.canScrollForward) stringResource(R.string.export_more_below) else null, Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(22.dp), tint = ValueGrey)
        }
    }
}

@Composable
private fun TemplateToggle(checked: Boolean, onChange: (Boolean) -> Unit, onInfo: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Am.Card).height(52.dp).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.also_keep_a_copy_as_a), Modifier.weight(1f, fill = false), color = Navy, maxLines = 1, overflow = TextOverflow.Ellipsis,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(11.sp, 16.sp))
            Box(Modifier.size(36.dp).clickable(onClick = onInfo), contentAlignment = Alignment.Center) { Icon(ExportIcons.Info, stringResource(R.string.export_info), Modifier.size(20.dp), tint = Am.HomeDim) }
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Am.HomeAccent, checkedTrackColor = Am.HomeAccent.copy(alpha = 0.5f), checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Am.Thumb, uncheckedTrackColor = Am.SwitchOff, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun ExportBar(enabled: Boolean, blocked: UiText?, pill: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Am.Card).navigationBarsPadding().padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 15.dp)) {
        if (pill) Box(
            Modifier.fillMaxWidth().height(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(animatedColor(if (blocked == null) Am.Accent else lerp(Navy, Am.Card, 0.8f)))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(blocked?.text() ?: stringResource(R.string.export), Modifier.padding(horizontal = 12.dp), color = if (blocked == null) Am.OnAccent else Navy, fontWeight = FontWeight.Medium, maxLines = 1, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(10.sp, 16.sp, 0.5.sp)) }
        else Box(
            Modifier.fillMaxWidth().height(50.dp).shadow(4.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
                .background(animatedColor(if (blocked == null) Navy else lerp(Navy, Am.Card, 0.8f)))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(blocked?.text() ?: stringResource(R.string.export), Modifier.padding(horizontal = 10.dp), color = if (blocked == null) Am.OnNavyAccent else Navy, maxLines = 1, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(11.sp, if (blocked == null) 21.sp else 18.sp, 0.5.sp)) }
    }
}

@Composable
private fun OptionCard(
    selected: Boolean, enabled: Boolean, icon: ImageVector?, title: String, note: String?, right: ImageVector?,
    onSelect: () -> Unit, onRight: () -> Unit, modifier: Modifier, below: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Am.Card)) {
        Row(Modifier.fillMaxWidth().height(70.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxSize().clickable(onClick = onSelect).alpha(if (enabled) 1f else Dimmed), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(60.dp), contentAlignment = Alignment.Center) { Radio(selected) }
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = Navy) else GifBadge()
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(title, color = Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp, maxLines = 1)
                    if (note != null) Text(note, color = NoteGrey, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.width(38.dp).fillMaxSize().then(if (right != null) Modifier.clickable(onClick = onRight) else Modifier), contentAlignment = Alignment.Center) {
                if (right != null) Icon(right, stringResource(if (right == ExportIcons.Info) R.string.export_info else R.string.settings), Modifier.size(24.dp).alpha(if (enabled || right == ExportIcons.Info) 1f else Dimmed), tint = Am.HomeDim)
            }
        }
        below?.let {
            Box(Modifier.fillMaxWidth().height(2.dp).background(Am.SheetBg))
            it()
        }
    }
}

@Composable
private fun Radio(selected: Boolean) {
    val on by animateFloatAsState(if (selected) 1f else 0f, Motion.fast(), label = "radio")
    Canvas(Modifier.size(28.dp)) {
        val r = size.minDimension / 2
        drawCircle(Navy, r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
        drawCircle(Navy, r, alpha = on)
        drawCircle(Am.OnNavyAccent, r - 4.dp.toPx(), alpha = on)
    }
}

@Composable
private fun GifBadge() {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Navy, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5.dp.toPx(), 2.dp.toPx()))))
        }
        Text(stringResource(R.string.gif), color = Navy, fontSize = 7.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ResolutionDropdown(shortSide: Int, choices: List<Choice<app.absentmotion.export.Resolution>>, onReason: (UiText) -> Unit, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(44.dp).clickable { open = true }) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(resolutionLabel(shortSide), Modifier.weight(1f), color = Navy, fontSize = 16.sp)
            Icon(ExportIcons.ChevronDown, null, Modifier.size(20.dp), tint = Navy)
        }
        DropdownMenu(open, { open = false }, containerColor = Am.Card, shape = if (app.absentmotion.platform.PlatformFeatures.desktop) androidx.compose.foundation.shape.RoundedCornerShape(10.dp) else androidx.compose.material3.MenuDefaults.shape) {
            choices.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.value.label, Modifier.alpha(if (c.enabled) 1f else Dimmed), color = Navy, fontSize = 17.sp) },
                    onClick = { open = false; c.reason?.let(onReason) ?: onPick(c.value.shortSide) },
                )
            }
        }
    }
}

@Composable
private fun PageFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().presence(slide = 1f, fade = false).background(Am.SheetBg).pointerInput(Unit) {}, contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = SheetMaxWidth).fillMaxSize()) {
            TitleBar(title, ExportIcons.Back, stringResource(R.string.export_back), onBack)
            content()
        }
    }
}

private val SheetMaxWidth = 720.dp

@Composable
private fun VideoPage(
    scene: Scene, s: ExportSettings, hasAudio: Boolean, check: (ExportSettings) -> Support,
    info: @Composable () -> Unit, bar: @Composable () -> Unit, naming: @Composable () -> Unit,
    warning: UiText?, slow: Boolean, use: UiText?, onUse: () -> Unit, onContinue: () -> Unit,
    onChange: (ExportSettings) -> Unit, onReason: (UiText) -> Unit, onAudio: () -> Unit, onHelp: () -> Unit, onBack: () -> Unit,
) {
    PageFrame(stringResource(R.string.export_kind_video), onBack) {
        info()
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), Am.SheetBg, PaddingValues(start = 15.dp, end = 15.dp, top = 16.dp, bottom = 20.dp), GroupGap) {
            naming()
            VideoFields(scene, s, hasAudio, check, onChange, onReason, onHelp) { NavRow(audioSummary(s.audio).text(), if (hasAudio) null else stringResource(R.string.export_no_sound_yet), onAudio) }
        }
        if (warning != null) WarningCard(stringResource(if (slow) R.string.export_slow_title else R.string.onboarding_check_unsupported), warning.text(), use?.text(), onUse, if (slow) onContinue else null)
        bar()
    }
}

@Composable
private fun VideoFields(
    scene: Scene, s: ExportSettings, hasAudio: Boolean, check: (ExportSettings) -> Support,
    onChange: (ExportSettings) -> Unit, onReason: (UiText) -> Unit, onHelp: () -> Unit, audio: @Composable () -> Unit,
) {
    val rates = (VideoFrameRates + scene.fps).distinct().sorted()
    val (w, h) = exportSize(scene, s.shortSide, even = true)
    Field(stringResource(R.string.export_resolution_heading)) {
        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Am.Card)) { ResolutionDropdown(s.shortSide, resolutionChoices(s, check), onReason) { onChange(s.copy(shortSide = it)) } }
    }
    Field(stringResource(R.string.export_format)) { Segments(containerChoices(s, check), s.container, { it.label }, onReason) { onChange(withContainer(s, it, check)) } }
    Field(stringResource(R.string.export_codec)) { Segments(codecChoices(s, check), s.codec, { it.shortName }, onReason) { onChange(s.copy(codec = it)) } }
    Field(stringResource(R.string.export_frame_rate), "${s.fps}fps") {
        ExportSlider(rates.indexOf(s.fps).coerceAtLeast(0).toFloat() / (rates.size - 1)) { onChange(s.copy(fps = rates[(it * (rates.size - 1)).roundToInt()])) }
        Ends("${rates.first()}fps", "${rates.last()}fps")
    }
    Field(stringResource(R.string.export_interpolation), onHelp = onHelp) {
        Segments(FrameInterpolation.entries.map { if (it == FrameInterpolation.Ai) app.absentmotion.Tier.choice(it, app.absentmotion.Feature.AiSmooth) else Choice(it) }, s.interpolation, { stringResource(it.label) }, onReason) { onChange(s.copy(interpolation = it)) }
        Segments(levelChoices(s), s.interpolationLevel, { stringResource(it.label) }, onReason) { onChange(s.copy(interpolationLevel = it)) }
        Hint(stringResource(R.string.export_interpolation_hint))
        AiSmoothHint(scene, s, w, h)
    }
    Field(stringResource(R.string.export_motion_blur), onHelp = onHelp) {
        Segments(MotionBlur.entries.map { Choice(it) }, s.motionBlur, { stringResource(it.label) }, onReason) { onChange(s.copy(motionBlur = it)) }
        Hint(stringResource(R.string.export_motion_blur_hint))
    }
    Field(stringResource(R.string.export_quality), "%.1f Mbps".format(Locale.ROOT, videoBitrate(w, h, s) / 1e6)) {
        ExportSlider(s.quality) { onChange(s.copy(quality = it)) }
        Ends(stringResource(R.string.export_quality_low), stringResource(R.string.export_quality_high))
    }
    Field(stringResource(R.string.export_kind_audio)) { audio() }
}

@Composable
private fun AudioSettingsPage(
    s: ExportSettings, hasAudio: Boolean, hasEncoder: (String) -> Boolean, fits: (ExportSettings) -> Boolean,
    onChange: (ExportSettings) -> Unit, onReason: (UiText) -> Unit, onBack: () -> Unit,
) {
    PageFrame(stringResource(R.string.export_kind_audio), onBack) {
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), Am.SheetBg, PaddingValues(start = 15.dp, end = 15.dp, top = 16.dp, bottom = 20.dp), GroupGap) {
            AudioFields(s, hasAudio, hasEncoder, fits, onChange, onReason)
        }
    }
}

@Composable
private fun AudioFields(s: ExportSettings, hasAudio: Boolean, hasEncoder: (String) -> Boolean, fits: (ExportSettings) -> Boolean, onChange: (ExportSettings) -> Unit, onReason: (UiText) -> Unit) {
    val a = s.audio
    val change = { next: AudioSettings -> onChange(fitBitrate(s.copy(audio = next), fits)) }
    Hint(if (hasAudio) audioHint(s).text() else stringResource(R.string.export_audio_hint_silent))
    Field(stringResource(R.string.export_codec)) { Segments(audioCodecChoices(s.container, hasEncoder), a.codec, { it.label }, onReason) { change(a.copy(codec = it)) } }
    Field(stringResource(R.string.export_bit_rate), "${a.bitrateKbps} kbps") {
        Segments(bitrateChoices(null) { kbps -> txt(R.string.export_support_audio_settings, a.codec.label).takeIf { !fits(s.copy(audio = a.copy(bitrateKbps = kbps))) } }, a.bitrateKbps, { "$it" }, onReason) { change(a.copy(bitrateKbps = it)) }
    }
    Field(stringResource(R.string.export_sample_rate)) {
        Segments(sampleRateChoices(if (a.codec == AudioCodec.Opus) AudioCodec.Opus.label else null), a.effectiveRate, { khz(it) }, onReason) { change(a.copy(sampleRate = it)) }
    }
    Field(stringResource(R.string.export_channels)) { Segments(listOf(Choice(2), Choice(1)), a.channels, { channelsLabel(it).text() }, onReason) { change(a.copy(channels = it)) } }
}

@Composable
private fun AudioOnlyPage(
    s: ExportSettings, hasEncoder: (String) -> Boolean, fits: (ExportSettings) -> Boolean, info: @Composable () -> Unit, bar: @Composable () -> Unit,
    onChange: (ExportSettings) -> Unit, onReason: (UiText) -> Unit, onBack: () -> Unit,
) {
    PageFrame(stringResource(R.string.export_kind_audio_only), onBack) {
        info()
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), Am.SheetBg, PaddingValues(start = 15.dp, end = 15.dp, top = 16.dp, bottom = 20.dp), GroupGap) {
            AudioOnlyFields(s, hasEncoder, fits, onChange, onReason)
        }
        bar()
    }
}

@Composable
private fun AudioOnlyFields(s: ExportSettings, hasEncoder: (String) -> Boolean, fits: (ExportSettings) -> Boolean, onChange: (ExportSettings) -> Unit, onReason: (UiText) -> Unit) {
    val a = s.audio
    val f = s.audioFormat
    val change = { next: ExportSettings -> onChange(fitBitrate(next, fits)) }
    Field(stringResource(R.string.export_format)) {
        audioFormatChoices(hasEncoder).chunked(3).forEach { row ->
            Segments(row, f, { stringResource(it.label) }, onReason) { change(s.copy(audioFormat = it)) }
        }
    }
    Field(stringResource(R.string.export_bit_rate), if (f.lossless) stringResource(R.string.export_lossless) else "${a.bitrateKbps} kbps") {
        Segments(bitrateChoices(f) { kbps -> txt(R.string.export_support_audio_settings, txt(f.label)).takeIf { !fits(s.copy(audio = a.copy(bitrateKbps = kbps))) } }, if (f.lossless) -1 else a.bitrateKbps, { "$it" }, onReason) { change(s.copy(audio = a.copy(bitrateKbps = it))) }
    }
    Field(stringResource(R.string.export_sample_rate)) {
        Segments(sampleRateChoices(if (f == AudioFormat.Ogg) txt(f.label) else null), audioRate(s), { khz(it) }, onReason) { change(s.copy(audio = a.copy(sampleRate = it))) }
    }
    Field(stringResource(R.string.export_channels)) { Segments(listOf(Choice(2), Choice(1)), a.channels, { channelsLabel(it).text() }, onReason) { change(s.copy(audio = a.copy(channels = it))) } }
    Hint(stringResource(R.string.export_audio_only_hint))
}

@Composable
private fun WarningCard(title: String, text: String, use: String?, onUse: () -> Unit, onContinue: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().background(Am.Card)) {
        Divider()
        Column(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 10.dp)) {
            Text(title, color = Alert, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(text, Modifier.padding(top = 2.dp), color = Navy, fontSize = 14.sp, lineHeight = 19.sp)
            Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (onContinue != null) TextButton(onContinue) { Text(stringResource(R.string.export_continue_anyway), color = ValueGrey, fontSize = 15.sp, maxLines = 1) }
                if (use != null) TextButton(onUse) { Text(use, color = Alert, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun Field(
    title: String, value: String? = null, reason: UiText? = null, onReason: (UiText) -> Unit = {}, onHelp: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dim = if (reason == null) 1f else Dimmed
    val reasonText = reason?.text()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FieldGap)) {
        Row(Modifier.fillMaxWidth().height(if (DESK) 24.dp else 28.dp).alpha(dim), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Navy, fontSize = if (DESK) 14.sp else 17.sp, fontWeight = if (DESK) FontWeight.Medium else null, maxLines = 1)
            if (onHelp != null) Box(Modifier.size(32.dp).clickable(onClick = onHelp), contentAlignment = Alignment.Center) {
                Icon(ExportIcons.Help, stringResource(R.string.export_what_is, title), Modifier.size(20.dp), tint = ValueGrey)
            }
            Spacer(Modifier.weight(1f))
            if (value != null) Text(value, color = ValueGrey, fontSize = if (DESK) 13.sp else 14.sp, maxLines = 1, style = Mixed)
        }
        Box {
            Column(Modifier.fillMaxWidth().alpha(dim), verticalArrangement = Arrangement.spacedBy(FieldGap), content = content)
            if (reason != null && reasonText != null) Box(Modifier.matchParentSize().semantics { contentDescription = reasonText }.clickable { onReason(reason) })
        }
    }
}

@Composable
private fun FileNameField(name: ExportName, kind: ExportKind, options: Boolean = true) {
    val label = stringResource(R.string.export_filename)
    val random = name.random && kind.randomNameAllowed
    val keyboard = LocalSoftwareKeyboardController.current
    Field(label) {
        BasicTextField(
            value = if (random) stringResource(R.string.export_filename_generated) else name.value,
            onValueChange = { name.value = it.take(160) },
            readOnly = random, singleLine = true,
            textStyle = Mixed.copy(color = Navy, fontSize = if (DESK) 14.sp else 16.sp),
            cursorBrush = SolidColor(Navy),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
                .clip(RoundedCornerShape(4.dp)).background(Am.Card).padding(horizontal = 12.dp, vertical = 13.dp),
        )
        if (options && kind.randomNameAllowed) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.export_filename_random), Modifier.weight(1f), color = Navy, fontSize = 15.sp)
                Switch(name.random, { name.random = it; keyboard?.hide() }, colors = SwitchDefaults.colors(checkedTrackColor = Navy, checkedThumbColor = Am.OnNavy, uncheckedThumbColor = Am.Thumb, uncheckedTrackColor = Am.SwitchOff, uncheckedBorderColor = Color.Transparent))
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = NoteGrey, fontSize = 13.sp, lineHeight = 17.sp)
}

@Composable
private fun Ends(left: String, right: String) = Ltr {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(left, Modifier.weight(1f), color = ValueGrey, fontSize = 12.sp, maxLines = 1, style = Mixed, textAlign = TextAlign.Left)
        Text(right, color = ValueGrey, fontSize = 12.sp, maxLines = 1, style = Mixed)
    }
}

private val Mixed = TextStyle(textDirection = TextDirection.Content)

@Composable
private fun NavRow(text: String, note: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(4.dp)).background(Am.Card).clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, color = Navy, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, color = NoteGrey, fontSize = 12.sp, maxLines = 1)
        }
        Icon(ExportIcons.ChevronRight, stringResource(R.string.export_audio_settings), Modifier.size(22.dp), tint = Am.HomeDim)
    }
}

private fun audioHint(s: ExportSettings): UiText =
    txt(R.string.export_audio_hint, s.container.label, listed(audioCodecsFor(s.container).map { it.label }, R.string.export_list_or))

@Composable
private fun <T> Segments(options: List<Choice<T>>, selected: T, label: @Composable (T) -> String, onReason: (UiText) -> Unit, onPick: (T) -> Unit) {
    val size = if (DESK) 13.sp else if (options.size > 4) 13.sp else 15.sp
    Row(Modifier.fillMaxWidth().height(if (DESK) 36.dp else 42.dp).clip(RoundedCornerShape(4.dp)).background(Am.Card).padding(3.dp)) {
        options.forEach { c ->
            val on = c.value == selected
            val fill = animatedColor(if (on) Navy else Am.Card)
            val text = animatedColor(if (on) Am.OnNavyAccent else Navy)
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(fill).deskPress(4.dp).clickable { c.reason?.let(onReason) ?: onPick(c.value) },
                contentAlignment = Alignment.Center,
            ) {
                Row(Modifier.alpha(if (c.enabled) 1f else Dimmed), verticalAlignment = Alignment.CenterVertically) {
                    if (c.locked) Icon(ExportIcons.Lock, null, Modifier.size(13.dp), tint = text)
                    Text(label(c.value), Modifier.padding(horizontal = 2.dp), color = text, fontWeight = FontWeight.Medium, maxLines = 1, style = Mixed,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(9.sp, size))
                }
            }
        }
    }
}

@Composable
private fun ExportSlider(value: Float, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    Box(
        Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(Unit) { detectTapGestures { change(along(it.x)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures(onDragStart = { change(along(it.x)) }) { c, _ -> c.consume(); change(along(c.position.x)) } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 8.dp.toPx()
            val y = size.height / 2
            val x = inset + (size.width - 2 * inset) * value
            drawLine(Navy, Offset(x, y), Offset(size.width - inset, y), 2.dp.toPx())
            drawLine(Am.HomeAccent, Offset(inset, y), Offset(x, y), 2.dp.toPx())
            drawCircle(Am.Thumb, 9.dp.toPx(), Offset(x, y))
        }
    }
}

private fun PointerInputScope.along(x: Float): Float {
    val inset = 8.dp.toPx()
    return ((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsPage(kind: ExportKind, scene: Scene, s: ExportSettings, onReason: (UiText) -> Unit, onHelp: () -> Unit, onChange: (ExportSettings) -> Unit) {
    if (kind == ExportKind.Frame) return
    val rates = (if (kind == ExportKind.Gif) GifFrameRates else (VideoFrameRates + scene.fps)).distinct().sorted()
    val images = kind == ExportKind.Sequence || kind == ExportKind.FrameAs
    if (images) {
        Chips(stringResource(R.string.export_format), if (kind == ExportKind.FrameAs) "${scene.width}x${scene.height}" else null) {
            ImageKind.entries.forEach { image ->
                val reason = imageProblem(image, android.os.Build.VERSION.SDK_INT)
                Chip(stringResource(image.label), s.image == image, reason == null) { reason?.let(onReason) ?: onChange(s.copy(image = image)) }
            }
        }
        Chips(stringResource(R.string.export_quality), reason = if (s.image.lossy) null else txt(R.string.export_reason_image_lossless, txt(s.image.label)), onReason = onReason) {
            ImageQualities.forEach { Chip("$it%", s.imageQuality == it) { onChange(s.copy(imageQuality = it)) } }
        }
    }
    Chips(stringResource(R.string.export_transparent_becomes), reason = if (images && s.image.alpha) txt(R.string.export_reason_image_alpha, txt(s.image.label)) else null, onReason = onReason) {
        Chip(stringResource(R.string.export_white), s.background == BackgroundWhite) { onChange(s.copy(background = BackgroundWhite)) }
        Chip(stringResource(R.string.export_black), s.background == BackgroundBlack) { onChange(s.copy(background = BackgroundBlack)) }
    }
    if (kind == ExportKind.FrameAs) return
    val (w, h) = exportSize(scene, s.shortSide, even = false)
    Chips(stringResource(if (kind == ExportKind.Gif) R.string.export_size else R.string.export_resolution), "${w}x$h") {
        (if (kind == ExportKind.Gif) GifResolutions else VideoResolutions).forEach { Chip(it.label, s.shortSide == it.shortSide) { onChange(s.copy(shortSide = it.shortSide)) } }
    }
    Chips(stringResource(R.string.export_frame_rate)) { rates.forEach { Chip("${it}fps", s.fps == it) { onChange(s.copy(fps = it)) } } }
    Field(stringResource(R.string.export_interpolation), onHelp = onHelp) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FrameInterpolation.entries.forEach {
                val reason = if (it == FrameInterpolation.Ai) app.absentmotion.Tier.reason(app.absentmotion.Feature.AiSmooth) else null
                Chip(stringResource(it.label), s.interpolation == it) { reason?.let(onReason) ?: onChange(s.copy(interpolation = it)) }
            }
        }
        FlowRow(Modifier.alpha(if (s.interpolation == FrameInterpolation.Off) Dimmed else 1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            levelChoices(s).forEach { c -> Chip(stringResource(c.value.label), s.interpolationLevel == c.value) { c.reason?.let(onReason) ?: onChange(s.copy(interpolationLevel = c.value)) } }
        }
        Hint(stringResource(R.string.export_interpolation_hint))
        AiSmoothHint(scene, s, w, h)
    }
    Field(stringResource(R.string.export_motion_blur), onHelp = onHelp) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MotionBlur.entries.forEach { Chip(stringResource(it.label), s.motionBlur == it) { onChange(s.copy(motionBlur = it)) } }
        }
        Hint(stringResource(R.string.export_motion_blur_hint))
    }
}

private fun levelChoices(s: ExportSettings) =
    SmoothLevel.entries.map {
        when {
            s.interpolation == FrameInterpolation.Off -> Choice(it, txt(R.string.export_interpolation_pick_method))
            s.interpolation == FrameInterpolation.Flow && it == SmoothLevel.High -> app.absentmotion.Tier.choice(it, app.absentmotion.Feature.FlowHigh)
            else -> Choice(it)
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(label: String, value: String? = null, reason: UiText? = null, onReason: (UiText) -> Unit = {}, chips: @Composable () -> Unit) {
    Field(label, value, reason, onReason) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val fill = animatedColor(if (selected) Navy else Am.SheetBg)
    val text = animatedColor(if (selected) Am.OnNavyAccent else Navy)
    Box(Modifier.height(if (DESK) 30.dp else 34.dp).alpha(if (enabled) 1f else Dimmed).clip(RoundedCornerShape(17.dp)).background(fill).deskPress(17.dp).clickable(onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(label, color = text, fontSize = if (DESK) 13.sp else 15.sp, maxLines = 1, style = Mixed)
    }
}

@Composable
private fun BoxScope.RunningPanel(p: Phase.Running, progress: Float, heat: ThermalLevel, note: UiText?, onCancel: () -> Unit) {
    val percent = (progress * 100).toInt().coerceIn(0, 100)
    val now by produceState(System.nanoTime(), p) { while (true) { value = System.nanoTime(); delay(250) } }
    val left = exportSecondsLeft((now - p.startNs) / 1e9, progress, p.plannedMs)
    Column(Modifier.align(Alignment.Center).presence(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(runningTitle(p.kind)), color = Am.OnScrim, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(15.dp))
        Box(Modifier.width(220.dp).height(7.dp).clip(RoundedCornerShape(5.dp)).background(Am.OnScrim.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Am.OnScrimAccent))
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.width(220.dp)) {
            Text("$percent%", Modifier.weight(1f), color = Am.OnScrim, fontSize = 13.sp)
            Text(left?.let { "%d:%02d".format(Locale.ROOT, it / 60, it % 60) } ?: "", color = Am.OnScrim, fontSize = 13.sp)
        }
        Box(Modifier.width(300.dp).height(96.dp).padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
            exportHeatText(heat)?.let { Text(it.text(), color = Am.OnScrimAccent, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3) }
                ?: note?.let { Text(it.text(), color = Am.OnScrim, fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, maxLines = 4) }
        }
        TextButton(onCancel) { Text(stringResource(R.string.cancel), color = Am.OnScrimAccent, fontSize = 17.sp) }
    }
    Text(
        stringResource(if (p.kind in BackgroundKinds) R.string.export_running_leave else R.string.keep_absentmotion_open_and_your_screen),
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 30.dp, vertical = 30.dp),
        color = Am.OnScrim, fontSize = 12.sp, textAlign = TextAlign.Center,
    )
}

@StringRes
private fun runningTitle(kind: ExportKind): Int = when (kind) {
    ExportKind.Video -> R.string.export_running_video
    ExportKind.Audio -> R.string.export_running_audio
    ExportKind.Gif -> R.string.export_running_gif
    ExportKind.Sequence -> R.string.export_running_sequence
    else -> R.string.export_running
}

private val BackgroundKinds = setOf(ExportKind.Video, ExportKind.Audio, ExportKind.Gif, ExportKind.Sequence, ExportKind.Package)

private fun timeKey(kind: ExportKind): String? = when (kind) {
    ExportKind.Video -> "time.video"
    ExportKind.Gif -> "time.gif"
    ExportKind.Sequence -> "time.sequence"
    else -> null
}

private const val SaveDelayMs = 400L
private const val MeasureDelayMs = 300L

@Composable
private fun rememberNotifyAsk(): NotifyAsk {
    val context = LocalContext.current
    val launcher = remember { arrayOfNulls<androidx.activity.result.ActivityResultLauncher<String>>(1) }
    val ask = remember {
        val prefs = context.getSharedPreferences("export_settings", android.content.Context.MODE_PRIVATE)
        NotifyAsk(
            android.os.Build.VERSION.SDK_INT,
            { context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            { prefs.getBoolean("notifyAsked", false) },
            { prefs.edit().putBoolean("notifyAsked", true).apply() },
            { launcher[0]?.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
        )
    }
    launcher[0] = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ask.answered() }
    return ask
}

private fun share(context: app.absentmotion.platform.Context, done: Phase.Done) {
    app.absentmotion.platform.shareExport(context, done.result)
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeskExport(
    scene: Scene, frame: Int, fromList: Boolean, engine: EngineThread,
    kinds: List<ExportKind>, kind: ExportKind, onKind: (ExportKind) -> Unit, hasAudio: Boolean,
    settings: MutableMap<ExportKind, ExportSettings>, check: (ExportSettings) -> Support, hasEncoder: (String) -> Boolean,
    bitrateFits: (ExportSettings, Boolean) -> Boolean, explain: (UiText) -> Unit, onHelp: () -> Unit, exportName: ExportName,
    warning: UiText?, slow: Boolean, use: UiText?, onUse: () -> Unit, onContinue: () -> Unit,
    free: Long, estimate: Long, timeMs: Long?, storage: StorageStatus, blocked: UiText?,
    asTemplate: Boolean, onTemplate: (Boolean) -> Unit, onTemplateInfo: () -> Unit, onPackageInfo: () -> Unit, bundleFields: @Composable () -> Unit,
    phase: Phase, progress: Float, heat: ThermalLevel, note: UiText?, onCancel: () -> Unit, onDone: () -> Unit, onShare: () -> Unit,
    onStart: () -> Unit, onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DisposableEffect(Unit) {
        DeskModals.open++
        DeskModals.fade = 1f
        onDispose { DeskModals.open--; if (DeskModals.open == 0) DeskModals.fade = 0f }
    }
    val idle = phase is Phase.Idle
    val idleNow by rememberUpdatedState(idle)
    val closeNow by rememberUpdatedState(onClose)
    val s = settings.getValue(kind)
    val onChange: (ExportSettings) -> Unit = { settings[kind] = it }
    val onReason = explain
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = DeskModals.ScrimAlpha)).pointerInput(Unit) { detectTapGestures { if (idleNow) closeNow() } }
            .focusRequester(focus).focusable()
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown || it.key != Key.Enter && it.key != Key.NumPadEnter) false
                else if (app.absentmotion.platform.textFieldFocused()) false
                else when (phase) { Phase.Idle -> { if (blocked == null) onStart(); true }; is Phase.Done -> { onDone(); true }; else -> false }
            },
        Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
            val width = minOf(maxWidth, 980.dp)
            val height = minOf(maxHeight, 660.dp)
            Column(Modifier.size(width, height).shadow(24.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Am.SheetBg).pointerInput(Unit) { detectTapGestures { } }) {
                Row(Modifier.fillMaxWidth().height(56.dp).background(Am.Card).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.export_title), color = Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(scene.title, Modifier.weight(1f).padding(start = 12.dp), color = NoteGrey, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).deskPress(10.dp).clickable(enabled = idle, onClick = onClose), Alignment.Center) {
                        Icon(ExportIcons.Close, stringResource(R.string.cancel), Modifier.size(22.dp), tint = Navy)
                    }
                }
                Divider()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    val side = (width * 0.4f).coerceIn(280.dp, 420.dp)
                    Column(Modifier.width(side).fillMaxHeight().padding(20.dp)) {
                        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.PreviewBg)) {
                            val done = phase as? Phase.Done
                            if (done != null) app.absentmotion.platform.ExportMediaPreview(done.kind, done.result.uris)
                            else DeskStill(scene, if (fromList) scene.totalTime / 2 else scene.frameStart(frame), engine, Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("${scene.width} x ${scene.height}  \u00B7  ${((scene.totalTime + 500) / 1000).let { "%d:%02d".format(Locale.ROOT, it / 60, it % 60) }}  \u00B7  ${scene.fps}fps", color = NoteGrey, fontSize = 13.sp, maxLines = 1)
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Line))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (phase) {
                            Phase.Idle -> Column(Modifier.fillMaxSize().deskVerticalScroll(color = Navy).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                FileNameField(exportName, kind)
                                Column(verticalArrangement = Arrangement.spacedBy(FieldGap)) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        kinds.chunked(2).forEach { pair ->
                                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                                pair.forEach { k ->
                                                    val silent = k == ExportKind.Audio && !hasAudio
                                                    Box(Modifier.weight(1f)) {
                                                        app.absentmotion.ui.editor.DeskTip(if (silent) stringResource(R.string.export_no_sound_short) else "") {
                                                            KindChip(k.icon(), stringResource(k.title()), kind == k, !silent, Modifier.fillMaxWidth()) { if (silent) explain(NoSound) else onKind(k) }
                                                        }
                                                    }
                                                }
                                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                    optionNote(kind, scene, s)?.let { Hint(it.text()) }
                                }
                                when (kind) {
                                    ExportKind.Video -> {
                                        VideoFields(scene, s, hasAudio, check, onChange, onReason, onHelp) {
                                            var open by remember { mutableStateOf(false) }
                                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                NavRow(audioSummary(s.audio).text(), if (hasAudio) null else stringResource(R.string.export_no_sound_yet)) { open = !open }
                                                if (open) AudioFields(s, hasAudio, hasEncoder, { bitrateFits(it, false) }, onChange, onReason)
                                            }
                                        }
                                        if (warning != null) Column(Modifier.clip(RoundedCornerShape(8.dp))) {
                                            WarningCard(stringResource(if (slow) R.string.export_slow_title else R.string.onboarding_check_unsupported), warning.text(), use?.text(), onUse, if (slow) onContinue else null)
                                        }
                                    }
                                    ExportKind.Audio -> AudioOnlyFields(s, hasEncoder, { bitrateFits(it, true) }, onChange, onReason)
                                    ExportKind.Package -> bundleFields()
                                    ExportKind.Xml -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Am.Card).deskPress(8.dp).clickable(onClick = onPackageInfo).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(ExportIcons.Info, null, Modifier.size(20.dp), tint = Am.HomeDim)
                                        Text(stringResource(R.string.one_absproj_package_with_the_project), Modifier.padding(start = 10.dp), color = Navy, fontSize = 13.sp, lineHeight = 18.sp)
                                    }
                                    ExportKind.FrameAs, ExportKind.Sequence, ExportKind.Gif -> OptionsPage(kind, scene, s, onReason, onHelp, onChange)
                                    else -> Unit
                                }
                            }
                            is Phase.Running -> Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                                val percent = (progress * 100).toInt().coerceIn(0, 100)
                                val now by produceState(System.nanoTime(), phase) { while (true) { value = System.nanoTime(); delay(250) } }
                                val left = exportSecondsLeft((now - phase.startNs) / 1e9, progress, phase.plannedMs)
                                Text(stringResource(runningTitle(phase.kind)), color = Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(20.dp))
                                Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = 0.12f))) {
                                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Am.Accent))
                                }
                                Row(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 8.dp)) {
                                    Text("$percent%", Modifier.weight(1f), color = Navy, fontSize = 14.sp)
                                    Text(left?.let { "%d:%02d".format(Locale.ROOT, it / 60, it % 60) } ?: "", color = NoteGrey, fontSize = 14.sp)
                                }
                                Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().height(72.dp).padding(top = 14.dp), Alignment.TopCenter) {
                                    exportHeatText(heat)?.let { Text(it.text(), color = Alert, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3) }
                                        ?: note?.let { Text(it.text(), color = NoteGrey, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, maxLines = 3) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    DeskButton(stringResource(R.string.cancel), false, onCancel)
                                    if (phase.kind in BackgroundKinds) DeskButton(stringResource(R.string.desk_export_hide), true, onClose)
                                }
                                if (phase.kind in BackgroundKinds) Text(stringResource(R.string.desk_export_hide_note), Modifier.widthIn(max = 420.dp).padding(top = 14.dp), color = NoteGrey, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
                            }
                            is Phase.Done -> Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                                Box(Modifier.size(56.dp).clip(RoundedCornerShape(28.dp)).background(Navy), Alignment.Center) {
                                    Icon(PanelIcons.Check, null, Modifier.size(30.dp), tint = Am.OnNavy)
                                }
                                Spacer(Modifier.height(16.dp))
                                Text(stringResource(R.string.saved_to_storage), color = Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                Text(phase.result.where, Modifier.padding(top = 6.dp), color = NoteGrey, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
                                phase.result.verification?.takeUnless { it.ok }?.let { v ->
                                    Text(verificationLine(v, still = phase.kind == ExportKind.Frame || phase.kind == ExportKind.FrameAs).text(), Modifier.padding(top = 6.dp), color = Alert, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 3)
                                }
                                phase.result.verification?.takeIf { it.ok && it.unchecked }?.let {
                                    Text(stringResource(R.string.export_checked_unavailable), Modifier.padding(top = 6.dp), color = NoteGrey, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 3)
                                }
                                Spacer(Modifier.height(24.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    DeskButton(stringResource(shareLabel(phase.kind)), false, onShare)
                                    DeskButton(stringResource(R.string.ok), true, onDone)
                                }
                            }
                        }
                    }
                }
                Divider()
                Row(Modifier.fillMaxWidth().height(68.dp).background(Am.Card).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    val space = when (storage) {
                        StorageStatus.Enough -> null
                        is StorageStatus.Low -> stringResource(R.string.export_space_low)
                        is StorageStatus.NotEnough -> stringResource(R.string.export_space_not_enough)
                    }
                    DeskStat(stringResource(R.string.export_available_space), formatBytes(free), space)
                    DeskStat(stringResource(R.string.export_estimated_size), if (estimate < 0) stringResource(R.string.export_calculating) else formatBytes(estimate), null)
                    DeskStat(stringResource(R.string.export_estimated_time), timeMs?.let { timeLabel(it).text() } ?: stringResource(if (estimate < 0) R.string.export_calculating else R.string.export_time_unknown), null)
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).deskPress(8.dp).clickable { onTemplate(!asTemplate) }.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.also_keep_a_copy_as_a), color = Navy, fontSize = 13.sp, maxLines = 1)
                        Box(Modifier.size(32.dp).clickable(onClick = onTemplateInfo), Alignment.Center) { Icon(ExportIcons.Info, stringResource(R.string.export_info), Modifier.size(18.dp), tint = Am.HomeDim) }
                        Switch(
                            asTemplate, onTemplate,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Am.HomeAccent, checkedTrackColor = Am.HomeAccent.copy(alpha = 0.5f), checkedBorderColor = Color.Transparent,
                                uncheckedThumbColor = Am.Thumb, uncheckedTrackColor = Am.SwitchOff, uncheckedBorderColor = Color.Transparent,
                            ),
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (blocked != null) Text(blocked.text(), Modifier.widthIn(max = 220.dp), color = Alert, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, textAlign = TextAlign.End)
                        DeskButton(stringResource(R.string.export), true, onStart, enabled = idle && blocked == null)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeskStat(label: String, value: String, warn: String?) {
    Column {
        Text(label, color = NoteGrey, fontSize = 12.sp, maxLines = 1)
        Text(value + (warn?.let { " \u00B7 $it" } ?: ""), color = if (warn != null) Alert else Navy, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, style = Mixed)
    }
}

@Composable
private fun DeskButton(label: String, primary: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.height(40.dp).widthIn(min = 120.dp).alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(10.dp)).background(if (primary) Am.Accent else Am.Card)
            .deskPress(10.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp),
        Alignment.Center,
    ) { Text(label, color = if (primary) Am.OnAccent else Navy, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

@Composable
private fun KindChip(icon: ImageVector?, label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fill = animatedColor(if (selected) Navy else Am.Card)
    val ink = animatedColor(if (selected) Am.OnNavy else Navy)
    Row(
        modifier.height(36.dp).alpha(if (enabled) 1f else Dimmed).clip(RoundedCornerShape(18.dp)).background(fill).deskPress(18.dp).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = ink) else Text(stringResource(R.string.gif), color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(label, Modifier.padding(start = 8.dp).weight(1f), color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeskStill(scene: Scene, time: Int, engine: EngineThread, modifier: Modifier) {
    val context = LocalContext.current
    val picture by produceState<ImageBitmap?>(null, scene, time) {
        val long = maxOf(scene.width, scene.height, 1)
        val file = java.io.File(context.cacheDir, "export-preview-${System.nanoTime()}.png")
        try {
            val made = try {
                engine.stillPng(withContext(Dispatchers.IO) { scene.toXml() }, time, (900 * scene.width / long).coerceAtLeast(1), (900 * scene.height / long).coerceAtLeast(1), file)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            value = if (made) withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() } else null
        } finally { file.delete() }
    }
    Box(modifier, Alignment.Center) {
        picture?.let { Image(it, null, Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit) }
    }
}

private val SuggestedTags = listOf("Edit", "Text", "Transition", "Intro", "Effects", "3D", "Shake", "Glow", "Template", "Preset")

@Composable
private fun BundlePage(
    scene: Scene, engine: EngineThread, settings: app.absentmotion.library.AppSettings, community: app.absentmotion.export.CommunityDetails?, onChange: (app.absentmotion.export.CommunityDetails?) -> Unit,
    info: @Composable () -> Unit, bar: @Composable () -> Unit, onBack: () -> Unit,
) {
    PageFrame(stringResource(R.string.project_bundle), onBack) {
        info()
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), Am.SheetBg, PaddingValues(start = 15.dp, end = 15.dp, top = 16.dp, bottom = 20.dp), GroupGap) {
            BundleFields(scene, engine, settings, community, onChange)
        }
        bar()
    }
}

@Composable
private fun BundleFields(scene: Scene, engine: EngineThread, settings: app.absentmotion.library.AppSettings, community: app.absentmotion.export.CommunityDetails?, onChange: (app.absentmotion.export.CommunityDetails?) -> Unit) {
    Field(stringResource(R.string.project_bundle)) {
        Segments(listOf(Choice(false), Choice(true)), community != null, { stringResource(if (it) R.string.bundle_community else R.string.project_bundle) }, {}) { wanted ->
            if (!wanted) onChange(null)
            else if (community == null) onChange(app.absentmotion.export.CommunityDetails(scene.title, creator = settings.communityCreator, thumbMs = scene.totalTime / 2))
        }
        Hint(stringResource(if (community == null) R.string.one_absproj_package_with_the_project else R.string.bundle_community_note))
    }
    if (community != null) CommunityFields(scene, engine, settings, community, onChange)
    SaveInField(settings)
}

@Composable
private fun SaveInField(settings: app.absentmotion.library.AppSettings) {
    val folder = settings.bundlesFolder
    val view = app.absentmotion.ui.home.folderView(folder)
    val pick = app.absentmotion.ui.home.rememberBundleFolderPicker(settings)
    val fallback = app.absentmotion.ui.home.defaultBundles()
    Field(stringResource(R.string.bundle_save_in)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (DESK) 38.dp else 46.dp).clip(RoundedCornerShape(4.dp)).background(Am.Card).deskPress(4.dp).clickable { pick() }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(app.absentmotion.ui.home.HomeIcons.Folder, null, Modifier.size(20.dp), tint = Navy)
            Spacer(Modifier.width(10.dp))
            Text(
                if (folder == null) stringResource(R.string.bundle_pick_folder) else view?.label.orEmpty(), Modifier.weight(1f),
                color = Navy, fontSize = if (DESK) 13.sp else 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, style = Mixed,
            )
            Icon(ExportIcons.ChevronRight, null, Modifier.size(20.dp), tint = ValueGrey)
        }
        if (folder == null) Hint(stringResource(R.string.bundle_for_now, fallback))
        else if (view?.reachable == false) Text(stringResource(R.string.bundle_folder_lost, fallback), color = Am.HomeAccent, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun CommunityFields(scene: Scene, engine: EngineThread, settings: app.absentmotion.library.AppSettings, community: app.absentmotion.export.CommunityDetails, onChange: (app.absentmotion.export.CommunityDetails?) -> Unit) {
    Field(stringResource(R.string.community_thumbnail)) {
        val ratio = scene.width.coerceAtLeast(1).toFloat() / scene.height.coerceAtLeast(1)
        Box(Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 2f)).clip(RoundedCornerShape(8.dp)).background(Am.Card)) {
            DeskStill(scene, community.thumbMs, engine, Modifier.fillMaxSize())
            SquareChooser(scene.width, scene.height, community.square(scene.width, scene.height), 12.dp, Modifier.fillMaxSize(),
                if (DESK) { square -> onChange(community.withSquare(square, scene.width, scene.height)) } else null)
        }
        if (DESK) {
            var at by remember { mutableFloatStateOf(community.thumbMs.toFloat()) }
            androidx.compose.material3.Slider(
                at, { at = it }, valueRange = 0f..(scene.totalTime - 1).coerceAtLeast(1).toFloat(),
                onValueChangeFinished = { onChange(community.copy(thumbMs = at.toInt())) },
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Navy, activeTrackColor = Navy),
            )
            Hint(stringResource(R.string.community_thumbnail_hint))
        } else {
            var picking by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(4.dp)).background(Navy).clickable { picking = true }, contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.community_choose_thumbnail), color = Am.OnNavy, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, style = Mixed)
            }
            if (picking) ThumbnailPicker(scene, engine, community, { onChange(it); picking = false }) { picking = false }
        }
    }
    TextBox(R.string.community_title, community.title, 80) { onChange(community.copy(title = it)) }
    TextBox(R.string.community_description, community.description, 500, lines = 3) { onChange(community.copy(description = it)) }
    Chips(stringResource(R.string.community_tags), "${community.tags.size}/8") {
        for (tag in (SuggestedTags + community.tags).distinct()) Chip(tag, tag in community.tags, enabled = tag in community.tags || community.tags.size < 8) {
            onChange(community.copy(tags = if (tag in community.tags) community.tags - tag else (community.tags + tag).take(8)))
        }
    }
    var custom by remember { mutableStateOf("") }
    TextBox(R.string.community_tag_hint, custom, 24, done = {
        val tag = custom.trim()
        if (tag.isEmpty() || tag in community.tags) custom = ""
        else if (community.tags.size < 8) {
            onChange(community.copy(tags = community.tags + tag))
            custom = ""
        }
    }) { custom = it }
    TextBox(R.string.community_creator, community.creator, 60) { settings.communityCreator = it; onChange(community.copy(creator = it)) }
    val bytes = remember(scene) { scene.communityBytes() }
    Field(stringResource(R.string.community_size), "${formatBytes(bytes)} / 20 MB") {
        if (bytes > app.absentmotion.export.CommunityLimitBytes) Text(stringResource(R.string.community_too_big, formatBytes(bytes)), color = Am.HomeAccent, fontSize = 13.sp, lineHeight = 18.sp)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onChange(community.copy(confirmed = !community.confirmed)) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Checkbox(community.confirmed, { onChange(community.copy(confirmed = it)) },
            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Navy, uncheckedColor = Navy, checkmarkColor = Am.OnNavy))
        Text(stringResource(R.string.community_confirm), color = Navy, fontSize = if (DESK) 13.sp else 15.sp)
    }
}

@Composable
private fun TextBox(@StringRes label: Int, value: String, limit: Int, lines: Int = 1, done: (() -> Unit)? = null, onChange: (String) -> Unit) {
    val title = stringResource(label)
    val keyboard = LocalSoftwareKeyboardController.current
    Field(title) {
        BasicTextField(
            value = value, onValueChange = { onChange(it.take(limit)) },
            singleLine = lines == 1, minLines = lines,
            textStyle = Mixed.copy(color = Navy, fontSize = if (DESK) 14.sp else 16.sp),
            cursorBrush = SolidColor(Navy),
            keyboardOptions = KeyboardOptions(imeAction = if (lines == 1) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { done?.invoke(); keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title }
                .clip(RoundedCornerShape(4.dp)).background(Am.Card).padding(horizontal = 12.dp, vertical = 13.dp),
        )
    }
}

@Composable
private fun ThumbnailPicker(scene: Scene, engine: EngineThread, community: app.absentmotion.export.CommunityDetails, onConfirm: (app.absentmotion.export.CommunityDetails) -> Unit, onCancel: () -> Unit) {
    val startMs = community.thumbMs
    var square by remember { mutableStateOf(community.square(scene.width, scene.height)) }
    val context = LocalContext.current
    val state = remember {
        val root = java.io.File(context.cacheDir, "thumbnail-picker").apply { deleteRecursively(); mkdirs() }
        val store = app.absentmotion.library.ProjectStore(java.io.File(root, "projects"))
        val saver = app.absentmotion.library.ProjectSaver("look", store, app.absentmotion.library.Recovery(java.io.File(root, "recovery")), failed = {})
        app.absentmotion.editor.EditorState("look", scene, store, engine, app.absentmotion.library.AppSettings(context), saver, lookOnly = true)
            .apply { frame = scene.frameAt(startMs).coerceIn(0, scene.lastFrame) }
    }
    var shownMs by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    val ready = shownMs == state.timeMs
    androidx.compose.ui.window.Dialog(onCancel, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Am.TopBar).systemBarsPadding()) {
            val lower = app.absentmotion.ui.editor.lowerPanelHeight(maxHeight, 280.dp)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(48.dp).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
                        Icon(app.absentmotion.ui.editor.ChromeIcons.Back, stringResource(R.string.export_back), Modifier.size(22.dp), tint = Am.OnDark)
                    }
                    Text(stringResource(R.string.community_thumbnail), Modifier.weight(1f), color = Am.OnDark, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.height(34.dp).alpha(if (ready) 1f else Dimmed).clip(RoundedCornerShape(4.dp)).background(Am.Accent).clickable(enabled = ready) { onConfirm(community.copy(thumbMs = state.timeMs).withSquare(square, scene.width, scene.height)) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.community_confirm_thumbnail), color = Am.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, style = Mixed)
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f).background(Am.PreviewBg)) {
                    PickerStill(scene, state.timeMs, engine, Modifier.fillMaxSize()) { shownMs = it }
                    SquareChooser(scene.width, scene.height, square, 10.dp, Modifier.fillMaxSize()) { square = it }
                }
                Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(app.absentmotion.ui.editor.TimelineMath.timecode(state.frame, scene.fps), color = Am.OnDark, fontSize = 14.sp, maxLines = 1, style = Mixed)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.community_picker_hint), Modifier.weight(1f), color = Am.OnDark.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2)
                }
                Box(Modifier.fillMaxWidth().height(lower).background(Am.TimelineBg)) { app.absentmotion.ui.editor.Timeline(state, Modifier.fillMaxSize()) }
            }
        }
    }
}

@Composable
private fun PickerStill(scene: Scene, timeMs: Int, engine: EngineThread, modifier: Modifier, onShown: (Int) -> Unit) {
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    val wanted by rememberUpdatedState(timeMs)
    val shown by rememberUpdatedState(onShown)
    LaunchedEffect(scene) {
        val xml = withContext(Dispatchers.IO) { scene.toXml() }
        val long = maxOf(scene.width, scene.height, 1)
        fun sized(edge: Int) = (edge * scene.width / long).coerceAtLeast(1) to (edge * scene.height / long).coerceAtLeast(1)
        val (quickW, quickH) = sized(PickerQuickEdge)
        val (sharpW, sharpH) = sized(PickerSharpEdge)
        try {
            engine.exportSession(xml) {
                var drawn = -1
                while (true) {
                    val at = wanted
                    if (at != drawn) {
                        drawn = at
                        previewStill(at, quickW, quickH, false)?.let { picture = app.absentmotion.platform.rawFrameImage(it, quickW, quickH) }
                        continue
                    }
                    if (kotlinx.coroutines.withTimeoutOrNull(PickerSettleMs) { snapshotFlow { wanted }.first { it != at } } != null) continue
                    still(at, sharpW, sharpH)?.let { picture = app.absentmotion.platform.rawFrameImage(it, sharpW, sharpH); shown(at) }
                    snapshotFlow { wanted }.first { it != at }
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { }
    }
    Box(modifier, Alignment.Center) {
        picture?.let { Image(it, null, Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Fit) }
    }
}

@Composable
private fun SquareChooser(width: Int, height: Int, square: app.absentmotion.export.ThumbSquare, pad: androidx.compose.ui.unit.Dp, modifier: Modifier,
                          onChange: ((app.absentmotion.export.ThumbSquare) -> Unit)?) {
    if (width == height) return
    val latest by rememberUpdatedState(square)
    val change by rememberUpdatedState(onChange)
    val description = stringResource(R.string.a11y_crop_area)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.padding(pad)) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val scale = minOf(boxW / width.coerceAtLeast(1), boxH / height.coerceAtLeast(1))
        val origin = androidx.compose.ui.geometry.Offset((boxW - width * scale) / 2f, (boxH - height * scale) / 2f)
        fun toScreen(x: Float, y: Float) = origin + androidx.compose.ui.geometry.Offset(x * scale, y * scale)
        val reach = with(density) { 28.dp.toPx() }
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }.then(if (onChange == null) Modifier else Modifier.pointerInput(width, height, scale, origin) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = latest
                val corner = start.corners().map { (x, y) -> (toScreen(x, y) - down.position).getDistance() }.withIndex().filter { it.value < reach }.minByOrNull { it.value }?.index ?: -1
                val topLeft = toScreen(start.left, start.top)
                val inside = down.position.x in topLeft.x..topLeft.x + start.side * scale && down.position.y in topLeft.y..topLeft.y + start.side * scale
                if (corner < 0 && !inside) return@awaitEachGesture
                var now = start
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    now = when {
                        pressed.size > 1 -> now.scaled(event.calculateZoom(), width, height).moved(event.calculatePan().x / scale, event.calculatePan().y / scale, width, height)
                        corner >= 0 -> event.changes.first().position.let { p -> now.dragged(corner, (p.x - origin.x) / scale, (p.y - origin.y) / scale, width, height) }
                        else -> event.calculatePan().let { now.moved(it.x / scale, it.y / scale, width, height) }
                    }
                    if (now != latest) change?.invoke(now)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        })) {
            val q = square
            val a = toScreen(q.left, q.top)
            val b = toScreen(q.left + q.side, q.top + q.side)
            val image = androidx.compose.ui.geometry.Rect(origin, androidx.compose.ui.geometry.Size(width * scale, height * scale))
            val dim = androidx.compose.ui.graphics.Path().apply {
                fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                addRect(image)
                addRect(androidx.compose.ui.geometry.Rect(a, b))
            }
            drawPath(dim, Color.Black.copy(alpha = 0.6f))
            val thin = 1.dp.toPx()
            for (i in 1..2) {
                val f = i / 3f
                drawLine(Color.White.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(a.x + (b.x - a.x) * f, a.y), androidx.compose.ui.geometry.Offset(a.x + (b.x - a.x) * f, b.y), thin)
                drawLine(Color.White.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(a.x, a.y + (b.y - a.y) * f), androidx.compose.ui.geometry.Offset(b.x, a.y + (b.y - a.y) * f), thin)
            }
            drawRect(Color.Black.copy(alpha = 0.5f), a, androidx.compose.ui.geometry.Size(b.x - a.x, b.y - a.y), style = androidx.compose.ui.graphics.drawscope.Stroke(thin * 3f))
            drawRect(Color.White, a, androidx.compose.ui.geometry.Size(b.x - a.x, b.y - a.y), style = androidx.compose.ui.graphics.drawscope.Stroke(thin * 1.5f))
            if (onChange != null) {
                val arm = minOf(16.dp.toPx(), (b.x - a.x) / 3f)
                val stroke = 4.dp.toPx()
                for ((corner, dx, dy) in listOf(Triple(a, 1f, 1f), Triple(androidx.compose.ui.geometry.Offset(b.x, a.y), -1f, 1f), Triple(b, -1f, -1f), Triple(androidx.compose.ui.geometry.Offset(a.x, b.y), 1f, -1f))) {
                    for (end in listOf(corner + androidx.compose.ui.geometry.Offset(arm * dx, 0f), corner + androidx.compose.ui.geometry.Offset(0f, arm * dy))) {
                        drawLine(Color.Black.copy(alpha = 0.55f), corner, end, stroke + 2.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(Color.White, corner, end, stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
            }
        }
    }
}

private const val PickerQuickEdge = 480
private const val PickerSharpEdge = 900
private const val PickerSettleMs = 180L
