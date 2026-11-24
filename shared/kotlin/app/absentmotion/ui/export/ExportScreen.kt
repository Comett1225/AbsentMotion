package app.absentmotion.ui.export

import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.annotation.StringRes
import app.absentmotion.ui.home.DeskModals
import app.absentmotion.ui.home.deskPress
import app.absentmotion.ui.home.checkerboard
import app.absentmotion.export.verificationLine
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.layout.onSizeChanged
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
import app.absentmotion.export.fixedFormat
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
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import app.absentmotion.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.AudioCodec
import app.absentmotion.export.AudioFormat
import app.absentmotion.export.AudioSettings
import app.absentmotion.Feature
import app.absentmotion.Risk
import app.absentmotion.RiskAsk
import app.absentmotion.Tier
import app.absentmotion.Unlock
import app.absentmotion.ui.LocalRiskAsk
import app.absentmotion.ui.RiskDialog
import app.absentmotion.export.Choice
import app.absentmotion.export.SmoothedKinds
import app.absentmotion.export.exportRisks
import app.absentmotion.export.savable
import app.absentmotion.export.sizeLabel
import app.absentmotion.export.DeviceCapabilities
import app.absentmotion.export.ExportPrefs
import app.absentmotion.export.codecFailed
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
import app.absentmotion.export.ExportClock
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.aspectRatio
import app.absentmotion.export.problem
import app.absentmotion.export.communityBytes
import app.absentmotion.effects.AddedEffectStore
import app.absentmotion.effects.addedEffectUses
import app.absentmotion.ui.home.SwitchRow
import app.absentmotion.ui.HoldName
import app.absentmotion.ui.home.DialogPill
import app.absentmotion.ui.home.AccentPill

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

private val FormatKinds = setOf(ExportKind.Gif, ExportKind.Sequence, ExportKind.FrameAs)

private sealed interface Phase {
    data object Idle : Phase
    class Running(val kind: ExportKind, val job: Job, val clock: ExportClock, @StringRes val title: Int = runningTitle(kind), @StringRes val reserve: Int? = null) : Phase {
        var progress by mutableFloatStateOf(0f)
    }
    class Done(val kind: ExportKind, val result: ExportResult, val staged: app.absentmotion.export.StagedExport? = null, val saved: Boolean = false) : Phase
}

internal object SavedTemplates { var revision by androidx.compose.runtime.mutableIntStateOf(0) }

private class Failure(@StringRes val title: Int, val text: UiText, val action: UiText? = null, val proceed: (() -> Unit)? = null, val hint: Boolean = false)

