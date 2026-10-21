package app.absentmotion.ui.export

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.AudioCodec
import app.absentmotion.export.AudioFormat
import app.absentmotion.export.Choice
import app.absentmotion.export.DeviceCapabilities
import app.absentmotion.export.ExportPrefs
import app.absentmotion.export.ExportSizes
import app.absentmotion.export.ExportTimes
import app.absentmotion.export.StorageStatus
import app.absentmotion.export.Support
import app.absentmotion.export.ThermalLevel
import app.absentmotion.export.ThermalMonitor
import app.absentmotion.export.storageStatus
import app.absentmotion.export.ExportAudio
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportResult
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.Exporter
import app.absentmotion.export.FrameInterpolation
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
import app.absentmotion.export.safeName
import app.absentmotion.export.sampleRateChoices
import app.absentmotion.export.shortName
import app.absentmotion.export.suggestionLabel
import app.absentmotion.export.thermalLabel
import app.absentmotion.export.timeLabel
import app.absentmotion.export.videoBitrate
import app.absentmotion.export.withContainer
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.ChromeIcons
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Navy get() = Am.Navy
private val NoteGrey get() = Am.HomeFaint
private val ValueGrey get() = Am.HomeDim
private val Line get() = Am.HomeLine
private val Scrim get() = Am.Scrim
private val Alert get() = Am.HomeAccent

private val GroupGap = 18.dp
private val FieldGap = 6.dp
private const val Dimmed = 0.38f

private val SheetKinds = setOf(ExportKind.Gif, ExportKind.Sequence, ExportKind.FrameAs)

private sealed interface Phase {
    data object Idle : Phase
    class Running(val kind: ExportKind, val startNs: Long, val job: Job) : Phase
    class Done(val kind: ExportKind, val result: ExportResult) : Phase
}

private class Failure(@StringRes val title: Int, val text: UiText, val action: UiText? = null, val proceed: (() -> Unit)? = null, val hint: Boolean = false)

