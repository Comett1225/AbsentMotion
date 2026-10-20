package app.absentmotion.ui.export

import app.absentmotion.i18n.AppText
import app.absentmotion.i18n.text
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
import app.absentmotion.export.measureGifBytes
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
import kotlinx.coroutines.CancellationException
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

private class Failure(val title: String, val text: String, val action: String? = null, val proceed: (() -> Unit)? = null)

@Composable
fun ExportScreen(scene: Scene, frame: Int, engine: EngineThread, fromProjectList: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = remember { Exporter(context, engine, MediaStoreExport(context)) }
    val kinds = remember { listOf(ExportKind.Video, ExportKind.Audio, ExportKind.Package, ExportKind.Frame, ExportKind.FrameAs, ExportKind.Sequence, ExportKind.Gif) + if (fromProjectList) listOf(ExportKind.Xml) else emptyList() }
    var kind by remember { mutableStateOf(ExportKind.Video) }
    val prefs = remember { ExportPrefs(context) }
    val settings = remember { mutableStateMapOf<ExportKind, ExportSettings>().apply { ExportKind.entries.forEach { put(it, prefs.load(it, scene)) } } }
    var page by remember { mutableStateOf<ExportKind?>(null) }
    var lastPage by remember { mutableStateOf(ExportKind.Gif) }
    var audioPage by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf<String?>(null) }
    var lastPhase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var packageInfo by remember { mutableStateOf(false) }
    var asTemplate by remember { mutableStateOf(false) }
    var templateInfo by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Failure?>(null) }
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    page?.let { if (it in SheetKinds) lastPage = it }
    if (phase !is Phase.Idle) lastPhase = phase
    var progress by remember { mutableFloatStateOf(0f) }
    val hasAudio = remember(scene) { ExportAudio.mixFor != null && AudioClip.of(scene).isNotEmpty() }
    val gifSettings = settings.getValue(ExportKind.Gif)
    val gifEstimate by produceState<Long?>(null, kind, scene, gifSettings, phase) {
        value = null
        if (kind == ExportKind.Gif && phase is Phase.Idle) {
            try { value = measureGifBytes(engine, scene, gifSettings) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("AMExport", "Cannot sample GIF size", e) }
        }
    }
    val estimate = if (kind == ExportKind.Gif) gifEstimate ?: -1L else remember(kind, scene, settings.getValue(kind), hasAudio) { estimateBytes(kind, scene, settings.getValue(kind), hasAudio) }
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
    val timeMs = remember(kind, current, report, heat.level, estimate, phase is Phase.Idle) {
        when (kind) {
            ExportKind.Video, ExportKind.Gif, ExportKind.Sequence -> times.estimateMs(current, scene, report?.probe, heat.level)
            ExportKind.Package -> estimate.coerceAtLeast(0) / 100_000
            else -> 0L
        }
    }
    val storage = if (estimate < 0) StorageStatus.Enough else storageStatus(estimate, free)
    val blocked = exportBlock(storage, if (kind == ExportKind.Video) support else Support.Supported)
    val explain: (String) -> Unit = { failure = Failure("Not Available", it) }

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
            failure = Failure("Export Failed", "There is nothing to export yet. Add a layer or some media, then try again.")
            return
        }
        if (kind == ExportKind.Audio && !hasAudio) {
            failure = Failure("Nothing to Export", NoSound)
            return
        }
        if (kind == ExportKind.Video && support is Support.Unsupported) {
            failure = Failure("Not Supported", codecWarning(s, support).orEmpty(), better?.let { suggestionLabel(s, it) }, better?.let { ::useBetter })
            return
        }
        free = freeSpace()
        if (kind != ExportKind.Gif && storageStatus(estimate, free) is StorageStatus.NotEnough) {
            failure = Failure("Not Enough Storage", AppText.get((storageStatus(estimate, free) as StorageStatus.NotEnough).message))
            return
        }
        val chosen = kind
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
                        failure = Failure("Media Missing", "This project uses files that are not on this device:\n" + missing.joinToString("\n") +
                            "\n\nThe layers that use them are left out if you export anyway.", "Export Anyway") { start(true) }
                        phase = Phase.Idle
                        return@launch
                    }
                }
                if (chosen == ExportKind.Gif) {
                    val measured = measureGifBytes(engine, scene, s)
                    free = freeSpace()
                    if (storageStatus(measured, free) is StorageStatus.NotEnough) {
                        failure = Failure("Not Enough Storage", "Free up some storage on this device and export again.")
                        phase = Phase.Idle
                        return@launch
                    }
                }
                val result = exporter.export(chosen, scene, s, frame, name) { progress = it }
                if (alsoTemplate) try {
                    TemplateStore(context).add(scene, scene.frameStart(frame), engine)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("AMExport", "could not store the template", e)
                }
                phase = Phase.Done(chosen, result)
            } catch (e: CancellationException) {
                phase = Phase.Idle
                throw e
            } catch (e: Throwable) {
                Log.e("AMExport", "export failed", e)
                phase = Phase.Idle
                failure = Failure("Export Failed", e.message ?: e.toString())
            }
        }
        phase = Phase.Running(chosen, System.nanoTime(), job)
    }

    val info: @Composable () -> Unit = { InfoBlock(free, estimate, timeMs, heat.level, storage) }
    val bar: @Composable () -> Unit = { ExportBar(enabled = phase == Phase.Idle, blocked = blocked, onClick = ::start) }

    Box(Modifier.fillMaxSize().background(Am.SheetBg).pointerInput(Unit) {}.enterFade(12.dp)) {
        Column(Modifier.fillMaxSize()) {
            TitleBar("Export Project", ExportIcons.Close, "Cancel", onClose)
            info()
            ScrollArea(Modifier.weight(1f), Am.SheetBg, PaddingValues(15.dp), 10.dp) {
                kinds.forEachIndexed { i, k ->
                    val s = settings.getValue(k)
                    val silent = k == ExportKind.Audio && !hasAudio
                    OptionCard(
                        selected = kind == k, enabled = !silent, icon = k.icon(), title = k.title(),
                        note = if (silent) "This project has no sound" else optionNote(k, scene, s),
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
                    Text(shown.title(), Modifier.padding(start = 15.dp, end = 15.dp, top = 18.dp, bottom = 4.dp), color = Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
            title = { Text("Project Bundle", color = Navy) },
            text = { Text("One .absproj package with the project and the original media it uses, saved to Documents / AbsentMotion. Keep it as a backup or move the project to another phone.", color = Navy) },
            confirmButton = { TextButton({ packageInfo = false }) { Text("OK", color = Navy) } },
            containerColor = Am.Card,
        )
    }
    if (templateInfo) {
        AlertDialog(
            onDismissRequest = { templateInfo = false },
            title = { Text("Template", color = Navy) },
            text = { Text("Also keeps the project, with its media, in Templates on this phone. Open it there to replace its media and continue to a new project.", color = Navy) },
            confirmButton = { TextButton({ templateInfo = false }) { Text("OK", color = Navy) } },
            containerColor = Am.Card,
        )
    }
    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text("Frame Interpolation", color = Navy) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InterpolationHelp.forEach { (name, text) ->
                        Column {
                            Text(name, color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(text, color = Navy, fontSize = 14.sp, lineHeight = 19.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ help = false }) { Text("OK", color = Navy) } },
            containerColor = Am.Card,
        )
    }
    failure?.let { f ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(f.title, color = Navy) },
            text = { Text(f.text, color = Navy) },
            confirmButton = { TextButton({ failure = null; f.proceed?.invoke() }) { Text(f.action ?: "OK", color = Navy) } },
            dismissButton = f.proceed?.let { { TextButton({ failure = null }) { Text("Cancel", color = Navy) } } },
            containerColor = Am.Card,
        )
    }
}

