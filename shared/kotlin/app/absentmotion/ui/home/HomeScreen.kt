package app.absentmotion.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.composed
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import app.absentmotion.export.formatBytes
import app.absentmotion.platform.BitmapFactory
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Uri
import app.absentmotion.platform.rememberLauncherForActivityResult
import app.absentmotion.platform.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.positionChange
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.absentmotion.ui.adaptive.AdaptiveWindow
import app.absentmotion.ui.adaptive.LocalAdaptiveSpace
import app.absentmotion.ui.adaptive.adaptiveWindowIsWide
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.library.AppSettings
import app.absentmotion.library.CommunityInfo
import app.absentmotion.library.ImportReport
import app.absentmotion.library.ProjectInfo
import app.absentmotion.library.ProjectStore
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.editor.LocalTablet
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.i18n.text
import app.absentmotion.ui.HoldName

private val Grey get() = Am.HomeDim
private val FactText get() = Am.HomeDim
private val MenuScrim get() = Am.MenuScrim
private val BarHeight = 72.dp
private val PlusOverhang = 16.dp
internal fun newProjectIsAsset(page: Int, tab: Int) = page == 0 && tab == 1
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
    engine: (() -> app.absentmotion.engine.EngineThread)? = null,
    onImportLink: (() -> Unit)? = null,
) {
    var projects by remember { mutableStateOf<List<ProjectInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(lastTab) }
    var page by rememberSaveable { mutableIntStateOf(lastPage) }
    var customizingTools by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deletingMany by remember { mutableStateOf<List<ProjectInfo>?>(null) }
    LaunchedEffect(page, tab) { chosen = emptySet() }
    DisposableEffect(Unit) { onDispose { lastTab = tab; lastPage = page } }
    var creating by remember { mutableStateOf(false) }
    var opening by remember { mutableIntStateOf(0) }
    var initialElement by remember { mutableStateOf(false) }
    val newProject = { initialElement = newProjectIsAsset(page, tab); opening++; creating = true }
    var duplicating by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf<String?>(null) }
    val query = app.absentmotion.ui.editor.debounced(search.orEmpty())
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val wide = LocalWide.current || LocalTablet.current

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val replayed = remember { booleanArrayOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (replayed[0]) replayed[0] = false else version++ }
    app.absentmotion.platform.BackHandler(search != null) { search = null }
    LaunchedEffect(version, changes) { projects = withContext(Dispatchers.IO) { store.list(includeDamaged = true) }; loaded = true }
    val shown = remember(projects, tab, query) { projects.filter { it.isElement == (tab == 1) && matchesSearch(query, it.title) } }
    LaunchedEffect(shown) { if (chosen.isNotEmpty()) chosen = chosen.filterTo(LinkedHashSet()) { id -> shown.any { it.id == id } } }
    app.absentmotion.platform.BackHandler(chosen.isNotEmpty()) { chosen = emptySet() }
    fun duplicate(ids: List<String>) {
        if (duplicating || ids.isEmpty()) return
        duplicating = true
        app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.home_duplicating), app.absentmotion.platform.Toast.LENGTH_SHORT).show()
        scope.launch { try {
            val failed = withContext(Dispatchers.IO) { ids.count { id -> runCatching { store.duplicate(id) }.onFailure { app.absentmotion.platform.Log.w("AMStore", "cannot duplicate $id", it) }.isFailure } }
            if (failed > 0) app.absentmotion.platform.Toast.makeText(context, context.getString(R.string.home_create_project_failed), app.absentmotion.platform.Toast.LENGTH_LONG).show()
            version++
        } finally { duplicating = false } }
    }
    fun delete(ids: Set<String>) {
        projects = projects.filter { it.id !in ids }
        scope.launch { withContext(Dispatchers.IO) { ids.forEach { store.delete(it, context.getExternalFilesDir(null)?.resolve("ai")) } }; version++ }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onImportFile(uri) }
    val toReport = imported.filter { it.sections.isNotEmpty() || it.community != null }
    LaunchedEffect(imported) {
        if (imported.isEmpty()) return@LaunchedEffect
        tab = if (imported.all { it.element }) 1 else 0
        if (page != 2) page = if (imported.all { it.template }) 1 else 0
        version++
        if (toReport.isEmpty()) onImportShown()
    }

    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == LayoutDirection.Rtl
    ModalNavigationDrawer(
        drawerContent = { SettingsDrawer(settings, drawer, onRestored = { version++ }) },
        drawerState = drawer,
        scrimColor = Color.Black.copy(alpha = 0.94f),
        gesturesEnabled = drawer.isOpen,
    ) {
        Box(Modifier.fillMaxSize().background(Am.HomeBg).swipeOpens(page != 3 && !creating, rtl) { scope.launch { drawer.open() } }) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.captionBar))) {
                val picked = shown.filter { it.id in chosen }
                if (chosen.isNotEmpty() && page == 0) SelectionBar(
                    picked.size, picked.size == 1, picked.none { it.damaged },
                    onClose = { chosen = emptySet() },
                    onSelectAll = { chosen = shown.mapTo(LinkedHashSet()) { it.id } },
                    onExport = { picked.singleOrNull()?.let { chosen = emptySet(); onExport(it.id) } },
                    onDuplicate = { val ids = picked.map { it.id }; chosen = emptySet(); duplicate(ids) },
                    onSend = { picked.singleOrNull()?.let { chosen = emptySet(); DeviceSend.scan(context, it) } },
                    onDelete = { deletingMany = picked },
                ) else TopBar(onMenu = { scope.launch { drawer.open() } }, search, page, { search = it }, settings, onCustomizeTools = { customizingTools = true })
                val fill = Modifier.weight(1f)
                if (page == 3) CommunityTab(fill, engine) { onImportFile(Uri.fromFile(it)) }
                else if (page == 2) ToolsTab(fill, store, settings, customizingTools, { customizingTools = it }, onImportLink = { onImportLink?.invoke() }) { id -> version++; onOpen(id) }
                else if (page == 1) TemplatesTab(version + changes, onTemplate, onTemplateProject, fill, query, engine) else ProjectsPane(
                    Modifier.weight(1f), shown, loaded, tab, { tab = it }, settings, version + changes, onOpen, onExport, query,
                    onUpload = { picker.launch(arrayOf("text/xml", "application/xml", "application/zip", "application/octet-stream")) },
                    onImportLink = onImportLink,
                    onDuplicate = { id -> duplicate(listOf(id)) },
                    onDelete = { id -> delete(setOf(id)) },
                    onHistory = onHistory,
                    onCollect = onCollect,
                    wide = wide,
                    usedBy = store::linkedBy,
                    chosen = chosen,
                    onChosen = { chosen = it },
                )
                Spacer(Modifier.height(BarHeight + navInset))
            }
            BottomBar(onNew = newProject, page, { page = it; if (it >= 2) search = null; if (it != 2) customizingTools = false }, Modifier.align(Alignment.BottomCenter), navInset, wide, settings.vibration)
            if (drawer.isOpen || drawer.targetValue == DrawerValue.Open || drawer.isAnimationRunning) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    val up = waitForUpOrCancellation()
                    up?.consume()
                    if (up != null) scope.launch { drawer.close() }
                }
            })
            deletingMany?.let { targets ->
                val elements = targets.all { it.isElement }
                val users by produceState(emptyList<String>(), targets) { value = withContext(Dispatchers.IO) { targets.filter { it.isElement }.flatMap { store.linkedBy(it.id) }.distinct() } }
                AlertDialog(
                    onDismissRequest = { deletingMany = null },
                    title = { Text(pluralStringResource(if (elements) R.plurals.home_delete_assets_title else R.plurals.home_delete_projects_title, targets.size, targets.size), color = Am.Navy) },
                    text = if (users.isEmpty()) null else { { Text(stringResource(R.string.home_delete_asset_used, users.take(6).joinToString(", ") + if (users.size > 6) ", …" else ""), color = Am.Navy) } },
                    dismissButton = { DialogPill(stringResource(R.string.cancel), primary = false) { deletingMany = null } },
                    confirmButton = { DialogPill(stringResource(R.string.delete)) { deletingMany = null; chosen = emptySet(); delete(targets.mapTo(HashSet()) { it.id }) } },
                    containerColor = Am.Card,
                )
            }
            if (toReport.isNotEmpty()) ImportDialog(toReport, onDismiss = onImportShown, onOpen = { onImportShown(); toReport.first().let { if (it.template) onTemplate(it.id) else onOpen(it.id) } })
            Presence(creating) {
                NewProjectSheet(
                    store, settings, projects.map { it.title }, initialElement, opening, onClose = { creating = false },
                    onOpen = { id -> lastScroll.fill(0); version++; onOpen(id) },
                )
            }
        }
    }
}

