package app.absentmotion.desk

import android.content.Desk
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import android.content.mime
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import app.absentmotion.ui.home.DeskModals
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.compat.EffectMigration
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.ElementNav
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.PreviewCache
import app.absentmotion.engine.EngineThread
import app.absentmotion.fonts.FontName
import app.absentmotion.model.fontNames
import app.absentmotion.i18n.Ltr
import app.absentmotion.export.DeskExports
import app.absentmotion.export.ExportRun
import app.absentmotion.library.AppSettings
import app.absentmotion.library.CrashLogs
import app.absentmotion.library.MediaKeeper
import app.absentmotion.library.RecoveryHandler
import app.absentmotion.library.RecoveryOffer
import app.absentmotion.library.sweepStaleTemps
import app.absentmotion.library.sweepStorageDaily
import app.absentmotion.ui.home.autoBackupIfDue
import app.absentmotion.library.DiskThread
import app.absentmotion.library.ImportReport
import app.absentmotion.library.Importer
import app.absentmotion.library.MediaRelink
import app.absentmotion.library.ProjectSaver
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.ProjectVersions
import app.absentmotion.library.Recovery
import app.absentmotion.library.TemplateStore
import app.absentmotion.model.Scene
import app.absentmotion.model.ShapeCatalog
import app.absentmotion.ui.editor.DeskTopBar
import app.absentmotion.ui.editor.EditorScreen
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.editor.MediaPicks
import app.absentmotion.ui.editor.wideEditor
import app.absentmotion.ui.export.ExportScreen
import app.absentmotion.ui.home.DeskButton
import app.absentmotion.ui.onboarding.DeskOnboarding
import app.absentmotion.ui.onboarding.FirstRun
import app.absentmotion.ui.tutorial.Tutorial
import app.absentmotion.ui.tutorial.TutorialHost
import app.absentmotion.ui.home.DeskHome
import app.absentmotion.ui.home.DeskModal
import app.absentmotion.ui.home.DeskMessage
import app.absentmotion.ui.home.DeskSettings
import app.absentmotion.ui.home.SettingsPage
import app.absentmotion.ui.home.UpdateDialog
import app.absentmotion.ui.home.OpeningOverlay
import app.absentmotion.ui.home.Updates
import app.absentmotion.ui.home.OpenFailedDialog
import app.absentmotion.ui.home.TemplateEditor
import app.absentmotion.ui.home.VersionHistory
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.PressIndication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import app.absentmotion.ui.home.WhatsNewDialog
import app.absentmotion.ui.home.WhatsNewNotes
import app.absentmotion.platform.LocalContext

private val PaletteFiles = setOf("gpl", "ase", "act", "pal")

class DeskAppState {
    val context = Desk.context
    val store = ProjectStore(context)
    val settings = AppSettings(context)
    val templates = TemplateStore(context)
    val recovery = Recovery(File(context.filesDir, "recovery"))
    val engine by lazy { EngineThread(background = Am.PreviewBg.toArgb(), context = context) { false } }
    var editor by mutableStateOf<EditorState?>(null)
    var exportingFromList by mutableStateOf<Scene?>(null)
    var imported by mutableStateOf<List<ImportReport>>(emptyList())
    var editingTemplate by mutableStateOf<String?>(null)
    var opening by mutableStateOf(false)
    var openFailed by mutableStateOf<String?>(null)
    var importFailed by mutableStateOf<String?>(null)
    var historyFor by mutableStateOf<Triple<String, String, Boolean>?>(null)
    var projectsChanged by mutableIntStateOf(0)
    var recovered by mutableStateOf<RecoveryOffer?>(null)
    var settingsOpen by mutableStateOf(false)
    var settingsPage by mutableStateOf(SettingsPage.Editor)
    var newProjectTick by mutableIntStateOf(0)
    var saveFailed by mutableStateOf(false)
    var fontOffer by mutableStateOf<Pair<EditorState, List<String>>?>(null)
    var afterClose: (() -> Unit)? = null
    var hidden by mutableStateOf(false)
    var quitAsk by mutableStateOf(false)
    var quitNow: (() -> Unit)? = null
    var job: Job? = null
    private var closeAnyway = false
    private val fontsDeclined = HashSet<String>()
    private val closeLock = Mutex()
    private val openLock = Mutex()
    private val background = MainScope()
    val migration by lazy { EffectMigration.read(context) }

