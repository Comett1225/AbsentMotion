package app.absentmotion.pc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

private val panel = Color(0xff25272d)
private val preview = Color(0xff14161a)
private val timeline = Color(0xff1c1e24)
private val accent = Color(0xffff8f66)
private val muted = Color(0xffa9abb2)
private val dataDir = File(System.getProperty("user.home"), "Library/Application Support/AbsentMotion")
private fun assets(): File = System.getProperty("am.assets")?.let(::File) ?: File(System.getProperty("compose.application.resources.dir"), "assets")
private data class RenderRequest(val scene: Scene, val time: Int, val viewport: androidx.compose.ui.unit.IntSize, val selected: Long, val scaleMode: String)
private fun emptyScene() = Scene.parse("<scene title=\"Untitled\" width=\"1920\" height=\"1080\" fps=\"30\" totalTime=\"10000\" bgcolor=\"#ff14161a\"/>")

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--scene-check") {
        sceneCheck()
        return
    }
    if (args.firstOrNull() == "--render-check") {
        renderCheck()
        return
    }
    if (args.firstOrNull() == "--render") {
        val source = File(args[1])
        DesktopEngine(assets(), System.getProperty("am.repo")?.let(::File)).use { engine ->
            val scene = Scene.parse(source.readText())
            engine.setScene(scene, source.parentFile)
            val scale = minOf(1.0, 1200.0 / maxOf(scene.width, scene.height))
            ImageIO.write(engine.render(args.getOrNull(3)?.toIntOrNull() ?: 0, (scene.width * scale).roundToInt(), (scene.height * scale).roundToInt()), "png", File(args[2]))
            println("Effects loaded: ${engine.definitions.size - engine.loadErrors.size}; failures: ${engine.loadErrors}")
        }
        return
    }
    application {
        val size = java.awt.Toolkit.getDefaultToolkit().screenSize
        val state = rememberWindowState(width = (size.width * 0.9).dp, height = (size.height * 0.9).dp)
        Window(onCloseRequest = ::exitApplication, title = "AbsentMotion", state = state) {
            Workspace(window, ::exitApplication, args.firstOrNull()?.let(::File)?.takeIf { it.isFile })
        }
    }
}

