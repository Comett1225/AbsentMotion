package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import app.absentmotion.ui.editor.panels.ThinSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import app.absentmotion.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.ai.CaptionModel
import app.absentmotion.ai.CaptionPosition
import app.absentmotion.ai.CaptionStyle
import app.absentmotion.ai.addCaptions
import app.absentmotion.ai.recogniseCaptions
import app.absentmotion.audio.soundUri
import app.absentmotion.editor.EditorState
import app.absentmotion.fonts.FontLibrary
import app.absentmotion.fonts.FontName
import app.absentmotion.ui.editor.panels.ThinSwitch
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.Swap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.ui.editor.panels.buttonLabel
import app.absentmotion.ui.editor.panels.colourText

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

@Composable
internal fun CaptionsSheet(state: EditorState, onClose: () -> Unit) {
    val context = LocalContext.current
    val fonts = remember { state.engine.fonts ?: FontLibrary(context) }
    val scope = rememberCoroutineScope()
    val sources = remember { state.scene.layers.filter { it.soundUri != null }.reversed() }
    var source by remember { mutableStateOf(state.selectedId?.takeIf { id -> sources.any { it.id == id } }) }
    var sourceMenu by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(lastStyle) }
    var phase by remember { mutableStateOf(Phase.Choose) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var browsing by remember { mutableStateOf(false) }
    fun update(next: CaptionStyle) { style = next; lastStyle = next }

    fun start(work: suspend () -> Unit) {
        message = ""
        job = scope.launch {
            try { work() }
            catch (_: CancellationException) { message = context.getString(R.string.caption_cancelled) }
            catch (e: Exception) { message = e.message ?: context.getString(R.string.caption_unable) }
            catch (_: OutOfMemoryError) { message = context.getString(R.string.caption_out_of_memory) }
            catch (_: LinkageError) { message = context.getString(R.string.caption_unavailable_build) }
            finally { job = null; phase = Phase.Choose }
        }
    }
    suspend fun captions() {
        phase = Phase.Work
        progress = 0f
        status = context.getString(R.string.caption_reading_audio)
        val chunks = recogniseCaptions(context, state.scene, source, style) { fraction, text -> progress = fraction; status = text }
        state.addCaptions(chunks, style, fonts)
        onClose()
    }
    fun generate() {
        if (CaptionModel.installed(context)) start { captions() } else { message = ""; phase = Phase.Confirm }
    }
    fun download() = start {
        phase = Phase.Download
        progress = 0f
        try { CaptionModel.download(context) { progress = it } }
        catch (_: IOException) { error(context.getString(R.string.caption_download_failed)) }
        captions()
    }
    fun dismiss() {
        val running = job
        when {
            running != null -> running.cancel()
            browsing -> browsing = false
            else -> onClose()
        }
    }

    Dialog(::dismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        PhoneScreen { Swap(browsing, Modifier.fillMaxSize(), rise = 0.dp) {
            if (browsing) Box(Modifier.fillMaxSize().background(Am.Panel).systemBarsPadding()) {
                FontBrowser(fonts, onSelect = { name -> fonts.remember(name); update(style.copy(font = name)); browsing = false }, onClose = { browsing = false })
            } else Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { dismiss() }.systemBarsPadding(), Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth().clip(SheetShape).background(Am.Panel).clickable(interactionSource = null, indication = null) {}.padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.auto_captions), Modifier.weight(1f), color = Am.OnDark, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        BarButton(ChromeIcons.Close, stringResource(R.string.action_close), Modifier.size(40.dp), onClick = ::dismiss)
                    }
                    val names = listOf(stringResource(R.string.caption_whole_project)) + sources.map { layer ->
                        layer.label.ifEmpty { layer.soundUri?.let(state.scene::media)?.title.orEmpty() }.ifEmpty { layer.kind }
                    }
                    Box {
                        Line(stringResource(R.string.caption_source), Modifier.clickable { sourceMenu = true }) {
                            Text(names[sources.indexOfFirst { it.id == source } + 1], Modifier.widthIn(max = 220.dp), color = Am.OnDark, fontSize = 14.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(ChromeIcons.ChevronDown, null, Modifier.size(24.dp), tint = Am.OnDark)
                        }
                        Presence(sourceMenu) { ChoiceList(names, { sourceMenu = false }) { source = sources.getOrNull(it - 1)?.id } }
                    }
                    Line(stringResource(R.string.settings_language)) { Choices(listOf(stringResource(R.string.caption_language_auto), stringResource(R.string.caption_language_english)), if (style.english) 1 else 0) { update(style.copy(english = it == 1)) } }
                    Line(stringResource(R.string.caption_font), Modifier.clickable { browsing = true }) {
                        Text(FontName.parse(style.font).label, Modifier.widthIn(max = 220.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Icon(ChromeIcons.ChevronRight, null, Modifier.size(24.dp), tint = Am.OnDark)
                    }
                    Line(stringResource(R.string.caption_size)) {
                        Steps(style.sizePercent, 50..200, 10, "${style.sizePercent}%") { update(style.copy(sizePercent = it)) }
                    }
                    Line(stringResource(R.string.color)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (argb in Swatches) {
                                val chosen = argb == style.color
                                Box(Modifier.size(28.dp).clip(CircleShape).buttonLabel(colourText(argb)).clickable { update(style.copy(color = argb)) }
                                    .border(2.dp, if (chosen) Am.Accent else Color.Transparent, CircleShape).padding(4.dp)
                                    .background(Color(argb), CircleShape).border(1.dp, Am.OnDarkDim, CircleShape))
                            }
                        }
                    }
                    Line(stringResource(R.string.caption_outline)) { ThinSwitch(style.outline) { update(style.copy(outline = it)) } }
                    Line(stringResource(R.string.caption_position)) {
                        Choices(CaptionPosition.entries.map { stringResource(it.labelRes()) }, style.position.ordinal) { update(style.copy(position = CaptionPosition.entries[it])) }
                    }
                    Line(stringResource(R.string.caption_words_per_caption)) {
                        Steps(style.maxWords, 1..10, 1, "${style.maxWords}") { update(style.copy(maxWords = it)) }
                    }
                    Box(Modifier.fillMaxWidth().height(112.dp).padding(vertical = 12.dp)) {
                        when (phase) {
                            Phase.Choose -> Column {
                                Text(message, Modifier.fillMaxWidth().height(36.dp), color = Warning, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2)
                                Action(stringResource(R.string.caption_generate), Modifier.fillMaxWidth(), primary = true, onClick = ::generate)
                            }
                            Phase.Confirm -> Column {
                                Text(
                                    app.absentmotion.platform.captionDownloadDescription(context, CaptionModel.NAME, CaptionModel.BYTES),
                                    Modifier.fillMaxWidth().height(36.dp), color = Am.OnDark, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Action(stringResource(R.string.caption_not_now), Modifier.weight(1f)) { phase = Phase.Choose }
                                    Action(stringResource(R.string.caption_download), Modifier.weight(1f), primary = true) { download() }
                                }
                            }
                            Phase.Download, Phase.Work -> Column {
                                val text = if (phase == Phase.Download) stringResource(R.string.caption_downloading_model) else status
                                Text("$text ${(progress * 100).roundToInt()}%", Modifier.fillMaxWidth().height(22.dp), color = Am.OnDark, fontSize = 13.sp, maxLines = 1)
                                Box(Modifier.fillMaxWidth().height(4.dp).background(Am.Field, CircleShape)) {
                                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Am.Accent, CircleShape))
                                }
                                Spacer(Modifier.height(10.dp))
                                Action(stringResource(R.string.cancel), Modifier.fillMaxWidth()) { job?.cancel() }
                            }
                        }
                    }
                }
            }
        } }
    }
}

@Composable
internal fun Line(label: String, modifier: Modifier = Modifier, control: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
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
    Box(modifier.height(40.dp).clip(ChoiceShape).background(if (primary) Am.Accent else Am.Button).clickable(onClick = onClick), Alignment.Center) {
        Text(label, color = if (primary) Am.OnAccent else Am.OnDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
