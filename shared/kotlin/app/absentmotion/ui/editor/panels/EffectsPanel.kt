package app.absentmotion.ui.editor.panels

import app.absentmotion.ui.editor.deskVerticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.collectAsState
import app.absentmotion.ui.editor.DeskTip
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import app.absentmotion.ui.editor.DeskDrop
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.ui.editor.effects.Thumbnail
import app.absentmotion.ui.tutorial.tutorialGuard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
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
import app.absentmotion.effects.PresetFavouriteStore
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.editor.effects.PresetStar
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
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.home.HelpScreen
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
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.absentmotion.ui.editor.effects.EffectSwipe
import app.absentmotion.ui.editor.effects.effectSwipe
import app.absentmotion.ui.editor.rememberHaptic
import app.absentmotion.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    LaunchedEffect(definition) { active = definition?.params?.firstOrNull { it.id == active?.id } }
    var menu by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val usable = instance != null && definition?.available == true
    var pin by remember(layer.id, index) { mutableStateOf<String?>(null) }
    var adding by remember(layer.id, index) { mutableStateOf(false) }
    var pinField by remember(layer.id, index) { mutableStateOf("position") }
    val puppet = usable && definition.id == PUPPET_ID && instance["hidden"] != "true"
    val pathText = usable && definition.id == TEXT_PATH_ID
    val pinTarget = if (puppet) pin?.let { name ->
        val number = name.removePrefix("pin")
        val prop = when (pinField) { "rotation" -> "pinRotation$number"; "scale" -> "pinScale$number"; else -> name }
        val fallback = when (pinField) { "rotation" -> "0"; "scale" -> "1"; else -> "0,0" }
        KeyTarget(PropRef.effect(index, prop), instance.effectProperty(prop)?.valueAt(layer.t) ?: fallback, if (pinField == "position") "vec2" else "float")
    } else null
    val target = pinTarget ?: active?.takeIf { usable && definition.shown(it, instance, layer.t) }?.let { KeyTarget(PropRef.effect(index, it.id), it.missingDefault, it.type) }
    val point = active?.takeIf { it.kind == "point" && usable && !pathText }
    val adjust = remember(layer.id, index, point?.id, point?.label) {
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
    val full = rememberPinsFull()
    val points = when {
        puppet -> puppetPoints(state, layer, index, instance, pin, adding, { pin = it; pinField = "position"; if (it != null) active = null }, full)
        pathText -> textPathPoints(state, layer, index, instance, definition, active) { active = it; pin = null }
        else -> null
    }
    SideEffect { state.keyTarget = target; state.pointAdjust = adjust; state.layerPoints = points }
    DisposableEffect(Unit) { onDispose { state.pointAdjust = null; state.layerPoints = null } }
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
            val linked = target != null && layer.element(target.ref)?.children?.any { it.name == "valueLink" } == true
            val onKeyframe = target != null && layer.onKeyframe(target.ref)
            EffectFrameButton(if (onKeyframe) PanelIcons.KeyframeRemove else PanelIcons.Keyframe, stringResource(if (onKeyframe) R.string.a11y_remove_keyframe else R.string.a11y_add_keyframe), Modifier.weight(1f), target != null && !linked) { state.toggleKeyframe() }
            EffectFrameButton(PanelIcons.Easing, stringResource(R.string.a11y_easing), Modifier.weight(1f), target != null && !linked) { if (target != null && layer.easing(target.ref) != null) state.easingOpen = true else state.easingHint = true }
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
        val listScroll = remember(layer.id) { ScrollState(0) }
        CardSwap(if (instance == null) -1 else index, Modifier.weight(1f).fillMaxHeight(), list = { cards ->
            val scroll = listScroll
            val marks = layer.aiMarks
            val reorder = rememberReorder(state, scroll, top = 6.5.dp + (AiMarkRowHeight + AiMarkRowGap) * marks.size)
            val id = layer.id
            val effects = layer.effects
            Column(Modifier.fillMaxSize().deskVerticalScroll(scroll)) {
                for (mark in marks) Box(Modifier.cardAway(cards, -1)) { AiMarkRow(state, mark) }
                key(reorder.generation) {
                    effects.forEachIndexed { i, effect ->
                        EffectListRow(state, reorder, i, effects.size, effect, catalog.find(effect["id"] ?: ""), cards) { from, to ->
                            state.edit { it.update(id, state.sampleMs) { l -> l.moveEffect(from, to) } }
                        }
                    }
                }
                Box(Modifier.cardAway(cards, Int.MAX_VALUE).padding(6.5.dp).fillMaxWidth().height(37.dp).clip(RowShape).background(Am.Button)
                    .clickable { state.playing = false; state.effectBrowserOpen = true }, contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(EffectIcons.Add, null, Modifier.size(18.dp), tint = Am.OnDark)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.add_effect), color = Am.OnDark, fontSize = 14.sp)
                    }
                }
            }
        }) { cards, at ->
            val current = at == index && instance != null
            val kept = remember { arrayOfNulls<XmlNode>(1) }
            if (current) kept[0] = instance
            val shown = kept[0]
            if (shown != null) {
                val shownDefinition = if (current) definition else catalog.find(shown["id"] ?: "")
                Column(Modifier.fillMaxSize().padding(6.5.dp).cardBounds(cards, cardKey(at, shown)).clip(RowShape).background(Am.Field)) {
                    EffectHeader(state, at, shown, shownDefinition)
                    Column(Modifier.weight(1f).fillMaxWidth().deskVerticalScroll()) {
                        if (shownDefinition != null) EffectSettings(state, layer, shown, shownDefinition, if (current) active else null, { active = it; pin = null })
                        else Text(shown["id"] ?: "", Modifier.padding(12.dp).dimmed(), color = Am.OnDark, fontSize = 12.sp)
                        if (current && puppet) PuppetSection(state, layer, index, shown, pin, pinField, { pinField = it }, adding, { pin = it; pinField = "position"; if (it != null) active = null }) { adding = it }
                        if (current && pathText) TextPathHint(state, layer, shown)
                    }
                }
            }
        }
    }
}

