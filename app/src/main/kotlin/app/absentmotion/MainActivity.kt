package app.absentmotion

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import app.absentmotion.media.dropThumbnails
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.editor.dropShapeIcons
import app.absentmotion.ui.editor.dropVideoThumbnails
import app.absentmotion.ui.editor.isTablet
import app.absentmotion.ui.editor.wideEditor
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.absentmotion.compat.EffectMigration
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.engine.EngineThread
import app.absentmotion.library.AppSettings
import app.absentmotion.library.DiskThread
import app.absentmotion.library.ProjectSaver
import app.absentmotion.library.ProjectVersions
import app.absentmotion.library.Recovery
import app.absentmotion.library.RecoveryHandler
import app.absentmotion.library.RecoveryOffer
import app.absentmotion.library.ImportFailed
import app.absentmotion.library.ImportReport
import app.absentmotion.library.Importer
import app.absentmotion.library.MediaKeeper
import app.absentmotion.library.MediaRelink
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.TemplateStore
import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatFloats
import app.absentmotion.model.fontNames
import app.absentmotion.fonts.FontName
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import app.absentmotion.ui.home.EmbedRequest
import app.absentmotion.ui.home.TemplateEditor
import app.absentmotion.model.Scene
import app.absentmotion.model.withCopiedLayerIds
import app.absentmotion.model.ShapeCatalog
import app.absentmotion.ui.editor.EditorScreen
import app.absentmotion.ui.export.ExportScreen
import app.absentmotion.ui.home.HomeScreen
import app.absentmotion.ui.home.LowMemoryNotice
import app.absentmotion.i18n.Ltr
import app.absentmotion.i18n.inAppLanguage
import app.absentmotion.ui.onboarding.FirstRun
import app.absentmotion.ui.onboarding.Onboarding
import app.absentmotion.ui.home.OpenFailedDialog
import app.absentmotion.ui.home.OpeningOverlay
import app.absentmotion.ui.home.ClosedDialog
import app.absentmotion.ui.home.OutdatedHint
import app.absentmotion.ui.home.outdatedNow
import app.absentmotion.library.CrashEntry
import app.absentmotion.library.CrashLogs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.absentmotion.ui.home.UnsupportedPhone
import app.absentmotion.ui.home.UpdateAskDialog
import app.absentmotion.ui.home.UpdateDialog
import app.absentmotion.ui.home.Updates
import app.absentmotion.ui.home.autoBackupIfDue
import app.absentmotion.ui.home.VersionHistory
import app.absentmotion.ui.theme.PressIndication
import app.absentmotion.ui.theme.enterFade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import app.absentmotion.ui.theme.Am

class MainActivity : ComponentActivity() {
    internal lateinit var store: ProjectStore
    internal lateinit var settings: AppSettings
    private lateinit var templates: TemplateStore
    internal val engine by lazy { EngineThread(background = Am.PreviewBg.toArgb(), context = applicationContext) }
    internal var editor by mutableStateOf<EditorState?>(null)
        private set
    private var closingEditor = false
    private var cameFromEditor = false
    private var exportingFromList by mutableStateOf<Scene?>(null)
    private var imported by mutableStateOf<List<ImportReport>>(emptyList())
    private var editingTemplate by mutableStateOf<String?>(null)
    private var firstRun by mutableStateOf<FirstRun?>(null)
    private var opening by mutableStateOf(false)
    private var openFailed by mutableStateOf<String?>(null)
    private var openJob: kotlinx.coroutines.Job? = null
    private var closeAnyway = false
    private var saveFailed by mutableStateOf(false)
    private var historyFor by mutableStateOf<Triple<String, String, Boolean>?>(null)
    private var projectsChanged by mutableIntStateOf(0)
    private lateinit var recovery: Recovery
    private var recovered by mutableStateOf<RecoveryOffer?>(null)
    private var crashed by mutableStateOf<CrashEntry?>(null)
    private var importFailed by mutableStateOf<String?>(null)
    private var fontOffer by mutableStateOf<Pair<EditorState, List<String>>?>(null)
    private val fontsDeclined = HashSet<String>()
    private var engineProblem by mutableStateOf<String?>(null)
    private var problemShown by mutableStateOf(false)
    private var elementNav: ((String) -> Unit)? = null

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(newBase.inAppLanguage())

