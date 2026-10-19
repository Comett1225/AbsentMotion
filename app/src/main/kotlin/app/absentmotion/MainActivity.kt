package app.absentmotion

import androidx.core.content.FileProvider
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.getValue
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
import app.absentmotion.library.ImportReport
import app.absentmotion.library.Importer
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
import app.absentmotion.ui.editor.EditorScreen
import app.absentmotion.ui.export.ExportScreen
import app.absentmotion.ui.home.HomeScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        settings = AppSettings(this)
        templates = TemplateStore(this)
        ShapeCatalog.load(this)
        app.absentmotion.editor.ElementNav.open = { id ->
            editor?.let { it.playing = false; it.endGesture() }
            MultiSelect.clear()
            open(id)
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) handleTestIntent()
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
                if (open == null) {
                    val returning = remember { cameFromEditor }
                    Box(Modifier.fillMaxSize().then(if (returning) Modifier.enterFade() else Modifier)) {
                        HomeScreen(
                            store, settings, onOpen = ::open, onExport = { id -> exportingFromList = loadRelinked(id) },
                            imported = imported, onImportShown = { imported = emptyList() }, onImportFile = { importUris(listOf(it)) },
                            onTemplate = { editingTemplate = it },
                        )
                        editingTemplate?.let { id ->
                            TemplateEditor(id, engine, settings, onClose = { editingTemplate = null }) { scene ->
                                lifecycleScope.launch {
                                    val project = withContext(Dispatchers.IO) { templates.createProject(scene, store) }
                                    editingTemplate = null
                                    open(project)
                                }
                            }
                        }
                        exportingFromList?.let { ExportScreen(it, 0, engine, fromProjectList = true) { exportingFromList = null } }
                    }
                } else {
                    Box(Modifier.fillMaxSize()) {
                        val embed = EmbedRequest.templateId
                        if (embed != null) TemplateEditor(embed, engine, settings, onClose = { endEmbed(open) }) { scene -> embedTemplate(open, scene) }
                        else EditorScreen(open, onExit = ::closeEditor)
                        if (open.exportOpen) ExportScreen(open.scene, open.frame, engine, fromProjectList = false) { open.exportOpen = false }
                    }
                }
            }
        }
    }



    private fun importShared() {
        val uris = ImportHandOff.take().map(Uri::parse)
        if (uris.isNotEmpty()) importUris(uris)
    }

    private fun importUris(uris: List<Uri>) {
        lifecycleScope.launch {
            val reports = ArrayList<ImportReport>()
            var failure: String? = null
            withContext(Dispatchers.IO) {
                val importer = Importer.forApp(applicationContext, store)
                for (uri in uris) try {
                    reports += importer.import { contentResolver.openInputStream(uri) }.map {
                        if (!it.template) it else templates.adopt(store, it.id, engine)?.let { id -> it.copy(id = id) } ?: it.copy(template = false)
                    }
                } catch (e: Exception) {
                    Log.w("AMImport", "cannot import $uri", e)
                    failure = failure ?: e.message ?: "Unsupported file type"
                }
            }
            failure?.let { Toast.makeText(this@MainActivity, "Import Failed\n$it", Toast.LENGTH_LONG).show() }
            if (reports.isNotEmpty()) imported = reports
        }
    }

    private fun endEmbed(state: EditorState) {
        EmbedRequest.templateId = null
        engine.setScene(state.scene)
    }

    private fun embedTemplate(state: EditorState, template: Scene) {
        lifecycleScope.launch {
            val local = withContext(Dispatchers.IO) { templates.localize(template, store.mediaDir(state.projectId)) }
            val start = state.scene.frameStart(state.frame)
            val id = Layer.newId()
            val copied = local.withCopiedLayerIds().node
            val inner = copied.copy(children = copied.children.filterNot { it.name == "media" })
            val layer = Layer(XmlNode("embedScene", linkedMapOf("id" to "$id", "label" to local.title.ifBlank { "Template" }, "startTime" to "$start", "endTime" to "${start + local.totalTime}"),
                listOf(XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(state.scene.width / 2f, state.scene.height / 2f, 0f)))))), inner)))
            state.edit { it.add(layer, local.media) }
            state.selectedId = id
            endEmbed(state)
        }
    }

    private val migration by lazy { EffectMigration.read(applicationContext) }

    private fun loadRelinked(id: String): Scene? = store.load(id)?.let { scene ->
        migration.migrate(MediaRelink.relink(this, scene)).scene.also {
            if (it != scene) try { store.save(id, it) } catch (e: java.io.IOException) { Log.e("AMStore", "cannot save the updated project $id", e) }
        }
    }

    private fun open(id: String) {
        cameFromEditor = true
        editor = loadRelinked(id)?.let {
            EditorState(id, it, store, engine, settings) { Toast.makeText(this, "Could not save the project. Free some storage space.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun closeEditor() {
        val closing = editor ?: return
        if (closingEditor) return
        closingEditor = true
        closing.playing = false
        closing.endGesture()
        engine.audio?.release()
        val scene = closing.scene
        lifecycleScope.launch {
            try {
                val longSide = maxOf(scene.width, scene.height, 1)
                engine.stillPng(scene.toXml(), closing.timeMs, (256 * scene.width / longSide).coerceAtLeast(1), (256 * scene.height / longSide).coerceAtLeast(1), store.thumbnailFile(closing.projectId))
            } finally {
                MultiSelect.clear()
                editor = null
                closingEditor = false
            }
        }
    }

    private fun handleTestIntent() {
        DebugFrameInterpolation.divisor = intent.getIntExtra("interpDivisor", 4)
        DebugFrameInterpolation.levels = intent.getIntExtra("interpLevels", 5)
        DebugFrameInterpolation.iterations = intent.getIntExtra("interpIterations", 6)
        DebugFrameInterpolation.warps = intent.getIntExtra("interpWarps", 1)
        DebugFrameInterpolation.alpha = intent.getFloatExtra("interpAlpha", 0.15f)
        DebugFrameInterpolation.fuse = intent.getIntExtra("interpFuse", 4)
        if (intent.getBooleanExtra("audiolog", false)) engine.audio?.log = true
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
                    Log.i("AMTest", "still $name ok=$ok")
                }
                Log.i("AMTest", "batch done")
            }
        }
        val path = intent.getStringExtra("xml") ?: return
        val parsed = runCatching { Scene.parse(File(path).readText()) }.getOrElse { Log.e("AMTest", "cannot read $path", it); return }
        val id = "test-" + File(path).nameWithoutExtension
        store.save(id, parsed)
        open(id)
        val state = editor ?: return
        val scene = state.scene
        state.frame = intent.getIntExtra("frame", 0).coerceIn(0, scene.lastFrame)
        val stillName = intent.getStringExtra("still")
        intent.getStringExtra("export")?.let { exportForTest(it, scene, state.frame) }
        lifecycleScope.launch {
            try {
                engine.awaitMedia()
                engine.awaitVideoFrames(scene, state.timeMs)
                Log.i("AMTest", "media ready $path")
                stillName?.let { name ->
                    val ok = engine.stillPng(scene.toXml(), state.timeMs, scene.width, scene.height, File(getExternalFilesDir(null), "$name.png"))
                    Log.i("AMTest", "still $name ok=$ok")
                }
                intent.getStringExtra("hit")?.split(';')?.forEach { point ->
                    val xy = point.split(',').mapNotNull { it.trim().toFloatOrNull() }
                    if (xy.size == 2) Log.i("AMTest", "hit $point ${engine.hitTest(xy[0], xy[1], state.timeMs)}")
                }
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
            "xml" to ExportKind.Xml, "package" to ExportKind.Package)[kindName] ?: run { Log.e("AMExport", "unknown kind $kindName"); return }
        var s = defaultSettings(kind, scene)
        intent.takeIf { it.hasExtra("short") }?.let { s = s.copy(shortSide = it.getIntExtra("short", s.shortSide)) }
        intent.takeIf { it.hasExtra("fps") }?.let { s = s.copy(fps = it.getIntExtra("fps", s.fps)) }
        intent.getStringExtra("codec")?.let { name -> app.absentmotion.export.Codec.entries.firstOrNull { it.name.equals(name, true) }?.let { s = s.copy(codec = it) } }
        intent.takeIf { it.hasExtra("quality") }?.let { s = s.copy(quality = it.getFloatExtra("quality", s.quality)) }
        intent.getStringExtra("format")?.let { name -> app.absentmotion.export.ImageKind.entries.firstOrNull { it.name.equals(name, true) || it.ext == name }?.let { s = s.copy(image = it) } }
        intent.takeIf { it.hasExtra("iquality") }?.let { s = s.copy(imageQuality = it.getIntExtra("iquality", s.imageQuality)) }
        intent.getStringExtra("interp")?.let { name -> FrameInterpolation.entries.firstOrNull { it.name.equals(name, true) }?.let { s = s.copy(interpolation = it) } }
        val dir = getExternalFilesDir(null)!!
        val ext = mapOf(ExportKind.Video to "mp4", ExportKind.Gif to "gif", ExportKind.Frame to "png", ExportKind.FrameAs to s.image.ext, ExportKind.Xml to "xml", ExportKind.Package to "absproj")
        val name = if (kind == ExportKind.Sequence) "export.seq" else "export"
        lifecycleScope.launch {
            try {
                Exporter(applicationContext, engine, FolderExport(dir)).export(kind, scene, s, frame, name) { }
                Log.i("AMExport", "done " + File(dir, if (kind == ExportKind.Sequence) name else "$name.${ext[kind]}").path)
            } catch (e: Throwable) {
                Log.e("AMExport", "failed", e)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importShared()
        if ((intent.hasExtra("xml") || intent.hasExtra("aaccheck") || intent.hasExtra("devicecheck")) && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
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
