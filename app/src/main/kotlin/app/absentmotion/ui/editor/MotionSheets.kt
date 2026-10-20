package app.absentmotion.ui.editor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.absentmotion.editor.EditorState
import app.absentmotion.model.Animations
import app.absentmotion.model.LOOP_MAX_MS
import app.absentmotion.model.LOOP_MIN_MS
import app.absentmotion.model.MOTION_MAX_MS
import app.absentmotion.model.MOTION_MIN_MS
import app.absentmotion.model.Motion
import app.absentmotion.model.MotionSlot
import app.absentmotion.model.TransitionKind
import app.absentmotion.model.cutAt
import app.absentmotion.model.motion
import app.absentmotion.model.parseColor
import app.absentmotion.model.withAnimation
import app.absentmotion.model.withTransition
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.ui.editor.panels.ThinSlider
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.pressScale
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.text

private val TileShape = RoundedCornerShape(6.dp)

@Composable
internal fun TransitionButton(state: EditorState, modifier: Modifier) {
    var open by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val cut = if (state.playing) null else state.scene.cutAt(state.timeMs)
    if (cut != null) {
        val press = remember { MutableInteractionSource() }
        Box(modifier.size(73.dp).clickable(interactionSource = press, indication = null) { open = cut.first.id to cut.second.id }, contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(47.dp).pressScale(press).shadow(4.dp, CircleShape).background(Am.Panel, CircleShape).border(1.8.dp, Am.OnDark, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ChromeIcons.Transition, stringResource(R.string.transition_title), Modifier.size(22.dp), tint = Am.OnDark)
            }
        }
    }
    open?.let { (a, b) -> TransitionSheet(state, a, b) { open = null } }
}

@Composable
internal fun TransitionSheet(state: EditorState, aId: Long, bId: Long, onClose: () -> Unit) {
    val record = state.scene.layer(bId)?.motion(MotionSlot.In)?.takeIf { it.transition && it.partner == aId }
    val current = TransitionKind.of(record?.id)
    var ms by remember { mutableIntStateOf(record?.ms ?: 500) }
    var note by remember { mutableStateOf<UiText?>(null) }
    val clock = rememberClock()
    fun apply(kind: TransitionKind?, key: Any? = null) {
        var told: UiText? = null
        state.edit(key) { s -> s.withTransition(aId, bId, kind, ms).also { told = it.note }.scene }
        note = told
    }
    val clip = stringResource(R.string.transition_clip)
    val names = listOf(aId, bId).map { id -> state.scene.layer(id)?.label?.ifEmpty { null } ?: clip }
    MotionSheet(stringResource(R.string.transition_title), onClose) {
        Text("${names[0]}  →  ${names[1]}", Modifier.fillMaxWidth().height(24.dp), color = Am.OnDarkDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TileGrid(listOf<TransitionKind?>(null) + TransitionKind.entries) { kind, modifier ->
            if (kind == null) Tile(stringResource(R.string.none), current == null, modifier, { apply(null) }, null)
            else Tile(stringResource(kind.label), kind == current, modifier, { apply(kind) }) { drawTransition(kind, clock.value) }
        }
        DurationLine(ms, MOTION_MIN_MS, MOTION_MAX_MS, { ms = it; if (current != null) apply(current, "transition-duration") }, state::endGesture)
        Text(note?.text().orEmpty(), Modifier.fillMaxWidth().height(40.dp), color = Warning, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2)
    }
}

@Composable
internal fun AnimationSheet(state: EditorState, id: Long, onClose: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val slot = MotionSlot.entries[tab]
    val layer = state.scene.layer(id)
    val record = layer?.motion(slot)
    val chosen = record?.takeUnless { it.transition }?.id
    val loop = slot == MotionSlot.Loop
    var ms by remember(tab) { mutableIntStateOf(record?.takeUnless { it.transition }?.ms ?: if (loop) 1000 else 500) }
    val clock = rememberClock()
    fun apply(motionId: String?, key: Any? = null) = state.edit(key) { it.withAnimation(id, slot, motionId, ms) }
    MotionSheet(stringResource(R.string.animation_title), onClose) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Choices(listOf(stringResource(R.string.animation_in), stringResource(R.string.animation_out), stringResource(R.string.animation_loop)), tab) { tab = it }
        }
        val ids = if (loop) Animations.Loops else Animations.InOut.map { it.id to it.label }
        TileGrid(listOf<Pair<String, Int>?>(null) + ids) { item, modifier ->
            if (item == null) Tile(stringResource(R.string.none), record == null, modifier, { apply(null) }, null)
            else Tile(stringResource(item.second), item.first == chosen, modifier, { apply(item.first) }) {
                if (loop) drawLoop(item.first, clock.value) else drawAnimation(Animations.find(item.first)!!, slot == MotionSlot.In, clock.value)
            }
        }
        DurationLine(ms, if (loop) LOOP_MIN_MS else MOTION_MIN_MS, if (loop) LOOP_MAX_MS else MOTION_MAX_MS,
            { ms = it; if (chosen != null) apply(chosen, "animation-duration") }, state::endGesture)
        val note = if (record?.transition == true) stringResource(R.string.animation_replaces_transition) else ""
        Text(note, Modifier.fillMaxWidth().height(40.dp), color = Am.OnDarkDim, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2)
    }
}

