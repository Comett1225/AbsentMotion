package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.model.Scene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class FirstProjectTimingTest {
    @Test fun firstAndSecondProject() {
        assumeTrue("set AM_TIMING=1 to run", System.getenv("AM_TIMING") != null)
        val idleMs = System.getenv("AM_TIMING_IDLE_MS")?.toLongOrNull() ?: 3000L
        val preload = System.getenv("AM_TIMING_PRELOAD") ?: "on"
        System.setProperty("am.preload", preload)
        val data = File("build/first-project-data").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", data.absolutePath)
        System.setProperty("user.home", File("build/first-project-home").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        val report = StringBuilder("preload $preload, idle on the home screen before the first click: $idleMs ms\n")
        val launched = System.nanoTime()
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            waitForIdle()
            report.append("home screen ready after ${(System.nanoTime() - launched) / 1_000_000} ms\n")
            if (idleMs > 0) { mainClock.advanceTimeBy(idleMs); Thread.sleep(idleMs); waitForIdle() }
            for (round in 1..2) {
                val started = System.nanoTime()
                val id = runBlocking { withContext(Dispatchers.IO) { app.store.create(Scene.create("Timing $round", 1080, 1920, 30, 0xff000000.toInt())) } }
                val before = if (round == 1) 0L else app.engine.renders
                checkNotNull(runBlocking { app.openNow(id) })
                val opened = System.nanoTime()
                waitUntil(timeoutMillis = 60_000) { app.engine.renders > before }
                val shown = System.nanoTime()
                report.append("project $round: editor state after ${(opened - started) / 1_000_000} ms, first editor frame after ${(shown - started) / 1_000_000} ms\n")
                runBlocking { app.closeEditor() }
                waitForIdle()
            }
        }
        print(report)
        File("build/first-project-timing.txt").writeText(report.toString())
    }
}