@Composable
internal fun ExportScreen(scene: Scene, frame: Int, engine: EngineThread, fromProjectList: Boolean, outdated: Outdated? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = remember { Exporter(context, engine, MediaStoreExport(context)) }
    val kinds = remember { listOf(ExportKind.Video, ExportKind.Audio, ExportKind.Package, ExportKind.Frame, ExportKind.FrameAs, ExportKind.Sequence, ExportKind.Gif) + if (fromProjectList) listOf(ExportKind.Xml) else emptyList() }
    var kind by remember { mutableStateOf(ExportKind.Video) }
    val prefs = remember { ExportPrefs(context) }
    val settings = remember { mutableStateMapOf<ExportKind, ExportSettings>().apply { ExportKind.entries.forEach { put(it, prefs.load(it, scene)) } } }
    LaunchedEffect(settings) {
        var last = settings.toMap()
        snapshotFlow { settings.toMap() }.collect { now ->
            now.forEach { (k, v) -> if (last[k] != v) prefs.save(k, v) }
            last = now
        }
    }
    var page by remember { mutableStateOf<ExportKind?>(null) }
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
    val anyAudio = remember(scene) { ExportAudio.mixFor != null && AudioClip.of(scene).isNotEmpty() }
    val hasAudio by produceState(anyAudio, scene) {
        value = anyAudio
        if (anyAudio) value = withContext(Dispatchers.IO) { OfflineMix.audible(context, scene).isNotEmpty() }
    }
    val gifSettings = settings.getValue(ExportKind.Gif)
    val gifSample by produceState<GifSample?>(null, kind, scene, gifSettings, phase) {
        value = null
        if (kind == ExportKind.Gif && phase is Phase.Idle) {
            try { value = measureGif(engine, scene, gifSettings) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("AMExport", "Cannot sample GIF size", e) }
        }
    }
    var free by remember { mutableLongStateOf(freeSpace()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) free = freeSpace() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(phase) { free = freeSpace() }

    val caps = remember { DeviceCapabilities.of(context) }
    val report by caps.report.collectAsState()
    LaunchedEffect(caps) {
        try { caps.load() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w("AMExport", "Cannot read the encoders", e) }
    }
    val sizes = remember { ExportSizes(context) }
    val rawEstimate = if (kind == ExportKind.Gif) gifSample?.bytes ?: -1L else remember(kind, scene, settings.getValue(kind), hasAudio, report) {
        val s = settings.getValue(kind)
        val bitrates = if (kind == ExportKind.Video) exportSize(scene, s.shortSide, even = true).let { (w, h) -> report?.encoderFor(s.codec, w, h)?.bitrates } else null
        estimateBytes(kind, scene, s, hasAudio, bitrates)
    }
    val sizeKey = ExportSizes.key(kind, settings.getValue(kind))
    val estimate = remember(rawEstimate, sizeKey, phase is Phase.Idle) { if (rawEstimate < 0 || sizeKey == null) rawEstimate else (rawEstimate * sizes.ratio(sizeKey)).toLong() }
    val check: (ExportSettings) -> Support = remember(report, scene, hasAudio) { { caps.check(it, scene, hasAudio) } }
    val hasEncoder: (String) -> Boolean = remember(report) { { mime -> report?.encodersFor(mime)?.isNotEmpty() ?: true } }
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
    val timeMs = remember(kind, current, report, heat.level, estimate, phase is Phase.Idle, gifSample) {
        when (kind) {
            ExportKind.Video -> times.estimateMs(current, scene, report?.probe, heat.level, report)
            ExportKind.Gif -> gifSample?.let { gifTimeMs(frameCount(scene, current.fps), it, times.estimateMs(current, scene, report?.probe, heat.level)) }
            ExportKind.Sequence -> times.estimateMs(current, scene, report?.probe, heat.level)
            ExportKind.Package -> estimate.coerceAtLeast(0) / 100_000
            ExportKind.Audio -> scene.totalTime / if (current.audioFormat.lossless) 20L else 14L
            else -> 0L
        }
    }
    val storage = if (estimate < 0) StorageStatus.Enough else storageStatus(estimate, free)
    val blocked = exportBlock(storage, if (kind == ExportKind.Video) support else Support.Supported)
    val explain: (UiText) -> Unit = { failure = Failure(R.string.export_not_available, it) }

    app.absentmotion.platform.ExportBars(dark = false, running = phase is Phase.Running)
    BackHandler {
        when (val p = phase) {
            is Phase.Running -> p.job.cancel()
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

    fun start(ignoreMissing: Boolean = false) {
        val s = settings.getValue(kind)
        if (scene.layers.isEmpty() && kind != ExportKind.Xml && kind != ExportKind.Package) {
            failure = Failure(R.string.export_failed_title, txt(R.string.export_failed_empty))
            return
        }
        if (kind == ExportKind.Audio && !hasAudio) {
            failure = Failure(R.string.export_nothing_title, NoSound)
            return
        }
        if (kind == ExportKind.Video && support is Support.Unsupported) {
            failure = Failure(R.string.export_not_supported_title, codecWarning(s, support) ?: txt(R.string.export_block_unsupported), better?.let { suggestionLabel(s, it) }, better?.let { ::useBetter })
            return
        }
        free = freeSpace()
        if (kind != ExportKind.Gif && storageStatus(estimate, free) is StorageStatus.NotEnough) {
            failure = Failure(R.string.export_storage_title, (storageStatus(estimate, free) as StorageStatus.NotEnough).message)
            return
        }
        val chosen = kind
        val estimated = rawEstimate
        prefs.save(chosen, s)
        val alsoTemplate = asTemplate
        val name = safeName(scene.title) + " " + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date())
        progress = 0f
        val job = scope.launch {
            try {
                if (chosen != ExportKind.Xml && !ignoreMissing) {
                    val missing = withContext(Dispatchers.IO) {
                        scene.node.mediaUris().filter { uri ->
                            try { context.contentResolver.openAssetFileDescriptor(app.absentmotion.platform.Uri.parse(uri), "r")?.close() == null } catch (e: Exception) { true }
                        }.map { scene.media(it)?.filename?.ifEmpty { null } ?: it }
                    }
                    if (missing.isNotEmpty()) {
                        failure = Failure(R.string.export_missing_title, txt(R.string.export_missing_text, missing.joinToString("\n")), txt(R.string.export_anyway), proceed = { start(true) })
                        phase = Phase.Idle
                        return@launch
                    }
                }
                var predicted = estimated
                if (chosen == ExportKind.Gif) {
                    val measured = measureGif(engine, scene, s).bytes
                    predicted = measured
                    free = freeSpace()
                    if (storageStatus(measured, free) is StorageStatus.NotEnough) {
                        failure = Failure(R.string.export_storage_title, txt(R.string.export_storage_text))
                        phase = Phase.Idle
                        return@launch
                    }
                }
                val result = exporter.export(chosen, scene, s, frame, name) { progress = it }
                ExportSizes.key(chosen, s)?.let { key ->
                    withContext(Dispatchers.IO) {
                        val written = result.uris.sumOf { uri ->
                            runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } }.getOrNull() ?: 0L
                        }
                        sizes.record(key, predicted, written)
                    }
                }
                if (alsoTemplate) try {
                    TemplateStore(context).add(scene, scene.frameStart(frame), engine)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("AMExport", "could not store the template", e)
                    app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.export_template_failed), app.absentmotion.platform.Toast.LENGTH_LONG).show()
                }
                phase = Phase.Done(chosen, result)
            } catch (e: CancellationException) {
                phase = Phase.Idle
                throw e
            } catch (e: Throwable) {
                Log.e("AMExport", "export failed", e)
                phase = Phase.Idle
                failure = Failure(R.string.export_failed_title, txt(R.string.library_text_value, e.message ?: e.toString()), hint = true)
            }
        }
        phase = Phase.Running(chosen, System.nanoTime(), job)
    }

    val info: @Composable () -> Unit = { InfoBlock(free, estimate, timeMs, heat.level, storage) }
    val bar: @Composable () -> Unit = { ExportBar(enabled = phase == Phase.Idle, blocked = blocked, onClick = ::start) }

    Box(Modifier.fillMaxSize().background(Am.SheetBg).pointerInput(Unit) {}.enterFade(12.dp)) {
        Column(Modifier.fillMaxSize()) {
            TitleBar(stringResource(R.string.export_title), ExportIcons.Close, stringResource(R.string.cancel), onClose)
            info()
            ScrollArea(Modifier.weight(1f), Am.SheetBg, PaddingValues(15.dp), 10.dp) {
                kinds.forEachIndexed { i, k ->
                    val s = settings.getValue(k)
                    val silent = k == ExportKind.Audio && !hasAudio
                    OptionCard(
                        selected = kind == k, enabled = !silent, icon = k.icon(), title = stringResource(k.title()),
                        note = if (silent) stringResource(R.string.export_no_sound_short) else optionNote(k, scene, s)?.text(),
                        right = when (k) { ExportKind.Package -> ExportIcons.Info; ExportKind.Video, ExportKind.Audio, ExportKind.FrameAs, ExportKind.Sequence, ExportKind.Gif -> ExportIcons.ChevronRight; else -> null },
                        onSelect = { if (silent) explain(NoSound) else { kind = k; if (k == ExportKind.FrameAs) page = k } },
                        onRight = { if (k == ExportKind.Package) packageInfo = true else if (silent) explain(NoSound) else { kind = k; page = k } },
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
                scene, video, hasAudio, check, info, bar,
                warning = warning, slow = support is Support.SlowSoftwareOnly, use = better?.let { suggestionLabel(video, it) },
                onUse = ::useBetter, onContinue = { acknowledged = warning },
                onChange = { settings[ExportKind.Video] = it }, onReason = explain, onAudio = { audioPage = true }, onHelp = { help = true },
                onBack = { page = null },
            )
        }
        Presence(audioPage) {
            AudioSettingsPage(video, hasAudio, hasEncoder, { settings[ExportKind.Video] = it }, explain) { audioPage = false }
        }
        Presence(page == ExportKind.Audio) {
            AudioOnlyPage(settings.getValue(ExportKind.Audio), hasEncoder, info, bar, { settings[ExportKind.Audio] = it }, explain) { page = null }
        }
        Presence(page in SheetKinds) {
            val shown = lastPage
            BoxWithConstraints(Modifier.fillMaxSize().presenceScrim(Color(0x66000000)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { page = null }) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * 0.82f).presence(slide = 1f, fade = false)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Am.Card)
                        .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}
                        .navigationBarsPadding(),
                ) {
                    Text(stringResource(shown.title()), Modifier.padding(start = 15.dp, end = 15.dp, top = 18.dp, bottom = 4.dp), color = Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    ScrollArea(Modifier.weight(1f, fill = false), Am.Card, PaddingValues(start = 15.dp, end = 15.dp, top = 10.dp, bottom = 18.dp), GroupGap) {
                        OptionsPage(shown, scene, settings.getValue(shown), explain, { help = true }) { settings[shown] = it }
                    }
                }
            }
        }

        Presence(phase !is Phase.Idle) {
            val shown = lastPhase
            val background = animatedColor(if (shown is Phase.Done) Am.Navy else Scrim)
            Box(Modifier.fillMaxSize().presenceScrim(background).pointerInput(Unit) {}, contentAlignment = Alignment.Center) {
                if (shown is Phase.Running) RunningPanel(shown, progress, heat.level) { shown.job.cancel() }
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
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InterpolationHelp.forEach { (name, text) ->
                        Column {
                            Text(stringResource(name), color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(text), color = Navy, fontSize = 14.sp, lineHeight = 19.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ help = false }) { Text(stringResource(R.string.ok), color = Navy) } },
            containerColor = Am.Card,
        )
    }
    failure?.let { f ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(stringResource(f.title), color = Navy) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(f.text.text(), color = Navy)
                    if (f.hint) OutdatedHint(outdated, Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton({ failure = null; f.proceed?.invoke() }) { Text(f.action?.text() ?: stringResource(R.string.ok), color = Navy) } },
            dismissButton = f.proceed?.let { { TextButton({ failure = null }) { Text(stringResource(R.string.cancel), color = Navy) } } },
            containerColor = Am.Card,
        )
    }
}

