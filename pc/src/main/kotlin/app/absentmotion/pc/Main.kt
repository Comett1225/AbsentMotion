package app.absentmotion.pc

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.prefs.Preferences
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import app.absentmotion.pc.platform.DesktopPlatform
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskFiles
import app.absentmotion.desk.DeskExportMini
import app.absentmotion.desk.DeskInstance
import app.absentmotion.desk.DeskOpen
import app.absentmotion.desk.DeskOs
import app.absentmotion.desk.DeskTaskbar
import app.absentmotion.desk.DeskWindow
import app.absentmotion.export.DeskExports
import app.absentmotion.export.ExportRun
import app.absentmotion.desk.DeskKeys
import app.absentmotion.desk.DeskMenus
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.appearance", "system")
    when (args.firstOrNull()) {
        "--warmup" -> { warmup(); return }
        "--scene-check" -> { sceneCheck(); return }
        "--render-check" -> { renderCheck(); return }
        "--render" -> {
            val source = File(args[1])
            DesktopEngine(DesktopPlatform.assets(), System.getProperty("am.repo")?.let(::File)).use { engine ->
                val scene = Scene.parse(source.readText())
                engine.setScene(scene, source.parentFile)
                val scale = minOf(1.0, 1200.0 / maxOf(scene.width, scene.height))
                ImageIO.write(engine.render(args.getOrNull(3)?.toIntOrNull() ?: 0, (scene.width * scale).roundToInt(), (scene.height * scale).roundToInt()), "png", File(args[2]))
                println("Effects loaded: ${engine.definitions.size - engine.loadErrors.size}; failures: ${engine.loadErrors}")
            }
            return
        }
    }
    val files = DeskOpen.fromArguments(args)
    if (!DeskOs.mac && DeskInstance.forward(files)) return
    DeskOpen.offer(files)
    DeskOpen.listenForFiles()
    DeskInstance.serve { DeskOpen.offer(it); DeskOpen.raise() }
    application {
        val prefs = remember { Preferences.userNodeForPackage(DesktopPlatform::class.java) }
        val placed = remember {
            val saved = if (prefs.getInt("windowWidth", 0) > 0) java.awt.Rectangle(prefs.getInt("windowX", 0), prefs.getInt("windowY", 0), prefs.getInt("windowWidth", 0), prefs.getInt("windowHeight", 0)) else null
            val (screens, primary) = DeskWindow.screens()
            DeskWindow.place(saved, screens, primary, 800, 560)
        }
        val state = rememberWindowState(
            placement = if (prefs.getBoolean("windowMaximized", false)) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = WindowPosition(placed.x.dp, placed.y.dp), width = placed.width.dp, height = placed.height.dp,
        )
        val desk = remember { DeskAppState() }
        val scope = rememberCoroutineScope()
        remember { desk.quitNow = ::exitApplication }
        LaunchedEffect(DeskOpen.raised.intValue) { if (DeskOpen.raised.intValue > 0) desk.hidden = false }
        DisposableEffect(Unit) {
            val platform = runCatching { java.awt.Desktop.getDesktop().takeIf { java.awt.Desktop.isDesktopSupported() && it.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER) } }.getOrNull()
            platform?.setQuitHandler { _, response ->
                if (ExportRun.running) { response.cancelQuit(); desk.requestQuit() }
                else scope.launch { if (desk.shutdown()) response.performQuit() else { response.cancelQuit(); desk.afterClose = desk.quitNow } }
            }
            val reopen = object : java.awt.desktop.AppReopenedListener { override fun appReopened(e: java.awt.desktop.AppReopenedEvent) { desk.hidden = false } }
            val events = runCatching { java.awt.Desktop.getDesktop().takeIf { java.awt.Desktop.isDesktopSupported() && it.isSupported(java.awt.Desktop.Action.APP_EVENT_REOPENED) } }.getOrNull()
            events?.addAppEventListener(reopen)
            onDispose { runCatching { platform?.setQuitHandler(null) }; runCatching { events?.removeAppEventListener(reopen) } }
        }
        LaunchedEffect(DeskExports.running, (DeskExports.fraction * 100).toInt()) { DeskTaskbar.progress(if (DeskExports.running) (DeskExports.fraction * 100).toInt() else null) }
        if (desk.hidden) Window(onCloseRequest = desk::requestQuit, title = "AbsentMotion", state = rememberWindowState(position = WindowPosition(Alignment.Center), width = 460.dp, height = 220.dp), resizable = false) {
            remember(window) { DeskWindow.secondary(window) }
            DeskExportMini(desk)
        }
        Window(onCloseRequest = desk::requestQuit, visible = !desk.hidden, title = "AbsentMotion", state = state,
            onPreviewKeyEvent = { it.type == KeyEventType.KeyDown && it.key == Key.Escape && DeskBack.back() || DeskKeys.handle(desk, it) },
            onKeyEvent = { app.absentmotion.desk.DeskGlobalKeys.handle(desk, it) }) {
            DeskFiles.window = window
            remember(window) { window.minimumSize = java.awt.Dimension(820, 560) }
            remember(window) { DeskWindow.attach(window) }
            if (DeskOs.mac) DeskMenus(desk)
            DisposableEffect(window) {
                val listener = object : ComponentAdapter() {
                    private fun save() {
                        if (!window.isShowing || window.extendedState != java.awt.Frame.NORMAL) return
                        prefs.putInt("windowWidth", window.width); prefs.putInt("windowHeight", window.height)
                        prefs.putInt("windowX", window.x); prefs.putInt("windowY", window.y)
                    }
                    override fun componentMoved(e: ComponentEvent) = save()
                    override fun componentResized(e: ComponentEvent) = save()
                }
                val maximized = java.awt.event.WindowStateListener { prefs.putBoolean("windowMaximized", it.newState and java.awt.Frame.MAXIMIZED_BOTH == java.awt.Frame.MAXIMIZED_BOTH) }
                window.addComponentListener(listener)
                window.addWindowStateListener(maximized)
                onDispose { window.removeComponentListener(listener); window.removeWindowStateListener(maximized) }
            }
            DeskApp(desk)
        }
    }
}
