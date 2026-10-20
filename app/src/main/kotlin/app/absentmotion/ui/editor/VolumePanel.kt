package app.absentmotion.ui.editor

import app.absentmotion.platform.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.audio.GainRef
import app.absentmotion.audio.MAX_GAIN
import app.absentmotion.audio.audioEffects
import app.absentmotion.audio.gain
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.editor.KeypadField
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.editor.setGain
import app.absentmotion.editor.toggleKeyframe
import app.absentmotion.ui.editor.panels.EasingPanel
import app.absentmotion.ui.editor.panels.PanelIcon
import app.absentmotion.ui.editor.panels.dimmed
import app.absentmotion.ui.editor.panels.px
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.ui.theme.Am
import kotlin.math.roundToInt

private val VolumeGreen get() = Am.AccentLit
private val TrackOff get() = Am.Well
private val ValueBox get() = Am.PanelInset
private val SliderPad = 45.px
private val ThumbRadius = 5.9.dp
private val BarShape = RoundedCornerShape(4.dp)

@Composable
internal fun VolumePanel(state: EditorState, modifier: Modifier = Modifier) {
    val layer = state.selected ?: return
    DisposableEffect(Unit) {
        onDispose { state.volumeOpen = false; state.easingOpen = false; state.keyTarget = null }
    }
    var effects by remember { mutableStateOf(false) }
    if (effects) {
        AudioEffectsPanel(state, modifier) { effects = false }
        return
    }
    SideEffect { state.keyTarget = KeyTarget(GainRef, "1.000000") }
    BackHandler { if (state.easingOpen) state.easingOpen = false else state.volumeOpen = false }
    if (state.easingOpen) {
        EasingPanel(state, modifier)
        return
    }
    val percent = (layer.gain * 100).roundToInt()
    val volumeLabel = stringResource(R.string.volume_volume)
    Column(modifier.background(Am.Panel).padding(start = 62.px, top = 42.px, end = 62.px)) {
        Row(Modifier.fillMaxWidth().height(107.px).clip(BarShape).background(Am.Button)) {
            val count = layer.audioEffects.size
            Box(Modifier.weight(1f).fillMaxHeight().clickable { state.easingOpen = false; effects = true }.padding(start = 14.dp), Alignment.CenterStart) {
                Text(if (count == 0) stringResource(R.string.volume_audio_effects) else stringResource(R.string.volume_audio_effects_count, count.toString()), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
            }
            Box(Modifier.width(183.px).fillMaxHeight().clickable { state.volumeOpen = false }, Alignment.Center) {
                Icon(AudioIcons.Speaker, volumeLabel, Modifier.size(32.dp), tint = VolumeGreen)
                Canvas(Modifier.align(Alignment.BottomStart).padding(start = 14.px, bottom = 14.px).size(4.dp)) {
                    drawPath(Path().apply { moveTo(0f, 0f); lineTo(0f, size.height); lineTo(size.width, size.height); close() }, Am.OnDark.copy(alpha = 0.3f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(365.px).clip(BarShape).background(Am.TimelineBg)) {
            Column(Modifier.width(140.px).fillMaxHeight().padding(top = 11.px)) {
                Box(Modifier.fillMaxWidth().height(118.px).clickable { state.toggleKeyframe() }, Alignment.Center) {
                    PanelIcon(if (layer.onKeyframe(GainRef)) PanelIcons.KeyframeRemove else PanelIcons.Keyframe, Am.OnDark)
                }
                Box(Modifier.fillMaxWidth().height(118.px).clickable { if (layer.easing(GainRef) != null) state.easingOpen = true else state.easingHint = true }, Alignment.Center) {
                    PanelIcon(PanelIcons.Easing, Am.OnDark)
                }
            }
            Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(65.px))
                Icon(AudioIcons.Level, null, Modifier.size(15.dp, 12.dp), tint = Am.OnDark.copy(alpha = 0.5f))
                Spacer(Modifier.width(23.px))
                VolumeSlider(percent, state, Modifier.width(440.px).height(113.px))
                Spacer(Modifier.width(23.px))
                Box(Modifier.width(158.px).height(101.px).clip(RoundedCornerShape(4.dp)).background(ValueBox).clickable {
                    state.keypad = KeypadRequest(listOf(KeypadField(volumeLabel, layer.gain * 100)), 0) { values ->
                        state.setGain(values[0].coerceIn(0f, MAX_GAIN * 100) / 100f)
                    }
                }, Alignment.Center) {
                    Text("$percent", color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun VolumeSlider(percent: Int, state: EditorState, modifier: Modifier) {
    Canvas(modifier.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown()
            fun update(x: Float) {
                val pad = SliderPad.toPx()
                val fraction = ((x - pad) / (size.width - 2 * pad).coerceAtLeast(1f)).coerceIn(0f, 1f)
                state.setGain((fraction * MAX_GAIN * 100).roundToInt() / 100f, "gain")
            }
            down.consume()
            update(down.position.x)
            try {
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.pressed) { change.consume(); update(change.position.x) }
                } while (event.changes.any { it.pressed })
            } finally { state.endGesture() }
        }
    }) {
        val pad = SliderPad.toPx()
        val cy = size.height / 2
        val x = pad + (size.width - 2 * pad) * (percent / (MAX_GAIN * 100)).coerceIn(0f, 1f)
        val thickness = 6.px.toPx()
        drawLine(TrackOff, Offset(pad, cy), Offset(size.width - pad, cy), thickness, StrokeCap.Round)
        drawLine(VolumeGreen, Offset(pad, cy), Offset(x, cy), thickness, StrokeCap.Round)
        drawCircle(VolumeGreen, ThumbRadius.toPx(), Offset(x, cy))
    }
}
