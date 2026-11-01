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

    @Test fun sharedSelectionAndUndoableOperations() {
        val state = project()
        val ids = state.scene.layers.map { it.id }
        state.selectMany(ids.take(2))
        assertEquals(ids[1], state.selected)
        state.updateSelection { it.withRotation(42f) }
        assertTrue(ids.take(2).all { state.scene.layer(it)!!.rotation == 42f })
        state.undo()
        assertTrue(ids.take(2).all { state.scene.layer(it)!!.rotation != 42f })
        state.redo()
        state.duplicate()
        assertEquals(2, state.selectedIds.size)
        assertEquals(ids.size + 2, state.scene.layers.size)
        state.delete()
        assertEquals(ids.size, state.scene.layers.size)
        state.undo()
        assertEquals(ids.size + 2, state.scene.layers.size)
    }

    @Test fun pasteCopiesAtPlayheadAndRoundTrips() {
        val state = project()
        val originals = state.scene.layers.take(2)
        val source = Scene(state.scene.node.copy(children = originals.map { it.node }))
        val parsed = Scene.parse(source.toXml())
        state.time = 2000
        state.paste(parsed.layers, parsed.media)
        val copies = state.selectedIds.map { state.scene.layer(it)!! }
        assertEquals(2000, copies.minOf { it.startTime })
        assertTrue(copies.none { it.id in originals.map { original -> original.id } })
        val changed = state.scene
        assertEquals(changed.layers.map { it.node }, Scene.parse(changed.toXml()).layers.map { it.node })
        state.undo()
        assertEquals(0, state.selectedIds.size)
        state.redo()
        assertEquals(changed, state.scene)
    }
}
