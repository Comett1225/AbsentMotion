package app.absentmotion.ui.editor

import app.absentmotion.platform.readAtMost
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.absentmotion.editor.selectedCuts
import app.absentmotion.editor.chosenLayers
import app.absentmotion.editor.deleteChosen
import app.absentmotion.editor.alignClipTimes
import app.absentmotion.editor.moveClipEdges
import app.absentmotion.editor.split
import app.absentmotion.model.canSplitAt
import app.absentmotion.model.trimEndAt
import app.absentmotion.model.trimStartAt
import app.absentmotion.editor.trimChosen
import app.absentmotion.model.cutBetween
import app.absentmotion.model.shownName
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
import androidx.compose.ui.window.PopupProperties
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.EffectClipboard
import app.absentmotion.editor.pasteEffectsOnChosen
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.StylePart
import app.absentmotion.editor.copiedLayerForStyle
import app.absentmotion.editor.pasteStyleOnChosen
import app.absentmotion.editor.removeAudioOfChosen
import app.absentmotion.editor.addCamera
import app.absentmotion.editor.addLight
import app.absentmotion.editor.addEmptyGroup
import app.absentmotion.editor.addModel
import app.absentmotion.editor.addNull
import app.absentmotion.editor.alignSelection
import app.absentmotion.editor.distributeSelection
import app.absentmotion.editor.groupLayersCentred
import app.absentmotion.editor.pasteStyle
import app.absentmotion.editor.setParent
import app.absentmotion.editor.setParentOfChosen
import app.absentmotion.editor.unparentChildrenOf
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Swap
import app.absentmotion.ui.theme.TilePress
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.presence
import app.absentmotion.ui.theme.pressScale
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import app.absentmotion.model.canAnimate
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.editor.addSvg
import app.absentmotion.library.ProjectStore
import app.absentmotion.media.MAX_MODEL_BYTES
import app.absentmotion.media.MODEL_HIGH_DETAIL_BUDGET
import app.absentmotion.media.MODEL_TRIANGLE_BUDGET
import app.absentmotion.media.ModelDetail
import app.absentmotion.media.triangles
import app.absentmotion.media.modelFiles
import app.absentmotion.media.modelFileName
import app.absentmotion.media.parseModel
import app.absentmotion.model.importSvg
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ItemShape = RoundedCornerShape(4.dp)

@Composable
private fun ObjectTile(label: String, icon: ImageVector, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit = {}) {
    val press = remember { MutableInteractionSource() }
    Box(modifier.pressScale(press, TilePress).alpha(if (enabled) 1f else DimAlpha).clip(ItemShape).background(Am.Button)
        .clickable(press, LocalIndication.current, enabled = enabled, onClick = onClick)) {
        Icon(icon, null, Modifier.align(Alignment.TopCenter).padding(top = 14.dp).size(24.dp), tint = Am.OnDark)
        Text(
            label, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp).padding(start = 3.dp, end = 3.dp, bottom = 4.dp).wrapContentHeight(Alignment.CenterVertically),
            color = Am.OnDark, fontSize = deskSp(11f), lineHeight = 13.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

internal class ObjectImport(val reading: Boolean, val svg: () -> Unit, val model: () -> Unit)

@Composable
internal fun rememberObjectImport(state: EditorState): ObjectImport {
    var reading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<Int?>(null) }
    val models = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            reading = true
            val (note, count) = try { importModel(context, state, uris) } finally { reading = false }
            if (count > MODEL_TRIANGLE_BUDGET && !ModelDetail.high) asking = count
            else note?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
    }
    asking?.let { count ->
        val simplified = stringResource(R.string.model_simplified, "$count", "$MODEL_HIGH_DETAIL_BUDGET")
        AlertDialog(
            onDismissRequest = { asking = null }, title = { Text(stringResource(R.string.model_detail_ask_title)) },
            text = { Text(stringResource(R.string.model_detail_ask_body, triangles(count), triangles(MODEL_TRIANGLE_BUDGET), triangles(MODEL_HIGH_DETAIL_BUDGET))) },
            dismissButton = { TextButton({ asking = null }) { Text(stringResource(R.string.model_detail_keep, triangles(MODEL_TRIANGLE_BUDGET)), color = Am.Accent) } },
            confirmButton = {
                TextButton({
                    asking = null
                    state.settings.highDetailModels = true
                    if (count > MODEL_HIGH_DETAIL_BUDGET) Toast.makeText(context, simplified, Toast.LENGTH_LONG).show()
                }) { Text(stringResource(R.string.model_detail_allow, triangles(MODEL_HIGH_DETAIL_BUDGET)), color = Am.Accent) }
            },
            containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark,
        )
    }
    val svg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            reading = true
            try { state.importSvgFile(context, uri) } finally { reading = false }
        }
    }
    return ObjectImport(reading, { svg.launch(arrayOf("image/svg+xml")) }) { models.launch(arrayOf("*/*")) }
}

