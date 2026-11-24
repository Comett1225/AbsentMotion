package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.editor.LayerPoints
import app.absentmotion.effects.EffectParam
import app.absentmotion.media.ModelBone
import app.absentmotion.media.boneProperty
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Pt
import app.absentmotion.model.posed
import app.absentmotion.model.withoutPose
import app.absentmotion.ui.adaptive.deskLayout
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.deskVerticalScroll
import app.absentmotion.ui.home.AccentPill
import app.absentmotion.ui.theme.Am

private val TurnLabels = listOf(R.string.model_turn_x, R.string.model_turn_y, R.string.model_turn_z)

private val chosenBone = HashMap<Long, Int>()
private val chosenAxis = HashMap<Long, Int>()

internal fun jointPoints(xy: FloatArray?, bones: List<ModelBone>, chosen: Int, pick: (Int) -> Unit): LayerPoints? {
    if (xy == null || xy.size != bones.size * 2) return null
    return LayerPoints(List(bones.size) { Pt(xy[it * 2], xy[it * 2 + 1]) }, chosen, lines = bones.mapIndexedNotNull { i, b -> if (b.parent >= 0) b.parent to i else null },
        pick = pick, scene = true) { _, _ -> }
}

@Composable
internal fun PosePanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val bones = rememberModelFacts(layer)?.bones.orEmpty()
    var chosen by remember(layer.id) { mutableIntStateOf(chosenBone[layer.id] ?: 0) }
    var axis by remember(layer.id) { mutableIntStateOf(chosenAxis[layer.id] ?: 2) }
    SideEffect { chosenBone[layer.id] = chosen; chosenAxis[layer.id] = axis }
    val bone = bones.getOrNull(chosen)
    SideEffect { state.keyTarget = bone?.let { KeyTarget(PropRef.shape(boneProperty(it.node, axis)), "0") } }
    val revision by state.engine.mediaRevision.collectAsState()
    val joints by produceState<FloatArray?>(null, layer.id, layer.node, state.timeMs, state.playing, revision) {
        value = if (state.playing) null else state.engine.modelJoints(layer.id, state.timeMs)
    }
    SideEffect { state.layerPoints = jointPoints(joints, bones, chosen) { chosen = it } }
    DisposableEffect(Unit) { onDispose { state.layerPoints = null } }
    Column(modifier.deskVerticalScroll().padding(horizontal = if (deskLayout) 8.dp else 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (deskLayout) {
            Text(stringResource(R.string.model_bones), color = Am.Caption, fontSize = 12.sp)
            val depth = remember(bones) { IntArray(bones.size).also { d -> bones.forEachIndexed { i, b -> if (b.parent in 0 until i) d[i] = d[b.parent] + 1 } } }
            bones.forEachIndexed { i, b ->
                val on = i == chosen
                Row(Modifier.fillMaxWidth().height(30.dp).clip(PanelShape).background(if (on) PanelDark else Am.Panel).clickable { chosen = i }
                    .padding(start = (8 + 16 * depth[i].coerceAtMost(12)).dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PanelIcon(PanelIcons.Bone, if (on) Am.Accent else Am.Caption, size = 14.dp)
                    Text(b.name.ifEmpty { stringResource(R.string.model_bone_number, "${i + 1}") }, Modifier.padding(start = 7.dp), color = if (on) Am.Accent else Am.OnDark,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            bones.forEachIndexed { i, b ->
                val on = i == chosen
                Box(Modifier.height(28.dp).clip(CircleShape).background(if (on) Am.Field else Am.Button).then(if (on) Modifier.border(1.dp, Am.Accent, CircleShape) else Modifier)
                    .clickable { chosen = i }.padding(horizontal = 10.dp), Alignment.Center) {
                    Text(b.name.ifEmpty { stringResource(R.string.model_bone_number, "${i + 1}") }, color = if (on) Am.Accent else Am.OnDark, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        if (bone != null) for (a in 0..2) {
            val id = boneProperty(bone.node, a)
            val param = EffectParam(id, stringResource(TurnLabels[a]), "angle", "0", -3600f, 3600f, 1f)
            EffectNumbers(state, param, layer.propText(id) ?: "0", true, axis == a, { axis = a }) { text, gesture ->
                axis = a
                state.editSelected(if (gesture) "model-$id" else null) { it.withProp(id, "float", text) }
            }
        }
        AccentPill(stringResource(R.string.model_reset_pose), Modifier.fillMaxWidth().height(40.dp).padding(top = 4.dp), enabled = layer.posed, primary = false) {
            state.editSelected { it.withoutPose() }
        }
    }
}