@Composable
internal fun ExportScreen(scene: Scene, frame: Int, engine: EngineThread, appSettings: app.absentmotion.library.AppSettings, fromProjectList: Boolean, outdated: Outdated? = null, preset: PresetShare? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bundles = remember(appSettings.bundlesFolder) { app.absentmotion.platform.bundleStore(context, appSettings.bundlesFolder) }
    val exporter = remember(bundles) { Exporter(context, engine, MediaStoreExport(context), bundles = bundles) }
    val kinds = remember { if (preset != null) listOf(ExportKind.Package) else listOf(ExportKind.Video, ExportKind.Audio, ExportKind.Package, ExportKind.Frame, ExportKind.FrameAs, ExportKind.Sequence, ExportKind.Gif) + if (fromProjectList) listOf(ExportKind.Xml) else emptyList() }
    var kind by remember { mutableStateOf(if (preset != null) ExportKind.Package else ExportKind.Video) }
    val exportName = remember(scene.title) { ExportName(scene.title) }
    val prefs = remember { ExportPrefs(context) }
    val settings = remember { mutableStateMapOf<ExportKind, ExportSettings>().apply { ExportKind.entries.forEach { put(it, prefs.load(it, scene)) } } }
    val stored = remember { HashMap(settings.toMap()) }
    val persist = {
        settings.forEach { (k, v) ->
            val keep = savable(v, stored.getValue(k), if (k == ExportKind.Video) DeviceCapabilities.of(context).check(v, scene) else Support.Supported)
            if (stored[k] != keep) { prefs.save(k, keep); stored[k] = keep }
        }
    }
    val unlock = remember { Unlock() }
    var risk by remember { mutableStateOf<RiskAsk?>(null) }
    LaunchedEffect(settings) {
        snapshotFlow { settings.toMap() }.collectLatest {
            delay(SaveDelayMs)
            persist()
        }
    }
    DisposableEffect(settings) { onDispose { persist() } }
    var page by remember { mutableStateOf<ExportKind?>(null) }
    var beforeFrame by remember { mutableStateOf(ExportKind.Video) }
    var community by remember { mutableStateOf(preset?.let { app.absentmotion.export.CommunityDetails(scene.title.trim().take(80), creator = appSettings.communityCreator, preset = true) }) }
    var includeAddedEffects by remember { mutableStateOf(true) }
    var lastPage by remember { mutableStateOf(ExportKind.Gif) }
    var audioPage by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf<UiText?>(null) }
    var lastPhase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var asTemplate by remember { mutableStateOf(false) }
    var templateInfo by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Failure?>(null) }
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    page?.let { if (it in FormatKinds) lastPage = it }
    if (phase !is Phase.Idle) lastPhase = phase
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
    var gifFailed by remember { mutableStateOf(false) }
    val gifSample by produceState<GifSample?>(null, kind, scene, gifSettings, phase) {
        value = null
        gifFailed = false
        if (kind == ExportKind.Gif && phase is Phase.Idle) {
            try { delay(MeasureDelayMs); value = measureGif(engine, scene, gifSettings) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("AMExport", "Cannot sample GIF size", e); gifFailed = true }
        }
    }
    val videoSettings = settings.getValue(ExportKind.Video)
    val drawMs by produceState<Double?>(null, kind, scene, videoSettings.shortSide, videoSettings.smoothing, videoSettings.fps, phase is Phase.Idle) {
        value = null
        if (kind == ExportKind.Video && phase is Phase.Idle) {
            try { delay(MeasureDelayMs); value = measureVideoDraw(engine, scene, videoSettings) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("AMExport", "Cannot sample the drawing time", e); value = Double.NaN }
        }
    }
    val sequenceSettings = settings.getValue(ExportKind.Sequence)
    val sequenceMs by produceState<Double?>(null, kind, scene, sequenceSettings.shortSide, sequenceSettings.smoothing, sequenceSettings.fps, sequenceSettings.image, phase is Phase.Idle) {
        value = null
        if (kind == ExportKind.Sequence && phase is Phase.Idle) {
            try { delay(MeasureDelayMs); value = measureSequenceFrame(engine, scene, sequenceSettings) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("AMExport", "Cannot sample the drawing time", e); value = Double.NaN }
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
        val bitrates = if (kind == ExportKind.Video) exportSize(scene, s.shortSide, even = true).let { (w, h) -> report?.bitrates(s, w, h) } else null
        estimateBytes(kind, scene, s, hasAudio, bitrates)
    }
    val sizeKey = ExportSizes.key(kind, settings.getValue(kind))
    val estimate = remember(rawEstimate, sizeKey, phase is Phase.Idle) { if (rawEstimate < 0 || sizeKey == null) rawEstimate else (rawEstimate * sizes.ratio(sizeKey)).toLong() }
    val room = if (DESK && (kind == ExportKind.Video || kind == ExportKind.Audio)) estimate * 2 else estimate
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
    fun ratioKey(k: ExportKind, s: ExportSettings) = timeKey(k, s)?.takeUnless { k == ExportKind.Video && times.learns(s, scene, report?.probe) }
    val video = settings.getValue(ExportKind.Video)
    val support = remember(video, check) { check(video) }
    val better = remember(video, support, check) { betterChoice(video, support, check) }
    val warning = codecWarning(video, support)?.takeIf { it != acknowledged || support is Support.Unsupported }
    val current = settings.getValue(kind)
    val aiMs = if (kind == ExportKind.Video && current.interpolation == FrameInterpolation.Ai) {
        val gmac = remember(scene, current.interpolationLevel, current.fps, videoSize) { AiSmooth.exportGmac(scene, current.interpolationLevel, current.fps, videoSize.first, videoSize.second) }
        if (gmac > 0) aiSmoothEstimate(gmac, current.interpolationLevel)?.let { (it.seconds * 1000).toLong() } ?: 0L else 0L
    } else 0L
    val modelMs = remember(kind, current, report, heat.level, estimate, phase is Phase.Idle, gifSample, drawMs, sequenceMs, aiMs) {
        when (kind) {
            ExportKind.Video -> times.estimateMs(current, scene, report?.probe, heat.level, report, drawMs?.takeUnless(Double::isNaN), aiMs)
                ?: if (drawMs?.isNaN() == true) times.roughMs(current, scene) else null
            ExportKind.Gif -> gifSample?.let { gifTimeMs(frameCount(scene, current.fps), it, times.estimateMs(current, scene, report?.probe, heat.level)) }
            ExportKind.Sequence -> times.estimateMs(current, scene, report?.probe, heat.level, null, sequenceMs?.takeUnless(Double::isNaN))
                ?: if (sequenceMs?.isNaN() == true) times.roughMs(current, scene) else null
            ExportKind.Package -> estimate.coerceAtLeast(0) / 100_000
            ExportKind.Audio -> scene.totalTime / if (current.audioFormat.lossless) 20L else 14L
            else -> 0L
        }
    }
    val calculating = phase is Phase.Idle && when (kind) {
        ExportKind.Video -> drawMs == null
        ExportKind.Sequence -> sequenceMs == null
        ExportKind.Gif -> gifSample == null && !gifFailed
        else -> false
    } || estimate < 0 && !gifFailed
    val timeMs = remember(modelMs, kind, phase is Phase.Idle) { modelMs?.let { m -> ratioKey(kind, current)?.let { (m * sizes.ratio(it)).toLong() } ?: m } }
    val storage = if (estimate < 0) StorageStatus.Enough else storageStatus(room, free)
    val audioOnly = settings.getValue(ExportKind.Audio)
    val audioProblem = remember(report, audioOnly) { caps.checkAudioOnly(audioOnly) }
    val blocked = if (checking) txt(R.string.export_calculating) else exportBlock(storage, when {
        kind == ExportKind.Video -> support
        kind == ExportKind.Audio && audioProblem != null -> Support.Unsupported(audioProblem, null)
        else -> Support.Supported
    })
    val explain: (UiText) -> Unit = { failure = Failure(R.string.export_not_available, it) }
    val askRisk: (UiText, () -> Unit) -> Unit = { reason, go ->
        risk = RiskAsk(listOf(Risk(reason, reason))) {
            go()
            unlock.confirm(exportRisks(kind, settings.getValue(kind), if (kind == ExportKind.Video) check(settings.getValue(kind)) else Support.Supported))
        }
    }

    app.absentmotion.platform.ExportBars(dark = false, running = phase is Phase.Running)
    app.absentmotion.ui.theme.ThemeFade.Hold(phase is Phase.Running)
    fun closePage() {
        if (DESK && page == ExportKind.FrameAs) kind = beforeFrame
        page = null
    }

    BackHandler {
        when (phase) {
            is Phase.Running -> if (!DESK) (phase as? Phase.Running)?.job?.cancel() else if ((phase as? Phase.Running)?.kind in BackgroundKinds) onClose()
            is Phase.Done -> phase = Phase.Idle
            Phase.Idle -> when {
                audioPage -> audioPage = false
                page != null -> closePage()
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
        if (ExportRun.running) {
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
        val aiLevel = s.interpolationLevel.takeIf { s.interpolation == FrameInterpolation.Ai && kind in SmoothedKinds }
        if (kind == ExportKind.Package) community?.problem(scene, app.absentmotion.library.MediaAccess.forApp(context),
            if (includeAddedEffects) AddedEffectStore(context.filesDir).bundleBytes(scene) else 0L)?.let {
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
        if (kind == ExportKind.Video && fresh is Support.Unsupported && !fresh.risky) {
            val fix = betterChoice(s, fresh, check)
            failure = Failure(R.string.export_not_supported_title, codecWarning(s, fresh) ?: txt(R.string.export_block_unsupported), fix?.let { suggestionLabel(s, it) },
                fix?.let { { settings[ExportKind.Video] = it } })
            return
        }
        if (kind == ExportKind.Audio) caps.checkAudioOnly(s)?.let {
            failure = Failure(R.string.export_not_supported_title, it)
            return
        }
        val risks = exportRisks(kind, s, fresh)
        unlock.pending(risks).takeIf { it.isNotEmpty() }?.let {
            risk = RiskAsk(it) { unlock.confirm(it); start(ignoreMissing, aiReady) }
            return
        }
        if (aiLevel != null && !aiReady && !AiSmooth.installed(context, aiLevel)) {
            failure = Failure(R.string.ai_smooth_download_title, txt(R.string.ai_smooth_download_text, formatBytes(AiSmooth.downloadBytes(aiLevel))),
                txt(R.string.ai_download_and_start), proceed = { start(ignoreMissing, true) })
            return
        }
        val fetching = aiLevel?.takeIf { !AiSmooth.installed(context, it) }
        val needed = room + (fetching?.let(AiSmooth::downloadBytes) ?: 0L)
        free = freeSpace()
        if (kind != ExportKind.Gif && storageStatus(needed, free) is StorageStatus.NotEnough) {
            failure = Failure(R.string.export_storage_title, (storageStatus(needed, free) as StorageStatus.NotEnough).message)
            return
        }
        val chosen = kind
        val estimated = rawEstimate
        val modelled = modelMs
        val ratio = ratioKey(chosen, s)
        persist()
        val alsoTemplate = asTemplate && page != ExportKind.FrameAs
        val name = exportName.next(chosen)
        val planned = timeMs
        note = null
        val background = chosen in BackgroundKinds
        Log.i("AMExport", "start ${chosen.name.lowercase()} estimated ${timeMs ?: -1} ms, drawing ${drawMs ?: -1.0} ms per frame")
        val report: (Float) -> Unit = { done -> (phase as? Phase.Running)?.let { it.clock.add(done); it.progress = done }; ExportRun.progress = done }
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
                val stageKey = if (chosen in StagedKinds && !DESK) app.absentmotion.export.StagedExports.key(exported.toXml(), s, chosen, frame) else null
                val (stageExt, stageMime) = when (chosen) {
                    ExportKind.Gif -> "gif" to "image/gif"
                    ExportKind.Frame -> ImageKind.Png.ext to ImageKind.Png.mime
                    ExportKind.FrameAs -> s.image.ext to s.image.mime
                    else -> s.fixedFormat().let { it.container.ext to it.container.mime }
                }
                fun staged() = stageKey?.let { app.absentmotion.export.StagedExports.find(context, it, name, stageExt, stageMime) }
                staged()?.let { ready ->
                    outcome = true
                    phase = Phase.Done(chosen, ExportResult(listOf(ready.uri), ready.mime, ""), ready)
                    return@launch
                }
                val stage = stageKey?.let { app.absentmotion.export.StagedExports.store(context, it) }
                if (fetching != null) {
                    try {
                        AiSmooth.download(context, fetching, report)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ensureActive()
                        Log.w("AMExport", "the AI Smooth download failed", e)
                        outcome = false
                        phase = Phase.Idle
                        failure = Failure(R.string.ai_smooth_download_failed, e.message?.let { txt(R.string.library_text_value, it) } ?: txt(R.string.ai_model_download_failed))
                        return@launch
                    }
                    phase = Phase.Running(chosen, coroutineContext.job, ExportClock(planned), reserve = R.string.export_running_ai_smooth)
                    ExportRun.title = runningTitle(chosen)
                    ExportRun.progress = 0f
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
                val began = System.nanoTime()
                unlock.spend()
                val result = Tier.unlocking(risks.mapNotNull { it.key as? Feature }) {
                    (if (background) Exporter(context.applicationContext, app.absentmotion.platform.exportEngine(engine), MediaStoreExport(context.applicationContext), bundles = bundles, stage = stage)
                        else if (stage != null) Exporter(context, engine, MediaStoreExport(context), bundles = bundles, stage = stage) else exporter).export(chosen, exported, s, frame, name, { note = it }, community.takeIf { chosen == ExportKind.Package }, includeAddedEffects, report)
                }
                outcome = true
                if (background) app.absentmotion.platform.exportDone(result)
                phase = Phase.Done(chosen, result, staged())
                if (preset != null) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { app.absentmotion.library.PresetStore(context).setPlaceholder(preset.preset.id, preset.choice) }
                    }
                    PresetSharing.revision++
                }
                ratio?.let { key -> modelled?.let { sizes.record(key, it, (System.nanoTime() - began) / 1_000_000) } }
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
                        SavedTemplates.revision++
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
                failure = Failure(R.string.export_failed_title, when {
                    e is OutOfMemoryError -> if (chosen in SizedKinds) txt(R.string.export_error_too_large, sizeLabel(s.shortSide)) else txt(R.string.crash_reason_memory_export)
                    risks.any { it.key is String } && generateSequence(e) { it.cause }.take(16).any(::codecFailed) -> txt(R.string.export_error_encoder_busy)
                    else -> txt(R.string.library_text_value, e.message ?: e.toString())
                }, hint = true)
            } finally {
                if (background) ExportRun.end(outcome)
            }
        }
        val first = if (fetching == null) Phase.Running(chosen, job, ExportClock(planned))
            else Phase.Running(chosen, job, ExportClock(null, skipsStalls = false), R.string.export_running_ai_smooth, runningTitle(chosen))
        phase = first
        if (background) ExportRun.begin(context, first.title) { job.cancel() }
        if (background && !ExportRun.running) phase = Phase.Idle
    }

    val info: @Composable () -> Unit = { InfoBlock(free, estimate, timeMs, calculating, storage) }
    val naming: @Composable (ExportKind) -> Unit = { FileNameField(exportName, it) }
    val card = !DESK && app.absentmotion.ui.editor.isTablet(androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp)
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val twoPane = card && cfg.screenWidthDp > cfg.screenHeightDp && cfg.screenWidthDp - 48 >= app.absentmotion.ui.adaptive.WideFrom.value
    val bar: @Composable () -> Unit = { ExportBar(enabled = phase == Phase.Idle, blocked = blocked, pill = card, onClick = ::start) }

    val framing = page == ExportKind.FrameAs
    val closeFrame = { if (phase is Phase.Done) phase = Phase.Idle; closePage() }
    var saving by remember { mutableStateOf(false) }
    fun saveStaged(done: Phase.Done) {
        val staged = done.staged ?: return
        if (saving) return
        saving = true
        app.absentmotion.platform.exportScope(scope).launch {
            try {
                val target = MediaStoreExport(context.applicationContext)
                val uri = withContext(Dispatchers.IO) { app.absentmotion.export.StagedExports.save(context, staged, target) }
                val folder = if (done.kind == ExportKind.Video) "Movies" else "Pictures"
                val where = target.location("$folder/${app.absentmotion.export.AppFolder}")
                if (phase === done) phase = Phase.Done(done.kind, ExportResult(listOf(uri), staged.mime, where, done.result.verification), staged, saved = true)
                app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.compress_saved_to, where), app.absentmotion.platform.Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AMExport", "saving the export failed", e)
                failure = Failure(R.string.export_failed_title, txt(R.string.library_text_value, e.message ?: e.toString()), hint = true)
            } finally {
                saving = false
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalRiskAsk provides askRisk) {
    if (DESK) DeskExport(
        scene, frame, fromProjectList, engine, if (framing) listOf(ExportKind.FrameAs) else kinds, kind, { if (it == ExportKind.FrameAs) { beforeFrame = kind; page = it }; kind = it }, hasAudio, settings, check, hasEncoder, bitrateFits, explain, { help = true }, exportName,
        warning, support is Support.SlowSoftwareOnly, better?.let { suggestionLabel(video, it) }, ::useBetter, { acknowledged = warning },
        free, estimate, timeMs, calculating, storage, blocked, asTemplate, { asTemplate = it }, { templateInfo = true }, { BundleFields(scene, engine, appSettings, community, preset, includeAddedEffects = includeAddedEffects, onIncludeAddedEffects = { includeAddedEffects = it }) { community = it } },
        phase, heat.level, note, { (phase as? Phase.Running)?.job?.cancel() }, { phase = Phase.Idle }, { (phase as? Phase.Done)?.let { share(context, it) } }, ::start,
        if (framing) closeFrame else onClose, stringResource(if (preset != null) R.string.preset_share else if (framing) R.string.export_kind_frame_as else R.string.export_title),
        preset?.let { { modifier -> PresetStill(scene, engine, modifier) } },
    ) else Box(
        if (card) Modifier.fillMaxSize().background(Scrim).pointerInput(Unit) {}.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)).padding(24.dp).wrapContentSize()
            .widthIn(max = if (twoPane) 980.dp else 520.dp).heightIn(max = 760.dp).enterFade(12.dp).clip(RoundedCornerShape(18.dp)).background(Am.SheetBg)
        else Modifier.fillMaxSize().background(Am.SheetBg).pointerInput(Unit) {}.imePadding().enterFade(12.dp),
    ) {
        if (preset != null) BundlePage(scene, engine, appSettings, community, { community = it }, info, bar, preset, onClose, includeAddedEffects) { includeAddedEffects = it }
        else Column(Modifier.align(Alignment.TopCenter).widthIn(max = if (twoPane) Dp.Infinity else SheetMaxWidth).fillMaxSize()) {
            TitleBar(stringResource(R.string.export_title), ExportIcons.Close, stringResource(R.string.cancel)) { (phase as? Phase.Running)?.job?.cancel(); onClose() }
            if (!twoPane) info()
            val kindCards: @Composable ColumnScope.() -> Unit = {
                kinds.forEachIndexed { i, k ->
                    val s = settings.getValue(k)
                    val silent = k == ExportKind.Audio && !hasAudio
                    OptionCard(
                        selected = kind == k, enabled = !silent, icon = k.icon(), title = stringResource(k.title()),
                        note = if (silent) stringResource(R.string.export_no_sound_short) else if (k == ExportKind.Package && community != null) stringResource(R.string.bundle_community) else optionNote(k, scene, s)?.text(),
                        right = when (k) { ExportKind.Video, ExportKind.Audio, ExportKind.Package, ExportKind.FrameAs, ExportKind.Sequence, ExportKind.Gif -> ExportIcons.ChevronRight; else -> null },
                        onSelect = { if (silent) explain(NoSound) else { kind = k } },
                        onRight = { if (silent) explain(NoSound) else { kind = k; page = k } },
                        modifier = Modifier.enterFade(10.dp, minOf(i, 6) * 30),
                        below = if (k == ExportKind.Video) { { ResolutionDropdown(s.shortSide, resolutionChoices(s, check), explain) { kind = k; settings[k] = s.copy(shortSide = it) } } } else null,
                    )
                }
            }
            if (twoPane) Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(0.4f).fillMaxHeight()) {
                    info()
                    ScrollArea(Modifier.weight(1f), Am.SheetBg, PaddingValues(15.dp), 10.dp) { FileNameField(exportName, kind, options = false) }
                    TemplateToggle(asTemplate, { asTemplate = it }) { templateInfo = true }
                    bar()
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Line))
                ScrollArea(Modifier.weight(0.6f).fillMaxHeight(), Am.SheetBg, PaddingValues(15.dp), 10.dp) { kindCards() }
            } else {
                ScrollArea(Modifier.weight(1f), Am.SheetBg, PaddingValues(15.dp), 10.dp) {
                    FileNameField(exportName, kind, options = false)
                    kindCards()
                }
                TemplateToggle(asTemplate, { asTemplate = it }) { templateInfo = true }
                bar()
            }
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
            BundlePage(scene, engine, appSettings, community, { community = it }, info, bar, null, { page = null }, includeAddedEffects) { includeAddedEffects = it }
        }
        Presence(page == ExportKind.Audio) {
            AudioOnlyPage(settings.getValue(ExportKind.Audio), hasEncoder, { bitrateFits(it, true) }, info, bar, { settings[ExportKind.Audio] = it }, explain) { page = null }
        }
        Presence(page in FormatKinds) {
            val shown = lastPage
            FormatPage(shown, scene, settings.getValue(shown), info, bar, { naming(shown) }, { settings[shown] = it }, explain, { help = true }, if (shown == ExportKind.FrameAs) closeFrame else { { page = null } })
        }

        Presence(phase !is Phase.Idle) {
            val shown = lastPhase
            val background = animatedColor(if (shown is Phase.Done) Am.DoneBg else Scrim)
            Box(Modifier.fillMaxSize().presenceScrim(background).pointerInput(Unit) {}, contentAlignment = Alignment.Center) {
                if (shown is Phase.Running) RunningPanel(shown, heat.level, note) { shown.job.cancel() }
                else if (shown is Phase.Done) DoneScreen(shown.kind, shown.result, { phase = Phase.Idle }, staged = shown.staged != null, saving = saving, saved = shown.saved, onSave = { saveStaged(shown) }) { share(context, shown) }
            }
        }
    }

    }
    RiskDialog(risk, Navy, Alert, Am.Card) { risk = null; it?.proceed?.invoke() }
    if (templateInfo) {
        AlertDialog(
            onDismissRequest = { templateInfo = false },
            title = { Text(stringResource(R.string.template), color = Navy) },
            text = { Text(stringResource(R.string.also_keeps_the_project_with_its), color = Navy) },
            confirmButton = { DialogPill(stringResource(R.string.ok)) { templateInfo = false } },
            containerColor = Am.Card,
        )
    }
    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(stringResource(R.string.export_interpolation), color = Navy) },
            text = { SmoothingHelp(Navy, Modifier.heightIn(max = 420.dp)) },
            confirmButton = { DialogPill(stringResource(R.string.ok)) { help = false } },
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
                    if (f.hint) DialogPill(stringResource(R.string.report_button), Modifier.padding(top = 12.dp), primary = false) { reporting = true }
                }
            },
            confirmButton = { DialogPill(f.action?.text() ?: stringResource(R.string.ok)) { failure = null; f.proceed?.invoke() } },
            dismissButton = f.proceed?.let { { DialogPill(stringResource(R.string.cancel), primary = false) { failure = null } } },
            containerColor = Am.Card,
        )
    }
}

