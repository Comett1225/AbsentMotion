package app.absentmotion.ui.editor.effects

import app.absentmotion.i18n.text
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.ai.AiAction
import app.absentmotion.ai.AiDevice
import app.absentmotion.ai.AiEnhance
import app.absentmotion.ai.AiSmooth
import app.absentmotion.ai.AiTools
import app.absentmotion.ai.CutoutQuality
import app.absentmotion.ai.AiVerdict
import app.absentmotion.ai.EnhanceMode
import app.absentmotion.ai.aiEstimate
import app.absentmotion.export.formatBytes
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import app.absentmotion.ai.AiKind
import app.absentmotion.ai.MAX_SMOOTH_FRAMES
import app.absentmotion.ai.aiMarks
import app.absentmotion.ai.smoothPlan
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.MotionBlur
import app.absentmotion.export.SmoothLevel
import app.absentmotion.ui.export.SmoothingHelp
import androidx.compose.material3.Icon
import app.absentmotion.ai.LONG_MATTE_SECONDS
import app.absentmotion.ai.MAX_MATTE_SECONDS
import app.absentmotion.ai.MatteSides
import app.absentmotion.ai.NoiseSource
import app.absentmotion.ai.matteBytes
import app.absentmotion.ai.matteSeconds
import app.absentmotion.ai.matteSize
import app.absentmotion.ai.smoothSource
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import kotlin.math.roundToInt
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.TilePress
import app.absentmotion.ui.theme.pressScale
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

@Composable
internal fun AiBackdrop(modifier: Modifier, action: AiAction? = null) {
    Canvas(modifier.background(Brush.linearGradient(listOf(Color(0xFF7862A8), Color(0xFF467B8B), Color(0xFF509C95))))) {
        for ((x, y, radius) in listOf(Triple(0.16f, 0.25f, 4f), Triple(0.83f, 0.32f, 3f), Triple(0.71f, 0.78f, 2f))) {
            val cx = size.width * x; val cy = size.height * y; val r = radius.dp.toPx()
            drawPath(Path().apply {
                moveTo(cx, cy - r); quadraticTo(cx, cy, cx + r, cy)
                quadraticTo(cx, cy, cx, cy + r); quadraticTo(cx, cy, cx - r, cy)
                quadraticTo(cx, cy, cx, cy - r); close()
            }, Color.White.copy(alpha = 0.65f))
        }
        val cx = size.width / 2; val cy = size.height / 2
        val unit = minOf(size.width, size.height)
        when (action) {
            AiAction.Background -> {
                drawCircle(Color.White.copy(alpha = 0.85f), unit * 0.1f, Offset(cx, cy - unit * 0.12f))
                drawPath(Path().apply {
                    moveTo(cx - unit * 0.22f, cy + unit * 0.22f)
                    cubicTo(cx - unit * 0.22f, cy - unit * 0.05f, cx + unit * 0.22f, cy - unit * 0.05f, cx + unit * 0.22f, cy + unit * 0.22f)
                    close()
                }, Color.White.copy(alpha = 0.85f))
            }
            AiAction.Noise -> {
                for (i in -4..4) {
                    val h = unit * (if (i % 2 == 0) 0.13f else 0.23f)
                    val x = cx + i * unit * 0.06f
                    drawLine(Color.White.copy(alpha = 0.85f), Offset(x, cy - h), Offset(x, cy + h), unit * 0.022f, StrokeCap.Round)
                }
            }
            AiAction.Video -> drawPath(Path().apply {
                moveTo(cx - unit * 0.12f, cy - unit * 0.2f)
                lineTo(cx + unit * 0.2f, cy); lineTo(cx - unit * 0.12f, cy + unit * 0.2f); close()
            }, Color.White.copy(alpha = 0.85f))
            AiAction.Enhance -> {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(unit * 0.025f)
                drawRect(Color.White.copy(alpha = 0.85f), Offset(cx - unit * 0.22f, cy - unit * 0.02f), androidx.compose.ui.geometry.Size(unit * 0.2f, unit * 0.2f))
                drawRect(Color.White.copy(alpha = 0.85f), Offset(cx - unit * 0.22f, cy - unit * 0.22f), androidx.compose.ui.geometry.Size(unit * 0.44f, unit * 0.44f), style = stroke)
                drawLine(Color.White.copy(alpha = 0.85f), Offset(cx + unit * 0.02f, cy - unit * 0.02f), Offset(cx + unit * 0.15f, cy - unit * 0.15f), unit * 0.025f, StrokeCap.Round)
            }
            AiAction.Smooth, null -> Unit
        }
    }
}