private val NoSound = txt(R.string.export_no_sound)

private val InterpolationHelp = listOf(
    R.string.export_interpolation_off to R.string.export_interpolation_off_help,
    R.string.export_interpolation_blend to R.string.export_interpolation_blend_help,
    R.string.export_interpolation_flow to R.string.export_interpolation_flow_help,
)

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
    Box(Modifier.fillMaxWidth().background(Am.Card).statusBarsPadding().height(49.dp)) {
        Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(24.dp), tint = Navy)
        }
        Text(title, Modifier.align(Alignment.Center), color = Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(0.7.dp).background(Line))
    }
}

@Composable
private fun InfoBlock(free: Long, estimate: Long, timeMs: Long?, heat: ThermalLevel, storage: StorageStatus) {
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
        Divider()
        InfoRow(stringResource(R.string.export_phone_temperature), thermalLabel(heat).text(), heat >= ThermalLevel.Hot)
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
private fun ExportBar(enabled: Boolean, blocked: UiText?, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Am.Card).navigationBarsPadding().padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 15.dp)) {
        Box(
            Modifier.fillMaxWidth().height(50.dp).shadow(4.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
                .background(animatedColor(if (blocked == null) Navy else lerp(Navy, Am.Card, 0.8f)))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(blocked?.text() ?: stringResource(R.string.export), color = if (blocked == null) Am.OnNavyAccent else Navy, fontSize = if (blocked == null) 21.sp else 18.sp, maxLines = 1) }
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
                if (right != null) Icon(right, null, Modifier.size(24.dp).alpha(if (enabled || right == ExportIcons.Info) 1f else Dimmed), tint = Am.HomeDim)
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
        DropdownMenu(open, { open = false }, containerColor = Am.Card) {
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
    Column(Modifier.fillMaxSize().presence(slide = 1f, fade = false).background(Am.SheetBg).pointerInput(Unit) {}) {
        TitleBar(title, ExportIcons.Back, stringResource(R.string.export_back), onBack)
        content()
    }
}

@Composable
private fun VideoPage(
    scene: Scene, s: ExportSettings, hasAudio: Boolean, check: (ExportSettings) -> Support,
    info: @Composable () -> Unit, bar: @Composable () -> Unit,
    warning: UiText?, slow: Boolean, use: UiText?, onUse: () -> Unit, onContinue: () -> Unit,
    onChange: (ExportSettings) -> Unit, onReason: (UiText) -> Unit, onAudio: () -> Unit, onHelp: () -> Unit, onBack: () -> Unit,
) {
    val rates = (VideoFrameRates + scene.fps).distinct().sorted()
    val (w, h) = exportSize(scene, s.shortSide, even = true)
    PageFrame(stringResource(R.string.export_kind_video), onBack) {
        info()
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), Am.SheetBg, PaddingValues(start = 15.dp, end = 15.dp, top = 16.dp, bottom = 20.dp), GroupGap) {
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
                Segments(FrameInterpolation.entries.map { Choice(it) }, s.interpolation, { stringResource(it.label) }, onReason) { onChange(s.copy(interpolation = it)) }
                Hint(stringResource(R.string.export_interpolation_hint))
            }
            Field(stringResource(R.string.export_quality), "%.1f Mbps".format(Locale.ROOT, videoBitrate(w, h, s) / 1e6)) {
                ExportSlider(s.quality) { onChange(s.copy(quality = it)) }
                Ends(stringResource(R.string.export_quality_low), stringResource(R.string.export_quality_high))
            }
            Field(stringResource(R.string.export_kind_audio)) {
                NavRow(audioSummary(s.audio).text(), if (hasAudio) null else stringResource(R.string.export_no_sound_yet), onAudio)
            }
        }
        if (warning != null) WarningCard(stringResource(if (slow) R.string.export_slow_title else R.string.onboarding_check_unsupported), warning.text(), use?.text(), onUse, if (slow) onContinue else null)
        bar()
    }
}

