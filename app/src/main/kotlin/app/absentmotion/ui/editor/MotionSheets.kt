package app.absentmotion.ui.editor

import app.absentmotion.ui.tutorial.tutorialTarget
import app.absentmotion.ui.tutorial.tutorialGuard
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
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
import app.absentmotion.ui.theme.Motion as ThemeMotion
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.presence
import app.absentmotion.ui.theme.pressScale
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.MultiSelect
import app.absentmotion.model.canAnimate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.produceState
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
internal fun TransitionButton(state: EditorState, modifier: Modifier, compact: Boolean = false) {
    var open by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val cut = if (state.playing || state.transitionButtonDismissed || state.selectedId != null || state.addOpen || MultiSelect.active) null else state.scene.cutAt(state.timeMs)
    if (cut != null) {
        var drag by remember { mutableFloatStateOf(0f) }
        val hide = stringResource(R.string.transition_hide)
        val press = remember { MutableInteractionSource() }
        Box(modifier.size(if (compact) 46.dp else 73.dp).tutorialGuard()
            .semantics { customActions = listOf(CustomAccessibilityAction(hide) { state.transitionButtonDismissed = true; true }) }
            .pointerInput(state) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f }, onDragCancel = { drag = 0f },
                    onDragEnd = { if (drag <= -24.dp.toPx()) state.transitionButtonDismissed = true; drag = 0f },
                ) { change, amount -> change.consume(); drag += amount }
            }
            .graphicsLayer { translationX = drag.coerceAtMost(0f) }
            .clickable(interactionSource = press, indication = null) { open = cut.first.id to cut.second.id }, contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(if (compact) 38.dp else 47.dp).pressScale(press).shadow(4.dp, CircleShape).background(Am.Panel, CircleShape).border(1.8.dp, Am.OnDark, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ChromeIcons.Transition, stringResource(R.string.transition_title), Modifier.size(if (compact) 18.dp else 22.dp), tint = Am.OnDark)
            }
        }
    }
    open?.let { (a, b) -> TransitionSheet(state, a, b) { open = null } }
}

@Composable
internal fun AnimateButton(state: EditorState, modifier: Modifier) {
    var open by remember { mutableStateOf<Long?>(null) }
    val shown = state.selected?.canAnimate == true && state.panel == EditorPanel.Tools && !state.addOpen && !state.volumeOpen && !MultiSelect.active
    val clear = animateDpAsState(if (state.previewPanelOpen) PreviewPanelWidth else 0.dp, ThemeMotion.enter(), label = "animate-clear")
    Presence(shown) {
        val press = remember { MutableInteractionSource() }
        Box(
            modifier.offset { IntOffset(-clear.value.roundToPx(), 0) }.padding(12.dp).presence(scaleFrom = 0.6f).size(48.dp).tutorialTarget("animate").tutorialGuard().pressScale(press)
                .shadow(4.dp, CircleShape).background(Am.Panel, CircleShape).border(1.8.dp, Am.Accent, CircleShape)
                .clickable(press, null) { state.selectedId?.let { state.playing = false; open = it } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(PanelIcons.Animate, stringResource(R.string.a11y_animate), Modifier.size(24.dp), tint = Am.Accent)
        }
    }
    open?.let { AnimationSheet(state, it) { open = null } }
}

private val PreviewPanelWidth = 55.dp

@Composable
internal fun TransitionSheet(state: EditorState, aId: Long, bId: Long, onClose: () -> Unit) {
    val record = state.scene.layer(bId)?.motion(MotionSlot.In)?.takeIf { it.transition && it.partner == aId }
    val current = TransitionKind.of(record?.id)
    var ms by remember { mutableIntStateOf(record?.ms ?: 500) }
    var note by remember { mutableStateOf<UiText?>(null) }
    val clock = rememberClock()
    val pictures = rememberMotionPictures()
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
            else Tile(stringResource(kind.label), kind == current, modifier, { apply(kind) }) { drawTransition(kind, clock.value, pictures) }
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
    val picture = rememberMotionPictures().firstOrNull()
    fun apply(motionId: String?, key: Any? = null) = state.edit(key) { it.withAnimation(id, slot, motionId, ms) }
    MotionSheet(stringResource(R.string.animation_title), onClose) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Choices(listOf(stringResource(R.string.animation_in), stringResource(R.string.animation_out), stringResource(R.string.animation_loop)), tab) { tab = it }
        }
        val ids = if (loop) Animations.Loops else Animations.InOut.map { it.id to it.label }
        TileGrid(listOf<Pair<String, Int>?>(null) + ids) { item, modifier ->
            if (item == null) Tile(stringResource(R.string.none), record == null, modifier, { apply(null) }, null)
            else Tile(stringResource(item.second), item.first == chosen, modifier, { apply(item.first) }) {
                if (loop) drawLoop(item.first, clock.value, picture) else drawAnimation(Animations.find(item.first)!!, slot == MotionSlot.In, clock.value, picture)
            }
        }
        DurationLine(ms, if (loop) LOOP_MIN_MS else MOTION_MIN_MS, if (loop) LOOP_MAX_MS else MOTION_MAX_MS,
            { ms = it; if (chosen != null) apply(chosen, "animation-duration") }, state::endGesture)
        val note = if (record?.transition == true) stringResource(R.string.animation_replaces_transition) else ""
        Text(note, Modifier.fillMaxWidth().height(40.dp), color = Am.OnDarkDim, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2)
    }
}

