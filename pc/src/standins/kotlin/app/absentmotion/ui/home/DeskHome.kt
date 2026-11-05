package app.absentmotion.ui.home

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.i18n.text
import app.absentmotion.library.AppSettings
import app.absentmotion.library.ImportReport
import app.absentmotion.library.PresetStore
import app.absentmotion.library.ProjectInfo
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.SavedPreset
import app.absentmotion.library.SavedTemplate
import app.absentmotion.library.TemplateStore
import app.absentmotion.model.Scene
import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.Hint
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.asImageBitmap
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.NoMatches
import app.absentmotion.ui.editor.SearchField
import app.absentmotion.ui.editor.SizedBox
import app.absentmotion.ui.editor.TextIcons
import app.absentmotion.ui.editor.debounced
import app.absentmotion.ui.editor.matchesSearch
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.enterOnce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val ContentMax = 1920.dp
private val CardMin = 240.dp
private val CardMax = 400.dp
private val TileGap = 16.dp
private val GridPad = 24.dp

internal fun deskKey(letter: String): String = app.absentmotion.desk.DeskOs.commandKey + letter

internal enum class DeskSection { Projects, Assets, Templates, Tools }

@Composable
internal fun DeskHome(
    store: ProjectStore,
    settings: AppSettings,
    onOpen: (String) -> Unit,
    onExport: (String) -> Unit,
    imported: List<ImportReport>,
    onImportShown: () -> Unit,
    onImportFiles: (List<Uri>) -> Unit,
    onTemplate: (String) -> Unit,
    onTemplateProject: (Scene) -> Unit,
    onHistory: (ProjectInfo) -> Unit,
    onSettings: () -> Unit,
    changes: Int,
    newTick: Int,
    onCollect: ((String) -> Unit)? = null,
) {
    var projects by remember { mutableStateOf<List<ProjectInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var section by remember { mutableStateOf(DeskSection.Projects) }
    var search by remember { mutableStateOf("") }
    val query = debounced(search)
    var creating by remember { mutableStateOf(false) }
    var opening by remember { mutableIntStateOf(0) }
    var initialElement by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ProjectInfo?>(null) }
    var duplicating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val newProject = { initialElement = section == DeskSection.Assets; opening++; creating = true }
    val firstTick = remember { newTick }
    LaunchedEffect(newTick) { if (newTick != firstTick) newProject() }
    LaunchedEffect(version, changes) { projects = withContext(Dispatchers.IO) { store.list(includeDamaged = true) }; loaded = true }
    val toReport = imported.filter { it.sections.isNotEmpty() }
    LaunchedEffect(imported) {
        if (imported.isEmpty()) return@LaunchedEffect
        section = when { imported.all { it.template } -> DeskSection.Templates; imported.all { it.element } -> DeskSection.Assets; else -> DeskSection.Projects }
        version++
        if (toReport.isEmpty()) onImportShown()
    }
    val shown = remember(projects, section, query) { projects.filter { it.isElement == (section == DeskSection.Assets) && matchesSearch(query, it.title) } }
    val import = {
        val files = app.absentmotion.desk.DeskFiles.chooseFiles(media = false, multiple = true)
        if (files.isNotEmpty()) onImportFiles(files.map(Uri::fromFile))
    }
    val duplicate: (String) -> Unit = { id ->
        if (!duplicating) {
            duplicating = true
            app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.home_duplicating), app.absentmotion.platform.Toast.LENGTH_SHORT).show()
            scope.launch {
                try {
                    val copied = withContext(Dispatchers.IO) { runCatching { store.duplicate(id) }.isSuccess }
                    if (!copied) app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.home_create_project_failed), app.absentmotion.platform.Toast.LENGTH_LONG).show()
                    version++
                } finally { duplicating = false }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Am.HomeBg)) {
        Column(Modifier.fillMaxSize()) {
            DeskHeader(section, { section = it; if (it == DeskSection.Templates) selected = null }, search, { search = it }, templates = section == DeskSection.Templates, newProject, import, onSettings)
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.TopCenter) {
                Box(Modifier.widthIn(max = ContentMax).fillMaxSize()) {
                    when (section) {
                        DeskSection.Templates -> DeskTemplates(version, query, onTemplate, onTemplateProject)
                        DeskSection.Tools -> ToolsTab(Modifier, store, settings) { id -> version++; onOpen(id) }
                        else -> ProjectsPane(
                            shown, loaded, section == DeskSection.Assets, query, settings, version, selected, { selected = it },
                            onOpen = { info -> if (info.damaged) onHistory(info) else onOpen(info.id) },
                            onExport = onExport, onDuplicate = duplicate, onHistory = onHistory, onDelete = { deleting = it },
                            onNew = newProject, onImport = import, onCollect = onCollect,
                        )
                    }
                }
            }
        }
        if (creating) DeskNewProject(store, settings, projects.map { it.title }, initialElement, opening, onClose = { creating = false }, onOpen = { id -> version++; onOpen(id) })
        if (toReport.isNotEmpty()) DeskImportReport(toReport, onImportShown, onOpen = { onImportShown(); toReport.first().let { if (it.template) onTemplate(it.id) else onOpen(it.id) } })
        deleting?.let { target ->
            val users by produceState(emptyList<String>(), target.id) { if (target.isElement) value = withContext(Dispatchers.IO) { store.linkedBy(target.id) } }
            DeskConfirm(
                stringResource(if (target.isElement) R.string.home_delete_asset_title else R.string.home_delete_project_title), target.title.ifBlank { null },
                users.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.home_delete_asset_used, it.take(6).joinToString(", ") + if (it.size > 6) ", …" else "") },
                stringResource(R.string.delete), danger = true, onCancel = { deleting = null },
            ) {
                deleting = null
                if (selected == target.id) selected = null
                scope.launch { withContext(Dispatchers.IO) { store.delete(target.id, context.getExternalFilesDir(null)?.resolve("ai")) }; version++ }
            }
        }
    }
}

