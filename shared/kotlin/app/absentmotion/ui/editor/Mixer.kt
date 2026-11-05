package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.audio.GainRef
import app.absentmotion.audio.LiveMix
import app.absentmotion.audio.MAX_GAIN
import app.absentmotion.audio.gain
import app.absentmotion.audio.mutes
import app.absentmotion.audio.soundUri
import app.absentmotion.editor.EditorState
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.formatFloats
import app.absentmotion.model.isKeyframed
import app.absentmotion.platform.Hint
import app.absentmotion.ui.editor.panels.sliderSemantics
import app.absentmotion.ui.home.handCursor
import app.absentmotion.ui.home.hoverFill
import app.absentmotion.ui.theme.Am
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt

private val MixRed = Color(0xFFFF3B30)
private val MixYellow = Color(0xFFFFCC00)
private val MixGreen = Color(0xFF34C759)
private val Numbers = TextStyle(fontFeatureSettings = "tnum")
private val StripWidth = 72.dp
private val ThumbWidth = 26.dp
private val ThumbHeight = 12.dp

internal fun mixerLayers(scene: Scene): List<Layer> = scene.layers.asReversed().filter { layer ->
    val uri = layer.soundUri ?: return@filter false
    layer.kind == "audio" || scene.media(uri)?.node?.get("audio") != "none"
}

internal object MixerFader {
    fun fraction(gain: Float): Float = (gain / MAX_GAIN).coerceIn(0f, 1f)

    fun gain(fraction: Float): Float = (fraction.coerceIn(0f, 1f) * MAX_GAIN * 100).roundToInt() / 100f

    fun dragged(start: Float, dy: Float, travel: Float): Float = gain(fraction(start) - dy / travel.coerceAtLeast(1f))
}

internal class MeterBallistics {
    var shown by mutableFloatStateOf(0f)
        private set
    var peak by mutableFloatStateOf(0f)
        private set
    var clip by mutableFloatStateOf(0f)
        private set
    private var age = 0f

    fun reset() { shown = 0f; peak = 0f; clip = 0f; age = 0f }

    fun step(level: Float, dt: Float) {
        shown = maxOf(position(level), shown * exp(-dt / 0.11f))
        if (shown >= peak) { peak = shown; age = 0f }
        else { age += dt; if (age > 0.6f) peak = maxOf(shown, peak - 1.5f * dt) }
        clip = if (level >= 1f) 0.5f else maxOf(0f, clip - dt)
    }

    companion object {
        fun position(level: Float): Float = if (level > 0f) (1f + 20f * log10(level) / 48f).coerceIn(0f, 1f) else 0f

        fun decibels(position: Float): Int? = if (position <= 0f) null else ((position - 1f) * 48f).roundToInt()
    }
}

private fun EditorState.setGainOf(id: Long, value: Float, key: Any? = null) =
    edit(key) { scene -> scene.update(id, sampleMs) { it.withValue(GainRef, formatFloats(floatArrayOf(value.coerceIn(0f, MAX_GAIN)))) } }

