package app.absentmotion.pc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.absentmotion.R
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.Strings
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.PointMode
import app.absentmotion.editor.clearSelection
import app.absentmotion.editor.editPoints
import app.absentmotion.editor.goTo
import app.absentmotion.editor.startVectorDrawing
import app.absentmotion.editor.ungroupSelected
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.addEffect
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.removeEffect
import app.absentmotion.model.withEffect
import app.absentmotion.model.withEffectProperty
import app.absentmotion.ui.editor.DeskDrop
import app.absentmotion.ui.onboarding.ONBOARDING_DONE
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class HistoryNoteTest {
    private val saved = listOf("am.data", "user.home", "am.exports").associateWith { System.getProperty(it) }

    @org.junit.After fun restoreProperties() {
        saved.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
    }

    private val notes = Scene.parse(
        """<scene title="Notes" width="1080" height="1080" fps="30" totalTime="3999">""" +
            """<shape id="1" label="One" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="300,300,0"/></transform><fillColor value="#ffd03030"/></shape>""" +
            """<shape id="2" label="Two" startTime="2000" endTime="3999" fillType="color" s=".rect"><transform><location value="700,700,0"/></transform><fillColor value="#ff30d030"/></shape>""" +
            """<embedScene id="3" label="Group" startTime="0" endTime="3999"><transform><location value="540,540,0"/></transform>""" +
            """<scene width="1080" height="1080" fps="30" totalTime="3999"><shape id="4" label="Inner" startTime="1000" endTime="2999" fillType="color" s=".rect"><transform><location value="0,0,0"/></transform><fillColor value="#ff3030d0"/></shape></scene></embedScene>""" +
            """</scene>""",
    )

    private fun session(name: String, body: ComposeUiTest.(EditorState) -> Unit) {
        System.setProperty("am.data", File("build/$name-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("user.home", File("build/$name-home").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("am.exports", File("build/$name-exports").also { it.deleteRecursively() }.absolutePath)
        DeskAppState().settings.apply { tutorialAsk = 0; onboarding = ONBOARDING_DONE; tutorialStep = null; tutorialProject = null }
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            val id = app.store.create(notes)
            runBlocking { app.openNow(id) }
            waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
            settle(800)
            body(app.editor!!)
            runBlocking { app.closeEditor() }
        }
    }

    private fun ComposeUiTest.settle(ms: Long = 500) { mainClock.advanceTimeBy(ms); waitForIdle() }

    private fun undid(id: Int, vararg args: Any) = Strings.get(R.string.history_undid, Strings.get(id, *args))

    private fun ComposeUiTest.moveAndUndo(editor: EditorState, layer: Long) {
        runOnIdle { editor.edit { it.update(layer) { l -> l.withTransform("location", 410f, 420f, 0f) } }; editor.undo() }
        settle(400)
    }

    @Test fun aSwipeDownDismissesTheNoteAtOnceWithTouchOrMouse() = session("note-swipe") { editor ->
        moveAndUndo(editor, 2)
        val note = onNodeWithText(undid(R.string.history_position))
        note.assertIsDisplayed()
        note.performTouchInput { down(center); moveBy(Offset(0f, 30f)); moveBy(Offset(0f, 40f)); moveBy(Offset(0f, 40f)); up() }
        settle(400)
        note.assertDoesNotExist()
        assertTrue(editor.historyNotes.isEmpty())
        assertEquals(null, editor.selectedId)

        moveAndUndo(editor, 2)
        onNodeWithText(undid(R.string.history_position)).performMouseInput { moveTo(center); press(); moveBy(Offset(0f, 30f)); moveBy(Offset(0f, 30f)); moveBy(Offset(0f, 30f)); release() }
        settle(400)
        onNodeWithText(undid(R.string.history_position)).assertDoesNotExist()
        assertTrue(editor.historyNotes.isEmpty())
        assertNull(editor.selectedId)

        moveAndUndo(editor, 2)
        onNodeWithText(undid(R.string.history_position)).performTouchInput { down(center); repeat(8) { moveBy(Offset(0f, 10f), delayMillis = 150) }; advanceEventTime(300); up() }
        settle(400)
        onNodeWithText(undid(R.string.history_position)).assertDoesNotExist()
        assertNull(editor.selectedId)
    }

    @Test fun aFlickBackUpAndAGrabDuringTheSpringKeepTheNote() = session("note-flick") { editor ->
        moveAndUndo(editor, 2)
        val note = onNodeWithText(undid(R.string.history_position))
        val label = onNodeWithText(undid(R.string.history_position), useUnmergedTree = true)
        val top = label.getBoundsInRoot().top.value
        note.performTouchInput { down(center); repeat(10) { moveBy(Offset(0f, 10f), delayMillis = 100) }; moveBy(Offset(0f, -15f), delayMillis = 10); moveBy(Offset(0f, -15f), delayMillis = 10); up() }
        settle(800)
        note.assertExists()
        assertEquals(top, label.getBoundsInRoot().top.value, 0.5f)

        note.performTouchInput {
            down(center)
            repeat(5) { moveBy(Offset(0f, 8f), delayMillis = 100) }
            advanceEventTime(200)
            up()
            advanceEventTime(48)
            down(center)
            moveBy(Offset(0f, 30f))
            moveBy(Offset(0f, 30f))
        }
        settle(800)
        assertTrue("top ${label.getBoundsInRoot().top.value}", label.getBoundsInRoot().top.value > top + 30f)
        note.performTouchInput { up() }
        settle(400)
        note.assertDoesNotExist()
        assertNull(editor.selectedId)
    }

    @Test fun sidewaysDragsAndLongHoldsDoNotGoToTheChange() = session("note-sideways") { editor ->
        moveAndUndo(editor, 2)
        val note = onNodeWithText(undid(R.string.history_position))
        note.performTouchInput { down(center); moveBy(Offset(-40f, 0f), delayMillis = 50); moveBy(Offset(80f, 0f), delayMillis = 50); up() }
        settle(100)
        note.performMouseInput { moveTo(center); press(); moveBy(Offset(45f, 0f), delayMillis = 50); release() }
        settle(100)
        note.performTouchInput { down(center); advanceEventTime(1500); up() }
        settle(100)
        note.performMouseInput { moveTo(center); press(); advanceEventTime(1500); release() }
        settle(100)
        note.assertExists()
        assertNull(editor.selectedId)
        assertEquals(EditorPanel.Tools, editor.panel)
        note.performClick()
        settle()
        assertEquals(2L, editor.selectedId)
    }

    @Test fun aShortDragSpringsBackAndHoldingKeepsTheNote() = session("note-spring") { editor ->
        moveAndUndo(editor, 2)
        val note = onNodeWithText(undid(R.string.history_position))
        val label = onNodeWithText(undid(R.string.history_position), useUnmergedTree = true)
        val top = label.getBoundsInRoot().top.value
        note.performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, 5f), delayMillis = 60) }
        }
        settle(100)
        assertTrue(label.getBoundsInRoot().top.value > top + 1f)
        settle(3000)
        note.assertExists()
        note.performTouchInput { advanceEventTime(200); up() }
        settle(800)
        note.assertExists()
        assertEquals(top, label.getBoundsInRoot().top.value, 0.5f)
        assertNull(editor.selectedId)
        settle(2600)
        note.assertDoesNotExist()

        runOnIdle { editor.hint(R.string.history_line_up_hint) }
        settle(400)
        val hint = onNodeWithText(Strings.get(R.string.history_line_up_hint))
        onNode(hasText(Strings.get(R.string.history_line_up_hint)) and hasClickAction()).assertDoesNotExist()
        hint.performTouchInput { down(center) }
        settle(3500)
        hint.assertExists()
        hint.performTouchInput { up() }
        settle(400)
        hint.assertExists()
        assertNull(editor.selectedId)
        hint.performTouchInput { down(center); moveBy(Offset(0f, 40f)); moveBy(Offset(0f, 40f)); up() }
        settle(400)
        hint.assertDoesNotExist()
    }

    @Test fun tappingGoesToTheEffectSettingsAndMovesThePlayheadIntoTheLayer() = session("note-effect") { editor ->
        val blur = editor.engine.effects.value.find("absent.blur")!!.instance()
        runOnIdle {
            editor.edit { it.update(1) { l -> l.addEffect(blur) } }
            editor.edit { it.update(1) { l -> l.withEffect(0) { e -> e.withEffectProperty("strength", "float", "50", 0f, 0f..0f) } } }
            editor.selectedId = 2
            editor.panel = EditorPanel.BorderShadow
            editor.frame = 75
            editor.undo()
        }
        settle(400)
        val note = onNodeWithText(undid(R.string.library_text_value, "Blur"))
        assertEquals(Strings.get(R.string.history_go_to_change), note.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        note.performMouseInput { click() }
        settle()
        assertEquals(1L, editor.selectedId)
        assertEquals(EditorPanel.Effects, editor.panel)
        assertEquals(0, editor.effectIndex)
        assertTrue(editor.frame in 0..29)
        note.assertDoesNotExist()

        runOnIdle { editor.edit { it.update(1) { l -> l.addEffect(blur) } }; editor.clearSelection(); editor.undo() }
        settle(400)
        onNodeWithText(undid(R.string.history_add_effect, "Blur")).performClick()
        settle()
        assertNull(editor.selectedId)
        assertEquals(EditorPanel.Tools, editor.panel)
    }

    @Test fun effectColorsOpenTheFullPickerAndCanSampleThePreview() = session("effect-color") { editor ->
        val glow = editor.engine.effects.value.find("absent.softglow")!!.instance()
        runOnIdle {
            editor.edit { it.update(1) { layer -> layer.addEffect(glow) } }
            editor.selectedId = 1
            editor.effectIndex = 0
            editor.panel = EditorPanel.Effects
        }
        settle()
        val picker = onNodeWithContentDescription(Strings.get(R.string.a11y_full_color_picker))
        picker.performScrollTo().performMouseInput { click() }
        settle()
        onNodeWithContentDescription(Strings.get(R.string.a11y_color_wheel)).assertIsDisplayed()
        runOnIdle {
            editor.fullColor!!.apply(0x80147258.toInt(), false)
            assertEquals("#80147258", editor.selected!!.effects.single().effectProperty("color")!!["value"]!!.lowercase())
            editor.fullColor = null
        }
        settle()
        onNodeWithContentDescription(Strings.get(R.string.palettes)).performClick()
        settle()
        onNodeWithText(Strings.get(R.string.a11y_eyedropper)).performClick()
        settle()
        runOnIdle {
            editor.eyedropper!!(0xff236789.toInt())
            assertEquals("#80236789", editor.selected!!.effects.single().effectProperty("color")!!["value"]!!.lowercase())
            editor.panel = EditorPanel.ColorFill
        }
        settle()
        picker.performMouseInput { click() }
        settle()
        onNodeWithContentDescription(Strings.get(R.string.a11y_color_wheel)).assertIsDisplayed()
        runOnIdle {
            editor.fullColor!!.apply(0xff137ac4.toInt(), false)
            assertEquals(0xff137ac4.toInt(), editor.selected!!.fillColor)
            editor.fullColor = null
        }
    }

    @Test fun tappingGoesToMoveAndTransformAndIntoAGroup() = session("note-transform") { editor ->
        runOnIdle { editor.selectedId = 1; editor.panel = EditorPanel.ColorFill; editor.frame = 0 }
        moveAndUndo(editor, 2)
        onNodeWithText(undid(R.string.history_position)).performClick()
        settle()
        assertEquals(2L, editor.selectedId)
        assertEquals(EditorPanel.MoveTransform, editor.panel)
        assertTrue("frame ${editor.frame}", editor.frame in 60..119)

        runOnIdle {
            editor.enterGroup(3)
            editor.edit { it.update(4) { l -> l.withTransform("location", 50f, 60f, 0f) } }
            editor.leaveGroups()
            editor.clearSelection()
            editor.frame = 100
            editor.undo()
        }
        settle(400)
        assertEquals(emptyList<Long>(), editor.groupPath)
        onNodeWithText(undid(R.string.history_position)).performClick()
        settle()
        assertEquals(listOf(3L), editor.groupPath)
        assertEquals(4L, editor.selectedId)
        assertEquals(EditorPanel.MoveTransform, editor.panel)
        assertTrue("frame ${editor.frame}", editor.frame in 30..89)
    }

    @Test fun aMovedEffectOrAnUngroupedLayerIsStillFound() = session("note-moved") { editor ->
        val blur = editor.engine.effects.value.find("absent.blur")!!.instance()
        val glow = editor.engine.effects.value.find("absent.glow")!!.instance()
        runOnIdle {
            editor.edit { it.update(1) { l -> l.addEffect(glow).addEffect(blur) } }
            editor.edit { it.update(1) { l -> l.withEffect(1) { e -> e.withEffectProperty("strength", "float", "50", 0f, 0f..0f) } } }
            editor.undo()
            editor.edit { it.update(1) { l -> l.removeEffect(0) } }
        }
        settle(400)
        onNodeWithText(undid(R.string.library_text_value, "Blur")).performClick()
        settle()
        assertEquals(1L, editor.selectedId)
        assertEquals(EditorPanel.Effects, editor.panel)
        assertEquals(0, editor.effectIndex)

        runOnIdle {
            editor.enterGroup(3)
            editor.edit { it.update(4) { l -> l.withTransform("location", 50f, 60f, 0f) } }
            editor.leaveGroups()
            editor.undo()
            editor.selectedId = 3
            editor.ungroupSelected()
        }
        settle(400)
        assertNotNull(editor.scene.layer(4))
        onNodeWithText(undid(R.string.history_position)).performClick()
        settle()
        assertEquals(emptyList<Long>(), editor.groupPath)
        assertEquals(4L, editor.selectedId)
        assertEquals(EditorPanel.MoveTransform, editor.panel)
    }

    @Test fun inEditPointsATapLeavesThemAndAnEmptyPenShapeIsNeverDropped() = session("note-points") { editor ->
        runOnIdle { editor.selectedId = 1; editor.editPoints() }
        settle()
        assertTrue(editor.drawing is PointMode)
        moveAndUndo(editor, 2)
        onNodeWithText(undid(R.string.history_position)).performClick()
        settle()
        assertNull(editor.drawing)
        assertEquals(2L, editor.selectedId)
        assertEquals(EditorPanel.MoveTransform, editor.panel)

        moveAndUndo(editor, 1)
        runOnIdle { editor.startVectorDrawing() }
        settle(300)
        val pen = editor.selectedId!!
        val project = editor.project
        onNodeWithText(undid(R.string.history_position)).performClick()
        settle()
        onNodeWithText(undid(R.string.history_position)).assertDoesNotExist()
        assertSame(project.node, editor.project.node)
        assertEquals(pen, (editor.drawing as PointMode).layerId)
        assertEquals(pen, editor.selectedId)
    }

    @Test fun tappingOpensAClosedDesktopInspector() = session("note-inspector") { editor ->
        val rail = Strings.get(R.string.desk_inspector)
        runOnIdle { editor.selectedId = 1 }
        settle()
        runOnIdle { DeskDrop.panelKey!!(false) }
        settle()
        onAllNodesWithContentDescription(rail).assertCountEquals(1)
        moveAndUndo(editor, 1)
        onNodeWithText(undid(R.string.history_position)).performClick()
        settle()
        onAllNodesWithContentDescription(rail).assertCountEquals(0)
        assertEquals(1L, editor.selectedId)
        assertEquals(EditorPanel.MoveTransform, editor.panel)

        moveAndUndo(editor, 2)
        runOnIdle {
            val spot = editor.historyNotes.last().spot!!
            editor.clearSelection()
            editor.addOpen = true
            assertTrue(editor.goTo(spot))
            assertFalse(editor.addOpen)
            assertEquals(2L, editor.selectedId)
        }
    }

    @Test fun aGoneLayerLeavesSelectionPanelsAndPlayheadAlone() = session("note-gone") { editor ->
        val gone = Layer(XmlNode("shape", linkedMapOf("id" to "9", "label" to "Gone", "startTime" to "0", "endTime" to "999", "fillType" to "color", "s" to ".rect")))
        runOnIdle {
            editor.edit { it.add(gone) }
            editor.undo()
            editor.redo()
            editor.edit { it.remove(9) }
            editor.selectedId = 1
            editor.panel = EditorPanel.BorderShadow
            editor.frame = 10
        }
        settle(400)
        val redid = Strings.get(R.string.history_redid, Strings.get(R.string.history_add_named, "Gone"))
        onNodeWithText(redid).performClick()
        settle()
        onNodeWithText(redid).assertDoesNotExist()
        val undidAdd = undid(R.string.history_add_named, "Gone")
        onNodeWithText(undidAdd).performClick()
        settle()
        onNodeWithText(undidAdd).assertDoesNotExist()
        assertEquals(1L, editor.selectedId)
        assertEquals(EditorPanel.BorderShadow, editor.panel)
        assertEquals(10, editor.frame)
        assertEquals(emptyList<Long>(), editor.groupPath)
        assertTrue(editor.historyNotes.isEmpty())
    }

    @Test fun screenReadersCanCloseTheNote() = session("note-a11y") { editor ->
        moveAndUndo(editor, 2)
        val note = onNodeWithText(undid(R.string.history_position))
        val close = note.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == Strings.get(R.string.action_close) }
        runOnIdle { close.action() }
        settle(200)
        note.assertDoesNotExist()
        assertNull(editor.selectedId)

        runOnIdle { editor.hint(R.string.history_line_up_hint) }
        settle(400)
        val hint = onNodeWithText(Strings.get(R.string.history_line_up_hint))
        val closeHint = hint.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == Strings.get(R.string.action_close) }
        runOnIdle { closeHint.action() }
        settle(200)
        hint.assertDoesNotExist()
    }
}
