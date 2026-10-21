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

    @Test fun onlyNewFiltersUseTheFlatTwentyFourDecibelSlope() {
        val saved = Scene.parse(old.replace("</audio>", """<audioEffects><fx type="lowpass" freq="500" resonance="2.5" slope="24"/></audioEffects></audio>"""))
        assertEquals(0f, saved.layer(1)!!.audioEffects.spec()!!.values[5])
        val fresh = saved.update(1) { it.withAudioEffects(listOf(AudioFx.of(FxKind.LowPass, "slope" to 24f), AudioFx.of(FxKind.HighPass))) }
        val spec = reload(fresh).layer(1)!!.audioEffects.spec()!!
        assertEquals(1f, spec.values[5])
        assertEquals(1f, spec.values[11])
        val voice = listOf(AudioFx.of(FxKind.Voice, "muffle" to 0.5f)).spec()!!
        var at = 0
        for (native in voice.kinds) { if (native == FxKind.LowPass.id || native == FxKind.HighPass.id) assertEquals(0f, voice.values[at + 5]); at += 2 + nativeCounts.getValue(native) }
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

    private val nativeCounts = mapOf(0 to 13, 1 to 4, 2 to 4, 3 to 6, 4 to 5, 5 to 6, 6 to 5, NativeFx.PITCH to 1)

    @Test fun parameterLayoutMatchesTheNativeBridge() {
        val counts = mapOf(FxKind.Eq to 13, FxKind.LowPass to 3, FxKind.HighPass to 3, FxKind.Reverb to 6, FxKind.Echo to 5, FxKind.Compressor to 6, FxKind.Gate to 5,
            FxKind.HighPitch to 1, FxKind.LowPitch to 1, FxKind.Voice to 9)
        assertEquals(FxKind.entries.toSet(), counts.keys)
        for ((kind, count) in counts) {
            assertEquals(kind.xml, count, kind.params.size)
            for (p in kind.params) assertTrue("${kind.xml} ${p.key}", p.default in p.min..p.max && (!p.log || p.min > 0f))
        }
        for (kind in FxKind.entries.filter { it.units == 1 && it != FxKind.HighPitch && it != FxKind.LowPitch })
            assertEquals(kind, FxKind.entries.single { it.id == kind.id })
        val spec = FxKind.entries.map { AudioFx.of(it) }.spec()!!
        assertEquals(spec.kinds.sumOf { 2 + nativeCounts.getValue(it) }, spec.values.size)
        assertEquals(FxKind.entries.sumOf { it.units }, spec.kinds.size)
        assertTrue((List(MAX_AUDIO_FX + 3) { AudioFx.of(FxKind.Eq) }).spec()!!.kinds.size == MAX_AUDIO_FX)
    }

    @Test fun pitchEffectsAndVoiceFiltersBecomeNativeUnits() {
        val up = listOf(AudioFx.of(FxKind.HighPitch, "amount" to 7f)).spec()!!
        val down = listOf(AudioFx.of(FxKind.LowPitch, "amount" to 7f)).spec()!!
        assertArrayEquals(intArrayOf(NativeFx.PITCH), up.kinds)
        assertEquals(7f, up.values[2])
        assertEquals(-7f, down.values[2])
        val neutral = listOf(AudioFx.of(FxKind.Voice)).spec()!!
        assertArrayEquals(intArrayOf(NativeFx.PITCH, 0, 2, 1, 5, 4, 3), neutral.kinds)
        var at = 0
        for (native in neutral.kinds) { assertEquals(1f, neutral.values[at + 1]); at += 2 + nativeCounts.getValue(native) }
        assertEquals(neutral.values.size, at)
        assertTrue(listOf(AudioFx.of(FxKind.Voice).withBypass(true)).spec()!!.values.let { v -> var i = 0; neutral.kinds.all { k -> (v[i + 1] == 1f).also { i += 2 + nativeCounts.getValue(k) } } })
    }

    @Test fun everyVoiceFilterKeepsTheSameChainAndChangesTheSound() {
        assertTrue(VoicePresets.size in 6..12)
        for (preset in VoicePresets) {
            assertEquals(FxKind.Voice, preset.effect.kind)
            val spec = listOf(preset.effect).spec()!!
            assertArrayEquals(intArrayOf(NativeFx.PITCH, 0, 2, 1, 5, 4, 3), spec.kinds)
            assertTrue(spec.values.all { it.isFinite() })
            var at = 0
            var active = 0
            for (native in spec.kinds) { if (spec.values[at + 1] == 0f) active++; at += 2 + nativeCounts.getValue(native) }
            assertTrue("${preset.name}", active > 0)
        }
        val layer = Scene.parse(old).layer(1)!!.withAudioEffects(listOf(AudioFx.of(FxKind.Reverb)))
        val first = layer.withVoicePreset(VoicePresets[0])
        assertEquals(listOf(FxKind.Reverb, FxKind.Voice), first.audioEffects.map { it.kind })
        val second = first.withVoicePreset(VoicePresets[1])
        assertEquals(listOf(FxKind.Reverb, FxKind.Voice), second.audioEffects.map { it.kind })
        assertEquals(VoicePresets[1].effect, second.audioEffects[1])
        assertTrue(listOf(AudioFx.of(FxKind.Voice)).fits(FxKind.Voice))
        assertTrue(!listOf(AudioFx.of(FxKind.Voice), AudioFx.of(FxKind.Voice)).fits(FxKind.Voice))
        assertTrue(listOf(AudioFx.of(FxKind.Voice), AudioFx.of(FxKind.Voice)).fits(FxKind.Eq))
    }

    @Test fun theAudioCategoryListsEveryEffectOnce() {
        val shown = app.absentmotion.ui.editor.effects.AudioOrder
        assertEquals(FxKind.entries.toSet(), shown.toSet())
        assertEquals(shown.size, shown.distinct().size)
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
