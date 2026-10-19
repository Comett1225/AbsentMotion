package app.absentmotion.ui.export

import app.absentmotion.platform.Log
import app.absentmotion.model.mediaUris
import app.absentmotion.platform.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import app.absentmotion.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.AudioBitrate
import app.absentmotion.export.AvailableCodecs
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
import app.absentmotion.export.defaultSettings
import app.absentmotion.export.estimateBytes
import app.absentmotion.export.exportSize
import app.absentmotion.export.formatBytes
import app.absentmotion.export.freeSpace
import app.absentmotion.export.optionNote
import app.absentmotion.export.resolutionLabel
import app.absentmotion.export.safeName
import app.absentmotion.export.videoBitrate
import app.absentmotion.model.Scene
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

private sealed interface Phase {
    data object Idle : Phase
    class Running(val kind: ExportKind, val startNs: Long, val job: Job) : Phase
    class Done(val kind: ExportKind, val result: ExportResult) : Phase
}

private class Failure(val title: String, val text: String, val proceed: (() -> Unit)? = null)

@Composable
fun ExportScreen(scene: Scene, frame: Int, engine: EngineThread, fromProjectList: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = remember { Exporter(context, engine, MediaStoreExport(context)) }
    val kinds = remember { listOf(ExportKind.Video, ExportKind.Package, ExportKind.Frame, ExportKind.FrameAs, ExportKind.Sequence, ExportKind.Gif) + if (fromProjectList) listOf(ExportKind.Xml) else emptyList() }
    var kind by remember { mutableStateOf(ExportKind.Video) }
    val settings = remember { mutableStateMapOf<ExportKind, ExportSettings>().apply { ExportKind.entries.forEach { put(it, defaultSettings(it, scene)) } } }
    var page by remember { mutableStateOf<ExportKind?>(null) }
    var lastPage by remember { mutableStateOf(ExportKind.Gif) }
    var lastPhase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var packageInfo by remember { mutableStateOf(false) }
    var asTemplate by remember { mutableStateOf(false) }
    var templateInfo by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Failure?>(null) }
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    page?.let { if (it != ExportKind.Video) lastPage = it }
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
    val estimate = if (kind == ExportKind.Gif) gifEstimate ?: -1L else estimateBytes(kind, scene, settings.getValue(kind), hasAudio)
    var free by remember { mutableLongStateOf(freeSpace()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) free = freeSpace() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(phase) { free = freeSpace() }

    app.absentmotion.platform.ExportBars(dark = false, running = phase is Phase.Running)
    BackHandler {
        when (val p = phase) {
            is Phase.Running -> p.job.cancel()
            is Phase.Done -> phase = Phase.Idle
            Phase.Idle -> if (page != null) page = null else onClose()
        }
    }

    fun start(ignoreMissing: Boolean = false) {
        val s = settings.getValue(kind)
        if (scene.layers.isEmpty() && kind != ExportKind.Xml && kind != ExportKind.Package) {
            failure = Failure("Export Failed", "There is nothing to export yet. Add a layer or some media, then try again.")
            return
        }
        free = freeSpace()
        if (kind != ExportKind.Gif && estimate > free) {
            failure = Failure("Not Enough Storage", "Free up some storage on this device and export again.")
            return
        }
        val chosen = kind
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
                            "\n\nThe layers that use them are left out if you export anyway.") { start(true) }
                        phase = Phase.Idle
                        return@launch
                    }
                }
                if (chosen == ExportKind.Gif) {
                    val measured = measureGifBytes(engine, scene, s)
                    free = freeSpace()
                    if (measured > free) {
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

    Box(Modifier.fillMaxSize().background(Am.SheetBg).pointerInput(Unit) {}.enterFade(12.dp)) {
        Column(Modifier.fillMaxSize()) {
            TitleBar("Export Project", ExportIcons.Close, "Cancel", onClose)
            EstimateBlock(free, estimate)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                kinds.forEachIndexed { i, k ->
                    val s = settings.getValue(k)
                    OptionCard(
                        selected = kind == k, icon = k.icon(), title = k.title(), note = optionNote(k, scene, s),
                        right = when (k) { ExportKind.Package -> ExportIcons.Info; ExportKind.Video, ExportKind.FrameAs, ExportKind.Sequence, ExportKind.Gif -> ExportIcons.ChevronRight; else -> null },
                        onSelect = { kind = k; if (k == ExportKind.FrameAs) page = k },
                        onRight = { if (k == ExportKind.Package) packageInfo = true else { kind = k; page = k } },
                        modifier = Modifier.enterFade(10.dp, minOf(i, 6) * 30),
                        below = if (k == ExportKind.Video) { { ResolutionDropdown(s.shortSide) { kind = k; settings[k] = s.copy(shortSide = it) } } } else null,
                    )
                }
            }
            TemplateToggle(asTemplate, { asTemplate = it }) { templateInfo = true }
            ExportBar(enabled = phase == Phase.Idle, onClick = ::start)
        }

        Presence(page == ExportKind.Video) {
            VideoPage(
                scene, settings.getValue(ExportKind.Video), free, estimate, phase == Phase.Idle,
                { settings[ExportKind.Video] = it }, { page = null }, ::start,
            )
        }
        Presence(page == ExportKind.Gif || page == ExportKind.Sequence || page == ExportKind.FrameAs) {
            val shown = lastPage
            Box(Modifier.fillMaxSize().presenceScrim(Color(0x66000000)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { page = null }) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().presence(slide = 1f, fade = false)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Am.Card)
                        .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}
                        .navigationBarsPadding().padding(horizontal = 15.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(shown.title(), color = Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    OptionsPage(shown, scene, settings.getValue(shown)) { settings[shown] = it }
                }
            }
        }

        Presence(phase !is Phase.Idle) {
            val shown = lastPhase
            val background = animatedColor(if (shown is Phase.Done) Am.Navy else Scrim)
            Box(Modifier.fillMaxSize().presenceScrim(background).pointerInput(Unit) {}, contentAlignment = Alignment.Center) {
                if (shown is Phase.Running) RunningPanel(shown, progress) { shown.job.cancel() }
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
    failure?.let { f ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(f.title, color = Navy) },
            text = { Text(f.text, color = Navy) },
            confirmButton = { TextButton({ failure = null; f.proceed?.invoke() }) { Text(if (f.proceed == null) "OK" else "Export Anyway", color = Navy) } },
            dismissButton = f.proceed?.let { { TextButton({ failure = null }) { Text("Cancel", color = Navy) } } },
            containerColor = Am.Card,
        )
    }
}

