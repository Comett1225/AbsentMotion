package app.absentmotion.ui.home

import app.absentmotion.export.formatBytes
import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Uri
import app.absentmotion.platform.rememberLauncherForActivityResult
import app.absentmotion.platform.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import app.absentmotion.platform.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.absentmotion.library.AppSettings
import app.absentmotion.library.ImportReport
import app.absentmotion.library.ProjectInfo
import app.absentmotion.library.ProjectStore
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.editor.NoMatches
import app.absentmotion.ui.editor.SearchField
import app.absentmotion.ui.editor.TextIcons
import app.absentmotion.ui.editor.matchesSearch
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Motion
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.theme.animatedColor
import app.absentmotion.ui.theme.enterFade
import app.absentmotion.ui.theme.enterOnce
import app.absentmotion.ui.theme.presence
import app.absentmotion.ui.theme.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.i18n.text

private val Grey get() = Am.HomeDim
private val FactText get() = Am.HomeFaint
private val MenuScrim get() = Am.MenuScrim
private val BarHeight = 72.dp
private val PlusOverhang = 16.dp
internal fun dateHeader(locale: Locale): DateTimeFormatter =
    if (locale.language == "en") DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    else DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG).withLocale(locale)

internal sealed interface ListRow {
    data class Date(val text: String) : ListRow
    data class Card(val info: ProjectInfo) : ListRow
}

private class OpenMenu(val id: String, val bounds: Rect)

@Composable
fun HomeScreen(
    store: ProjectStore,
    settings: AppSettings,
    onOpen: (String) -> Unit,
    onExport: (String) -> Unit = {},
    imported: List<ImportReport> = emptyList(),
    onImportShown: () -> Unit = {},
    onImportFile: (Uri) -> Unit = {},
    onTemplate: (String) -> Unit = {},
    onTemplateProject: (app.absentmotion.model.Scene) -> Unit = {},
    onHistory: (ProjectInfo) -> Unit = {},
    onCollect: (String) -> Unit = {},
    changes: Int = 0,
) {
    var projects by remember { mutableStateOf<List<ProjectInfo>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf<String?>(null) }
    val query = app.absentmotion.ui.editor.debounced(search.orEmpty())
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val wide = LocalWide.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    app.absentmotion.platform.BackHandler(search != null) { search = null }
    LaunchedEffect(version, changes) { projects = withContext(Dispatchers.IO) { store.list(includeDamaged = true) } }
    val shown = remember(projects, tab, query) { projects.filter { it.isElement == (tab == 1) && matchesSearch(query, it.title) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onImportFile(uri) }
    val toReport = imported.filter { it.sections.isNotEmpty() }
    LaunchedEffect(imported) {
        if (imported.isEmpty()) return@LaunchedEffect
        tab = if (imported.all { it.element }) 1 else 0
        page = if (imported.all { it.template }) 1 else 0
        version++
        if (toReport.isEmpty()) onImportShown()
    }

    ModalNavigationDrawer(
        drawerContent = { SettingsDrawer(settings, drawer, onRestored = { version++ }) },
        drawerState = drawer,
        scrimColor = Color.Black.copy(alpha = 0.94f),
    ) {
        Box(Modifier.fillMaxSize().background(Am.HomeBg)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                if (wide) WideHeader(page, tab, { p, t -> page = p; tab = t }, onSettings = { scope.launch { drawer.open() } }, onNew = { creating = true })
                else TopBar(onMenu = { scope.launch { drawer.open() } }, search, { search = it })
                if (page == 1) TemplatesTab(version, onTemplate, onTemplateProject, Modifier.weight(1f).then(if (wide) Modifier.widthIn(max = 760.dp).align(Alignment.CenterHorizontally) else Modifier), query) else ProjectsPane(
                    Modifier.weight(1f), shown, tab, { tab = it }, settings, version, onOpen, onExport, query,
                    onUpload = { picker.launch(arrayOf("text/xml", "application/xml", "application/zip", "application/octet-stream")) },
                    onDuplicate = { id -> scope.launch {
                        val copied = withContext(Dispatchers.IO) { runCatching { store.duplicate(id) }.onFailure { app.absentmotion.platform.Log.w("AMStore", "cannot duplicate $id", it) }.isSuccess }
                        if (!copied) app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.home_create_project_failed), app.absentmotion.platform.Toast.LENGTH_LONG).show()
                        version++
                    } },
                    onDelete = { id -> scope.launch { withContext(Dispatchers.IO) { store.delete(id, context.getExternalFilesDir(null)?.resolve("ai")) }; version++ } },
                    onHistory = onHistory,
                    onCollect = onCollect,
                    wide = wide,
                )
                if (!wide) Spacer(Modifier.height(BarHeight + navInset))
            }
            if (!wide) BottomBar(onNew = { creating = true }, page, { page = it }, Modifier.align(Alignment.BottomCenter), navInset)
            if (toReport.isNotEmpty()) ImportDialog(toReport, onDismiss = onImportShown, onOpen = { onImportShown(); toReport.first().let { if (it.template) onTemplate(it.id) else onOpen(it.id) } })
            Presence(creating) {
                NewProjectSheet(
                    store, settings, projects.map { it.title }, onClose = { creating = false },
                    onOpen = { id -> version++; onOpen(id) },
                )
            }
        }
    }
}

