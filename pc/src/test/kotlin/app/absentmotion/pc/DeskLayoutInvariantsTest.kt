package app.absentmotion.pc

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.addCamera
import app.absentmotion.editor.addLight
import app.absentmotion.editor.addModel
import app.absentmotion.editor.addShape
import app.absentmotion.editor.addText
import app.absentmotion.editor.openCrop
import app.absentmotion.editor.requestPoints
import app.absentmotion.model.Scene
import app.absentmotion.model.withContent
import app.absentmotion.model.ShapeCatalog
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DeskLayoutInvariantsTest {
    private class Case(val name: String, val setup: EditorState.() -> Unit)

    private fun EditorState.pick(label: String) { selectedId = scene.layers.first { it.label == label }.id }

    private fun EditorState.shape(outline: String) { selectedId = null; addShape(ShapeCatalog.all.first { it.outline == outline }) }

    private val cube = File("build/layout-model/cube.obj").also {
        it.parentFile.mkdirs()
        it.writeText("v -1 -1 -1\nv 1 -1 -1\nv 1 1 -1\nv -1 1 -1\nv -1 -1 1\nv 1 -1 1\nv 1 1 1\nv -1 1 1\nf 1 2 3 4\nf 5 6 7 8\nf 1 2 6 5\nf 2 3 7 6\nf 3 4 8 7\nf 4 1 5 8\n")
    }.absoluteFile

    private val cases = listOf(
        Case("project") { selectedId = null; panel = EditorPanel.Tools },
        Case("tools") { pick("Shadow"); panel = EditorPanel.Tools },
        Case("transform-advanced") { settings.basicTransform = false; pick("Shadow"); panel = EditorPanel.MoveTransform },
        Case("transform-basic") { settings.basicTransform = true; pick("Shadow"); panel = EditorPanel.MoveTransform },
        Case("fill") { pick("Linear gradient"); panel = EditorPanel.ColorFill },
        Case("shape-star") { pick("Shadow"); panel = EditorPanel.EditShape },
        Case("shape-polygon") { pick("Border and shadow"); panel = EditorPanel.EditShape },
        Case("border") { pick("Border and shadow"); panel = EditorPanel.BorderShadow },
        Case("blending") { pick("Blend multiply"); panel = EditorPanel.Blending },
        Case("crop") { pick("Backing"); openCrop() },
        Case("speed") { pick("Backing"); panel = EditorPanel.Speed },
        Case("effects") { pick("Backing"); panel = EditorPanel.Effects },
        Case("multi") { selectedId = null; panel = EditorPanel.Tools; MultiSelect.ids = scene.layers.take(3).map { it.id }.toSet(); MultiSelect.active = true },
        Case("shape-heart") { MultiSelect.clear(); shape("heart"); panel = EditorPanel.EditShape },
        Case("shape-gear") { shape("gear"); panel = EditorPanel.EditShape },
        Case("shape-phone") { shape("phone"); panel = EditorPanel.EditShape },
        Case("points") { pick("Backing"); panel = EditorPanel.Tools; requestPoints(enter = true) },
        Case("text") { selectedId = null; addText(); editSelected { it.withContent("Layout check") }; panel = EditorPanel.EditText },
        Case("text-tools") { panel = EditorPanel.Tools },
        Case("camera") { selectedId = null; addCamera(); panel = EditorPanel.Camera },
        Case("light") { selectedId = null; addLight(); panel = EditorPanel.Light },
        Case("model") { selectedId = null; addModel(cube.toURI().toString(), "cube.obj"); panel = EditorPanel.Model },
    )

    private fun SemanticsNode.label(): String? {
        val c = config
        return c.getOrNull(SemanticsProperties.Text)?.joinToString(" ")?.takeIf { it.isNotBlank() }
            ?: c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.takeIf { it.isNotBlank() }
            ?: c.getOrNull(SemanticsProperties.EditableText)?.text?.let { "edit:$it" }
    }

    private fun SemanticsNode.clickable() = config.contains(SemanticsActions.OnClick)

    private fun SemanticsNode.interesting() = clickable() || label() != null

    private fun SemanticsNode.hidden() = config.contains(SemanticsProperties.InvisibleToUser) || config.contains(SemanticsProperties.HideFromAccessibility)

    private fun SemanticsNode.scrolled(): Boolean {
        var p = parent
        while (p != null) {
            if (p.config.contains(SemanticsProperties.VerticalScrollAxisRange) || p.config.contains(SemanticsProperties.HorizontalScrollAxisRange)) return true
            p = p.parent
        }
        return false
    }

    private fun SemanticsNode.full() = Rect(positionInWindow.x, positionInWindow.y, positionInWindow.x + size.width, positionInWindow.y + size.height)

    private fun SemanticsNode.layoutResult(): TextLayoutResult? {
        val get = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        val out = ArrayList<TextLayoutResult>()
        return if (get(out)) out.firstOrNull() else null
    }

    private fun box(r: Rect) = "[%.0f,%.0f %.0fx%.0f]".format(r.left, r.top, r.width, r.height)

    private fun check(root: SemanticsNode, where: String, w: Int, h: Int, rows: Set<String>, out: MutableSet<String>) {
        fun walk(n: SemanticsNode) {
            if (n.hidden()) return
            val kids = n.children.filter { !it.hidden() }
            if (n.interesting() && !n.isRoot) {
                val name = n.label() ?: "(click)"
                val full = n.full()
                val shown = n.boundsInWindow
                val scrolled = name in rows || n.scrolled()
                when {
                    n.size.width == 0 || n.size.height == 0 -> out.add("$where ZERO '$name' ${box(full)}")
                    !scrolled && (full.left < -1 || full.top < -1 || full.right > w + 1 || full.bottom > h + 1) -> out.add("$where OUTSIDE '$name' ${box(full)}")
                    !scrolled && shown.width * shown.height < full.width * full.height * 0.9f -> out.add("$where CLIPPED '$name' full=${box(full)} shown=${box(shown)}")
                }
                n.layoutResult()?.let { t ->
                    val ellipsized = (0 until t.lineCount).any { t.isLineEllipsized(it) }
                    val wide = (0 until t.lineCount).maxOf { t.getLineRight(it) - t.getLineLeft(it) }
                    val cut = ellipsized || t.multiParagraph.didExceedMaxLines || wide > n.size.width * 1.05f + 2 || t.multiParagraph.height > n.size.height * 1.05f + 2
                    if (cut && !(name == "Search photos and videos")) out.add("$where TEXT-CUT '$name' ${box(full)} text=%.0fx%.0f lines=${t.lineCount} ellipsis=$ellipsized more=${t.multiParagraph.didExceedMaxLines} max=${t.layoutInput.maxLines}".format(wide, t.multiParagraph.height))
                }
            }
            val peers = kids.filter { it.interesting() && it.size.width > 0 && it.size.height > 0 && it.boundsInWindow.width > 0 && it.boundsInWindow.height > 0 }
            for (i in peers.indices) for (j in i + 1 until peers.size) {
                val a = peers[i]
                val b = peers[j]
                if (!a.clickable() || !b.clickable()) continue
                val x = a.boundsInWindow.intersect(b.boundsInWindow)
                if (x.width > 4 && x.height > 4) out.add("$where OVERLAP '${a.label() ?: "(click)"}' ${box(a.boundsInWindow)} with '${b.label() ?: "(click)"}' ${box(b.boundsInWindow)}")
            }
            kids.forEach(::walk)
        }
        walk(root)
    }

    private fun ComposeUiTest.shot(file: File) {
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
    }

    @Test fun everyPanelKeepsItsControlsVisible() {
        val data = File("build/layout-invariants-data").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", data.absolutePath)
        val shots = File("build/ui-shots/layout").also { it.mkdirs() }
        val bad = LinkedHashSet<String>()
        for ((w, h) in listOf(1024 to 700, 1280 to 800, 1728 to 1080, 2560 to 1440)) {
            runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                val app = DeskAppState()
                setContent { DeskApp(app) }
                val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
                runBlocking { app.openNow(id) }
                waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
                mainClock.advanceTimeBy(1000)
                val editor = app.editor!!
                for (case in cases) {
                    val ok = runCatching { runOnIdle { case.setup(editor) } }
                    if (ok.isFailure) { bad.add("${case.name} ${w}x$h SETUP ${ok.exceptionOrNull()}"); continue }
                    mainClock.advanceTimeBy(800)
                    waitForIdle()
                    val where = "${case.name} ${w}x$h"
                    for (root in onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()) check(root, where, w, h, editor.scene.layers.map { it.label }.toSet(), bad)
                    shot(shots.resolve("${w}x$h-${case.name}.png"))
                }
                runOnIdle { MultiSelect.clear() }
                runBlocking { app.closeEditor() }
            }
        }
        bad.forEach { println("LAYOUT $it") }
        assertTrue("${bad.size} layout problems:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