@Composable
private fun DeskHeader(
    section: DeskSection, onSection: (DeskSection) -> Unit, search: String, onSearch: (String) -> Unit, templates: Boolean,
    onNew: () -> Unit, onImport: () -> Unit, onSettings: () -> Unit,
) = SizedBox(Modifier.fillMaxWidth()) { maxWidth, _ ->
    val compact = maxWidth < 1180.dp
    val iconNew = maxWidth < 900.dp
    val tight = maxWidth < 780.dp
    val fixed = if (tight) 510.dp else 456.dp + (if (compact) 72.dp else 216.dp) + (if (iconNew) 36.dp else 150.dp) + 32.dp
    val searchWidth = (maxWidth - fixed).coerceIn(if (tight) 110.dp else 140.dp, 300.dp)
    Box(Modifier.fillMaxWidth(), Alignment.TopCenter) {
        Row(Modifier.widthIn(max = ContentMax).fillMaxWidth().height(68.dp).padding(horizontal = if (tight) 16.dp else GridPad), Arrangement.spacedBy(if (tight) 6.dp else 8.dp), Alignment.CenterVertically) {
            DeskTab(stringResource(R.string.home_tab_projects), section == DeskSection.Projects, tight) { onSection(DeskSection.Projects) }
            DeskTab(stringResource(R.string.home_tab_assets), section == DeskSection.Assets, tight) { onSection(DeskSection.Assets) }
            DeskTab(stringResource(R.string.home_tab_templates), section == DeskSection.Templates, tight) { onSection(DeskSection.Templates) }
            DeskTab(stringResource(R.string.home_tab_tools), section == DeskSection.Tools, tight) { onSection(DeskSection.Tools) }
            Spacer(Modifier.weight(1f))
            SearchField(
                search, onSearch, Modifier.width(searchWidth).height(36.dp),
                hint = stringResource(if (searchWidth < 210.dp) R.string.home_search else if (templates) R.string.home_search_templates_hint else R.string.home_search_hint),
                background = Am.Card, content = Am.Navy, faint = Am.HomeDim,
            )
            if (compact) DeskIconButton(HomeIcons.Upload, stringResource(R.string.desk_import), onImport)
            else DeskButton(stringResource(R.string.desk_import), onImport, icon = HomeIcons.Upload)
            Hint("${stringResource(R.string.home_new_project)} (${deskKey("N")})") {
                if (iconNew) DeskIconButton(HomeIcons.Plus, stringResource(R.string.home_new_project), onNew, tooltip = null, tint = Am.OnAccent, modifier = Modifier.background(Am.Accent, RoundedCornerShape(10.dp)))
                else DeskButton(stringResource(R.string.home_new_project), onNew, primary = true, icon = HomeIcons.Plus)
            }
            Hint("${stringResource(R.string.settings)} (${deskKey(",")})") {
                if (compact) DeskIconButton(ChromeIcons.Gear, stringResource(R.string.settings), onSettings, tooltip = null)
                else DeskButton(stringResource(R.string.settings), onSettings, icon = ChromeIcons.Gear)
            }
        }
    }
}