    fun openSettings(page: SettingsPage = SettingsPage.Editor) { settingsPage = page; settingsOpen = true }

    suspend fun startup() {
        RecoveryHandler.install(recovery) { editor?.let { it.projectId to it.project.toXml() } }
        runCatching { CrashLogs.install(context) }
        ElementNav.open = { id ->
            editor?.let { it.playing = false; it.endGesture(); it.saver.snapshot(it.project.toXml()) }
            MultiSelect.clear()
            background.launch { openNow(id) }
        }
        recovered = withContext(DiskThread.dispatcher) { runCatching { recovery.pending(store) }.getOrNull() }
    }

    suspend fun answerRecovery(offer: RecoveryOffer, restore: Boolean) {
        recovered = null
        val done = withContext(DiskThread.dispatcher) { runCatching { if (restore) recovery.restore(store, offer) else recovery.discard(store, offer) }.isSuccess }
        projectsChanged++
        when {
            !done -> toast(R.string.home_restore_edits_failed)
            restore -> openNow(offer.id)
            else -> recovered = withContext(DiskThread.dispatcher) { runCatching { recovery.pending(store) }.getOrNull() }
        }
    }

    suspend fun maintainNow() {
        autoBackupIfDue(context, settings)
        sweepStorageDaily(context)
        withContext(Dispatchers.IO) { runCatching { sweepStaleTemps(context.filesDir, context.cacheDir) }; runCatching { app.absentmotion.export.MediaStoreExport(context).discardAbandoned() } }
    }

    suspend fun maintain() {
        delay(15_000)
        while (editor != null || opening || editingTemplate != null || exportingFromList != null) delay(30_000)
        maintainNow()
    }

