package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.editor.KeypadField
import app.absentmotion.editor.KeypadRequest
import app.absentmotion.editor.setCrop
import app.absentmotion.editor.stepBack
import app.absentmotion.editor.toggleCropKeyframes
import app.absentmotion.editor.uncropped
import app.absentmotion.model.CROP_ID
import app.absentmotion.model.CropBox
import app.absentmotion.model.CropSides
import app.absentmotion.model.PropRef
import app.absentmotion.model.cropBox
import app.absentmotion.model.cropIndex
import app.absentmotion.model.cropRatio
import app.absentmotion.model.onCropKeyframe
import app.absentmotion.ui.editor.PanelHeight
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch

internal val CropAspects = listOf(null to "", 1f to "1:1", 4f / 5f to "4:5", 3f / 4f to "3:4", 16f / 9f to "16:9", 9f / 16f to "9:16")

@Composable
internal fun CropPanel(state: EditorState, modifier: Modifier) {
    val layer = state.selected ?: return
    val index = layer.cropIndex
    LaunchedEffect(index) { if (index < 0) state.panel = EditorPanel.Tools }
    if (index < 0) return
    val catalog by state.engine.effects.collectAsState()
    val labels = catalog.find(CROP_ID)?.params?.associate { it.id to it.label }.orEmpty()
    val box = layer.cropBox(index)
    val scope = rememberCoroutineScope()
    val playing = state.playing
    val change = remember(layer.id, index) { uncropped(layer.id, index) }
    SideEffect {
        state.keyTarget = KeyTarget(PropRef.effect(index, "left"), "0.000000")
        state.previewChange = if (playing) null else change
    }
    DisposableEffect(Unit) { onDispose { state.previewChange = null; state.keyTarget = null } }
    fun aspect(ratio: Float?) {
        state.cropAspect = ratio
        if (ratio == null) return
        scope.launch {
            val q = state.engine.layerQuad(layer.id, state.timeMs) ?: return@launch
            val w = kotlin.math.hypot(q[2] - q[0], q[3] - q[1])
            val h = kotlin.math.hypot(q[6] - q[0], q[7] - q[1])
            state.setCrop(CropBox.fit(cropRatio(ratio, w, h)))
        }
    }
    fun keypad(active: Int) {
        val shown = List(4) { kotlin.math.round(box.side(it) * 1000f) / 10f }
        state.keypad = KeypadRequest(CropSides.mapIndexed { i, side -> KeypadField(labels[side] ?: side, shown[i], "%", 0f, 100f) }, active) { next ->
            state.setCrop(box.typed(next.withIndex().filter { (i, v) -> v != shown[i] }.associate { (i, v) -> i to v / 100f }))
        }
    }
    Row(modifier.background(Am.Panel)) {
        Column(Modifier.width(168.px).heightIn(max = PanelHeight).fillMaxHeight()) {
            SideButton(PanelIcons.Back, stringResource(R.string.topbar_back), Modifier.weight(1f)) { state.stepBack() }
            val keyed = layer.onCropKeyframe(index)
            SideButton(if (keyed) PanelIcons.KeyframeRemove else PanelIcons.Keyframe, stringResource(if (keyed) R.string.a11y_remove_keyframe else R.string.a11y_add_keyframe), Modifier.weight(1f)) {
                state.toggleCropKeyframes()
            }
            Spacer(Modifier.weight(2f))
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(Modifier.weight(1f).fillMaxHeight().background(Am.PanelInset).padding(horizontal = 8.dp, vertical = 6.dp), Arrangement.SpaceEvenly) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), Arrangement.spacedBy(6.dp)) {
                    val free = stringResource(R.string.crop_free)
                    for ((ratio, name) in CropAspects) {
                        val label = name.ifEmpty { free }
                        val chosen = state.cropAspect == ratio
                        val description = if (ratio == null) free else stringResource(R.string.a11y_crop_ratio, name)
                        Box(
                            Modifier.height(48.dp).widthIn(min = 52.dp).clip(PanelShape).background(if (chosen) PanelDark else Am.Field)
                                .semantics { contentDescription = description; selected = chosen }
                                .clickable(role = Role.RadioButton) { aspect(ratio) }.padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (chosen) Am.Accent else Am.OnDark, fontSize = 14.sp, maxLines = 1) }
                    }
                }
                Row(Modifier.fillMaxWidth().height(52.dp), Arrangement.spacedBy(6.dp)) {
                    CropSides.forEachIndexed { i, side ->
                        val label = labels[side] ?: side
                        val value = formatFixed(box.side(i) * 100f, 1) + "%"
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(PanelShape).background(Am.Field)
                                .semantics(mergeDescendants = true) { contentDescription = "$label $value" }.clickable { keypad(i) },
                            Arrangement.Center, Alignment.CenterHorizontally,
                        ) {
                            Text(label, color = PanelCaption, fontSize = 11.sp, maxLines = 1)
                            Text(value, color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(48.dp), Arrangement.spacedBy(6.dp)) {
                    CropButton(stringResource(R.string.crop_reset), Modifier.weight(1f)) { state.cropAspect = null; state.setCrop(CropBox()) }
                    CropButton(stringResource(R.string.done), Modifier.weight(1f)) { state.panel = EditorPanel.Tools }
                }
            }
        }
    }
}

@Composable
private fun SideButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        PanelIcon(icon, Am.OnDark, description = description)
    }
}

@Composable
private fun CropButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().clip(PanelShape).background(Am.Button).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
    }
}
