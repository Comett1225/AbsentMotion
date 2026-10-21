package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.*
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.*
import app.absentmotion.ui.editor.AboveLeft
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.DimAlpha
import app.absentmotion.ui.editor.DropOrRise
import app.absentmotion.ui.editor.MenuPopup
import app.absentmotion.ui.editor.MenuRow
import app.absentmotion.ui.editor.PanelHeight
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.Reorder
import app.absentmotion.ui.editor.rememberReorder
import app.absentmotion.ui.editor.reorderGrip
import app.absentmotion.ui.editor.reorderLift
import app.absentmotion.ui.editor.reorderSlot
import app.absentmotion.ai.aiMarks
import app.absentmotion.ui.editor.effects.AiMarkRow
import app.absentmotion.ui.editor.effects.AiMarkRowGap
import app.absentmotion.ui.editor.effects.AiMarkRowHeight
import app.absentmotion.ui.editor.effects.EffectIcons
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.Swap
import androidx.compose.ui.res.stringResource
import app.absentmotion.R

@Composable
internal fun EffectsPanel(state: EditorState, modifier: Modifier) {
    val layer = state.selected ?: return
    val catalog by state.engine.effects.collectAsState()
    val index = state.effectIndex
    val instance = layer.effects.getOrNull(index)
    val definition = instance?.let { catalog.find(it["id"] ?: "") }
    val shownFor = remember { longArrayOf(layer.id) }
    LaunchedEffect(layer.id) { if (shownFor[0] != layer.id) { shownFor[0] = layer.id; state.effectIndex = -1 } }
    LaunchedEffect(instance == null) { if (instance == null && index >= 0) state.effectIndex = -1 }
    var active by remember(layer.id, index, definition?.id) { mutableStateOf(definition?.params?.firstOrNull { it.kind != "color" && it.kind != "switch" && it.kind != "choice" && it.kind != "text" }) }
    var menu by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val usable = instance != null && definition?.available == true
    val target = active?.takeIf { usable }?.let { KeyTarget(PropRef.effect(index, it.id), it.missingDefault, it.type) }
    val point = active?.takeIf { it.kind == "point" && usable }
    val adjust = remember(layer.id, index, point?.id) {
        point?.let { param ->
            PointAdjust(param.label) { dx, dy ->
                state.editSelected("effect-$index-${param.id}") { current ->
                    val old = (current.effects.getOrNull(index)?.effectProperty(param.id)?.valueAt(current.t) ?: param.missingDefault).split(',').map { it.trim().finiteFloat() ?: 0f }
                    val x = (old.getOrElse(0) { 0f } + if (param.pixels) dx else dx / state.scene.width).coerceIn(param.min, param.max)
                    val y = (old.getOrElse(1) { 0f } + if (param.pixels) dy else dy / state.scene.height).coerceIn(param.min, param.max)
                    current.withEffect(index) { it.withEffectProperty(param.id, param.type, formatFloats(floatArrayOf(x, y)), current.t, current.snap) }
                }
            }
        }
    }
    SideEffect { state.keyTarget = target; state.pointAdjust = adjust }
    DisposableEffect(Unit) { onDispose { state.pointAdjust = null } }
    if (state.easingOpen) { EasingPanel(state, modifier); return }
    if (choosing) {
        CopyEffectsDialog(layer.effects.map { catalog.find(it["id"] ?: "")?.name ?: it["id"]?.substringAfterLast('.') ?: "" }, { choosing = false }) { ticked ->
            EffectClipboard.copy(layer.effects, ticked)
            choosing = false
        }
    }
    Row(modifier.background(Am.Panel)) {
        Column(Modifier.width(168.px).heightIn(max = PanelHeight).fillMaxHeight()) {
            EffectFrameButton(PanelIcons.Back, stringResource(R.string.topbar_back), Modifier.weight(1f)) { state.stepBack() }
            val onKeyframe = target != null && layer.onKeyframe(target.ref)
            EffectFrameButton(if (onKeyframe) PanelIcons.KeyframeRemove else PanelIcons.Keyframe, stringResource(if (onKeyframe) R.string.a11y_remove_keyframe else R.string.a11y_add_keyframe), Modifier.weight(1f), target != null) { state.toggleKeyframe() }
            EffectFrameButton(PanelIcons.Easing, stringResource(R.string.a11y_easing), Modifier.weight(1f), target != null) { if (target != null && layer.easing(target.ref) != null) state.easingOpen = true else state.easingHint = true }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EffectFrameButton(PanelIcons.More, stringResource(R.string.effects_more), Modifier.fillMaxSize(), instance == null) { menu = true }
                Presence(menu) {
                    MenuPopup(AboveLeft, { menu = false }, TransformOrigin(0f, 1f)) {
                        MenuRow(stringResource(R.string.effects_copy_effects), enabled = layer.effects.isNotEmpty()) { choosing = true; menu = false }
                        MenuRow(stringResource(if (EffectClipboard.effects.size == 1) R.string.effects_paste_effect else R.string.effects_paste_effects), enabled = EffectClipboard.effects.isNotEmpty(), divider = true) {
                            state.editSelected { EffectClipboard.effects.fold(it) { next, effect -> next.addEffect(effect) } }
                            menu = false
                        }
                    }
                }
            }
        }
        Swap(index, Modifier.weight(1f).fillMaxHeight(), rise = 0.dp) {
            if (instance == null) {
                val scroll = rememberScrollState()
                val marks = layer.aiMarks
                val reorder = rememberReorder(state, scroll, top = 6.5.dp + (AiMarkRowHeight + AiMarkRowGap) * marks.size)
                val id = layer.id
                val effects = layer.effects
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    for (mark in marks) AiMarkRow(state, mark)
                    key(reorder.generation) {
                        effects.forEachIndexed { i, effect ->
                            EffectListRow(state, reorder, i, effects.size, effect, catalog.find(effect["id"] ?: "")) { from, to ->
                                state.edit { it.update(id, state.sampleMs) { l -> l.moveEffect(from, to) } }
                            }
                        }
                    }
                    Box(Modifier.padding(6.5.dp).fillMaxWidth().height(37.dp).clip(RowShape).background(Am.Button)
                        .clickable { state.playing = false; state.effectBrowserOpen = true }, contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(EffectIcons.Add, null, Modifier.size(18.dp), tint = Am.OnDark)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.add_effect), color = Am.OnDark, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(6.5.dp).clip(RowShape).background(Am.Field)) {
                    EffectHeader(state, index, instance, definition)
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        if (definition != null) EffectSettings(state, layer, instance, definition, active, { active = it })
                        else Text(instance["id"] ?: "", Modifier.padding(12.dp).dimmed(), color = Am.OnDark, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private val RowShape = RoundedCornerShape(5.dp)

@Composable
private fun CopyEffectsDialog(names: List<String>, onDismiss: () -> Unit, onCopy: (Set<Int>) -> Unit) {
    var ticked by remember { mutableStateOf(names.indices.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.effects_copy_effects)) },
        text = {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                names.forEachIndexed { i, name ->
                    val on = i in ticked
                    Row(Modifier.fillMaxWidth().height(44.dp).toggleable(on, role = Role.Checkbox) { ticked = if (on) ticked - i else ticked + i }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = Am.Accent, uncheckedColor = Am.OnDark, checkmarkColor = Am.OnAccent))
                        Text(name, Modifier.padding(start = 8.dp), color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ onCopy(ticked) }, enabled = ticked.isNotEmpty()) {
                Text(stringResource(R.string.effects_copy_confirm), color = Am.Accent.copy(alpha = if (ticked.isEmpty()) DimAlpha else 1f))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = Am.Accent) } },
        containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark,
    )
}