@Composable
internal fun ObjectPage(state: EditorState, modifier: Modifier) {
    var picking by remember { mutableStateOf(false) }
    val import = rememberObjectImport(state)
    Swap(picking, modifier, rise = 0.dp) { if (picking) ElementPicker(state, Modifier.fillMaxSize()) else ObjectTiles(state, import.reading, { picking = true }, import.svg, import.model) }
}

@Composable
private fun ObjectTiles(state: EditorState, reading: Boolean, pick: () -> Unit, importSvg: () -> Unit, importModel: () -> Unit) {
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ObjectTile(stringResource(R.string.camera), PanelIcons.Camera, Modifier.size(70.dp, 80.dp)) { state.addCamera() }
            ObjectTile(stringResource(R.string.light_layer), PanelIcons.Brightness, Modifier.size(70.dp, 80.dp)) { state.addLight() }
            ObjectTile(stringResource(R.string.structure_null), ChromeIcons.NullObject, Modifier.size(70.dp, 80.dp)) { state.addNull() }
            ObjectTile(stringResource(R.string.structure_empty_group), SelectionIcons.Group, Modifier.size(70.dp, 80.dp)) { state.addEmptyGroup() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ObjectTile(stringResource(R.string.structure_asset_project), ChromeIcons.AssetProject, Modifier.size(70.dp, 80.dp), onClick = pick)
            ObjectTile(stringResource(R.string.structure_import_svg), ChromeIcons.ImportSvg, Modifier.size(70.dp, 80.dp), enabled = !reading, onClick = importSvg)
            ObjectTile(stringResource(R.string.structure_3d_model), ChromeIcons.Model3d, Modifier.size(70.dp, 80.dp), enabled = !reading, onClick = importModel)
        }
    }
}

private const val MaxSvgBytes = 16 shl 20

internal suspend fun EditorState.importSvgFile(context: Context, uri: Uri) {
    val host = scene
    val start = host.frameStart(frame)
    val end = host.endTimeFor(frame, settings.defaultLayerDuration * 1000)
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    val working = Toast.makeText(context, context.getString(R.string.structure_svg_importing), Toast.LENGTH_LONG).also { it.show() }
    val result = try {
        withContext(Dispatchers.IO) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment.orEmpty()
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readAtMost(MaxSvgBytes + 1) } ?: throw java.io.IOException("unreadable")
            if (bytes.size > MaxSvgBytes) throw java.io.IOException("too large")
            ensureActive()
            importSvg(String(bytes, Charsets.UTF_8), name.substringAfterLast('/').removeSuffix(".svg").removeSuffix(".SVG"), host, start, end)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("AMSvg", "cannot import $uri", e)
        say(context.getString(R.string.structure_svg_unreadable))
        return
    } catch (e: OutOfMemoryError) {
        Log.w("AMSvg", "too large to import $uri", e)
        say(context.getString(R.string.structure_svg_unreadable))
        return
    } finally {
        working.cancel()
    }
    val layer = result.layer
    if (layer == null) {
        say(context.getString(R.string.structure_svg_empty))
        return
    }
    addSvg(layer)
    if (result.skipped.isNotEmpty()) say(context.resources.getQuantityString(R.plurals.structure_svg_imported, result.shapes, result.shapes.toString(), result.skipped.joinToString(", ") { it.resolve(context) }))
}

private suspend fun importModel(context: Context, state: EditorState, picked: List<Uri>): Pair<String?, Int> =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val names = picked.map { uri ->
            modelFileName(resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: uri.lastPathSegment ?: "model")
        }
        val main = names.indexOfFirst { it.substringAfterLast('.').lowercase() in setOf("obj", "glb", "gltf") }
        if (main < 0) return@withContext context.getString(R.string.model_choose_file) to 0
        val folder = File(ProjectStore(context).mediaDir(state.projectId), "model-${System.currentTimeMillis()}")
        var added = false
        try {
            folder.mkdirs()
            var total = 0L
            val buffer = ByteArray(1 shl 16)
            picked.forEachIndexed { i, uri ->
                resolver.openInputStream(uri)?.use { input ->
                    File(folder, names[i]).outputStream().use { out ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_MODEL_BYTES) throw ModelTooLarge()
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            val uri = File(folder, names[main]).toURI().toString()
            val (bytes, open) = modelFiles(resolver, uri)
            val count = parseModel(names[main], bytes, open).triangleCount
            withContext(Dispatchers.Main) { state.addModel(uri, names[main]); added = true }
            val budget = ModelDetail.budget.value
            (if (count > budget) context.getString(R.string.model_simplified, "$count", "$budget") else null) to count
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!added) folder.deleteRecursively()
            throw e
        } catch (_: ModelTooLarge) {
            folder.deleteRecursively()
            context.getString(R.string.model_too_large) to 0
        } catch (e: Exception) {
            folder.deleteRecursively()
            context.getString(R.string.model_unreadable, e.message.orEmpty()) to 0
        } catch (_: OutOfMemoryError) {
            folder.deleteRecursively()
            context.getString(R.string.model_too_large) to 0
        }
    }