internal fun Modifier.swipeOpens(enabled: Boolean, rtl: Boolean, anywhere: Boolean = false, onOpen: () -> Unit): Modifier = if (!enabled) this else pointerInput(rtl, anywhere) {
    val needed = 48.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type == PointerType.Mouse || (!anywhere && (down.position.x < size.width / 2f) == rtl)) return@awaitEachGesture
        var moved = 0f
        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            if ((over > 0f) != rtl) { change.consume(); moved = over }
        } ?: return@awaitEachGesture
        var opened = false
        horizontalDrag(start.id) { change ->
            moved += change.positionChange().x
            change.consume()
            if (!opened && (if (rtl) -moved else moved) > needed) { opened = true; onOpen() }
        }
    }
}

private var lastTab = 0
private var lastPage = 0
private val lastScroll = intArrayOf(0, 0)

private const val SearchAnimMs = 200

@Composable
private fun TopBar(onMenu: () -> Unit, search: String?, page: Int, onSearch: (String?) -> Unit, settings: AppSettings, onCustomizeTools: () -> Unit) {
    val searching = search != null
    var shownText by remember { mutableStateOf(search.orEmpty()) }
    if (search != null) shownText = search
    val focus = LocalFocusManager.current
    var wasSearching by remember { mutableStateOf(searching) }
    LaunchedEffect(searching) {
        if (wasSearching && !searching) focus.clearFocus()
        wasSearching = searching
    }
    val fade = remember { tween<Float>(SearchAnimMs, easing = FastOutSlowInEasing) }
    val size = remember { tween<IntSize>(SearchAnimMs, easing = FastOutSlowInEasing) }
    Box(Modifier.fillMaxWidth().height(57.dp)) {
        Box(Modifier.align(Alignment.CenterStart).size(50.dp).clickable(onClick = onMenu), contentAlignment = Alignment.Center) {
            Icon(HomeIcons.Menu, stringResource(R.string.settings), Modifier.size(24.dp), tint = Am.Navy)
            HoldName(stringResource(R.string.settings))
        }
        AnimatedVisibility(
            !searching,
            Modifier.align(Alignment.Center),
            enter = fadeIn(fade),
            exit = fadeOut(fade),
        ) {
            Text("AbsentMotion", color = Am.Navy, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
        AnimatedVisibility(
            !searching,
            Modifier.align(Alignment.CenterEnd).padding(end = 50.dp),
            enter = fadeIn(fade),
            exit = fadeOut(fade),
        ) {
            ChangelogButton(settings)
        }
        Box(Modifier.matchParentSize().padding(horizontal = 56.dp), contentAlignment = Alignment.CenterEnd) {
            AnimatedVisibility(
                searching,
                enter = expandHorizontally(size, expandFrom = Alignment.End) + fadeIn(fade),
                exit = shrinkHorizontally(size, shrinkTowards = Alignment.End) + fadeOut(fade),
            ) {
                val ready = transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
                HomeSearchField(shownText, page == 1, { if (searching) onSearch(it) }, Modifier.fillMaxWidth(), autoFocus = ready)
            }
        }
        if (page < 2) SearchButton(search, onSearch, Modifier.align(Alignment.CenterEnd))
        else if (page == 2) ToolsCustomizeButton(onCustomizeTools, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun SelectionBar(
    count: Int, single: Boolean, healthy: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit,
    onExport: () -> Unit, onDuplicate: () -> Unit, onSend: () -> Unit, onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(57.dp), verticalAlignment = Alignment.CenterVertically) {
        BarButton(HomeIcons.Close, stringResource(R.string.action_close), Modifier.size(50.dp), onClick = onClose)
        Text(
            pluralStringResource(R.plurals.home_selected, count, count), Modifier.weight(1f), color = Am.Navy, fontWeight = FontWeight.Medium, maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(12.sp, 18.sp, 1.sp),
        )
        BarButton(app.absentmotion.ui.editor.ChromeIcons.SelectAll, stringResource(R.string.home_select_all), onClick = onSelectAll)
        BarButton(HomeIcons.Export, stringResource(R.string.export), enabled = single && healthy, onClick = onExport)
        BarButton(HomeIcons.Duplicate, stringResource(R.string.home_duplicate), enabled = healthy, onClick = onDuplicate)
        BarButton(HomeIcons.Devices, stringResource(R.string.send_to_device), enabled = single && healthy, onClick = onSend)
        BarButton(HomeIcons.Delete, stringResource(R.string.delete), onClick = onDelete)
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, modifier: Modifier = Modifier.size(42.dp, 50.dp), enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.alpha(if (enabled) 1f else 0.35f).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(24.dp), tint = Am.Navy)
        HoldName(label)
    }
}

@Composable
internal fun HomeSearchField(search: String, templates: Boolean, onSearch: (String?) -> Unit, modifier: Modifier, autoFocus: Boolean = true) = SearchField(
    search, { onSearch(it) }, modifier.height(40.dp),
    hint = stringResource(if (templates) R.string.home_search_templates_hint else R.string.home_search_hint), background = Am.Card, content = Am.Navy, faint = Am.HomeDim, autoFocus = autoFocus,
)

@Composable
internal fun SearchButton(search: String?, onSearch: (String?) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(50.dp).clickable { onSearch(if (search == null) "" else null) }, contentAlignment = Alignment.Center) {
        Crossfade(
            search != null,
            animationSpec = tween(SearchAnimMs, easing = FastOutSlowInEasing),
            label = "searchIcon",
        ) { open ->
            Icon(if (!open) TextIcons.Search else HomeIcons.Close, stringResource(if (!open) R.string.home_search else R.string.home_close_search), Modifier.size(24.dp), tint = Am.Navy)
        }
        HoldName(stringResource(if (search == null) R.string.home_search else R.string.home_close_search))
    }
}

@Composable
private fun ProjectsPane(
    modifier: Modifier,
    shown: List<ProjectInfo>,
    loaded: Boolean,
    tab: Int,
    onTab: (Int) -> Unit,
    settings: AppSettings,
    version: Int,
    onOpen: (String) -> Unit,
    onExport: (String) -> Unit,
    query: String,
    onUpload: () -> Unit,
    onImportLink: (() -> Unit)?,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onHistory: (ProjectInfo) -> Unit,
    onCollect: (String) -> Unit,
    wide: Boolean = false,
    usedBy: (String) -> List<String> = { emptyList() },
    chosen: Set<String> = emptySet(),
    onChosen: (Set<String>) -> Unit = {},
) {
    val thumbs = remember {
        object : LinkedHashMap<String, Thumb>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Thumb>?) = size > 64
        }
    }
    val seen = remember { HashSet<Any>() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(lastScroll[0], lastScroll[1])
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(lastScroll[0], lastScroll[1])
    androidx.compose.runtime.DisposableEffect(wide) {
        onDispose {
            lastScroll[0] = if (wide) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex
            lastScroll[1] = if (wide) gridState.firstVisibleItemScrollOffset else listState.firstVisibleItemScrollOffset
        }
    }
    val context = LocalContext.current
    var menu by remember { mutableStateOf<OpenMenu?>(null) }
    var deleting by remember { mutableStateOf<ProjectInfo?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val byName = settings.sortBy == "name"
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val rows = remember(shown, byName, locale) {
        if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) listState.requestScrollToItem(0)
        if (gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0) gridState.requestScrollToItem(0)
        listRows(shown, byName, dateHeader(locale))
    }
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
            onSend = { menu = null; DeviceSend.scan(context, info) },
            vibrate = settings.vibration,
            selecting = chosen.isNotEmpty(),
            selected = info.id in chosen,
            onChoose = { menu = null; onChosen(if (info.id in chosen) chosen - info.id else chosen + info.id) },
        )
    }

    Box(modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(if (wide) 8.dp else 24.dp))
            Chips(tab, onTab, wide, onImportLink)
            if (loaded && shown.isEmpty() && tab == 0 && settings.xmlUpload && query.isBlank()) {
                Spacer(Modifier.height(13.dp))
                Box(Modifier.padding(horizontal = 16.dp).then(if (wide) WideCard else Modifier)) { UploadCard(onUpload) }
            }
            if (shown.isNotEmpty()) {
                Spacer(Modifier.height(13.dp))
                ListHeader(if (tab == 0) stringResource(R.string.home_your_projects) else null, byName) { settings.sortBy = if (it) "name" else "date" }
                if (wide) ProjectGrid(rows, tab == 0 && settings.xmlUpload, onUpload, gridState, card) else LazyColumn(
                    Modifier.fillMaxSize(), listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                ) {
                    if (tab == 0 && settings.xmlUpload) item(key = "upload") {
                        UploadCard(onUpload)
                        Spacer(Modifier.height(24.dp))
                    }
                    itemsIndexed(rows, key = { _, r -> if (r is ListRow.Card) "p-" + r.info.id else "date-${(r as ListRow.Date).text}" }) { i, row ->
                        when (row) {
                            is ListRow.Date -> DateLabel(row.text, first = i == 0, Modifier.animateItem(fadeInSpec = Motion.fast(), placementSpec = Motion.enter(), fadeOutSpec = Motion.exit()))
                            is ListRow.Card -> card(row.info, i, Modifier.animateItem(fadeInSpec = Motion.fast(), placementSpec = Motion.enter(), fadeOutSpec = Motion.exit()))
                        }
                    }
                }
            }
        }
        if (loaded && shown.isEmpty() && query.isNotBlank()) NoMatches(query, if (tab == 1) R.string.search_no_assets else R.string.search_no_projects, Modifier.fillMaxWidth().padding(top = 120.dp), Am.Navy, 17.sp)
        else if (loaded && shown.isEmpty()) EmptyState(element = tab == 1)
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
        val users by produceState(emptyList<String>(), target.id) { if (target.isElement) value = withContext(Dispatchers.IO) { usedBy(target.id) } }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(if (target.isElement) R.string.home_delete_asset_title else R.string.home_delete_project_title), color = Am.Navy) },
            text = if (users.isEmpty()) null else { { Text(stringResource(R.string.home_delete_asset_used, users.take(6).joinToString(", ") + if (users.size > 6) ", …" else ""), color = Am.Navy) } },
            dismissButton = { DialogPill(stringResource(R.string.cancel), primary = false) { deleting = null } },
            confirmButton = { DialogPill(stringResource(R.string.delete)) { deleting = null; onDelete(target.id) } },
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
internal fun ImportDialog(reports: List<ImportReport>, onDismiss: () -> Unit, onOpen: () -> Unit) {
    if (adaptiveWindowIsWide()) {
        Dialog(onDismiss, sheetDialogProperties()) {
            AdaptiveWindow(1000.dp, 620.dp) {
                Column(Modifier.fillMaxSize().background(Am.Card).padding(24.dp)) {
                    Text(stringResource(R.string.import_complete), color = Am.Navy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                        ImportReports(reports)
                    }
                    ImportButtons(onDismiss, onOpen)
                }
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_complete), color = Am.Navy) },
        text = {
            Column {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    ImportReports(reports)
                }
                ImportButtons(onDismiss, onOpen)
            }
        },
        confirmButton = {},
        containerColor = Am.Card,
    )
}

