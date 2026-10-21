package app.absentmotion.editor

import app.absentmotion.R
import app.absentmotion.audio.AudioFx
import app.absentmotion.audio.FxKind
import app.absentmotion.audio.withAudioEffects
import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Test

class EditDescriptionTest {
    private val base = Scene.parse(
        """<scene width="100" height="100" fps="30"><shape id="1" label="Box" startTime="0" endTime="999" s=".rect"><transform><location value="10,10,0"/></transform></shape>""" +
            """<shape id="2" startTime="0" endTime="999" s=".rect"/><audio id="3" src="a" startTime="0" endTime="999"/></scene>""",
    )

    private fun say(after: Scene, before: Scene = base) = describeEdit(before, after).id

    @Test fun anEditIsNamedAfterWhatChanged() {
        assertEquals(R.string.history_position, say(base.update(1) { it.withTransform("location", 20f, 10f, 0f) }))
        assertEquals(R.string.history_opacity, say(base.update(1) { it.withTransform("opacity", 0.5f) }))
        assertEquals(R.string.history_size, say(base.update(1) { it.withTransform("scale", 2f, 2f) }))
        assertEquals(R.string.history_rotation, say(base.update(1) { it.withRotation(30f) }))
        assertEquals(R.string.history_audio_effects, say(base.update(3) { it.withAudioEffects(listOf(AudioFx.of(FxKind.Reverb))) }))
        assertEquals(R.string.history_rename, say(base.update(2) { it.copy(node = it.node.with("label", "Two")) }))
        assertEquals(R.string.history_timing, say(base.update(2) { it.copy(node = it.node.with("endTime", "500")) }))
    }

    @Test fun addingAndDeletingNameTheLayerWhenItHasAName() {
        val without = base.remove(1)
        assertEquals(R.string.history_delete_named, say(without, base))
        assertEquals(listOf<Any>("Box"), describeEdit(base, without).args)
        assertEquals(R.string.history_add_named, say(base, without))
        assertEquals(R.string.history_delete_layer, say(base.remove(2), base))
        assertEquals(R.string.history_delete_layers, say(base.remove(2).remove(1), base))
        assertEquals(R.string.history_add_layers, say(base, base.remove(2).remove(1)))
    }

    @Test fun severalLayersChangingTheSameWayShareTheName() {
        val both = base.update(1) { it.withTransform("opacity", 0.5f) }.update(2) { it.withTransform("opacity", 0.5f) }
        assertEquals(R.string.history_opacity, say(both))
        val different = base.update(1) { it.withTransform("opacity", 0.5f) }.update(2) { it.withTransform("location", 1f, 1f, 0f) }
        assertEquals(R.string.history_edit_layers, say(different))
    }

    @Test fun projectSettingsAndUnknownChangesHaveAName() {
        assertEquals(R.string.history_project, say(base.withSettings(200, 100, 30, 0)))
        assertEquals(R.string.history_edit, say(base.update(2) { it.copy(node = it.node.with("somethingNew", "1")) }))
        assertEquals(R.string.history_edit, say(base))
    }
}