private const val NoSound = "This project has no sound to export. Add music, a sound or a video with sound first."

private val InterpolationHelp = listOf(
    "Off" to "Each clip shows its own frames. A 30 fps clip in a 60 fps export shows every frame twice, so motion can look a little stepped.",
    "Frame blending" to "The new in-between frames mix the two nearest frames like a quick cross-fade. Fast to export; fast motion can look ghosted.",
    "Optical flow" to "The app follows how each part of the picture moves and draws real in-between frames. Smoothest motion, but the export is slower and very fast motion can smear at the edges.",
)

private fun ExportKind.title() = when (this) {
    ExportKind.Video -> "Video"
    ExportKind.Package -> "Project Bundle"
    ExportKind.Frame -> "Current Frame as PNG"
    ExportKind.FrameAs -> "Current Frame as…"
    ExportKind.Sequence -> "Image Sequence"
    ExportKind.Gif -> "GIF"
    ExportKind.Xml -> "XML"
    ExportKind.Audio -> "Audio Only"
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
        is StorageStatus.Low -> "Low"
        is StorageStatus.NotEnough -> "Not enough"
    }
    Column(Modifier.fillMaxWidth().background(Am.Card).padding(horizontal = 15.dp)) {
        InfoRow("Available space", formatBytes(free) + (space?.let { " · $it" } ?: ""), space != null)
        Divider()
        InfoRow("Estimated file size", if (estimate < 0) "Calculating..." else formatBytes(estimate), false)
        Divider()
        InfoRow("Estimated export time", timeMs?.let { timeLabel(it).text() } ?: "Unknown", false)
        Divider()
        InfoRow("Phone temperature", thermalLabel(heat), heat >= ThermalLevel.Hot)
    }
}