private val NoSound = txt(R.string.export_no_sound)

private fun <T> Choice<T>.tap(selected: Boolean, ask: (UiText, () -> Unit) -> Unit, onReason: (UiText) -> Unit, pick: () -> Unit) = when {
    reason == null -> pick()
    !risky -> onReason(reason)
    !selected -> ask(reason, pick)
    else -> Unit
}

@Composable
private fun AiSmoothHint(scene: Scene, s: ExportSettings, width: Int, height: Int) {
    if (s.interpolation != FrameInterpolation.Ai) return
    val gmac = remember(scene, s.interpolationLevel, s.fps, width, height) { AiSmooth.exportGmac(scene, s.interpolationLevel, s.fps, width, height) }
    Hint(aiSmoothNote(if (gmac > 0) aiSmoothEstimate(gmac, s.interpolationLevel) else null, s.interpolationLevel, clip = false, idle = gmac <= 0))
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
            HoldName(description)
        }
        Text(title, Modifier.align(Alignment.Center), color = Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(0.7.dp).background(Line))
    }
}

@Composable
internal fun InfoBlock(free: Long, estimate: Long, timeMs: Long?, calculating: Boolean, storage: StorageStatus) {
    val space = when (storage) {
        StorageStatus.Enough -> null
        is StorageStatus.Low -> stringResource(R.string.export_space_low)
        is StorageStatus.NotEnough -> stringResource(R.string.export_space_not_enough)
    }
    Column(Modifier.fillMaxWidth().background(Am.Card).padding(horizontal = 15.dp)) {
        InfoRow(stringResource(R.string.export_available_space), formatBytes(free) + (space?.let { " · $it" } ?: ""), space != null)
        Divider()
        InfoRow(stringResource(R.string.export_estimated_size), if (estimate < 0) stringResource(if (calculating) R.string.export_calculating else R.string.export_time_unknown) else formatBytes(estimate), false)
        Divider()
        InfoRow(stringResource(R.string.export_estimated_time), timeMs?.let { timeLabel(it).text() } ?: stringResource(if (calculating) R.string.export_calculating else R.string.export_time_unknown), false)
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
            Box(Modifier.size(36.dp).clickable(onClick = onInfo), contentAlignment = Alignment.Center) {
                Icon(ExportIcons.Info, stringResource(R.string.export_info), Modifier.size(20.dp), tint = Am.HomeDim)
                HoldName(stringResource(R.string.export_info))
            }
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
internal fun ExportBar(enabled: Boolean, blocked: UiText?, pill: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Am.Card).windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime)).padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 15.dp)) {
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
                if (right != null) {
                    Icon(right, stringResource(if (right == ExportIcons.Info) R.string.export_info else R.string.settings), Modifier.size(24.dp).alpha(if (enabled || right == ExportIcons.Info) 1f else Dimmed), tint = Am.HomeDim)
                    HoldName(stringResource(if (right == ExportIcons.Info) R.string.export_info else R.string.settings))
                }
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
    val ask = LocalRiskAsk.current
    Box(Modifier.fillMaxWidth().height(44.dp).clickable { open = true }) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(resolutionLabel(shortSide), Modifier.weight(1f), color = Navy, fontSize = 16.sp)
            Icon(ExportIcons.ChevronDown, null, Modifier.size(20.dp), tint = Navy)
        }
        DropdownMenu(open, { open = false }, containerColor = Am.Card, shape = if (app.absentmotion.platform.PlatformFeatures.desktop) androidx.compose.foundation.shape.RoundedCornerShape(10.dp) else androidx.compose.material3.MenuDefaults.shape) {
            choices.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.value.label, Modifier.alpha(if (c.enabled || c.risky) 1f else Dimmed), color = Navy, fontSize = 17.sp) },
                    trailingIcon = if (c.risky) { { Icon(ExportIcons.Warning, null, Modifier.size(16.dp), tint = Alert) } } else null,
                    onClick = { open = false; c.tap(c.value.shortSide == shortSide, ask, onReason) { onPick(c.value.shortSide) } },
                )
            }
        }
    }
}