private fun ExportKind.title() = when (this) {
    ExportKind.Video -> "Video"
    ExportKind.Package -> "Project Bundle"
    ExportKind.Frame -> "Current Frame as PNG"
    ExportKind.FrameAs -> "Current Frame as…"
    ExportKind.Sequence -> "Image Sequence"
    ExportKind.Gif -> "GIF"
    ExportKind.Xml -> "XML"
}

private fun ExportKind.icon(): ImageVector? = when (this) {
    ExportKind.Video -> ExportIcons.Video
    ExportKind.Package -> ExportIcons.Package
    ExportKind.Frame, ExportKind.FrameAs -> ExportIcons.Frame
    ExportKind.Sequence -> ExportIcons.Sequence
    ExportKind.Gif -> null
    ExportKind.Xml -> ExportIcons.Xml
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Navy, fontSize = 17.sp)
        Text(value, color = Navy, fontSize = 17.sp)
    }
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
private fun EstimateBlock(free: Long, estimate: Long) {
    Column(Modifier.fillMaxWidth().background(Am.Card).padding(horizontal = 15.dp).height(130.dp)) {
        InfoRow("Available Space", formatBytes(free), Modifier.height(67.dp))
        InfoRow("Estimated file size", if (estimate < 0) "Calculating..." else formatBytes(estimate), Modifier.height(63.dp))
    }
}

@Composable
private fun TemplateToggle(checked: Boolean, onChange: (Boolean) -> Unit, onInfo: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Am.Card).height(40.dp).padding(start = 15.dp, end = 15.dp, top = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Also keep a copy as a Template", color = Navy, fontSize = 16.sp, maxLines = 1)
        Box(Modifier.size(36.dp).clickable(onClick = onInfo), contentAlignment = Alignment.Center) { Icon(ExportIcons.Info, "Info", Modifier.size(20.dp), tint = Am.HomeDim) }
        Spacer(Modifier.weight(1f))
        Switch(
            checked, onChange, Modifier.scale(0.85f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Am.HomeAccent, checkedTrackColor = Am.HomeAccent.copy(alpha = 0.5f), checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Am.Thumb, uncheckedTrackColor = Am.SwitchOff, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun ExportBar(enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Am.Card).navigationBarsPadding().padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 15.dp)) {
        Box(
            Modifier.fillMaxWidth().height(50.dp).shadow(4.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)).background(Navy)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text("Export", color = Am.OnNavyAccent, fontSize = 21.sp) }
    }
}