@Composable
private fun InfoRow(label: String, value: String, warn: Boolean) {
    Row(Modifier.fillMaxWidth().height(33.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Navy, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, Modifier.padding(start = 10.dp), color = animatedColor(if (warn) Alert else Navy), fontSize = 15.sp,
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
            Icon(ExportIcons.ChevronDown, if (scroll.canScrollForward) "More below" else null, Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(22.dp), tint = ValueGrey)
        }
    }
}

@Composable
private fun TemplateToggle(checked: Boolean, onChange: (Boolean) -> Unit, onInfo: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Am.Card).height(52.dp).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text("Also keep a copy as a Template", Modifier.weight(1f, fill = false), color = Navy, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(36.dp).clickable(onClick = onInfo), contentAlignment = Alignment.Center) { Icon(ExportIcons.Info, "Info", Modifier.size(20.dp), tint = Am.HomeDim) }
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
private fun ExportBar(enabled: Boolean, blocked: String?, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Am.Card).navigationBarsPadding().padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 15.dp)) {
        Box(
            Modifier.fillMaxWidth().height(50.dp).shadow(4.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
                .background(animatedColor(if (blocked == null) Navy else lerp(Navy, Am.Card, 0.8f)))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(blocked ?: "Export", color = if (blocked == null) Am.OnNavyAccent else Navy, fontSize = if (blocked == null) 21.sp else 18.sp, maxLines = 1) }
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
        Text("GIF", color = Navy, fontSize = 7.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ResolutionDropdown(shortSide: Int, choices: List<Choice<app.absentmotion.export.Resolution>>, onReason: (String) -> Unit, onPick: (Int) -> Unit) {
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
        TitleBar(title, ExportIcons.Back, "Back", onBack)
        content()
    }
}

@Composable
private fun VideoPage(
    scene: Scene, s: ExportSettings, hasAudio: Boolean, check: (ExportSettings) -> Support,
    info: @Composable () -> Unit, bar: @Composable () -> Unit,
    warning: String?, slow: Boolean, use: String?, onUse: () -> Unit, onContinue: () -> Unit,
    onChange: (ExportSettings) -> Unit, onReason: (String) -> Unit, onAudio: () -> Unit, onHelp: () -> Unit, onBack: () -> Unit,
) {
    val rates = (VideoFrameRates + scene.fps).distinct().sorted()
    val (w, h) = exportSize(scene, s.shortSide, even = true)
    PageFrame("Video", onBack) {
        info()
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), Am.SheetBg, PaddingValues(start = 15.dp, end = 15.dp, top = 16.dp, bottom = 20.dp), GroupGap) {
            Field("Export Resolution") {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Am.Card)) { ResolutionDropdown(s.shortSide, resolutionChoices(s, check), onReason) { onChange(s.copy(shortSide = it)) } }
            }
            Field("Format") { Segments(containerChoices(s, check), s.container, { it.label }, onReason) { onChange(withContainer(s, it, check)) } }
            Field("Codec") { Segments(codecChoices(s, check), s.codec, { it.shortName }, onReason) { onChange(s.copy(codec = it)) } }
            Field("Frame Rate", "${s.fps}fps") {
                ExportSlider(rates.indexOf(s.fps).coerceAtLeast(0).toFloat() / (rates.size - 1)) { onChange(s.copy(fps = rates[(it * (rates.size - 1)).roundToInt()])) }
                Ends("${rates.first()}fps", "${rates.last()}fps")
            }
            Field("Frame Interpolation", onHelp = onHelp) {
                Segments(FrameInterpolation.entries.map { Choice(it) }, s.interpolation, { it.label }, onReason) { onChange(s.copy(interpolation = it)) }
                Hint(InterpolationHint)
            }
            Field("Quality", "%.1f Mbps".format(Locale.ROOT, videoBitrate(w, h, s) / 1e6)) {
                ExportSlider(s.quality) { onChange(s.copy(quality = it)) }
                Ends("Low", "High")
            }
            Field("Audio") {
                NavRow(audioSummary(s.audio), if (hasAudio) null else "This project has no sound yet", onAudio)
            }
        }
        if (warning != null) WarningCard(if (slow) "Slow export" else "Not supported", warning, use, onUse, if (slow) onContinue else null)
        bar()
    }
}

