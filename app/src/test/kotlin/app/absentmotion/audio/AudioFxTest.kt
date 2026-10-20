package app.absentmotion.audio

import app.absentmotion.R
import app.absentmotion.editor.extractedAudio
import app.absentmotion.model.Scene
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFxTest {
    private val old = """<scene width="100" height="100" fps="30"><audio id="1" src="a" startTime="0" endTime="999"><gain value="0.8"/></audio></scene>"""

    private fun reload(scene: Scene) = Scene.parse(scene.node.toXml())

    @Test fun aChainSurvivesSavingAndLoading() {
        val chain = listOf(
            AudioFx.of(FxKind.Eq, "lowGain" to 6f, "mid2Freq" to 2500f).withMix(0.75f),
            AudioFx.of(FxKind.Reverb, "room" to 0.8f, "preDelay" to 30f).withBypass(true),
            AudioFx.of(FxKind.LowPass, "freq" to 900f, "slope" to 24f),
        )
        val scene = Scene.parse(old).update(1) { it.withAudioEffects(chain) }
        val loaded = reload(scene).layer(1)!!.audioEffects
        assertEquals(chain, loaded)
        assertEquals(0.75f, loaded[0].mix)
        assertTrue(loaded[1].bypass)
        assertEquals(24f, loaded[2][FxKind.LowPass.params[2]])
        val a = chain.spec()!!
        val b = loaded.spec()!!
        assertArrayEquals(a.kinds, b.kinds)
        assertArrayEquals(a.values, b.values, 0f)
        assertEquals("0.8", reload(scene).layer(1)!!.node.child("gain")!!["value"])
    }

    @Test fun oldProjectsLoadWithoutEffects() {
        val scene = Scene.parse(old)
        assertTrue(scene.layer(1)!!.audioEffects.isEmpty())
        assertNull(AudioClip.of(scene).single().fx)
        assertEquals(scene.node.toXml(), reload(scene).node.toXml())
        assertEquals(scene.layer(1)!!.node, scene.layer(1)!!.withAudioEffects(emptyList()).node)
    }

    @Test fun unknownEffectsAndNodesArePreserved() {
        val xml = old.replace("</audio>", """<audioEffects version="9"><fx type="flanger" speed="3"/><fx type="echo" time="500" colour="blue"/><future a="1"/></audioEffects></audio>""")
        val layer = Scene.parse(xml).layer(1)!!
        assertEquals(2, layer.audioEffects.size)
        assertNull(layer.audioEffects[0].kind)
        val spec = layer.audioEffects.spec()!!
        assertArrayEquals(intArrayOf(FxKind.Echo.id), spec.kinds)
        assertEquals(500f, spec.values[2])
        val edited = layer.updateAudioEffect(1) { it.with(FxKind.Echo.params[1], 0.5f) }.moveAudioEffect(1, 0)
        val container = edited.node.child(AUDIO_FX_TAG)!!
        assertEquals("9", container["version"])
        assertEquals(listOf("fx", "fx", "future"), container.children.map { it.name })
        assertEquals("echo", container.children[0]["type"])
        assertEquals("blue", container.children[0]["colour"])
        assertEquals("0.5", container.children[0]["feedback"])
        assertEquals("flanger", container.children[1]["type"])
        val emptied = edited.withAudioEffects(emptyList()).node.child(AUDIO_FX_TAG)!!
        assertEquals(listOf("future"), emptied.children.map { it.name })
    }

    @Test fun badValuesFallBackToDefaultsOrRanges() {
        val fx = AudioFx.of(FxKind.Compressor).let { AudioFx(it.node.with("ratio", "NaN").with("threshold", "-500").with("mix", "7")) }
        assertEquals(4f, fx[FxKind.Compressor.params[1]])
        assertEquals(-60f, fx[FxKind.Compressor.params[0]])
        assertEquals(1f, fx.mix)
        val reset = fx.with(FxKind.Compressor.params[0], -30f).withBypass(true).reset()
        assertEquals(-18f, reset[FxKind.Compressor.params[0]])
        assertTrue(reset.bypass)
    }

    @Test fun parameterLayoutMatchesTheNativeBridge() {
        val counts = mapOf(FxKind.Eq to 13, FxKind.LowPass to 3, FxKind.HighPass to 3, FxKind.Reverb to 6, FxKind.Echo to 5, FxKind.Compressor to 6, FxKind.Gate to 5)
        assertEquals(FxKind.entries.toSet(), counts.keys)
        for ((kind, count) in counts) {
            assertEquals(kind.xml, count, kind.params.size)
            assertEquals(kind, FxKind.entries.single { it.id == kind.id })
            for (p in kind.params) assertTrue("${kind.xml} ${p.key}", p.default in p.min..p.max && (!p.log || p.min > 0f))
        }
        val spec = FxKind.entries.map { AudioFx.of(it) }.spec()!!
        assertEquals(counts.values.sumOf { it + 2 }, spec.values.size)
        assertTrue((List(MAX_AUDIO_FX + 3) { AudioFx.of(FxKind.Eq) }).spec()!!.kinds.size == MAX_AUDIO_FX)
    }

    @Test fun everyPresetIsAValidChain() {
        assertEquals(listOf(R.string.audio_preset_radio, R.string.audio_preset_telephone, R.string.audio_preset_small_room, R.string.audio_preset_hall, R.string.audio_preset_stadium, R.string.audio_preset_underwater, R.string.audio_fx_echo, R.string.audio_preset_bass_boost, R.string.audio_preset_vocal_clarity, R.string.audio_fx_noise_gate), AudioPresets.map { it.name })
        for (preset in AudioPresets) {
            assertTrue("${preset.name}", preset.effects.isNotEmpty() && preset.effects.all { it.kind != null })
            val spec = preset.effects.spec()!!
            assertEquals(preset.effects.size, spec.kinds.size)
            assertTrue(spec.values.all { it.isFinite() })
        }
    }

    @Test fun extractedAudioKeepsTheEffects() {
        val video = Scene.parse("""<scene width="100" height="100" fps="30"><shape id="1" startTime="0" endTime="999" fillType="media" fillVideo="file:/v.mp4" s=".rect"/></scene>""")
            .update(1) { it.withAudioEffects(AudioPresets.first { p -> p.name == R.string.audio_preset_hall }.effects) }
        val clip = AudioClip.of(video).single()
        assertArrayEquals(intArrayOf(FxKind.Reverb.id), clip.fx!!.kinds)
        assertEquals(video.layer(1)!!.audioEffects, video.layer(1)!!.extractedAudio(2)!!.audioEffects)
    }
}
