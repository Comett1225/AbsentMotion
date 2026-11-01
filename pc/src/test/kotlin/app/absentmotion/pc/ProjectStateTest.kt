package app.absentmotion.pc

import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.*
import app.absentmotion.pc.project.ProjectState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProjectStateTest {
    private fun project(): ProjectState = ProjectState().apply {
        open(Scene.parse(File("../testdata/scenes/shapes.xml").readText()), null)
        selected = 1001
    }

    @Test fun transformUndoRedoAndRoundTrip() {
        val state = project()
        val original = state.scene
        state.update { it.withTransform("location", 300f, 400f, 0f) }
        assertTrue(state.dirty)
        assertEquals(300f, state.scene.layer(1001)!!.location[0], 0.01f)
        val changed = state.scene
        state.undo()
        assertEquals(original, state.scene)
        assertFalse(state.dirty)
        state.redo()
        assertEquals(changed, state.scene)
        val restored = Scene.parse(state.scene.toXml())
        assertEquals(changed.node.copy(attrs = changed.node.attrs - "modifiedTime"), restored.node.copy(attrs = restored.node.attrs - "modifiedTime"))
    }

    @Test fun splitTrimMoveAndUndo() {
        val state = project()
        val original = state.scene
        state.time = 1000
        state.split()
        assertEquals(original.layers.size + 1, state.scene.layers.size)
        val split = state.scene
        state.undo()
        assertEquals(original, state.scene)
        state.redo()
        assertEquals(split, state.scene)
        state.edit { scene -> scene.update(1001) { it.trimmedTo(200, it.endTime) } }
        assertTrue(state.scene.layer(1001)!!.startTime >= 200)
        state.undo()
        assertEquals(split, state.scene)
    }

    @Test fun effectPresetAndKeyframe() {
        val state = project()
        val effects = File("../app/src/main/assets/effects")
        val def = effects.listFiles()!!.filter { it.extension == "json" && it.name != "index.json" }.asSequence().mapNotNull { runCatching { EffectDefinition.parse(JSONObject(it.readText())) }.getOrNull() }.first { it.presets.isNotEmpty() && it.params.isNotEmpty() }
        state.update { it.addEffect(def.instance(def.presets.first(), it.endTime - it.startTime)) }
        assertEquals(def.id, state.layer!!.effects.first()["id"])
        val before = state.scene
        val param = def.params.first()
        val ref = PropRef.effect(0, param.id)
        state.time = 500
        state.update { it.withKeyframeToggled(ref, param.default) }
        assertTrue(state.scene.layerAt(1001, 500)!!.onKeyframe(ref))
        state.undo()
        assertEquals(before, state.scene)
        state.redo()
        assertTrue(state.scene.layerAt(1001, 500)!!.onKeyframe(ref))
    }

    @Test fun historyIsCappedAndNewEditClearsRedo() {
        val state = project()
        repeat(210) { index -> state.update { it.withLabel("Layer $index") } }
        repeat(200) { state.undo() }
        assertFalse(state.canUndo)
        assertTrue(state.canRedo)
        state.update { it.withLabel("New") }
        assertFalse(state.canRedo)
    }
}