@Composable
private fun MotionSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onClose, sheetDialogProperties()) {
        PhoneScreen {
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onClose).systemBarsPadding(), Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth().clip(SheetShape).background(Am.Panel).clickable(interactionSource = null, indication = null) {}.padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), color = Am.OnDark, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        BarButton(ChromeIcons.Close, stringResource(R.string.action_close), Modifier.size(40.dp), onClick = onClose)
                    }
                    content()
                }
            }
        }
    }
}

@Composable
private fun <T> TileGrid(items: List<T>, tile: @Composable (T, Modifier) -> Unit) {
    Column(Modifier.fillMaxWidth().height(264.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile(it, Modifier.weight(1f)) }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tile(label: String, chosen: Boolean, modifier: Modifier, onClick: () -> Unit, draw: (DrawScope.() -> Unit)?) {
    Column(modifier.height(84.dp).clip(TileShape).clickable(onClick = onClick).padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(56.dp).clip(TileShape).background(Am.Field).then(if (chosen) Modifier.border(2.dp, Am.Accent, TileShape) else Modifier)) {
            if (draw == null) Text("—", Modifier.align(Alignment.Center), color = Am.OnDarkDim, fontSize = 18.sp)
            else Canvas(Modifier.fillMaxSize()) { draw() }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (chosen) Am.Accent else Am.OnDark, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DurationLine(ms: Int, min: Int, max: Int, onChange: (Int) -> Unit, onDone: () -> Unit) {
    Line(stringResource(R.string.mediainfo_duration)) {
        ThinSlider(ms.toFloat(), { v -> ((v / 100f).roundToInt() * 100).coerceIn(min, max).takeIf { it != ms }?.let(onChange) }, Modifier.width(180.dp),
            valueRange = min.toFloat()..max.toFloat(), onValueChangeFinished = onDone, inactive = Am.Field)
        Text(String.format(Locale.US, "%.1f s", ms / 1000f), Modifier.width(48.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun rememberClock(): State<Float> = rememberInfiniteTransition(label = "motion")
    .animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "clock")

private class Pose(var dx: Float = 0f, var dy: Float = 0f, var scale: Float = 1f, var turn: Float = 0f, var alpha: Float = 1f,
                   var tint: Color = Color.Transparent, var smear: Float = 0f, var split: Float = 0f, var shake: Float = 0f)

private fun Motion.pose(u: Float, inSide: Boolean, w: Float, h: Float): Pose {
    val eased = curve(inSide).at(u.coerceIn(0f, 1f))
    val f = if (inSide) 1 - eased else eased
    val sign = if (inSide) -1f else 1f
    val pose = Pose()
    for (look in looks) when (look.prop) {
        "opacity" -> pose.alpha *= 1 + (look.away(floatArrayOf(1f), w, h, sign)[0] - 1) * f
        "location" -> look.away(floatArrayOf(0f, 0f, 0f), w, h, sign).let { pose.dx += it[0] * f; pose.dy += it[1] * f }
        "scale" -> pose.scale *= 1 + (look.away(floatArrayOf(1f, 1f), w, h, sign)[0] - 1) * f
        "rotation" -> pose.turn += look.away(floatArrayOf(0f), w, h, sign)[0] * f
    }
    val amount = f.coerceIn(0f, 1f)
    for (effect in fx) {
        when (effect.id) {
            "absent.solid" -> pose.tint = Color(parseColor(effect.fixed["color"]) ?: 0).copy(alpha = amount)
            "absent.exposure" -> pose.tint = Color.White.copy(alpha = 0.9f * amount)
            "absent.blur", "absent.motionblur" -> pose.smear = max(pose.smear, amount)
            "absent.glitch", "absent.rgbsplit" -> pose.split = max(pose.split, amount)
            "absent.autoshake" -> pose.shake = max(pose.shake, amount)
        }
    }
    return pose
}

private fun DrawScope.drawPose(pose: Pose, color: Color, full: Boolean, clock: Float) {
    val w = size.width
    val h = size.height
    val box = if (full) Size(w, h) else Size(w * 0.4f, h * 0.5f)
    val at = Offset((w - box.width) / 2, (h - box.height) / 2)
    val corner = CornerRadius(if (full) 0f else 6f)
    val jitter = pose.shake * w * 0.06f
    val alpha = pose.alpha.coerceIn(0f, 1f)
    withTransform({
        translate(pose.dx + sin(clock * 191f) * jitter, pose.dy + cos(clock * 157f) * jitter)
        rotate(-pose.turn, center)
        scale(pose.scale, pose.scale, center)
    }) {
        val side = pose.split * w * 0.06f
        if (side > 0f) {
            drawRoundRect(Color.Red.copy(alpha = 0.7f * alpha), at + Offset(-side, 0f), box, corner)
            drawRoundRect(Color.Cyan.copy(alpha = 0.7f * alpha), at + Offset(side, 0f), box, corner)
        }
        val trail = pose.smear * w * 0.05f
        if (trail > 0f) for (i in 1..3) {
            drawRoundRect(color.copy(alpha = 0.3f * alpha), at + Offset(i * trail, 0f), box, corner)
            drawRoundRect(color.copy(alpha = 0.3f * alpha), at + Offset(-i * trail, 0f), box, corner)
        }
        drawRoundRect(color.copy(alpha = alpha), at, box, corner)
        if (pose.tint.alpha > 0f) drawRoundRect(pose.tint.copy(alpha = pose.tint.alpha * alpha), at, box, corner)
    }
}

private fun DrawScope.drawTransition(kind: TransitionKind, clock: Float) {
    val (outSide, inSide) = kind.sides(bOnTop = true, bothFade = false)
    val u = ((clock - 0.15f) / 0.7f).coerceIn(0f, 1f)
    val w = size.width
    val h = size.height
    if (kind.overlap) {
        if (u < 1f) drawPose(outSide.pose(u, false, w, h), Am.Accent, true, clock)
        if (u > 0f) drawPose(inSide.pose(u, true, w, h), Am.Cool, true, clock)
    } else if (u < 0.5f) drawPose(outSide.pose(2 * u, false, w, h), Am.Accent, true, clock)
    else drawPose(inSide.pose(2 * u - 1, true, w, h), Am.Cool, true, clock)
}

private fun DrawScope.drawAnimation(motion: Motion, inSide: Boolean, clock: Float) {
    val u = if (inSide) clock / 0.6f else (clock - 0.25f) / 0.6f
    drawPose(motion.pose(u, inSide, size.width, size.height), Am.Accent, false, clock)
}

private fun DrawScope.drawLoop(id: String, clock: Float) {
    val wave = sin(2 * PI.toFloat() * clock)
    val pose = when (id) {
        "pulse" -> Pose(scale = 1 + 0.15f * wave)
        "wobble" -> Pose(turn = 12f * wave)
        "float" -> Pose(dy = 0.12f * size.height * wave)
        else -> Pose(turn = -360f * clock)
    }
    drawPose(pose, Am.Accent, false, clock)
}
