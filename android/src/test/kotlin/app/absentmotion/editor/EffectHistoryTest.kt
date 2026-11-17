package app.absentmotion.editor

import app.absentmotion.R
import app.absentmotion.audio.AudioFx
import app.absentmotion.audio.FxKind
import app.absentmotion.audio.FxParam
import app.absentmotion.audio.audioEffects
import app.absentmotion.audio.updateAudioEffect
import app.absentmotion.audio.withAudioEffects
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.i18n.txt
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.addEffect
import app.absentmotion.model.moveEffect
import app.absentmotion.model.removeEffect
import app.absentmotion.model.withEffect
import app.absentmotion.model.withEffectProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class EffectHistoryTest {
    private val catalog = EffectCatalog.parse { File("../shared/assets/effects", it).inputStream() }
    private val names: (String) -> String? = { catalog.find(it)?.name }
    private fun effect(id: String) = catalog.find("absent.$id")!!.instance()

    private val base = Scene.parse(
        """<scene width="100" height="100" fps="30"><shape id="1" label="Box" startTime="0" endTime="1000" s=".rect"><transform><location value="10,10,0"/></transform></shape>""" +
            """<shape id="2" startTime="2000" endTime="3000" s=".rect"/><audio id="3" src="a" startTime="0" endTime="999"/>""" +
            """<embedScene id="5" label="Group" startTime="0" endTime="3000"><scene width="100" height="100" fps="30" totalTime="3000"><shape id="6" startTime="500" endTime="1500" s=".rect"/></scene></embedScene></scene>""",
    )
    private val blurred = base.update(1) { it.addEffect(effect("blur")) }
    private val both = blurred.update(1) { it.addEffect(effect("glow")) }

    private fun say(before: Scene, after: Scene, effectName: (String) -> String? = names) = describeEdit(before, after, effectName)
    private fun spot(before: Scene, after: Scene, undone: Boolean) = summarizeEdit(before, after, names, undone).spot

    @Test fun addingAndRemovingAnEffectNamesIt() {
        assertEquals(txt(R.string.history_add_effect, "Blur"), say(base, blurred))
        assertEquals(txt(R.string.history_add_effect, "Glow"), say(blurred, both))
        assertEquals(txt(R.string.history_remove_effect, "Blur"), say(both, both.update(1) { it.removeEffect(0) }))
    }

    @Test fun settingsKeyframesAndTurningOffNameTheEffectAlone() {
        val stronger = both.update(1) { l -> l.withEffect(0) { it.withEffectProperty("strength", "float", "40", l.t, l.snap) } }
        assertEquals(txt(R.string.library_text_value, "Blur"), say(both, stronger))
        val keyed = both.update(1, 500) { it.withKeyframeToggled(PropRef.effect(1, "radius"), "100") }
        assertEquals(txt(R.string.library_text_value, "Glow"), say(both, keyed))
        val off = both.update(1) { l -> l.withEffect(1) { it.with("hidden", "true") } }
        assertEquals(txt(R.string.library_text_value, "Glow"), say(both, off))
    }

    @Test fun movingAnEffectNamesTheOneThatMoved() {
        assertEquals(txt(R.string.history_move_effect, "Blur"), say(both, both.update(1) { it.moveEffect(0, 1) }))
    }

    @Test fun swappingTwoOfTheSameEffectIsAMove() {
        val two = base.update(1) { it.addEffect(effect("blur")).addEffect(effect("blur")) }.update(1) { l -> l.withEffect(1) { it.withEffectProperty("strength", "float", "40", l.t, l.snap) } }
        val swapped = two.update(1) { it.moveEffect(0, 1) }
        assertEquals(txt(R.string.history_move_effect, "Blur"), say(two, swapped))
        assertEquals(1, spot(two, swapped, undone = false)!!.effect)
        assertEquals(0, spot(two, swapped, undone = true)!!.effect)
    }

    @Test fun twoEffectsAreBothNamedAndMoreFallBackToEffects() {
        val two = base.update(1) { it.addEffect(effect("blur")).addEffect(effect("glow")) }
        assertEquals(txt(R.string.history_add_effect, txt(R.string.history_two_effects, "Blur", "Glow")), say(base, two))
        val three = base.update(1) { it.addEffect(effect("blur")).addEffect(effect("glow")).addEffect(effect("pixelate")) }
        assertEquals(txt(R.string.history_effects), say(base, three))
        val onTwoLayers = base.update(1) { it.addEffect(effect("blur")) }.update(2) { it.addEffect(effect("blur")) }
        assertEquals(txt(R.string.history_add_effect, "Blur"), say(base, onTwoLayers))
    }

    @Test fun anEffectWithoutAKnownNameKeepsTheGenericText() {
        assertEquals(txt(R.string.history_effects), say(base, blurred) { null })
    }

    @Test fun audioEffectsAreNamedLikeTheAudioEffectsList() {
        val reverb = base.update(3) { it.withAudioEffects(listOf(AudioFx.of(FxKind.Reverb))) }
        assertEquals(txt(R.string.history_add_effect, txt(R.string.audio_fx_reverb)), say(base, reverb))
        val wetter = reverb.update(3) { l -> l.updateAudioEffect(0) { it.with(FxKind.Reverb.params.first { p: FxParam -> p.key == "wet" }, 0.8f) } }
        assertEquals(txt(R.string.library_text_value, txt(R.string.audio_fx_reverb)), say(reverb, wetter))
        assertEquals(EditSpot(emptyList(), 3, R.string.history_audio_effects, 0, "reverb"), spot(reverb, wetter, undone = true))
        assertEquals(EditSpot(emptyList(), 3, R.string.history_audio_effects, 0, "reverb"), spot(base, reverb, undone = false))
        assertNull(spot(base, reverb, undone = true))
        assertEquals(0.8f, wetter.layer(3)!!.audioEffects[0][FxKind.Reverb.params.first { it.key == "wet" }], 1e-4f)
    }

    @Test fun aLayerInsideAGroupIsNamedAndFound() {
        val inner = base.update(5) { g -> g.copy(node = g.node.withChild("scene", g.nested!!.update(6) { it.addEffect(effect("glow")) }.node)) }
        assertEquals(txt(R.string.history_add_effect, "Glow"), say(base, inner))
        assertEquals(EditSpot(listOf(5L), 6, R.string.history_effects, 0, "absent.glow"), spot(base, inner, undone = false))
        val moved = base.update(5) { g -> g.copy(node = g.node.withChild("scene", g.nested!!.update(6) { it.withTransform("location", 3f, 4f, 0f) }.node)) }
        assertEquals(txt(R.string.history_position), say(base, moved))
        assertEquals(EditSpot(listOf(5L), 6, R.string.history_position), spot(base, moved, undone = true))
    }

    @Test fun effectNamesFollowTheLanguage() {
        val german = EffectCatalog.parse("de") { File("../shared/assets/effects", it).inputStream() }
        val name = german.find("absent.blur")!!.name
        assertEquals(txt(R.string.history_add_effect, name), say(base, blurred) { german.find(it)?.name })
        assertEquals("Unschärfe", name)
    }

    @Test fun theSpotPointsAtTheEffectWhereItIsNowOrNowhere() {
        assertEquals(EditSpot(emptyList(), 1, R.string.history_effects, 0, "absent.blur"), spot(base, blurred, undone = false))
        assertNull(spot(base, blurred, undone = true))
        val removed = both.update(1) { it.removeEffect(1) }
        assertEquals(EditSpot(emptyList(), 1, R.string.history_effects, 1, "absent.glow"), spot(both, removed, undone = true))
        assertNull(spot(both, removed, undone = false))
        val moved = both.update(1) { it.moveEffect(0, 1) }
        assertEquals(1, spot(both, moved, undone = false)!!.effect)
        assertEquals(0, spot(both, moved, undone = true)!!.effect)
    }

    @Test fun aKeyframeChangeGivesItsTime() {
        val at = both.layer(1)!!.at(500, 30)
        val keyed = both.update(1, 500) { it.withKeyframeToggled(PropRef.effect(1, "radius"), "100") }
        val where = spot(both, keyed, undone = false)!!
        assertEquals(1, where.effect)
        assertEquals(at.t, where.keyT!!, 1e-4f)
        assertEquals(at.t, spot(both, keyed, undone = true)!!.keyT!!, 1e-4f)
        val moved = base.update(1, 700) { it.withKeyframeToggled(PropRef.Location, "10,10,0") }
        assertEquals(base.layer(1)!!.at(700, 30).t, spot(base, moved, undone = false)!!.keyT!!, 1e-4f)
        assertNull(spot(base, base.update(1) { it.withTransform("location", 1f, 2f, 0f) }, undone = false)!!.keyT)
    }

    @Test fun theSpotOfOtherChanges() {
        assertEquals(EditSpot(emptyList(), 2, R.string.history_timing), spot(base, base.update(2) { it.withTimes(2000, 2500) }, undone = false))
        assertEquals(EditSpot(emptyList(), 1, R.string.history_opacity), spot(base, base.update(1) { it.withTransform("opacity", 0.5f) }, undone = true))
        val without = base.remove(1)
        assertEquals(EditSpot(emptyList(), 1), spot(base, without, undone = true))
        assertNull(spot(base, without, undone = false))
        assertEquals(EditSpot(emptyList(), 1), spot(without, base, undone = false))
        assertNull(spot(base, base.withSettings(200, 100, 30, 0), undone = false))
        val reordered = Scene(base.node.copy(children = base.node.children.let { listOf(it[1], it[0]) + it.drop(2) }))
        assertEquals(txt(R.string.history_reorder), say(base, reordered))
        assertNotNull(spot(base, reordered, undone = false))
    }
}