@Composable
private fun AudioSettingsPage(
    s: ExportSettings, hasAudio: Boolean, hasEncoder: (String) -> Boolean,
    onChange: (ExportSettings) -> Unit, onReason: (String) -> Unit, onBack: () -> Unit,
) {
    val a = s.audio
    PageFrame("Audio", onBack) {
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), Am.SheetBg, PaddingValues(start = 15.dp, end = 15.dp, top = 16.dp, bottom = 20.dp), GroupGap) {
            Hint(if (hasAudio) "Sound settings for the video file. ${s.container.label} can hold ${audioCodecsFor(s.container).joinToString(" or ") { it.label }} audio."
                else "This project has no sound yet. These settings are used once you add some.")
            Field("Codec") { Segments(audioCodecChoices(s.container, hasEncoder), a.codec, { it.label }, onReason) { onChange(s.copy(audio = a.copy(codec = it))) } }
            Field("Bit Rate", "${a.bitrateKbps} kbps") {
                Segments(bitrateChoices(null), a.bitrateKbps, { "$it" }, onReason) { onChange(s.copy(audio = a.copy(bitrateKbps = it))) }
            }
            Field("Sample Rate") {
                Segments(sampleRateChoices(if (a.codec == AudioCodec.Opus) "Opus" else null), a.effectiveRate, ::khz, onReason) { onChange(s.copy(audio = a.copy(sampleRate = it))) }
            }
            Field("Channels") { Segments(listOf(Choice(2), Choice(1)), a.channels, ::channelsLabel, onReason) { onChange(s.copy(audio = a.copy(channels = it))) } }
        }
    }
}

