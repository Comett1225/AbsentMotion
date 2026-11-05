package app.absentmotion.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.export.formatBytes
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Toast
import app.absentmotion.platform.rememberMediaPicker
import app.absentmotion.platform.slotPicture
import app.absentmotion.tools.CompressLevel
import app.absentmotion.tools.Compressed
import app.absentmotion.tools.CustomChoice
import app.absentmotion.tools.CustomFrameRates
import app.absentmotion.tools.CustomPictureSides
import app.absentmotion.tools.CustomVideoSides
import app.absentmotion.tools.DiscordLimit
import app.absentmotion.tools.PickedMedia
import app.absentmotion.tools.SoundKbps
import app.absentmotion.tools.clearCompressed
import app.absentmotion.tools.compressPicture
import app.absentmotion.tools.compressVideo
import app.absentmotion.tools.estimatePicture
import app.absentmotion.tools.estimateVideo
import app.absentmotion.tools.limitText
import app.absentmotion.tools.memorySide
import app.absentmotion.tools.planPicture
import app.absentmotion.tools.planVideo
import app.absentmotion.tools.readPicked
import app.absentmotion.tools.saveToGallery
import app.absentmotion.tools.shareCompressed
import app.absentmotion.tools.sizeChange
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.animatedColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private sealed interface Step {
    data object Ready : Step
    data class Running(val percent: Int, val retry: Boolean) : Step
    data class Done(val result: Compressed, val bytes: Long, val saved: Boolean = false) : Step
    data class Failed(val message: String) : Step
}

private val Levels = listOf(
    Triple(CompressLevel.Low, R.string.compress_level_low, R.string.compress_level_low_about),
    Triple(CompressLevel.Medium, R.string.compress_level_medium, R.string.compress_level_medium_about),
    Triple(CompressLevel.High, R.string.compress_level_high, R.string.compress_level_high_about),
    Triple(CompressLevel.Discord, R.string.compress_level_discord, R.string.compress_level_discord_about),
    Triple(CompressLevel.Custom, R.string.compress_level_custom, R.string.compress_level_custom_about),
)

private val DiscordNames = listOf(R.string.compress_discord_free, R.string.compress_discord_basic, R.string.compress_discord_nitro)

