package app.absentmotion.editor

import app.absentmotion.library.ProjectSaver
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.Recovery
import app.absentmotion.model.Scene
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EditHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val start = Scene.create("start", 400, 400, 30, 0)
    private var scene = start
    private val shown = ArrayList<Pair<String, Boolean>>()
    private val history = EditHistory({ scene }) { next, save -> scene = next; shown += next.title to save }

    private fun edit(key: Any?, title: String) = history.edit(key, scene.withTitle(title))

    @Test fun plainEditsAreSavedAndEachOneIsAnUndoStep() {
        edit(null, "a")
        edit(null, "b")
        assertEquals(listOf("a" to true, "b" to true), shown)
        assertEquals("a", history.undo()!!.title)
        assertEquals("start", history.undo()!!.title)
        assertFalse(history.canUndo)
        assertEquals(null, history.undo())
        assertEquals("a", history.redo()!!.title)
        assertEquals("b", history.redo()!!.title)
        assertFalse(history.canRedo)
        assertTrue(shown.drop(2).all { it.second })
    }

    @Test fun keyedEditsMergeIntoOneStepAndOnlyDraftUntilTheGestureEnds() {
        edit("drag", "a")
        edit("drag", "b")
        edit("drag", "c")
        assertEquals(listOf("a" to false, "b" to false, "c" to false), shown)
        assertTrue(history.endGesture())
        assertFalse(history.endGesture())
        assertEquals("start", history.undo()!!.title)
        assertFalse(history.canUndo)
    }

    @Test fun aNewKeyOrAPlainEditStartsANewStep() {
        edit("drag", "a")
        edit("pinch", "b")
        edit(null, "c")
        edit("pinch", "d")
        assertEquals(listOf("c", "b", "a", "start"), List(4) { history.undo()!!.title })
    }

    @Test fun anEditThatChangesNothingIsNotRecorded() {
        history.edit(null, scene)
        history.edit("drag", Scene(scene.node))
        assertTrue(shown.isEmpty())
        assertFalse(history.canUndo)
    }

    @Test fun anyEditClearsRedo() {
        edit(null, "a")
        history.undo()
        assertTrue(history.canRedo)
        edit("drag", "b")
        assertFalse(history.canRedo)
    }

    @Test fun cancellingAGestureRestoresTheSceneAndTheRedoListAsADraft() {
        edit(null, "a")
        history.undo()
        shown.clear()
        edit("drag", "b")
        edit("drag", "c")
        history.cancelGesture("other")
        assertEquals("c", scene.title)
        history.cancelGesture("drag")
        assertEquals("start", scene.title)
        assertEquals("start" to false, shown.last())
        assertFalse(history.canUndo)
        assertEquals("a", history.redo()!!.title)
        assertFalse(history.endGesture())
    }

    @Test fun undoAndRedoCloseAnOpenGesture() {
        edit("drag", "a")
        history.undo()
        edit("drag", "b")
        assertEquals("start", history.undo()!!.title)
        assertFalse(history.endGesture())
    }

    @Test fun theOldestStepsAreDroppedPastTheLimit() {
        repeat(EditHistory.MAX_UNDO + 5) { edit(null, "e$it") }
        var steps = 0
        while (history.undo() != null) steps++
        assertEquals(EditHistory.MAX_UNDO, steps)
        assertEquals("e4", scene.title)
    }

    @Test fun cancellingAGestureAtTheLimitKeepsTheOldestStep() {
        repeat(EditHistory.MAX_UNDO) { edit(null, "e$it") }
        edit("drag", "dragging")
        assertTrue(history.gesturing)
        history.cancelGesture("drag")
        assertFalse(history.gesturing)
        assertEquals("e${EditHistory.MAX_UNDO - 1}", scene.title)
        var steps = 0
        while (history.undo() != null) steps++
        assertEquals(EditHistory.MAX_UNDO, steps)
        assertEquals("start", scene.title)
    }

    @Test fun theMultiSelectionCountsOnlyRealChanges() {
        MultiSelect.clear()
        val start = MultiSelect.revision
        MultiSelect.ids = setOf(1L, 2L)
        MultiSelect.ids = setOf(2L, 1L)
        assertEquals(start + 1, MultiSelect.revision)
        MultiSelect.clear()
        MultiSelect.ids = setOf(1L, 2L)
        assertEquals(start + 3, MultiSelect.revision)
        MultiSelect.clear()
    }

    @Test fun amendingReplacesTheCurrentSceneWithoutANewStep() {
        edit(null, "a")
        history.amend(scene.withTitle("a2"))
        assertEquals("a2" to true, shown.last())
        assertEquals("start", history.undo()!!.title)
        assertFalse(history.canUndo)
        assertEquals("a2", history.redo()!!.title)
        val count = shown.size
        history.amend(scene)
        assertEquals(count, shown.size)
    }

    @Test fun forgettingAnUntouchedEditLeavesNoUndoStep() {
        edit(null, "a")
        val added = scene
        edit(null, "b")
        assertFalse(history.forget(added))
        history.undo()
        assertSame(added.node, scene.node)
        assertTrue(history.forget(added))
        assertEquals("start" to true, shown.last())
        assertEquals("start", scene.title)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertFalse(history.forget(null))
    }

    @Test fun changingTheFrameRateKeepsThePlayheadTime() {
        val long = Scene(start.node.with("totalTime", "10000"))
        val faster = Scene(long.node.with("fps", "60"))
        assertEquals(120, keptFrame(long, faster, 60))
        assertEquals(60, keptFrame(faster, long, 120))
        assertEquals(60, keptFrame(long, long, 60))
        assertEquals(long.lastFrame, keptFrame(long, long, 100_000))
        assertEquals(0, keptFrame(long, long, -3))
    }

    private val executor = Executors.newSingleThreadScheduledExecutor()

    @After fun stop() { executor.shutdownNow() }

    @Test fun keyedEditsReachOnlyTheRecoveryDraftUntilTheGestureEnds() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val id = store.create(start)
        val recovery = Recovery(root.resolve("recovery"))
        val saver = ProjectSaver(id, store, recovery, failed = { throw AssertionError(it) }, executor = executor)
        val wired = EditHistory({ scene }) { next, save -> scene = next; if (save) saver.commit(next.toXml()) else saver.draft(next.toXml()) }
        fun endGesture() { if (wired.endGesture()) saver.commit(scene.toXml()); saver.flush().get(5, TimeUnit.SECONDS) }
        wired.edit("drag", scene.withTitle("dragging"))
        wired.edit("drag", scene.withTitle("dragged"))
        saver.flush().get(5, TimeUnit.SECONDS)
        executor.submit { }.get(5, TimeUnit.SECONDS)
        assertEquals("start", store.load(id)!!.title)
        assertEquals("dragged", Scene.parse(recovery.file(id).readText()).title)
        endGesture()
        assertEquals("dragged", store.load(id)!!.title)
        assertFalse(recovery.file(id).exists())
        wired.edit(null, scene.withTitle("plain"))
        saver.flush().get(5, TimeUnit.SECONDS)
        assertEquals("plain", store.load(id)!!.title)
    }
}