@Composable
private fun TopBar(onMenu: () -> Unit, search: String?, onSearch: (String?) -> Unit) {
    Box(Modifier.fillMaxWidth().height(57.dp)) {
        Box(Modifier.align(Alignment.CenterStart).size(50.dp).clickable(onClick = onMenu), contentAlignment = Alignment.Center) {
            Icon(HomeIcons.Menu, stringResource(R.string.settings), Modifier.size(24.dp), tint = Am.Navy)
        }
        if (search == null) Text("AbsentMotion", Modifier.align(Alignment.Center), color = Am.Navy, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        else SearchField(
            search, { onSearch(it) }, Modifier.align(Alignment.Center).padding(horizontal = 56.dp).fillMaxWidth().height(40.dp),
            hint = stringResource(R.string.home_search_hint), background = Am.Card, content = Am.Navy, faint = Am.HomeDim, autoFocus = true,
        )
        Box(Modifier.align(Alignment.CenterEnd).size(50.dp).clickable { onSearch(if (search == null) "" else null) }, contentAlignment = Alignment.Center) {
            Icon(if (search == null) TextIcons.Search else HomeIcons.Close, stringResource(if (search == null) R.string.home_search else R.string.home_close_search), Modifier.size(24.dp), tint = Am.Navy)
        }
    }
}

@Composable
private fun ProjectsPane(
    modifier: Modifier,
    shown: List<ProjectInfo>,
    tab: Int,
    onTab: (Int) -> Unit,
    settings: AppSettings,
    version: Int,
    onOpen: (String) -> Unit,
    onExport: (String) -> Unit,
    query: String,
    onUpload: () -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onHistory: (ProjectInfo) -> Unit,
    onCollect: (String) -> Unit,
    wide: Boolean = false,
) {
    val thumbs = remember {
        object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 64
        }
    }
    val seen = remember { HashSet<Any>() }
    var menu by remember { mutableStateOf<OpenMenu?>(null) }
    var deleting by remember { mutableStateOf<ProjectInfo?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val byName = settings.sortBy == "name"
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val rows = remember(shown, byName, locale) { listRows(shown, byName, dateHeader(locale)) }
    val card: @Composable (ProjectInfo, Int, Modifier) -> Unit = { info, i, itemModifier ->
        ProjectCard(
            info, version, thumbs, itemModifier.enterOnce(seen, info.id, i), menuOpen = menu?.id == info.id,
            onMenu = { menu = OpenMenu(info.id, it) },
            onDismissMenu = { menu = null },
            onOpen = { if (info.damaged) onHistory(info) else onOpen(info.id) },
            onExport = { menu = null; onExport(info.id) },
            onDuplicate = { menu = null; onDuplicate(info.id) },
            onDelete = { menu = null; deleting = info },
            onHistory = { menu = null; onHistory(info) },
            onCollect = { menu = null; onCollect(info.id) },
        )
    }

    Box(modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            if (!wide) {
                Spacer(Modifier.height(24.dp))
                Chips(tab, onTab)
            }
            if (shown.isEmpty() && tab == 0 && settings.xmlUpload && query.isBlank()) {
                Spacer(Modifier.height(13.dp))
                Box(Modifier.padding(horizontal = 16.dp).then(if (wide) WideCard else Modifier)) { UploadCard(onUpload) }
            }
            if (shown.isNotEmpty()) {
                Spacer(Modifier.height(13.dp))
                ListHeader(if (tab == 0) stringResource(R.string.home_your_projects) else null, byName) { settings.sortBy = if (it) "name" else "date" }
                if (wide) ProjectGrid(rows, tab == 0 && settings.xmlUpload, onUpload, card) else LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                ) {
                    if (tab == 0 && settings.xmlUpload) item(key = "upload") {
                        UploadCard(onUpload)
                        Spacer(Modifier.height(24.dp))
                    }
                    itemsIndexed(rows, key = { _, r -> if (r is ListRow.Card) r.info.id else "date-${(r as ListRow.Date).text}" }) { i, row ->
                        when (row) {
                            is ListRow.Date -> DateLabel(row.text, first = i == 0)
                            is ListRow.Card -> card(row.info, i, Modifier.animateItem(fadeInSpec = null, placementSpec = Motion.enter(), fadeOutSpec = Motion.exit()))
                        }
                    }
                }
            }
        }
        if (shown.isEmpty() && query.isNotBlank()) NoMatches(query, if (tab == 1) R.string.search_no_assets else R.string.search_no_projects, Modifier.fillMaxWidth().padding(top = 120.dp), Am.Navy, 17.sp)
        else if (shown.isEmpty()) EmptyState(element = tab == 1, arrow = !wide)
        menu?.let { open ->
            Box(
                Modifier.fillMaxSize().enterFade()
                    .drawBehind {
                        val hole = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(Offset.Zero, size))
                            addRoundRect(RoundRect(open.bounds.translate(-origin), CornerRadius(12.dp.toPx())))
                        }
                        drawPath(hole, MenuScrim)
                    }
                    .pointerInput(Unit) { detectTapGestures { menu = null } },
            )
        }
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(if (target.isElement) R.string.home_delete_asset_title else R.string.home_delete_project_title), color = Am.Navy) },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.cancel), color = Am.Navy) } },
            confirmButton = { TextButton({ deleting = null; onDelete(target.id) }) { Text(stringResource(R.string.delete), color = Am.Navy) } },
            containerColor = Am.Card,
        )
    }
}