@Composable
internal fun CompressScreen(initial: android.net.Uri? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val storage = app.absentmotion.platform.rememberStorageAccess()
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<PickedMedia?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    LaunchedEffect(initial) {
        if (initial != null) {
            picked = readPicked(context, initial)
            unreadable = picked == null
        }
    }
    var level by rememberSaveable { mutableStateOf(CompressLevel.Medium) }
    var discord by rememberSaveable { mutableStateOf(DiscordLimit.Free) }
    var custom by remember { mutableStateOf(CustomChoice()) }
    var step by remember { mutableStateOf<Step>(Step.Ready) }
    var work by remember { mutableStateOf<Job?>(null) }
    val running = step is Step.Running
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { clearCompressed(context) } }
    val picker = rememberMediaPicker { uri ->
        if (uri != null) scope.launch {
            work?.cancel()
            step = Step.Ready
            picked = readPicked(context, uri)
            unreadable = picked == null
        }
    }
    val source = picked?.source
    val videoJob = remember(source, level, discord, custom) { source?.takeIf { it.video }?.let { planVideo(it, level, custom, discord) } }
    val pictureJob = remember(source, level, discord, custom) { source?.takeIf { !it.video }?.let { planPicture(level, custom, discord) } }
    val estimate = remember(source, videoJob, pictureJob) {
        when {
            source == null -> null
            videoJob != null -> if (videoJob.fits) estimateVideo(videoJob, source.durationUs) else null
            pictureJob != null -> estimatePicture(pictureJob, source.width, source.height, memorySide())
            else -> null
        }
    }
    fun change(block: () -> Unit) { if (!running) { block(); step = Step.Ready } }
    fun start() {
        val media = picked ?: return
        step = Step.Running(0, false)
        work = scope.launch {
            try {
                val result = if (videoJob != null) compressVideo(context, media, videoJob) { fraction, retry ->
                    val percent = (fraction * 100).toInt().coerceIn(0, 100)
                    if (step != Step.Running(percent, retry)) step = Step.Running(percent, retry)
                } else compressPicture(context, media, pictureJob ?: return@launch)
                step = Step.Done(result, result.file.length())
            } catch (e: CancellationException) {
                step = Step.Ready
                throw e
            } catch (e: Exception) {
                step = Step.Failed(e.message ?: e.javaClass.simpleName)
            } catch (e: OutOfMemoryError) {
                step = Step.Failed(context.getString(R.string.compress_too_big))
            }
        }
    }
    val back = { if (running) work?.cancel() ?: Unit else onClose() }
    ToolSheet(stringResource(R.string.tools_compress), back, bottom = {
        val view = LocalView.current
        DisposableEffect(running) {
            view.keepScreenOn = running
            onDispose { view.keepScreenOn = false }
        }
        CompressBar(
            step, picked, estimate, videoJob?.limitBytes ?: pictureJob?.limitBytes ?: 0L, videoJob?.fits == false,
            onStart = ::start,
            onCancel = { work?.cancel() },
            onSave = { done ->
                fun save() {
                    scope.launch {
                        try {
                            val where = saveToGallery(context, done.result)
                            if (step == done) step = done.copy(saved = true)
                            Toast.makeText(context, context.getString(R.string.compress_saved_to, where), Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, context.getString(R.string.compress_save_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                if (storage.ready { save() }) save()
            },
            onShare = { done -> runCatching { shareCompressed(context, done.result) } },
        )
    }, side = {
        SectionTitle(stringResource(R.string.compress_how_small))
        for ((value, title, about) in Levels) {
            ChoiceRow(stringResource(title), stringResource(about), level == value, enabled = !running) { change { level = value } }
        }
        if (level == CompressLevel.Discord) {
            Spacer(Modifier.height(4.dp))
            Chips(DiscordNames.map { stringResource(it) }, discord.ordinal, !running) { i -> change { discord = DiscordLimit.entries[i] } }
        }
        if (level == CompressLevel.Custom) CustomPanel(source?.video != false, custom, !running) { next -> change { custom = next } }
        Spacer(Modifier.height(24.dp))
    }) {
        Spacer(Modifier.height(20.dp))
        PickCard(picked, unreadable, !running) { picker.launch() }
        val done = step as? Step.Done
        if (done != null && picked != null) ResultPreview(done.result, picked!!.uri)
    }
}

@Composable
private fun ResultPreview(result: Compressed, original: android.net.Uri) {
    var before by remember(result) { mutableStateOf(false) }
    var full by remember(result) { mutableStateOf(false) }
    val kind = if (result.video) app.absentmotion.export.ExportKind.Video else app.absentmotion.export.ExportKind.Frame
    val shown = if (before) original else android.net.Uri.fromFile(result.file)
    SectionTitle(stringResource(R.string.compress_result))
    Chips(listOf(stringResource(R.string.compress_after), stringResource(R.string.compress_before)), if (before) 1 else 0, true) { before = it == 1 }
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(12.dp)).background(Am.Scrim)) {
        if (!full) androidx.compose.runtime.key(before) {
            Box(Modifier.fillMaxSize().then(if (result.video) Modifier else Modifier.clickable { full = true })) {
                app.absentmotion.platform.ExportMediaPreview(kind, listOf(shown), onTap = { full = true })
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(10.dp).size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.55f)).clickable { full = true },
            contentAlignment = Alignment.Center,
        ) { Icon(app.absentmotion.ui.editor.ChromeIcons.Fullscreen, stringResource(R.string.compress_full_screen), Modifier.size(20.dp), tint = Color.White) }
    }
    if (full) androidx.compose.ui.window.Dialog({ full = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((i, label) in listOf(R.string.compress_after, R.string.compress_before).withIndex()) {
                        val chosen = (i == 1) == before
                        Box(
                            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (chosen) Am.Accent else Color.White.copy(alpha = 0.14f))
                                .clickable { before = i == 1 }.padding(horizontal = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(stringResource(label), color = if (chosen) Am.OnAccent else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                    }
                }
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { full = false }, contentAlignment = Alignment.Center) {
                    Icon(app.absentmotion.ui.editor.ChromeIcons.Close, stringResource(R.string.action_close), Modifier.size(26.dp), tint = Color.White)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                androidx.compose.runtime.key(before) { app.absentmotion.platform.ExportMediaPreview(kind, listOf(shown), large = true) }
            }
        }
    }
}

@Composable
internal fun PickCard(
    picked: PickedMedia?, unreadable: Boolean, enabled: Boolean, @StringRes pick: Int = R.string.compress_pick, @StringRes problem: Int = R.string.compress_unreadable,
    onPick: () -> Unit,
) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, picked?.uri) { value = picked?.let { slotPicture(context, it.uri.toString(), it.source.video) } }
    Row(
        Modifier.fillMaxWidth().height(96.dp).alpha(if (enabled) 1f else 0.6f).clip(RoundedCornerShape(12.dp)).background(Am.Card)
            .clickable(enabled = enabled, onClick = onPick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(Am.HomeLine), contentAlignment = Alignment.Center) {
            val image = thumb
            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(HomeIcons.Plus, null, Modifier.size(28.dp), tint = Am.Navy)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (picked == null) {
                Text(stringResource(pick), color = Am.Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(
                    stringResource(if (unreadable) problem else R.string.compress_pick_note),
                    color = if (unreadable) Am.Accent else Am.HomeDim, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2,
                )
            } else {
                Text(picked.name, color = Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(details(picked), color = Am.HomeDim, fontSize = 14.sp, maxLines = 2)
            }
        }
        if (picked != null) Text(stringResource(R.string.compress_change), Modifier.padding(start = 8.dp), color = Am.Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

private fun details(picked: PickedMedia): String {
    val s = picked.source
    val turned = picked.rotation % 180 != 0
    val size = "${if (turned) s.height else s.width} × ${if (turned) s.width else s.height}"
    if (!s.video) return "$size · ${formatBytes(s.bytes)}"
    val seconds = (s.durationUs + 500_000) / 1_000_000
    return "$size · ${String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)} · ${formatBytes(s.bytes)}"
}

@Composable
private fun CompressBar(
    step: Step, picked: PickedMedia?, estimate: Long?, limit: Long, tooLong: Boolean,
    onStart: () -> Unit, onCancel: () -> Unit, onSave: (Step.Done) -> Unit, onShare: (Step.Done) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Am.Card).padding(16.dp)) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
            when (step) {
                is Step.Running -> Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(if (step.retry) R.string.compress_retrying else R.string.compress_running, step.percent),
                        color = Am.Navy, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        progress = { step.percent / 100f }, Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(4.dp)),
                        color = Am.Accent, trackColor = Am.Navy.copy(alpha = 0.12f),
                    )
                }
                is Step.Done -> {
                    val (change, percent) = sizeChange(picked?.source?.bytes ?: 0L, step.bytes)
                    BarText(if (percent > 0) stringResource(R.string.compress_done, change, percent) else stringResource(R.string.compress_not_smaller, change), Am.Navy)
                }
                is Step.Failed -> BarText(stringResource(R.string.compress_failed, step.message), Am.Accent)
                Step.Ready -> when {
                    picked == null -> BarText(stringResource(R.string.compress_choose_first), Am.HomeDim)
                    tooLong -> BarText(stringResource(R.string.compress_too_long, limitText(limit)), Am.Accent)
                    estimate != null -> {
                        val now = formatBytes(picked.source.bytes)
                        val main = if (limit in 1..estimate) stringResource(R.string.compress_estimate_limit, formatBytes(estimate), now)
                            else stringResource(R.string.compress_estimate, formatBytes(estimate), now)
                        BarText(if (estimate >= picked.source.bytes * 0.85) main + "\n" + stringResource(R.string.compress_already_small) else main, Am.Navy)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                is Step.Running -> BigButton(stringResource(R.string.cancel), Modifier.weight(1f), primary = false, onClick = onCancel)
                is Step.Done -> {
                    BigButton(stringResource(if (step.saved) R.string.compress_saved else if (app.absentmotion.platform.PlatformFeatures.desktop) R.string.tools_save else R.string.compress_save), Modifier.weight(1f).fillMaxHeight(), HomeIcons.Download, enabled = !step.saved) { onSave(step) }
                    BigButton(stringResource(R.string.compress_share), Modifier.weight(1f).fillMaxHeight(), ExportIcons.Share, primary = false) { onShare(step) }
                }
                is Step.Failed -> {
                    var reporting by remember { mutableStateOf(false) }
                    if (reporting) ReportDialog(stringResource(R.string.compress_failed, step.message)) { reporting = false }
                    BigButton(stringResource(R.string.compress_start), Modifier.weight(1f), HomeIcons.Compress, enabled = picked != null && estimate != null, onClick = onStart)
                    BigButton(stringResource(R.string.report_button), Modifier.weight(1f), primary = false) { reporting = true }
                }
                else -> BigButton(stringResource(R.string.compress_start), Modifier.weight(1f), HomeIcons.Compress, enabled = picked != null && estimate != null, onClick = onStart)
            }
        }
    }
}

