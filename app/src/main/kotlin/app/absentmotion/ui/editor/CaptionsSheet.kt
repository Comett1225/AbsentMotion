package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.R
import app.absentmotion.ai.CaptionModel
import app.absentmotion.ai.CaptionPosition
import app.absentmotion.ai.CaptionStyle
import app.absentmotion.ai.addCaptions
import app.absentmotion.ai.recogniseCaptions
import app.absentmotion.audio.soundUri
import app.absentmotion.model.shownName
import app.absentmotion.editor.EditorState
import app.absentmotion.fonts.FontLibrary
import app.absentmotion.fonts.FontName
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.editor.panels.ThinSlider
import app.absentmotion.ui.editor.panels.ThinSwitch
import app.absentmotion.ui.editor.panels.buttonLabel
import app.absentmotion.ui.editor.panels.colourText
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.IOException
import kotlin.math.roundToInt

internal val SheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
private val ChoiceShape = RoundedCornerShape(6.dp)
private val Swatches = listOf(0xFFFFFFFF, 0xFFFFE14D, 0xFFFF9F1C, 0xFFFF4D4D, 0xFFFF5FC8, 0xFF00FFA8, 0xFF4DB8FF, 0xFF000000).map { it.toInt() }
internal val Warning get() = Am.Warning

private var lastStyle = CaptionStyle()

private fun CaptionPosition.labelRes() = when (this) {
    CaptionPosition.Top -> R.string.caption_position_top
    CaptionPosition.Middle -> R.string.caption_position_middle
    CaptionPosition.Bottom -> R.string.caption_position_bottom
}

private enum class Phase { Choose, Confirm, Download, Work }

internal fun captionRemainingSeconds(elapsedMs: Long, progress: Float): Long? {
    if (elapsedMs < 2_000L || !progress.isFinite()) return null
    val p = progress.toDouble()
    if (p <= 0.0 || p >= 1.0) return null
    val total = elapsedMs.toDouble() / p
    val remaining = total - elapsedMs.toDouble()
    if (remaining <= 0.0) return 0L
    return kotlin.math.ceil(remaining / 1000.0).toLong()
}

internal fun captionSheetHeight(widthDp: Float, heightDp: Float, expanded: Boolean): Float {
    if (!heightDp.isFinite() || heightDp <= 0f) return 0f
    val portrait = !widthDp.isFinite() || widthDp <= heightDp
    val fraction = when {
        expanded -> 0.92f
        portrait -> 0.7f
        else -> 0.88f
    }
    val floor = minOf(heightDp, 320f)
    val wanted = maxOf(heightDp * fraction, floor)
    val capped = if (expanded) wanted else minOf(wanted, 640f)
    return minOf(capped, heightDp)
}

private fun formatClock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val m = s / 60L
    val r = s % 60L
    return "$m:${r.toString().padStart(2, '0')}"
}