@Composable
private fun AudioOnlyPage(
    s: ExportSettings, hasEncoder: (String) -> Boolean, info: @Composable () -> Unit, bar: @Composable () -> Unit,
    onChange: (ExportSettings) -> Unit, onReason: (String) -> Unit, onBack: () -> Unit,
) {
    val a = s.audio
    val f = s.audioFormat
    PageFrame("Audio Only", onBack) {
        info()
        ScrollArea(Modifier.weight(1f).fillMaxWidth(), Am.SheetBg, PaddingValues(start = 15.dp, end = 15.dp, top = 16.dp, bottom = 20.dp), GroupGap) {
            Field("Format") {
                audioFormatChoices(hasEncoder).chunked(3).forEach { row ->
                    Segments(row, f, { it.label }, onReason) { onChange(s.copy(audioFormat = it)) }
                }
            }
            Field("Bit Rate", if (f.lossless) "Lossless" else "${a.bitrateKbps} kbps") {
                Segments(bitrateChoices(f), if (f.lossless) -1 else a.bitrateKbps, { "$it" }, onReason) { onChange(s.copy(audio = a.copy(bitrateKbps = it))) }
            }
            Field("Sample Rate") {
                Segments(sampleRateChoices(if (f == AudioFormat.Ogg) f.label else null), audioRate(s), ::khz, onReason) { onChange(s.copy(audio = a.copy(sampleRate = it))) }
            }
            Field("Channels") { Segments(listOf(Choice(2), Choice(1)), a.channels, ::channelsLabel, onReason) { onChange(s.copy(audio = a.copy(channels = it))) } }
            Hint("Saved to Music / AbsentMotion. WAV and FLAC keep every detail; M4A, OGG and MP3 are smaller.")
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
                if (onContinue != null) TextButton(onContinue) { Text("Continue anyway", color = ValueGrey, fontSize = 15.sp, maxLines = 1) }
                if (use != null) TextButton(onUse) { Text(use, color = Alert, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun Field(
    title: String, value: String? = null, reason: String? = null, onReason: (String) -> Unit = {}, onHelp: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dim = if (reason == null) 1f else Dimmed
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FieldGap)) {
        Row(Modifier.fillMaxWidth().height(28.dp).alpha(dim), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Navy, fontSize = 17.sp, maxLines = 1)
            if (onHelp != null) Box(Modifier.size(32.dp).clickable(onClick = onHelp), contentAlignment = Alignment.Center) {
                Icon(ExportIcons.Help, "What is $title?", Modifier.size(20.dp), tint = ValueGrey)
            }
            Spacer(Modifier.weight(1f))
            if (value != null) Text(value, color = ValueGrey, fontSize = 14.sp, maxLines = 1)
        }
        Box {
            Column(Modifier.fillMaxWidth().alpha(dim), verticalArrangement = Arrangement.spacedBy(FieldGap), content = content)
            if (reason != null) Box(Modifier.matchParentSize().semantics { contentDescription = reason }.clickable { onReason(reason) })
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = NoteGrey, fontSize = 13.sp, lineHeight = 17.sp)
}

@Composable
private fun Ends(left: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(left, Modifier.weight(1f), color = ValueGrey, fontSize = 12.sp, maxLines = 1)
        Text(right, color = ValueGrey, fontSize = 12.sp, maxLines = 1)
    }
}

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
        Icon(ExportIcons.ChevronRight, "Audio settings", Modifier.size(22.dp), tint = Am.HomeDim)
    }
}

private const val InterpolationHint = "Clips with a lower frame rate than the export are smoothed to match, for example 30 to 60 fps."

