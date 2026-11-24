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
import app.absentmotion.platform.SystemBars
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.absentmotion.ui.editor.distrustVideoThumbnails
import app.absentmotion.ui.editor.recheckVideoThumbnails
import app.absentmotion.ui.editor.isTablet
import app.absentmotion.ui.editor.tabletEditor
import app.absentmotion.ui.editor.LocalTablet
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.absentmotion.compat.EffectMigration
import app.absentmotion.compat.oldAlightImport
import app.absentmotion.compat.withReimportTipShown
import app.absentmotion.effects.readAddedEffectUri
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
import app.absentmotion.library.ProjectLinkImportFailure
import app.absentmotion.library.stoppableImport
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
import kotlinx.coroutines.job
import app.absentmotion.ui.home.EmbedRequest
import app.absentmotion.ui.home.TemplateEditor
import app.absentmotion.model.Scene
import app.absentmotion.model.withCopiedLayerIds
import app.absentmotion.model.ShapeCatalog
import app.absentmotion.ui.editor.effects.AddedEffectDialogs
import app.absentmotion.ui.editor.effects.AddedEffectImport
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.absentmotion.ui.home.UnsupportedPhone
import app.absentmotion.ui.home.UpdateAskDialog
import app.absentmotion.ui.home.UpdateDialog
import app.absentmotion.ui.home.UpdateService
import app.absentmotion.ui.home.Updater
import app.absentmotion.ui.home.Updates
import app.absentmotion.ui.home.autoBackupIfDue
import app.absentmotion.ui.home.VersionHistory
import app.absentmotion.ui.theme.PressIndication
import app.absentmotion.ui.theme.enterFade
import app.absentmotion.ui.tutorial.Tutorial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import app.absentmotion.ui.editor.MediaPicks
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.enterZoom
import app.absentmotion.ui.home.DialogPill

