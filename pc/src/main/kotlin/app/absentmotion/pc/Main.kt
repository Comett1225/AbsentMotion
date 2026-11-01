package app.absentmotion.pc

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
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
import app.absentmotion.desk.DeskMenus
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
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
    application {
        val size = java.awt.Toolkit.getDefaultToolkit().screenSize
        val prefs = remember { Preferences.userNodeForPackage(DesktopPlatform::class.java) }
        val width = prefs.getInt("windowWidth", (size.width * 0.9).toInt()).coerceIn(640, size.width)
        val height = prefs.getInt("windowHeight", (size.height * 0.9).toInt()).coerceIn(480, size.height)
        val x = prefs.getInt("windowX", (size.width - width) / 2).coerceIn(0, (size.width - 100).coerceAtLeast(0))
        val y = prefs.getInt("windowY", (size.height - height) / 2).coerceIn(0, (size.height - 100).coerceAtLeast(0))
        val state = rememberWindowState(position = WindowPosition(x.dp, y.dp), width = width.dp, height = height.dp)
        val desk = remember { DeskAppState() }
        Window(onCloseRequest = { exitApplication() }, title = "AbsentMotion", state = state,
            onPreviewKeyEvent = { it.type == KeyEventType.KeyDown && it.key == Key.Escape && DeskBack.back() },
            onKeyEvent = { app.absentmotion.desk.DeskKeys.handle(desk, it) }) {
            DeskFiles.window = window
            if (DesktopPlatform.mac) remember(window) {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
            DeskMenus(desk)
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
                window.addComponentListener(listener)
                onDispose { window.removeComponentListener(listener) }
            }
            DeskApp(desk)
        }
    }
}