@Composable
private fun PageFrame(title: String, onBack: () -> Unit, icon: ImageVector = ExportIcons.Back, description: String = stringResource(R.string.export_back), content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().presence(slide = 1f, fade = false).background(Am.SheetBg).pointerInput(Unit) {}, contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = SheetMaxWidth).fillMaxSize().imePadding()) {
            TitleBar(title, icon, description, onBack)
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
    val rates = VideoFrameRates + scene.fps
    val (w, h) = exportSize(scene, s.shortSide, even = true)
    Field(stringResource(R.string.export_resolution_heading)) {
        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Am.Card)) { ResolutionDropdown(s.shortSide, resolutionChoices(s, check), onReason) { onChange(s.copy(shortSide = it)) } }
    }
    Field(stringResource(R.string.export_format)) { Segments(containerChoices(s, check), s.container, { it.label }, onReason) { onChange(withContainer(s, it, check)) } }
    Field(stringResource(R.string.export_codec)) { Segments(codecChoices(s, check), s.codec, { it.shortName }, onReason) { onChange(s.copy(codec = it)) } }
    FrameRateField(rates, s, onChange)
    SmoothingFields(scene, s, w, h, onChange, onReason, onHelp)
    val report by DeviceCapabilities.of(LocalContext.current).report.collectAsState()
    Field(stringResource(R.string.export_quality), "%.1f Mbps".format(Locale.ROOT, videoBitrate(w, h, s, report?.bitrates(s, w, h)) / 1e6)) {
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
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onContinue != null) DialogPill(stringResource(R.string.export_continue_anyway), primary = false, onClick = onContinue)
                if (use != null) DialogPill(use, onClick = onUse)
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
                HoldName(stringResource(R.string.export_what_is, title))
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
internal fun FileNameField(name: ExportName, kind: ExportKind, options: Boolean = true) {
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

private val Mixed: TextStyle @Composable get() = TextStyle(textDirection = TextDirection.Content, fontFamily = androidx.compose.material3.LocalTextStyle.current.fontFamily)

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
    val ask = LocalRiskAsk.current
    Row(Modifier.fillMaxWidth().height(if (DESK) 36.dp else 42.dp).clip(RoundedCornerShape(4.dp)).background(Am.Card).padding(3.dp)) {
        options.forEach { c ->
            val on = c.value == selected
            val fill = animatedColor(if (on) Navy else Am.Card)
            val text = animatedColor(if (on) Am.OnNavyAccent else Navy)
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(fill).deskPress(4.dp).clickable { c.tap(on, ask, onReason) { onPick(c.value) } },
                contentAlignment = Alignment.Center,
            ) {
                Row(Modifier.alpha(if (c.enabled || c.risky) 1f else Dimmed), verticalAlignment = Alignment.CenterVertically) {
                    if (c.risky) Icon(ExportIcons.Warning, null, Modifier.size(13.dp), tint = if (on) text else Alert)
                    Text(label(c.value), Modifier.padding(horizontal = 2.dp), color = text, fontWeight = FontWeight.Medium, maxLines = 1, style = Mixed,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(9.sp, size))
                }
            }
        }
    }
}

