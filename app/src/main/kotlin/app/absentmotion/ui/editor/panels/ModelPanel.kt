package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef

private val ModelParams = listOf(
    EffectParam("size", "Size", "float", "300", 1f, 20000f, 1f),
    EffectParam("spin", "Auto-rotate", "angle", "0", -3600f, 3600f, 1f),
    EffectParam("lightAngle", "Light Angle", "angle", "135", -3600f, 3600f, 1f),
    EffectParam("ambient", "Ambient Light", "float", "0.35", 0f, 1f, 0.01f),
    EffectParam("intensity", "Light Intensity", "float", "1", 0f, 4f, 0.01f),
)

@Composable
internal fun ModelPanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    var active by remember { mutableStateOf(ModelParams[0]) }
    SideEffect { state.keyTarget = KeyTarget(PropRef.shape(active.id), active.default) }
    Column(modifier.verticalScroll(rememberScrollState())) {
        for (param in ModelParams) {
            EffectNumbers(state, param, layer.propText(param.id) ?: param.default, true, active == param, { active = param }) { text, gesture ->
                active = param
                state.editSelected(if (gesture) "model-${param.id}" else null) { it.withProp(param.id, "float", text) }
            }
        }
    }
}
