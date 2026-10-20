package app.absentmotion

import androidx.core.content.FileProvider
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
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
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.editor.isTablet
import app.absentmotion.ui.editor.wideEditor
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.absentmotion.ai.AiMedia
import app.absentmotion.ai.BackgroundRemoval
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
import app.absentmotion.media.DebugFrameInterpolation
import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatFloats
import app.absentmotion.ui.home.EmbedRequest
import app.absentmotion.ui.home.TemplateEditor
import app.absentmotion.model.Scene
import app.absentmotion.model.withCopiedLayerIds
import app.absentmotion.model.ShapeCatalog
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.Exporter
import app.absentmotion.export.FolderExport
import app.absentmotion.export.defaultSettings
import app.absentmotion.export.fixedFormat
import app.absentmotion.ui.editor.EditorScreen
import app.absentmotion.ui.export.ExportScreen
import app.absentmotion.ui.home.HomeScreen
import app.absentmotion.i18n.Ltr
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
    private lateinit var store: ProjectStore
    private lateinit var settings: AppSettings
    private lateinit var templates: TemplateStore
    private val engine by lazy { EngineThread(background = Am.PreviewBg.toArgb(), context = applicationContext) }
    private var editor by mutableStateOf<EditorState?>(null)
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
    private var historyFor by mutableStateOf<Triple<String, String, Boolean>?>(null)
    private var projectsChanged by mutableIntStateOf(0)
    private lateinit var recovery: Recovery
    private var recovered by mutableStateOf<RecoveryOffer?>(null)
    private var crashed by mutableStateOf<CrashEntry?>(null)
    private var importFailed by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val started = SystemClock.elapsedRealtime()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app.absentmotion.i18n.AppText.install(this)
        followScreenSize(resources.configuration)
        store = ProjectStore(this)
        settings = AppSettings(this)
        templates = TemplateStore(this)
        recovery = Recovery(File(filesDir, "recovery"))
        CrashLogs.install(this)
        RecoveryHandler.install(recovery) { editor?.let { it.projectId to it.scene.toXml() } }
        lifecycleScope.launch {
            val (offer, crash) = withContext(DiskThread.dispatcher) { runCatching { recovery.pending(store) }.getOrNull() to runCatching { CrashLogs.collect(applicationContext) }.getOrNull() }
            crashed = crash
            recovered = offer
        }
        lifecycleScope.launch { kotlinx.coroutines.delay(15_000); autoBackupIfDue(applicationContext, settings) }
        lifecycleScope.launch(Dispatchers.IO) { ShapeCatalog.load(applicationContext) }
        app.absentmotion.editor.ElementNav.open = { id ->
            editor?.let { it.playing = false; it.endGesture(); it.saver.snapshot(it.scene.toXml()) }
            MultiSelect.clear()
            open(id)
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) handleTestIntent()
        firstRun = FirstRun.of(settings, intent.getStringExtra("onboarding").takeIf { savedInstanceState == null && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 })
        importShared()
        setContent {
            val open = editor
            val light = if (open == null && editingTemplate == null) Am.homeIsLight else Am.editorIsLight
            LaunchedEffect(light) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(if (light) SystemBarStyle.light(transparent, transparent) else SystemBarStyle.dark(transparent))
            }
            val density = LocalDensity.current.density
            val editorPress = remember(Am.PressEditor) { PressIndication(Am.PressEditor) }
            val homePress = remember(Am.PressHome) { PressIndication(Am.PressHome) }
            CompositionLocalProvider(LocalIndication provides if (open == null) homePress else editorPress, LocalDensity provides remember(density) { Density(density, 1f) }) {
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
                    Onboarding(settings, first, { engine }) { firstRun = null }
                } else if (open == null) {
                    LaunchedEffect(settings.autoUpdateCheck) { if (settings.autoUpdateCheck) Updates.atStart(applicationContext, settings) }
                    val returning = remember { cameFromEditor }
                    Box(Modifier.fillMaxSize().then(if (returning) Modifier.enterFade() else Modifier)) {
                        HomeScreen(
                            store, settings, onOpen = ::open, onExport = ::exportFromList,
                            imported = imported, onImportShown = { imported = emptyList() }, onImportFile = { importUris(listOf(it)) },
                            onTemplate = { editingTemplate = it }, onTemplateProject = ::projectFromTemplate,
                            onHistory = { historyFor = Triple(it.id, it.title, it.damaged) }, onCollect = ::collectMedia, changes = projectsChanged,
                        )
                        editingTemplate?.let { id ->
                            TemplateEditor(id, engine, settings, onClose = { editingTemplate = null }, onContinue = ::projectFromTemplate)
                        }
                        exportingFromList?.let { ExportScreen(it, 0, engine, fromProjectList = true, outdated) { exportingFromList = null } }
                    }
                } else {
                    Box(Modifier.fillMaxSize()) {
                        val embed = EmbedRequest.templateId
                        if (embed != null) TemplateEditor(embed, engine, settings, onClose = { endEmbed(open) }) { scene -> embedTemplate(open, scene) }
                        else {
                            val screen = LocalConfiguration.current
                            CompositionLocalProvider(LocalWide provides wideEditor(screen.smallestScreenWidthDp, screen.screenWidthDp)) {
                                key(open) { Ltr { EditorScreen(open, onExit = ::closeEditor) } }
                            }
                        }
                        if (open.exportOpen) ExportScreen(open.scene, open.frame, engine, fromProjectList = false, outdated) { open.exportOpen = false }
                    }
                }
                if (opening) OpeningOverlay { openJob?.cancel() }
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
                if (open?.historyOpen == true) VersionHistory(store, open.projectId, open.scene.title, engine, onRestore = { restoreVersion(open.projectId, it) }) { open.historyOpen = false }
                historyFor?.let { (id, title, damaged) ->
                    VersionHistory(store, id, title, engine, onRestore = { restoreVersion(id, it) }, onDelete = if (damaged) { { deleteDamaged(id) } } else null) { historyFor = null }
                }
                val offer = recovered
                if (open == null && !opening && (offer != null || crashed != null)) ClosedDialog(
                    crashed, offer?.title, outdated,
                    onRestore = { seenCrash(outdated != null); offer?.let { answerRecovery(it, true) } },
                    onDiscard = { seenCrash(outdated != null); offer?.let { answerRecovery(it, false) } },
                    onClose = { seenCrash(outdated != null) },
                ) else if (open == null && first == null && !opening && editingTemplate == null && exportingFromList == null) {
                    if (settings.updatesAsked) UpdateDialog() else UpdateAskDialog(settings)
                }
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
            CrashLogs.during("importing") { withContext(Dispatchers.IO) {
                val importer = Importer.forApp(applicationContext, store)
                for (uri in uris) try {
                    reports += importer.import { contentResolver.openInputStream(uri) }.map {
                        if (!it.template) it else templates.adopt(store, it.id, engine)?.let { id -> it.copy(id = id) } ?: it.copy(template = false)
                    }
                } catch (e: Exception) {
                    Log.w("AMImport", "cannot import $uri", e)
                    failure = failure ?: (e as? ImportFailed)?.text?.resolve(this@MainActivity) ?: e.message ?: getString(R.string.library_err_unsupported_type)
                }
            } }
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

    private val migration by lazy { EffectMigration.read(applicationContext) }

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
        openJob = lifecycleScope.launch { exportingFromList = loadInBackground(id) }
    }

    private fun open(id: String) {
        if (opening) return
        openJob = lifecycleScope.launch { openNow(id) }
    }

    private suspend fun openNow(id: String): EditorState? {
        val scene = loadInBackground(id) ?: return null
        cameFromEditor = true
        val saver = ProjectSaver(id, store, recovery, failed = { runOnUiThread { Toast.makeText(this, getString(R.string.home_save_project_failed), Toast.LENGTH_LONG).show() } })
        return EditorState(id, scene, store, engine, settings, saver).also { editor = it }
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
                    resources.getQuantityString(R.plurals.home_collect_copied, result.copied, result.copied.toString()),
                    result.unreadable.takeIf { it > 0 }?.let { getString(R.string.home_collect_could_not_read, it.toString()) },
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
        state?.let { it.playing = false; it.endGesture(); it.historyOpen = false; opening = true }
        historyFor = null
        openJob = lifecycleScope.launch {
            val restored = try {
                withContext(DiskThread.dispatcher) {
                    runCatching { store.restore(id, version) }.onFailure { Log.e("AMStore", "cannot restore a version of $id", it) }.isSuccess
                }
            } finally { opening = false }
            projectsChanged++
            when {
                !restored -> Toast.makeText(this@MainActivity, getString(R.string.home_restore_version_failed), Toast.LENGTH_LONG).show()
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
        closing.endGesture()
        val scene = closing.scene
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { closing.saver.flush().get() } }
            if (closing.saver.failing && !closeAnyway) {
                closeAnyway = true
                closingEditor = false
                Toast.makeText(this@MainActivity, getString(R.string.home_close_save_failed), Toast.LENGTH_LONG).show()
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

    private fun crashForTest(kind: String) {
        Log.i("AMTest", "crash test: $kind")
        when (kind) {
            "java" -> throw IllegalStateException(kind)
            "memory" -> lifecycleScope.launch { CrashLogs.during("exporting video") { throw OutOfMemoryError(kind) } }
            "native" -> android.system.Os.kill(android.system.Os.getpid(), android.system.OsConstants.SIGABRT)
            "anr" -> Thread.sleep(60_000)
        }
    }

    private fun handleTestIntent() {
        DebugFrameInterpolation.divisor = intent.getIntExtra("interpDivisor", 4)
        DebugFrameInterpolation.levels = intent.getIntExtra("interpLevels", 5)
        DebugFrameInterpolation.iterations = intent.getIntExtra("interpIterations", 6)
        DebugFrameInterpolation.warps = intent.getIntExtra("interpWarps", 1)
        DebugFrameInterpolation.alpha = intent.getFloatExtra("interpAlpha", 0.15f)
        DebugFrameInterpolation.fuse = intent.getIntExtra("interpFuse", 4)
        intent.getStringExtra("fakeversion")?.let { Updates.installedOverride = it }
        intent.getStringExtra("crash")?.let { kind -> window.decorView.postDelayed({ crashForTest(kind) }, 2000) }
        if (intent.getBooleanExtra("audiolog", false)) engine.audio?.log = true
        if (intent.hasExtra("previewCopies")) settings.previewCopies = intent.getBooleanExtra("previewCopies", true)
        if (intent.getBooleanExtra("effectcheck", false)) lifecycleScope.launch {
            engine.effectCheck(getExternalFilesDir(null)!!)
            Log.i("AMEffects", "effectcheck.json written")
        }
        if (intent.hasExtra("aaccheck")) {
            val duration = intent.getLongExtra("aaccheck", 32000L).coerceIn(1L, 10_000_000L)
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { app.absentmotion.export.exportAacForTest(File(getExternalFilesDir(null), "aaccheck.m4a"), duration) }
                    Log.i("AMAac", "done")
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Log.e("AMAac", "failed", e) }
            }
        }
        if (intent.getBooleanExtra("capcheck", false)) lifecycleScope.launch {
            try {
                val caps = app.absentmotion.export.DeviceCapabilities.of(this@MainActivity)
                val report = caps.runCheckNow(engine)
                report.toJson().toString().chunked(3000).forEach { Log.i("AMCaps", it) }
                for (codec in app.absentmotion.export.Codec.entries) for (side in listOf(1080, 2160)) {
                    Log.i("AMCaps", "check ${codec.name} ${side}p30: ${report.check(ExportSettings(side, 30, codec), side, side * 16 / 9)}")
                }
                Log.i("AMCaps", "done probe=${report.probe?.pixelsPerSecond}")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Log.e("AMCaps", "failed", e) }
        }
        if (intent.getBooleanExtra("devicecheck", false)) lifecycleScope.launch {
            try { checkDevice(this@MainActivity) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Log.e("AMDevice", "failed", e) }
        }
        intent.getStringExtra("bgremove")?.let { asset ->
            val runs = intent.getIntExtra("runs", 3).coerceAtLeast(1)
            lifecycleScope.launch(Dispatchers.IO) { checkBackgroundRemoval(asset, runs) }
        }
        intent.getStringExtra("batch")?.let { list ->
            lifecycleScope.launch {
                for (line in File(list).readLines().filter { it.isNotBlank() }) {
                    val (path, frame, name) = line.trim().split(' ')
                    val scene = migration.migrate(Scene.parse(File(path).readText())).scene
                    val ok = engine.stillPng(scene.toXml(), scene.frameStart(frame.toInt()), scene.width, scene.height, File(getExternalFilesDir(null), "$name.png"))
                    Log.i("AMTest", "still $name ok=$ok render=${"%.1f".format(java.util.Locale.US, engine.lastStillMs)}ms")
                }
                Log.i("AMTest", "batch done")
            }
        }
        val path = intent.getStringExtra("xml") ?: return
        val parsed = runCatching { Scene.parse(File(path).readText()) }.getOrElse { Log.e("AMTest", "cannot read $path", it); return }
        val id = "test-" + File(path).nameWithoutExtension
        store.save(id, parsed)
        lifecycleScope.launch {
            val state = openNow(id) ?: return@launch
            val scene = state.scene
            state.frame = intent.getIntExtra("frame", 0).coerceIn(0, scene.lastFrame)
            val stillName = intent.getStringExtra("still")
            intent.getStringExtra("export")?.let { exportForTest(it, scene, state.frame) }
            intent.getStringExtra("verify")?.let { file ->
                launch {
                    val kind = if (file.endsWith(".mp4") || file.endsWith(".mkv")) ExportKind.Video else ExportKind.Audio
                    val expect = app.absentmotion.export.expectationFor(kind, scene, defaultSettings(kind, scene), app.absentmotion.audio.AudioClip.of(scene).isNotEmpty())!!
                    val v = app.absentmotion.export.verifyExport(applicationContext, android.net.Uri.fromFile(File(file)), expect)
                    Log.i("AMExport", "verify " + app.absentmotion.export.verificationSummary(v))
                }
            }
            try {
                engine.awaitMedia()
                try { engine.awaitVideoFrames(scene, state.timeMs) } catch (e: java.io.IOException) { Log.i("AMTest", "video unavailable: ${e.message}") }
                Log.i("AMTest", "media ready $path")
                stillName?.let { name ->
                    val ok = engine.stillPng(scene.toXml(), state.timeMs, scene.width, scene.height, File(getExternalFilesDir(null), "$name.png"))
                    Log.i("AMTest", "still $name ok=$ok")
                }
                intent.getStringExtra("hit")?.split(';')?.forEach { point ->
                    val xy = point.split(',').mapNotNull { it.trim().toFloatOrNull() }
                    if (xy.size == 2) Log.i("AMTest", "hit $point ${engine.hitTest(xy[0], xy[1], state.timeMs)}")
                }
                intent.getIntExtra("bench", 0).takeIf { it > 0 }?.let { runBench(this@MainActivity, state, it, intent.getBooleanExtra("proxies", false)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                if (e.message != "engine closed") throw e
                Log.i("AMTest", "test capture stopped with the engine")
            }
        }
    }

    private suspend fun checkBackgroundRemoval(asset: String, runs: Int) {
        try {
            val input = File(asset).takeIf { it.isFile } ?: File(cacheDir, "bgremove-" + asset.substringAfterLast('/')).also { file ->
                assets.open(asset).use { source -> file.outputStream().use { source.copyTo(it) } }
            }
            val times = mutableListOf<Long>()
            var output: AiMedia? = null
            repeat(runs) {
                output?.file?.delete()
                val start = SystemClock.elapsedRealtime()
                output = BackgroundRemoval.run(applicationContext, Uri.fromFile(input).toString(), null, engine.maxPictureSize()) { _, _ -> }
                times += SystemClock.elapsedRealtime() - start
            }
            val result = File(getExternalFilesDir(null), "bgremove.png")
            checkNotNull(output).file.let { it.copyTo(result, overwrite = true); it.delete() }
            val source = checkNotNull(BitmapFactory.decodeFile(input.path))
            val cut = checkNotNull(BitmapFactory.decodeFile(result.path))
            val pixels = IntArray(cut.width * cut.height).also { cut.getPixels(it, 0, cut.width, 0, 0, cut.width, cut.height) }
            val clear = pixels.count { it ushr 24 <= 8 }
            val solid = pixels.count { it ushr 24 >= 247 }
            val ok = cut.width == source.width && cut.height == source.height && clear > 0 && solid > 0
            Log.i("AMTest", "bgremove $asset ${source.width}x${source.height} -> ${cut.width}x${cut.height} transparent=$clear opaque=$solid ms=$times ok=$ok")
        } catch (e: Throwable) {
            Log.e("AMTest", "bgremove failed", e)
        }
    }

    private fun exportForTest(kindName: String, scene: Scene, frame: Int) {
        val kind = mapOf("video" to ExportKind.Video, "gif" to ExportKind.Gif, "frame" to ExportKind.Frame, "frameas" to ExportKind.FrameAs, "sequence" to ExportKind.Sequence,
            "xml" to ExportKind.Xml, "package" to ExportKind.Package, "audio" to ExportKind.Audio)[kindName] ?: run { Log.e("AMExport", "unknown kind $kindName"); return }
        var s = defaultSettings(kind, scene)
        intent.takeIf { it.hasExtra("short") }?.let { s = s.copy(shortSide = it.getIntExtra("short", s.shortSide)) }
        intent.takeIf { it.hasExtra("fps") }?.let { s = s.copy(fps = it.getIntExtra("fps", s.fps)) }
        intent.getStringExtra("codec")?.let { name -> app.absentmotion.export.Codec.entries.firstOrNull { it.name.equals(name, true) }?.let { s = s.copy(codec = it) } }
        intent.takeIf { it.hasExtra("quality") }?.let { s = s.copy(quality = it.getFloatExtra("quality", s.quality)) }
        intent.getStringExtra("format")?.let { name -> app.absentmotion.export.ImageKind.entries.firstOrNull { it.name.equals(name, true) || it.ext == name }?.let { s = s.copy(image = it) } }
        intent.takeIf { it.hasExtra("iquality") }?.let { s = s.copy(imageQuality = it.getIntExtra("iquality", s.imageQuality)) }
        intent.getStringExtra("interp")?.let { name -> FrameInterpolation.entries.firstOrNull { it.name.equals(name, true) }?.let { s = s.copy(interpolation = it) } }
        app.absentmotion.export.ExportDebug.forceBuffers = intent.getBooleanExtra("buffers", false)
        app.absentmotion.export.ExportDebug.fakeFreeBytes = intent.getLongExtra("fakefree", -1L)
        app.absentmotion.export.ThermalMonitor.of(applicationContext).forced =
            app.absentmotion.export.ThermalLevel.entries.firstOrNull { it.name.equals(intent.getStringExtra("thermal"), true) }
        intent.getStringExtra("container")?.let { name -> app.absentmotion.export.Container.entries.firstOrNull { it.name.equals(name, true) }?.let { s = s.copy(container = it) } }
        intent.getStringExtra("aformat")?.let { name -> app.absentmotion.export.AudioFormat.entries.firstOrNull { it.name.equals(name, true) }?.let { s = s.copy(audioFormat = it) } }
        var a = s.audio
        intent.getStringExtra("acodec")?.let { name -> app.absentmotion.export.AudioCodec.entries.firstOrNull { it.name.equals(name, true) }?.let { a = a.copy(codec = it) } }
        intent.takeIf { it.hasExtra("abitrate") }?.let { a = a.copy(bitrateKbps = it.getIntExtra("abitrate", a.bitrateKbps)) }
        intent.takeIf { it.hasExtra("arate") }?.let { a = a.copy(sampleRate = it.getIntExtra("arate", a.sampleRate)) }
        intent.takeIf { it.hasExtra("channels") }?.let { a = a.copy(channels = it.getIntExtra("channels", a.channels)) }
        s = s.copy(audio = a)
        val dir = getExternalFilesDir(null)!!
        val ext = mapOf(ExportKind.Video to s.fixedFormat().container.ext, ExportKind.Audio to s.audioFormat.ext, ExportKind.Gif to "gif", ExportKind.Frame to "png", ExportKind.FrameAs to s.image.ext, ExportKind.Xml to "xml", ExportKind.Package to "absproj")
        val name = if (kind == ExportKind.Sequence) "export.seq" else "export"
        val job = lifecycleScope.launch {
            try {
                if (kind == ExportKind.Video) {
                    val probe = app.absentmotion.export.DeviceCapabilities.of(applicationContext).load().probe
                    Log.i("AMExport", "estimate ms=${app.absentmotion.export.ExportTimes(applicationContext).estimateMs(s.fixedFormat(), scene, probe)}")
                }
                if (kind == ExportKind.Gif) Log.i("AMExport", "estimate ms=" + app.absentmotion.export.gifTimeMs(
                    app.absentmotion.export.frameCount(scene, s.fps), app.absentmotion.export.measureGif(engine, scene, s),
                    app.absentmotion.export.ExportTimes(applicationContext).estimateMs(s, scene)))
                val started = SystemClock.elapsedRealtime()
                val mediaStore = intent.getBooleanExtra("mediastore", false)
                val result = Exporter(applicationContext, engine, if (mediaStore) app.absentmotion.export.MediaStoreExport(applicationContext) else FolderExport(dir))
                    .export(kind, scene, s, frame, name) { }
                result.verification?.let { Log.i("AMExport", "verify " + app.absentmotion.export.verificationSummary(it)) }
                if (mediaStore) Log.i("AMExport", "done " + result.uris.joinToString(" "))
                else Log.i("AMExport", "done " + File(dir, if (kind == ExportKind.Sequence) name else "$name.${ext[kind]}").path + " ms=" + (SystemClock.elapsedRealtime() - started))
            } catch (e: Throwable) {
                Log.e("AMExport", "failed", e)
            }
        }
        intent.getIntExtra("cancelafter", 0).takeIf { it > 0 }?.let { ms -> lifecycleScope.launch { kotlinx.coroutines.delay(ms.toLong()); job.cancel() } }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        followScreenSize(newConfig)
    }

    private fun followScreenSize(config: Configuration) {
        val wanted = if (isTablet(config.smallestScreenWidthDp)) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importShared()
        if ((intent.hasExtra("xml") || intent.hasExtra("aaccheck") || intent.hasExtra("devicecheck") || intent.hasExtra("capcheck")) && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            setIntent(intent)
            handleTestIntent()
            return
        }
        val state = editor ?: return
        val frame = intent.getIntExtra("frame", state.frame).coerceIn(0, state.scene.lastFrame)
        if (intent.getBooleanExtra("losegpu", false) && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            window.decorView.postDelayed({ engine.simulateGpuLoss = true; state.frame = frame }, 2000)
        } else if (intent.hasExtra("frame")) state.frame = frame
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
        engine.close()
    }

    override fun onResume() {
        super.onResume()
        engine.refreshMedia()
    }
}