@Composable
internal fun Mixer(state: EditorState, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val scene = state.scene
    val layers = remember(scene) { mixerLayers(scene) }
    val mix = state.engine.audio?.mix
    val solo = remember { mutableStateListOf<Long>() }
    val unmuted = remember { HashMap<Long, Float>() }
    LaunchedEffect(layers) { if (solo.retainAll { id -> layers.any { it.id == id } }) mix?.solo = solo.toLongArray() }
    DisposableEffect(mix) { onDispose { mix?.solo = LongArray(0) } }
    Column(modifier.background(Am.Panel)) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${scene.width} × ${scene.height} · ${scene.fps} fps · ${TimelineMath.timecode(scene.lastFrame + 1, scene.fps)}", Modifier.weight(1f),
                color = Am.OnDarkDim, fontSize = 12.sp, style = Numbers, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DeskIcon(ChromeIcons.Gear, stringResource(R.string.topbar_project_settings), size = 32.dp, action = onSettings)
        }
        if (layers.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Icon(AudioIcons.Speaker, null, Modifier.size(32.dp), tint = Am.OnDarkDim)
                Text(stringResource(R.string.mixer_empty), Modifier.padding(top = 8.dp), color = Am.OnDark, fontSize = 14.sp, textAlign = TextAlign.Center)
                Text(stringResource(R.string.mixer_empty_hint), Modifier.padding(top = 4.dp), color = Am.OnDarkDim, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        } else Row(Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
            val scroll = rememberScrollState()
            val hint = Am.OnDarkDim.copy(alpha = 0.5f)
            val notch = with(LocalDensity.current) { 40.dp.toPx() }
            Row(Modifier.weight(1f).fillMaxHeight().drawWithContent {
                drawContent()
                val max = scroll.maxValue
                if (max in 1 until Int.MAX_VALUE) {
                    val thumb = size.width * size.width / (size.width + max)
                    drawRoundRect(hint, Offset((size.width - thumb) * scroll.value / max, size.height + 2.dp.toPx()), Size(thumb, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
                }
            }.then(app.absentmotion.platform.wheel { delta, _, _ -> scroll.dispatchRawDelta((delta.x + delta.y) * notch) }).horizontalScroll(scroll), Arrangement.spacedBy(6.dp)) {
                layers.forEach { layer ->
                    key(layer.id) {
                        LayerStrip(state, layer, mix, layer.id in solo, unmuted) {
                            if (!solo.remove(layer.id)) solo.add(layer.id)
                            mix?.solo = solo.toLongArray()
                        }
                    }
                }
            }
            Box(Modifier.padding(horizontal = 6.dp).width(1.dp).fillMaxHeight().background(Am.Line))
            MasterStrip(state, mix)
        }
    }
}

@Composable
private fun StripFrame(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, value: String, modifier: Modifier, body: @Composable () -> Unit, buttons: @Composable () -> Unit) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Am.PanelInset).padding(top = 8.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(16.dp), tint = Am.OnDarkDim)
        Hint(name) { Text(name, Modifier.padding(start = 4.dp, top = 2.dp, end = 4.dp), color = Am.OnDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
        Row(Modifier.weight(1f).padding(vertical = 8.dp), Arrangement.spacedBy(6.dp)) { body() }
        Text(value, color = Am.OnDark, fontSize = 12.sp, style = Numbers, maxLines = 1)
        Row(Modifier.padding(top = 6.dp).height(28.dp), Arrangement.spacedBy(4.dp)) { buttons() }
    }
}

@Composable
private fun LayerStrip(state: EditorState, layer: Layer, mix: LiveMix?, soloed: Boolean, unmuted: HashMap<Long, Float>, onSolo: () -> Unit) {
    val id = layer.id
    val element = layer.element(GainRef)
    val keyed = element?.isKeyframed == true
    val gain = if (keyed) layer.at(state.sampleMs, state.scene.fps).gain else layer.gain
    val muted = element?.mutes == true
    val drag = remember(id) { Any() }
    val volume = stringResource(R.string.volume_volume)
    StripFrame(deskLayerIcon(layer), layer.label, "${(gain * 100).roundToInt()}%", Modifier.width(StripWidth).alpha(if (layer.hidden) 0.5f else 1f), body = {
        Fader(gain, "$volume, ${layer.label}", { value, dragging -> state.setGainOf(id, value, if (dragging) drag else null) }, { state.endGesture() }, Modifier.width(28.dp).fillMaxHeight())
        MeterSlot(state.playing, mix?.let { m -> { m.level(id) } })
    }) {
        StripToggle(stringResource(R.string.mixer_mute_short), stringResource(R.string.mixer_mute), muted, Am.Accent, enabled = !keyed) {
            if (muted) state.setGainOf(id, unmuted.remove(id) ?: 1f)
            else { unmuted[id] = gain; state.setGainOf(id, 0f) }
        }
        StripToggle(stringResource(R.string.mixer_solo_short), stringResource(R.string.mixer_solo), soloed, Am.Gold, onClick = onSolo)
    }
}

@Composable
private fun MasterStrip(state: EditorState, mix: LiveMix?) {
    val meter = remember { MeterBallistics() }
    val db by remember { derivedStateOf { MeterBallistics.decibels(meter.peak) } }
    StripFrame(AudioIcons.Speaker, stringResource(R.string.mixer_master), if (mix == null) "" else db?.let { "$it dB" } ?: "−∞ dB", Modifier.width(64.dp), body = {
        if (mix != null) Meter(meter, state.playing, { mix.master }, Modifier.width(12.dp).fillMaxHeight())
    }) {}
}