@Composable
private fun ImportButtons(onDismiss: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton(stringResource(R.string.ok), Modifier.weight(1f).fillMaxHeight(), primary = false, onClick = onDismiss)
        BigButton(stringResource(R.string.open), Modifier.weight(1f).fillMaxHeight(), onClick = onOpen)
    }
}

@Composable
private fun ImportReports(reports: List<ImportReport>) {
    for (report in reports) {
        report.community?.let { CommunityCard(it) }
        if (reports.size > 1 && report.community == null) Text(report.title, Modifier.padding(top = 8.dp), color = Am.Navy, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        for ((heading, lines) in report.sections) {
            Text(heading.text(), Modifier.padding(top = 8.dp), color = Am.Navy, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            for (line in lines) Text("\u2022 ${line.text()}", color = Am.Navy, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
internal fun CommunityCard(info: CommunityInfo) {
    val picture = remember(info) {
        info.thumbnail?.let { bytes ->
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val sampled = android.graphics.BitmapFactory.Options().apply { inSampleSize = app.absentmotion.media.pictureSample(maxOf(bounds.outWidth, bounds.outHeight), 1024) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampled)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val details: @Composable () -> Unit = {
        Text(info.title, Modifier.padding(top = 4.dp), color = Am.Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (info.creator.isNotEmpty()) Text(stringResource(R.string.community_by, info.creator), color = Am.Navy.copy(alpha = 0.7f), fontSize = 14.sp)
        if (info.description.isNotEmpty()) Text(info.description, color = Am.Navy, fontSize = 14.sp, lineHeight = 19.sp)
        if (info.tags.isNotEmpty()) Text(info.tags.joinToString("  \u00B7  "), color = Am.Navy.copy(alpha = 0.7f), fontSize = 13.sp)
    }
    if (LocalAdaptiveSpace.current?.wide == true && picture != null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Image(picture, null, Modifier.weight(0.4f).heightIn(max = 300.dp).aspectRatio((picture.width.toFloat() / picture.height).coerceIn(0.2f, 5f)).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
            Column(Modifier.weight(0.6f), verticalArrangement = Arrangement.spacedBy(4.dp)) { details() }
        }
    } else Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        picture?.let { Image(it, null, Modifier.fillMaxWidth().aspectRatio((it.width.toFloat() / it.height).coerceIn(0.2f, 5f)).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit) }
        details()
    }
}

@Composable
internal fun DateLabel(text: String, first: Boolean, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = if (first) 0.dp else 12.dp, bottom = 12.dp), color = Am.Navy, fontSize = 15.sp, lineHeight = 19.sp)
}

internal fun listRows(list: List<ProjectInfo>, byName: Boolean, dates: DateTimeFormatter): List<ListRow> =
    if (byName) {
        list.sortedWith(compareBy(java.text.Collator.getInstance(dates.locale)) { it.title }).map { ListRow.Card(it) }
    } else {
        list.sortedByDescending { it.modified }
            .groupBy { Instant.ofEpochMilli(it.modified).atZone(ZoneId.systemDefault()).format(dates) }
            .flatMap { (date, items) -> listOf<ListRow>(ListRow.Date(date)) + items.map { ListRow.Card(it) } }
    }

@Composable
private fun Chips(tab: Int, onTab: (Int) -> Unit, crisp: Boolean, onImportLink: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (crisp) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip(stringResource(R.string.home_tab_projects), tab == 0, crisp) { onTab(0) }
        Chip(stringResource(R.string.home_tab_assets), tab == 1, crisp) { onTab(1) }
        if (tab == 0 && onImportLink != null) {
            Spacer(Modifier.weight(1f))
            val label = stringResource(R.string.project_link_import)
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onImportLink), contentAlignment = Alignment.Center) {
                Icon(HomeIcons.Link, label, Modifier.size(24.dp), tint = Am.Navy)
                HoldName(label)
            }
        }
    }
}

@Composable
private fun ListHeader(title: String?, byName: Boolean, onSort: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.orEmpty(), Modifier.weight(1f), color = Am.Navy, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2)
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
    thumbs: MutableMap<String, Thumb>,
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
    onSend: () -> Unit,
    vibrate: Boolean,
    selecting: Boolean = false,
    selected: Boolean = false,
    onChoose: () -> Unit = {},
) {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val pick = remember { HoldPick() }
    Box(
        modifier.padding(bottom = 12.dp).fillMaxWidth().heightIn(min = 96.dp)
            .onGloballyPositioned { coords[0] = it }
            .clip(RoundedCornerShape(12.dp)).background(Am.Card)
            .border(2.dp, animatedColor(if (selected) Am.Accent else Color.Transparent), RoundedCornerShape(12.dp))
            .combinedClickable(onLongClick = onChoose, onClick = if (selecting) onChoose else onOpen),
    ) {
        Thumbnail(info, version, thumbs, Modifier.padding(8.dp))
        Column(Modifier.padding(start = 104.dp, top = 16.dp, end = 52.dp, bottom = 12.dp)) {
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
        if (selecting) Box(Modifier.align(Alignment.CenterEnd).size(48.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(animatedColor(if (selected) Am.Accent else Color.Transparent))
                    .border(2.dp, animatedColor(if (selected) Am.Accent else Grey), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(app.absentmotion.ui.editor.PanelIcons.Check, null, Modifier.size(16.dp), tint = Am.OnAccent)
            }
        } else Box(
            Modifier.align(Alignment.CenterEnd).size(48.dp).clickable { coords[0]?.let { onMenu(it.boundsInRoot()) } }
                .holdToPick(pick, vibrate) { coords[0]?.let { onMenu(it.boundsInRoot()) } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(HomeIcons.More, stringResource(R.string.home_more), Modifier.size(24.dp), tint = Grey)
        }
        Box(Modifier.align(Alignment.BottomEnd).offset(y = 8.dp)) {
            RefMenu(menuOpen, onDismissMenu, Am.Card, RoundedCornerShape(14.dp), Modifier.width(270.dp)) {
                if (!info.damaged) {
                    MenuRow(stringResource(R.string.export), HomeIcons.Export, onExport, pick)
                    MenuRow(stringResource(if (info.isElement) R.string.home_duplicate_asset else R.string.home_duplicate), HomeIcons.Duplicate, onDuplicate, pick)
                    MenuRow(stringResource(R.string.home_collect_media), HomeIcons.Folder, onCollect, pick)
                }
                MenuRow(stringResource(R.string.version_history), ChromeIcons.Undo, onHistory, pick)
                if (!info.damaged) MenuRow(stringResource(R.string.send_to_device), HomeIcons.Devices, onSend, pick)
                MenuRow(stringResource(if (info.isElement) R.string.home_delete_asset else R.string.delete), HomeIcons.Delete, onDelete, pick)
            }
        }
    }
}

@Composable
internal fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit, pick: HoldPick? = null) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).then(pick?.row(label, onClick) ?: Modifier).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = Am.HomeInk, fontSize = 16.sp)
        Icon(icon, null, Modifier.size(24.dp), tint = Am.HomeInk)
    }
}