@Composable
private fun AudioSettingsPage(
    s: ExportSettings, hasAudio: Boolean, hasEncoder: (String) -> Boolean,
    onChange: (ExportSettings) -> Unit, onReason: (UiText) -> Unit, onBack: () -> Unit,
) {
    val a = s.audio
    PageFrame(stringResource(R.string.export_kind_audio), onBack) {
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), Am.SheetBg, PaddingValues(start = 15.dp, end = 15.dp, top = 16.dp, bottom = 20.dp), GroupGap) {
            Hint(if (hasAudio) audioHint(s).text() else stringResource(R.string.export_audio_hint_silent))
            Field(stringResource(R.string.export_codec)) { Segments(audioCodecChoices(s.container, hasEncoder), a.codec, { it.label }, onReason) { onChange(s.copy(audio = a.copy(codec = it))) } }
            Field(stringResource(R.string.export_bit_rate), "${a.bitrateKbps} kbps") {
                Segments(bitrateChoices(null), a.bitrateKbps, { "$it" }, onReason) { onChange(s.copy(audio = a.copy(bitrateKbps = it))) }
            }
            Field(stringResource(R.string.export_sample_rate)) {
                Segments(sampleRateChoices(if (a.codec == AudioCodec.Opus) AudioCodec.Opus.label else null), a.effectiveRate, { khz(it) }, onReason) { onChange(s.copy(audio = a.copy(sampleRate = it))) }
            }
            Field(stringResource(R.string.export_channels)) { Segments(listOf(Choice(2), Choice(1)), a.channels, { channelsLabel(it).text() }, onReason) { onChange(s.copy(audio = a.copy(channels = it))) } }
        }
    }
}

