package app.absentmotion.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import app.absentmotion.R
import app.absentmotion.editor.*
import app.absentmotion.library.ProjectInfo
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.SavedTemplate
import app.absentmotion.library.TemplateStore
import app.absentmotion.model.ShapeCatalog
import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.Hint
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.asImageBitmap
import app.absentmotion.ui.home.EmbedRequest
import app.absentmotion.ui.home.rememberFileBitmap
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.tutorial.tutorialTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Section(val title: Int, val rail: Int, val icon: ImageVector)

private val Sections = listOf(
    Section(R.string.add_tab_media, R.string.add_tab_media, ChromeIcons.MediaTab),
    Section(R.string.add_tab_audio, R.string.add_tab_audio, ChromeIcons.AudioTab),
    Section(R.string.add_tab_shape, R.string.add_tab_shape, ChromeIcons.ShapeTab),
    Section(R.string.add_quick_text, R.string.add_quick_text, ChromeIcons.TextTool),
    Section(R.string.add_tab_object_asset, R.string.desk_objects, ChromeIcons.ObjectTab),
    Section(R.string.add_tab_template, R.string.add_tab_template, ChromeIcons.TemplateTab),
    Section(R.string.desk_draw, R.string.desk_draw, ChromeIcons.Freehand),
)

internal val DeskRailHeight = (Sections.size * 54 + 8).dp

@Composable
internal fun DeskLibraryRail(selected: Int, expanded: Boolean, modifier: Modifier, select: (Int) -> Unit) {
    Column(modifier.background(Am.Panel).tutorialTarget("lib_rail").deskVerticalScroll().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Sections.forEachIndexed { index, section ->
            val title = stringResource(section.title)
            val active = expanded && selected == index
            val tint = if (active) Am.Accent else Am.OnDark
            Hint(title) {
                val source = remember { MutableInteractionSource() }
                val hovered by source.collectIsHoveredAsState()
                Column(Modifier.width(56.dp).height(54.dp).tutorialTarget(when (index) { 0 -> "lib_tab_media"; 1 -> "lib_tab_audio"; 3 -> "lib_tab_text"; else -> null }).clip(RoundedCornerShape(8.dp)).background(if (active) Am.Accent.copy(alpha = .16f) else if (hovered) Am.Field else androidx.compose.ui.graphics.Color.Transparent)
                    .hoverable(source).pointerHoverIcon(PointerIcon.Hand).clickable { select(index) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(section.icon, null, Modifier.size(22.dp), tint = tint)
                    Text(stringResource(section.rail), Modifier.fillMaxWidth().padding(top = 3.dp), color = if (active) Am.Accent else Am.OnDarkDim,
                        lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Clip, textAlign = TextAlign.Center,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(9.sp, 12.sp))
                }
            }
        }
    }
}

@Composable
internal fun DeskLibrary(state: EditorState, library: AddLibrary, tab: Int, modifier: Modifier, close: () -> Unit) {
    var captions by remember { mutableStateOf(false) }
    Column(modifier.background(Am.Panel)) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Sections[tab].title), Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DeskIcon(ChromeIcons.Close, deskShortcut(R.string.action_close, "${DeskMod}1"), action = close)
        }
        Box(Modifier.weight(1f).fillMaxWidth().tutorialTarget(when (tab) { 0 -> "lib_media"; 1 -> "lib_audio"; 3 -> "lib_text"; else -> null })) {
            when (tab) {
                0 -> DeskMedia(state, library)
                1 -> DeskAudio(state, library)
                2 -> DeskGrid(Modifier.fillMaxSize()) {
                    items(ShapeCatalog.all) { shape ->
                        DeskTile(state, stringResource(shape.title), { ShapeThumbnail(shape, state.engine, Modifier.size(44.dp)) }) { state.addShape(shape) }
                    }
                }
                3 -> DeskGrid(Modifier.fillMaxSize()) {
                    item { DeskIconTile(state, stringResource(R.string.add_quick_text), ChromeIcons.TextTool) { state.addText() } }
                    item { DeskIconTile(state, stringResource(R.string.add_quick_captions), ChromeIcons.Captions, direct = false) { captions = true } }
                }
                4 -> DeskObjects(state)
                5 -> DeskTemplates(state)
                else -> Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeskToolRow(ChromeIcons.Freehand, stringResource(R.string.add_quick_freehand_brush), stringResource(R.string.desk_draw_freehand_hint)) { state.startFreehand() }
                    DeskToolRow(ChromeIcons.VectorDrawing, stringResource(R.string.add_quick_pen_tool), stringResource(R.string.desk_draw_pen_hint)) { state.startVectorDrawing() }
                }
            }
        }
    }
    if (captions) CaptionsSheet(state) { captions = false }
}