@Composable
private fun <T> StepSlider(values: List<T>, selected: T, onPick: (T) -> Unit) =
    ExportSlider(values.indexOf(selected).coerceAtLeast(0).toFloat() / (values.size - 1)) { onPick(values[(it * (values.size - 1)).roundToInt()]) }

@Composable
private fun ExportSlider(value: Float, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    Box(
        Modifier.fillMaxWidth().height(36.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f); setProgress { change(it.coerceIn(0f, 1f)); true } }
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

@Composable
private fun FrameRateField(rates: List<Int>, s: ExportSettings, onChange: (ExportSettings) -> Unit) {
    val sorted = rates.distinct().sorted()
    Field(stringResource(R.string.export_frame_rate), "${s.fps}fps") {
        StepSlider(sorted, s.fps) { onChange(s.copy(fps = it)) }
        Ends("${sorted.first()}fps", "${sorted.last()}fps")
    }
}

@Composable
private fun SmoothingFields(scene: Scene, s: ExportSettings, w: Int, h: Int, onChange: (ExportSettings) -> Unit, onReason: (UiText) -> Unit, onHelp: () -> Unit) {
    Field(stringResource(R.string.export_interpolation), onHelp = onHelp) {
        Segments(FrameInterpolation.entries.map { if (it == FrameInterpolation.Ai) Tier.choice(it, Feature.AiSmooth) else Choice(it) }, s.interpolation, { stringResource(it.label) }, onReason) { onChange(s.copy(interpolation = it)) }
        Segments(levelChoices(s), s.interpolationLevel, { stringResource(it.label) }, onReason) { onChange(s.copy(interpolationLevel = it)) }
        Hint(stringResource(R.string.export_interpolation_hint))
        AiSmoothHint(scene, s, w, h)
    }
    Field(stringResource(R.string.export_motion_blur), onHelp = onHelp) {
        Segments(MotionBlur.entries.map { Choice(it) }, s.motionBlur, { stringResource(it.label) }, onReason) { onChange(s.copy(motionBlur = it)) }
        Hint(stringResource(R.string.export_motion_blur_hint))
    }
}

@Composable
internal fun FormatPage(
    kind: ExportKind, scene: Scene, s: ExportSettings, info: @Composable () -> Unit, bar: @Composable () -> Unit, naming: @Composable () -> Unit,
    onChange: (ExportSettings) -> Unit, onReason: (UiText) -> Unit, onHelp: () -> Unit, onBack: () -> Unit,
) {
    val close = kind == ExportKind.FrameAs
    PageFrame(stringResource(kind.title()), onBack, if (close) ExportIcons.Close else ExportIcons.Back, stringResource(if (close) R.string.cancel else R.string.export_back)) {
        info()
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), Am.SheetBg, PaddingValues(start = 15.dp, end = 15.dp, top = 16.dp, bottom = 20.dp), GroupGap) {
            naming()
            FormatFields(kind, scene, s, onReason, onHelp, onChange)
        }
        bar()
    }
}

