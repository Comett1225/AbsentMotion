package app.absentmotion.pc

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.addShape
import app.absentmotion.editor.addText
import app.absentmotion.model.Scene
import app.absentmotion.model.ShapeCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

fun warmup() {
    val dir = java.nio.file.Files.createTempDirectory("absentmotion-warmup").toFile()
    System.setProperty("am.data", dir.resolve("data").path)
    System.setProperty("am.exports", dir.resolve("exports").path)
    application {
        val desk = remember { DeskAppState().also { it.settings.onboarding = app.absentmotion.ui.onboarding.ONBOARDING_DONE } }
        Window(onCloseRequest = ::exitApplication, title = "AbsentMotion", state = rememberWindowState(width = 1280.dp, height = 800.dp)) {
            remember(window) { app.absentmotion.desk.DeskWindow.decorate(window) }
            DeskApp(desk)
            LaunchedEffect(Unit) {
                delay(1500)
                val id = withContext(Dispatchers.IO) { desk.store.create(Scene.create("Warm-up", 1080, 1920, 30, 0xff000000.toInt())) }
                desk.openNow(id)?.let { state ->
                    delay(1500)
                    ShapeCatalog.all.firstOrNull()?.let { state.addShape(it) }
                    delay(300)
                    state.addText()
                    delay(800)
                    state.panel = EditorPanel.Effects
                    delay(600)
                    state.effectBrowserOpen = true
                    delay(900)
                    state.effectBrowserOpen = false
                    state.selectedId = null
                    for (frame in 0 until 30) { state.frame = frame; delay(16) }
                    state.exportOpen = true
                    delay(1200)
                    state.exportOpen = false
                    desk.closeEditor()
                }
                delay(500)
                dir.deleteRecursively()
                exitApplication()
            }
        }
    }
}
