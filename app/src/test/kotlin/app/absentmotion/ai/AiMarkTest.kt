package app.absentmotion.ai

import app.absentmotion.model.Scene
import app.absentmotion.model.effects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMarkTest {
    private val scene = Scene.parse("""<scene width="100" height="100" fps="30"><audio id="1" src="clean.wav" startTime="0" endTime="999"/><shape id="2" fillImage="cut.png" startTime="0" endTime="999" s=".rect"/></scene>""")

    @Test fun aMarkRecordsWhatWasDoneAndSurvivesSaving() {
        val layer = scene.layer(1)!!.withAiMark(AiKind.Noise, "raw.wav", "clean.wav", NoiseSource.Wind.xml)
        val saved = Scene.parse(scene.update(1) { layer }.node.toXml()).layer(1)!!
        val mark = saved.aiMarks.single()
        assertEquals(AiKind.Noise, mark.kind)
        assertEquals(NoiseSource.Wind, mark.source)
        assertEquals("raw.wav", mark.original)
        assertTrue(mark.restorable)
        assertTrue(saved.effects.isEmpty())
    }

    @Test fun doingItAgainKeepsTheFirstOriginal() {
        val once = scene.layer(1)!!.withAiMark(AiKind.Noise, "raw.wav", "one.wav", "voice")
        val twice = once.copy(node = once.node.with("src", "two.wav")).withAiMark(AiKind.Noise, "one.wav", "two.wav", "mixed")
        assertEquals(1, twice.aiMarks.size)
        assertEquals("raw.wav", twice.aiMarks.single().original)
        assertEquals("two.wav", twice.aiMarks.single().result)
        assertEquals(NoiseSource.Mixed, twice.aiMarks.single().source)
    }

    @Test fun removingAMarkBringsBackTheOriginalMedia() {
        val noise = scene.layer(1)!!.withAiMark(AiKind.Noise, "raw.wav", "clean.wav", "voice")
        val restored = noise.withoutAiMark(AiKind.Noise)
        assertEquals("raw.wav", restored.node["src"])
        assertTrue(restored.aiMarks.isEmpty())
        val cut = scene.layer(2)!!.withAiMark(AiKind.Background, "photo.png", "cut.png")
        assertEquals("photo.png", cut.withoutAiMark(AiKind.Background).node["fillImage"])
    }

    @Test fun mediaChangedAfterwardsIsNotOverwritten() {
        val noise = scene.layer(1)!!.withAiMark(AiKind.Noise, "raw.wav", "clean.wav", "voice")
        val replaced = noise.copy(node = noise.node.with("src", "newer.wav"))
        val restored = replaced.withoutAiMark(AiKind.Noise)
        assertEquals("newer.wav", restored.node["src"])
        assertTrue(restored.aiMarks.isEmpty())
    }

    @Test fun aVideoCutOutIsShownButCannotBeBroughtBackWithOneTap() {
        val group = scene.layer(2)!!.withAiMark(AiKind.Video, null, null)
        assertFalse(group.aiMarks.single().restorable)
        assertNull(group.aiMarks.single().original)
    }
}