@Composable
internal fun AiTile(action: AiAction, layer: Layer?, tools: AiTools) {
    val reason = action.unavailable(layer)
    Column(Modifier.width(100.dp).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val press = remember { MutableInteractionSource() }
        Column(Modifier.pressScale(press, TilePress).alpha(if (reason == null) 1f else 0.35f)
            .clickable(press, LocalIndication.current, enabled = reason == null && tools.running == null) { tools.apply(action) }, horizontalAlignment = Alignment.CenterHorizontally) {
            AiBackdrop(Modifier.size(93.dp).clip(RoundedCornerShape(5.dp)), action)
            Text(stringResource(action.title), Modifier.fillMaxWidth().height(26.dp).padding(top = 4.dp), color = Am.OnDark, fontSize = 8.sp,
                lineHeight = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
        }
        Text(reason?.text() ?: "", Modifier.fillMaxWidth().height(16.dp), color = Am.OnDark.copy(alpha = 0.7f), fontSize = 8.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
    }
}

@Composable
internal fun AiVideoDialog(tools: AiTools, scene: Scene) {
    val layer = tools.videoSetup ?: return
    val media = layer.mediaFill?.uri?.let(scene::media)
    val seconds = media?.let(layer::matteSeconds) ?: 0.0
    val frames = (seconds * (media?.fps ?: 30f)).roundToInt()
    val size = media?.let { matteSize(it.width, it.height, tools.videoSide) }
    val tooLong = seconds > MAX_MATTE_SECONDS
    val sizeNames = listOf(R.string.aibrowser_size_small, R.string.aibrowser_size_medium, R.string.aibrowser_size_large)
    AlertDialog(onDismissRequest = { tools.videoSetup = null },
        title = { Text(stringResource(AiAction.Video.title), color = Am.OnDark) },
        text = {
            Column {
                Text(stringResource(R.string.every_frame_of_this_clip_is),
                    color = Am.OnDark, fontSize = 14.sp)
                Row(Modifier.padding(top = 14.dp).fillMaxWidth().height(36.dp).clip(RoundedCornerShape(6.dp)).background(Am.PanelInset)) {
                    for ((side, name) in MatteSides.zip(sizeNames)) {
                        val on = side == tools.videoSide
                        Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp).clip(RoundedCornerShape(5.dp)).background(if (on) Am.FieldHi else Color.Transparent)
                            .clickable { tools.videoSide = side }, Alignment.Center) {
                            Text(stringResource(R.string.aibrowser_size_option, stringResource(name), side.toString()), color = if (on) Am.Accent else Am.OnDark, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
                Text(
                    when {
                        tooLong -> pluralStringResource(R.plurals.aibrowser_too_long, MAX_MATTE_SECONDS / 60, (MAX_MATTE_SECONDS / 60).toString())
                        size == null -> stringResource(R.string.aibrowser_no_size)
                        else -> pluralStringResource(
                            if (seconds > LONG_MATTE_SECONDS) R.plurals.aibrowser_estimate_long else R.plurals.aibrowser_estimate, frames,
                            frames.toString(), size.first.toString(), size.second.toString(), (matteBytes(seconds, size.first, size.second) / 1_000_000 + 1).toString(),
                        )
                    },
                    Modifier.padding(top = 12.dp).height(54.dp), color = if (tooLong) Am.Warning else Am.Caption, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        },
        confirmButton = {
            TextButton({ tools.apply(AiAction.Video, confirmed = true) }, enabled = !tooLong && size != null) { Text(stringResource(R.string.start), color = Am.Accent) }
        },
        dismissButton = { TextButton({ tools.videoSetup = null }) { Text(stringResource(R.string.cancel), color = Am.Accent) } },
        containerColor = Am.Field)
}

@Composable
internal fun AiNoiseDialog(tools: AiTools) {
    if (tools.noiseSetup == null) return
    AlertDialog(onDismissRequest = { tools.noiseSetup = null },
        title = { Text(stringResource(AiAction.Noise.title), color = Am.OnDark) },
        text = {
            Column {
                Text(stringResource(R.string.ai_noise_question), color = Am.OnDark, fontSize = 14.sp)
                Column(Modifier.padding(top = 12.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (source in NoiseSource.entries) {
                        val on = source == tools.noiseSource
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (on) Am.FieldHi else Am.PanelInset)
                            .selectable(on, role = Role.RadioButton) { tools.noiseSource = source }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(on, null, colors = RadioButtonDefaults.colors(selectedColor = Am.Accent, unselectedColor = Am.OnDark))
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(stringResource(source.title), color = if (on) Am.Accent else Am.OnDark, fontSize = 14.sp)
                                Text(stringResource(source.hint), color = Am.Caption, fontSize = 12.sp, lineHeight = 16.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ tools.apply(AiAction.Noise, confirmed = true) }) { Text(stringResource(R.string.start), color = Am.Accent) } },
        dismissButton = { TextButton({ tools.noiseSetup = null }) { Text(stringResource(R.string.cancel), color = Am.Accent) } },
        containerColor = Am.Field)
}

@Composable
internal fun AiCutoutDialog(tools: AiTools) {
    if (tools.cutoutSetup == null) return
    val size = formatBytes(app.absentmotion.ai.AiModel.Cutout.bytes)
    AlertDialog(onDismissRequest = { tools.cutoutSetup = null },
        title = { Text(stringResource(R.string.settings_cutout_quality), color = Am.OnDark) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.cutout_question), color = Am.OnDark, fontSize = 14.sp)
                Column(Modifier.padding(top = 12.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (quality in CutoutQuality.entries) {
                        val on = quality == tools.cutoutQuality
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (on) Am.FieldHi else Am.PanelInset)
                            .selectable(on, role = Role.RadioButton) { tools.cutoutQuality = quality }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(on, null, colors = RadioButtonDefaults.colors(selectedColor = Am.Accent, unselectedColor = Am.OnDark))
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(stringResource(quality.title), color = if (on) Am.Accent else Am.OnDark, fontSize = 14.sp)
                                Text(stringResource(quality.hint, size), color = Am.Caption, fontSize = 12.sp, lineHeight = 16.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ tools.apply(AiAction.Background, confirmed = true) }) { Text(stringResource(R.string.start), color = Am.Accent) } },
        dismissButton = { TextButton({ tools.cutoutSetup = null }) { Text(stringResource(R.string.cancel), color = Am.Accent) } },
        containerColor = Am.Field)
}

@Composable
internal fun AiEnhanceDialog(tools: AiTools, scene: Scene, onQuickSharpen: () -> Unit) {
    val layer = tools.enhanceSetup ?: return
    val context = LocalContext.current
    val media = layer.mediaFill?.uri?.let(scene::media)
    val options = tools.enhance
    val fits = media != null && options.mode.fits(media.width, media.height)
    val seconds = media?.let(layer::matteSeconds) ?: 0.0
    val tooLong = seconds > MAX_MATTE_SECONDS
    var help by remember { mutableStateOf<EnhanceMode?>(null) }
    var estimate by remember { mutableStateOf<String?>(null) }
    var cannot by remember { mutableStateOf(false) }
    val missing = options.models.filterNot { it.installed(context) }.sumOf { it.bytes }
    LaunchedEffect(options, layer, media) {
        estimate = null
        cannot = false
        if (media == null || !options.usesAi || !fits) return@LaunchedEffect
        val phone = AiDevice.phone(context)
        val gmac = seconds * (media.fps ?: 30f) * options.gmacPerFrame(media.width, media.height)
        aiEstimate(phone, gmac, AiDevice.measured(options.model, phone.usesGpu)).let { estimate = it.text; cannot = it.verdict == AiVerdict.Cannot }
        AiDevice.benchmark(context, options.model)?.let { rate -> aiEstimate(phone, gmac, rate).let { estimate = it.text; cannot = it.verdict == AiVerdict.Cannot } }
    }
    AlertDialog(onDismissRequest = { tools.enhanceSetup = null },
        title = { Text(stringResource(R.string.ai_enhance), color = Am.OnDark) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (mode in EnhanceMode.entries) {
                        val on = mode == options.mode
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (on) Am.FieldHi else Am.PanelInset)
                            .selectable(on, role = Role.RadioButton) { tools.enhance = options.copy(mode = mode) }.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(on, null, colors = RadioButtonDefaults.colors(selectedColor = Am.Accent, unselectedColor = Am.OnDark))
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(stringResource(mode.title), color = if (on) Am.Accent else Am.OnDark, fontSize = 14.sp)
                                    Text(stringResource(mode.hint), color = Am.Caption, fontSize = 12.sp, lineHeight = 16.sp)
                                }
                                Box(Modifier.size(32.dp).clip(CircleShape).clickable { help = if (help == mode) null else mode }, Alignment.Center) {
                                    Box(Modifier.size(20.dp).border(1.5.dp, Am.OnDark, CircleShape), Alignment.Center) {
                                        Text("?", color = Am.OnDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            if (help == mode) Text(stringResource(mode.help), Modifier.padding(top = 6.dp), color = Am.OnDark, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
                if (options.mode == EnhanceMode.Sharpen) EnhanceSwitch(stringResource(R.string.ai_enhance_quick), options.quick) { tools.enhance = options.copy(quick = it) }
                if (options.usesAi) {
                    EnhanceSwitch(stringResource(R.string.ai_enhance_cartoon), options.cartoon) { tools.enhance = options.copy(cartoon = it) }
                    if (!options.cartoon) EnhanceSwitch(stringResource(R.string.ai_enhance_grain), options.grain) { tools.enhance = options.copy(grain = it) }
                }
                val note = when {
                    !options.usesAi -> stringResource(R.string.ai_enhance_quick_note)
                    media == null -> stringResource(R.string.aibrowser_no_size)
                    tooLong -> pluralStringResource(R.plurals.aibrowser_too_long, MAX_MATTE_SECONDS / 60, (MAX_MATTE_SECONDS / 60).toString())
                    !fits -> AiEnhance.capText(options.mode)
                    else -> listOfNotNull(estimate ?: stringResource(R.string.ai_checking_phone),
                        if (missing > 0) stringResource(R.string.ai_download_once, formatBytes(missing)) else null).joinToString("\n")
                }
                Text(note, Modifier.padding(top = 12.dp).heightIn(min = 40.dp), color = if (!fits || tooLong || cannot) Am.Warning else Am.Caption, fontSize = 12.sp, lineHeight = 17.sp)
            }
        },
        confirmButton = {
            TextButton({
                if (options.usesAi) tools.apply(AiAction.Enhance, confirmed = true) else { tools.enhanceSetup = null; onQuickSharpen() }
            }, enabled = !options.usesAi || (fits && !tooLong && !cannot)) {
                Text(stringResource(if (options.usesAi && missing > 0) R.string.ai_download_and_start else R.string.start), color = Am.Accent)
            }
        },
        dismissButton = { TextButton({ tools.enhanceSetup = null }) { Text(stringResource(R.string.cancel), color = Am.Accent) } },
        containerColor = Am.Field)
}

@Composable
private fun EnhanceSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(top = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp)
        app.absentmotion.ui.editor.panels.ThinSwitch(on, onChange = onChange)
    }
}

@Composable
internal fun AiProgressDialog(tools: AiTools) {
    if (tools.message.isEmpty()) return
    val action = tools.running
    val view = LocalView.current
    DisposableEffect(action != null) { view.keepScreenOn = action != null; onDispose { view.keepScreenOn = false } }
    AlertDialog(onDismissRequest = { if (action != null) tools.cancel() else tools.dismiss() },
        title = { Text((action ?: tools.last)?.let { stringResource(it.title) } ?: stringResource(R.string.ai), color = Am.OnDark) },
        text = {
            Column {
                Text(tools.message, color = Am.OnDark, fontSize = 14.sp)
                if (action != null) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { tools.progress }, Modifier.fillMaxWidth(), color = Am.Accent)
                }
            }
        },
        confirmButton = {
            TextButton({ if (action != null) tools.cancel() else tools.retry?.let(tools::apply) ?: tools.dismiss() }) {
                Text(stringResource(if (action != null) R.string.cancel else if (tools.retry != null) R.string.aibrowser_retry else R.string.ok), color = Am.Accent)
            }
        },
        dismissButton = { if (action == null && tools.retry != null) TextButton(tools::dismiss) { Text(stringResource(R.string.cancel), color = Am.Accent) } },
        containerColor = Am.Field)
}

@Composable
internal fun AiSmoothDialog(tools: AiTools, scene: Scene) {
    val layer = tools.smoothSetup ?: return
    var help by remember { mutableStateOf(false) }
    val previous = layer.aiMarks.firstOrNull { it.kind == AiKind.Smooth }
    val media = previous?.media ?: layer.mediaFill?.uri?.let(scene::media)
    val source = layer.smoothSource(previous)
    val s = tools.smoothing
    val plan = media?.let { smoothPlan(source, it, scene.fps, s) }
    val tooLong = plan != null && (plan.seconds > MAX_MATTE_SECONDS || plan.frames > MAX_SMOOTH_FRAMES)
    val ai = s.method == FrameInterpolation.Ai
    val estimate = if (ai && plan != null) app.absentmotion.ui.export.aiSmoothEstimate(
        AiSmooth.gmac(s.level, plan.frames.toDouble(), media.width, media.height), s.level) else null
    val cannot = estimate?.verdict == AiVerdict.Cannot
    val download = ai && !AiSmooth.installed(LocalContext.current, s.level)
    AlertDialog(onDismissRequest = { tools.smoothSetup = null },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(AiAction.Smooth.title), color = Am.OnDark)
                Box(Modifier.size(36.dp).clickable { help = true }, Alignment.Center) {
                    Icon(app.absentmotion.ui.export.ExportIcons.Help, stringResource(R.string.export_what_is, stringResource(AiAction.Smooth.title)), Modifier.size(20.dp), tint = Am.Caption)
                }
            }
        },
        text = {
            Column {
                Text(stringResource(R.string.smooth_intro), color = Am.OnDark, fontSize = 14.sp)
                Text(stringResource(R.string.smooth_method), Modifier.padding(top = 12.dp), color = Am.Caption, fontSize = 12.sp)
                Pills(FrameInterpolation.entries, s.method, true, { stringResource(it.label) },
                    locked = { it == FrameInterpolation.Ai && !app.absentmotion.Tier.has(app.absentmotion.Feature.AiSmooth) }) { tools.smoothing = s.copy(method = it) }
                Pills(SmoothLevel.entries, s.level, s.method != FrameInterpolation.Off, { stringResource(it.label) },
                    locked = { s.method == FrameInterpolation.Flow && it == SmoothLevel.High && !app.absentmotion.Tier.has(app.absentmotion.Feature.FlowHigh) }) { tools.smoothing = s.copy(level = it) }
                Text(stringResource(R.string.export_motion_blur), Modifier.padding(top = 12.dp), color = Am.Caption, fontSize = 12.sp)
                Pills(MotionBlur.entries, s.blur, true, { stringResource(it.label) }) { tools.smoothing = s.copy(blur = it) }
                Text(
                    when {
                        media == null -> stringResource(R.string.ai_video_missing)
                        tooLong -> stringResource(R.string.ai_clip_too_long, (MAX_MATTE_SECONDS / 60).toString())
                        plan == null -> stringResource(R.string.smooth_nothing)
                        else -> pluralStringResource(R.plurals.smooth_estimate, plan.frames, plan.frames.toString(), plan.fps.toString(),
                            plan.width.toString(), plan.height.toString(), (plan.bytes / 1_000_000 + 1).toString())
                    },
                    Modifier.padding(top = 12.dp).height(54.dp), color = if (plan == null || tooLong) Am.Warning else Am.Caption, fontSize = 12.sp, lineHeight = 17.sp,
                )
                if (ai && plan != null && !tooLong) Text(app.absentmotion.ui.export.aiSmoothNote(estimate, s.level, clip = true), Modifier.padding(top = 4.dp),
                    color = if (cannot) Am.Warning else Am.Caption, fontSize = 12.sp, lineHeight = 17.sp)
            }
        },
        confirmButton = {
            TextButton({ tools.apply(AiAction.Smooth, confirmed = true) }, enabled = plan != null && !tooLong && !cannot) {
                Text(stringResource(if (download) R.string.ai_download_and_start else R.string.start), color = Am.Accent)
            }
        },
        dismissButton = { TextButton({ tools.smoothSetup = null }) { Text(stringResource(R.string.cancel), color = Am.Accent) } },
        containerColor = Am.Field)
    if (help) AlertDialog(onDismissRequest = { help = false },
        title = { Text(stringResource(R.string.export_interpolation), color = Am.OnDark) },
        text = { SmoothingHelp(Am.OnDark, Modifier.heightIn(max = 420.dp)) },
        confirmButton = { TextButton({ help = false }) { Text(stringResource(R.string.ok), color = Am.Accent) } },
        containerColor = Am.Field)
}

@Composable
private fun <T> Pills(options: List<T>, selected: T, enabled: Boolean, label: @Composable (T) -> String, locked: (T) -> Boolean = { false }, onPick: (T) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val needs = stringResource(R.string.tier_needs_latest)
    Row(Modifier.padding(top = 6.dp).fillMaxWidth().height(36.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(6.dp)).background(Am.PanelInset)) {
        for (option in options) {
            val on = option == selected
            Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp).clip(RoundedCornerShape(5.dp)).background(if (on) Am.FieldHi else Color.Transparent)
                .clickable(enabled = enabled) { if (locked(option)) android.widget.Toast.makeText(context, needs, android.widget.Toast.LENGTH_SHORT).show() else onPick(option) }, Alignment.Center) {
                Row(Modifier.alpha(if (locked(option)) 0.45f else 1f), verticalAlignment = Alignment.CenterVertically) {
                    if (locked(option)) Icon(app.absentmotion.ui.export.ExportIcons.Lock, null, Modifier.size(12.dp), tint = Am.OnDark)
                    Text(label(option), color = if (on) Am.Accent else Am.OnDark, fontSize = 12.sp, lineHeight = 13.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}
