package app.absentmotion.pc

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import app.absentmotion.pc.platform.DesktopPlatform
import app.absentmotion.pc.ui.Workspace
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
        val state = rememberWindowState(width = (size.width * 0.9).dp, height = (size.height * 0.9).dp)
        val close = remember { mutableListOf<() -> Unit>() }
        Window(onCloseRequest = { close.firstOrNull()?.invoke() }, title = "AbsentMotion", state = state) {
            Workspace(window, { exitApplication() }, args.firstOrNull()?.let(::File)?.takeIf { it.isFile }, close)
        }
    }
}
