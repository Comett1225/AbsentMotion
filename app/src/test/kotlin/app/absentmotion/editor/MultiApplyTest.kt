package app.absentmotion.editor

import app.absentmotion.sceneOf
import app.absentmotion.shapeLayer
import app.absentmotion.model.MotionSlot
import app.absentmotion.model.Scene
import app.absentmotion.model.TransitionKind
import app.absentmotion.model.XmlNode
import app.absentmotion.model.motion
import org.junit.Assert.*
import org.junit.Test

class MultiApplyTest {
    private fun clips(): Scene = sceneOf(
        shapeLayer("text", id = 1, start = 0, end = 999).node,
        shapeLayer("text", id = 2, start = 1000, end = 1999).node,
        shapeLayer("text", id = 3, start = 2000, end = 2999).node,
        shapeLayer("audio", id = 4, start = 3000, end = 3999).node,
        shapeLayer("text", id = 5, start = 5000, end = 5999).node,
    )

    private fun undoOnce(before: Scene, after: Scene) {
        var current = before
        val history = EditHistory({ current }) { scene, _ -> current = scene }
        history.edit(null, after)
        assertEquals(before, history.undo())
        assertFalse(history.canUndo)
        assertEquals(after, history.redo())
    }

    @Test fun animationsSkipNonvisualAndApplyEachSlotInOneEdit() {
        val before = clips()
        for ((slot, name) in listOf(MotionSlot.In to "fade", MotionSlot.Out to "fade", MotionSlot.Loop to "pulse")) {
            val after = before.animateLayers(setOf(1, 2, 4, 99), slot, name, 500)
            assertEquals(name, after.layer(1)!!.motion(slot)!!.id)
            assertEquals(name, after.layer(2)!!.motion(slot)!!.id)
            assertEquals(before.layer(4), after.layer(4))
            assertEquals(before.layer(3), after.layer(3))
            undoOnce(before, after)
        }
    }

    @Test fun presetReachesOnlySelectedLayersInOneEdit() {
        val before = clips()
        val preset = shapeLayer("text", children = listOf(XmlNode("shadow")))
        val after = before.applyPresetToLayers(setOf(1, 3, 99), preset, "saved")
        assertNotNull(after.layer(1)!!.node.child("shadow"))
        assertNotNull(after.layer(3)!!.node.child("shadow"))
        assertEquals(before.layer(2), after.layer(2))
        undoOnce(before, after)
    }

    @Test fun cutsUseAdjacentSelectedClipsAndIgnoreGapsAndAudio() {
        val before = clips()
        val cuts = before.selectedCuts(setOf(1, 2, 3, 4, 5))
        assertEquals(listOf(1L to 2L, 2L to 3L), cuts)
        assertTrue(before.selectedCuts(setOf(1, 3, 5)).isEmpty())
        val after = before.transitionCuts(cuts, TransitionKind.DipBlack, 500)
        assertEquals(2L, after.layer(1)!!.motion(MotionSlot.Out)!!.partner)
        assertEquals(1L, after.layer(2)!!.motion(MotionSlot.In)!!.partner)
        assertEquals(3L, after.layer(2)!!.motion(MotionSlot.Out)!!.partner)
        assertEquals(2L, after.layer(3)!!.motion(MotionSlot.In)!!.partner)
        assertEquals(before.layer(4), after.layer(4))
        undoOnce(before, after)
    }

    @Test fun overlappingTransitionsCanBeReplacedAcrossAllCuts() {
        val before = clips()
        val cuts = before.selectedCuts(setOf(1, 2, 3))
        val first = before.transitionCuts(cuts, TransitionKind.CrossDissolve, 500)
        assertEquals(2L, first.layer(1)!!.motion(MotionSlot.Out)!!.partner)
        assertEquals(3L, first.layer(2)!!.motion(MotionSlot.Out)!!.partner)
        val next = first.transitionCuts(cuts, TransitionKind.DipBlack, 300)
        assertEquals(TransitionKind.DipBlack.id, next.layer(1)!!.motion(MotionSlot.Out)!!.id)
        assertEquals(TransitionKind.DipBlack.id, next.layer(2)!!.motion(MotionSlot.In)!!.id)
        assertEquals(TransitionKind.DipBlack.id, next.layer(2)!!.motion(MotionSlot.Out)!!.id)
        assertEquals(TransitionKind.DipBlack.id, next.layer(3)!!.motion(MotionSlot.In)!!.id)
        undoOnce(first, next)
    }
}