@Composable
internal fun UploadCard(onUpload: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HomeIcons.Upload, null, Modifier.size(48.dp), tint = Am.Navy)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.import_a_project_file), Modifier.weight(1f), color = Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(16.dp))
        Box(
            Modifier.height(40.dp).clip(RoundedCornerShape(14.dp)).background(Am.Navy).clickable(onClick = onUpload).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.choose_file), color = Am.OnNavy, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun ImportDialog(reports: List<ImportReport>, onDismiss: () -> Unit, onOpen: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_complete), color = Am.Navy) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (report in reports) {
                    if (reports.size > 1) Text(report.title, Modifier.padding(top = 8.dp), color = Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    for ((heading, lines) in report.sections) {
                        Text(heading.text(), Modifier.padding(top = 8.dp), color = Am.Navy, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        for (line in lines) Text("\u2022 ${line.text()}", color = Am.Navy, fontSize = 14.sp, lineHeight = 19.sp)
                    }
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok), color = Am.Navy) } },
        confirmButton = { TextButton(onOpen) { Text(stringResource(R.string.open), color = Am.Navy) } },
        containerColor = Am.Card,
    )
}

@Composable
internal fun DateLabel(text: String, first: Boolean) {
    Text(text, Modifier.padding(top = if (first) 0.dp else 12.dp, bottom = 12.dp), color = Am.Navy, fontSize = 15.sp, lineHeight = 19.sp)
}

private fun listRows(list: List<ProjectInfo>, byName: Boolean, dates: DateTimeFormatter): List<ListRow> =
    if (byName) {
        list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }).map { ListRow.Card(it) }
    } else {
        list.sortedByDescending { it.modified }
            .groupBy { Instant.ofEpochMilli(it.modified).atZone(ZoneId.systemDefault()).format(dates) }
            .flatMap { (date, items) -> listOf<ListRow>(ListRow.Date(date)) + items.map { ListRow.Card(it) } }
    }

