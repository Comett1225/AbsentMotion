package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.FullColor
import app.absentmotion.editor.KeyTarget
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.parseColor
import app.absentmotion.ui.editor.deskVerticalScroll
import app.absentmotion.ui.theme.Am

private val Intensity = EffectParam("intensity", "", "float", "1", 0f, 4f, 0.01f)
private val Falloff = EffectParam("falloff", "", "float", "1", 0f, 100f, 0.05f)
private val ColorRef = PropRef.shape("color")

@Composable
internal fun LightPanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    var active by remember { mutableStateOf("intensity") }
    val type = layer.node["type"] ?: "directional"
    val colour = parseColor(layer.propText("color")) ?: 0xffffffff.toInt()
    SideEffect {
        if (type != "point" && active == "falloff") active = "intensity"
        state.keyTarget = if (active == "color") KeyTarget(ColorRef, "#ffffffff", "color") else KeyTarget(PropRef.shape(active), "1")
    }
    Column(modifier.deskVerticalScroll()) {
        Row(Modifier.fillMaxWidth().height(40.dp)) {
            listOf("ambient" to R.string.light_ambient, "directional" to R.string.light_directional, "point" to R.string.light_point).forEach { (id, label) ->
                Box(Modifier.weight(1f).padding(2.dp).background(if (id == type) Am.Field else PanelDark)
                    .clickable { state.editSelected { it.copy(node = it.node.with("type", id)) } }, contentAlignment = Alignment.Center) {
                    Text(stringResource(label), color = if (id == type) Am.Accent else Am.OnDark, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(40.dp).clickable {
            active = "color"
            state.fullColor = FullColor(colour) { value, drag -> state.setColour(ColorRef, value, if (drag) "light-color" else null) }
        }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.color), Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp)
            Box(Modifier.weight(1f).height(28.dp).background(Color(colour)))
        }
        val params = if (type == "point") listOf(Intensity to R.string.light_intensity, Falloff to R.string.light_falloff) else listOf(Intensity to R.string.light_intensity)
        for ((plain, label) in params) {
            val param = plain.copy(label = stringResource(label))
            EffectNumbers(state, param, layer.propText(param.id) ?: param.default, true, active == param.id, { active = param.id }) { text, gesture ->
                active = param.id
                state.editSelected(if (gesture) "light-${param.id}" else null) { it.withProp(param.id, "float", text) }
            }
        }
    }
}