@Composable
private fun EffectHeader(state: EditorState, index: Int, effect: XmlNode, definition: EffectDefinition?) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(37.dp).clickable { state.effectIndex = -1 }.padding(start = 3.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(EffectIcons.Expanded, null, Modifier.padding(start = 8.dp).size(18.dp).alpha(0.9f), tint = Am.OnDark)
        Text(definition?.name ?: effect["id"]?.substringAfterLast('.') ?: "", Modifier.weight(1f).padding(start = 6.dp, end = 4.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
        Box(Modifier.size(36.dp).clickable { menu = true }, contentAlignment = Alignment.Center) {
            Icon(EffectIcons.MoreVert, stringResource(R.string.effects_more), Modifier.size(24.dp), tint = Am.OnDark)
            Presence(menu) {
                MenuPopup(DropOrRise, { menu = false }) {
                    MenuRow(stringResource(R.string.effects_effect_info), enabled = definition != null) {
                        definition?.let { state.effectDetail = it.id; state.playing = false; state.effectBrowserOpen = true }
                        menu = false
                    }
                    MenuRow(stringResource(R.string.effects_reset_defaults), enabled = definition != null) {
                        definition?.let { def -> state.editSelected { it.withEffect(index) { old -> old.copy(children = def.instance().children) } } }
                        menu = false
                    }
                    MenuRow(stringResource(R.string.effects_duplicate)) { state.editSelected { it.duplicateEffect(index) }; menu = false }
                    MenuRow(stringResource(R.string.effects_copy_effect)) { EffectClipboard.copyOne(effect); menu = false }
                    val settings = EffectClipboard.effects.singleOrNull()?.takeIf { it["id"] == effect["id"] }
                    MenuRow(stringResource(R.string.effects_paste_values), enabled = settings != null) {
                        settings?.let { from -> state.editSelected { it.withEffect(index) { old -> old.copy(children = from.children) } } }
                        menu = false
                    }
                }
            }
        }
        Box(Modifier.size(36.dp).clickable { state.editSelected { it.removeEffect(index) }; state.effectIndex = -1 }, contentAlignment = Alignment.Center) {
            Icon(ChromeIcons.Trash, stringResource(R.string.delete), Modifier.size(22.dp), tint = Am.OnDark)
        }
    }
}

@Composable
private fun EffectListRow(state: EditorState, reorder: Reorder, index: Int, count: Int, effect: XmlNode, definition: EffectDefinition?, move: (Int, Int) -> Unit) {
    val visible = effect["hidden"] != "true"
    Row(Modifier.reorderSlot(reorder, index).padding(start = 6.5.dp, end = 6.5.dp, top = 6.5.dp).fillMaxWidth().height(37.dp).reorderLift(reorder, index, 5.dp)
        .clip(RowShape).background(Am.Field), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).fillMaxHeight().alpha(if (definition?.available == true && effect["locallyApplied"] != "true") 1f else 0.35f)
            .clickable { state.effectIndex = index }.padding(start = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(EffectIcons.Expand, null, Modifier.size(18.dp).alpha(0.3f), tint = Am.OnDark)
            Text(definition?.name ?: effect["id"]?.substringAfterLast('.') ?: "", Modifier.padding(start = 6.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
        }
        Icon(if (visible) EffectIcons.Eye else EffectIcons.EyeOff, stringResource(R.string.effects_visibility), Modifier.width(36.dp).clickable {
            state.editSelected { it.withEffect(index) { node -> node.with("hidden", if (visible) "true" else null) } }
        }, tint = Am.OnDark)
        Icon(EffectIcons.Grip, stringResource(R.string.effects_reorder), Modifier.width(32.dp).fillMaxHeight().reorderGrip(reorder, index, count, move), tint = Am.OnDark)
    }
}

@Composable
private fun EffectFrameButton(icon: ImageVector, description: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(24.dp), tint = Am.OnDark)
    }
}