@Composable
internal fun BarText(text: String, color: androidx.compose.ui.graphics.Color) =
    Text(text, color = color, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)

@Composable
private fun CustomPanel(video: Boolean, custom: CustomChoice, enabled: Boolean, onChange: (CustomChoice) -> Unit) {
    val original = stringResource(R.string.compress_original)
    Label(stringResource(R.string.compress_size))
    Chips(
        if (video) CustomVideoSides.map { if (it == 0) original else "${it}p" } else CustomPictureSides.map { if (it == 0) original else "$it px" },
        custom.size, enabled,
    ) { onChange(custom.copy(size = it)) }
    Label(stringResource(R.string.compress_quality))
    Chips(
        listOf(R.string.compress_quality_best, R.string.compress_quality_good, R.string.compress_quality_small, R.string.compress_quality_smallest).map { stringResource(it) },
        if (custom.bySize) -1 else custom.quality, enabled,
    ) { onChange(custom.copy(quality = it, bySize = false)) }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        ToolChip(stringResource(R.string.compress_aim_size), custom.bySize, enabled) { onChange(custom.copy(bySize = true)) }
        Spacer(Modifier.width(10.dp))
        val typing = enabled && custom.bySize
        Row(
            Modifier.height(42.dp).width(116.dp).alpha(if (typing) 1f else 0.5f).clip(RoundedCornerShape(14.dp)).background(Am.Card).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                custom.targetMb.toString(), { typed -> onChange(custom.copy(targetMb = typed.filter(Char::isDigit).take(4).toIntOrNull() ?: 0)) },
                Modifier.weight(1f), enabled = typing, singleLine = true,
                textStyle = TextStyle(color = Am.HomeInk, fontSize = 16.sp), cursorBrush = SolidColor(Am.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text(stringResource(R.string.compress_megabytes), color = Am.HomeDim, fontSize = 15.sp)
        }
    }
    if (video) {
        Label(stringResource(R.string.compress_frame_rate))
        Chips(CustomFrameRates.map { if (it == 0) original else "$it" }, CustomFrameRates.indexOf(custom.fps), enabled) { onChange(custom.copy(fps = CustomFrameRates[it])) }
        Label(stringResource(R.string.compress_sound))
        Chips(
            listOf(R.string.compress_sound_off, R.string.compress_quality_small, R.string.compress_quality_good, R.string.compress_quality_best).map { stringResource(it) },
            if (custom.sound) SoundKbps.indexOf(custom.soundKbps) + 1 else 0, enabled,
        ) { i -> onChange(if (i == 0) custom.copy(sound = false) else custom.copy(sound = true, soundKbps = SoundKbps[i - 1])) }
    } else {
        Label(stringResource(R.string.compress_format))
        Chips(listOf("JPEG", "WebP"), if (custom.webp) 1 else 0, enabled) { onChange(custom.copy(webp = it == 1)) }
    }
}

@Composable
internal fun Label(text: String) = Text(text, Modifier.padding(top = 16.dp, bottom = 8.dp), color = Am.HomeMid, fontSize = 14.sp, fontWeight = FontWeight.Bold)

@Composable
internal fun Chips(options: List<String>, selected: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, option -> ToolChip(option, i == selected, enabled) { onPick(i) } }
    }
}

@Composable
internal fun ToolChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(42.dp).alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(14.dp)).background(animatedColor(if (selected) Am.Navy else Am.Card))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = animatedColor(if (selected) Am.OnNavy else Am.Navy), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