@Composable
private fun FormatFields(kind: ExportKind, scene: Scene, s: ExportSettings, onReason: (UiText) -> Unit, onHelp: () -> Unit, onChange: (ExportSettings) -> Unit) {
    val images = kind != ExportKind.Gif
    val moving = kind != ExportKind.FrameAs
    val (w, h) = exportSize(scene, s.shortSide, even = false)
    if (moving) Field(stringResource(if (images) R.string.export_resolution else R.string.export_size), "${w}x$h") {
        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Am.Card)) {
            ResolutionDropdown(s.shortSide, (if (images) VideoResolutions else GifResolutions).map { Choice(it) }, onReason) { onChange(s.copy(shortSide = it)) }
        }
    }
    if (images) Field(stringResource(R.string.export_format), if (moving) null else "${scene.width}x${scene.height}") {
        ImageKind.entries.map { Choice(it, imageProblem(it, android.os.Build.VERSION.SDK_INT)) }.chunked(3).forEach { row ->
            Segments(row, s.image, { stringResource(it.label) }, onReason) { onChange(s.copy(image = it)) }
        }
    }
    if (moving) {
        FrameRateField(if (images) VideoFrameRates + scene.fps else GifFrameRates, s, onChange)
        SmoothingFields(scene, s, w, h, onChange, onReason, onHelp)
    }
    if (images) Field(stringResource(R.string.export_quality), "${s.imageQuality}%", if (s.image.lossy) null else txt(R.string.export_reason_image_lossless, txt(s.image.label)), onReason) {
        StepSlider(ImageQualities, s.imageQuality) { onChange(s.copy(imageQuality = it)) }
        Ends(stringResource(R.string.export_quality_low), stringResource(R.string.export_quality_high))
    }
    Field(stringResource(R.string.export_transparent_becomes), reason = if (images && s.image.alpha) txt(R.string.export_reason_image_alpha, txt(s.image.label)) else null, onReason = onReason) {
        Segments(listOf(Choice(BackgroundWhite), Choice(BackgroundBlack)), s.background, { stringResource(if (it == BackgroundWhite) R.string.export_white else R.string.export_black) }, onReason) { onChange(s.copy(background = it)) }
    }
}

private fun levelChoices(s: ExportSettings) =
    SmoothLevel.entries.map {
        when {
            s.interpolation == FrameInterpolation.Off -> Choice(it, txt(R.string.export_interpolation_pick_method))
            s.interpolation == FrameInterpolation.Flow && it == SmoothLevel.High -> Tier.choice(it, Feature.FlowHigh)
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
private fun BoxScope.RunningPanel(p: Phase.Running, heat: ThermalLevel, note: UiText?, onCancel: () -> Unit) {
    val percent = (p.progress * 100).toInt().coerceIn(0, 100)
    val left by produceState<Int?>(null, p) { while (true) { value = p.clock.secondsLeft(); delay(250) } }
    Column(Modifier.align(Alignment.Center).presence(), horizontalAlignment = Alignment.CenterHorizontally) {
        RunningTitle(p, Am.OnScrim, 18.sp)
        Spacer(Modifier.height(15.dp))
        Box(Modifier.width(220.dp).height(7.dp).clip(RoundedCornerShape(5.dp)).background(Am.OnScrim.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(p.progress.coerceIn(0f, 1f)).fillMaxHeight().background(Am.OnScrimAccent))
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
        AccentPill(stringResource(R.string.cancel), primary = false, onClick = onCancel)
    }
    Text(
        stringResource(if (p.kind in BackgroundKinds) R.string.export_running_leave else R.string.keep_absentmotion_open_and_your_screen),
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 30.dp, vertical = 30.dp),
        color = Am.OnScrim, fontSize = 12.sp, textAlign = TextAlign.Center,
    )
}

@Composable
private fun RunningTitle(p: Phase.Running, color: Color, size: TextUnit, weight: FontWeight? = null) = Box(contentAlignment = Alignment.Center) {
    p.reserve?.let { Text(stringResource(it), Modifier.alpha(0f).clearAndSetSemantics {}, fontSize = size, fontWeight = weight, textAlign = TextAlign.Center) }
    Text(stringResource(p.title), color = color, fontSize = size, fontWeight = weight, textAlign = TextAlign.Center)
}

@StringRes
private fun runningTitle(kind: ExportKind): Int = when (kind) {
    ExportKind.Video -> R.string.export_running_video
    ExportKind.Audio -> R.string.export_running_audio
    ExportKind.Gif -> R.string.export_running_gif
    ExportKind.Sequence -> R.string.export_running_sequence
    else -> R.string.export_running
}

private val StagedKinds = setOf(ExportKind.Video, ExportKind.Gif, ExportKind.Frame, ExportKind.FrameAs)

private val BackgroundKinds = setOf(ExportKind.Video, ExportKind.Audio, ExportKind.Gif, ExportKind.Sequence, ExportKind.Package)
private val SizedKinds = setOf(ExportKind.Video, ExportKind.Gif, ExportKind.Sequence)

private fun timeKey(kind: ExportKind, s: ExportSettings): String? = when (kind) {
    ExportKind.Video -> if (s.interpolation == FrameInterpolation.Ai) "time.video.ai" else "time.video"
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
    free: Long, estimate: Long, timeMs: Long?, calculating: Boolean, storage: StorageStatus, blocked: UiText?,
    asTemplate: Boolean, onTemplate: (Boolean) -> Unit, onTemplateInfo: () -> Unit, bundleFields: @Composable () -> Unit,
    phase: Phase, heat: ThermalLevel, note: UiText?, onCancel: () -> Unit, onDone: () -> Unit, onShare: () -> Unit,
    onStart: () -> Unit, onClose: () -> Unit, title: String, still: (@Composable (Modifier) -> Unit)? = null,
) {
    val single = kinds.size == 1
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DisposableEffect(Unit) {
        DeskModals.open++
        DeskModals.fade = 1f
        onDispose { DeskModals.open--; if (DeskModals.open == 0) DeskModals.fade = 0f }
    }
    val idle = phase is Phase.Idle
    val closable = phase !is Phase.Running
    val closableNow by rememberUpdatedState(closable)
    val closeNow by rememberUpdatedState(onClose)
    val s = settings.getValue(kind)
    val onChange: (ExportSettings) -> Unit = { settings[kind] = it }
    val onReason = explain
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = DeskModals.ScrimAlpha)).pointerInput(Unit) { detectTapGestures { if (closableNow) closeNow() } }
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
                    Text(title, color = Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(scene.title, Modifier.weight(1f).padding(start = 12.dp), color = NoteGrey, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).deskPress(10.dp).clickable(enabled = closable, onClick = onClose), Alignment.Center) {
                        Icon(ExportIcons.Close, stringResource(R.string.cancel), Modifier.size(22.dp), tint = Navy)
                        HoldName(stringResource(R.string.cancel))
                    }
                }
                Divider()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    val side = (width * 0.4f).coerceIn(280.dp, 420.dp)
                    Column(Modifier.width(side).fillMaxHeight().padding(20.dp)) {
                        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.PreviewBg)) {
                            val done = phase as? Phase.Done
                            if (done != null) app.absentmotion.platform.ExportMediaPreview(done.kind, done.result.uris)
                            else if (still != null) still(Modifier.fillMaxSize())
                            else DeskStill(scene, if (fromList && kind != ExportKind.Frame && kind != ExportKind.FrameAs) scene.totalTime / 2 else scene.frameStart(frame), engine, Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("${scene.width} x ${scene.height}  \u00B7  ${((scene.totalTime + 500) / 1000).let { "%d:%02d".format(Locale.ROOT, it / 60, it % 60) }}  \u00B7  ${scene.fps}fps", color = NoteGrey, fontSize = 13.sp, maxLines = 1)
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Line))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (phase) {
                            Phase.Idle -> Column(Modifier.fillMaxSize().imePadding().deskVerticalScroll(color = Navy).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                FileNameField(exportName, kind)
                                Column(verticalArrangement = Arrangement.spacedBy(FieldGap)) {
                                    if (!single) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    optionNote(kind, scene, s)?.takeIf { still == null }?.let { Hint(it.text()) }
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
                                    ExportKind.FrameAs, ExportKind.Sequence, ExportKind.Gif -> FormatFields(kind, scene, s, onReason, onHelp, onChange)
                                    else -> Unit
                                }
                            }
                            is Phase.Running -> Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                                val percent = (phase.progress * 100).toInt().coerceIn(0, 100)
                                val left by produceState<Int?>(null, phase) { while (true) { value = phase.clock.secondsLeft(); delay(250) } }
                                RunningTitle(phase, Navy, 20.sp, FontWeight.Bold)
                                Spacer(Modifier.height(20.dp))
                                Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = 0.12f))) {
                                    Box(Modifier.fillMaxWidth(phase.progress.coerceIn(0f, 1f)).fillMaxHeight().background(Am.Accent))
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
                    DeskStat(stringResource(R.string.export_estimated_size), if (estimate < 0) stringResource(if (calculating) R.string.export_calculating else R.string.export_time_unknown) else formatBytes(estimate), null)
                    DeskStat(stringResource(R.string.export_estimated_time), timeMs?.let { timeLabel(it).text() } ?: stringResource(if (calculating) R.string.export_calculating else R.string.export_time_unknown), null)
                    Spacer(Modifier.weight(1f))
                    if (!single) Row(Modifier.clip(RoundedCornerShape(8.dp)).deskPress(8.dp).clickable { onTemplate(!asTemplate) }.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.also_keep_a_copy_as_a), color = Navy, fontSize = 13.sp, maxLines = 1)
                        Box(Modifier.size(32.dp).clickable(onClick = onTemplateInfo), Alignment.Center) {
                            Icon(ExportIcons.Info, stringResource(R.string.export_info), Modifier.size(18.dp), tint = Am.HomeDim)
                            HoldName(stringResource(R.string.export_info))
                        }
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
internal fun BundlePage(
    scene: Scene, engine: EngineThread, settings: app.absentmotion.library.AppSettings, community: app.absentmotion.export.CommunityDetails?, onChange: (app.absentmotion.export.CommunityDetails?) -> Unit,
    info: @Composable () -> Unit, bar: @Composable () -> Unit, preset: PresetShare?, onBack: () -> Unit,
    includeAddedEffects: Boolean = true, onIncludeAddedEffects: (Boolean) -> Unit = {},
) {
    PageFrame(stringResource(if (preset != null) R.string.preset_share else R.string.project_bundle), onBack) {
        info()
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), Am.SheetBg, PaddingValues(start = 15.dp, end = 15.dp, top = 16.dp, bottom = 20.dp), GroupGap) {
            BundleFields(scene, engine, settings, community, preset, true, includeAddedEffects, onIncludeAddedEffects, onChange)
        }
        bar()
    }
}