@Composable
private fun AudioOnlyPage(
    s: ExportSettings, hasEncoder: (String) -> Boolean, info: @Composable () -> Unit, bar: @Composable () -> Unit,
    onChange: (ExportSettings) -> Unit, onReason: (UiText) -> Unit, onBack: () -> Unit,
) {
    val a = s.audio
    val f = s.audioFormat
    PageFrame(stringResource(R.string.export_kind_audio_only), onBack) {
        info()
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), Am.SheetBg, PaddingValues(start = 15.dp, end = 15.dp, top = 16.dp, bottom = 20.dp), GroupGap) {
            Field(stringResource(R.string.export_format)) {
                audioFormatChoices(hasEncoder).chunked(3).forEach { row ->
                    Segments(row, f, { stringResource(it.label) }, onReason) { onChange(s.copy(audioFormat = it)) }
                }
            }
            Field(stringResource(R.string.export_bit_rate), if (f.lossless) stringResource(R.string.export_lossless) else "${a.bitrateKbps} kbps") {
                Segments(bitrateChoices(f), if (f.lossless) -1 else a.bitrateKbps, { "$it" }, onReason) { onChange(s.copy(audio = a.copy(bitrateKbps = it))) }
            }
            Field(stringResource(R.string.export_sample_rate)) {
                Segments(sampleRateChoices(if (f == AudioFormat.Ogg) txt(f.label) else null), audioRate(s), { khz(it) }, onReason) { onChange(s.copy(audio = a.copy(sampleRate = it))) }
            }
            Field(stringResource(R.string.export_channels)) { Segments(listOf(Choice(2), Choice(1)), a.channels, { channelsLabel(it).text() }, onReason) { onChange(s.copy(audio = a.copy(channels = it))) } }
            Hint(stringResource(R.string.export_audio_only_hint))
        }
        bar()
    }
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
        Row(Modifier.fillMaxWidth().height(28.dp).alpha(dim), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Navy, fontSize = 17.sp, maxLines = 1)
            if (onHelp != null) Box(Modifier.size(32.dp).clickable(onClick = onHelp), contentAlignment = Alignment.Center) {
                Icon(ExportIcons.Help, stringResource(R.string.export_what_is, title), Modifier.size(20.dp), tint = ValueGrey)
            }
            Spacer(Modifier.weight(1f))
            if (value != null) Text(value, color = ValueGrey, fontSize = 14.sp, maxLines = 1, style = Mixed)
        }
        Box {
            Column(Modifier.fillMaxWidth().alpha(dim), verticalArrangement = Arrangement.spacedBy(FieldGap), content = content)
            if (reason != null && reasonText != null) Box(Modifier.matchParentSize().semantics { contentDescription = reasonText }.clickable { onReason(reason) })
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
    val size = if (options.size > 4) 13.sp else 15.sp
    Row(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(4.dp)).background(Am.Card).padding(3.dp)) {
        options.forEach { c ->
            val on = c.value == selected
            val fill = animatedColor(if (on) Navy else Am.Card)
            val text = animatedColor(if (on) Am.OnNavyAccent else Navy)
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(fill).clickable { c.reason?.let(onReason) ?: onPick(c.value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label(c.value), Modifier.alpha(if (c.enabled) 1f else Dimmed).padding(horizontal = 2.dp), color = text, fontWeight = FontWeight.Medium, maxLines = 1, style = Mixed,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(9.sp, size))
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
    val rates = (if (kind == ExportKind.Gif) GifFrameRates else (VideoFrameRates + scene.fps)).distinct().sorted()
    val images = kind == ExportKind.Sequence || kind == ExportKind.FrameAs
    if (images) {
        Chips(stringResource(R.string.export_format), if (kind == ExportKind.FrameAs) "${scene.width}x${scene.height}" else null) { ImageKind.entries.forEach { Chip(stringResource(it.label), s.image == it) { onChange(s.copy(image = it)) } } }
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
            FrameInterpolation.entries.forEach { Chip(stringResource(it.label), s.interpolation == it) { onChange(s.copy(interpolation = it)) } }
        }
        Hint(stringResource(R.string.export_interpolation_hint))
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
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val fill = animatedColor(if (selected) Navy else Am.SheetBg)
    val text = animatedColor(if (selected) Am.OnNavyAccent else Navy)
    Box(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(fill).clickable(onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(label, color = text, fontSize = 15.sp, maxLines = 1, style = Mixed)
    }
}

@Composable
private fun BoxScope.RunningPanel(p: Phase.Running, progress: Float, heat: ThermalLevel, onCancel: () -> Unit) {
    val percent = (progress * 100).toInt().coerceIn(0, 100)
    val left = if (progress > 0.02f) ((System.nanoTime() - p.startNs) / 1e9 * (1 - progress) / progress).toInt() else null
    Column(Modifier.align(Alignment.Center).presence(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(when (p.kind) {
                ExportKind.Video -> R.string.export_running_video
                ExportKind.Audio -> R.string.export_running_audio
                ExportKind.Gif -> R.string.export_running_gif
                ExportKind.Sequence -> R.string.export_running_sequence
                else -> R.string.export_running
            }),
            color = Am.OnScrim, fontSize = 18.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(15.dp))
        Box(Modifier.width(220.dp).height(7.dp).clip(RoundedCornerShape(5.dp)).background(Am.OnScrim.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Am.OnNavyAccent))
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.width(220.dp)) {
            Text("$percent%", Modifier.weight(1f), color = Am.OnScrim, fontSize = 13.sp)
            Text(left?.let { "%d:%02d".format(Locale.ROOT, it / 60, it % 60) } ?: "", color = Am.OnScrim, fontSize = 13.sp)
        }
        Box(Modifier.width(300.dp).height(96.dp).padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
            exportHeatText(heat)?.let { Text(it.text(), color = Am.OnNavyAccent, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3) }
        }
        TextButton(onCancel) { Text(stringResource(R.string.cancel), color = Am.OnNavyAccent, fontSize = 17.sp) }
    }
    Text(
        stringResource(R.string.keep_absentmotion_open_and_your_screen),
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 30.dp, vertical = 30.dp),
        color = Am.OnScrim, fontSize = 12.sp, textAlign = TextAlign.Center,
    )
}

private fun share(context: app.absentmotion.platform.Context, done: Phase.Done) {
    app.absentmotion.platform.shareExport(context, done.result)
}