@Composable
internal fun MotionSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
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
internal fun <T> TileGrid(items: List<T>, tile: @Composable (T, Modifier) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().height(292.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(items.size) { tile(items[it], Modifier) }
    }
}

@Composable
internal fun Tile(label: String, chosen: Boolean, modifier: Modifier, onClick: () -> Unit, draw: (DrawScope.() -> Unit)?) {
    Column(
        modifier.height(94.dp).clip(TileShape)
            .then(if (chosen) Modifier.background(Am.Accent.copy(alpha = 0.16f)).border(2.dp, Am.Accent, TileShape) else Modifier)
            .clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(54.dp).clip(TileShape).background(Am.Field)) {
            if (draw == null) Text("—", Modifier.align(Alignment.Center), color = Am.OnDarkDim, fontSize = 18.sp)
            else Canvas(Modifier.fillMaxSize()) { draw() }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, Modifier.fillMaxWidth().height(28.dp), color = if (chosen) Am.Accent else Am.OnDark, lineHeight = 12.sp, maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(8.sp, 11.sp))
    }
}

@Composable
internal fun DurationLine(ms: Int, min: Int, max: Int, onChange: (Int) -> Unit, onDone: () -> Unit) {
    Line(stringResource(R.string.mediainfo_duration)) {
        ThinSlider(ms.toFloat(), { v -> ((v / 100f).roundToInt() * 100).coerceIn(min, max).takeIf { it != ms }?.let(onChange) }, Modifier.width(180.dp),
            valueRange = min.toFloat()..max.toFloat(), onValueChangeFinished = onDone, inactive = Am.Field)
        Text(String.format(Locale.US, "%.1f s", ms / 1000f), Modifier.width(48.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
internal fun rememberClock(): State<Float> = rememberInfiniteTransition(label = "motion")
    .animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "clock")

private class Pose(var dx: Float = 0f, var dy: Float = 0f, var scale: Float = 1f, var turn: Float = 0f, var alpha: Float = 1f,
                   var tint: Color = Color.Transparent, var smear: Float = 0f, var split: Float = 0f, var shake: Float = 0f)

@Composable
private fun rememberMotionPictures(): List<ImageBitmap> {
    val context = LocalContext.current
    val pictures by produceState(emptyList<ImageBitmap>(), context) {
        value = withContext(Dispatchers.IO) {
            listOf("colorwheels", "huesaturation").mapNotNull { name ->
                context.assets.open("effects/thumb/$name.webp").use { BitmapFactory.decodeStream(it) }?.let { source ->
                    val start = (source.width * 0.43f).roundToInt()
                    android.graphics.Bitmap.createBitmap(source, start, 0, source.width - start, source.height).asImageBitmap()
                }
            }
        }
    }
    return pictures
}

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
            "absent.blur", "absent.smear" -> pose.smear = max(pose.smear, amount)
            "absent.glitch", "absent.rgbsplit" -> pose.split = max(pose.split, amount)
            "absent.autoshake" -> pose.shake = max(pose.shake, amount)
        }
    }
    return pose
}