@Composable
internal fun CaptionsSheet(state: EditorState, clipId: Long? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val fonts = remember { state.engine.fonts ?: FontLibrary(context) }
    val scope = rememberCoroutineScope()
    val snapshot = remember { state.scene }
    val sources = remember { snapshot.layers.filter { it.soundUri != null }.reversed() }
    val locked = clipId != null
    var source by remember {
        mutableStateOf(
            when {
                locked -> clipId
                else -> null
            }
        )
    }
    var sourceMenu by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(lastStyle) }
    var phase by remember { mutableStateOf(Phase.Choose) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var browsing by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    var phaseStart by remember { mutableLongStateOf(0L) }
    fun update(next: CaptionStyle) { style = next; lastStyle = next }

    fun start(work: suspend () -> Unit) {
        if (job != null) return
        message = ""
        job = scope.launch {
            try { work() }
            catch (_: CancellationException) { message = context.getString(R.string.caption_cancelled) }
            catch (e: Exception) { message = app.absentmotion.i18n.failureText(context.getString(R.string.caption_unable), e) }
            catch (_: OutOfMemoryError) { message = context.getString(R.string.caption_out_of_memory) }
            catch (_: LinkageError) { message = context.getString(R.string.caption_unavailable_build) }
            finally { job = null; phase = Phase.Choose }
        }
    }
    suspend fun captions() {
        phase = Phase.Work
        progress = 0f
        phaseStart = System.nanoTime()
        status = context.getString(R.string.caption_reading_audio)
        val chunks = recogniseCaptions(context, snapshot, source, style) { fraction, text ->
            scope.launch { if (phase == Phase.Work && job?.isActive == true) { progress = fraction; status = text } }
        }
        coroutineContext.ensureActive()
        state.addCaptions(chunks, style, fonts)
        onClose()
    }
    fun generate() {
        if (source != null && sources.none { it.id == source }) { message = context.getString(R.string.ai_no_sound_to_caption); return }
        if (CaptionModel.installed(context, style.highPrecision)) start { captions() } else { message = ""; phase = Phase.Confirm }
    }
    fun download() = start {
        phase = Phase.Download
        progress = 0f
        phaseStart = System.nanoTime()
        try { CaptionModel.download(context, style.highPrecision) { fraction -> scope.launch { if (phase == Phase.Download && job?.isActive == true) progress = fraction } } }
        catch (_: IOException) { error(context.getString(R.string.caption_download_failed)) }
        captions()
    }
    fun dismiss() {
        val running = job
        when {
            running != null -> running.cancel()
            browsing -> browsing = false
            sourceMenu -> sourceMenu = false
            else -> onClose()
        }
    }

    val working = phase == Phase.Download || phase == Phase.Work
    val scrimTap by rememberUpdatedState { if (!working && !browsing) dismiss() }
    Dialog(::dismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { scrimTap() } }.windowInsetsPadding(WindowInsets.safeDrawing),
            Alignment.BottomCenter,
        ) {
            val sheetMax = captionSheetHeight(maxWidth.value, maxHeight.value, browsing).dp
            val sheetSize = if (browsing) Modifier.height(sheetMax) else Modifier.heightIn(max = sheetMax)
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().then(sheetSize).clip(SheetShape).background(Am.Panel)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                when {
                    browsing -> FontBrowser(fonts, onSelect = { name -> fonts.remember(name); update(style.copy(font = name)); browsing = false }, onClose = { browsing = false })
                    phase == Phase.Confirm -> ConfirmScreen(
                        highPrecision = style.highPrecision,
                        minHeight = minOf(sheetMax, 300.dp),
                        onCancel = { phase = Phase.Choose },
                        onDownload = { download() },
                    )
                    working -> WorkingScreen(
                        phase = phase,
                        progress = progress,
                        status = status,
                        phaseStart = phaseStart,
                        minHeight = minOf(sheetMax, 300.dp),
                        onCancel = { job?.cancel() },
                    )
                    else -> SetupScreen(
                        state = state,
                        sources = sources,
                        locked = locked,
                        source = source,
                        sourceMenu = sourceMenu,
                        onSourceMenu = { sourceMenu = it },
                        onSource = { source = it },
                        style = style,
                        onStyle = ::update,
                        appearanceOpen = appearanceOpen,
                        onAppearance = { appearanceOpen = it },
                        message = message,
                        onBrowse = { browsing = true },
                        onGenerate = ::generate,
                        onClose = ::dismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(
    state: EditorState,
    sources: List<app.absentmotion.model.Layer>,
    locked: Boolean,
    source: Long?,
    sourceMenu: Boolean,
    onSourceMenu: (Boolean) -> Unit,
    onSource: (Long?) -> Unit,
    style: CaptionStyle,
    onStyle: (CaptionStyle) -> Unit,
    appearanceOpen: Boolean,
    onAppearance: (Boolean) -> Unit,
    message: String,
    onBrowse: () -> Unit,
    onGenerate: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.auto_captions), Modifier.weight(1f), color = Am.OnDark, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            BarButton(ChromeIcons.Close, stringResource(R.string.action_close), Modifier.size(40.dp), onClick = onClose)
        }
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(stringResource(if (locked) R.string.caption_scope_clip else R.string.caption_intro), Modifier.fillMaxWidth().padding(bottom = 16.dp), color = Am.OnDarkDim, fontSize = 13.sp, lineHeight = 18.sp)
            GroupTitle(stringResource(R.string.caption_source))
            if (locked) {
                val layer = sources.firstOrNull { it.id == source }
                val title = layer?.label?.ifEmpty { layer.soundUri?.let(state.scene::media)?.title.orEmpty() }?.ifEmpty { layer.shownName() }.orEmpty()
                Line(stringResource(R.string.caption_source)) {
                    Text(title, Modifier.widthIn(max = 220.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                val names = listOf(stringResource(R.string.caption_whole_project)) + sources.map { layer ->
                    layer.label.ifEmpty { layer.soundUri?.let(state.scene::media)?.title.orEmpty() }.ifEmpty { layer.shownName() }
                }
                Box {
                    Line(stringResource(R.string.caption_source), Modifier.clickable { onSourceMenu(true) }) {
                        Text(names[sources.indexOfFirst { it.id == source } + 1], Modifier.widthIn(max = 220.dp), color = Am.OnDark, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(ChromeIcons.ChevronDown, null, Modifier.size(24.dp), tint = Am.OnDark)
                    }
                    Presence(sourceMenu) { ChoiceList(names, { onSourceMenu(false) }) { onSource(sources.getOrNull(it - 1)?.id) } }
                }
            }
            Spacer(Modifier.height(8.dp))
            PreviewSample(style, state.engine.fonts)
            Line(stringResource(R.string.settings_language)) { Choices(listOf(stringResource(R.string.caption_language_auto), stringResource(R.string.caption_language_english)), if (style.english) 1 else 0) { onStyle(style.copy(english = it == 1)) } }
            Line(stringResource(R.string.caption_words_per_caption)) {
                Steps(style.maxWords, 1..10, 1, "${style.maxWords}") { onStyle(style.copy(maxWords = it)) }
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(stringResource(R.string.caption_model), color = Am.OnDark, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Choices(listOf(stringResource(R.string.caption_model_standard), stringResource(R.string.caption_model_precision)), if (style.highPrecision) 1 else 0) { onStyle(style.copy(highPrecision = it == 1)) }
            }
            Line(stringResource(R.string.caption_appearance), Modifier.clickable { onAppearance(!appearanceOpen) }) {
                Icon(if (appearanceOpen) ChromeIcons.ChevronDown else ChromeIcons.ChevronRight, null, Modifier.size(24.dp), tint = Am.OnDark)
            }
            if (appearanceOpen) {
            Line(stringResource(R.string.caption_font), Modifier.clickable { onBrowse() }) {
                Text(FontName.parse(style.font).label, Modifier.widthIn(max = 220.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Icon(ChromeIcons.ChevronRight, null, Modifier.size(24.dp), tint = Am.OnDark)
            }
            Line(stringResource(R.string.caption_size)) {
                Steps(style.sizePercent, 50..200, 10, "${style.sizePercent}%") { onStyle(style.copy(sizePercent = it)) }
            }
            Line(stringResource(R.string.color)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (argb in Swatches) {
                        val chosen = argb == style.color
                        Box(Modifier.size(28.dp).clip(CircleShape).buttonLabel(colourText(argb)).clickable { onStyle(style.copy(color = argb)) }
                            .border(2.dp, if (chosen) Am.Accent else Color.Transparent, CircleShape).padding(4.dp)
                            .background(Color(argb), CircleShape).border(1.dp, Am.OnDarkDim, CircleShape))
                    }
                }
            }
            Line(stringResource(R.string.caption_position)) {
                Choices(CaptionPosition.entries.map { stringResource(it.labelRes()) }, style.position.ordinal) { onStyle(style.copy(position = CaptionPosition.entries[it])) }
            }
            Line(stringResource(R.string.caption_outline)) { ThinSwitch(style.outline) { onStyle(style.copy(outline = it)) } }
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (message.isNotEmpty()) {
                Text(message, Modifier.fillMaxWidth().padding(bottom = 8.dp), color = Warning, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2)
            }
            Action(stringResource(R.string.caption_generate), Modifier.fillMaxWidth(), primary = true, onClick = onGenerate)
        }
    }
}

@Composable
private fun ColumnScope.CenteredPanel(minHeight: Dp, actions: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().heightIn(min = minHeight).padding(start = 24.dp, end = 24.dp, top = 24.dp), Arrangement.Center, Alignment.CenterHorizontally, content = content)
    }
    Box(Modifier.fillMaxWidth().padding(24.dp)) { actions() }
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), color = Am.OnDarkDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun PreviewSample(style: CaptionStyle, library: FontLibrary?) {
    val context = LocalContext.current
    val fonts = remember(library) { library ?: FontLibrary(context) }
    val alignment = when (style.position) {
        CaptionPosition.Top -> Alignment.TopCenter
        CaptionPosition.Middle -> Alignment.Center
        CaptionPosition.Bottom -> Alignment.BottomCenter
    }
    Box(Modifier.fillMaxWidth().height(96.dp).clip(ChoiceShape).background(Am.Field).padding(8.dp), alignment) {
        Text(
            stringResource(R.string.caption_preview_text),
            color = Color(style.color),
            fontSize = (style.sizePercent / 100f * 16f).sp,
            fontFamily = app.absentmotion.platform.fontFamily(fonts, style.font),
            style = androidx.compose.ui.text.TextStyle(shadow = if (style.outline) androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 4f) else null),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ColumnScope.ConfirmScreen(highPrecision: Boolean, minHeight: Dp, onCancel: () -> Unit, onDownload: () -> Unit) {
    val context = LocalContext.current
    CenteredPanel(minHeight, actions = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Action(stringResource(R.string.caption_not_now), Modifier.weight(1f), onClick = onCancel)
            Action(stringResource(R.string.caption_download), Modifier.weight(1f), primary = true, onClick = onDownload)
        }
    }) {
        Text(stringResource(R.string.auto_captions), color = Am.OnDark, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            app.absentmotion.platform.captionDownloadDescription(context, CaptionModel.name(highPrecision), CaptionModel.bytes(highPrecision)),
            Modifier.fillMaxWidth(), color = Am.OnDark, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ColumnScope.WorkingScreen(
    phase: Phase,
    progress: Float,
    status: String,
    phaseStart: Long,
    minHeight: Dp,
    onCancel: () -> Unit,
) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phaseStart) {
        while (true) {
            val e = (System.nanoTime() - phaseStart) / 1_000_000L
            elapsed = e
            kotlinx.coroutines.delay(1000L)
        }
    }
    val pct = (progress.coerceIn(0f, 1f) * 100f).roundToInt()
    val remaining = captionRemainingSeconds(elapsed, progress)
    CenteredPanel(minHeight, actions = { Action(stringResource(R.string.cancel), Modifier.fillMaxWidth(), onClick = onCancel) }) {
        CircularProgressIndicator(color = Am.Accent)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.caption_working), color = Am.OnDark, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        val text = if (phase == Phase.Download) stringResource(R.string.caption_downloading_model) else status
        Text(text, Modifier.fillMaxWidth(), color = Am.OnDarkDim, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.caption_progress, pct.toString()), color = Am.OnDark, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Am.Accent)
        Spacer(Modifier.height(12.dp))
        val eta = if (remaining == null) stringResource(R.string.caption_estimating) else stringResource(R.string.caption_remaining, formatClock(remaining))
        Text(eta, Modifier.fillMaxWidth(), color = Am.OnDarkDim, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun Line(label: String, modifier: Modifier = Modifier, control: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        control()
    }
}

@Composable
internal fun Choices(options: List<String>, chosen: Int, onChoose: (Int) -> Unit) {
    Row(Modifier.height(32.dp).background(Am.Field, ChoiceShape).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            Box(
                Modifier.fillMaxHeight().background(if (i == chosen) Am.Accent else Color.Transparent, ChoiceShape)
                    .clickable { onChoose(i) }.padding(horizontal = 10.dp),
                Alignment.Center,
            ) {
                Text(label, color = if (i == chosen) Am.OnAccent else Am.OnDark, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun Steps(value: Int, range: IntRange, step: Int, text: String, onChange: (Int) -> Unit) {
    ThinSlider(
        value.toFloat(), { onChange((it / step).roundToInt() * step) }, Modifier.width(180.dp),
        valueRange = range.first.toFloat()..range.last.toFloat(), inactive = Am.Field,
    )
    Text(text, Modifier.width(44.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1, textAlign = TextAlign.End)
}

@Composable
internal fun Action(label: String, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 40.dp).clip(ChoiceShape).background(if (primary) Am.Accent else Am.Button).clickable(onClick = onClick).padding(horizontal = 8.dp), Alignment.Center) {
        Text(label, color = if (primary) Am.OnAccent else Am.OnDark, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}
