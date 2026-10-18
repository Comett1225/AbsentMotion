package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.*
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.*
import app.absentmotion.ui.editor.AboveLeft
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.DropOrRise
import app.absentmotion.ui.editor.MenuPopup
import app.absentmotion.ui.editor.MenuRow
import app.absentmotion.ui.editor.PanelHeight
import app.absentmotion.ui.editor.PanelIcons
import app.absentmotion.ui.editor.effects.EffectIcons
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.Swap

private var copiedEffects by mutableStateOf<List<XmlNode>>(emptyList())

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
    var active by remember(layer.id, index, definition?.id) { mutableStateOf(definition?.params?.firstOrNull { it.kind != "color" && it.kind != "switch" }) }
    var menu by remember { mutableStateOf(false) }
    val usable = instance != null && definition?.available == true
    val target = active?.takeIf { usable }?.let { KeyTarget(PropRef.effect(index, it.id), it.default, it.type) }
    val point = active?.takeIf { it.kind == "point" && usable }
    val adjust = remember(layer.id, index, point?.id) {
        point?.let { param ->
            PointAdjust(param.label) { dx, dy ->
                state.editSelected("effect-$index-${param.id}") { current ->
                    val old = (current.effects.getOrNull(index)?.effectProperty(param.id)?.valueAt(current.t) ?: param.default).split(',').map { it.trim().toFloatOrNull() ?: 0f }
                    val x = (old.getOrElse(0) { 0f } + dx / state.scene.width).coerceIn(param.min, param.max)
                    val y = (old.getOrElse(1) { 0f } + dy / state.scene.height).coerceIn(param.min, param.max)
                    current.withEffect(index) { it.withEffectProperty(param.id, param.type, formatFloats(floatArrayOf(x, y)), current.t, current.snap) }
                }
            }
        }
    }
    SideEffect { state.keyTarget = target; state.pointAdjust = adjust }
    DisposableEffect(Unit) { onDispose { state.pointAdjust = null } }
    if (state.easingOpen) { EasingPanel(state, modifier); return }
    Row(modifier.background(Am.Panel)) {
        Column(Modifier.width(168.px).heightIn(max = PanelHeight).fillMaxHeight()) {
            EffectFrameButton(PanelIcons.Back, Modifier.weight(1f)) { state.stepBack() }
            EffectFrameButton(if (target != null && layer.onKeyframe(target.ref)) PanelIcons.KeyframeRemove else PanelIcons.Keyframe, Modifier.weight(1f), target != null) { state.toggleKeyframe() }
            EffectFrameButton(PanelIcons.Easing, Modifier.weight(1f), target != null) { if (target != null && layer.easing(target.ref) != null) state.easingOpen = true else state.easingHint = true }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EffectFrameButton(PanelIcons.More, Modifier.fillMaxSize(), instance == null) { menu = true }
                Presence(menu) {
                    MenuPopup(AboveLeft, { menu = false }, TransformOrigin(0f, 1f)) {
                        MenuRow("Copy Effects", enabled = layer.effects.isNotEmpty()) { copiedEffects = layer.effects; menu = false }
                        MenuRow(if (copiedEffects.size == 1) "Paste Effect" else "Paste Effects", enabled = copiedEffects.isNotEmpty(), divider = true) {
                            state.editSelected { copiedEffects.fold(it) { next, effect -> next.addEffect(effect) } }
                            menu = false
                        }
                    }
                }
            }
        }
        Swap(index, Modifier.weight(1f).fillMaxHeight(), rise = 0.dp) {
            if (instance == null) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    layer.effects.forEachIndexed { i, effect -> EffectListRow(state, i, effect, catalog.find(effect["id"] ?: "")) }
                    Box(Modifier.padding(6.5.dp).fillMaxWidth().height(37.dp).clip(RowShape).background(Am.Button)
                        .clickable { state.playing = false; state.effectBrowserOpen = true }, contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(EffectIcons.Add, null, Modifier.size(18.dp), tint = Am.OnDark)
                            Spacer(Modifier.width(6.dp))
                            Text("Add Effect", color = Am.OnDark, fontSize = 14.sp)
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
private fun EffectHeader(state: EditorState, index: Int, effect: XmlNode, definition: EffectDefinition?) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(37.dp).clickable { state.effectIndex = -1 }.padding(start = 3.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(EffectIcons.Expanded, null, Modifier.padding(start = 8.dp).size(18.dp).alpha(0.9f), tint = Am.OnDark)
        Text(definition?.name ?: effect["id"]?.substringAfterLast('.') ?: "", Modifier.weight(1f).padding(start = 6.dp, end = 4.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
        Box(Modifier.size(36.dp).clickable { menu = true }, contentAlignment = Alignment.Center) {
            Icon(EffectIcons.MoreVert, "More", Modifier.size(24.dp), tint = Am.OnDark)
            Presence(menu) {
                MenuPopup(DropOrRise, { menu = false }) {
                    MenuRow("Effect Info", enabled = definition != null) {
                        definition?.let { state.effectDetail = it.id; state.playing = false; state.effectBrowserOpen = true }
                        menu = false
                    }
                    MenuRow("Reset to Defaults", enabled = definition != null) {
                        definition?.let { def -> state.editSelected { it.withEffect(index) { old -> old.copy(children = def.instance().children) } } }
                        menu = false
                    }
                    MenuRow("Duplicate") { state.editSelected { it.duplicateEffect(index) }; menu = false }
                    MenuRow("Copy Effect") { copiedEffects = listOf(effect); menu = false }
                    val settings = copiedEffects.singleOrNull()?.takeIf { it["id"] == effect["id"] }
                    MenuRow("Paste Effect Values", enabled = settings != null) {
                        settings?.let { from -> state.editSelected { it.withEffect(index) { old -> old.copy(children = from.children) } } }
                        menu = false
                    }
                }
            }
        }
        Box(Modifier.size(36.dp).clickable { state.editSelected { it.removeEffect(index) }; state.effectIndex = -1 }, contentAlignment = Alignment.Center) {
            Icon(ChromeIcons.Trash, "Delete", Modifier.size(22.dp), tint = Am.OnDark)
        }
    }
}

@Composable
private fun EffectListRow(state: EditorState, index: Int, effect: XmlNode, definition: EffectDefinition?) {
    val visible = effect["hidden"] != "true"
    var drag by remember { mutableFloatStateOf(0f) }
    val latestIndex by rememberUpdatedState(index)
    var held by remember { mutableIntStateOf(index) }
    Row(Modifier.padding(start = 6.5.dp, end = 6.5.dp, top = 6.5.dp).fillMaxWidth().height(37.dp).clip(RowShape)
        .background(Am.Field), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).fillMaxHeight().alpha(if (definition?.available == true && effect["locallyApplied"] != "true") 1f else 0.35f)
            .clickable { state.effectIndex = index }.padding(start = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(EffectIcons.Expand, null, Modifier.size(18.dp).alpha(0.3f), tint = Am.OnDark)
            Text(definition?.name ?: effect["id"]?.substringAfterLast('.') ?: "", Modifier.padding(start = 6.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
        }
        Icon(if (visible) EffectIcons.Eye else EffectIcons.EyeOff, "Visibility", Modifier.width(36.dp).clickable {
            state.editSelected { it.withEffect(index) { node -> node.with("hidden", if (visible) "true" else null) } }
        }, tint = Am.OnDark)
        Icon(EffectIcons.Grip, "Reorder", Modifier.width(32.dp).fillMaxHeight().pointerInput(state) {
            detectDragGesturesAfterLongPress(onDragStart = { drag = 0f; held = latestIndex }, onDragEnd = { state.endGesture() }, onDragCancel = { state.endGesture() }) { change, amount ->
                change.consume(); drag += amount.y
                val step = 43.5.dp.toPx()
                if (kotlin.math.abs(drag) >= step) {
                    val from = held
                    val to = (from + if (drag > 0) 1 else -1).coerceIn(0, (state.selected?.effects?.size ?: 1) - 1)
                    if (to != from) state.editSelected("effect-reorder") { it.moveEffect(from, to) }
                    held = to
                    drag = 0f
                }
            }
        }, tint = Am.OnDark)
    }
}

@Composable
private fun EffectFrameButton(icon: ImageVector, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = Am.OnDark)
    }
}