@Composable
internal fun DeskGrid(modifier: Modifier, top: Any? = null, content: LazyGridScope.() -> Unit) {
    val grid = rememberLazyGridState()
    var shownTop by remember { mutableStateOf(top) }
    LaunchedEffect(top) { if (top != shownTop) { shownTop = top; grid.scrollToItem(0) } }
    LazyVerticalGrid(GridCells.Adaptive(80.dp), modifier.deskScrollbar(grid), grid, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun DeskTile(state: EditorState, label: String, picture: @Composable () -> Unit, reveal: (() -> Unit)? = null, direct: Boolean = true, detail: String? = null, remove: DeskRemove? = null, add: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    if (remove != null) {
        val removeNow = remember { { remove.run(false) } }
        DisposableEffect(hovered) {
            if (hovered) DeskDrop.removeHovered = removeNow
            onDispose { if (DeskDrop.removeHovered === removeNow) DeskDrop.removeHovered = null }
        }
    }
    val body: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (hovered) Am.Button else Am.Field).hoverable(source).pointerHoverIcon(PointerIcon.Hand)
                .then(if (direct) Modifier.deskDrag(state, label, add, picture) else Modifier).clickable(onClick = add).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)), Alignment.Center) { picture() }
            Column(Modifier.fillMaxWidth().height(32.dp).padding(top = 4.dp), Arrangement.Center) {
                Text(label, Modifier.fillMaxWidth(), color = Am.OnDark, fontSize = 12.sp, lineHeight = 14.sp, maxLines = if (detail == null) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (detail != null) Text(detail, Modifier.fillMaxWidth(), color = Am.OnDarkDim, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
    DeskTip(if (detail != null) "$label · $detail" else label) { if (direct) DeskContext(add, reveal, remove) { body() } else body() }
}

@Composable
internal fun DeskToolRow(icon: ImageVector, title: String, description: String, add: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (hovered) Am.Button else Am.Field).hoverable(source).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = add).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Am.Panel), Alignment.Center) { Icon(icon, null, Modifier.size(26.dp), tint = Am.OnDark) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Am.OnDark, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(description, Modifier.padding(top = 2.dp), color = Am.OnDarkDim, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun DeskIconTile(state: EditorState, label: String, icon: ImageVector, direct: Boolean = true, add: () -> Unit) {
    DeskTile(state, label, { Icon(icon, null, Modifier.size(36.dp), tint = Am.OnDark) }, direct = direct, add = add)
}

@Composable
internal fun Modifier.deskDrag(state: EditorState, label: String, add: () -> Unit, picture: @Composable () -> Unit): Modifier {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val latest by rememberUpdatedState(add)
    val latestPicture by rememberUpdatedState(picture)
    val live = remember { BooleanArray(1) }
    DisposableEffect(Unit) { onDispose { if (live[0]) DeskDrop.clear() } }
    return onGloballyPositioned { origin = it.positionInWindow() }.pointerInput(state, label) {
        detectDragGestures(onDragStart = { live[0] = true; DeskDrop.item = DropItem(label) { latest() }; DeskGhost.picture = latestPicture; DeskDrop.at = origin + it },
            onDragCancel = { live[0] = false; DeskDrop.clear() }, onDragEnd = {
                try { finishDeskDrop(state, DeskDrop.at) { latest() } } finally { live[0] = false; DeskDrop.clear() }
            }) { change, _ -> DeskDrop.at = origin + change.position; change.consume() }
    }
}

private class AtPointer(private val at: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(
        (anchorBounds.left + at.x).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (anchorBounds.top + at.y).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}

internal class DeskRemove(val inUse: Boolean, val folder: String?, val run: (unlinkFolder: Boolean) -> Unit)

@Composable
internal fun DeskContext(add: () -> Unit, reveal: (() -> Unit)? = null, remove: DeskRemove? = null, menu: (@Composable (close: () -> Unit) -> Unit)? = null, content: @Composable () -> Unit) {
    var at by remember { mutableStateOf<IntOffset?>(null) }
    Box(Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && (event.buttons.isSecondaryPressed || mac && event.keyboardModifiers.isCtrlPressed)) {
                    val position = event.changes.first().position
                    at = IntOffset(position.x.toInt(), position.y.toInt())
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }) {
        content()
        at?.let { point ->
            MenuPopup(remember(point) { AtPointer(point) }, { at = null }) {
                if (menu != null) menu { at = null } else {
                MenuRow(stringResource(R.string.desk_add)) { at = null; add() }
                if (reveal != null) MenuRow(stringResource(R.string.desk_reveal_file)) { at = null; reveal() }
                if (remove != null) {
                    MenuRow(stringResource(R.string.desk_remove_media), subText = stringResource(if (remove.inUse) R.string.desk_remove_media_used else R.string.desk_remove_media_note), divider = true) { at = null; remove.run(false) }
                    if (remove.folder != null) MenuRow(stringResource(R.string.desk_unlink_folder, remove.folder)) { at = null; remove.run(true) }
                }
                }
            }
        }
    }
}

internal object DeskGhost { var picture by mutableStateOf<(@Composable () -> Unit)?>(null) }

@Composable
private fun DeskObjects(state: EditorState) {
    var picking by remember { mutableStateOf(false) }
    val import = rememberObjectImport(state)
    if (picking) Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            DeskIcon(ChromeIcons.Back, stringResource(R.string.topbar_back)) { picking = false }
            Text(stringResource(R.string.structure_asset_project), color = Am.OnDarkDim, fontSize = 13.sp, maxLines = 1)
        }
        DeskElementPicker(state)
    } else DeskGrid(Modifier.fillMaxSize()) {
        item { DeskIconTile(state, stringResource(R.string.camera), PanelIcons.Camera) { state.addCamera() } }
        item { DeskIconTile(state, stringResource(R.string.light_layer), PanelIcons.Brightness) { state.addLight() } }
        item { DeskIconTile(state, stringResource(R.string.structure_null), ChromeIcons.NullObject) { state.addNull() } }
        item { DeskIconTile(state, stringResource(R.string.structure_empty_group), SelectionIcons.Group) { state.addEmptyGroup() } }
        item { DeskIconTile(state, stringResource(R.string.structure_asset_project), ChromeIcons.AssetProject, direct = false) { picking = true } }
        item { DeskIconTile(state, stringResource(R.string.structure_import_svg), ChromeIcons.ImportSvg, direct = false) { if (!import.reading) import.svg() } }
        item { DeskIconTile(state, stringResource(R.string.structure_3d_model), ChromeIcons.Model3d, direct = false) { if (!import.reading) import.model() } }
    }
}