@Composable
private fun FrameWindowScope.Workspace(window: java.awt.Window, quit: () -> Unit, startFile: File?) {
    val engine = remember { DesktopEngine(assets(), System.getProperty("am.repo")?.let(::File)) }
    var scene by remember { mutableStateOf(emptyScene()) }
    var file by remember { mutableStateOf<File?>(null) }
    var time by remember { mutableIntStateOf(0) }
    var selected by remember { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf(runCatching { dataDir.resolve("recent.txt").readLines().map(::File).filter(File::isFile).take(10) }.getOrDefault(emptyList())) }
    var leftWidth by remember { mutableFloatStateOf(220f) }
    var rightWidth by remember { mutableFloatStateOf(230f) }
    var timelineHeight by remember { mutableFloatStateOf(210f) }
    var scaleMode by remember { mutableStateOf("Fit") }
    var tab by remember { mutableStateOf("Media") }
    var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(800, 500)) }
    var image by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var outline by remember { mutableStateOf<FloatArray?>(null) }
    val lock = remember { Any() }

    fun setProject(next: Scene, source: File?) {
        runCatching {
            synchronized(lock) { engine.setScene(next, source?.parentFile) }
            scene = next
            file = source
            (window as? javax.swing.JFrame)?.rootPane?.putClientProperty("Window.documentFile", source)
            (window as? java.awt.Frame)?.title = source?.nameWithoutExtension?.let { "$it - AbsentMotion" } ?: "AbsentMotion"
            time = 0
            selected = 0
            playing = false
            status = ""
            if (source != null) {
                recent = (listOf(source) + recent.filter { it != source }).take(10)
                dataDir.mkdirs()
                dataDir.resolve("recent.txt").writeText(recent.joinToString("\n") { it.absolutePath })
            }
        }.onFailure { status = it.message ?: "Could not open project" }
    }
    fun choose(save: Boolean): File? {
        val dialog = FileDialog(window as java.awt.Frame, if (save) "Save Project" else "Open Project", if (save) FileDialog.SAVE else FileDialog.LOAD)
        dialog.isVisible = true
        return dialog.file?.let { File(dialog.directory, it) }
    }
    fun open(source: File?) { if (source != null) runCatching { setProject(Scene.parse(source.readText()), source) }.onFailure { status = it.message ?: "Could not open project" } }
    fun save(asNew: Boolean) {
        val dest = (if (asNew) null else file) ?: choose(true) ?: return
        runCatching { dest.writeText(scene.toXml()); file = dest; status = "Saved" }.onFailure { status = it.message ?: "Could not save project" }
    }
    fun step(frames: Int) { time = scene.frameStart((scene.frameAt(time) + frames).coerceIn(0, scene.lastFrame)) }
    LaunchedEffect(Unit) {
        setProject(scene, null)
        open(startFile)
        runCatching { java.awt.Desktop.getDesktop().setOpenFileHandler { event -> event.files.firstOrNull()?.let(::open) } }
    }
    LaunchedEffect(playing, scene) {
        if (playing) {
            val start = System.nanoTime()
            val initial = time
            while (true) {
                val elapsed = ((System.nanoTime() - start) / 1_000_000).toInt()
                val frame = scene.frameAt((initial + elapsed).coerceAtMost(scene.totalTime))
                time = scene.frameStart(frame)
                if (initial + elapsed >= scene.totalTime) { playing = false; break }
                delay((1000L / scene.fps / 2).coerceAtLeast(5))
            }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { RenderRequest(scene, time, viewport, selected, scaleMode) }.conflate().collect { request ->
            val availableW = request.viewport.width.coerceAtLeast(1)
            val availableH = request.viewport.height.coerceAtLeast(1)
            val fit = minOf(availableW.toFloat() / request.scene.width, availableH.toFloat() / request.scene.height)
            val target = when (request.scaleMode) { "50%" -> 0.5f; "100%" -> 1f; else -> fit }
            val renderScale = minOf(target, 1f, 1600f / maxOf(request.scene.width, request.scene.height).coerceAtLeast(1))
            val w = (request.scene.width * renderScale).roundToInt().coerceIn(1, 1600)
            val h = (request.scene.height * renderScale).roundToInt().coerceIn(1, 1600)
            runCatching {
                withContext(Dispatchers.Default) {
                    synchronized(lock) {
                        engine.render(request.time, w, h).toComposeImageBitmap() to
                            if (request.selected != 0L) engine.quad(request.selected, request.time) else null
                    }
                }
            }.onSuccess { result ->
                if (scene == request.scene) { image = result.first; outline = result.second }
            }.onFailure { status = it.message ?: "Render failed" }
        }
    }
    DisposableEffect(Unit) { onDispose { synchronized(lock) { engine.close() } } }
    MenuBar {
        Menu("File") {
            Item("New", onClick = { setProject(emptyScene(), null) }, shortcut = KeyShortcut(Key.N, meta = true))
            Item("Open…", onClick = { open(choose(false)) }, shortcut = KeyShortcut(Key.O, meta = true))
            Menu("Open Recent") { recent.forEach { entry -> Item(entry.name, onClick = { open(entry) }) } }
            Separator()
            Item("Save", onClick = { save(false) }, shortcut = KeyShortcut(Key.S, meta = true))
            Item("Save As…", onClick = { save(true) }, shortcut = KeyShortcut(Key.S, meta = true, shift = true))
            Item("Close", onClick = { setProject(emptyScene(), null) }, shortcut = KeyShortcut(Key.W, meta = true))
            Separator()
            Item("Quit", onClick = quit, shortcut = KeyShortcut(Key.Q, meta = true))
        }
        Menu("Edit") { Item("Undo", onClick = {}, enabled = false); Item("Redo", onClick = {}, enabled = false) }
        Menu("View") { listOf("Fit", "50%", "100%").forEach { item -> Item(item, onClick = { scaleMode = item }) } }
        Menu("Help") { Item("About AbsentMotion", onClick = { status = "AbsentMotion 5.0.0" }) }
    }
    MaterialTheme(colors = darkColors(primary = accent, surface = panel, background = preview)) {
        Column(Modifier.fillMaxSize().background(preview).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.Spacebar -> { playing = !playing; true }
                Key.DirectionLeft -> { playing = false; step(-1); true }
                Key.DirectionRight -> { playing = false; step(1); true }
                Key.MoveHome -> { playing = false; time = 0; true }
                Key.MoveEnd -> { playing = false; time = scene.frameStart(scene.lastFrame); true }
                else -> false
            }
        }) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(leftWidth.dp).fillMaxHeight().background(panel)) {
                    Row { listOf("Media", "Effects").forEach { name -> Text(name, Modifier.clickable { tab = name }.padding(12.dp), color = if (tab == name) accent else muted) } }
                    if (tab == "Effects") Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) { engine.definitions.forEach { Text(it.name, Modifier.padding(vertical = 4.dp), fontSize = 12.sp) } }
                    else Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) { scene.media.forEach { Text(it.uri.substringAfterLast('/'), Modifier.padding(vertical = 4.dp), fontSize = 12.sp) } }
                }
                DividerDrag(true) { leftWidth = (leftWidth + it).coerceIn(140f, 400f) }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { viewport = it }.pointerInput(scene, time, scaleMode) {
                        detectTapGestures { p ->
                            val fit = when (scaleMode) { "50%" -> 0.5f; "100%" -> 1f; else -> minOf(size.width.toFloat() / scene.width, size.height.toFloat() / scene.height) }
                            val x = (p.x - (size.width - scene.width * fit) / 2) / fit
                            val y = (p.y - (size.height - scene.height * fit) / 2) / fit
                            selected = synchronized(lock) { engine.hitTest(x, y, time) }
                        }
                    }, contentAlignment = Alignment.Center) {
                        image?.let {
                            val base = minOf(viewport.width.toFloat() / scene.width, viewport.height.toFloat() / scene.height).coerceAtLeast(0.001f)
                            val actual = when (scaleMode) { "50%" -> 0.5f; "100%" -> 1f; else -> base }
                            Image(it, null, Modifier.fillMaxSize().graphicsLayer { scaleX = actual / base; scaleY = actual / base }, contentScale = ContentScale.Fit)
                        }
                        Canvas(Modifier.fillMaxSize()) {
                            outline?.let { q ->
                                val fit = when (scaleMode) { "50%" -> 0.5f; "100%" -> 1f; else -> minOf(size.width / scene.width, size.height / scene.height) }
                                val ox = (size.width - scene.width * fit) / 2
                                val oy = (size.height - scene.height * fit) / 2
                                val path = Path().apply { moveTo(ox + q[0] * fit, oy + q[1] * fit); for (i in 1..3) lineTo(ox + q[i*2] * fit, oy + q[i*2+1] * fit); close() }
                                drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(panel).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        listOf("|◀" to { time = 0 }, "◀" to { step(-1) }, (if (playing) "❚❚" else "▶") to { playing = !playing }, "▶" to { step(1) }, "▶|" to { time = scene.frameStart(scene.lastFrame) }).forEach { (label, action) -> Text(label, Modifier.clickable { action() }.padding(horizontal = 12.dp), color = Color.White) }
                        Text(formatTime(time, scene.fps), Modifier.padding(start = 20.dp), fontSize = 13.sp)
                        Text(scaleMode, Modifier.padding(start = 20.dp), color = muted, fontSize = 12.sp)
                    }
                }
                DividerDrag(true) { rightWidth = (rightWidth - it).coerceIn(160f, 400f) }
                Column(Modifier.width(rightWidth.dp).fillMaxHeight().background(panel).padding(14.dp)) {
                    Text("Inspector", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    scene.layer(selected)?.let { layer ->
                        Text(layer.label.ifBlank { layer.kind }, color = accent)
                        Text("Kind: ${layer.kind}", Modifier.padding(top = 12.dp), color = muted)
                        Text("Start: ${formatTime(layer.startTime, scene.fps)}", Modifier.padding(top = 8.dp), color = muted)
                        Text("End: ${formatTime(layer.endTime, scene.fps)}", Modifier.padding(top = 8.dp), color = muted)
                    } ?: Text("Select a layer", color = muted)
                }
            }
            DividerDrag(false) { timelineHeight = (timelineHeight - it).coerceIn(100f, 500f) }
            Column(Modifier.height(timelineHeight.dp).fillMaxWidth().background(timeline)) {
                Text("Timeline    ${formatTime(time, scene.fps)}    $status", Modifier.padding(10.dp), color = muted, fontSize = 12.sp)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    scene.layers.asReversed().forEach { layer ->
                        Row(Modifier.fillMaxWidth().height(28.dp).clickable { selected = layer.id }, verticalAlignment = Alignment.CenterVertically) {
                            Text(layer.label.ifBlank { layer.kind }, Modifier.width(180.dp).padding(start = 10.dp), fontSize = 12.sp, color = if (selected == layer.id) accent else Color.White, maxLines = 1)
                            Canvas(Modifier.weight(1f).fillMaxHeight().pointerInput(scene) { detectTapGestures { p -> time = (p.x / size.width * scene.totalTime).toInt().coerceIn(0, scene.totalTime) } }.pointerInput(scene) { detectDragGestures { change, _ -> time = (change.position.x / size.width * scene.totalTime).toInt().coerceIn(0, scene.totalTime) } }) {
                                val total = scene.totalTime.coerceAtLeast(1).toFloat()
                                drawRect(if (selected == layer.id) accent else Color(0xff666c80), Offset(size.width * layer.startTime / total, 4f), Size(size.width * (layer.endTime - layer.startTime).coerceAtLeast(1) / total, size.height - 8f))
                                val x = size.width * time / total
                                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2f)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DividerDrag(vertical: Boolean, change: (Float) -> Unit) {
    Box(Modifier.then(if (vertical) Modifier.width(5.dp).fillMaxHeight() else Modifier.height(5.dp).fillMaxWidth()).background(Color(0xff383b42)).pointerInput(vertical) { detectDragGestures { _, drag -> change(if (vertical) drag.x else drag.y) } })
}

private fun formatTime(ms: Int, fps: Int): String = "%02d:%02d:%02d:%02d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, (ms % 1000) * fps / 1000)