@Composable
private fun Chips(tab: Int, onTab: (Int) -> Unit) {
    Row(Modifier.padding(start = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Chip(stringResource(R.string.home_tab_projects), tab == 0) { onTab(0) }
        Chip(stringResource(R.string.home_tab_assets), tab == 1) { onTab(1) }
    }
}

@Composable
internal fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.height(40.dp).shadow(3.dp, shape).clip(shape)
            .background(animatedColor(if (selected) Am.Navy else Am.Card)).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = animatedColor(if (selected) Am.OnNavy else Grey), fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ListHeader(title: String?, byName: Boolean, onSort: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.orEmpty(), Modifier.weight(1f), color = Am.Navy, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Box {
            Row(Modifier.height(48.dp).clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (byName) R.string.home_sort_by_name else R.string.home_sort_by_date), color = Am.Navy, fontSize = 15.sp)
                Icon(HomeIcons.ChevronDown, null, Modifier.size(24.dp), tint = Am.Navy)
            }
            RefMenu(open, { open = false }, Am.Card, RoundedCornerShape(14.dp), Modifier.width(211.dp)) {
                SortRow(stringResource(R.string.home_sort_name), byName) { open = false; onSort(true) }
                SortRow(stringResource(R.string.home_sort_date), !byName) { open = false; onSort(false) }
            }
        }
    }
}

@Composable
private fun SortRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Am.HomeInk, fontSize = 16.sp)
        RadioButton(
            selected, onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Am.Navy, unselectedColor = Am.HomeDim),
        )
    }
}

