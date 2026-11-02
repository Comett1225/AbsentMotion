package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.EffectClipboard
import app.absentmotion.editor.StylePart
import app.absentmotion.editor.alignClipTimes
import app.absentmotion.editor.chosenLayers
import app.absentmotion.editor.copiedLayerForStyle
import app.absentmotion.editor.moveClipEdges
import app.absentmotion.editor.pasteStyleOnChosen
import app.absentmotion.editor.removeAudioOfChosen
import app.absentmotion.editor.selectedCuts
import app.absentmotion.model.canAnimate
import app.absentmotion.model.cutBetween
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Toast
import app.absentmotion.ui.theme.Am

@Composable
internal fun DeskToolTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, tip: String? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(modifier) {
        DeskTip(tip ?: "") {
            Column(
                Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(4.dp)).background(if (hovered && enabled) Am.Field else Am.Button).hoverable(source)
                    .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick) else Modifier).alpha(if (enabled) 1f else DimAlpha)
                    .padding(horizontal = 6.dp),
                Arrangement.Center, Alignment.CenterHorizontally,
            ) {
                Icon(icon, null, Modifier.size(24.dp), tint = Am.OnDark)
                Text(label, Modifier.fillMaxWidth().padding(top = 4.dp), color = Am.OnDarkDim, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

private class MultiTile(val icon: ImageVector, val label: Int, val enabled: Boolean = true, val reason: String? = null, val tip: Int? = null, val action: () -> Unit)

@Composable
internal fun DeskMulti(state: EditorState, modifier: Modifier) {
    val layers = state.chosenLayers
    val context = LocalContext.current
    val cut = layers.takeIf { it.size == 2 }?.let { state.scene.cutBetween(it[0].id, it[1].id) }
    val cuts = if (layers.size == 2) listOfNotNull(cut?.let { it.first.id to it.second.id }) else state.scene.selectedCuts(layers.map { it.id }.toSet())
    var transition by remember { mutableStateOf<List<Pair<Long, Long>>?>(null) }
    var animation by remember { mutableStateOf<Set<Long>?>(null) }
    transition?.let { TransitionSheet(state, it) { transition = null } }
    animation?.let { AnimationSheet(state, it) { animation = null } }
    val some = layers.isNotEmpty()
    val end = some && state.timeMs >= layers.maxOf { it.endTime }
    val copied = EffectClipboard.effects.isNotEmpty()
    val style = copiedLayerForStyle != null
    val noStyle = stringResource(R.string.desk_style_not_copied)
    val noEffects = stringResource(R.string.effects_none_copied)
    val noSound = stringResource(R.string.desk_no_video_sound)
    val needsTwo = stringResource(R.string.desk_needs_two_layers)
    val needsThree = stringResource(R.string.desk_needs_three_layers)
    val noTransition = stringResource(when {
        layers.size > 2 -> R.string.multi_no_cut
        layers.size != 2 -> R.string.transition_needs_two
        layers.any { !it.canAnimate } -> R.string.transition_needs_visual
        else -> R.string.transition_needs_order
    })
    fun paste(parts: Set<StylePart>) = styleToast(context, state.pasteStyleOnChosen(parts))
    val tiles = listOf(
        MultiTile(PanelIcons.Effects, R.string.effects_add_to_all, some) {
            state.playing = false
            state.effectCategory = null
            state.effectTargets = layers.map { it.id }.toSet()
            state.effectBrowserOpen = true
        },
        MultiTile(PanelIcons.Effects, R.string.effects_paste_all, some && copied, if (copied) null else noEffects) { pasteEffectsOnSelection(state, context) },
        MultiTile(PanelIcons.Animate, R.string.multi_animation, some) {
            state.playing = false
            val ids = layers.map { it.id }.toSet()
            if (layers.any { it.canAnimate }) animation = ids
            else Toast.makeText(context, context.resources.getQuantityString(R.plurals.multi_applied_count, 0, "0"), Toast.LENGTH_SHORT).show()
        },
        MultiTile(PanelIcons.Star, R.string.multi_presets, some) { state.playing = false; state.presetsOpen = true },
        MultiTile(PanelIcons.PaintBucket, R.string.structure_paste_fill_color, some && style, if (style) null else noStyle) { paste(setOf(StylePart.ColorFill)) },
        MultiTile(PanelIcons.BorderShadow, R.string.structure_paste_border_shadow, some && style, if (style) null else noStyle) { paste(setOf(StylePart.BorderShadow)) },
        MultiTile(PanelIcons.Blending, R.string.structure_paste_blending, some && style, if (style) null else noStyle) { paste(setOf(StylePart.Blending)) },
        MultiTile(ChromeIcons.CopyPaste, R.string.structure_paste_style, some && style, if (style) null else noStyle) { paste(StylePart.entries.toSet()) },
        MultiTile(PanelIcons.AudioMuted, R.string.structure_remove_audio_all, layers.any { it.mediaFill?.video == true }, noSound) {
            val n = state.removeAudioOfChosen()
            Toast.makeText(context, context.resources.getQuantityString(R.plurals.structure_audio_removed, n, n.toString()), Toast.LENGTH_SHORT).show()
        },
        MultiTile(ChromeIcons.Transition, R.string.transition_title, cuts.isNotEmpty(), noTransition, R.string.transition_add_between) { state.playing = false; transition = cuts },
        MultiTile(if (end) ChromeIcons.SkipForward else ChromeIcons.SkipBack, if (end) R.string.structure_move_ends else R.string.structure_move_starts, some) { state.moveClipEdges(end, false) },
        MultiTile(SelectionIcons.Align[0], R.string.structure_align_starts, layers.size > 1, needsTwo) { state.alignClipTimes(0) },
        MultiTile(SelectionIcons.DistributeHorizontal, R.string.structure_distribute_starts, layers.size > 2, needsThree) { state.alignClipTimes(1) },
        MultiTile(SelectionIcons.Align[2], R.string.structure_align_ends, layers.size > 1, needsTwo) { state.alignClipTimes(2) },
    )
    Column(modifier.deskVerticalScroll().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                pair.forEach { tile ->
                    val label = stringResource(tile.label)
                    DeskToolTile(tile.icon, label, Modifier.weight(1f), tile.enabled, if (!tile.enabled) tile.reason ?: label else tile.tip?.let { stringResource(it) } ?: label, tile.action)
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
