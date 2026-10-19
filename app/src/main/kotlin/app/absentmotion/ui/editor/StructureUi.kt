package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.absentmotion.editor.chosenLayers
import app.absentmotion.editor.deleteChosen
import app.absentmotion.editor.alignClipTimes
import app.absentmotion.editor.moveClipEdges
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.StylePart
import app.absentmotion.editor.addCamera
import app.absentmotion.editor.addEmptyGroup
import app.absentmotion.editor.addNull
import app.absentmotion.editor.alignSelection
import app.absentmotion.editor.distributeSelection
import app.absentmotion.editor.groupLayers
import app.absentmotion.editor.pasteStyle
import app.absentmotion.editor.setParent
import app.absentmotion.editor.unparentChildrenOf
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch

private val ItemShape = RoundedCornerShape(4.dp)

@Composable
private fun TextButton(label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit = {}) {
    Box(
        modifier.alpha(if (enabled) 1f else DimAlpha).clip(ItemShape).background(Am.Button).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Am.OnDark, fontSize = 12.sp, maxLines = 2, softWrap = true, lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
}

@Composable
internal fun ObjectPage(state: EditorState, modifier: Modifier) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        ElementPicker(state, modifier)
        return
    }
    Row(modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton("Camera", Modifier.size(70.dp, 80.dp)) { state.addCamera() }
        TextButton("Null", Modifier.size(70.dp, 80.dp)) { state.addNull() }
        TextButton("Empty Group", Modifier.size(70.dp, 80.dp)) { state.addEmptyGroup() }
        TextButton("Asset / Project", Modifier.size(70.dp, 80.dp)) { picking = true }
    }
}

@Composable
internal fun MultiBar(state: EditorState, modifier: Modifier) {
    val layers = state.chosenLayers
    Row(modifier.padding(6.dp).clip(RoundedCornerShape(3.dp)).background(Am.PanelInset), verticalAlignment = Alignment.CenterVertically) {
        val end = layers.isNotEmpty() && state.timeMs >= layers.maxOf { it.endTime }
        BarButton(if (end) PanelIcons.TrimRight else PanelIcons.TrimLeft, if (end) "Extend clip ends to playhead" else "Extend clip starts to playhead", Modifier.width(60.dp).fillMaxHeight(), layers.isNotEmpty()) {
            state.moveClipEdges(end, true)
        }
        BarButton(if (end) ChromeIcons.SkipForward else ChromeIcons.SkipBack, if (end) "Move clip ends to playhead" else "Move clip starts to playhead", Modifier.width(60.dp).fillMaxHeight(), layers.isNotEmpty()) {
            state.moveClipEdges(end, false)
        }
        Spacer(Modifier.weight(1f))
        BarButton(SelectionIcons.Align[0], "Align clip starts", Modifier.width(40.dp).fillMaxHeight(), layers.size > 1) { state.alignClipTimes(0) }
        BarButton(SelectionIcons.DistributeHorizontal, "Distribute clip starts", Modifier.width(40.dp).fillMaxHeight(), layers.size > 2) { state.alignClipTimes(1) }
        BarButton(SelectionIcons.Align[2], "Align clip ends", Modifier.width(40.dp).fillMaxHeight(), layers.size > 1) { state.alignClipTimes(2) }
    }
}

@Composable
internal fun MultiTopBar(state: EditorState, modifier: Modifier) {
    val ids = MultiSelect.ids
    val scope = rememberCoroutineScope()
    var alignment by remember { mutableStateOf(false) }
    val tint = Am.OnAccent
    Row(modifier.background(Am.Accent), verticalAlignment = Alignment.CenterVertically) {
        SelectionButton(SelectionIcons.Cancel, "Cancel selection", Modifier.width(40.dp).fillMaxHeight(), tint) { MultiSelect.clear() }
        if (!alignment) {
            val text = if (ids.size == 1) "1 layer selected" else "${ids.size} layers selected"
            Text(text, Modifier.weight(1f), color = tint, fontSize = 14.sp, maxLines = 1)
            val cell = Modifier.width(40.dp).fillMaxHeight()
            SelectionButton(SelectionIcons.Group, "Group", cell, tint, ids.isNotEmpty()) { state.groupLayers(ids) }
            SelectionButton(SelectionIcons.Mask, "Group & Mask", cell, tint, ids.size > 1) { state.groupLayers(ids, "mask") }
            SelectionButton(SelectionIcons.Exclude, "Group & Exclude", cell, tint, ids.size > 1) { state.groupLayers(ids, "exclude") }
            SelectionButton(ChromeIcons.Trash, "Delete selected layers", cell, tint, ids.isNotEmpty()) { state.deleteChosen() }
            SelectionButton(SelectionIcons.Next, "Alignment options", cell, tint) { alignment = true }
        } else {
            val cell = Modifier.weight(1f).fillMaxHeight()
            SelectionButton(SelectionIcons.Previous, "Group options", cell, tint) { alignment = false }
            SelectionIcons.Align.forEachIndexed { edge, icon ->
                SelectionButton(icon, icon.name, cell, tint, ids.isNotEmpty()) { scope.launch { state.alignSelection(edge) } }
            }
            SelectionButton(SelectionIcons.DistributeVertical, "Distribute vertically", cell, tint, ids.size > 2) { scope.launch { state.distributeSelection(false) } }
            SelectionButton(SelectionIcons.DistributeHorizontal, "Distribute horizontally", cell, tint, ids.size > 2) { scope.launch { state.distributeSelection(true) } }
        }
    }
}

@Composable
private fun SelectionButton(icon: ImageVector, description: String, modifier: Modifier, tint: Color, enabled: Boolean = true, action: () -> Unit) {
    Box(modifier.alpha(if (enabled) 1f else DimAlpha).clickable(enabled = enabled, onClick = action), Alignment.Center) {
        Icon(icon, description, Modifier.size(24.dp), tint = tint)
    }
}

@Composable
internal fun ChoiceList(items: List<String>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    Popup(ChoicePosition, onDismissRequest = onDismiss) {
        Column(
            Modifier.width(220.dp).heightIn(max = 280.dp).clip(ItemShape).background(Am.PanelInset).verticalScroll(rememberScrollState()),
        ) {
            items.forEachIndexed { i, label ->
                Box(Modifier.fillMaxWidth().height(40.dp).clickable { onPick(i); onDismiss() }.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                    Text(label, color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun ParentMenu(state: EditorState, onDismiss: () -> Unit) {
    val layer = state.selected ?: return
    val others = state.scene.layers.filter { it.id != layer.id }.reversed()
    val current = layer.node["parent"]?.toLongOrNull()
    ChoiceList(
        listOf("None") + others.map { (if (it.id == current) "> " else "") + it.label.ifEmpty { it.kind } }, onDismiss,
    ) { i -> state.setParent(if (i == 0) null else others[i - 1].id) }
}

@Composable
internal fun PasteStyleMenu(state: EditorState, onDismiss: () -> Unit) {
    val parts = StylePart.entries
    ChoiceList(parts.map { it.label } + "Paste Style", onDismiss) { i ->
        state.pasteStyle(if (i < parts.size) setOf(parts[i]) else parts.toSet())
    }
}

internal object ChoicePosition : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                                   layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize): androidx.compose.ui.unit.IntOffset {
        val desired = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) anchorBounds.right - popupContentSize.width else anchorBounds.left
        return androidx.compose.ui.unit.IntOffset(
            desired.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.bottom - popupContentSize.height).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