private val RowShape = RoundedCornerShape(5.dp)

private fun cardKey(index: Int, effect: XmlNode): String = "$index:${effect["id"]}"

@Composable
private fun CopyEffectsDialog(names: List<String>, onDismiss: () -> Unit, onCopy: (Set<Int>) -> Unit) {
    var ticked by remember { mutableStateOf(names.indices.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.effects_copy_effects)) },
        text = {
            Column(Modifier.heightIn(max = 280.dp).deskVerticalScroll()) {
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
    var help by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(37.dp).background(Am.CardHeader).deleteWhenHovered { state.editSelected { it.removeEffect(index) }; state.effectIndex = -1 }.clickable { state.effectIndex = -1 }.padding(start = 3.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(EffectIcons.Expanded, null, Modifier.padding(start = 8.dp).size(18.dp).alpha(0.9f), tint = Am.OnDark)
        Text(definition?.name ?: effect["id"]?.substringAfterLast('.') ?: "", Modifier.weight(1f).padding(start = 6.dp, end = 4.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
        if (definition != null && definition.presets.isNotEmpty()) Box {
            Text(stringResource(R.string.presets), Modifier.clip(RowShape).background(Color(0x33000000)).clickable { presets = true }.padding(horizontal = 9.dp, vertical = 5.dp),
                color = Am.OnDark, fontSize = 13.sp, maxLines = 1)
            Presence(presets) {
                MenuPopup(DropOrRise, { presets = false }) {
                    val catalog by state.engine.effects.collectAsState()
                    val context = LocalContext.current
                    val presetFavourites = remember { PresetFavouriteStore.get(context) }
                    Column(Modifier.heightIn(max = 360.dp).deskVerticalScroll()) {
                        definition.presets.forEach { preset ->
                            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).fillMaxHeight().clickable {
                                    state.editSelected { layer -> layer.withEffect(index) { old -> old.copy(children = definition.instance(preset, layer.endTime - layer.startTime).children) } }
                                    presets = false
                                }.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Thumbnail(catalog, definition.thumbnail(preset), Modifier.size(40.dp).clip(RowShape))
                                    Text(preset.name, Modifier.weight(1f).padding(start = 12.dp), color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
                                }
                                PresetStar(presetFavourites.contains(definition, preset)) { presetFavourites.toggle(definition, preset) }
                            }
                        }
                    }
                }
            }
        }
        if (definition != null) Box(Modifier.size(36.dp).clickable { help = true }, contentAlignment = Alignment.Center) {
            DeskTip(stringResource(R.string.effect_help)) { Icon(ExportIcons.Help, stringResource(R.string.effect_help), Modifier.size(22.dp), tint = Am.OnDark) }
        }
        Box(Modifier.size(36.dp).clickable { menu = true }, contentAlignment = Alignment.Center) {
            DeskTip(stringResource(R.string.effects_more)) { Icon(EffectIcons.MoreVert, stringResource(R.string.effects_more), Modifier.size(24.dp), tint = Am.OnDark) }
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
            DeskTip(stringResource(R.string.delete)) { Icon(ChromeIcons.Trash, stringResource(R.string.delete), Modifier.size(22.dp), tint = Am.OnDark) }
        }
    }
    if (help && definition != null) HelpScreen("effects/${definition.page}.html") { help = false }
}

@Composable
private fun EffectListRow(state: EditorState, reorder: Reorder, index: Int, count: Int, effect: XmlNode, definition: EffectDefinition?, cards: CardScope, move: (Int, Int) -> Unit) {
    val visible = effect["hidden"] != "true"
    val scope = rememberCoroutineScope()
    val tick = rememberHaptic(state)
    val fling = EffectSwipe.FLING_DP * LocalDensity.current.density
    val toStart = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1 else -1
    var offset by remember { mutableFloatStateOf(0f) }
    var shrink by remember { mutableFloatStateOf(0f) }
    var deleting by remember { mutableStateOf(false) }
    val back = remember { arrayOfNulls<Job>(1) }
    val open by remember { derivedStateOf { offset > 0f } }
    fun accept(way: Int): Boolean {
        if (way != toStart) return false
        back[0]?.cancel()
        return true
    }
    fun drag(dx: Float, width: Float) {
        val was = offset >= width * EffectSwipe.DELETE_SHARE
        offset = (offset + dx * toStart).coerceAtLeast(0f)
        if (!was && offset >= width * EffectSwipe.DELETE_SHARE) tick(HapticFeedbackType.GestureThresholdActivate)
    }
    fun release(velocity: Float?, width: Float) {
        if (velocity == null || !EffectSwipe.commits(offset, width, velocity * toStart, EffectSwipe.DELETE_SHARE, fling)) {
            back[0] = scope.launch { animate(offset, 0f, animationSpec = spring()) { v, _ -> offset = v } }
            return
        }
        deleting = true
        val target = effect
        scope.launch {
            animate(offset, width, animationSpec = tween(Motion.FastMs, easing = Motion.EaseIn)) { v, _ -> offset = v }
            animate(0f, 1f, animationSpec = tween(Motion.FastMs, easing = Motion.EaseOut)) { v, _ -> shrink = v }
            state.editSelected { l -> l.effects.indexOfFirst { it === target }.let { i -> if (i < 0) l else l.removeEffect(i) } }
            state.effectIndex = -1
            offset = 0f
            shrink = 0f
            deleting = false
        }
    }
    Box(Modifier.cardAway(cards, index).reorderSlot(reorder, index).graphicsLayer { clip = shrink > 0f }
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, (placeable.height * (1f - shrink)).roundToInt()) { placeable.place(0, 0) }
        }
        .padding(start = 6.5.dp, end = 6.5.dp, top = 6.5.dp).fillMaxWidth().height(37.dp).cardBounds(cards, cardKey(index, effect)).graphicsLayer { shape = RowShape; clip = offset > 0f }
        .deleteWhenHovered { if (!deleting) { state.editSelected { it.removeEffect(index) }; state.effectIndex = -1 } }
        .effectSwipe(reorder.held < 0 && !deleting, ::accept, ::drag, ::release)) {
        if (open) Box(Modifier.matchParentSize().background(DeleteRed), contentAlignment = Alignment.CenterEnd) {
            Icon(ChromeIcons.Trash, null, Modifier.padding(end = 12.dp).size(22.dp), tint = Color.White)
        }
        Row(Modifier.fillMaxSize().graphicsLayer { translationX = offset * toStart }.reorderLift(reorder, index, 5.dp)
            .clip(RowShape).background(Am.Field), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxHeight().alpha(if (definition?.available == true && effect["locallyApplied"] != "true") 1f else 0.35f)
                .clickable { state.effectIndex = index }.padding(start = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(EffectIcons.Expand, null, Modifier.size(18.dp).alpha(0.3f), tint = Am.OnDark)
                Text(definition?.name ?: effect["id"]?.substringAfterLast('.') ?: "", Modifier.padding(start = 6.dp), color = Am.OnDark, fontSize = 14.sp, maxLines = 1)
            }
            DeskTip(stringResource(R.string.effects_visibility)) {
                Icon(if (visible) EffectIcons.Eye else EffectIcons.EyeOff, stringResource(R.string.effects_visibility), Modifier.width(36.dp).clickable {
                    state.editSelected { it.withEffect(index) { node -> node.with("hidden", if (visible) "true" else null) } }
                }, tint = Am.OnDark)
            }
            Box(Modifier.width(36.dp).fillMaxHeight().clickable { state.effectIndex = index }.reorderGrip(reorder, index, count, move), Alignment.Center) {
                Icon(EffectIcons.Grip, stringResource(R.string.effects_reorder), Modifier.size(20.dp), tint = Am.OnDark)
            }
        }
    }
}

private val DeleteRed = Color(0xFFD93A3A)

@Composable
private fun Modifier.deleteWhenHovered(remove: () -> Unit): Modifier {
    if (!PlatformFeatures.desktop) return this
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val latest by rememberUpdatedState(remove)
    val hook = remember { { latest() } }
    DisposableEffect(hovered) {
        if (hovered) DeskDrop.removeHovered = hook
        onDispose { if (DeskDrop.removeHovered === hook) DeskDrop.removeHovered = null }
    }
    return hoverable(source)
}

@Composable
private fun EffectFrameButton(icon: ImageVector, description: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(24.dp), tint = Am.OnDark)
    }
}
