package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.model.Scene
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DeskTransformTabsTest {
    @Test fun everyTransformTabIsShownOnDesktop() {
        val bad = ArrayList<String>()
        val data = File("build/transform-tabs-data").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", data.absolutePath)
        for ((width, height) in listOf(1024 to 700, 1280 to 800, 1728 to 1080)) {
            for (basic in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                    val app = DeskAppState()
                    setContent { DeskApp(app) }
                    val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
                    runBlocking { app.openNow(id) }
                    waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
                    mainClock.advanceTimeBy(1000)
                    val editor = app.editor!!
                    runOnIdle { editor.settings.basicTransform = basic; editor.selectedId = editor.scene.layers.last().id; editor.panel = EditorPanel.MoveTransform }
                    mainClock.advanceTimeBy(600)
                    val names = if (basic) listOf("Move", "Rotate", "Size", "Skew") else listOf("Move", "Rotate", "Size", "Tilt", "Skew", "Pivot")
                    for (name in names) {
                        val all = onAllNodesWithText(name, substring = false)
                        val boxes = (0 until all.fetchSemanticsNodes().size).map { all[it].getBoundsInRoot() }
                        val box = boxes.maxByOrNull { it.bottom.value - it.top.value } ?: error("no $name")
                        println("TAB ${width}x$height basic=$basic $name all=$boxes")
                        if (!((box.bottom.value - box.top.value) >= 10f && box.bottom.value <= height && box.right.value <= width)) bad.add("$name ${width}x$height basic=$basic $box")
                    }
                }
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
