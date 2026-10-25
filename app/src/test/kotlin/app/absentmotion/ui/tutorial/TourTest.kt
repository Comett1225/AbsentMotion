package app.absentmotion.ui.tutorial

import app.absentmotion.R
import app.absentmotion.editor.imageLayer
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.MotionSlot
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.withAnimation
import app.absentmotion.model.withContent
import app.absentmotion.model.withKeyframes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TourTest {
    private val editing = TourFacts(editing = true)

    private fun at(id: String) = Tour(startAt = id)

    private fun apply(action: TourAction, f: TourFacts): TourFacts = when (action) {
        TourAction.Unselect -> f.copy(selected = null, panel = TourFacts.TOOLS)
        TourAction.SamplePicture -> f.copy(media = f.media + 2, mediaMs = f.mediaMs + 4000, selected = TourFacts.MEDIA, adding = false, moves = f.moves + 1)
        TourAction.SampleText -> f.copy(texts = f.texts + 1, selected = TourFacts.TEXT, panel = TourFacts.TOOLS, adding = false)
        TourAction.SampleSound -> f.copy(audio = f.audio + 1, selected = TourFacts.AUDIO, adding = false)
        TourAction.Move -> f.copy(moves = f.moves + 1)
        TourAction.Scrub -> f.copy(frame = f.frame + 30)
        TourAction.Split -> f.copy(media = f.media + 1, selected = null)
        TourAction.Keyframes -> f.copy(keyframes = f.keyframes + 2, panel = TourFacts.TRANSFORM, selected = TourFacts.MEDIA)
        TourAction.Ease -> f.copy(eased = f.eased + 1)
        TourAction.Effect -> f.copy(effects = f.effects + 1, panel = TourFacts.EFFECTS, selected = TourFacts.MEDIA)
        TourAction.Animation -> f.copy(motions = f.motions + 1, panel = TourFacts.TOOLS)
        TourAction.Export -> f.copy(exporting = true, selected = null)
    }

    private fun Tour.drive(start: TourFacts, actions: List<TourAction>): TourFacts {
        var facts = start
        var pending = actions
        var guard = 0
        while (pending.isNotEmpty() && guard++ < 20) {
            pending.forEach { facts = apply(it, facts) }
            pending = observe(facts)
        }
        return facts
    }

    @Test fun stepsAreUniqueShortAndTimedForFourToFiveMinutes() {
        val steps = TourSteps.all
        assertEquals(steps.size, steps.map { it.id }.toSet().size)
        assertTrue(steps.size in 15..18)
        assertTrue(steps.sumOf { it.seconds } in 240..300)
        for (step in steps) if (step.doIt.isNotEmpty()) assertTrue(step.id, step.done != null)
    }

    @Test fun readingStepsWaitForNext() {
        val tour = Tour()
        assertEquals(emptyList<TourAction>(), tour.observe(editing))
        assertEquals("welcome", tour.step.id)
        tour.next()
        assertEquals("screen", tour.step.id)
        assertFalse(tour.canDoIt)
    }

    @Test fun nothingHappensOutsideThePracticeProject() {
        val tour = at("picture")
        assertEquals(emptyList<TourAction>(), tour.observe(TourFacts(editing = false, media = 5)))
        assertEquals("picture", tour.step.id)
        assertEquals(emptyList<TourAction>(), tour.pickerClosed(false))
    }

    @Test fun pictureStepUnselectsFirstAndPraisesAPickedPicture() {
        val tour = at("picture")
        assertEquals(listOf(TourAction.Unselect), tour.observe(editing.copy(selected = TourFacts.TEXT)))
        assertEquals(emptyList<TourAction>(), tour.observe(editing))
        assertEquals(listOf("add"), tour.targets())
        tour.observe(editing.copy(adding = true))
        assertEquals(listOf("add_panel"), tour.targets())
        assertEquals(emptyList<TourAction>(), tour.pickerClosed(true))
        tour.observe(editing.copy(adding = false, media = 1, selected = TourFacts.MEDIA))
        assertEquals("gestures", tour.step.id)
        assertEquals(R.string.tutorial_note_nice, tour.note)
    }

    @Test fun cancelledPickerAddsTheSamplesOnce() {
        val tour = at("picture")
        tour.observe(editing)
        tour.observe(editing.copy(adding = true))
        assertEquals(listOf(TourAction.SamplePicture), tour.pickerClosed(false))
        assertEquals(R.string.tutorial_note_sample_picture, tour.note)
        assertEquals(emptyList<TourAction>(), tour.pickerClosed(false))
        assertEquals(emptyList<TourAction>(), tour.observe(editing.copy(adding = false)))
        tour.observe(editing.copy(media = 2, selected = TourFacts.MEDIA))
        assertEquals("gestures", tour.step.id)
        assertEquals(R.string.tutorial_note_sample_picture, tour.note)
    }

    @Test fun backingOutOfTheAddPanelAddsTheSamples() {
        val tour = at("sound")
        tour.observe(editing)
        tour.observe(editing.copy(adding = true))
        assertEquals(listOf(TourAction.SampleSound), tour.observe(editing.copy(adding = false)))
        assertEquals(R.string.tutorial_note_sample_sound, tour.note)
    }

    @Test fun doItForMeCompletesTheStepWithoutPraise() {
        val tour = at("gestures")
        val start = editing.copy(media = 1, selected = TourFacts.MEDIA)
        tour.observe(start)
        assertTrue(tour.canDoIt)
        tour.drive(start, tour.doIt())
        assertEquals("timeline", tour.step.id)
        assertNull(tour.note)
    }

    @Test fun deletedSampleIsPutBackAtMostTwice() {
        val tour = at("gestures")
        val empty = editing.copy(media = 0)
        assertEquals(listOf(TourAction.SamplePicture), tour.observe(empty))
        assertEquals(R.string.tutorial_note_restored, tour.note)
        assertEquals(listOf(TourAction.SamplePicture), tour.observe(empty))
        assertEquals(emptyList<TourAction>(), tour.observe(empty))
        assertEquals("gestures", tour.step.id)
    }

    @Test fun restoredSampleDoesNotCountAsTheUsersMove() {
        val tour = at("gestures")
        tour.observe(editing.copy(media = 1, moves = 5, selected = TourFacts.MEDIA))
        tour.observe(editing.copy(media = 0, moves = 6))
        tour.observe(editing.copy(media = 2, moves = 7, selected = TourFacts.MEDIA))
        assertEquals("gestures", tour.step.id)
        tour.observe(editing.copy(media = 2, moves = 8, selected = TourFacts.MEDIA))
        assertEquals("timeline", tour.step.id)
    }

    @Test fun undoneKeyframesAreRestoredForTheEasingStep() {
        val tour = at("easing")
        assertEquals(listOf(TourAction.Keyframes), tour.observe(editing.copy(media = 1, keyframes = 0)))
    }

    @Test fun keyframeStepNeedsTwoNewKeyframes() {
        val tour = at("keyframes")
        val start = editing.copy(media = 1, selected = TourFacts.MEDIA, panel = TourFacts.TRANSFORM)
        tour.observe(start)
        assertEquals(listOf("keyframe"), tour.targets())
        tour.observe(start.copy(keyframes = 1))
        assertEquals(listOf("timeline"), tour.targets())
        assertEquals(R.string.tutorial_hint_second_keyframe, tour.hint())
        tour.observe(start.copy(keyframes = 2))
        assertEquals("easing", tour.step.id)
    }

    @Test fun hintsPointBackToTheTimelineOrTools() {
        val tour = at("effect")
        tour.observe(editing.copy(media = 1))
        assertEquals(R.string.tutorial_hint_select, tour.hint())
        assertEquals(listOf("timeline"), tour.targets())
        tour.observe(editing.copy(media = 1, selected = TourFacts.MEDIA, panel = "ColorFill"))
        assertEquals(R.string.tutorial_hint_tools, tour.hint())
        tour.observe(editing.copy(media = 1, selected = TourFacts.MEDIA))
        assertEquals(listOf("tool_effects", "tools"), tour.targets())
        assertNull(tour.hint())
    }

    @Test fun undoStepWaitsForAnUndo() {
        val tour = at("undo")
        tour.observe(editing.copy(undoMark = 4))
        tour.observe(editing.copy(undoMark = 0))
        assertEquals("undo", tour.step.id)
        tour.observe(editing.copy(undoMark = 5))
        assertEquals("undo", tour.step.id)
        tour.observe(editing.copy(undoMark = 6))
        assertEquals("safety", tour.step.id)
    }

    @Test fun effectStepWaitsForASliderAfterTheEffectIsAdded() {
        val tour = at("effect")
        val start = editing.copy(media = 1, selected = TourFacts.MEDIA, panel = TourFacts.EFFECTS, tune = 1)
        tour.observe(start)
        assertEquals(listOf("panel"), tour.targets())
        tour.observe(start.copy(browsing = true))
        assertEquals(emptyList<String>(), tour.targets())
        tour.observe(start.copy(effects = 1, tune = 5))
        assertEquals("effect", tour.step.id)
        assertEquals(R.string.tutorial_hint_slider, tour.hint())
        tour.observe(start.copy(effects = 1, tune = 6))
        assertEquals("motion", tour.step.id)
    }

    @Test fun doItAfterTheEffectWasPickedJustMovesOn() {
        val tour = at("effect")
        val start = editing.copy(media = 1, selected = TourFacts.MEDIA, panel = TourFacts.EFFECTS)
        tour.observe(start)
        tour.observe(start.copy(effects = 1, tune = 5))
        assertEquals(emptyList<TourAction>(), tour.doIt())
        tour.observe(start.copy(effects = 1, tune = 5))
        assertEquals("motion", tour.step.id)
    }

    @Test fun exportStepWaitsUntilTheExportScreenIsClosedAgain() {
        val tour = at("export")
        tour.observe(editing)
        tour.observe(editing)
        assertEquals(listOf("export"), tour.targets())
        tour.observe(editing.copy(exporting = true))
        assertEquals("export", tour.step.id)
        assertEquals(R.string.tutorial_hint_export_close, tour.hint())
        tour.observe(editing.copy(exporting = false))
        assertEquals("finish", tour.step.id)
    }

    @Test fun easingPointsAtTheTimelineUntilThePlayheadSitsBetweenKeyframes() {
        val tour = at("easing")
        val keyed = editing.copy(media = 1, keyframes = 2, selected = TourFacts.MEDIA, panel = TourFacts.TRANSFORM)
        tour.observe(keyed)
        assertEquals(listOf("timeline"), tour.targets())
        assertEquals(R.string.tutorial_hint_between, tour.hint())
        tour.observe(keyed.copy(between = true))
        assertEquals(listOf("easing"), tour.targets())
        tour.observe(keyed.copy(between = true, easing = true))
        assertEquals("effect", tour.step.id)
    }

    @Test fun screenAndTimelineStepsLightUpThePlayBarToo() {
        val screen = TourSteps.all.first { it.id == "screen" }
        assertTrue(screen.union)
        assertEquals(listOf("playbar", "timeline"), screen.targets(editing, editing))
    }

    @Test fun resumesFromTheSavedStep() {
        assertEquals("effect", Tour(startAt = "effect").step.id)
        assertEquals("welcome", Tour(startAt = "gone").step.id)
        assertEquals("welcome", Tour(startAt = null).step.id)
    }

    @Test fun skippingEveryStepFinishes() {
        val tour = Tour()
        repeat(TourSteps.all.size) { tour.next() }
        assertTrue(tour.finished)
        assertEquals(emptyList<TourAction>(), tour.observe(editing))
        assertEquals(emptyList<TourAction>(), tour.doIt())
    }

    @Test fun decliningEverythingStillBuildsTheSampleProject() {
        val tour = Tour()
        var facts = editing
        var guard = 0
        while (!tour.finished && guard++ < 100) {
            facts = tour.drive(facts, tour.observe(facts))
            if (tour.finished) break
            val step = tour.step
            if (step.sample != null) {
                facts = facts.copy(adding = true)
                tour.observe(facts)
                facts = tour.drive(facts.copy(adding = true), tour.pickerClosed(false))
                if (tour.step === step) tour.next()
            } else tour.next()
        }
        assertTrue(tour.finished)
        assertTrue(facts.media >= 2)
        assertTrue(facts.audio >= 1)
    }

    @Test fun doingEveryStepForTheUserCompletesThemAll() {
        val tour = Tour()
        var facts = editing
        val completed = ArrayList<String>()
        var guard = 0
        while (!tour.finished && guard++ < 100) {
            facts = tour.drive(facts, tour.observe(facts))
            if (tour.finished) break
            val step = tour.step
            if (tour.canDoIt) {
                facts = tour.drive(facts, tour.doIt())
                if (tour.step !== step) completed += step.id
            } else tour.next()
        }
        assertTrue(tour.finished)
        assertEquals(TourSteps.all.filter { it.doIt.isNotEmpty() }.map { it.id }, completed)
    }

    @Test fun sceneFactsCountWhatTheUserMade() {
        val media = MediaDeclaration(XmlNode("media", mapOf("uri" to "file:///p/media/a.jpg", "width" to "1080", "height" to "1080", "type" to "image/jpeg")))
        var scene = Scene.create("t", 1080, 1080, 30, 0)
        val picture = scene.imageLayer(media, 0, 2)!!
        scene = scene.add(picture, listOf(media))
        val text = Layer(XmlNode("text", linkedMapOf("id" to "77", "startTime" to "0", "endTime" to "999"))).withContent("Hi")
        scene = scene.add(text).add(Layer(XmlNode("audio", linkedMapOf("id" to "78", "startTime" to "0", "endTime" to "999", "src" to "file:///p/media/s.m4a"))))
        val before = sceneFacts(scene)
        assertEquals(1, before.media)
        assertEquals(1, before.texts)
        assertEquals(1, before.audio)
        assertEquals(0, before.keyframes)
        scene = scene.withAnimation(picture.id, MotionSlot.In, "fade", 500)
        val animated = sceneFacts(scene)
        assertEquals(1, animated.motions)
        assertEquals(0, animated.keyframes)
        scene = scene.update(text.id) { l ->
            l.withElement(PropRef.Location, XmlNode("location").withKeyframes(listOf(Keyframe(0f, "0,0,0"), Keyframe(1f, "100,0,0", "cubicBezier 0.42 0 0.58 1"))))
        }
        val keyed = sceneFacts(scene)
        assertEquals(2, keyed.keyframes)
        assertEquals(1, keyed.eased)
        assertTrue(keyed.moves != animated.moves)
    }

    @Test fun cardSitsAboveATargetAtTheBottomOfAPhone() {
        val area = Area(0f, 80f, 1080f, 2300f)
        val hole = Area(0f, 1500f, 1080f, 2300f)
        val (region, side) = CardPlacement.region(area, hole, 10f, 600f, 300f)
        assertEquals(CardSide.Above, side)
        assertTrue(region.bottom <= hole.top)
        val (x, y) = CardPlacement.position(region, side, hole, 900f, 400f, centred = false)
        assertTrue(y + 400f <= hole.top)
        assertTrue(x >= area.left && x + 900f <= area.right)
    }

    @Test fun cardSitsBelowATargetAtTheTop() {
        val area = Area(0f, 80f, 1080f, 2300f)
        val hole = Area(950f, 80f, 1070f, 200f)
        val (region, side) = CardPlacement.region(area, hole, 10f, 600f, 300f)
        assertEquals(CardSide.Below, side)
        val (x, y) = CardPlacement.position(region, side, hole, 900f, 400f, centred = false)
        assertTrue(y >= hole.bottom)
        assertTrue(x + 900f <= area.right)
    }

    @Test fun landscapeUsesTheFreeSide() {
        val area = Area(0f, 0f, 2400f, 1080f)
        val hole = Area(0f, 60f, 1500f, 1000f)
        val (region, side) = CardPlacement.region(area, hole, 10f, 600f, 400f)
        assertEquals(CardSide.End, side)
        val (x, _) = CardPlacement.position(region, side, hole, 800f, 500f, centred = false)
        assertTrue(x >= hole.right)
    }

    @Test fun noTargetCentresOrRestsAtTheBottom() {
        val area = Area(0f, 0f, 1000f, 2000f)
        val (region, side) = CardPlacement.region(area, null, 10f, 600f, 300f)
        assertEquals(CardSide.Free, side)
        assertEquals(800f, CardPlacement.position(region, side, null, 600f, 400f, centred = true).second)
        assertEquals(1600f, CardPlacement.position(region, side, null, 600f, 400f, centred = false).second)
    }

    @Test fun aTargetLeavingOnlyThinStripsGivesTheCardTheWholeArea() {
        val area = Area(0f, 0f, 2400f, 1080f)
        val (region, side) = CardPlacement.region(area, Area(40f, 60f, 2360f, 1020f), 10f, 600f, 300f)
        assertEquals(CardSide.Free, side)
        assertEquals(area, region)
        val (_, y) = CardPlacement.position(region, side, null, 800f, 400f, centred = false)
        assertEquals(680f, y)
    }

    @Test fun aTargetCoveringEverythingStillLeavesRoomForTheCard() {
        val area = Area(0f, 0f, 1000f, 2000f)
        val (region, _) = CardPlacement.region(area, Area(-10f, -10f, 1010f, 2010f), 10f, 600f, 300f)
        assertEquals(area, region)
    }
}