@Composable
private fun BundleFields(
    scene: Scene, engine: EngineThread, settings: app.absentmotion.library.AppSettings, community: app.absentmotion.export.CommunityDetails?,
    preset: PresetShare?, preview: Boolean = false, includeAddedEffects: Boolean, onIncludeAddedEffects: (Boolean) -> Unit,
    onChange: (app.absentmotion.export.CommunityDetails?) -> Unit,
) {
    if (preset == null) Field(stringResource(R.string.project_bundle)) {
        Segments(listOf(Choice(false), Choice(true)), community != null, { stringResource(if (it) R.string.bundle_community else R.string.project_bundle) }, {}) { wanted ->
            if (!wanted) onChange(null)
            else if (community == null) onChange(app.absentmotion.export.CommunityDetails(scene.title.trim().take(80), creator = settings.communityCreator, thumbMs = scene.totalTime / 2))
        }
        Hint(stringResource(if (community == null) R.string.one_absproj_package_with_the_project else R.string.bundle_community_note))
    }
    if (community != null) CommunityFields(scene, engine, settings, community, preset, preview, includeAddedEffects, onChange)
    SaveInField(settings)
    val effectCount = remember(scene) { scene.addedEffectUses().size }
    if (effectCount > 0) {
        SwitchRow(stringResource(R.string.bundle_include_added_effects, effectCount), includeAddedEffects, true, onIncludeAddedEffects)
    }
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
private fun CommunityFields(scene: Scene, engine: EngineThread, settings: app.absentmotion.library.AppSettings, community: app.absentmotion.export.CommunityDetails, preset: PresetShare?, preview: Boolean, includeAddedEffects: Boolean, onChange: (app.absentmotion.export.CommunityDetails?) -> Unit) {
    if (preset != null) PlaceholderField(scene, engine, preset, preview)
    else Field(stringResource(R.string.community_thumbnail)) {
        val ratio = scene.width.coerceAtLeast(1).toFloat() / scene.height.coerceAtLeast(1)
        Box(Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 2f)).clip(RoundedCornerShape(8.dp)).background(Am.Card)) {
            DeskStill(scene, community.thumbMs, engine, Modifier.fillMaxSize())
            SquareChooser(scene.width, scene.height, community.square(scene.width, scene.height), 12.dp, Modifier.fillMaxSize(),
                if (DESK) { square -> onChange(community.withSquare(square, scene.width, scene.height)) } else null)
        }
        var picking by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(4.dp)).background(Navy).clickable { picking = true }, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.community_choose_thumbnail), color = Am.OnNavy, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, style = Mixed)
        }
        if (picking) ThumbnailPicker(scene, engine, community, { onChange(it); picking = false }) { picking = false }
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
    val context = LocalContext.current
    val bytes = remember(scene, includeAddedEffects) {
        scene.communityBytes(app.absentmotion.library.MediaAccess.forApp(context)) +
            if (includeAddedEffects) AddedEffectStore(context.filesDir).bundleBytes(scene) else 0L
    }
    Field(stringResource(R.string.community_size), "${app.absentmotion.export.communitySizeText(bytes)} / 20 MB") {
        if (bytes > app.absentmotion.export.CommunityLimitBytes) Text(stringResource(R.string.community_too_big, app.absentmotion.export.communitySizeText(bytes)), color = Am.HomeAccent, fontSize = 13.sp, lineHeight = 18.sp)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onChange(community.copy(confirmed = !community.confirmed)) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Checkbox(community.confirmed, { onChange(community.copy(confirmed = it)) },
            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Navy, uncheckedColor = Navy, checkmarkColor = Am.OnNavy))
        Text(stringResource(R.string.community_confirm), color = Navy, fontSize = if (DESK) 13.sp else 15.sp)
    }
    var guidelinesOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(community.guidelinesConfirmed, { onChange(community.copy(guidelinesConfirmed = it)) },
            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Navy, uncheckedColor = Navy, checkmarkColor = Am.OnNavy))
        val link = stringResource(R.string.community_guidelines_link)
        val sentence = stringResource(R.string.community_follows_guidelines, link)
        val start = sentence.indexOf(link)
        val label = androidx.compose.ui.text.buildAnnotatedString {
            append(sentence)
            if (start >= 0) addLink(androidx.compose.ui.text.LinkAnnotation.Clickable("guidelines",
                androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = Am.HomeAccent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))) { guidelinesOpen = true }, start, start + link.length)
        }
        Text(label, Modifier.weight(1f).padding(vertical = 8.dp), color = Navy, fontSize = if (DESK) 13.sp else 15.sp)
    }
    if (guidelinesOpen) app.absentmotion.ui.home.CommunityGuidelines { guidelinesOpen = false }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextBox(@StringRes label: Int, value: String, limit: Int, lines: Int = 1, done: (() -> Unit)? = null, onChange: (String) -> Unit) {
    val title = stringResource(label)
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    var focused by remember { mutableStateOf(false) }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val below = with(density) { 52.dp.toPx() }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        snapshotFlow { ime.getBottom(density) }.collectLatest {
            withFrameNanos { }
            withFrameNanos { }
            requester.bringIntoView(androidx.compose.ui.geometry.Rect(0f, 0f, box.width.toFloat(), box.height + below))
        }
    }
    Field(title) {
        BasicTextField(
            value = value, onValueChange = { onChange(it.take(limit)) },
            singleLine = lines == 1, minLines = lines,
            textStyle = Mixed.copy(color = Navy, fontSize = if (DESK) 14.sp else 16.sp),
            cursorBrush = SolidColor(Navy),
            keyboardOptions = KeyboardOptions(imeAction = if (lines == 1) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { done?.invoke(); keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester).onSizeChanged { box = it }.onFocusEvent { focused = it.isFocused }
                .semantics { contentDescription = title }
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
                        HoldName(stringResource(R.string.export_back))
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
                Box(Modifier.fillMaxWidth().height(lower).background(Am.TimelineBg)) { Ltr { app.absentmotion.ui.editor.Timeline(state, Modifier.fillMaxSize()) } }
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

internal class PresetShare(val preset: app.absentmotion.library.SavedPreset, val choice: app.absentmotion.library.PresetPlaceholder, val onChoice: (app.absentmotion.library.PresetPlaceholder) -> Unit)

internal object PresetSharing {
    var preset by mutableStateOf<app.absentmotion.library.SavedPreset?>(null)
    var revision by androidx.compose.runtime.mutableIntStateOf(0)
}

@Composable
internal fun PresetShareScreen(preset: app.absentmotion.library.SavedPreset, engine: EngineThread, settings: app.absentmotion.library.AppSettings, onClose: () -> Unit) {
    val context = LocalContext.current
    var choice by remember { mutableStateOf(preset.placeholder) }
    val scene by produceState<Scene?>(null, choice) {
        value = app.absentmotion.library.placeholderScene(context, preset, choice)
            ?: app.absentmotion.library.PresetPlaceholder.Picture(preset.pictureIndex).let { fallback -> app.absentmotion.library.placeholderScene(context, preset, fallback)?.also { choice = fallback } }
        if (value == null) onClose()
    }
    scene?.let { ExportScreen(it, 0, engine, settings, fromProjectList = true, preset = PresetShare(preset, choice) { picked -> choice = picked }, onClose = onClose) }
}

@Composable
private fun PlaceholderField(scene: Scene, engine: EngineThread, share: PresetShare, preview: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = app.absentmotion.platform.rememberMediaPicker { uri ->
        if (uri != null) scope.launch {
            val media = app.absentmotion.media.mediaDeclaration(context, uri)
            if (media != null) share.onChoice(app.absentmotion.library.PresetPlaceholder.Media(media))
            else app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.library_err_unsupported_type), app.absentmotion.platform.Toast.LENGTH_SHORT).show()
        }
    }
    val choice = share.choice
    Field(stringResource(R.string.preset_placeholder)) {
        if (preview) PresetStill(scene, engine, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(8.dp)).checkerboard(5.dp))
        if (share.preset.ownLook) PlaceholderRow(stringResource(if (share.preset.layer?.kind == "text") R.string.preset_placeholder_text else R.string.preset_placeholder_shape), choice == app.absentmotion.library.PresetPlaceholder.Own) {
            share.onChoice(app.absentmotion.library.PresetPlaceholder.Own)
        }
        val picture = choice as? app.absentmotion.library.PresetPlaceholder.Picture
        PlaceholderRow(stringResource(R.string.preset_placeholder_random), picture != null, {
            Box(
                Modifier.heightIn(min = 40.dp).alpha(if (picture != null) 1f else 0f).clip(RoundedCornerShape(4.dp)).deskPress(4.dp)
                    .clickable(enabled = picture != null) { picture?.let { share.onChoice(it.copy(index = (it.index + kotlin.random.Random.nextInt(1, app.absentmotion.library.PresetPictures.size)) % app.absentmotion.library.PresetPictures.size)) } }
                    .padding(horizontal = 12.dp),
                Alignment.Center,
            ) { Text(stringResource(R.string.preset_shuffle), color = Navy, fontSize = if (DESK) 13.sp else 15.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
        }) {
            if (picture == null) share.onChoice(app.absentmotion.library.PresetPlaceholder.Picture(share.preset.pictureIndex))
        }
        PlaceholderRow(stringResource(R.string.preset_placeholder_choose), choice is app.absentmotion.library.PresetPlaceholder.Media) { picker.launch() }
        Hint(stringResource(R.string.preset_placeholder_note))
    }
}

@Composable
private fun PlaceholderRow(label: String, selected: Boolean, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (DESK) 40.dp else 48.dp).clip(RoundedCornerShape(4.dp)).background(Am.Card).deskPress(4.dp)
            .clickable(onClick = onClick).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected)
        Text(label, Modifier.weight(1f).padding(start = 10.dp), color = Navy, fontSize = if (DESK) 13.sp else 15.sp, lineHeight = if (DESK) 17.sp else 19.sp, style = Mixed)
        trailing()
    }
}

@Composable
private fun PresetStill(scene: Scene, engine: EngineThread, modifier: Modifier) {
    val picture by produceState<ImageBitmap?>(null, scene) {
        value = try {
            withContext(Dispatchers.IO) { app.absentmotion.library.renderPresetPicture(engine, scene)?.let { app.absentmotion.ui.home.decodeCommunityThumb(it) } }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }
    Box(modifier, Alignment.Center) {
        picture?.let { Image(it, null, Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Fit) }
    }
}