internal class HoldPick {
    var hovered by mutableStateOf<String?>(null)
    private val rows = HashMap<String, Pair<LayoutCoordinates, () -> Unit>>()

    fun row(key: String, action: () -> Unit): Modifier = Modifier
        .onGloballyPositioned { rows[key] = it to action }
        .drawBehind { if (hovered == key) drawRect(Am.Navy.copy(alpha = 0.1f)) }

    fun at(screen: Offset): String? = rows.entries.firstOrNull { (_, row) ->
        row.first.isAttached && Rect(row.first.localToScreen(Offset.Zero), row.first.size.toSize()).contains(screen)
    }?.key

    fun run(key: String) = rows[key]?.second?.invoke()
}

private const val HoldMs = 300L

internal fun Modifier.holdToPick(pick: HoldPick, vibrate: Boolean, open: () -> Unit): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val buzz by androidx.compose.runtime.rememberUpdatedState(vibrate)
    val where = remember { arrayOfNulls<LayoutCoordinates>(1) }
    onGloballyPositioned { where[0] = it }.pointerInput(pick) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var ended = false
            withTimeoutOrNull(HoldMs) { waitForUpOrCancellation().also { ended = true } }
            if (ended) return@awaitEachGesture
            open()
            if (buzz) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                val screen = where[0]?.takeIf { it.isAttached }?.localToScreen(change.position)
                val over = screen?.let(pick::at)
                if (!change.pressed) {
                    pick.hovered = null
                    over?.let(pick::run)
                    break
                }
                if (buzz && over != null && over != pick.hovered) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                pick.hovered = over
            }
        }
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    Row(Modifier.widthIn(min = 85.dp).heightIn(min = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = Grey)
        Spacer(Modifier.width(6.dp))
        Text(text, color = FactText, fontSize = 12.sp, maxLines = 1)
    }
}