    override fun onCreate(savedInstanceState: Bundle?) {
        val started = SystemClock.elapsedRealtime()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app.absentmotion.i18n.keepLegacyLanguage(this)
        app.absentmotion.i18n.AppText.install(this)
        followScreenSize(resources.configuration)
        store = ProjectStore(this)
        settings = AppSettings(this)
        templates = TemplateStore(this)
        recovery = Recovery(File(filesDir, "recovery"))
        CrashLogs.install(this)
        RecoveryHandler.install(recovery) { editor?.let { it.projectId to it.scene.toXml() } }
        val reopen = savedInstanceState?.getString(OPEN_PROJECT)
        lifecycleScope.launch {
            val (offer, crash) = withContext(DiskThread.dispatcher) { runCatching { recovery.pending(store) }.getOrNull() to runCatching { CrashLogs.collect(applicationContext) }.getOrNull() }
            crashed = crash
            recovered = offer
            if (reopen != null && offer?.id != reopen && editor == null && !opening) openJob = lifecycleScope.launch {
                val state = openNow(reopen) ?: return@launch
                state.frame = savedInstanceState.getInt(OPEN_FRAME).coerceIn(0, state.scene.lastFrame)
                if (savedInstanceState.containsKey(OPEN_LAYER)) state.selectedId = savedInstanceState.getLong(OPEN_LAYER)
                if (state.selected == null) state.selectedId = null
            }
        }
        lifecycleScope.launch {
            kotlinx.coroutines.delay(15_000)
            while (editor != null || opening || editingTemplate != null || exportingFromList != null || firstRun != null || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) kotlinx.coroutines.delay(30_000)
            autoBackupIfDue(applicationContext, settings)
            app.absentmotion.library.sweepStorageDaily(applicationContext)
        }
        lifecycleScope.launch(Dispatchers.IO) { ShapeCatalog.load(applicationContext) }
        lifecycleScope.launch(Dispatchers.IO) { app.absentmotion.ai.AiDevice.recover(applicationContext) }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { app.absentmotion.export.MediaStoreExport(applicationContext).discardAbandoned() }
            runCatching { app.absentmotion.library.sweepStaleTemps(filesDir, cacheDir) }
        }
        val openElement: (String) -> Unit = { id ->
            editor?.let { it.playing = false; it.endGesture(); it.saver.snapshot(it.scene.toXml()) }
            MultiSelect.clear()
            open(id)
        }
        elementNav = openElement
        app.absentmotion.editor.ElementNav.open = openElement
        TestHooks.start(this)
        lifecycleScope.launch { engineWorks() }
        firstRun = FirstRun.of(settings, TestHooks.onboarding(intent).takeIf { savedInstanceState == null })
        importShared()
        setContent {
            val open = editor
            val light = if (open == null && editingTemplate == null) Am.homeIsLight else Am.editorIsLight
            LaunchedEffect(light) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(if (light) SystemBarStyle.light(transparent, transparent) else SystemBarStyle.dark(transparent))
            }
            val density = LocalDensity.current.density
            val fontScale = LocalDensity.current.fontScale
            val dense = remember(density, fontScale) { Density(density, fontScale.coerceAtMost(EDITOR_FONT_LIMIT)) }
            val editorPress = remember(Am.PressEditor) { PressIndication(Am.PressEditor) }
            val homePress = remember(Am.PressHome) { PressIndication(Am.PressHome) }
            CompositionLocalProvider(LocalIndication provides if (open == null) homePress else editorPress, LocalDensity provides remember(density, fontScale) { Density(density, fontScale.coerceAtMost(UI_FONT_LIMIT)) }) {
                val first = firstRun
                val outdated = outdatedNow(settings)
                val screen = when {
                    open != null -> "editing a project"
                    first != null -> "first start"
                    editingTemplate != null -> "editing a template"
                    else -> "home"
                }
                LaunchedEffect(screen) { CrashLogs.onScreen(screen) }
                if (open == null && first != null) {
                    Onboarding(settings, first, { engine }) { firstRun = null; app.absentmotion.ui.tutorial.Tutorial.firstRunFinished(settings) }
                } else if (open == null) {
                    LaunchedEffect(settings.autoUpdateCheck) { if (settings.autoUpdateCheck) Updates.atStart(applicationContext, settings) }
                    val returning = remember { cameFromEditor }
                    Box(Modifier.fillMaxSize().then(if (returning) Modifier.enterFade() else Modifier)) {
                        val config = LocalConfiguration.current
                        CompositionLocalProvider(LocalWide provides wideEditor(config.smallestScreenWidthDp, config.screenWidthDp)) {
                            HomeScreen(
                                store, settings, onOpen = ::open, onExport = ::exportFromList,
                                imported = imported, onImportShown = { imported = emptyList() }, onImportFile = { importUris(listOf(it)) },
                                onTemplate = { id -> lifecycleScope.launch { if (engineWorks()) editingTemplate = id } }, onTemplateProject = ::projectFromTemplate,
                                onHistory = { historyFor = Triple(it.id, it.title, it.damaged) }, onCollect = ::collectMedia, changes = projectsChanged,
                            )
                        }
                        editingTemplate?.let { id ->
                            CompositionLocalProvider(LocalDensity provides dense) { TemplateEditor(id, engine, settings, onClose = { editingTemplate = null }, onContinue = ::projectFromTemplate) }
                        }
                        exportingFromList?.let { ExportScreen(it, 0, engine, fromProjectList = true, outdated) { exportingFromList = null } }
                        LowMemoryNotice()
                    }
                } else {
                    Box(Modifier.fillMaxSize()) {
                        val embed = EmbedRequest.templateId
                        if (embed != null) CompositionLocalProvider(LocalDensity provides dense) { TemplateEditor(embed, engine, settings, onClose = { endEmbed(open) }) { scene -> embedTemplate(open, scene) } }
                        else {
                            val screen = LocalConfiguration.current
                            CompositionLocalProvider(LocalWide provides wideEditor(screen.smallestScreenWidthDp, screen.screenWidthDp), app.absentmotion.ui.editor.panels.LocalVibration provides settings.vibration, LocalDensity provides dense) {
                                key(open) { Ltr { EditorScreen(open, onExit = ::closeEditor) } }
                            }
                        }
                        if (open.exportOpen) ExportScreen(open.scene, open.frame, engine, fromProjectList = false, outdated) { open.exportOpen = false }
                    }
                }
                if (opening) OpeningOverlay { openJob?.cancel() }
                engineProblem?.takeIf { problemShown }?.let { UnsupportedPhone(it) { problemShown = false } }
                openFailed?.let { id -> OpenFailedDialog({ openFailed = null; historyFor = Triple(id, "", false) }, outdated) { openFailed = null } }
                importFailed?.let { reason ->
                    AlertDialog(
                        onDismissRequest = { importFailed = null },
                        text = {
                            Column {
                                Text(stringResource(R.string.home_import_failed, reason), color = Am.Navy)
                                OutdatedHint(outdated, Modifier.padding(top = 8.dp))
                            }
                        },
                        confirmButton = { TextButton({ importFailed = null }) { Text(stringResource(R.string.ok), color = Am.Navy) } },
                        containerColor = Am.Card,
                    )
                }
                fontOffer?.takeIf { it.first === open }?.let { (state, names) ->
                    AlertDialog(
                        onDismissRequest = { declineFonts(state) },
                        title = { Text(stringResource(R.string.fonts_missing_title)) },
                        text = { Text(stringResource(R.string.fonts_missing_body, names.joinToString(", ") { FontName.parse(it).label })) },
                        confirmButton = { TextButton({ downloadFonts(names) }) { Text(stringResource(R.string.update_download), color = Am.Accent) } },
                        dismissButton = { TextButton({ declineFonts(state) }) { Text(stringResource(R.string.update_not_now), color = Am.Accent) } },
                        containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark,
                    )
                }
                if (saveFailed && open != null) AlertDialog(
                    onDismissRequest = { saveFailed = false },
                    text = { Text(stringResource(R.string.home_close_save_failed)) },
                    confirmButton = { TextButton({ saveFailed = false; closeEditor() }) { Text(stringResource(R.string.home_save_failed_retry), color = Am.Accent) } },
                    dismissButton = { TextButton({ saveFailed = false; closeAnyway = true; closeEditor() }) { Text(stringResource(R.string.home_save_failed_leave), color = Am.Accent) } },
                    containerColor = Am.Field, textContentColor = Am.OnDark,
                )
                if (open?.historyOpen == true) VersionHistory(store, open.projectId, open.scene.title, engine, onRestore = { restoreVersion(open.projectId, it) }) { open.historyOpen = false }
                historyFor?.let { (id, title, damaged) ->
                    VersionHistory(store, id, title, engine, onRestore = { restoreVersion(id, it) }, onDelete = if (damaged) { { deleteDamaged(id) } } else null) { historyFor = null }
                }
                val offer = recovered
                if (problemShown) Unit
                else if (open == null && !opening && (offer != null || crashed != null)) ClosedDialog(
                    crashed, offer?.title, outdated,
                    onRestore = { seenCrash(outdated != null); offer?.let { answerRecovery(it, true) } },
                    onDiscard = { seenCrash(outdated != null); offer?.let { answerRecovery(it, false) } },
                    onClose = { seenCrash(outdated != null) },
                ) else if (open == null && first == null && !opening && editingTemplate == null && exportingFromList == null) {
                    if (settings.updatesAsked) UpdateDialog() else UpdateAskDialog(settings)
                }
                app.absentmotion.ui.tutorial.TutorialHost(
                    open, store, settings,
                    homeFree = open == null && first == null && !opening && editingTemplate == null && exportingFromList == null && offer == null && crashed == null && !problemShown && imported.isEmpty(),
                    onOpen = ::open, onCloseEditor = ::closeEditor, onChanged = { projectsChanged++ },
                )
            }
        }
        Log.i("AMStart", "onCreate took ${SystemClock.elapsedRealtime() - started} ms")
        androidx.core.view.OneShotPreDrawListener.add(window.decorView) { Log.i("AMStart", "first frame after ${SystemClock.elapsedRealtime() - started} ms") }
    }



    private fun importShared() {
        val uris = ImportHandOff.take().map(Uri::parse)
        if (uris.isNotEmpty()) importUris(uris)
    }

    private fun importUris(uris: List<Uri>) {
        lifecycleScope.launch {
            val reports = ArrayList<ImportReport>()
            var failure: String? = null
            val overlay = !opening
            if (overlay) { opening = true; openJob = coroutineContext.job }
            try {
                CrashLogs.during("importing") { withContext(Dispatchers.IO) {
                    val job = coroutineContext.job
                    val importer = Importer.forApp(applicationContext, store)
                    for (uri in uris) try {
                        reports += importer.import { contentResolver.openInputStream(uri)?.let { stoppable(it, job) } }.map {
                            if (!it.template) it else templates.adopt(store, it.id, engine)?.let { id -> it.copy(id = id) } ?: it.copy(template = false)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("AMImport", "cannot import $uri", e)
                        failure = failure ?: (e as? ImportFailed)?.text?.resolve(this@MainActivity) ?: getString(R.string.backup_open_failed).takeIf { e is SecurityException } ?: e.message ?: getString(R.string.library_err_unsupported_type)
                    }
                } }
            } finally {
                if (overlay) opening = false
                projectsChanged++
            }
            failure?.let { importFailed = it }
            if (reports.isNotEmpty()) imported = reports
            var drawn = false
            for (report in reports) if (!report.template) {
                val scene = withContext(Dispatchers.IO) { store.load(report.id) } ?: continue
                drawn = saveThumbnail(scene, scene.totalTime / 2, report.id) || drawn
            }
            if (drawn) projectsChanged++
        }
    }

    private fun stoppable(input: java.io.InputStream, job: kotlinx.coroutines.Job) = object : java.io.FilterInputStream(input) {
        override fun read(): Int { job.ensureActive(); return super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { job.ensureActive(); return super.read(b, off, len) }
    }

    private fun offerFonts(state: EditorState) {
        val library = engine.fonts ?: return
        if (state.projectId in fontsDeclined) return
        lifecycleScope.launch {
            val missing = withContext(Dispatchers.IO) { state.scene.fontNames().filter { FontName.parse(it).source == "googlefonts" && library.localFile(it) == null } }
            if (missing.isNotEmpty() && editor === state) fontOffer = state to missing
        }
    }

    private fun declineFonts(state: EditorState) {
        fontsDeclined += state.projectId
        fontOffer = null
    }

    private fun downloadFonts(names: List<String>) {
        fontOffer = null
        val library = engine.fonts ?: return
        lifecycleScope.launch {
            if (names.count { library.resolve(it, download = true) == null } > 0) Toast.makeText(this@MainActivity, getString(R.string.font_load_failed_body), Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun saveThumbnail(scene: Scene, timeMs: Int, id: String): Boolean {
        val longSide = maxOf(scene.width, scene.height, 1)
        return engine.stillPng(scene.toXml(), timeMs, (256 * scene.width / longSide).coerceAtLeast(1), (256 * scene.height / longSide).coerceAtLeast(1), store.thumbnailFile(id))
    }

    private fun projectFromTemplate(scene: Scene) {
        if (opening) return
        opening = true
        openJob = lifecycleScope.launch {
            val project = try { withContext(Dispatchers.IO) { templates.createProject(scene, store) } }
            catch (e: java.io.IOException) {
                Toast.makeText(this@MainActivity, getString(R.string.home_create_project_failed), Toast.LENGTH_LONG).show()
                return@launch
            } finally { opening = false }
            editingTemplate = null
            open(project)
        }
    }

    private fun endEmbed(state: EditorState) {
        EmbedRequest.templateId = null
        engine.setScene(state.scene)
    }

    private fun embedTemplate(state: EditorState, template: Scene) {
        if (opening) return
        opening = true
        openJob = lifecycleScope.launch {
            val local = try { withContext(Dispatchers.IO) { templates.localize(template, store.mediaDir(state.projectId)) } }
            catch (e: java.io.IOException) {
                Toast.makeText(this@MainActivity, getString(R.string.template_add_failed), Toast.LENGTH_LONG).show()
                return@launch
            } finally { opening = false }
            val start = state.scene.frameStart(state.frame)
            val id = Layer.newId()
            val copied = local.withCopiedLayerIds().node
            val inner = copied.copy(children = copied.children.filterNot { it.name == "media" })
            val layer = Layer(XmlNode("embedScene", linkedMapOf("id" to "$id", "label" to local.title.ifBlank { getString(R.string.template) }, "startTime" to "$start", "endTime" to "${start + local.totalTime}"),
                listOf(XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(state.scene.width / 2f, state.scene.height / 2f, 0f)))))), inner)))
            state.edit { it.add(layer, local.media) }
            state.selectedId = id
            endEmbed(state)
        }
    }

    internal val migration by lazy { EffectMigration.read(applicationContext) }

    private suspend fun loadInBackground(id: String): Scene? {
        opening = true
        val started = SystemClock.elapsedRealtime()
        val scene = try {
            val loaded = CrashLogs.during("opening a project") {
                withContext(DiskThread.dispatcher) {
                    store.text(id)?.let { runCatching { store.versions.snapshot(id, it) } }
                    store.load(id)
                }
            }
            loaded?.let { scene ->
                withContext(Dispatchers.IO) {
                    ShapeCatalog.load(applicationContext)
                    migration.migrate(MediaRelink.relink(this@MainActivity, scene)).scene.also {
                        if (it != scene) try { store.save(id, it) } catch (e: java.io.IOException) { Log.e("AMStore", "cannot save the updated project $id", e) }
                    }
                }
            }.also { Log.i("AMStart", "project $id loaded in ${SystemClock.elapsedRealtime() - started} ms") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("AMStore", "cannot open project $id", e)
            null
        } finally {
            opening = false
        }
        if (scene == null) openFailed = id
        return scene
    }

    private fun exportFromList(id: String) {
        if (opening) return
        openJob = lifecycleScope.launch { if (engineWorks()) exportingFromList = loadInBackground(id) }
    }

    private fun open(id: String) {
        if (opening) return
        openJob = lifecycleScope.launch { openNow(id) }
    }

    private suspend fun engineWorks(): Boolean {
        val problem = (kotlinx.coroutines.withTimeoutOrNull(30_000) { engine.startProblem.await() ?: "" } ?: "engine start timed out").ifEmpty { return true }
        engineProblem = problem
        problemShown = true
        return false
    }

    internal suspend fun openNow(id: String): EditorState? {
        if (!engineWorks()) return null
        val scene = loadInBackground(id) ?: return null
        cameFromEditor = true
        val saver = ProjectSaver(id, store, recovery, failed = { runOnUiThread { Toast.makeText(this, getString(R.string.home_save_project_failed), Toast.LENGTH_LONG).show() } })
        return EditorState(id, scene, store, engine, settings, saver).also { editor = it; offerFonts(it) }
    }

    private fun collectMedia(id: String) {
        if (opening) return
        opening = true
        openJob = lifecycleScope.launch {
            val result = try { withContext(Dispatchers.IO) {
                runCatching {
                    store.load(id)?.let { scene ->
                        MediaKeeper(store, { uri -> contentResolver.openInputStream(Uri.parse(uri)) }).collect(id, MediaRelink.relink(this@MainActivity, scene)).also {
                            if (it.copied > 0) {
                                store.text(id)?.let { old -> store.versions.snapshot(id, old) }
                                store.save(id, it.scene)
                            }
                        }
                    }
                }.onFailure { Log.e("AMStore", "cannot collect the media of $id", it) }.getOrNull()
            } } finally { opening = false }
            projectsChanged++
            val message = when {
                result == null -> getString(R.string.home_collect_unreadable_project)
                result.copied == 0 && result.unreadable == 0 && !result.noSpace -> getString(R.string.home_collect_already_kept)
                else -> listOfNotNull(
                    result.copied.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.home_collect_copied, it, it.toString()) },
                    result.unreadable.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.home_collect_could_not_read, it, it.toString()) },
                    getString(R.string.home_collect_storage_full).takeIf { result.noSpace },
                ).joinToString(" ")
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun seenCrash(hush: Boolean) {
        crashed?.let { CrashLogs.markShown(applicationContext, it.time) }
        crashed = null
        if (hush) Updates.hush()
    }

    private fun answerRecovery(offer: RecoveryOffer, restore: Boolean) {
        recovered = null
        lifecycleScope.launch {
            val done = withContext(DiskThread.dispatcher) {
                runCatching { if (restore) recovery.restore(store, offer) else recovery.discard(store, offer) }
                    .onFailure { Log.e("AMStore", "cannot answer the recovery of ${offer.id}", it) }.isSuccess
            }
            projectsChanged++
            when {
                !done -> Toast.makeText(this@MainActivity, getString(R.string.home_restore_edits_failed), Toast.LENGTH_LONG).show()
                restore -> open(offer.id)
                else -> recovered = withContext(DiskThread.dispatcher) { runCatching { recovery.pending(store) }.getOrNull() }
            }
        }
    }

    private fun deleteDamaged(id: String) {
        historyFor = null
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { store.delete(id, getExternalFilesDir(null)?.resolve("ai")) }
            projectsChanged++
        }
    }

    private fun restoreVersion(id: String, version: ProjectVersions.Version) {
        val state = editor?.takeIf { it.projectId == id }
        state?.let { it.playing = false; it.endGesture(); it.historyOpen = false }
        opening = true
        historyFor = null
        openJob = lifecycleScope.launch {
            val failure = try {
                withContext(DiskThread.dispatcher) {
                    runCatching { store.restore(id, version) }.onFailure { Log.e("AMStore", "cannot restore a version of $id", it) }.exceptionOrNull()
                }
            } finally { opening = false }
            projectsChanged++
            when {
                failure != null -> Toast.makeText(this@MainActivity, getString(if (failure is ProjectStore.DamagedVersion) R.string.home_restore_version_damaged else R.string.home_restore_version_failed), Toast.LENGTH_LONG).show()
                state != null && editor === state -> openNow(id)
                else -> Toast.makeText(this@MainActivity, getString(R.string.home_version_restored), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun closeEditor() {
        val closing = editor ?: return
        if (closingEditor) return
        closingEditor = true
        closing.playing = false
        app.absentmotion.editor.PreviewCache.clear()
        closing.endGesture()
        val scene = closing.scene
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { closing.saver.flush().get() } }
            if (closing.saver.failing && !closeAnyway) {
                closingEditor = false
                saveFailed = true
                return@launch
            }
            closeAnyway = false
            closing.saver.snapshot(scene.toXml())
            engine.audio?.release()
            try {
                saveThumbnail(scene, closing.timeMs, closing.projectId)
            } finally {
                MultiSelect.clear()
                editor = null
                closingEditor = false
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        followScreenSize(newConfig)
    }

    private fun followScreenSize(config: Configuration) {
        val wanted = if (isInMultiWindowMode || isTablet(config.smallestScreenWidthDp)) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        app.absentmotion.ui.tutorial.Tutorial.pickerClosed(resultCode == RESULT_OK)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importShared()
        TestHooks.newIntent(this, intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        editor?.let { state ->
            outState.putString(OPEN_PROJECT, state.projectId)
            outState.putInt(OPEN_FRAME, state.frame)
            state.selectedId?.let { outState.putLong(OPEN_LAYER, it) }
        }
    }

    override fun onPause() {
        super.onPause()
        editor?.saver?.flush()
    }

    override fun onStop() {
        super.onStop()
        editor?.let { it.playing = false; engine.audio?.stop() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (app.absentmotion.editor.ElementNav.open === elementNav) app.absentmotion.editor.ElementNav.open = null
        elementNav = null
        RecoveryHandler.install(recovery) { null }
        engine.close()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        app.absentmotion.editor.PreviewCache.trim(level)
        if (level < TRIM_RUNNING_LOW) return
        dropThumbnails()
        dropVideoThumbnails()
        dropShapeIcons()
        engine.trimMemory()
    }

    override fun onResume() {
        super.onResume()
        engine.refreshMedia()
    }

    private companion object {
        const val OPEN_PROJECT = "openProject"
        const val OPEN_FRAME = "openFrame"
        const val OPEN_LAYER = "openLayer"
        const val UI_FONT_LIMIT = 2f
        const val EDITOR_FONT_LIMIT = 1.3f
        const val TRIM_RUNNING_LOW = 10
    }
}
