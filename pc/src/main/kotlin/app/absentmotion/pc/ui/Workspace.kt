package app.absentmotion.pc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import app.absentmotion.model.*
import app.absentmotion.pc.engine.DesktopEngine
import app.absentmotion.pc.platform.DesktopPlatform
import app.absentmotion.pc.media.AudioPlayer
import app.absentmotion.pc.media.importMedia as loadMedia
import app.absentmotion.pc.media.installFileDrop
import app.absentmotion.pc.media.addMedia
import app.absentmotion.pc.media.openProject
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.project.emptyScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.awt.Window
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.prefs.Preferences
import javax.swing.JOptionPane
import kotlin.math.roundToInt

private data class RenderRequest(val scene: Scene, val time: Int, val viewport: IntSize, val selected: Long, val scaleMode: String, val playing: Boolean, val resolution: Int, val base: File?)

@Composable
fun FrameWindowScope.Workspace(window: Window, quit: () -> Unit, startFile: File?, close: MutableList<() -> Unit>) {
    WorkspaceContent(window, quit, startFile, close, this)
}

@Composable
fun WorkspaceContent(window: Window?, quit: () -> Unit, startFile: File?, close: MutableList<() -> Unit>, menuScope: FrameWindowScope? = null, project: ProjectState? = null, initialShortcuts: Boolean = false) {
    val assets = remember { DesktopPlatform.assets() }
    val engine = remember { DesktopEngine(assets, System.getProperty("am.repo")?.let(::File)) }
    val state = remember { project ?: ProjectState() }
    val prefs = remember { Preferences.userNodeForPackage(ProjectState::class.java) }
    var status by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf(runCatching { DesktopPlatform.dataDir.resolve("recent.txt").readLines().map(::File).filter(File::isFile).take(10) }.getOrDefault(emptyList())) }
    var leftWidth by remember { mutableFloatStateOf(prefs.getFloat("leftWidth", 240f)) }
    var rightWidth by remember { mutableFloatStateOf(prefs.getFloat("rightWidth", 300f)) }
    var timelineHeight by remember { mutableFloatStateOf(prefs.getFloat("timelineHeight", 245f)) }
    var scaleMode by remember { mutableStateOf("Fit") }
    var spaceDown by remember { mutableStateOf(false) }
    var spacePanned by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("Media") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var resolution by remember { mutableIntStateOf(1) }
    var loop by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var shortcuts by remember { mutableStateOf(initialShortcuts) }
    var viewport by remember { mutableStateOf(IntSize(800, 500)) }
    var image by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var outline by remember { mutableStateOf<FloatArray?>(null) }
    var renderedRequest by remember { mutableStateOf<RenderRequest?>(null) }
    val currentOutline by rememberUpdatedState(outline)
    val lock = remember { Any() }
    val scope = rememberCoroutineScope()
    val renderedScene = remember { arrayOfNulls<Scene>(1) }
    val renderedBase = remember { arrayOfNulls<File>(1) }
    fun importFiles(files: List<File>) {
        if (files.isEmpty()) return
        val project = state.file
        scope.launch {
            val imported = withContext(Dispatchers.IO) { loadMedia(files, project) }
            state.edit { scene -> imported.fold(scene) { result, media -> result.withMedia(media) } }
            status = if (imported.size == files.size) "Imported ${imported.size} files" else "Imported ${imported.size} of ${files.size} files"
        }
    }
    fun importMedia() = window?.let { importFiles(DesktopPlatform.chooseMedia(it)) } ?: Unit
    DisposableEffect(window) {
        val remove = window?.let { installFileDrop(it) { files -> scope.launch { importFiles(files) } } } ?: {}
        onDispose { remove() }
    }

    fun save(asNew: Boolean): Boolean {
        val dest = (if (asNew) null else state.file) ?: window?.let { DesktopPlatform.choose(it, true) } ?: return false
        return runCatching { dest.writeText(state.scene.toXml()); state.saved(dest); prefs.putBoolean("recoveryPending", false); window?.let { DesktopPlatform.title(it, dest) }; status = "Saved"; true }.getOrElse { status = it.message ?: "Could not save project"; false }
    }
    fun canClose(): Boolean {
        if (!state.dirty) return true
        return when (JOptionPane.showConfirmDialog(window, "Save changes to this project?", "Unsaved changes", JOptionPane.YES_NO_CANCEL_OPTION)) {
            JOptionPane.YES_OPTION -> save(false)
            JOptionPane.NO_OPTION -> { prefs.putBoolean("recoveryPending", false); true }
            else -> false
        }
    }
    fun open(source: File?) {
        if (source == null || !canClose()) return
        scope.launch {
        runCatching {
            val (next, projectFile) = withContext(Dispatchers.IO) { openProject(source) }
            state.open(next, projectFile)
            window?.let { DesktopPlatform.title(it, projectFile) }
            recent = (listOf(source) + recent.filter { it != source }).take(10)
            DesktopPlatform.dataDir.mkdirs()
            DesktopPlatform.dataDir.resolve("recent.txt").writeText(recent.joinToString("\n") { it.absolutePath })
            status = ""
        }.onFailure { status = it.message ?: "Could not open project" }
        }
    }
    fun copy(): Boolean {
        val layers = state.scene.layers.filter { it.id in state.selectedIds }
        if (layers.isEmpty()) return false
        val uris = layers.flatMap { it.node.mediaUris() }.toSet()
        val nodes = state.scene.node.children.filter { it.name == "media" && it["uri"] in uris || it.name in Scene.LAYER_TAGS && Layer(it).id in state.selectedIds }
        return runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(state.scene.node.copy(children = nodes).toXml()), null); true }.getOrElse { status = "Could not copy layers"; false }
    }
    fun paste() {
        val text = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull() ?: return
        val scene = runCatching { Scene.parse(text) }.getOrNull() ?: return
        state.paste(scene.layers, scene.media)
    }
    fun newProject() { if (canClose()) { state.open(emptyScene(), null); window?.let { DesktopPlatform.title(it, null) } } }
    SideEffect { close.clear(); close.add { if (canClose()) quit() } }
    LaunchedEffect(Unit) {
        val recovery = DesktopPlatform.dataDir.resolve("autosave/recovery.xml")
        if (window != null && startFile == null && prefs.getBoolean("recoveryPending", false) && recovery.isFile) {
            if (JOptionPane.showConfirmDialog(window, "Recover the autosaved project?", "Recovery", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                runCatching { state.open(Scene.parse(recovery.readText()), null); state.recovered() }.onFailure { status = "Could not recover project" }
            } else prefs.putBoolean("recoveryPending", false)
        } else open(startFile)
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            if (state.dirty) {
                val xml = state.scene.toXml()
                withContext(Dispatchers.IO) { runCatching { DesktopPlatform.dataDir.resolve("autosave").also { it.mkdirs() }.resolve("recovery.xml").writeText(xml); prefs.putBoolean("recoveryPending", true) } }
            }
        }
    }
    LaunchedEffect(state.playing, state.scene, loop) {
        if (state.playing) {
            var player: AudioPlayer? = null
            try {
                while (state.playing) {
                    val initial = state.time
                    withContext(Dispatchers.IO) { player = AudioPlayer(state.scene, state.file?.parentFile, System.getProperty("am.repo")?.let(::File), initial) }
                    val activePlayer = player ?: error("Audio player unavailable")
                    var last = initial
                    while (state.playing) {
                        if (state.time != last) { state.playing = false; break }
                        activePlayer.failure?.let { error(it) }
                        val raw = activePlayer.timeMs
                        if (raw > state.scene.totalTime) {
                            if (loop) state.time = 0 else { state.time = state.scene.frameStart(state.scene.lastFrame); state.playing = false }
                            break
                        }
                        last = state.scene.frameStart(state.scene.frameAt(raw))
                        state.time = last
                        delay((1000L / state.scene.fps / 2).coerceAtLeast(5))
                    }
                    withContext(Dispatchers.IO) { activePlayer.close() }
                    player = null
                }
            } catch (error: Exception) {
                if (error !is kotlinx.coroutines.CancellationException) { status = error.message ?: "Audio playback failed"; state.playing = false }
                else throw error
            } finally { player?.close() }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { RenderRequest(state.scene, state.time, viewport, state.selected, scaleMode, state.playing, resolution, state.file?.parentFile) }.conflate().collect { request ->
            val fit = minOf(request.viewport.width.coerceAtLeast(1).toFloat() / request.scene.width.coerceAtLeast(1), request.viewport.height.coerceAtLeast(1).toFloat() / request.scene.height.coerceAtLeast(1))
            val target = if (request.scaleMode == "Fit") fit else request.scaleMode.removeSuffix("%").toFloatOrNull()?.div(100f) ?: fit
            val renderScale = minOf(target, 1f, 1600f / maxOf(request.scene.width, request.scene.height).coerceAtLeast(1)) / if (request.playing) request.resolution else 1
            val w = (request.scene.width * renderScale).roundToInt().coerceIn(1, 1600)
            val h = (request.scene.height * renderScale).roundToInt().coerceIn(1, 1600)
            runCatching { withContext(Dispatchers.Default) {
                synchronized(lock) {
                    if (renderedScene[0] != request.scene || renderedBase[0] != request.base) { engine.setScene(request.scene, request.base); renderedScene[0] = request.scene; renderedBase[0] = request.base }
                    engine.render(request.time, w, h).toComposeImageBitmap() to if (request.selected != 0L) engine.quad(request.selected, request.time) else null
                }
            } }.onSuccess { result -> if (state.scene == request.scene && state.time == request.time && state.selected == request.selected) { image = result.first; outline = result.second; renderedRequest = request } }.onFailure { status = it.message ?: "Render failed" }
        }
    }
    LaunchedEffect(state.selected) { outline = null }
    DisposableEffect(Unit) { onDispose { synchronized(lock) { engine.close() } } }
    if (menuScope != null && window != null) with(menuScope) { MenuBar {
        Menu("File") {
            Item("New", onClick = ::newProject, shortcut = KeyShortcut(Key.N, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac))
            Item("Open…", onClick = { open(DesktopPlatform.choose(window, false)) }, shortcut = KeyShortcut(Key.O, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac))
            Menu("Open Recent") { recent.forEach { entry -> Item(entry.name, onClick = { open(entry) }) } }
            Item("Import Media…", onClick = ::importMedia)
            Separator()
            Item("Save", onClick = { save(false) }, shortcut = KeyShortcut(Key.S, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac))
            Item("Save As…", onClick = { save(true) }, shortcut = KeyShortcut(Key.S, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac, shift = true))
            Item("Export…", onClick = { exporting = true }, shortcut = KeyShortcut(Key.E, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac))
            Item("Project Settings…", onClick = { settings = true })
            Item("Close", onClick = ::newProject, shortcut = KeyShortcut(Key.W, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac))
            Item("Quit", onClick = { if (canClose()) quit() }, shortcut = KeyShortcut(Key.Q, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac))
        }
        Menu("Edit") {
            Item("Undo", onClick = state::undo, enabled = state.canUndo, shortcut = KeyShortcut(Key.Z, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac))
            Item("Redo", onClick = state::redo, enabled = state.canRedo, shortcut = KeyShortcut(Key.Z, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac, shift = true))
            Separator()
            Item("Cut", onClick = { if (copy()) state.delete() }, enabled = state.layer != null, shortcut = KeyShortcut(Key.X, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac))
            Item("Copy", onClick = { copy() }, enabled = state.layer != null, shortcut = KeyShortcut(Key.C, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac))
            Item("Paste", onClick = ::paste, shortcut = KeyShortcut(Key.V, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac))
            Item("Duplicate", onClick = state::duplicate, enabled = state.layer != null, shortcut = KeyShortcut(Key.D, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac))
            Item("Delete", onClick = state::delete, enabled = state.layer != null)
            Item("Select All", onClick = state::selectAll, shortcut = KeyShortcut(Key.A, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac))
        }
        Menu("Layer") {
            Item("Add Text", onClick = { addText(state) })
            Menu("Add Shape") { shapes(assets).forEach { shape -> Item(shape["name"] ?: "Shape", onClick = { addShape(state, shape) }) } }
            Item("Add Solid", onClick = { addSolid(state) })
            Item("Split at Playhead", onClick = state::split, enabled = state.layer != null, shortcut = KeyShortcut(Key.B, meta = DesktopPlatform.mac, ctrl = !DesktopPlatform.mac))
            Item("Bring Forward", onClick = { state.reorderSelection(true) }, enabled = state.layer != null)
            Item("Send Backward", onClick = { state.reorderSelection(false) }, enabled = state.layer != null)
            Item("Hide/Show", onClick = { val hide = state.layer?.hidden != true; state.updateSelection { it.withHidden(hide) } }, enabled = state.layer != null)
        }
        Menu("Effect") {
            Item("Add Effect…", onClick = { tab = "Effects" }, enabled = state.layer != null)
            Item("Remove", onClick = { state.update { it.removeEffect(it.effects.lastIndex) } }, enabled = state.layer?.effects?.isNotEmpty() == true)
        }
        Menu("View") {
            listOf("Fit", "50%", "100%", "200%").forEach { item -> Item(item, onClick = { scaleMode = item }) }
            Item("Zoom In", onClick = { scaleMode = "${(viewerScale(scaleMode, viewport.width.toFloat(), viewport.height.toFloat(), state.scene) * 150f).coerceAtMost(800f)}%" })
            Item("Zoom Out", onClick = { scaleMode = "${(viewerScale(scaleMode, viewport.width.toFloat(), viewport.height.toFloat(), state.scene) / 1.5f * 100f).coerceAtLeast(5f)}%" })
            CheckboxItem("Show Safe Area", checked = state.safeArea, onCheckedChange = { state.safeArea = it })
        }
        Menu("Window") { Item("Reset Layout", onClick = { leftWidth = 240f; rightWidth = 300f; timelineHeight = 245f; prefs.putFloat("leftWidth", leftWidth); prefs.putFloat("rightWidth", rightWidth); prefs.putFloat("timelineHeight", timelineHeight) }) }
        Menu("Help") {
            Item("About AbsentMotion 5.0.0", onClick = { JOptionPane.showMessageDialog(window, "AbsentMotion 5.0.0") })
            Item("Keyboard Shortcuts", onClick = { shortcuts = true })
        }
    }
    }
    MaterialTheme(colors = darkColors(primary = accent, surface = panel, background = preview)) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(preview).onKeyEvent { event ->
            if (event.key == Key.Spacebar && !event.isMetaPressed && !event.isCtrlPressed) {
                if (event.type == KeyEventType.KeyDown && !spaceDown) { spaceDown = true; spacePanned = false }
                if (event.type == KeyEventType.KeyUp && spaceDown) { spaceDown = false; if (!spacePanned) state.playing = !state.playing }
                true
            } else if (event.type != KeyEventType.KeyDown || event.isMetaPressed || event.isCtrlPressed) false else when (event.key) {
                Key.DirectionLeft -> { state.playing = false; state.step(if (event.isShiftPressed) -10 else -1); true }
                Key.DirectionRight -> { state.playing = false; state.step(if (event.isShiftPressed) 10 else 1); true }
                Key.Plus, Key.Equals -> { zoom = (zoom * 1.5f).coerceAtMost(15f); true }
                Key.Minus -> { zoom = (zoom / 1.5f).coerceAtLeast(.5f); true }
                Key.Delete, Key.Backspace -> { state.delete(); true }
                Key.MoveHome -> { state.playing = false; state.time = 0; true }
                Key.MoveEnd -> { state.playing = false; state.time = state.scene.frameStart(state.scene.lastFrame); true }
                else -> false
            }
        }) {
            Row(Modifier.fillMaxWidth().height(46.dp).background(panel).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolbarButton("Import", action = ::importMedia)
                ToolbarButton("Text") { addText(state) }
                ToolbarButton("Shape") { tab = "Elements" }
                ToolbarButton("Undo", state.canUndo, state::undo)
                ToolbarButton("Redo", state.canRedo, state::redo)
                Spacer(Modifier.width(12.dp))
                Text("${state.scene.title.ifBlank { state.file?.nameWithoutExtension ?: "Untitled" }}${if (state.dirty) " •" else ""}   ${state.scene.width} × ${state.scene.height}  /  ${state.scene.fps} fps", Modifier.weight(1f), color = muted, fontSize = 12.sp)
                ToolbarButton("Settings") { settings = true }
                ToolbarButton("Export") { exporting = true }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(leftWidth.dp).fillMaxHeight().background(panel)) {
                    Row { listOf("Media", "Effects", "Elements").forEach { name -> Text(name, Modifier.clickable { tab = name }.padding(10.dp), color = if (tab == name) accent else muted, fontSize = 12.sp) } }
                    when (tab) {
                        "Effects" -> EffectsLibrary(state, engine.definitions, assets)
                        "Elements" -> Elements(state, assets)
                        else -> MediaPool(state, ::importMedia)
                    }
                }
                DividerDrag(true) { leftWidth = (leftWidth + it).coerceIn(160f, 430f); prefs.putFloat("leftWidth", leftWidth) }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val scene = state.scene
                    Box(Modifier.weight(1f).fillMaxWidth().testTag("rendered-${renderedRequest?.scene?.hashCode()}-${renderedRequest?.selected}")) {
                        Viewer(state, image, outline, scaleMode, { scaleMode = it }, spaceDown, { spacePanned = true }, { viewport = it }) { x, y ->
                            scope.launch {
                                val selected = withContext(Dispatchers.Default) { synchronized(lock) { engine.hitTest(x, y, state.time) } }
                                state.selected = selected
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(panel).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        listOf("|◀" to { state.time = 0 }, "◀" to { state.step(-1) }, (if (state.playing) "❚❚" else "▶") to { state.playing = !state.playing }, "▶" to { state.step(1) }, "▶|" to { state.time = scene.frameStart(scene.lastFrame) }, (if (loop) "Loop ●" else "Loop") to { loop = !loop }).forEach { (label, action) -> Text(label, Modifier.clickable { action() }.padding(horizontal = 9.dp), color = Color.White, fontSize = 12.sp) }
                        Text("${formatTime(state.time, scene.fps)} / ${formatTime(scene.totalTime, scene.fps)}", Modifier.padding(start = 10.dp), fontSize = 12.sp)
                        Text(when (resolution) { 2 -> "Half"; 4 -> "Quarter"; else -> "Full" }, Modifier.clickable { resolution = when (resolution) { 1 -> 2; 2 -> 4; else -> 1 } }.padding(start = 12.dp), color = muted, fontSize = 12.sp)
                    }
                }
                DividerDrag(true) { rightWidth = (rightWidth - it).coerceIn(220f, 500f); prefs.putFloat("rightWidth", rightWidth) }
                Box(Modifier.width(rightWidth.dp).fillMaxHeight().background(panel)) { Inspector(state, engine.definitions) { tab = "Effects" } }
            }
            DividerDrag(false) { timelineHeight = (timelineHeight - it).coerceIn(110f, 500f); prefs.putFloat("timelineHeight", timelineHeight) }
            Box(Modifier.height(timelineHeight.dp).fillMaxWidth()) { Timeline(state, zoom, { zoom = it }, status) }
        }
        if (exporting) ExportDialog(state, window) { exporting = false }
        if (settings) ProjectSettings(state, { settings = false }, inline = window == null)
        if (shortcuts) KeyboardShortcuts(inline = window == null) { shortcuts = false }
        }
        }
    }
}

@Composable
private fun ToolbarButton(label: String, enabled: Boolean = true, action: () -> Unit) {
    Text(label, Modifier.clickable(enabled = enabled, onClick = action).padding(horizontal = 10.dp, vertical = 8.dp), color = if (enabled) Color.White else muted.copy(alpha = 0.5f), fontSize = 12.sp)
}