class MainActivity : ComponentActivity() {
    private val visualPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) MediaPicks.addPictures(listOf(uri))
    }
    private val filePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { MediaPicks.addPictures(it) }
    private val soundPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { MediaPicks.addSounds(it) }
    private val treePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) app.absentmotion.platform.LinkedMediaTrees.picked(applicationContext, uri)
    }
    internal lateinit var store: ProjectStore
    internal lateinit var settings: AppSettings
    private lateinit var templates: TemplateStore
    internal val engine by lazy { EngineThread(background = Am.PreviewBg.toArgb(), context = applicationContext) { settings.openGl } }
    private val addedEffects by lazy { AddedEffectImport(applicationContext) { engine } }
    private var addedEffectsOnHome by mutableStateOf(false)
    internal var editor by mutableStateOf<EditorState?>(null)
    private var crashSaver: RecoveryHandler? = null
        private set
    private var closingEditor = false
    private var cameFromEditor = false
    private var exportingFromList by mutableStateOf<Scene?>(null)
    private var imported by mutableStateOf<List<ImportReport>>(emptyList())
    private var sharedMedia by mutableStateOf<List<SharedMedia>>(emptyList())
    private var editingTemplate by mutableStateOf<String?>(null)
    private var firstRun by mutableStateOf<FirstRun?>(null)
    private var whatsNew by mutableStateOf<app.absentmotion.ui.home.WhatsNew?>(null)
    private var chooseLinkApp by mutableStateOf<java.net.URI?>(null)
    private var openAlightJob: kotlinx.coroutines.Job? = null
    private var projectLinkInput by mutableStateOf<String?>(null)
    private var opening by mutableStateOf(false)
    private var openingProject by mutableStateOf(false)
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
    private var effectLanguage = ""
    internal var memoryLow: () -> Boolean = { false }
    internal var afterImport: () -> Unit = {}

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(newBase.inAppLanguage())

    override fun onCreate(savedInstanceState: Bundle?) {
        val started = SystemClock.elapsedRealtime()
        SystemBars.screen(this, !app.absentmotion.platform.systemDark(this))
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 28) window.setSoftInputMode(window.attributes.softInputMode or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        app.absentmotion.i18n.keepLegacyLanguage(this)
        app.absentmotion.i18n.AppText.install(this)
        app.absentmotion.platform.MobileData.install(this)
        app.absentmotion.desk.ShortcutPlatform.prefs = getSharedPreferences("desk", MODE_PRIVATE)
        app.absentmotion.desk.DeskShortcuts.reload()
        app.absentmotion.media.MediaBackend.ffampReaders = app.absentmotion.ffamp.FfampMedia
        android.util.Log.i("AMMedia", "Media reader: ${if (app.absentmotion.media.MediaReader.ffamp(this)) "FFamp" else "Standard"}")
        effectLanguage = app.absentmotion.i18n.currentLanguage(this).tag
        followScreenSize(resources.configuration)
        store = ProjectStore(this)
        settings = AppSettings(this)
        if (BuildConfig.DEBUG) app.absentmotion.platform.DeskDetect.forced = intent.getBooleanExtra("desklayout", false)
        app.absentmotion.platform.DeskDetect.refresh(this)
        templates = TemplateStore(this)
        recovery = Recovery(File(filesDir, "recovery"))
        CrashLogs.install(this)
        crashSaver = RecoveryHandler.install(recovery) { editor?.let { it.projectId to it.project.toXml() } }
        val reopen = savedInstanceState?.getString(OPEN_PROJECT)
        lifecycleScope.launch {
            val (offer, crash) = withContext(DiskThread.dispatcher) { runCatching { recovery.pending(store) }.getOrNull() to runCatching { CrashLogs.collect(applicationContext) }.getOrNull() }
            crashed = crash
            recovered = offer
            if (reopen != null && offer?.id != reopen && editor == null && !opening && !openingProject) {
                openingProject = true
                openJob = lifecycleScope.launch {
                    try {
                        val state = openNow(reopen) ?: return@launch
                        state.frame = savedInstanceState.getInt(OPEN_FRAME).coerceIn(0, state.scene.lastFrame)
                        if (savedInstanceState.containsKey(OPEN_LAYER)) state.selectedId = savedInstanceState.getLong(OPEN_LAYER)
                        if (state.selected == null) state.selectedId = null
                    } finally { openingProject = false }
                }
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
            runCatching { app.absentmotion.ai.AiModel.dropReplaced(applicationContext) }
        }
        val openElement: (String) -> Unit = { id ->
            editor?.let { it.playing = false; it.commitTyping(); it.endGesture(); it.saver.snapshot(it.project.toXml()) }
            MultiSelect.clear()
            open(id)
        }
        elementNav = openElement
        app.absentmotion.editor.ElementNav.open = openElement
        MediaPicks.install(
            this,
            { visualPicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            { filePicker.launch(arrayOf("image/*", "video/*", "audio/*")) },
            { soundPicker.launch(arrayOf("audio/*", "application/ogg", "application/x-flac")) },
        ) { editor?.projectId }
        app.absentmotion.platform.LinkedMediaTrees.install(this, applicationContext) { treePicker.launch(null) }
        app.absentmotion.media.MediaLibrarySource.linkedMedia = app.absentmotion.platform.LinkedMediaTrees::listVisual
        lifecycleScope.launch {
            androidx.compose.runtime.snapshotFlow { editor }.collectLatest { state ->
                if (state == null) return@collectLatest
                while (true) {
                    androidx.compose.runtime.snapshotFlow { MediaPicks.waiting }.first { it > 0 }
                    MediaPicks.deliver(state, applicationContext)
                }
            }
        }
        watchMemory()
        TestHooks.start(this)
        app.absentmotion.ui.home.DeviceSend.importer = { file -> importProjectFiles(listOf(Uri.fromFile(file)), quiet = true) }
        app.absentmotion.ui.home.DeviceSend.opener = ::open
        app.absentmotion.ui.home.DeviceSend.changed = { projectsChanged++ }
        lifecycleScope.launch { androidx.compose.runtime.snapshotFlow { settings.openGl }.drop(1).collect { engine.restart() } }
        engine.fonts?.let { fonts -> lifecycleScope.launch { fonts.revision.collect { settings.uiFont?.takeIf { FontName.parse(it).source == "imported" && fonts.localFile(it) == null }?.let { settings.uiFont = null } } } }
        Updater.calm = ::updateCalm
        Updater.atLaunch(applicationContext, open = intent.action == UpdateService.OpenUpdate)
        lifecycleScope.launch { engineWorks() }
        firstRun = FirstRun.of(settings, TestHooks.onboarding(intent).takeIf { savedInstanceState == null })
        whatsNew = app.absentmotion.ui.home.WhatsNewNotes.pending(this, settings)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Am.HomeBg.toArgb()))
        importShared()
        receiveAlightLink(intent)
        setContent {
            app.absentmotion.ui.theme.ThemeFadeHost { app.absentmotion.ui.theme.UiFontHost(settings) {
            app.absentmotion.ui.theme.FollowSystemTheme(settings)
            val open = editor
            val light = if ((open == null || open.toolOpen != 0) && editingTemplate == null) Am.homeIsLight else Am.editorIsLight
            val homeBg = Am.HomeBg
            LaunchedEffect(homeBg) { window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(homeBg.toArgb())) }
            LaunchedEffect(light) { SystemBars.screen(this@MainActivity, light) }
            val density = LocalDensity.current.density
            val fontScale = LocalDensity.current.fontScale
            val dense = remember(density, fontScale) { Density(density, fontScale.coerceAtMost(EDITOR_FONT_LIMIT)) }
            val softPress = isTablet(androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp)
            val editorPress = remember(Am.PressEditor, softPress) { PressIndication(Am.PressEditor, soft = softPress) }
            val homePress = remember(Am.PressHome, softPress) { PressIndication(Am.PressHome, soft = softPress) }
            val systemHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
            val haptics = remember(systemHaptics) { app.absentmotion.platform.AppHaptics(applicationContext, systemHaptics) { settings.vibration } }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalHapticFeedback provides haptics, LocalIndication provides if (open == null) homePress else editorPress, LocalDensity provides remember(density, fontScale) { Density(density, fontScale.coerceAtMost(UI_FONT_LIMIT)) }) {
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
                        CompositionLocalProvider(LocalWide provides tabletEditor(config.smallestScreenWidthDp, config.screenWidthDp), LocalTablet provides isTablet(config.smallestScreenWidthDp)) {
                            HomeScreen(
                                store, settings, onOpen = ::open, onExport = ::exportFromList,
                                imported = if (projectLinkInput == null) imported else emptyList(), onImportShown = { imported = emptyList() }, onImportFile = { importUris(listOf(it)) },
                                onTemplate = { id -> if (!opening && !openingProject) { openingProject = true; openJob = lifecycleScope.launch { try { if (engineWorks()) editingTemplate = id } finally { openingProject = false } } } }, onTemplateProject = ::projectFromTemplate,
                                onHistory = { historyFor = Triple(it.id, it.title, it.damaged) }, onCollect = ::collectMedia, changes = projectsChanged, engine = { engine },
                                onImportLink = { if (!opening && !openingProject) projectLinkInput = "" },
                            )
                        }
                        editingTemplate?.let { id ->
                            CompositionLocalProvider(LocalDensity provides dense) { TemplateEditor(id, engine, settings, onClose = { editingTemplate = null }, onContinue = ::projectFromTemplate) }
                        }
                        exportingFromList?.let { ExportScreen(it, 0, engine, settings, fromProjectList = true, outdated) { exportingFromList = null } }
                        app.absentmotion.ui.export.PresetSharing.preset?.let { app.absentmotion.ui.export.PresetShareScreen(it, engine, settings) { app.absentmotion.ui.export.PresetSharing.preset = null } }
                        LowMemoryNotice()
                    }
                } else {
                    Box(Modifier.fillMaxSize().enterZoom()) {
                        val embed = EmbedRequest.templateId
                        if (embed != null) CompositionLocalProvider(LocalDensity provides dense) { TemplateEditor(embed, engine, settings, onClose = { endEmbed(open) }) { scene -> embedTemplate(open, scene) } }
                        else {
                            val screen = LocalConfiguration.current
                            CompositionLocalProvider(LocalWide provides tabletEditor(screen.smallestScreenWidthDp, screen.screenWidthDp), LocalTablet provides isTablet(screen.smallestScreenWidthDp), app.absentmotion.ui.editor.panels.LocalVibration provides settings.vibration, LocalDensity provides dense) {
                                key(open) { Ltr { app.absentmotion.platform.DeskTyping.Track { CompositionLocalProvider(app.absentmotion.ui.home.LocalImportLink provides { if (!opening && !openingProject) projectLinkInput = "" }) { EditorScreen(open, onExit = ::closeEditor) } } } }
                            }
                        }
                        if (open.exportOpen) ExportScreen(open.project, open.exportFrame, engine, settings, fromProjectList = false, outdated) { open.exportOpen = false }
                    }
                }
                if ((opening || openingProject) && !addedEffects.handlingProject) OpeningOverlay { openJob?.cancel() }
                AddedEffectDialogs(addedEffects, showImport = first == null && (!addedEffectsOnHome || open == null))
                chooseLinkApp?.let { link ->
                    AlertDialog(
                        onDismissRequest = { chooseLinkApp = null },
                        title = { Text(stringResource(R.string.project_link_open_with), color = Am.Navy) },
                        text = {
                            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                                DialogPill(applicationInfo.loadLabel(packageManager).toString(), Modifier.fillMaxWidth()) { chooseLinkApp = null; projectLinkInput = link.toString() }
                                DialogPill("Alight Motion", Modifier.fillMaxWidth()) { chooseLinkApp = null; openInAlight(link) }
                            }
                        },
                        confirmButton = { DialogPill(stringResource(R.string.cancel), primary = false) { chooseLinkApp = null } },
                        containerColor = Am.Card,
                    )
                }
                projectLinkInput?.let { input ->
                    androidx.compose.runtime.key(input) {
                        app.absentmotion.ui.home.ProjectLinkDialog({ projectLinkInput = null }, input) { uri, alightShare ->
                            importProjectFiles(listOf(uri), asProjects = alightShare, fromLink = true)
                        }
                    }
                }
                app.absentmotion.ui.home.MobileDataDialog()
                app.absentmotion.ui.home.DeviceSendHost()
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
                        confirmButton = { DialogPill(stringResource(R.string.ok)) { importFailed = null } },
                        containerColor = Am.Card,
                    )
                }
                fontOffer?.takeIf { it.first === open }?.let { (state, names) ->
                    AlertDialog(
                        onDismissRequest = { declineFonts(state) },
                        title = { Text(stringResource(R.string.fonts_missing_title)) },
                        text = { Text(stringResource(R.string.fonts_missing_body, names.joinToString(", ") { FontName.parse(it).label })) },
                        confirmButton = { DialogPill(stringResource(R.string.update_download)) { downloadFonts(names) } },
                        dismissButton = { DialogPill(stringResource(R.string.update_not_now), primary = false, editor = true) { declineFonts(state) } },
                        containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark,
                    )
                }
                if (saveFailed && open != null) AlertDialog(
                    onDismissRequest = { saveFailed = false },
                    text = { Text(stringResource(R.string.home_close_save_failed)) },
                    confirmButton = { DialogPill(stringResource(R.string.home_save_failed_retry)) { saveFailed = false; closeEditor() } },
                    dismissButton = { DialogPill(stringResource(R.string.home_save_failed_leave), primary = false, editor = true) { saveFailed = false; closeAnyway = true; closeEditor() } },
                    containerColor = Am.Field, textContentColor = Am.OnDark,
                )
                if (open?.historyOpen == true) VersionHistory(store, open.projectId, open.project.title, engine, onRestore = { restoreVersion(open.projectId, it) }, editor = true) { open.historyOpen = false }
                historyFor?.let { (id, title, damaged) ->
                    VersionHistory(store, id, title, engine, onRestore = { restoreVersion(id, it) }, onDelete = if (damaged) { { deleteDamaged(id) } } else null) { historyFor = null }
                }
                val offer = recovered
                val homeFree = open == null && first == null && !opening && editingTemplate == null && exportingFromList == null && app.absentmotion.ui.export.PresetSharing.preset == null && offer == null && crashed == null && !problemShown && imported.isEmpty() && whatsNew == null && !app.absentmotion.ui.home.FfampOffer.pending(settings)
                if (Updater.fromNotification && !Updater.hidden && Updater.busy) UpdateDialog()
                else if (problemShown) Unit
                else if (open == null && !opening && (offer != null || crashed != null)) ClosedDialog(
                    crashed, offer?.title, outdated,
                    onRestore = { seenCrash(outdated != null); offer?.let { answerRecovery(it, true) } },
                    onDiscard = { seenCrash(outdated != null); offer?.let { answerRecovery(it, false) } },
                    onClose = { seenCrash(outdated != null) },
                ) else if (open == null && first == null && !opening && editingTemplate == null && exportingFromList == null && app.absentmotion.ui.export.PresetSharing.preset == null) {
                    val notes = whatsNew
                    if (notes != null) app.absentmotion.ui.home.WhatsNewDialog(notes) { whatsNew = null; app.absentmotion.ui.home.WhatsNewNotes.seen(this, settings) }
                    else if (app.absentmotion.ui.home.FfampOffer.pending(settings)) app.absentmotion.ui.home.FfampOfferDialog(settings)
                    else if (homeFree && !openingProject && openFailed == null && importFailed == null && historyFor == null && sharedMedia.isNotEmpty()) {
                        if (Tutorial.running || Tutorial.offerDismissed || settings.tutorialAsk != Tutorial.ASK) {
                            SharedMediaDialog(sharedMedia, store, projectsChanged, { openShared(null) }, ::openShared) { sharedMedia = emptyList() }
                        }
                    } else if (settings.updatesAsked) UpdateDialog() else UpdateAskDialog(settings)
                }
                app.absentmotion.ui.tutorial.TutorialHost(
                    open, store, settings,
                    homeFree = homeFree,
                    onOpen = ::open, onCloseEditor = ::closeEditor, onChanged = { projectsChanged++ },
                )
            }
        } }
        }
        Log.i("AMStart", "onCreate took ${SystemClock.elapsedRealtime() - started} ms")
        androidx.core.view.OneShotPreDrawListener.add(window.decorView) { Log.i("AMStart", "first frame after ${SystemClock.elapsedRealtime() - started} ms") }
    }



    private fun updateCalm() = updateVisible() && editor == null && !closingEditor && !opening && !openingProject &&
        editingTemplate == null && exportingFromList == null && app.absentmotion.ui.export.PresetSharing.preset == null && firstRun == null && recovered == null && crashed == null && !problemShown && whatsNew == null && !app.absentmotion.ui.home.FfampOffer.pending(settings) &&
        settings.updatesAsked && !app.absentmotion.export.ExportRun.running

    private fun updateVisible() = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    private fun receiveAlightLink(intent: Intent) {
        val text = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull()
            else -> null
        } ?: return
        val link = app.absentmotion.library.externalAlightLink(text) ?: return
        openAlightJob?.cancel()
        val offerChoice = intent.action == Intent.ACTION_VIEW &&
            runCatching { packageManager.getApplicationInfo(AlightPackage, 0).enabled }.getOrDefault(false)
        if (offerChoice) {
            projectLinkInput = null
            chooseLinkApp = link
        } else {
            chooseLinkApp = null
            projectLinkInput = link.toString()
        }
    }

    private fun openInAlight(link: java.net.URI) {
        openAlightJob = lifecycleScope.launch {
            val http = app.absentmotion.library.projectFileHttp(link)
            try {
                val share = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                    app.absentmotion.library.resolveAlightPage(link, http)
                }
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(share.toString())).setPackage(AlightPackage))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: app.absentmotion.platform.MobileDataRefused) {
                importFailed = getString(R.string.mobile_data_refused)
            } catch (_: Exception) {
                importFailed = getString(R.string.project_link_alight)
            } finally {
                http.abort()
            }
        }
    }

    private fun importShared() {
        val uris = ImportHandOff.take().map(Uri::parse)
        if (uris.isEmpty()) return
        val target = editor?.takeUnless { closingEditor }
        lifecycleScope.launch {
            val (effects, remaining) = withContext(Dispatchers.IO) {
                uris.map { uri -> uri to readAddedEffectUri(applicationContext, uri) }.partition { it.second != null }
            }
            if (effects.isNotEmpty()) {
                if (editor != null) {
                    addedEffectsOnHome = true
                    closeEditor()
                }
                addedEffects.present(effects.mapNotNull { it.second })
            }
            val classified = withContext(Dispatchers.IO) { remaining.map { (uri, _) -> uri to readSharedMedia(uri) } }
            val projects = classified.filter { it.second == null }.map { it.first }
            if (projects.isNotEmpty()) importUris(projects, classifyEffects = false)
            val media = classified.mapNotNull { it.second }
            if (media.isEmpty()) return@launch
            if (target != null && editor === target && !closingEditor) queueSharedMedia(target.projectId, media)
            else sharedMedia += media
        }
    }

    private fun openShared(id: String?) {
        val media = sharedMedia
        if (media.isEmpty() || editor != null || opening || openingProject) return
        openingProject = true
        openJob = lifecycleScope.launch {
            try {
                val project = id ?: try { createSharedProject(applicationContext, store, settings, media) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.w("AMImport", "cannot create a project from shared media", e); null }
                if (project == null) {
                    Toast.makeText(this@MainActivity, getString(R.string.home_create_project_failed), Toast.LENGTH_LONG).show()
                    return@launch
                }
                val state = openNow(project) ?: return@launch
                sharedMedia = sharedMedia.drop(media.size)
                queueSharedMedia(state.projectId, media)
            } finally { openingProject = false }
        }
    }

    private fun importUris(uris: List<Uri>, classifyEffects: Boolean = true) {
        lifecycleScope.launch {
            val projects = if (classifyEffects) {
                val (effects, remaining) = withContext(Dispatchers.IO) {
                    uris.map { uri -> uri to readAddedEffectUri(applicationContext, uri) }.partition { it.second != null }
                }
                addedEffects.present(effects.mapNotNull { it.second })
                remaining.map { it.first }
            } else uris
            if (projects.isNotEmpty()) importProjectFiles(projects)
        }
    }

    private suspend fun importProjectFiles(uris: List<Uri>, asProjects: Boolean = false, fromLink: Boolean = false, quiet: Boolean = false): List<ImportReport> =
        kotlinx.coroutines.coroutineScope {
            val reports = ArrayList<ImportReport>()
            var failure: String? = null
            var presetsAdded = 0
            val overlay = !opening && !quiet
            if (overlay) { opening = true; openJob = coroutineContext.job }
            try {
                CrashLogs.during("importing") { withContext(Dispatchers.IO) {
                    val job = coroutineContext.job
                    val importer = Importer.forApp(applicationContext, store)
                    val presets = app.absentmotion.library.PresetStore(applicationContext)
                    for (uri in uris) try {
                        val items = importer.import { contentResolver.openInputStream(uri)?.stoppableImport(job) }
                        withContext(Dispatchers.Main) { addedEffects.confirmImported(items, store) }
                        reports += items.map { it.copy(bundledEffects = emptyMap()) }.mapNotNull {
                            afterImport()
                            if (it.community?.preset == true && presets.adopt(store, it.id, templates::localize) != null) null.also { presetsAdded++ }
                            else if (!it.template) it else if (asProjects) it.copy(template = false) else templates.adopt(store, it.id, engine)?.let { id -> it.copy(id = id) } ?: it.copy(template = false)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: OutOfMemoryError) {
                        Log.w("AMImport", "no memory left to import $uri", e)
                        failure = failure ?: getString(R.string.crash_reason_memory)
                    } catch (e: Exception) {
                        Log.w("AMImport", "cannot import $uri", e)
                        failure = failure ?: (e as? ImportFailed)?.text?.resolve(this@MainActivity) ?: getString(R.string.backup_open_failed).takeIf { e is SecurityException } ?: e.message ?: getString(R.string.library_err_unsupported_type)
                    }
                } }
            } finally {
                if (overlay) opening = false
                projectsChanged++
            }
            if (!fromLink && !quiet) failure?.let { importFailed = it }
            if (presetsAdded > 0) Toast.makeText(this@MainActivity, getString(R.string.community_added_preset), Toast.LENGTH_SHORT).show()
            if (reports.isNotEmpty() && !quiet) imported = if (fromLink) imported + reports else reports
            var drawn = false
            for (report in reports) if (!report.template) {
                val scene = withContext(Dispatchers.IO) { store.load(report.id)?.also { engine.fonts?.fetchMissing(it) } } ?: continue
                drawn = saveThumbnail(scene, scene.totalTime / 2, report.id) || drawn
            }
            if (drawn) projectsChanged++
            if (fromLink || quiet) failure?.let { throw ProjectLinkImportFailure(it) }
            reports
        }

    private fun offerFonts(state: EditorState) {
        val library = engine.fonts ?: return
        if (state.projectId in fontsDeclined) return
        lifecycleScope.launch {
            val missing = withContext(Dispatchers.IO) { library.fetchMissing(state.project) }
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

    private suspend fun saveThumbnail(scene: Scene, timeMs: Int, id: String, xml: String? = null, file: java.io.File = store.thumbnailFile(id)): Boolean {
        val longSide = maxOf(scene.width, scene.height, 1)
        return engine.stillPng(xml ?: withContext(Dispatchers.Default) { scene.toXml() }, timeMs, (256 * scene.width / longSide).coerceAtLeast(1), (256 * scene.height / longSide).coerceAtLeast(1), file)
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
        var tip = false
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
                    migration.migrate(MediaRelink.relink(this@MainActivity, scene)).scene
                        .let { if (it.oldAlightImport()) it.withReimportTipShown().also { tip = true } else it }.also {
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
        if (tip) Toast.makeText(this, getString(R.string.alight_reimport_tip), Toast.LENGTH_LONG).show()
        return scene
    }

    private fun exportFromList(id: String) {
        if (opening || openingProject) return
        openingProject = true
        openJob = lifecycleScope.launch { try { if (engineWorks()) exportingFromList = loadInBackground(id) } finally { openingProject = false } }
    }

    private fun open(id: String) {
        if (opening || openingProject || closingEditor) return
        openingProject = true
        openJob = lifecycleScope.launch { try { openNow(id) } finally { openingProject = false } }
    }

    private suspend fun engineWorks(): Boolean {
        val problem = (kotlinx.coroutines.withTimeoutOrNull(30_000) { engine.startProblem().await() ?: "" } ?: "engine start timed out").ifEmpty { return true }
        engineProblem = problem
        problemShown = true
        return false
    }

    internal suspend fun openNow(id: String): EditorState? {
        if (!engineWorks()) return null
        val scene = loadInBackground(id) ?: return null
        addedEffects.confirmMissing(scene, id)
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
        state?.let { it.playing = false; it.commitTyping(); it.endGesture(); it.historyOpen = false }
        opening = true
        historyFor = null
        openJob = lifecycleScope.launch {
            val failure = try {
                if (state != null) withContext(Dispatchers.IO) { runCatching { state.saver.flush().get() } }
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
        app.absentmotion.editor.PreviewCache.remove()
        closing.commitTyping()
        closing.endGesture()
        closing.leaveGroups()
        val scene = closing.scene
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { closing.saver.flush().get() } }
            if (closing.saver.failing && !closeAnyway) {
                closingEditor = false
                saveFailed = true
                return@launch
            }
            closeAnyway = false
            val xml = withContext(Dispatchers.Default) { scene.toXml() }
            closing.saver.snapshot(xml)
            engine.audio?.release()
            MultiSelect.clear()
            if (editor === closing) editor = null
            closingEditor = false
            addedEffectsOnHome = false
            val id = closing.projectId
            val thumbnail = store.thumbnailFile(id)
            val temp = java.io.File(thumbnail.parentFile, thumbnail.name + ".tmp")
            val saved = withContext(Dispatchers.IO) { store.text(id) }
            val drawn = saveThumbnail(scene, closing.timeMs, id, xml, temp)
            if (withContext(Dispatchers.IO) { (drawn && saved != null && store.text(id) == saved && temp.renameTo(thumbnail)).also { temp.delete() } }) projectsChanged++
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        app.absentmotion.i18n.AppText.install(this)
        val language = app.absentmotion.i18n.currentLanguage(this).tag
        if (effectLanguage.isNotEmpty() && language != effectLanguage) engine.relabelEffects(this)
        effectLanguage = language
        followScreenSize(newConfig)
        app.absentmotion.platform.DeskDetect.refresh(this)
    }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val state = editor
        if (state != null && (event.action == android.view.KeyEvent.ACTION_DOWN || event.action == android.view.KeyEvent.ACTION_UP) && app.absentmotion.platform.DeskKeys.handle(state, androidx.compose.ui.input.key.KeyEvent(event), ::closeEditor)) return true
        return super.dispatchKeyEvent(event)
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
        if (intent.action == UpdateService.OpenUpdate) Updater.atLaunch(applicationContext, open = true)
        if (BuildConfig.DEBUG && intent.hasExtra("desklayout")) {
            app.absentmotion.platform.DeskDetect.forced = intent.getBooleanExtra("desklayout", false)
            app.absentmotion.platform.DeskDetect.refresh(this)
        }
        importShared()
        receiveAlightLink(intent)
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
        distrustVideoThumbnails()
        editor?.saver?.flush()
        app.absentmotion.export.ExportRun.resumeService(this)
    }

    override fun onStop() {
        super.onStop()
        editor?.let { it.playing = false; engine.audio?.stop() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (app.absentmotion.editor.ElementNav.open === elementNav) app.absentmotion.editor.ElementNav.open = null
        elementNav = null
        MediaPicks.release(this)
        app.absentmotion.platform.LinkedMediaTrees.release(this)
        crashSaver?.current = { null }
        if (Updater.calm == ::updateCalm) Updater.calm = { false }
        app.absentmotion.export.ExportEngine.release(engine)
    }

    private fun watchMemory() {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val manager = getSystemService(android.app.ActivityManager::class.java) ?: return
        val info = android.app.ActivityManager.MemoryInfo()
        memoryLow = { manager.getMemoryInfo(info); info.lowMemory }
        lifecycleScope.launch {
            var low = false
            var trimmed = -MEMORY_REST_MS
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                while (true) {
                    val now = memoryLow()
                    if (now && !low && SystemClock.elapsedRealtime() - trimmed >= MEMORY_REST_MS) {
                        trimmed = SystemClock.elapsedRealtime()
                        Log.i("AMStart", "the phone is low on memory")
                        freeMemory(TRIM_RUNNING_LOW)
                    }
                    low = now
                    kotlinx.coroutines.delay(MEMORY_CHECK_MS)
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        freeMemory(level)
    }

    private fun freeMemory(level: Int) {
        app.absentmotion.editor.PreviewCache.trim(level)
        if (level < TRIM_RUNNING_LOW) return
        dropThumbnails()
        dropVideoThumbnails()
        dropShapeIcons()
        engine.trimMemory()
    }

    override fun onResume() {
        super.onResume()
        Updater.resumed(this)
        androidx.core.app.NotificationManagerCompat.from(this).cancel(app.absentmotion.ui.home.UPDATED_NOTIFICATION_ID)
        engine.refreshMedia()
        lifecycleScope.launch { recheckVideoThumbnails(applicationContext) }
    }

    private companion object {
        const val AlightPackage = "com.alightcreative.motion"
        const val OPEN_PROJECT = "openProject"
        const val OPEN_FRAME = "openFrame"
        const val OPEN_LAYER = "openLayer"
        const val UI_FONT_LIMIT = 2f
        const val EDITOR_FONT_LIMIT = 1.3f
        const val TRIM_RUNNING_LOW = 10
        const val MEMORY_CHECK_MS = 5_000L
        const val MEMORY_REST_MS = 60_000L
    }
}