private class ModelTooLarge : Exception()

@Composable
internal fun MultiBar(state: EditorState, modifier: Modifier) {
    val layers = state.chosenLayers
    val cut = layers.takeIf { it.size == 2 }?.let { state.scene.cutBetween(it[0].id, it[1].id) }
    val cuts = if (layers.size == 2) listOfNotNull(cut?.let { it.first.id to it.second.id }) else state.scene.selectedCuts(layers.map { it.id }.toSet())
    var transition by remember { mutableStateOf<List<Pair<Long, Long>>?>(null) }
    var animation by remember { mutableStateOf<Set<Long>?>(null) }
    transition?.let { TransitionSheet(state, it) { transition = null } }
    animation?.let { AnimationSheet(state, it) { animation = null } }
    Row(modifier.padding(6.dp).clip(RoundedCornerShape(3.dp)).background(Am.PanelInset), verticalAlignment = Alignment.CenterVertically) {
        val end = layers.isNotEmpty() && state.timeMs >= layers.maxOf { it.endTime }
        val context = LocalContext.current
        val copied = EffectClipboard.effects
        var moreMenu by remember { mutableStateOf(false) }
        Box(Modifier.width(40.dp).fillMaxHeight()) {
            BarButton(PanelIcons.More, stringResource(R.string.structure_more_actions), Modifier.fillMaxSize(), layers.isNotEmpty()) { moreMenu = true }
            Presence(moreMenu) {
                MenuPopup(AboveLeft, { moreMenu = false }, TransformOrigin(0f, 1f)) {
                    val note = if (copied.isEmpty()) stringResource(R.string.effects_none_copied)
                        else pluralStringResource(R.plurals.effects_count, copied.size, copied.size.toString())
                    MenuRow(stringResource(R.string.effects_add_to_all)) {
                        moreMenu = false
                        state.playing = false
                        state.effectCategory = null
                        state.effectTargets = layers.map { it.id }.toSet()
                        state.effectBrowserOpen = true
                    }
                    MenuRow(stringResource(R.string.effects_paste_all), enabled = copied.isNotEmpty(), subText = note) {
                        moreMenu = false
                        pasteEffectsOnSelection(state, context)
                    }
                    MenuRow(stringResource(R.string.multi_animation)) {
                        moreMenu = false
                        state.playing = false
                        val ids = layers.map { it.id }.toSet()
                        if (layers.any { it.canAnimate }) animation = ids
                        else Toast.makeText(context, context.resources.getQuantityString(R.plurals.multi_applied_count, 0, "0"), Toast.LENGTH_SHORT).show()
                    }
                    MenuRow(stringResource(R.string.multi_presets)) {
                        moreMenu = false
                        state.playing = false
                        state.presetsOpen = true
                    }
                    val style = copiedLayerForStyle != null
                    for (part in StylePart.entries) MenuRow(stringResource(PasteLabels.getValue(part)), enabled = style) {
                        moreMenu = false
                        styleToast(context, state.pasteStyleOnChosen(setOf(part)))
                    }
                    MenuRow(stringResource(R.string.structure_paste_style), enabled = style) {
                        moreMenu = false
                        styleToast(context, state.pasteStyleOnChosen(StylePart.entries.toSet()))
                    }
                    MenuRow(stringResource(R.string.structure_remove_audio_all), enabled = layers.any { it.mediaFill?.video == true }) {
                        moreMenu = false
                        val n = state.removeAudioOfChosen()
                        Toast.makeText(context, context.resources.getQuantityString(R.plurals.structure_audio_removed, n, n.toString()), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        BarButton(if (end) ChromeIcons.SkipForward else ChromeIcons.SkipBack, stringResource(if (end) R.string.structure_move_ends else R.string.structure_move_starts), Modifier.width(40.dp).fillMaxHeight(), layers.isNotEmpty()) {
            state.moveClipEdges(end, false)
        }
        BarButton(PanelIcons.TrimLeft, stringResource(R.string.a11y_trim_start), Modifier.width(40.dp).fillMaxHeight(), layers.any { state.scene.trimStartAt(it, state.frame) != null }) { state.trimChosen(true) }
        BarButton(PanelIcons.Split, stringResource(R.string.timeline_split), Modifier.width(40.dp).fillMaxHeight(), layers.any { state.scene.canSplitAt(it, state.frame) }) { state.split() }
        BarButton(PanelIcons.TrimRight, stringResource(R.string.a11y_trim_end), Modifier.width(40.dp).fillMaxHeight(), layers.any { state.scene.trimEndAt(it, state.frame) != null }) { state.trimChosen(false) }
        Spacer(Modifier.weight(1f))
        BarButton(ChromeIcons.Transition, stringResource(R.string.transition_add_between), Modifier.width(40.dp).fillMaxHeight().alpha(if (cuts.isNotEmpty()) 1f else DimAlpha)) {
            if (cuts.isNotEmpty()) { state.playing = false; transition = cuts }
            else Toast.makeText(context, context.getString(when {
                layers.size > 2 -> R.string.multi_no_cut
                layers.size != 2 -> R.string.transition_needs_two
                layers.any { !it.canAnimate } -> R.string.transition_needs_visual
                else -> R.string.transition_needs_order
            }), Toast.LENGTH_LONG).show()
        }
        Spacer(Modifier.weight(1f))
        BarButton(SelectionIcons.Align[0], stringResource(R.string.structure_align_starts), Modifier.width(36.dp).fillMaxHeight(), layers.size > 1) { state.alignClipTimes(0) }
        BarButton(SelectionIcons.DistributeHorizontal, stringResource(R.string.structure_distribute_starts), Modifier.width(36.dp).fillMaxHeight(), layers.size > 2) { state.alignClipTimes(1) }
        BarButton(SelectionIcons.Align[2], stringResource(R.string.structure_align_ends), Modifier.width(36.dp).fillMaxHeight(), layers.size > 1) { state.alignClipTimes(2) }
    }
}

private val AlignNames = listOf(
    R.string.structure_align_left, R.string.structure_align_centre, R.string.structure_align_right,
    R.string.structure_align_top, R.string.structure_align_middle, R.string.structure_align_bottom,
)

internal val PasteLabels = mapOf(
    StylePart.ColorFill to R.string.structure_paste_fill_color, StylePart.BorderShadow to R.string.structure_paste_border_shadow,
    StylePart.Blending to R.string.structure_paste_blending,
)

@Composable
internal fun MultiTopBar(state: EditorState, modifier: Modifier) {
    val ids = MultiSelect.ids
    val scope = rememberCoroutineScope()
    var alignment by remember { mutableStateOf(false) }
    val tint = Am.OnAccent
    Row(modifier.background(Am.Accent), verticalAlignment = Alignment.CenterVertically) {
        SelectionButton(SelectionIcons.Cancel, stringResource(R.string.structure_cancel_selection), Modifier.width(40.dp).fillMaxHeight(), tint) { MultiSelect.clear() }
        if (!alignment) {
            val text = pluralStringResource(R.plurals.structure_layers_selected, ids.size, ids.size.toString())
            Text(text, Modifier.weight(1f), color = tint, maxLines = 1, autoSize = TextAutoSize.StepBased(10.sp, 14.sp))
            val cell = Modifier.width(40.dp).fillMaxHeight()
            SelectionButton(SelectionIcons.Group, stringResource(R.string.structure_group), cell, tint, ids.isNotEmpty()) { scope.launch { state.groupLayersCentred(ids) } }
            SelectionButton(SelectionIcons.Mask, stringResource(R.string.structure_group_mask), cell, tint, ids.size > 1) { scope.launch { state.groupLayersCentred(ids, "mask") } }
            SelectionButton(SelectionIcons.Exclude, stringResource(R.string.structure_group_exclude), cell, tint, ids.size > 1) { scope.launch { state.groupLayersCentred(ids, "exclude") } }
            var parentMenu by remember { mutableStateOf(false) }
            val linkLabel = stringResource(R.string.structure_link_parent)
            Box(
                cell.alpha(if (ids.isNotEmpty()) 1f else DimAlpha).semantics { contentDescription = linkLabel }
                    .clickable(enabled = ids.isNotEmpty()) { parentMenu = true },
                Alignment.Center,
            ) {
                LayerParentIcon(back = Am.Accent, ink = tint, dot = tint)
                Presence(parentMenu) { MultiParentMenu(state) { parentMenu = false } }
            }
            SelectionButton(ChromeIcons.Trash, stringResource(R.string.structure_delete_selected), cell, tint, ids.isNotEmpty()) { state.deleteChosen() }
            SelectionButton(SelectionIcons.Next, stringResource(R.string.structure_alignment_options), cell, tint) { alignment = true }
        } else {
            val cell = Modifier.weight(1f).fillMaxHeight()
            SelectionButton(SelectionIcons.Previous, stringResource(R.string.structure_group_options), cell, tint) { alignment = false }
            SelectionIcons.Align.forEachIndexed { edge, icon ->
                SelectionButton(icon, stringResource(AlignNames[edge]), cell, tint, ids.isNotEmpty()) { scope.launch { state.alignSelection(edge) } }
            }
            SelectionButton(SelectionIcons.DistributeVertical, stringResource(R.string.structure_distribute_vertical), cell, tint, ids.size > 2) { scope.launch { state.distributeSelection(false) } }
            SelectionButton(SelectionIcons.DistributeHorizontal, stringResource(R.string.structure_distribute_horizontal), cell, tint, ids.size > 2) { scope.launch { state.distributeSelection(true) } }
        }
    }
}

internal fun styleToast(context: Context, n: Int) {
    Toast.makeText(context, context.resources.getQuantityString(R.plurals.structure_style_pasted, n, n.toString()), Toast.LENGTH_SHORT).show()
}

internal fun pasteEffectsOnSelection(state: EditorState, context: Context) {
    val (pasted, skipped) = state.pasteEffectsOnChosen()
    val resources = context.resources
    val count = EffectClipboard.effects.size
    val text = if (pasted == 0) context.getString(R.string.effects_paste_no_target) else listOfNotNull(
        context.getString(R.string.effects_pasted_onto, resources.getQuantityString(R.plurals.effects_count, count, count.toString()), resources.getQuantityString(R.plurals.clips_count, pasted, pasted.toString())),
        resources.getQuantityString(R.plurals.effects_paste_skipped, skipped, skipped.toString()).takeIf { skipped > 0 },
    ).joinToString("\n")
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

@Composable
private fun SelectionButton(icon: ImageVector, description: String, modifier: Modifier, tint: Color, enabled: Boolean = true, action: () -> Unit) {
    Box(modifier.alpha(if (enabled) 1f else DimAlpha).clickable(enabled = enabled, onClick = action), Alignment.Center) {
        Icon(icon, description, Modifier.size(24.dp), tint = tint)
    }
}

@Composable
internal fun ChoiceList(items: List<String>, onDismiss: () -> Unit, below: Boolean = false, onPick: (Int) -> Unit) {
    val start = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else 0f
    Popup(if (below) DropOrRise else ChoicePosition, onDismiss, PopupProperties(focusable = true)) {
        Column(
            Modifier.presence(scaleFrom = 0.92f, origin = if (below) TransformOrigin(1f, 0f) else TransformOrigin(start, 1f)).width(220.dp).heightIn(max = 280.dp).clip(ItemShape).background(Am.PanelInset)
                .verticalScroll(rememberScrollState()),
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
internal fun ParentMenu(state: EditorState, below: Boolean = false, onDismiss: () -> Unit) {
    val layer = state.selected ?: return
    val others = state.scene.layers.filter { it.id != layer.id }.reversed()
    val current = layer.node["parent"]?.toLongOrNull()
    ChoiceList(
        listOf(stringResource(R.string.none)) + others.map { (if (it.id == current) "> " else "") + it.shownName() }, onDismiss, below,
    ) { i -> state.setParent(if (i == 0) null else others[i - 1].id) }
}

@Composable
internal fun MultiParentMenu(state: EditorState, below: Boolean = false, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val skippedText = stringResource(R.string.structure_link_parent_skipped)
    val ids = MultiSelect.ids
    val others = state.scene.layers.filter { it.id !in ids }.reversed()
    ChoiceList(listOf(stringResource(R.string.none)) + others.map { it.shownName() }, onDismiss, below) { i ->
        val skipped = state.setParentOfChosen(if (i == 0) null else others[i - 1].id)
        if (skipped > 0) Toast.makeText(context, skippedText, Toast.LENGTH_LONG).show()
    }
}

@Composable
internal fun PasteStyleMenu(state: EditorState, onDismiss: () -> Unit) {
    val parts = StylePart.entries
    ChoiceList(parts.map { stringResource(PasteLabels.getValue(it)) } + stringResource(R.string.structure_paste_style), onDismiss) { i ->
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