@Composable
private fun MeterSlot(playing: Boolean, read: (() -> Float)?) {
    if (read == null) return
    val meter = remember { MeterBallistics() }
    Meter(meter, playing, read, Modifier.width(8.dp).fillMaxHeight())
}

@Composable
private fun Meter(meter: MeterBallistics, playing: Boolean, read: () -> Float, modifier: Modifier) {
    val latest by rememberUpdatedState(read)
    LaunchedEffect(playing) {
        meter.reset()
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            meter.step(latest(), ((now - last) / 1e9f).coerceIn(0f, 0.1f))
            last = now
        }
    }
    val well = Am.Deepest
    Spacer(modifier.drawWithCache {
        val ramp = Brush.verticalGradient(0f to MixRed, 0.3f to MixYellow, 1f to MixGreen, startY = 0f, endY = size.height)
        val corner = CornerRadius(2.dp.toPx())
        val tick = 2.dp.toPx()
        val cap = 3.dp.toPx()
        onDrawBehind {
            val h = size.height
            drawRoundRect(well, cornerRadius = corner)
            val shown = meter.shown
            if (shown > 0f) drawRect(ramp, Offset(0f, h * (1f - shown)), Size(size.width, h * shown))
            val peak = meter.peak
            if (peak > 0f) drawRect(Color.White.copy(alpha = 0.7f), Offset(0f, (h * (1f - peak)).coerceIn(0f, h - tick)), Size(size.width, tick))
            if (meter.clip > 0f) drawRect(MixRed, Offset.Zero, Size(size.width, cap))
        }
    })
}

@Composable
private fun Fader(gain: Float, label: String, onSet: (Float, Boolean) -> Unit, onEnd: () -> Unit, modifier: Modifier) {
    val now by rememberUpdatedState(gain)
    val set by rememberUpdatedState(onSet)
    val end by rememberUpdatedState(onEnd)
    val track = Am.Deepest
    val fill = Am.Accent
    val mark = Am.OnDarkDim
    val thumb = Am.OnDark
    Canvas(modifier.semantics { contentDescription = label }.sliderSemantics(gain, 0f..MAX_GAIN) { set(MixerFader.gain(MixerFader.fraction(it)), false) }
        .handCursor()
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { set(1f, false) }) }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = now
                val travel = size.height - ThumbHeight.toPx()
                val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                try {
                    set(MixerFader.dragged(start, drag.position.y - down.position.y, travel), true)
                    verticalDrag(drag.id) { change ->
                        change.consume()
                        set(MixerFader.dragged(start, change.position.y - down.position.y, travel), true)
                    }
                } finally {
                    end()
                }
            }
        }) {
        val pad = ThumbHeight.toPx() / 2
        val travel = size.height - 2 * pad
        val cx = size.width / 2
        val w = 4.dp.toPx()
        val y = pad + (1f - MixerFader.fraction(gain)) * travel
        drawRoundRect(track, Offset(cx - w / 2, pad), Size(w, travel), CornerRadius(w / 2))
        drawRoundRect(fill, Offset(cx - w / 2, y), Size(w, size.height - pad - y), CornerRadius(w / 2))
        val unity = pad + (1f - MixerFader.fraction(1f)) * travel
        drawRect(mark, Offset(cx - 9.dp.toPx(), unity - 0.5.dp.toPx()), Size(18.dp.toPx(), 1.dp.toPx()))
        val tw = ThumbWidth.toPx()
        drawRoundRect(thumb, Offset(cx - tw / 2, y - pad), Size(tw, ThumbHeight.toPx()), CornerRadius(3.dp.toPx()))
        drawRect(track, Offset(cx - tw / 2 + 4.dp.toPx(), y - 0.5.dp.toPx()), Size(tw - 8.dp.toPx(), 1.dp.toPx()))
    }
}

@Composable
private fun StripToggle(letter: String, hint: String, on: Boolean, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Hint(hint) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(if (on) color else Am.Button).alpha(if (enabled) 1f else DimAlpha)
            .then(if (enabled) Modifier.hoverFill(6.dp, Color.White.copy(alpha = 0.08f)).handCursor() else Modifier)
            .toggleable(on, enabled = enabled, role = Role.Switch) { onClick() }.semantics { contentDescription = hint }, Alignment.Center) {
            Text(letter, color = if (on) Am.OnAccent else Am.OnDark, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
