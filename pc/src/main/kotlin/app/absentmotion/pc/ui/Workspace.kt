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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import app.absentmotion.model.*
import app.absentmotion.pc.engine.DesktopEngine
import app.absentmotion.pc.platform.DesktopPlatform
import app.absentmotion.pc.media.inspectMedia
import app.absentmotion.pc.media.addMedia
import app.absentmotion.pc.project.ProjectState
import app.absentmotion.pc.project.emptyScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.awt.Window
import java.io.File
import java.util.prefs.Preferences
import javax.swing.JOptionPane
import kotlin.math.roundToInt

private data class RenderRequest(val scene: Scene, val time: Int, val viewport: IntSize, val selected: Long, val scaleMode: String, val playing: Boolean, val resolution: Int, val base: File?)

@Composable
fun FrameWindowScope.Workspace(window: Window, quit: () -> Unit, startFile: File?, close: MutableList<() -> Unit>) {
    val assets = remember { DesktopPlatform.assets() }
    val engine = remember { DesktopEngine(assets, System.getProperty("am.repo")?.let(::File)) }
    val state = remember { ProjectState() }
    val prefs = remember { Preferences.userNodeForPackage(ProjectState::class.java) }
    var status by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf(runCatching { DesktopPlatform.dataDir.resolve("recent.txt").readLines().map(::File).filter(File::isFile).take(10) }.getOrDefault(emptyList())) }
    var leftWidth by remember { mutableFloatStateOf(prefs.getFloat("leftWidth", 240f)) }
    var rightWidth by remember { mutableFloatStateOf(prefs.getFloat("rightWidth", 300f)) }
    var timelineHeight by remember { mutableFloatStateOf(prefs.getFloat("timelineHeight", 245f)) }
    var scaleMode by remember { mutableStateOf("Fit") }
    var tab by remember { mutableStateOf("Media") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var resolution by remember { mutableIntStateOf(1) }
    var loop by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize(800, 500)) }
    var image by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var outline by remember { mutableStateOf<FloatArray?>(null) }
    val currentOutline by rememberUpdatedState(outline)
    val lock = remember { Any() }
    val scope = rememberCoroutineScope()
    val renderedScene = remember { arrayOfNulls<Scene>(1) }
    val renderedBase = remember { arrayOfNulls<File>(1) }
    fun importMedia() {
        val files = DesktopPlatform.chooseMedia(window)
        if (files.isEmpty()) return
        scope.launch {
            val imported = withContext(Dispatchers.IO) { files.mapNotNull { runCatching { inspectMedia(it) }.getOrNull() } }
            state.edit { scene -> imported.fold(scene) { result, media -> result.withMedia(media) } }
            status = if (imported.size == files.size) "Imported ${imported.size} files" else "Imported ${imported.size} of ${files.size} files"
        }
    }

    fun save(asNew: Boolean): Boolean {
        val dest = (if (asNew) null else state.file) ?: DesktopPlatform.choose(window, true) ?: return false
        return runCatching { dest.writeText(state.scene.toXml()); state.saved(dest); prefs.putBoolean("recoveryPending", false); DesktopPlatform.title(window, dest); status = "Saved"; true }.getOrElse { status = it.message ?: "Could not save project"; false }
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
        runCatching {
            val next = Scene.parse(source.readText())
            state.open(next, source)
            DesktopPlatform.title(window, source)
            recent = (listOf(source) + recent.filter { it != source }).take(10)
            DesktopPlatform.dataDir.mkdirs()
            DesktopPlatform.dataDir.resolve("recent.txt").writeText(recent.joinToString("\n") { it.absolutePath })
            status = ""
        }.onFailure { status = it.message ?: "Could not open project" }
    }
    fun newProject() { if (canClose()) { state.open(emptyScene(), null); DesktopPlatform.title(window, null) } }
    SideEffect { close.clear(); close.add { if (canClose()) quit() } }
    LaunchedEffect(Unit) {
        val recovery = DesktopPlatform.dataDir.resolve("autosave/recovery.xml")
        if (startFile == null && prefs.getBoolean("recoveryPending", false) && recovery.isFile) {
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
            val start = System.nanoTime()
            val initial = state.time
            while (true) {
                val elapsed = ((System.nanoTime() - start) / 1_000_000).toInt()
                val total = state.scene.totalTime.coerceAtLeast(1)
                val raw = initial + elapsed
                if (raw >= total && !loop) { state.time = state.scene.frameStart(state.scene.lastFrame); state.playing = false; break }
                state.time = state.scene.frameStart(state.scene.frameAt(if (loop) raw % total else raw))
                delay((1000L / state.scene.fps / 2).coerceAtLeast(5))
            }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { RenderRequest(state.scene, state.time, viewport, state.selected, scaleMode, state.playing, resolution, state.file?.parentFile) }.conflate().collect { request ->
            val fit = minOf(request.viewport.width.coerceAtLeast(1).toFloat() / request.scene.width.coerceAtLeast(1), request.viewport.height.coerceAtLeast(1).toFloat() / request.scene.height.coerceAtLeast(1))
            val target = when (request.scaleMode) { "50%" -> 0.5f; "100%" -> 1f; "200%" -> 2f; else -> fit }
            val renderScale = minOf(target, 1f, 1600f / maxOf(request.scene.width, request.scene.height).coerceAtLeast(1)) / if (request.playing) request.resolution else 1
            val w = (request.scene.width * renderScale).roundToInt().coerceIn(1, 1600)
            val h = (request.scene.height * renderScale).roundToInt().coerceIn(1, 1600)
            runCatching { withContext(Dispatchers.Default) {
                synchronized(lock) {
                    if (renderedScene[0] != request.scene || renderedBase[0] != request.base) { engine.setScene(request.scene, request.base); renderedScene[0] = request.scene; renderedBase[0] = request.base }
                    engine.render(request.time, w, h).toComposeImageBitmap() to if (request.selected != 0L) engine.quad(request.selected, request.time) else null
                }
            } }.onSuccess { result -> if (state.scene == request.scene) { image = result.first; outline = result.second } }.onFailure { status = it.message ?: "Render failed" }
        }
    }
    DisposableEffect(Unit) { onDispose { synchronized(lock) { engine.close() } } }
    MenuBar {
        Menu("File") {
            Item("New", onClick = ::newProject, shortcut = KeyShortcut(Key.N, meta = true))
            Item("Open…", onClick = { open(DesktopPlatform.choose(window, false)) }, shortcut = KeyShortcut(Key.O, meta = true))
            Menu("Open Recent") { recent.forEach { entry -> Item(entry.name, onClick = { open(entry) }) } }
            Item("Import Media…", onClick = ::importMedia)
            Separator()
            Item("Save", onClick = { save(false) }, shortcut = KeyShortcut(Key.S, meta = true))
            Item("Save As…", onClick = { save(true) }, shortcut = KeyShortcut(Key.S, meta = true, shift = true))
            Item("Project Settings…", onClick = { settings = true })
            Item("Close", onClick = ::newProject, shortcut = KeyShortcut(Key.W, meta = true))
            Item("Quit", onClick = { if (canClose()) quit() }, shortcut = KeyShortcut(Key.Q, meta = true))
        }
        Menu("Edit") {
            Item("Undo", onClick = state::undo, enabled = state.canUndo, shortcut = KeyShortcut(Key.Z, meta = true))
            Item("Redo", onClick = state::redo, enabled = state.canRedo, shortcut = KeyShortcut(Key.Z, meta = true, shift = true))
            Item("Duplicate", onClick = state::duplicate, enabled = state.layer != null, shortcut = KeyShortcut(Key.D, meta = true))
            Item("Delete", onClick = state::delete, enabled = state.layer != null)
        }
        Menu("Layer") {
            Item("Add Text", onClick = { addText(state) })
            Menu("Add Shape") { shapes(assets).forEach { shape -> Item(shape["name"] ?: "Shape", onClick = { addShape(state, shape) }) } }
            Item("Split at Playhead", onClick = state::split, enabled = state.layer != null, shortcut = KeyShortcut(Key.B, meta = true))
            Item("Bring Forward", onClick = { val i = state.scene.layers.indexOfFirst { it.id == state.selected }; state.edit { it.reorder(state.selected, i + 1) } }, enabled = state.layer != null)
            Item("Send Backward", onClick = { val i = state.scene.layers.indexOfFirst { it.id == state.selected }; state.edit { it.reorder(state.selected, i - 1) } }, enabled = state.layer != null)
        }
        Menu("Effect") { Item("Add Effect…", onClick = { tab = "Effects" }, enabled = state.layer != null) }
        Menu("View") { listOf("Fit", "50%", "100%", "200%").forEach { item -> Item(item, onClick = { scaleMode = item }) } }
        Menu("Window") { Item("Reset Layout", onClick = { leftWidth = 240f; rightWidth = 300f; timelineHeight = 245f }) }
        Menu("Help") { Item("About AbsentMotion", onClick = { JOptionPane.showMessageDialog(window, "AbsentMotion 5.0.0") }) }
    }
    MaterialTheme(colors = darkColors(primary = accent, surface = panel, background = preview)) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(Modifier.fillMaxSize().background(preview).onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || event.isMetaPressed || event.isCtrlPressed) false else when (event.key) {
                Key.Spacebar -> { state.playing = !state.playing; true }
                Key.DirectionLeft -> { state.playing = false; state.step(if (event.isShiftPressed) -10 else -1); true }
                Key.DirectionRight -> { state.playing = false; state.step(if (event.isShiftPressed) 10 else 1); true }
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
                ToolbarButton("Export", false) {}
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(leftWidth.dp).fillMaxHeight().background(panel)) {
                    Row { listOf("Media", "Effects", "Elements").forEach { name -> Text(name, Modifier.clickable { tab = name }.padding(10.dp), color = if (tab == name) accent else muted, fontSize = 12.sp) } }
                    when (tab) {
                        "Effects" -> EffectsLibrary(state, engine.definitions, assets)
                        "Elements" -> Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text("Text", Modifier.fillMaxWidth().clickable { addText(state) }.padding(10.dp), color = accent)
                            shapes(assets).forEach { shape -> Text(shape["name"] ?: "Shape", Modifier.fillMaxWidth().clickable { addShape(state, shape) }.padding(8.dp), fontSize = 12.sp) }
                        }
                        else -> MediaPool(state, ::importMedia)
                    }
                }
                DividerDrag(true) { leftWidth = (leftWidth + it).coerceIn(160f, 430f); prefs.putFloat("leftWidth", leftWidth) }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val scene = state.scene
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { viewport = it }.pointerInput(scene, state.time, scaleMode) {
                        detectTapGestures { p ->
                            val fit = when (scaleMode) { "50%" -> 0.5f; "100%" -> 1f; "200%" -> 2f; else -> minOf(size.width.toFloat() / scene.width, size.height.toFloat() / scene.height) }
                            val x = (p.x - (size.width - scene.width * fit) / 2) / fit
                            val y = (p.y - (size.height - scene.height * fit) / 2) / fit
                            scope.launch { val selected = withContext(Dispatchers.Default) { synchronized(lock) { engine.hitTest(x, y, state.time) } }; state.selected = selected }
                        }
                    }.pointerInput(state.selected, state.time, scaleMode) {
                        var active = false
                        var dx = 0f
                        var dy = 0f
                        var fit = 1f
                        var location = floatArrayOf(0f, 0f, 0f)
                        detectDragGestures(onDragStart = { point ->
                            val q = currentOutline
                            val scene = state.scene
                            val layer = scene.layerAt(state.selected, state.time)
                            fit = when (scaleMode) { "50%" -> 0.5f; "100%" -> 1f; "200%" -> 2f; else -> minOf(size.width.toFloat() / scene.width, size.height.toFloat() / scene.height) }.coerceAtLeast(0.001f)
                            val ox = (size.width - scene.width * fit) / 2; val oy = (size.height - scene.height * fit) / 2
                            val x = (point.x - ox) / fit; val y = (point.y - oy) / fit
                            active = q != null && layer != null && x in minOf(q[0], q[2], q[4], q[6])..maxOf(q[0], q[2], q[4], q[6]) && y in minOf(q[1], q[3], q[5], q[7])..maxOf(q[1], q[3], q[5], q[7])
                            if (active && layer != null) { location = layer.location; dx = 0f; dy = 0f; state.beginGesture() }
                        }, onDragEnd = { if (active) state.endGesture(); active = false }, onDragCancel = { if (active) state.cancelGesture(); active = false }) { change, amount ->
                            if (active) {
                                change.consume(); dx += amount.x / fit; dy += amount.y / fit
                                state.gesture { start -> start.update(state.selected, state.time) { it.withTransform("location", location[0] + dx, location[1] + dy, location.getOrElse(2) { 0f }) } }
                            }
                        }
                    }, contentAlignment = Alignment.Center) {
                        image?.let { bitmap ->
                            val base = minOf(viewport.width.toFloat() / scene.width, viewport.height.toFloat() / scene.height).coerceAtLeast(0.001f)
                            val actual = when (scaleMode) { "50%" -> 0.5f; "100%" -> 1f; "200%" -> 2f; else -> base }
                            Image(bitmap, null, Modifier.fillMaxSize().graphicsLayer { scaleX = actual / base; scaleY = actual / base }, contentScale = ContentScale.Fit)
                        }
                        Canvas(Modifier.fillMaxSize()) {
                            outline?.let { q ->
                                val fit = when (scaleMode) { "50%" -> 0.5f; "100%" -> 1f; "200%" -> 2f; else -> minOf(size.width / scene.width, size.height / scene.height) }
                                val ox = (size.width - scene.width * fit) / 2; val oy = (size.height - scene.height * fit) / 2
                                val path = Path().apply { moveTo(ox + q[0] * fit, oy + q[1] * fit); for (i in 1..3) lineTo(ox + q[i*2] * fit, oy + q[i*2+1] * fit); close() }
                                drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                                for (i in 0..3) drawCircle(accent, 4f, Offset(ox + q[i*2] * fit, oy + q[i*2+1] * fit))
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
        if (settings) ProjectSettings(state) { settings = false }
        }
    }
}

@Composable
private fun ToolbarButton(label: String, enabled: Boolean = true, action: () -> Unit) {
    Text(label, Modifier.clickable(enabled = enabled, onClick = action).padding(horizontal = 10.dp, vertical = 8.dp), color = if (enabled) Color.White else muted.copy(alpha = 0.5f), fontSize = 12.sp)
}