internal fun facts(p: ProjectInfo): List<List<Pair<ImageVector, String>>> {
    val ratio = aspectName(p.width, p.height)
    val first = if (ratio != null) {
        listOf(HomeIcons.Aspect to ratio, HomeIcons.Resolution to "${minOf(p.width, p.height)}p")
    } else {
        listOf(HomeIcons.Resolution to "${p.width}x${p.height}")
    }
    return listOf(first, listOf(HomeIcons.Folder to formatBytes(p.sizeBytes), HomeIcons.FrameRate to "${p.fps}fps"))
}

internal fun duration(ms: Int): String {
    val s = (ms + 500) / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}

internal class Thumb(val stamp: Long, val image: ImageBitmap)

@Composable
private fun Thumbnail(info: ProjectInfo, version: Int, cache: MutableMap<String, Thumb>, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(cache[info.id]?.image, info.id, info.modified, info.thumbnail, version) {
        val file = info.thumbnail
        if (file == null) {
            value = null
            return@produceState
        }
        val stamp = withContext(Dispatchers.IO) { file.lastModified() }
        cache[info.id]?.takeIf { it.stamp == stamp }?.let { value = it.image; return@produceState }
        val decoded = withContext(Dispatchers.IO) { decodeThumbnail(file) }
        if (decoded != null) {
            cache[info.id] = Thumb(stamp, decoded)
            value = decoded
        }
    }
    Box(modifier.size(80.dp).clip(RoundedCornerShape(8.dp)).checkerboard(5.dp)) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        if (!info.damaged) Text(
            duration(info.durationMs),
            Modifier.align(Alignment.BottomStart).padding(4.dp).background(Am.Navy, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            color = Am.OnNavy, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(8.sp, 13.sp),
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
private fun EmptyState(element: Boolean) {
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
        Icon(HomeIcons.ArrowDown, null, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).size(56.dp), tint = Am.HomeInk)
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

internal object BarSlide {
    fun tab(x: Float, width: Float, gap: Float, tools: Boolean, rtl: Boolean): Int? {
        val side = (width - gap) / 2f
        if (side <= 0f) return null
        val at = (if (rtl) width - x else x).coerceIn(0f, width)
        return when {
            at < side / 2f -> 0
            at < side -> 3
            at <= side + gap -> null
            at < side + gap + side / 2f -> 1
            tools -> 2
            else -> null
        }
    }
}

@Composable
private fun BottomBar(onNew: () -> Unit, page: Int, onPage: (Int) -> Unit, modifier: Modifier, navInset: Dp, wide: Boolean, vibrate: Boolean) {
    val haptics = LocalHapticFeedback.current
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == LayoutDirection.Rtl
    val gap = with(LocalDensity.current) { 56.dp.toPx() }
    val tools = app.absentmotion.platform.PlatformFeatures.tools
    var held by remember { mutableStateOf(false) }
    val current by androidx.compose.runtime.rememberUpdatedState(page)
    val go by androidx.compose.runtime.rememberUpdatedState(onPage)
    val buzz by androidx.compose.runtime.rememberUpdatedState(vibrate)
    Box(modifier.fillMaxWidth().height(BarHeight + PlusOverhang + navInset)) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(BarHeight + navInset).shadow(8.dp).background(Am.Card))
        Row(
            Modifier.align(Alignment.BottomCenter).then(if (wide) Modifier.widthIn(max = 600.dp) else Modifier).fillMaxWidth().height(BarHeight + navInset)
                .pointerInput(rtl, gap, tools) {
                    awaitEachGesture {
                        val initial = androidx.compose.ui.input.pointer.PointerEventPass.Initial
                        val down = awaitFirstDown(requireUnconsumed = false, pass = initial)
                        val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var done = false
                            while (!done) {
                                val change = awaitPointerEvent(initial).changes.firstOrNull { it.id == down.id }
                                done = change == null || !change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            }
                        }
                        if (early != null) return@awaitEachGesture
                        var at = current
                        fun slide(x: Float): Boolean {
                            val next = BarSlide.tab(x, size.width.toFloat(), gap, tools, rtl) ?: return false
                            if (next == at) return false
                            at = next
                            go(next)
                            return true
                        }
                        held = true
                        if (buzz) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        slide(down.position.x)
                        try {
                            while (true) {
                                val change = awaitPointerEvent(initial).changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                if (slide(change.position.x) && buzz) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        } finally { held = false }
                    }
                }.padding(top = 12.dp),
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                BarItem(stringResource(R.string.home_tab_projects), if (page == 0) HomeIcons.ProjectsTab else HomeIcons.ProjectsTabOff, held && page == 0) { onPage(0) }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                BarItem(stringResource(R.string.home_tab_community), if (page == 3) HomeIcons.CommunityTabOn else HomeIcons.CommunityTab, held && page == 3) { onPage(3) }
            }
            Spacer(Modifier.width(56.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                BarItem(stringResource(R.string.home_tab_templates), if (page == 1) HomeIcons.TemplatesTabOn else HomeIcons.TemplatesTab, held && page == 1) { onPage(1) }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                if (tools) BarItem(stringResource(R.string.home_tab_tools), if (page == 2) HomeIcons.ToolsTabOn else HomeIcons.ToolsTab, held && page == 2) { onPage(2) }
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
            HoldName(stringResource(R.string.home_new_project))
        }
    }
}

@Composable
private fun BarItem(label: String, icon: ImageVector, lit: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val glow by androidx.compose.animation.core.animateFloatAsState(if (pressed || lit) 1f else 0f, androidx.compose.animation.core.tween(140), label = "bar")
    val ink = Am.Navy
    Column(
        Modifier.widthIn(min = 78.dp).heightIn(min = 48.dp).drawBehind {
            if (glow > 0f) {
                val w = 64.dp.toPx()
                val h = 32.dp.toPx()
                drawRoundRect(ink, Offset((size.width - w) / 2f, 12.dp.toPx() - h / 2f), Size(w, h), CornerRadius(h / 2f), alpha = 0.1f * glow)
            }
        }.clickable(interactionSource = source, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(24.dp).graphicsLayer { val s = 1f - 0.1f * glow; scaleX = s; scaleY = s }, tint = Am.Navy)
        Spacer(Modifier.height(8.dp))
        Text(label, Modifier.padding(horizontal = 2.dp), color = Am.Navy, fontWeight = FontWeight.Medium, maxLines = 1, style = androidx.compose.ui.text.TextStyle(lineHeight = 16.sp, fontFamily = androidx.compose.material3.LocalTextStyle.current.fontFamily),
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(9.sp, 12.sp))
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
    scroll: androidx.compose.foundation.ScrollState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Presence(expanded) {
        Popup(popupPositionProvider = MenuPosition, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
            Surface(Modifier.presence(scaleFrom = 0.94f, origin = TransformOrigin(1f, 0f)), color = color, shape = shape, shadowElevation = 8.dp) {
                Column(modifier.heightIn(max = maxHeight).verticalScroll(scroll ?: rememberScrollState()), content = content)
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
