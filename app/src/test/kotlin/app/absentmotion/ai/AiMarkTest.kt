package app.absentmotion.ai

import app.absentmotion.R
import app.absentmotion.i18n.txt
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.effects
import app.absentmotion.videoLayer
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

    private fun video(inTime: Int = 0, outTime: Int? = null) = videoLayer(0, 3000, inTime = inTime, outTime = outTime, uri = "raw.mp4")

    private fun Layer.show(uri: String) = copy(node = node.with("fillVideo", uri))

    private fun stacked(smoothFirst: Boolean): Layer = if (smoothFirst) {
        video().show("smooth.mp4").withAiMark(AiKind.Smooth, "raw.mp4", "smooth.mp4", "Flow:Medium:Off")
            .show("enhanced.mp4").withAiMark(AiKind.Enhance, "smooth.mp4", "enhanced.mp4", "upscale2")
    } else {
        video().show("enhanced.mp4").withAiMark(AiKind.Enhance, "raw.mp4", "enhanced.mp4", "upscale2")
            .show("smooth.mp4").withAiMark(AiKind.Smooth, "enhanced.mp4", "smooth.mp4", "Flow:Medium:Off")
    }

    @Test fun restoringTheOlderOfTwoStackedResultsAlsoUndoesTheNewerOne() {
        val smoothThenEnhance = stacked(true)
        assertEquals(listOf(AiKind.Smooth, AiKind.Enhance), smoothThenEnhance.aiStack(AiKind.Smooth).map { it.kind })
        val back = smoothThenEnhance.withoutAiMark(AiKind.Smooth)
        assertEquals("raw.mp4", back.node["fillVideo"])
        assertTrue(back.aiMarks.isEmpty())
        val newest = smoothThenEnhance.withoutAiMark(AiKind.Enhance)
        assertEquals("smooth.mp4", newest.node["fillVideo"])
        assertEquals(listOf(AiKind.Smooth), newest.aiMarks.map { it.kind })
        val enhanceThenSmooth = stacked(false)
        val smoothGone = enhanceThenSmooth.withoutAiMark(AiKind.Smooth)
        assertEquals("enhanced.mp4", smoothGone.node["fillVideo"])
        assertEquals(listOf(AiKind.Enhance), smoothGone.aiMarks.map { it.kind })
        val everything = enhanceThenSmooth.withoutAiMark(AiKind.Enhance)
        assertEquals("raw.mp4", everything.node["fillVideo"])
        assertTrue(everything.aiMarks.isEmpty())
    }

    @Test fun aToolIsBuriedOnlyWhileANewerOneStandsOnItsResult() {
        val smoothThenEnhance = stacked(true)
        assertTrue(smoothThenEnhance.aiBuried(AiKind.Smooth))
        assertFalse(smoothThenEnhance.aiBuried(AiKind.Enhance))
        val enhanceThenSmooth = stacked(false)
        assertTrue(enhanceThenSmooth.aiBuried(AiKind.Enhance))
        assertFalse(enhanceThenSmooth.aiBuried(AiKind.Smooth))
        assertFalse(video().aiBuried(AiKind.Smooth))
        assertFalse(scene.layer(1)!!.withAiMark(AiKind.Noise, "raw.wav", "clean.wav").aiBuried(AiKind.Noise))
        assertEquals(txt(R.string.ai_undo_newer_first), AiAction.Smooth.unavailable(smoothThenEnhance))
        assertNull(AiAction.Smooth.unavailable(enhanceThenSmooth))
        assertNull(AiAction.Video.unavailable(smoothThenEnhance.copy(node = smoothThenEnhance.node.with("s", ".rect"))))
        assertEquals(txt(R.string.ai_select_video_layer), AiAction.Smooth.unavailable(null))
    }

    @Test fun replacedMediaIsNeitherBuriedNorBroughtBack() {
        val replaced = stacked(true).show("other.mp4")
        assertFalse(replaced.aiBuried(AiKind.Smooth))
        assertTrue(replaced.aiStack(AiKind.Smooth).isEmpty())
        val restored = replaced.withoutAiMark(AiKind.Smooth)
        assertEquals("other.mp4", restored.node["fillVideo"])
        assertEquals(listOf(AiKind.Enhance), restored.aiMarks.map { it.kind })
    }

    @Test fun aRebasedResultIsRestoredInTheOriginalsTime() {
        val smoothed = video(inTime = 0, outTime = 3000).show("smooth.mp4").withAiMark(AiKind.Smooth, "raw.mp4", "smooth.mp4", "Flow:Medium:Off", null, 12_000)
        assertEquals("12000", smoothed.aiMarks.single().node["shift"])
        assertEquals(12_000, smoothed.aiMarks.single().shift)
        val back = smoothed.withoutAiMark(AiKind.Smooth).mediaFill!!
        assertEquals("raw.mp4", back.uri)
        assertEquals(12_000, back.inTime)
        assertEquals(15_000, back.outTime)
        val moved = smoothed.copy(node = smoothed.node.with("inTime", "500").with("outTime", "2500")).withoutAiMark(AiKind.Smooth).mediaFill!!
        assertEquals(12_500 to 14_500, moved.inTime to moved.outTime)
        val toTheEnd = video(inTime = 0).show("smooth.mp4").withAiMark(AiKind.Smooth, "raw.mp4", "smooth.mp4", "Flow:Medium:Off", null, 4_000).withoutAiMark(AiKind.Smooth).mediaFill!!
        assertEquals(4_000, toTheEnd.inTime)
        assertEquals(Int.MAX_VALUE, toTheEnd.outTime)
        assertNull(video().withAiMark(AiKind.Smooth, "raw.mp4", "smooth.mp4").aiMarks.single().node["shift"])
    }

    @Test fun shiftingMediaTimeKeepsTheTrimInsideTheFile() {
        val clip = video(inTime = 4000, outTime = 6000)
        val rebased = clip.shiftedMedia(-4000).mediaFill!!
        assertEquals(0 to 2000, rebased.inTime to rebased.outTime)
        assertEquals(clip.node, clip.shiftedMedia(0).node)
        assertEquals(0, video(inTime = 1000).shiftedMedia(-4000).mediaFill!!.inTime)
        assertEquals(Int.MAX_VALUE, video(inTime = 1000).shiftedMedia(-1000).mediaFill!!.outTime)
        val still = scene.layer(2)!!
        assertEquals(still.node, still.shiftedMedia(500).node)
    }
}
