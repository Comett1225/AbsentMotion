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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.compat.EffectMigration
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.PreviewCache
import app.absentmotion.engine.EngineThread
import app.absentmotion.i18n.Ltr
import app.absentmotion.library.AppSettings
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
import app.absentmotion.ui.editor.EditorScreen
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.editor.MediaPicks
import app.absentmotion.ui.editor.wideEditor
import app.absentmotion.ui.export.ExportScreen
import app.absentmotion.ui.home.HomeScreen
import app.absentmotion.ui.home.OpeningOverlay
import app.absentmotion.ui.home.OpenFailedDialog
import app.absentmotion.ui.home.TemplateEditor
import app.absentmotion.ui.home.VersionHistory
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.PressIndication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    var job: Job? = null
    private var closing = false
    val migration by lazy { EffectMigration.read(context) }

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
        return EditorState(id, scene, store, engine, settings, saver).also { editor = it }
    }

    suspend fun closeEditor() {
        val state = editor ?: return
        if (closing) return
        closing = true
        state.playing = false
        PreviewCache.remove()
        state.endGesture()
        val scene = state.scene
        try {
            withContext(Dispatchers.IO) { runCatching { state.saver.flush().get() } }
            state.saver.snapshot(scene.toXml())
            engine.audio?.release()
            saveThumbnail(scene, state.timeMs, state.projectId)
        } finally {
            MultiSelect.clear()
            editor = null
            closing = false
            projectsChanged++
        }
    }

    suspend fun importFiles(uris: List<Uri>) {
        val reports = ArrayList<ImportReport>()
        opening = true
        try {
            withContext(Dispatchers.IO) {
                val importer = Importer.forApp(context, store)
                for (uri in uris) try {
                    reports += importer.import { context.contentResolver.openInputStream(uri) }.map {
                        if (!it.template) it else templates.adopt(store, it.id, engine)?.let { id -> it.copy(id = id) } ?: it.copy(template = false)
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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DeskApp(app: DeskAppState) {
    val scope = rememberCoroutineScope()
    fun run(block: suspend () -> Unit) {
        if (app.opening) return
        app.job = scope.launch { block() }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { ShapeCatalog.load(app.context) } }
        MediaPicks.install(app,
            { DeskFiles.chooseFiles(media = true, multiple = true).map(Uri::fromFile).takeIf { it.isNotEmpty() }?.let { MediaPicks.addPictures(it) } },
            { DeskFiles.chooseFiles(media = true, multiple = true).map(Uri::fromFile).takeIf { it.isNotEmpty() }?.let { MediaPicks.addPictures(it) } },
            { DeskFiles.chooseFiles(media = true, multiple = true).map(Uri::fromFile).takeIf { it.isNotEmpty() }?.let { MediaPicks.addSounds(it) } },
        ) { app.editor?.projectId }
    }
    LaunchedEffect(app.editor) {
        val state = app.editor ?: return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.snapshotFlow { MediaPicks.waiting }.first { it > 0 }
            MediaPicks.deliver(state, app.context)
        }
    }
    val open = app.editor
    val density = LocalDensity.current
    val homePress = remember(Am.PressHome) { PressIndication(Am.PressHome) }
    val editorPress = remember(Am.PressEditor) { PressIndication(Am.PressEditor) }
    val drop = remember(app) { DeskDrop(app) { block -> run(block) } }
    BoxWithConstraints(Modifier.fillMaxSize().background(if (open == null) Am.HomeBg else Am.TopBar).dragAndDropTarget(shouldStartDragAndDrop = { true }, target = drop)) {
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
            if (open == null) {
                Box(Modifier.fillMaxSize()) {
                    HomeScreen(
                        app.store, app.settings, onOpen = { id -> run { app.openNow(id) } }, onExport = { id -> run { app.exportingFromList = app.load(id) } },
                        imported = app.imported, onImportShown = { app.imported = emptyList() }, onImportFile = { uri -> run { app.importFiles(listOf(uri)) } },
                        onTemplate = { id -> app.editingTemplate = id }, onTemplateProject = { scene -> run { app.projectFromTemplate(scene) } },
                        onHistory = { app.historyFor = Triple(it.id, it.title, it.damaged) }, onCollect = {}, changes = app.projectsChanged,
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
                    if (open.exportOpen) ExportScreen(open.scene, open.frame, app.engine, fromProjectList = false, null) { open.exportOpen = false }
                }
            }
            if (app.opening) OpeningOverlay { app.job?.cancel() }
            app.openFailed?.let { id -> OpenFailedDialog({ app.openFailed = null; app.historyFor = Triple(id, "", false) }, null) { app.openFailed = null } }
            app.importFailed?.let { reason -> AlertDialog(onDismissRequest = { app.importFailed = null }, text = { Text(stringResource(R.string.home_import_failed, reason), color = Am.Navy) },
                confirmButton = { TextButton({ app.importFailed = null }) { Text(stringResource(R.string.ok), color = Am.Navy) } }, containerColor = Am.Card) }
            if (open?.historyOpen == true) VersionHistory(app.store, open.projectId, open.scene.title, app.engine, onRestore = { v -> scope.launch { app.restoreVersion(open.projectId, v) } }, editor = true) { open.historyOpen = false }
            app.historyFor?.let { (id, title, _) ->
                VersionHistory(app.store, id, title, app.engine, onRestore = { v -> scope.launch { app.restoreVersion(id, v) } }) { app.historyFor = null }
            }
            ToastHost(Modifier.align(Alignment.BottomCenter))
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private class DeskDrop(private val app: DeskAppState, private val run: (suspend () -> Unit) -> Unit) : androidx.compose.ui.draganddrop.DragAndDropTarget {
    override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean {
        val transfer = event.awtTransferable
        if (!transfer.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) return false
        val files = (runCatching { transfer.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<*> }.getOrNull() ?: return false).filterIsInstance<java.io.File>().filter { it.isFile }
        if (files.isEmpty()) return false
        val uris = files.map(Uri::fromFile)
        if (app.editor == null) { run { app.importFiles(uris) }; return true }
        val sounds = uris.filter { android.content.Desk.mime(it.toString())?.startsWith("audio/") == true }
        val pictures = uris - sounds.toSet()
        if (pictures.isNotEmpty()) MediaPicks.addPictures(pictures)
        if (sounds.isNotEmpty()) MediaPicks.addSounds(sounds)
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
