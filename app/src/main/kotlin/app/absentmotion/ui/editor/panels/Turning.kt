package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.editor.KeypadField
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.formatFloats
import app.absentmotion.model.valueAt
import app.absentmotion.model.withEffect
import app.absentmotion.model.withEffectProperty
import app.absentmotion.ui.editor.ROTATE_TAB
import app.absentmotion.ui.editor.TILT_TAB
import app.absentmotion.ui.editor.TransformTargets
import app.absentmotion.ui.theme.Am
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal data class Turn(val effect: Int = -1, private val axes: List<EffectParam> = emptyList()) {
    fun values(layer: Layer): FloatArray {
        if (effect < 0) return floatArrayOf(layer.tilt[0], layer.tilt[1], layer.rotation)
        val node = layer.effects.getOrNull(effect)
        return FloatArray(3) { (node?.effectProperty(axes[it].id)?.valueAt(layer.t) ?: axes[it].default).toFloatOrNull() ?: 0f }
    }

    fun withValues(layer: Layer, next: FloatArray): Layer {
        val old = values(layer)
        val changed = BooleanArray(3) { abs(next[it] - old[it]) >= 1e-4f }
        if (effect < 0) {
            val tilted = if (changed[0] || changed[1]) layer.withTilt(next[0], next[1]) else layer
            return if (changed[2]) tilted.withRotation(next[2]) else tilted
        }
        if (effect !in layer.effects.indices) return layer
        return layer.withEffect(effect) { node ->
            axes.indices.filter { changed[it] }.fold(node) { n, i ->
                n.withEffectProperty(axes[i].id, axes[i].type, formatFloats(floatArrayOf(next[i].coerceIn(axes[i].min, axes[i].max))), layer.t, layer.snap)
            }
        }
    }

    fun turned(layer: Layer, drag: Offset, twist: Float): Layer {
        val now = values(layer)
        val step = turnStep(drag.x, drag.y, twist, now[2])
        return withValues(layer, FloatArray(3) { now[it] + step[it] })
    }

    fun reset(layer: Layer): Layer = withValues(layer, FloatArray(3) { if (effect < 0) 0f else axes[it].default.toFloatOrNull() ?: 0f })

    fun target(axis: Int): KeyTarget =
        if (effect >= 0) KeyTarget(PropRef.effect(effect, axes[axis].id), axes[axis].default, axes[axis].type)
        else TransformTargets[if (axis < 2) TILT_TAB else ROTATE_TAB]

    fun linked(a: Int, b: Int): Boolean = a == b || (effect < 0 && a < 2 && b < 2)
}

internal fun turnFor(layer: Layer, catalog: EffectCatalog, tab: Int): Turn? {
    if (tab != ROTATE_TAB && tab != TILT_TAB || layer.kind == "camera") return null
    val turn = layer.effects.withIndex().firstNotNullOfOrNull { (i, node) ->
        val definition = catalog.find(node["id"] ?: "")?.takeIf { it.available && node["hidden"] != "true" } ?: return@firstNotNullOfOrNull null
        val axes = TURN_AXES.map { id -> definition.params.firstOrNull { it.id == id } ?: return@firstNotNullOfOrNull null }
        Turn(i, axes)
    } ?: Turn()
    val solid = turn.effect >= 0 || layer.kind == "model" || layer.tilt.any { it != 0f } || layer.keyframes(PropRef.Tilt).isNotEmpty()
    return turn.takeIf { tab == TILT_TAB || solid }
}

private val TURN_AXES = listOf("rotX", "rotY", "rotZ")

internal const val TURN_PER_DP = 0.5f

internal fun turnStep(dx: Float, dy: Float, twist: Float, z: Float): FloatArray {
    val a = Math.toRadians(z.toDouble())
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    return floatArrayOf(-(dx * s + dy * c) * TURN_PER_DP, (dx * c - dy * s) * TURN_PER_DP, -twist)
}

@Composable
internal fun TurnHook(state: EditorState, turn: Turn) {
    DisposableEffect(state, turn) {
        val mine: (Offset, Float) -> Unit = { drag, twist -> state.editSelected("turn") { turn.turned(it, drag, twist) } }
        state.turner = mine
        onDispose { if (state.turner === mine) state.turner = null }
    }
}

@Composable
internal fun TurnTab(state: EditorState, layer: Layer, turn: Turn) {
    var axis by remember(layer.id) { mutableIntStateOf(0) }
    SideEffect { state.keyTarget = turn.target(axis) }
    TurnHook(state, turn)
    val values = turn.values(layer)
    val labels = listOf(stringResource(R.string.tilt_x_rotation), stringResource(R.string.tilt_y_rotation), stringResource(R.string.tilt_z_rotation))
    fun openKeypad(which: Int) {
        axis = which
        state.keypad = KeypadRequest(labels.mapIndexed { i, label -> KeypadField(label, values[i]) }, which) { v ->
            state.editSelected { turn.withValues(it, v.toFloatArray()) }
        }
    }
    Box(
        Modifier.fillMaxSize().pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var fingers = 1
                var moved = false
                do {
                    val event = awaitPointerEvent()
                    fingers = maxOf(fingers, event.changes.size)
                    if (!moved) moved = fingers > 1 || (event.changes.first().position - down.position).getDistance() > viewConfiguration.touchSlop
                    if (moved) {
                        state.turner?.invoke(if (fingers > 1) Offset.Zero else event.calculatePan() / density, if (fingers > 1) event.calculateRotation() else 0f)
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
                if (moved) state.endGesture()
            }
        },
    ) {
        TrackpadCorners()
        Text(stringResource(R.string.drag_here_to_rotate_in_3d), Modifier.align(Alignment.Center), color = PanelCaption, fontSize = 15.sp, maxLines = 1, softWrap = false)
        Column(Modifier.align(Alignment.TopCenter).padding(top = 17.px)) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.px)) {
                for (i in 0..2) NumberBox(formatFixed(values[i], 1) + "º", if (turn.linked(i, axis)) Am.Accent else Am.OnDark, Modifier.width(177.px).height(70.px), fontSize = 13.5f) { openKeypad(i) }
            }
            Row(Modifier.height(23.px), Arrangement.spacedBy(20.px)) {
                for (label in labels) Caption(label, Modifier.width(177.px).fillMaxHeight())
            }
        }
    }
}