    suspend fun collectMedia(id: String) {
        if (opening) return
        opening = true
        val result = try {
            withContext(Dispatchers.IO) {
                runCatching {
                    store.load(id)?.let { scene ->
                        MediaKeeper(store, { uri -> context.contentResolver.openInputStream(Uri.parse(uri)) }).collect(id, MediaRelink.relink(context, scene)).also {
                            if (it.copied > 0) {
                                store.text(id)?.let { old -> store.versions.snapshot(id, old) }
                                store.save(id, it.scene)
                            }
                        }
                    }
                }.getOrNull()
            }
        } finally { opening = false }
        projectsChanged++
        val message = when {
            result == null -> context.getString(R.string.home_collect_unreadable_project)
            result.copied == 0 && result.unreadable == 0 && !result.noSpace -> context.getString(R.string.home_collect_already_kept)
            else -> listOfNotNull(
                result.copied.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.home_collect_copied, it, it.toString()) },
                result.unreadable.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.home_collect_could_not_read, it, it.toString()) },
                context.getString(R.string.home_collect_storage_full).takeIf { result.noSpace },
            ).joinToString(" ")
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun toast(id: Int) = Toast.makeText(context, context.getString(id), Toast.LENGTH_LONG).show()

    suspend fun saveThumbnail(scene: Scene, timeMs: Int, id: String): Boolean {
        val longSide = maxOf(scene.width, scene.height, 1)
        return engine.stillPng(scene.toXml(), timeMs, (256 * scene.width / longSide).coerceAtLeast(1), (256 * scene.height / longSide).coerceAtLeast(1), store.thumbnailFile(id))
    }

    suspend fun load(id: String): Scene? {
        opening = true
        val scene = try {
            withContext(DiskThread.dispatcher) {
                store.text(id)?.let { runCatching { store.versions.snapshot(id, it) } }
                store.load(id)
            }?.let { scene ->
                withContext(Dispatchers.IO) {
                    ShapeCatalog.load(context)
                    migration.migrate(MediaRelink.relink(context, scene)).scene.also { if (it != scene) runCatching { store.save(id, it) } }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { android.util.Log.e("AMStore", "cannot open project $id", e); null }
        finally { opening = false }
        if (scene == null) openFailed = id
        return scene
    }

    suspend fun openNow(id: String): EditorState? {
        val scene = load(id) ?: return null
        val saver = ProjectSaver(id, store, recovery, failed = { javax.swing.SwingUtilities.invokeLater { toast(R.string.home_save_project_failed) } })
        return EditorState(id, scene, store, engine, settings, saver).also { DeskLibrary.open(id, it.scene.media.map { m -> m.uri }); editor = it; offerFonts(it) }
    }

    private fun offerFonts(state: EditorState) {
        val library = engine.fonts ?: return
        if (System.getProperty("am.fontOffer") == "off" || state.projectId in fontsDeclined) return
        background.launch {
            val missing = withContext(Dispatchers.IO) { state.project.fontNames().filter { FontName.parse(it).source == "googlefonts" && library.localFile(it) == null } }
            if (missing.isNotEmpty() && editor === state) fontOffer = state to missing
        }
    }

    fun declineFonts(state: EditorState) {
        fontsDeclined += state.projectId
        fontOffer = null
    }

    fun downloadFonts(names: List<String>) {
        fontOffer = null
        val library = engine.fonts ?: return
        background.launch {
            if (names.count { library.resolve(it, download = true) == null } > 0) toast(R.string.font_load_failed_body)
        }
    }

    fun closeAnyway() { saveFailed = false; closeAnyway = true; closeSoon() }

    fun retryClose() { saveFailed = false; closeSoon() }

    suspend fun closeEditor() {
        val closed = closeLock.withLock { closeNow() }
        if (closed) afterClose?.let { afterClose = null; it() }
    }

    private suspend fun closeNow(): Boolean {
        val state = editor ?: return true
        state.playing = false
        PreviewCache.remove()
        state.endGesture()
        state.leaveGroups()
        val scene = state.scene
        withContext(Dispatchers.IO) { runCatching { state.saver.flush().get() } }
        if (state.saver.failing && !closeAnyway) {
            saveFailed = true
            return false
        }
        closeAnyway = false
        try {
            state.saver.snapshot(scene.toXml())
            engine.audio?.release()
            saveThumbnail(scene, state.timeMs, state.projectId)
        } finally {
            MultiSelect.clear()
            DeskLibrary.close()
            editor = null
            projectsChanged++
        }
        return true
    }

    fun closeSoon() { background.launch { closeEditor() } }

    fun requestQuit() {
        if (ExportRun.running) { hidden = false; quitAsk = true; return }
        background.launch { if (shutdown()) quitNow?.invoke() else afterClose = quitNow }
    }

    fun keepExporting() {
        quitAsk = false
        background.launch {
            exportingFromList = null
            if (shutdown()) hidden = true
        }
    }

    fun stopAndQuit() {
        quitAsk = false
        background.launch {
            ExportRun.cancel()
            withTimeoutOrNull(5000) { while (ExportRun.running) delay(50) }
            if (shutdown()) quitNow?.invoke() else afterClose = quitNow
        }
    }

    suspend fun shutdown(timeoutMs: Long = 4000): Boolean {
        val closing = background.launch { closeEditor() }
        val finished = withTimeoutOrNull(timeoutMs) { closing.join(); true } ?: false
        return finished && !saveFailed
    }

    fun takePalettes(files: List<File>): List<File> = files.filterNot { file ->
        val colors = if (file.extension.lowercase() in PaletteFiles && file.length() <= app.absentmotion.ui.editor.panels.MaxPaletteBytes) runCatching { app.absentmotion.effects.Palettes.fromFile(file.name, file.readBytes()) }.getOrNull() else null
        if (colors != null) app.absentmotion.library.PaletteLibrary.at(context.filesDir).let { it.choose(it.create(file.nameWithoutExtension, colors).id) }
        colors != null
    }

    fun queueMedia(uris: List<Uri>) {
        MediaPicks.forgetTarget()
        val sounds = uris.filter { android.content.Desk.mime(it.toString())?.startsWith("audio/") == true }
        val pictures = uris - sounds.toSet()
        if (pictures.isNotEmpty()) MediaPicks.addPictures(pictures)
        if (sounds.isNotEmpty()) MediaPicks.addSounds(sounds)
    }

    fun openLater(files: List<File>) { job = background.launch { openLock.withLock { openFiles(files) } } }

    suspend fun openFiles(files: List<File>) {
        DeskLibrary.remember(files)
        val projects = files.filter(DeskOpen::isProject)
        val media = files - projects.toSet()
        if (projects.isNotEmpty()) {
            val reports = importFiles(projects.map(Uri::fromFile))
            val single = reports.singleOrNull()?.takeIf { !it.template }
            if (single != null && editor == null) { imported = emptyList(); openNow(single.id) }
        }
        if (media.isEmpty()) return
        val uris = media.map(Uri::fromFile)
        if (editor != null) return queueMedia(uris)
        val title = media.first().nameWithoutExtension.ifBlank { context.getString(R.string.home_new_project) }
        val id = try { withContext(Dispatchers.IO) { store.create(Scene.create(title, 1920, 1080, 30, 0xFF000000.toInt())) } }
        catch (e: java.io.IOException) { toast(R.string.home_create_project_failed); return }
        projectsChanged++
        queueMedia(uris)
        openNow(id)
    }

    suspend fun importFiles(uris: List<Uri>): List<ImportReport> {
        val reports = ArrayList<ImportReport>()
        opening = true
        try {
            withContext(Dispatchers.IO) {
                val importer = Importer.forApp(context, store)
                for (uri in uris) try {
                    val xfl = DeskOpen.xflZip(uri)
                    try {
                        reports += importer.import { xfl?.inputStream() ?: context.contentResolver.openInputStream(uri) }.map {
                            if (!it.template) it else templates.adopt(store, it.id, engine)?.let { id -> it.copy(id = id) } ?: it.copy(template = false)
                        }
                    } finally {
                        xfl?.delete()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { importFailed = e.message ?: context.getString(R.string.library_err_unsupported_type) }
            }
        } finally { opening = false; projectsChanged++ }
        if (reports.isNotEmpty()) imported = reports
        for (report in reports) if (!report.template) {
            val scene = withContext(Dispatchers.IO) { store.load(report.id) } ?: continue
            saveThumbnail(scene, scene.totalTime / 2, report.id)
        }
        projectsChanged++
        return reports
    }

    suspend fun projectFromTemplate(scene: Scene) {
        val project = try { withContext(Dispatchers.IO) { templates.createProject(scene, store) } }
        catch (e: java.io.IOException) { toast(R.string.home_create_project_failed); return }
        editingTemplate = null
        openNow(project)
    }

    suspend fun restoreVersion(id: String, version: ProjectVersions.Version) {
        val state = editor?.takeIf { it.projectId == id }
        state?.let { it.playing = false; it.endGesture(); it.historyOpen = false }
        historyFor = null
        val failure = withContext(DiskThread.dispatcher) { runCatching { store.restore(id, version) }.exceptionOrNull() }
        projectsChanged++
        when {
            failure != null -> toast(if (failure is ProjectStore.DamagedVersion) R.string.home_restore_version_damaged else R.string.home_restore_version_failed)
            state != null && editor === state -> openNow(id)
            else -> toast(R.string.home_version_restored)
        }
    }
}

@Composable
fun DeskApp(app: DeskAppState) {
    val base = LocalDensity.current
    val scale = DeskScale.percent / 100f
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (!focused) DeskKeys.releaseAll() }
    val clicks = LocalViewConfiguration.current
    val system = remember(clicks) { systemClicks(clicks) }
    CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale), LocalViewConfiguration provides system) { DeskTyping.Track { DeskContent(app, scale) } }
}

internal fun systemClicks(base: ViewConfiguration): ViewConfiguration {
    val interval = runCatching { java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int }.getOrNull() ?: 500
    return object : ViewConfiguration by base {
        override val doubleTapTimeoutMillis = interval.toLong().coerceAtLeast(base.doubleTapTimeoutMillis)
        override val doubleTapMinTimeMillis = 0L
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DeskContent(app: DeskAppState, scale: Float) {
    val scope = rememberCoroutineScope()
    fun run(block: suspend () -> Unit) {
        if (app.opening) return
        app.job = scope.launch { block() }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { ShapeCatalog.load(app.context) } }
        fun picked() = DeskFiles.chooseFiles(media = true, multiple = true).map(Uri::fromFile).also { Tutorial.pickerClosed(it.isNotEmpty()) }.takeIf { it.isNotEmpty() }
        MediaPicks.install(app,
            { picked()?.let { MediaPicks.addPictures(it) } },
            { picked()?.let { MediaPicks.addPictures(it) } },
            { picked()?.let { MediaPicks.addSounds(it) } },
        ) { app.editor?.projectId }
    }
    LaunchedEffect(Unit) { if (app.settings.autoUpdateCheck) Updates.atStart(app.context, app.settings) }
    LaunchedEffect(Unit) { app.startup() }
    LaunchedEffect(Unit) { DeskPreload.run(app) }
    LaunchedEffect(Unit) { app.maintain() }
    LaunchedEffect(DeskOpen.arrived.intValue) {
        val files = DeskOpen.take()
        if (files.isNotEmpty()) app.openLater(files)
    }
    LaunchedEffect(app.editor) {
        val state = app.editor ?: return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.snapshotFlow { MediaPicks.waiting }.first { it > 0 }
            MediaPicks.deliver(state, app.context)
        }
    }
    val open = app.editor
    var firstRun by remember { mutableStateOf(FirstRun.of(app.settings, System.getProperty("am.onboarding"))) }
    val context = LocalContext.current
    var whatsNew by remember { mutableStateOf(if (System.getProperty("am.whatsNew") == "off") null else WhatsNewNotes.pending(context, app.settings)) }
    val density = LocalDensity.current
    val homePress = remember(Am.PressHome) { PressIndication(Am.PressHome) }
    val editorPress = remember(Am.PressEditor) { PressIndication(Am.PressEditor) }
    val drop = remember(app) { DeskDrop(app) { block -> run(block) } }
    val titleBar = (DeskWindow.chrome.strip / scale).dp
    val barColor = if (open == null && app.editingTemplate == null) Am.HomeBg else Am.TopBar
    Box(Modifier.fillMaxSize().background(barColor)) {
    Column(Modifier.fillMaxSize().padding(top = titleBar)) {
    CompositionLocalProvider(LocalIndication provides if (open == null) homePress else editorPress) {
        DeskWindowBar(app, barColor) { menu ->
            if (open != null) DeskTopBar(open, { scope.launch { app.closeEditor() } }, menu)
            else {
                menu()
                Text("AbsentMotion", Modifier.padding(start = 8.dp), color = if (barColor.luminance() > .5f) Am.Navy else Am.OnDark, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).dragAndDropTarget(shouldStartDragAndDrop = { true }, target = drop)) {
        val config = remember(maxWidth, maxHeight) {
            android.content.res.Configuration().apply {
                screenWidthDp = maxWidth.value.toInt(); screenHeightDp = maxHeight.value.toInt()
                smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
            }
        }
        CompositionLocalProvider(
            LocalConfiguration provides config,
            LocalIndication provides if (open == null) homePress else editorPress,
            LocalWide provides wideEditor(config.smallestScreenWidthDp, config.screenWidthDp),
        ) {
            val first = firstRun
            if (open == null && first != null) {
                DeskOnboarding(app.settings, first) { firstRun = null; Tutorial.firstRunFinished(app.settings) }
            } else if (open == null) {
                Box(Modifier.fillMaxSize()) {
                    DeskHome(
                        app.store, app.settings, onOpen = { id -> run { app.openNow(id) } }, onExport = { id -> run { app.exportingFromList = app.load(id) } },
                        imported = app.imported, onImportShown = { app.imported = emptyList() }, onImportFiles = { uris -> run { app.importFiles(uris) } },
                        onTemplate = { id -> app.editingTemplate = id }, onTemplateProject = { scene -> run { app.projectFromTemplate(scene) } },
                        onHistory = { app.historyFor = Triple(it.id, it.title, it.damaged) }, onSettings = { app.openSettings() }, changes = app.projectsChanged,
                        newTick = app.newProjectTick, onCollect = { id -> scope.launch { app.collectMedia(id) } },
                    )
                    app.editingTemplate?.let { id ->
                        TemplateEditor(id, app.engine, app.settings, onClose = { app.editingTemplate = null }, onContinue = { scene -> run { app.projectFromTemplate(scene) } })
                    }
                    app.exportingFromList?.let { ExportScreen(it, 0, app.engine, fromProjectList = true, null) { app.exportingFromList = null } }
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.3f))) {
                        key(open) { Ltr { EditorScreen(open, onExit = { scope.launch { app.closeEditor() } }) } }
                    }
                    if (open.exportOpen) ExportScreen(open.project, open.exportFrame, app.engine, fromProjectList = false, null) { open.exportOpen = false }
                }
            }
            if (app.opening) OpeningOverlay { app.job?.cancel() }
            app.openFailed?.let { id -> OpenFailedDialog({ app.openFailed = null; app.historyFor = Triple(id, "", false) }, null) { app.openFailed = null } }
            app.importFailed?.let { reason -> DeskMessage(stringResource(R.string.home_import_failed, reason)) { app.importFailed = null } }
            if (open?.historyOpen == true) VersionHistory(app.store, open.projectId, open.project.title, app.engine, onRestore = { v -> scope.launch { app.restoreVersion(open.projectId, v) } }, editor = true) { open.historyOpen = false }
            app.historyFor?.let { (id, title, _) ->
                VersionHistory(app.store, id, title, app.engine, onRestore = { v -> scope.launch { app.restoreVersion(id, v) } }) { app.historyFor = null }
            }
            if (app.settingsOpen) DeskSettings(app.settings, app.settingsPage, onClose = { app.settingsOpen = false }, onRestored = { app.projectsChanged++ })
            app.recovered?.takeIf { open == null }?.let { offer -> DeskRecovery(offer, { scope.launch { app.answerRecovery(offer, it) } }) { app.recovered = null } }
            whatsNew?.takeIf { open == null && first == null && !app.opening && app.editingTemplate == null && app.exportingFromList == null && app.recovered == null && !app.settingsOpen }?.let { notes ->
                WhatsNewDialog(notes) { whatsNew = null; WhatsNewNotes.seen(context, app.settings) }
            }
            TutorialHost(
                open, app.store, app.settings,
                homeFree = open == null && first == null && !app.opening && app.editingTemplate == null && app.exportingFromList == null && app.recovered == null && app.imported.isEmpty() && !app.settingsOpen && Updates.answer == null && whatsNew == null,
                onOpen = { id -> run { app.openNow(id) } }, onCloseEditor = { scope.launch { app.closeEditor() } }, onChanged = { app.projectsChanged++ },
            )
            app.fontOffer?.takeIf { it.first === open }?.let { (state, names) -> DeskFontOffer(names, { app.downloadFonts(names) }) { app.declineFonts(state) } }
            if (app.saveFailed && open != null) DeskSaveFailed(app)
            if (app.quitAsk) DeskQuitAsk(app)
            UpdateDialog(app::requestQuit)
            ToastHost(Modifier.align(Alignment.BottomCenter))
            if (open?.exportOpen != true && app.exportingFromList == null) DeskExportPill(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp))
        }
    }
    }
    if (DeskModals.open > 0 && titleBar > 0.dp) Box(Modifier.fillMaxWidth().height(titleBar).background(Color.Black.copy(alpha = DeskModals.ScrimAlpha * DeskModals.fade)))
    }
}

