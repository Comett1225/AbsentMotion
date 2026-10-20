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
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

private val ModelParams = listOf(
    EffectParam("size", "", "float", "300", 1f, 20000f, 1f) to R.string.model_param_size,
    EffectParam("spin", "", "angle", "0", -3600f, 3600f, 1f) to R.string.model_param_auto_rotate,
    EffectParam("lightAngle", "", "angle", "135", -3600f, 3600f, 1f) to R.string.model_param_light_angle,
    EffectParam("ambient", "", "float", "0.35", 0f, 1f, 0.01f) to R.string.model_param_ambient_light,
    EffectParam("intensity", "", "float", "1", 0f, 4f, 0.01f) to R.string.model_param_light_intensity,
)

@Composable
internal fun ModelPanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    var active by remember { mutableStateOf(ModelParams[0].first) }
    SideEffect { state.keyTarget = KeyTarget(PropRef.shape(active.id), active.default) }
    Column(modifier.verticalScroll(rememberScrollState())) {
        for ((plain, label) in ModelParams) {
            val param = plain.copy(label = stringResource(label))
            EffectNumbers(state, param, layer.propText(param.id) ?: param.default, true, active.id == param.id, { active = plain }) { text, gesture ->
                active = plain
                state.editSelected(if (gesture) "model-${param.id}" else null) { it.withProp(param.id, "float", text) }
            }
        }
    }
}