private fun DrawScope.drawPose(pose: Pose, color: Color, clip: Boolean, clock: Float, picture: ImageBitmap?) {
    val w = size.width
    val h = size.height
    val box = if (clip) size else Size(w * 0.72f, h * 0.84f)
    val at = Offset((w - box.width) / 2, (h - box.height) / 2)
    val corner = CornerRadius(if (clip) 3f else 6f)
    val jitter = pose.shake * w * 0.08f
    val alpha = pose.alpha.coerceIn(0f, 1f)
    fun DrawScope.photo(at: Offset, opacity: Float = alpha) {
        if (picture == null) drawRoundRect(color.copy(alpha = opacity), at, box, corner)
        else {
            val scale = max(box.width / picture.width, box.height / picture.height)
            val source = IntSize((box.width / scale).roundToInt().coerceIn(1, picture.width), (box.height / scale).roundToInt().coerceIn(1, picture.height))
            drawImage(picture, srcOffset = IntOffset((picture.width - source.width) / 2, (picture.height - source.height) / 2), srcSize = source,
                dstOffset = IntOffset(at.x.roundToInt(), at.y.roundToInt()), dstSize = IntSize(box.width.roundToInt(), box.height.roundToInt()),
                alpha = opacity, filterQuality = FilterQuality.Low)
        }
    }
    withTransform({
        translate(pose.dx + sin(clock * 191f) * jitter, pose.dy + cos(clock * 157f) * jitter)
        rotate(-pose.turn, center)
        scale(pose.scale, pose.scale, center)
    }) {
        val side = pose.split * w * 0.09f
        val trail = pose.smear * w * 0.07f
        if (trail > 0f) for (i in 1..3) {
            photo(at + Offset(i * trail, 0f), 0.15f * alpha)
            photo(at + Offset(-i * trail, 0f), 0.15f * alpha)
        }
        if (side > 0f) {
            val band = box.height / 4
            for (i in 0 until 4) {
                val shift = (if (i % 2 == 0) 1 else -1) * side * (0.5f + 0.5f * sin(clock * 97f + i))
                clipRect(at.x, at.y + i * band, at.x + box.width, at.y + (i + 1) * band) { photo(at + Offset(shift, 0f)) }
            }
        } else photo(at)
        if (pose.tint.alpha > 0f && !clip) drawRoundRect(pose.tint.copy(alpha = pose.tint.alpha * alpha), at, box, corner)
    }
    if (clip && pose.tint.alpha > 0f) drawRect(pose.tint)
}

private fun DrawScope.drawTransition(kind: TransitionKind, clock: Float, pictures: List<ImageBitmap>) {
    val (outSide, inSide) = kind.sides(bOnTop = true, bothFade = false)
    val u = ((clock - 0.15f) / 0.7f).coerceIn(0f, 1f)
    val w = size.width
    val h = size.height
    if (kind.overlap) {
        if (u < 1f) drawPose(outSide.pose(u, false, w, h), Am.Accent, true, clock, pictures.firstOrNull())
        if (u > 0f) drawPose(inSide.pose(u, true, w, h), Am.Cool, true, clock, pictures.getOrNull(1))
    } else if (u < 0.5f) drawPose(outSide.pose(2 * u, false, w, h), Am.Accent, true, clock, pictures.firstOrNull())
    else drawPose(inSide.pose(2 * u - 1, true, w, h), Am.Cool, true, clock, pictures.getOrNull(1))
}

private fun DrawScope.drawAnimation(motion: Motion, inSide: Boolean, clock: Float, picture: ImageBitmap?) {
    val u = if (inSide) clock / 0.6f else (clock - 0.25f) / 0.6f
    drawPose(motion.pose(u, inSide, size.width, size.height), Am.Accent, false, clock, picture)
}

private fun DrawScope.drawLoop(id: String, clock: Float, picture: ImageBitmap?) {
    val wave = sin(2 * PI.toFloat() * clock)
    val pose = when (id) {
        "pulse" -> Pose(scale = 1 + 0.08f * wave)
        "wobble" -> Pose(turn = 6f * wave)
        "float" -> Pose(dy = 0.02f * size.height * wave)
        else -> Pose(turn = -360f * clock)
    }
    drawPose(pose, Am.Accent, false, clock, picture)
}