@Composable
private fun DeskRecovery(offer: RecoveryOffer, answer: (restore: Boolean) -> Unit, later: () -> Unit) {
    DeskModal(later, 480.dp, dismissOnScrim = false) {
        Text(stringResource(R.string.absentmotion_closed_unexpectedly), Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp), color = Am.Navy, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(
            stringResource(R.string.safety_recovery_body, offer.title.ifBlank { stringResource(R.string.recovery_your_project) }),
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 8.dp), color = Am.HomeInk, fontSize = 14.sp, lineHeight = 20.sp,
        )
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(20.dp), androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp, Alignment.End)) {
            DeskButton(stringResource(R.string.discard), { answer(false) })
            DeskButton(stringResource(R.string.restore), { answer(true) }, primary = true, minWidth = 92.dp)
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private class DeskDrop(private val app: DeskAppState, private val run: (suspend () -> Unit) -> Unit) : androidx.compose.ui.draganddrop.DragAndDropTarget {
    override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean {
        val transfer = event.awtTransferable
        if (!transfer.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) return false
        val dropped = (runCatching { transfer.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<*> }.getOrNull() ?: return false).filterIsInstance<java.io.File>().filter { it.isFile }
        if (dropped.isEmpty()) return false
        val files = app.takePalettes(dropped)
        if (files.isEmpty()) return true
        DeskLibrary.remember(files)
        val uris = files.map(Uri::fromFile)
        if (app.editor == null) { run { app.importFiles(uris) }; return true }
        app.queueMedia(uris)
        return true
    }
}

@Composable
private fun ToastHost(modifier: Modifier) {
    val toast = Toast.current.value ?: return
    LaunchedEffect(toast) {
        delay(if (toast.duration == Toast.LENGTH_LONG) 3500 else 2000)
        if (Toast.current.value === toast) Toast.current.value = null
    }
    Box(modifier.padding(bottom = 48.dp).widthIn(max = 520.dp).clip(RoundedCornerShape(20.dp)).background(Am.Field).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(toast.text.toString(), color = Am.OnDark, fontSize = 14.sp)
    }
}