@Composable
private fun <T> Segments(options: List<Choice<T>>, selected: T, label: (T) -> String, onReason: (String) -> Unit, onPick: (T) -> Unit) {
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
                Text(label(c.value), Modifier.alpha(if (c.enabled) 1f else Dimmed), color = text, fontSize = size, fontWeight = FontWeight.Medium, maxLines = 1)
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
private fun OptionsPage(kind: ExportKind, scene: Scene, s: ExportSettings, onReason: (String) -> Unit, onHelp: () -> Unit, onChange: (ExportSettings) -> Unit) {
    val rates = (if (kind == ExportKind.Gif) GifFrameRates else (VideoFrameRates + scene.fps)).distinct().sorted()
    val images = kind == ExportKind.Sequence || kind == ExportKind.FrameAs
    if (images) {
        Chips("Format", if (kind == ExportKind.FrameAs) "${scene.width}x${scene.height}" else null) { ImageKind.entries.forEach { Chip(it.label, s.image == it) { onChange(s.copy(image = it)) } } }
        Chips("Quality", reason = if (s.image.lossy) null else "${s.image.label} keeps every pixel exactly, so it has no quality setting.", onReason = onReason) {
            ImageQualities.forEach { Chip("$it%", s.imageQuality == it) { onChange(s.copy(imageQuality = it)) } }
        }
    }
    Chips("Transparent areas become", reason = if (images && s.image.alpha) "${s.image.label} keeps transparent areas, so nothing needs filling." else null, onReason = onReason) {
        Chip("White", s.background == BackgroundWhite) { onChange(s.copy(background = BackgroundWhite)) }
        Chip("Black", s.background == BackgroundBlack) { onChange(s.copy(background = BackgroundBlack)) }
    }
    if (kind == ExportKind.FrameAs) return
    val (w, h) = exportSize(scene, s.shortSide, even = false)
    Chips(if (kind == ExportKind.Gif) "Size" else "Resolution", "${w}x$h") {
        (if (kind == ExportKind.Gif) GifResolutions else VideoResolutions).forEach { Chip(it.label, s.shortSide == it.shortSide) { onChange(s.copy(shortSide = it.shortSide)) } }
    }
    Chips("Frame Rate") { rates.forEach { Chip("${it}fps", s.fps == it) { onChange(s.copy(fps = it)) } } }
    Field("Frame Interpolation", onHelp = onHelp) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FrameInterpolation.entries.forEach { Chip(it.label, s.interpolation == it) { onChange(s.copy(interpolation = it)) } }
        }
        Hint(InterpolationHint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(label: String, value: String? = null, reason: String? = null, onReason: (String) -> Unit = {}, chips: @Composable () -> Unit) {
    Field(label, value, reason, onReason) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val fill = animatedColor(if (selected) Navy else Am.SheetBg)
    val text = animatedColor(if (selected) Am.OnNavyAccent else Navy)
    Box(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(fill).clickable(onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(label, color = text, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
private fun BoxScope.RunningPanel(p: Phase.Running, progress: Float, heat: ThermalLevel, onCancel: () -> Unit) {
    val percent = (progress * 100).toInt().coerceIn(0, 100)
    val left = if (progress > 0.02f) ((System.nanoTime() - p.startNs) / 1e9 * (1 - progress) / progress).toInt() else null
    Column(Modifier.align(Alignment.Center).presence(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when (p.kind) { ExportKind.Video -> "Exporting Video"; ExportKind.Audio -> "Exporting Audio"; ExportKind.Gif -> "Exporting GIF"; ExportKind.Sequence -> "Exporting Image Sequence"; else -> "Exporting" },
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
            exportHeatText(heat)?.let { Text(it, color = Am.OnNavyAccent, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3) }
        }
        TextButton(onCancel) { Text("Cancel", color = Am.OnNavyAccent, fontSize = 17.sp) }
    }
    Text(
        "Keep AbsentMotion open and your screen on until the export has finished.",
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 30.dp, vertical = 30.dp),
        color = Am.OnScrim, fontSize = 12.sp, textAlign = TextAlign.Center,
    )
}

private fun share(context: app.absentmotion.platform.Context, done: Phase.Done) {
    app.absentmotion.platform.shareExport(context, done.result)
}