@Composable
private fun DeskElementPicker(state: EditorState) {
    val context = LocalContext.current
    val store = remember { ProjectStore(context.applicationContext) }
    val scope = LocalEditorScope.current
    var items by remember { mutableStateOf<List<ProjectInfo>?>(null) }
    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) {
            store.list().filter { it.id != state.projectId }.sortedWith(compareByDescending<ProjectInfo> { it.isElement }.thenByDescending { it.modified })
        }
    }
    var search by remember { mutableStateOf("") }
    val query = debounced(search)
    val list = items
    val shown = remember(list, query) { list?.filter { matchesSearch(query, it.title) } }
    Column(Modifier.fillMaxSize()) {
        SearchField(search, { search = it }, Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().height(36.dp), hint = stringResource(R.string.element_search_hint))
        val body = Modifier.fillMaxWidth().weight(1f)
        when {
            list == null || shown == null -> Box(body)
            list.isEmpty() -> Box(body.padding(16.dp), Alignment.Center) { Text(stringResource(R.string.your_asset_collection_is_empty), color = Am.OnDarkDim, fontSize = 13.sp, textAlign = TextAlign.Center) }
            shown.isEmpty() -> NoMatches(query, R.string.search_no_assets_or_projects, body)
            else -> DeskGrid(body) {
                items(shown, key = { it.id }) { info ->
                    val thumbnail by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, info.id, info.modified) {
                        value = withContext(Dispatchers.IO) { info.thumbnail?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
                    }
                    val add = { if (!state.addingMedia) scope.launch { state.addingMedia = true; try { state.addElement(store, info.id, link = info.isElement) } finally { state.addingMedia = false } }; Unit }
                    DeskTile(state, info.title, {
                        thumbnail?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: Icon(ChromeIcons.AssetProject, null, Modifier.size(36.dp), tint = Am.OnDarkDim)
                    }, add = add)
                }
            }
        }
    }
}

@Composable
private fun DeskTemplates(state: EditorState) {
    val context = LocalContext.current
    val saved by produceState<List<SavedTemplate>?>(null) { value = withContext(Dispatchers.IO) { TemplateStore(context).list() } }
    val list = saved ?: return
    if (list.isEmpty()) Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), Arrangement.spacedBy(8.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
        Icon(ChromeIcons.TemplateTab, null, Modifier.size(40.dp), tint = Am.OnDarkDim)
        Text(stringResource(R.string.desk_templates_empty), color = Am.OnDark, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.desk_templates_empty_hint), color = Am.OnDarkDim, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.height(36.dp).clip(RoundedCornerShape(8.dp)).background(Am.Accent).pointerHoverIcon(PointerIcon.Hand).clickable { state.playing = false; state.exportOpen = true }.padding(horizontal = 16.dp),
            Alignment.Center,
        ) { Text(stringResource(R.string.desk_templates_export), color = Am.OnAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
    } else DeskGrid(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { t ->
            val bitmap = rememberFileBitmap(t.thumbnail, t.modified)
            DeskTile(state, t.title, {
                bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Icon(ChromeIcons.TemplateTab, null, Modifier.size(36.dp), tint = Am.OnDarkDim)
            }) { EmbedRequest.templateId = t.id }
        }
    }
}