@Composable
private fun DeskTab(label: String, selected: Boolean, tight: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val fill = if (selected) Am.Navy else Color.Transparent
    Box(
        Modifier.height(36.dp).clip(shape).background(fill).then(if (selected) Modifier else Modifier.deskPress(18.dp)).clickable(onClick = onClick).padding(horizontal = if (tight) 8.dp else 16.dp),
        Alignment.Center,
    ) { Text(label, color = if (selected) Am.OnNavy else Am.HomeMid, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

@Composable
private fun ProjectsPane(
    shown: List<ProjectInfo>, loaded: Boolean, assets: Boolean, query: String, settings: AppSettings, version: Int,
    selected: String?, onSelect: (String?) -> Unit,
    onOpen: (ProjectInfo) -> Unit, onExport: (String) -> Unit, onDuplicate: (String) -> Unit, onHistory: (ProjectInfo) -> Unit, onDelete: (ProjectInfo) -> Unit,
    onNew: () -> Unit, onImport: () -> Unit, onCollect: ((String) -> Unit)?,
) {
    val byName = settings.sortBy == "name"
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val rows = remember(shown, byName, locale) { listRows(shown, byName, dateHeader(locale)) }
    val cards = remember(rows) { rows.filterIsInstance<ListRow.Card>().map { it.info } }
    val thumbs = remember { object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 96 } }
    val seen = remember { HashSet<Any>() }
    val grid = rememberLazyGridState()
    val focus = remember { FocusRequester() }
    val ids = remember(cards) { cards.map { it.id }.toSet() }

    var columns by remember { mutableIntStateOf(1) }

    fun move(dx: Int, dy: Int) {
        val current = selected
        if (current == null || current !in ids) { onSelect(cards.firstOrNull()?.id); return }
        val index = cards.indexOfFirst { it.id == current }
        if (dy == 0) {
            cards.getOrNull(index + dx)?.let { onSelect(it.id) }
            return
        }
        val items = grid.layoutInfo.visibleItemsInfo
        val me = items.firstOrNull { it.key == current }
        val above = if (me == null) emptyList() else items.filter { it.key in ids && (if (dy < 0) it.offset.y < me.offset.y else it.offset.y > me.offset.y) }
        val rowY = (if (dy < 0) above.maxOfOrNull { it.offset.y } else above.minOfOrNull { it.offset.y })
        val near = if (me == null || rowY == null) null else above.filter { it.offset.y == rowY }.minByOrNull { kotlin.math.abs(it.offset.x - me.offset.x) }
        if (near != null) { onSelect(near.key as String); return }
        val step = columns.coerceAtLeast(1)
        val target = index + dy * step
        val last = cards.lastIndex
        val picked = when {
            target in 0..last -> target
            dy > 0 && index / step < last / step -> last
            else -> null
        }
        picked?.let { onSelect(cards[it].id) }
    }
    LaunchedEffect(selected) {
        val id = selected ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it is ListRow.Card && it.info.id == id }.takeIf { it >= 0 } ?: return@LaunchedEffect
        val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val view = grid.layoutInfo
        if (info == null || info.offset.y < view.viewportStartOffset || info.offset.y + info.size.height > view.viewportEndOffset) grid.animateScrollToItem(index)
    }

    Box(
        Modifier.fillMaxSize().focusRequester(focus).focusable().onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val info = cards.firstOrNull { it.id == selected }
            when (event.key) {
                Key.Enter, Key.NumPadEnter -> info?.let { onOpen(it); true } ?: false
                Key.Delete, Key.Backspace -> info?.let { onDelete(it); true } ?: false
                Key.DirectionLeft -> { move(-1, 0); true }
                Key.DirectionRight -> { move(1, 0); true }
                Key.DirectionUp -> { move(0, -1); true }
                Key.DirectionDown -> { move(0, 1); true }
                else -> false
            }
        },
    ) {
        if (shown.isNotEmpty()) SizedBox(Modifier.fillMaxSize()) { maxWidth, _ ->
            val room = maxWidth - GridPad * 2 + TileGap
            val most = (room / (CardMin + TileGap)).toInt().coerceAtLeast(1)
            val fewest = kotlin.math.ceil(room / (CardMax + TileGap)).toInt().coerceIn(1, most)
            val across = cards.size.coerceIn(fewest, most)
            SideEffect { columns = across }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = GridPad), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (assets) R.string.home_tab_assets else R.string.home_your_projects), color = Am.Navy, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("${shown.size}", Modifier.padding(start = 10.dp), color = Am.HomeDim, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    DeskDropdown(
                        stringResource(if (byName) R.string.home_sort_by_name else R.string.home_sort_by_date), listOf(false, true),
                        { stringResource(if (it) R.string.home_sort_name else R.string.home_sort_date) }, byName, { settings.sortBy = if (it) "name" else "date" },
                        Modifier.width(170.dp), menuWidth = 170.dp, fill = Am.Card,
                    )
                }
                LazyVerticalGrid(
                    GridCells.Fixed(across), Modifier.fillMaxSize(), grid,
                    contentPadding = PaddingValues(start = GridPad, end = GridPad, top = 8.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(TileGap), verticalArrangement = Arrangement.spacedBy(TileGap),
                ) {
                    itemsIndexed(
                        rows, key = { _, r -> if (r is ListRow.Card) r.info.id else "date-${(r as ListRow.Date).text}" },
                        span = { _, r -> GridItemSpan(if (r is ListRow.Date) maxLineSpan else 1) },
                    ) { i, row ->
                        when (row) {
                            is ListRow.Date -> Text(row.text, Modifier.padding(top = if (i == 0) 0.dp else 12.dp), color = Am.HomeMid, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            is ListRow.Card -> {
                                val info = row.info
                                val picture = rememberProjectThumb(info, version, thumbs)
                                DeskCard(
                                    info.title, if (info.damaged) null else factLine(info), picture, if (info.damaged) null else duration(info.durationMs),
                                    if (info.damaged) stringResource(R.string.damaged_restore_a_version) else null,
                                    selected == info.id, { onSelect(info.id); runCatching { focus.requestFocus() } }, { onOpen(info) },
                                    Modifier.enterOnce(seen, info.id, i),
                                ) { close ->
                                    if (!info.damaged) {
                                        DeskMenuItem(stringResource(R.string.open), { close(); onOpen(info) }, HomeIcons.ProjectsTabOff, hint = "↵")
                                        DeskMenuItem(stringResource(R.string.export), { close(); onExport(info.id) }, HomeIcons.Export)
                                        DeskMenuItem(stringResource(if (info.isElement) R.string.home_duplicate_asset else R.string.home_duplicate), { close(); onDuplicate(info.id) }, HomeIcons.Duplicate)
                                        if (onCollect != null) DeskMenuItem(stringResource(R.string.home_collect_media), { close(); onCollect(info.id) }, HomeIcons.Folder)
                                    }
                                    DeskMenuItem(stringResource(R.string.version_history), { close(); onHistory(info) }, ChromeIcons.Undo)
                                    DeskMenuDivider()
                                    DeskMenuItem(stringResource(if (info.isElement) R.string.home_delete_asset else R.string.delete), { close(); onDelete(info) }, HomeIcons.Delete, danger = true, hint = "⌫")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (loaded && shown.isEmpty() && query.isNotBlank()) NoMatches(query, if (assets) R.string.search_no_assets else R.string.search_no_projects, Modifier.fillMaxWidth().padding(top = 160.dp), Am.Navy, 17.sp)
        else if (loaded && shown.isEmpty()) DeskEmpty(assets, onNew, onImport)
    }
}

internal fun factLine(info: ProjectInfo): String = facts(info).flatten().joinToString("  ·  ") { it.second }

@Composable
private fun rememberProjectThumb(info: ProjectInfo, version: Int, cache: MutableMap<String, ImageBitmap>): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(cache[info.id], info.id, info.modified, info.thumbnail, version) {
        val file = info.thumbnail
        if (file == null) { value = null; return@produceState }
        val decoded = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
        if (decoded != null) { cache[info.id] = decoded; value = decoded }
    }
    return bitmap
}

@Composable
internal fun DeskCard(
    title: String, facts: String?, thumbnail: ImageBitmap?, badge: String?, note: String?,
    selected: Boolean, onSelect: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    var buttonMenu by remember { mutableStateOf(false) }
    val lift = if (hovered || selected) 8.dp else 1.dp
    val shape = RoundedCornerShape(14.dp)
    val edge = when { selected -> Am.HomeAccent; hovered -> Am.HomeFaint; else -> Am.HomeLine }
    Column(
        modifier.fillMaxWidth().hoverable(source).shadow(lift, shape, ambientColor = Color(0x33000000), spotColor = Color(0x33000000)).clip(shape).background(Am.Card)
            .border(if (selected) 2.dp else 1.dp, edge, shape)
            .handCursor().pointerInput(onSelect) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); onSelect() } }.onSecondaryPress { menuAt = IntOffset(it.x.toInt(), it.y.toInt()); onSelect() }
            .combinedClickable(onDoubleClick = onOpen, onClick = onSelect),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.6f).background(Am.HomePanel)) {
            if (thumbnail == null) Icon(HomeIcons.ProjectsTabOff, null, Modifier.align(Alignment.Center).size(32.dp), tint = Am.HomeFaint)
            else BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), Alignment.Center) {
                val ratio = (thumbnail.width.toFloat() / thumbnail.height.coerceAtLeast(1)).coerceIn(0.2f, 5f)
                val frame = if (ratio < maxWidth / maxHeight) Modifier.fillMaxHeight().aspectRatio(ratio) else Modifier.fillMaxWidth().aspectRatio(ratio)
                Box(Modifier.align(Alignment.Center).then(frame).clip(RoundedCornerShape(6.dp)).checkerboard(6.dp)) {
                    Image(thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            if (badge != null) Text(
                badge, Modifier.align(Alignment.BottomStart).padding(8.dp).background(Am.Navy, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Am.OnNavy, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
            if (hovered || selected || buttonMenu) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Box(
                    Modifier.size(30.dp).background(Am.Card.copy(alpha = 0.92f), CircleShape).clip(CircleShape).deskPress(15.dp).clickable { onSelect(); buttonMenu = true },
                    Alignment.Center,
                ) { Icon(HomeIcons.More, stringResource(R.string.home_more), Modifier.size(20.dp), tint = Am.Navy) }
                if (buttonMenu) DeskPopupMenu(BelowAnchor(true), { buttonMenu = false }) { menu { buttonMenu = false } }
            }
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 13.dp)) {
            Text(title, color = Am.Navy, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (facts != null) Text(facts, Modifier.padding(top = 3.dp), color = Am.HomeDim, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, Modifier.padding(top = 3.dp), color = Am.HomeAccent, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        menuAt?.let { at -> DeskPopupMenu(AtPosition(at), { menuAt = null }) { menu { menuAt = null } } }
    }
}

@Composable
private fun DeskEmpty(assets: Boolean, onNew: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 96.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).background(Am.HomePanel, CircleShape), Alignment.Center) {
            Icon(if (assets) HomeIcons.Duplicate else HomeIcons.ProjectsTabOff, null, Modifier.size(40.dp), tint = Am.HomeMid)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(if (assets) R.string.home_empty_assets else R.string.home_empty_projects), color = Am.Navy, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (assets) Text(
            stringResource(R.string.home_asset_prompt_first) + " " + stringResource(R.string.home_asset_prompt_bold), Modifier.padding(top = 8.dp),
            color = Am.HomeMid, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DeskButton(stringResource(R.string.home_new_project), onNew, primary = true, icon = HomeIcons.Plus, height = 40.dp)
            DeskButton(stringResource(R.string.desk_import), onImport, icon = HomeIcons.Upload, height = 40.dp)
        }
        Text(stringResource(R.string.desk_drop_hint), Modifier.padding(top = 16.dp), color = Am.HomeDim, fontSize = 13.sp)
    }
}

@Composable
internal fun DeskConfirm(title: String, name: String?, body: String?, confirm: String, danger: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    DeskModal(onCancel, 440.dp, onEnter = onConfirm) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp)) {
            Text(title, color = Am.Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (name != null) Text(name, Modifier.padding(top = 8.dp), color = Am.HomeInk, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (body != null) Text(body, Modifier.padding(top = 8.dp), color = Am.HomeMid, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), Arrangement.spacedBy(10.dp, Alignment.End)) {
            DeskButton(stringResource(R.string.cancel), onCancel, minWidth = 92.dp)
            DeskButton(confirm, onConfirm, primary = !danger, danger = danger, minWidth = 92.dp)
        }
    }
}

@Composable
private fun DeskImportReport(reports: List<ImportReport>, onDismiss: () -> Unit, onOpen: () -> Unit) {
    DeskModal(onDismiss, 560.dp, onEnter = onOpen) {
        DeskDialogHeader(stringResource(R.string.import_complete), onDismiss)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
            for (report in reports) {
                if (reports.size > 1) Text(report.title, Modifier.padding(top = 8.dp), color = Am.Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                for ((heading, lines) in report.sections) {
                    Text(heading.text(), Modifier.padding(top = 10.dp), color = Am.Navy, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    for (line in lines) Text("• ${line.text()}", color = Am.HomeInk, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
        DeskDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), Arrangement.spacedBy(10.dp, Alignment.End)) {
            DeskButton(stringResource(R.string.ok), onDismiss, minWidth = 92.dp)
            DeskButton(stringResource(R.string.open), onOpen, primary = true, minWidth = 92.dp)
        }
    }
}

@Composable
private fun DeskTemplates(version: Int, query: String, onTemplate: (String) -> Unit, onProject: (Scene) -> Unit) {
    val context = LocalContext.current
    val templates = remember { TemplateStore(context) }
    val presets = remember { PresetStore(context) }
    val scope = rememberCoroutineScope()
    var sub by remember { mutableIntStateOf(0) }
    var rev by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirm by remember { mutableStateOf<Pair<Int, () -> Unit>?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    val savedTemplates by produceState(emptyList<SavedTemplate>(), version, rev) { value = withContext(Dispatchers.IO) { templates.list() } }
    val savedPresets by produceState(emptyList<SavedPreset>(), version, rev) { value = withContext(Dispatchers.IO) { presets.list() } }
    val shownTemplates = remember(savedTemplates, query) { savedTemplates.filter { matchesSearch(query, it.title) } }
    val shownPresets = remember(savedPresets, query) { savedPresets.filter { matchesSearch(query, it.title) } }
    val seen = remember { HashSet<Any>() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = GridPad), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            DeskTab(stringResource(R.string.template_my_templates), sub == 0) { sub = 0; selected = null }
            DeskTab(stringResource(R.string.template_my_presets), sub == 1) { sub = 1; selected = null }
            Spacer(Modifier.weight(1f))
            if (sub == 1 && savedPresets.isNotEmpty()) DeskButton(stringResource(R.string.delete_all), {
                confirm = R.string.template_delete_all_presets to { scope.launch { withContext(Dispatchers.IO) { presets.deleteAll() }; rev++ } }
            })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = if (sub == 0) shownTemplates.size else shownPresets.size
            val total = if (sub == 0) savedTemplates.size else savedPresets.size
            if (total == 0) Column(Modifier.fillMaxSize().padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(88.dp).background(Am.HomePanel, CircleShape), Alignment.Center) { Icon(HomeIcons.TemplatesTab, null, Modifier.size(40.dp), tint = Am.HomeMid) }
                Spacer(Modifier.height(20.dp))
                Text(stringResource(if (sub == 0) R.string.template_none else R.string.template_no_presets), color = Am.Navy, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            } else if (list == 0) NoMatches(query, if (sub == 0) R.string.search_no_templates else R.string.search_no_presets, Modifier.fillMaxWidth().padding(top = 160.dp), Am.Navy, 17.sp)
            else BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = (((maxWidth - GridPad * 2 + TileGap) / (CardMin + TileGap)).toInt()).coerceAtLeast(1)
                LazyVerticalGrid(
                    GridCells.Fixed(columns), Modifier.fillMaxSize(), rememberLazyGridState(),
                    contentPadding = PaddingValues(start = GridPad, end = GridPad, top = 8.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(TileGap), verticalArrangement = Arrangement.spacedBy(TileGap),
                ) {
                    if (sub == 0) itemsIndexed(shownTemplates, key = { _, t -> t.id }) { i, t ->
                        val scene = t.scene
                        val ratio = aspectName(scene.width, scene.height)
                        val facts = listOfNotNull(ratio ?: "${scene.width}x${scene.height}", ratio?.let { "${minOf(scene.width, scene.height)}p" }, "${scene.fps}fps").joinToString("  ·  ")
                        DeskCard(
                            t.title, facts, rememberFileBitmap(t.thumbnail, t.modified), duration(scene.totalTime), null,
                            selected == t.id, { selected = t.id }, { onTemplate(t.id) }, Modifier.enterOnce(seen, t.id, i),
                        ) { close ->
                            DeskMenuItem(stringResource(R.string.template_open_as_project), { close(); onProject(t.scene) }, HomeIcons.Select)
                            DeskMenuItem(stringResource(R.string.rename), { close(); renaming = t.id to t.title }, HomeIcons.Pencil)
                            DeskMenuDivider()
                            DeskMenuItem(stringResource(R.string.delete), {
                                close(); confirm = R.string.template_delete_template to { scope.launch { withContext(Dispatchers.IO) { templates.delete(t.id) }; rev++ } }
                            }, HomeIcons.Delete, danger = true)
                        }
                    } else itemsIndexed(shownPresets, key = { _, p -> p.id }) { i, p ->
                        DeskPresetCard(
                            p.title, Modifier.enterOnce(seen, p.id, i),
                            onRename = { renaming = p.id to p.title },
                            onDelete = { confirm = R.string.template_delete_preset to { scope.launch { withContext(Dispatchers.IO) { presets.delete(p.id) }; rev++ } } },
                        )
                    }
                }
            }
        }
    }
    renaming?.let { (id, title) ->
        DeskRename(title, onDone = { name ->
            val template = sub == 0
            scope.launch {
                val renamed = withContext(Dispatchers.IO) { runCatching { if (template) templates.rename(id, name) else presets.rename(id, name) }.isSuccess }
                if (!renamed) app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.template_rename_failed), app.absentmotion.platform.Toast.LENGTH_SHORT).show()
                rev++
            }
            renaming = null
        }, onDismiss = { renaming = null })
    }
    confirm?.let { (title, action) ->
        DeskConfirm(stringResource(title), null, null, stringResource(R.string.delete), danger = true, onCancel = { confirm = null }) { confirm = null; action() }
    }
}

@Composable
private fun DeskPresetCard(title: String, modifier: Modifier, onRename: () -> Unit, onDelete: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    var buttonMenu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val items: @Composable ColumnScope.(() -> Unit) -> Unit = { close ->
        DeskMenuItem(stringResource(R.string.rename), { close(); onRename() }, HomeIcons.Pencil)
        DeskMenuDivider()
        DeskMenuItem(stringResource(R.string.delete), { close(); onDelete() }, HomeIcons.Delete, danger = true)
    }
    Row(
        modifier.fillMaxWidth().height(56.dp).hoverable(source).clip(shape).background(Am.Card).border(1.dp, if (hovered) Am.HomeFaint else Am.HomeLine, shape)
            .handCursor().onSecondaryPress { menuAt = IntOffset(it.x.toInt(), it.y.toInt()) }.clickable { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f).padding(start = 16.dp), color = Am.Navy, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(end = 10.dp).alpha(if (hovered || buttonMenu) 1f else 0f)) {
            DeskIconButton(HomeIcons.More, stringResource(R.string.home_more), { buttonMenu = true }, size = 32.dp, tooltip = null)
            if (buttonMenu) DeskPopupMenu(BelowAnchor(true), { buttonMenu = false }) { items { buttonMenu = false } }
        }
        menuAt?.let { at -> DeskPopupMenu(AtPosition(at), { menuAt = null }) { items { menuAt = null } } }
    }
}

@Composable
private fun DeskRename(title: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(title) }
    val done = { onDone(text.ifBlank { title }) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DeskModal(onDismiss, 440.dp, onEnter = done) {
        Text(stringResource(R.string.rename), Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp), color = Am.Navy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        BasicTextField(
            text, { text = it }, Modifier.padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth().focusRequester(focus).clip(RoundedCornerShape(10.dp)).background(Am.HomeBg)
                .border(1.dp, Am.HomeLine, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = TextStyle(color = Am.Navy, fontSize = 14.sp), singleLine = true, cursorBrush = SolidColor(Am.Navy),
        )
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), Arrangement.spacedBy(10.dp, Alignment.End)) {
            DeskButton(stringResource(R.string.cancel), onDismiss, minWidth = 92.dp)
            DeskButton(stringResource(R.string.done), done, primary = true, minWidth = 92.dp)
        }
    }
}