@Composable
private fun OptionCard(
    selected: Boolean, icon: ImageVector?, title: String, note: String?, right: ImageVector?,
    onSelect: () -> Unit, onRight: () -> Unit, modifier: Modifier, below: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Am.Card)) {
        Row(Modifier.fillMaxWidth().height(70.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxSize().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(60.dp), contentAlignment = Alignment.Center) { Radio(selected) }
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = Navy) else GifBadge()
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(title, color = Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp, maxLines = 1)
                    if (note != null) Text(note, color = NoteGrey, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1)
                }
            }
            Box(Modifier.width(38.dp).fillMaxSize().then(if (right != null) Modifier.clickable(onClick = onRight) else Modifier), contentAlignment = Alignment.Center) {
                if (right != null) Icon(right, null, Modifier.size(24.dp), tint = Am.HomeDim)
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
private fun ResolutionDropdown(shortSide: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(44.dp).clickable { open = true }) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(resolutionLabel(shortSide), Modifier.weight(1f), color = Navy, fontSize = 16.sp)
            Icon(ExportIcons.ChevronDown, null, Modifier.size(20.dp), tint = Navy)
        }
        DropdownMenu(open, { open = false }, containerColor = Am.Card) {
            VideoResolutions.forEach { r ->
                DropdownMenuItem(text = { Text(r.label, color = Navy, fontSize = 17.sp) }, onClick = { open = false; onPick(r.shortSide) })
            }
        }
    }
}

@Composable
private fun VideoPage(
    scene: Scene, s: ExportSettings, free: Long, estimate: Long, canExport: Boolean,
    onChange: (ExportSettings) -> Unit, onBack: () -> Unit, onExport: () -> Unit,
) {
    val rates = (VideoFrameRates + scene.fps).distinct().sorted()
    val (w, h) = exportSize(scene, s.shortSide, even = true)
    Column(Modifier.fillMaxSize().presence(slide = 1f, fade = false).background(Am.SheetBg).pointerInput(Unit) {}) {
        TitleBar("Video", ExportIcons.Back, "Back", onBack)
        EstimateBlock(free, estimate)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp)) {
            Spacer(Modifier.height(26.dp))
            Heading("Export Resolution")
            Spacer(Modifier.height(8.dp))
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Am.Card)) { ResolutionDropdown(s.shortSide) { onChange(s.copy(shortSide = it)) } }
            Spacer(Modifier.height(27.dp))
            Heading("Codec")
            Spacer(Modifier.height(8.dp))
            Segments(AvailableCodecs, s.codec, { it.label }, if (AvailableCodecs.size > 3) 12.sp else 15.sp) { onChange(s.copy(codec = it)) }
            Spacer(Modifier.height(55.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Heading("Frame Rate", Modifier.weight(1f))
                Caption("${s.fps}fps", Modifier.width(70.dp), TextAlign.End)
            }
            Spacer(Modifier.height(34.5.dp))
            ExportSlider(rates.indexOf(s.fps).coerceAtLeast(0).toFloat() / (rates.size - 1)) { onChange(s.copy(fps = rates[(it * (rates.size - 1)).roundToInt()])) }
            Spacer(Modifier.height(27.dp))
            Heading("Frame Interpolation")
            Spacer(Modifier.height(8.dp))
            Segments(FrameInterpolation.entries, s.interpolation, { it.label }, 13.sp) { onChange(s.copy(interpolation = it)) }
            Spacer(Modifier.height(6.dp))
            Text(InterpolationHint, color = NoteGrey, fontSize = 13.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(35.5.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Heading("Quality"); Caption("Low") }
                Column(Modifier.width(90.dp), horizontalAlignment = Alignment.End) {
                    Caption("%.1fmbps".format(Locale.ROOT, videoBitrate(w, h, s) / 1e6))
                    Caption("High")
                }
            }
            ExportSlider(s.quality) { onChange(s.copy(quality = it)) }
        }
        ExportBar(canExport, onExport)
    }
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Navy, fontSize = 18.sp, lineHeight = 24.sp, maxLines = 1)
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(text, modifier, color = ValueGrey, fontSize = 14.sp, lineHeight = 19.sp, textAlign = align, maxLines = 1)
}