@Composable
private fun ProjectCard(
    info: ProjectInfo,
    version: Int,
    thumbs: MutableMap<String, ImageBitmap>,
    modifier: Modifier,
    menuOpen: Boolean,
    onMenu: (Rect) -> Unit,
    onDismissMenu: () -> Unit,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit,
    onCollect: () -> Unit,
) {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(
        modifier.padding(bottom = 12.dp).fillMaxWidth().height(96.dp)
            .onGloballyPositioned { coords[0] = it }
            .clip(RoundedCornerShape(12.dp)).background(Am.Card).clickable(onClick = onOpen),
    ) {
        Thumbnail(info, version, thumbs, Modifier.padding(8.dp))
        Column(Modifier.padding(start = 104.dp, top = 16.dp, end = 52.dp)) {
            Text(
                info.title, color = Am.Navy, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            if (info.damaged) Text(stringResource(R.string.damaged_restore_a_version), color = Am.Accent, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2)
            else facts(info).forEachIndexed { i, row ->
                if (i > 0) Spacer(Modifier.height(4.dp))
                Row { row.forEach { (icon, text) -> Fact(icon, text) } }
            }
        }
        Box(
            Modifier.align(Alignment.CenterEnd).size(48.dp).clickable { coords[0]?.let { onMenu(it.boundsInRoot()) } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(HomeIcons.More, stringResource(R.string.home_more), Modifier.size(24.dp), tint = Grey)
        }
        Box(Modifier.align(Alignment.BottomEnd).offset(y = 8.dp)) {
            RefMenu(menuOpen, onDismissMenu, Am.Card, RoundedCornerShape(14.dp), Modifier.width(270.dp)) {
                if (!info.damaged) {
                    MenuRow(stringResource(R.string.export), HomeIcons.Export, onExport)
                    MenuRow(stringResource(if (info.isElement) R.string.home_duplicate_asset else R.string.home_duplicate), HomeIcons.Duplicate, onDuplicate)
                    MenuRow(stringResource(R.string.home_collect_media), HomeIcons.Folder, onCollect)
                }
                MenuRow(stringResource(R.string.version_history), ChromeIcons.Undo, onHistory)
                MenuRow(stringResource(if (info.isElement) R.string.home_delete_asset else R.string.delete), HomeIcons.Delete, onDelete)
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = Am.HomeInk, fontSize = 16.sp)
        Icon(icon, null, Modifier.size(24.dp), tint = Am.HomeInk)
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    Row(Modifier.widthIn(min = 85.dp).height(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = Grey)
        Spacer(Modifier.width(6.dp))
        Text(text, color = FactText, fontSize = 12.sp, maxLines = 1)
    }
}

private fun facts(p: ProjectInfo): List<List<Pair<ImageVector, String>>> {
    val ratio = aspectName(p.width, p.height)
    val first = if (ratio != null) {
        listOf(HomeIcons.Aspect to ratio, HomeIcons.Resolution to "${minOf(p.width, p.height)}p")
    } else {
        listOf(HomeIcons.Resolution to "${p.width}x${p.height}")
    }
    return listOf(first, listOf(HomeIcons.Folder to formatBytes(p.sizeBytes), HomeIcons.FrameRate to "${p.fps}fps"))
}

private fun duration(ms: Int): String {
    val s = (ms + 500) / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}

@Composable
private fun Thumbnail(info: ProjectInfo, version: Int, cache: MutableMap<String, ImageBitmap>, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(cache[info.id], info.id, info.modified, version) {
        val file = info.thumbnail
        if (file == null) {
            value = null
            return@produceState
        }
        val decoded = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
        if (decoded != null) {
            cache[info.id] = decoded
            value = decoded
        }
    }
    Box(modifier.size(80.dp).clip(RoundedCornerShape(8.dp)).checkerboard(5.dp)) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        if (!info.damaged) Text(
            duration(info.durationMs),
            Modifier.align(Alignment.BottomStart).padding(4.dp).background(Am.Navy, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            color = Am.OnNavy, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
        )
    }
}

internal fun Modifier.checkerboard(cell: Dp): Modifier = drawWithCache {
    val c = cell.toPx()
    val dark = Path()
    for (row in 0 until (size.height / c).toInt() + 1) {
        for (col in 0 until (size.width / c).toInt() + 1) {
            if ((row + col) % 2 == 0) dark.addRect(Rect(col * c, row * c, (col + 1) * c, (row + 1) * c))
        }
    }
    onDrawBehind {
        drawRect(Color(0xFFE5E5E5))
        drawPath(dark, Color(0xFFCCCCCC))
    }
}

@Composable
private fun EmptyState(element: Boolean, arrow: Boolean = true) {
    Box(Modifier.fillMaxSize().enterFade()) {
        Column(Modifier.fillMaxWidth().padding(top = 243.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(if (element) R.string.home_empty_assets else R.string.home_empty_projects),
                color = Am.Navy, fontSize = 24.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold,
            )
            if (element) {
                Spacer(Modifier.height(8.dp))
                Text(promptText(), color = Am.Navy, fontSize = 17.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
            }
        }
        if (arrow) Icon(HomeIcons.ArrowDown, null, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).size(56.dp), tint = Am.HomeInk)
    }
}

@Composable
private fun promptText(): AnnotatedString {
    val first = stringResource(R.string.home_asset_prompt_first)
    val bold = stringResource(R.string.home_asset_prompt_bold)
    return buildAnnotatedString {
        append(first + "\n")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
    }
}

@Composable
private fun BottomBar(onNew: () -> Unit, page: Int, onPage: (Int) -> Unit, modifier: Modifier, navInset: Dp) {
    Box(modifier.fillMaxWidth().height(BarHeight + PlusOverhang + navInset)) {
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(BarHeight + navInset)
                .shadow(8.dp).background(Am.Card).padding(top = 12.dp),
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                BarItem(stringResource(R.string.home_tab_projects), if (page == 0) HomeIcons.ProjectsTab else HomeIcons.ProjectsTabOff) { onPage(0) }
            }
            Spacer(Modifier.width(56.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                BarItem(stringResource(R.string.home_tab_templates), if (page == 1) HomeIcons.TemplatesTabOn else HomeIcons.TemplatesTab) { onPage(1) }
            }
        }
        val press = remember { MutableInteractionSource() }
        Box(
            Modifier.align(Alignment.TopCenter).size(56.dp).pressScale(press)
                .shadow(14.dp, CircleShape, ambientColor = Am.Accent, spotColor = Am.Accent)
                .background(Am.Accent, CircleShape).clickable(interactionSource = press, indication = null, onClick = onNew),
            contentAlignment = Alignment.Center,
        ) {
            Icon(HomeIcons.Plus, stringResource(R.string.home_new_project), Modifier.size(24.dp), tint = Am.OnAccent)
        }
    }
}

@Composable
private fun BarItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(Modifier.width(78.dp).height(48.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(24.dp), tint = Am.Navy)
        Spacer(Modifier.height(8.dp))
        Text(label, color = Am.Navy, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
internal fun RefMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    color: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    Presence(expanded) {
        Popup(popupPositionProvider = MenuPosition, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
            Surface(Modifier.presence(scaleFrom = 0.94f, origin = TransformOrigin(1f, 0f)), color = color, shape = shape, shadowElevation = 8.dp) {
                Column(modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()), content = content)
            }
        }
    }
}

private object MenuPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val fitsBelow = anchorBounds.bottom + popupContentSize.height <= windowSize.height
        val y = if (fitsBelow) anchorBounds.bottom else (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