private const val InterpolationHint = "Clips with a lower frame rate than the export are smoothed to match, for example 30 to 60 fps."

@Composable
private fun <T> Segments(options: List<T>, selected: T, label: (T) -> String, fontSize: TextUnit, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(4.dp)).background(Am.Card).padding(3.dp)) {
        options.forEach { c ->
            val fill = animatedColor(if (c == selected) Navy else Am.Card)
            val text = animatedColor(if (c == selected) Am.OnNavyAccent else Navy)
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(fill).clickable { onPick(c) }, contentAlignment = Alignment.Center) {
                Text(label(c), color = text, fontSize = fontSize, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ExportSlider(value: Float, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    Box(
        Modifier.fillMaxWidth().height(36.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val inset = 8.dp.toPx()
                fun apply(x: Float) = change(((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f))
                down.consume()
                apply(down.position.x)
                drag(down.id) { it.consume(); apply(it.position.x) }
            }
        },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsPage(kind: ExportKind, scene: Scene, s: ExportSettings, onChange: (ExportSettings) -> Unit) {
    val rates = (if (kind == ExportKind.Gif) GifFrameRates else (VideoFrameRates + scene.fps)).distinct().sorted()
    if (kind == ExportKind.Sequence || kind == ExportKind.FrameAs) {
        Group("Format") { ImageKind.entries.forEach { Chip(it.label, s.image == it) { onChange(s.copy(image = it)) } } }
        if (s.image.lossy) Group("Quality") { ImageQualities.forEach { Chip("$it%", s.imageQuality == it) { onChange(s.copy(imageQuality = it)) } } }
    }
    if (kind == ExportKind.Gif || !s.image.alpha && (kind == ExportKind.Sequence || kind == ExportKind.FrameAs)) Group("Transparent areas become") {
        Chip("White", s.background == BackgroundWhite) { onChange(s.copy(background = BackgroundWhite)) }
        Chip("Black", s.background == BackgroundBlack) { onChange(s.copy(background = BackgroundBlack)) }
    }
    if (kind == ExportKind.FrameAs) {
        Text("${scene.width}x${scene.height}", color = NoteGrey, fontSize = 14.sp)
        return
    }
    Group(if (kind == ExportKind.Gif) "Size" else "Resolution") {
        (if (kind == ExportKind.Gif) GifResolutions else VideoResolutions).forEach { Chip(it.label, s.shortSide == it.shortSide) { onChange(s.copy(shortSide = it.shortSide)) } }
    }
    Group("Frame Rate") { rates.forEach { Chip("${it}fps", s.fps == it) { onChange(s.copy(fps = it)) } } }
    val (w, h) = exportSize(scene, s.shortSide, even = false)
    Text("${w}x$h", color = NoteGrey, fontSize = 14.sp)
    Group("Frame Interpolation") { FrameInterpolation.entries.forEach { Chip(it.label, s.interpolation == it) { onChange(s.copy(interpolation = it)) } } }
    Text(InterpolationHint, color = NoteGrey, fontSize = 14.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(label: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Navy, fontSize = 15.sp)
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
private fun BoxScope.RunningPanel(p: Phase.Running, progress: Float, onCancel: () -> Unit) {
    val percent = (progress * 100).toInt().coerceIn(0, 100)
    val left = if (progress > 0.02f) ((System.nanoTime() - p.startNs) / 1e9 * (1 - progress) / progress).toInt() else null
    Column(Modifier.align(Alignment.Center).presence(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when (p.kind) { ExportKind.Video -> "Exporting Video"; ExportKind.Gif -> "Exporting GIF"; ExportKind.Sequence -> "Exporting Image Sequence"; else -> "Exporting" },
            color = Am.OnScrim, fontSize = 18.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(15.dp))
        Box(Modifier.width(220.dp).height(7.dp).clip(RoundedCornerShape(5.dp)).background(Am.OnScrim.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Am.OnNavyAccent))
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.width(220.dp)) {
            Text("$percent%", Modifier.weight(1f), color = Am.OnScrim, fontSize = 13.sp)
            Text(left?.let { "%d:%02d".format(it / 60, it % 60) } ?: "", color = Am.OnScrim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(30.dp))
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
