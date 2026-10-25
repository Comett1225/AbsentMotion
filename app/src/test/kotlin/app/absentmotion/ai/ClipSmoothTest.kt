package app.absentmotion.ai

import app.absentmotion.editor.withClipEdgesAt
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.MotionBlur
import app.absentmotion.export.SmoothLevel
import app.absentmotion.export.Smoothing
import app.absentmotion.export.frameCount
import app.absentmotion.export.frameTimeMs
import app.absentmotion.media.ptsAt
import app.absentmotion.media.smoothFrame
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.exportFrameMs
import app.absentmotion.model.layerTracks
import app.absentmotion.model.retimedVideo
import app.absentmotion.model.trimmedTo
import app.absentmotion.model.videoTimeUs
import app.absentmotion.videoLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipSmoothTest {
    private val media = MediaDeclaration(XmlNode("media", linkedMapOf("uri" to "raw.mp4", "width" to "1920", "height" to "1080", "fps" to "30.00",
        "duration" to "4000000", "orientation" to "0")))

    private fun clip(speed: String, out: Int = 4000) = Layer(XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0",
        "endTime" to "${(out / speed.toFloat()).toInt()}", "fillType" to "media", "fillVideo" to "raw.mp4", "inTime" to "0", "outTime" to "$out", "speed" to speed)))

    @Test fun slowClipsGetEnoughFramesForTheProjectAndNormalClipsOnlyBlur() {
        val flow = Smoothing(FrameInterpolation.Flow)
        assertEquals(120, smoothPlan(clip("0.25"), media, 30, flow)!!.fps)
        assertEquals(60, smoothPlan(clip("1"), media, 60, flow)!!.fps)
        assertEquals(240, smoothPlan(clip("0.01"), media, 30, flow)!!.fps)
        assertNull(smoothPlan(clip("1"), media, 30, flow))
        assertNull(smoothPlan(clip("0.25"), media, 30, Smoothing(blur = MotionBlur.Off)))
        val blurOnly = smoothPlan(clip("2"), media, 30, Smoothing(blur = MotionBlur.Medium))!!
        assertEquals(30, blurOnly.fps)
        assertEquals(2f, blurOnly.blurScale, 1e-3f)
        val slow = smoothPlan(clip("0.25"), media, 30, flow)!!
        assertEquals(1f, slow.blurScale, 1e-3f)
        assertEquals(1920 to 1080, slow.width to slow.height)
        val big = smoothPlan(clip("0.5"), MediaDeclaration(media.node.with("width", "3840").with("height", "2160")), 30, flow)!!
        assertEquals(1920 to 1080, big.width to big.height)
        val turned = smoothPlan(clip("0.5"), MediaDeclaration(media.node.with("orientation", "90")), 30, flow)!!
        assertEquals(1920 to 1080, turned.width to turned.height)
        assertEquals(4.0, slow.seconds, 1e-9)
        assertEquals(481, slow.frames)
    }

    @Test fun everySmoothDecodesTheSourceOnceInOrder() {
        val stamps = LongArray(120) { it * 1_000_000L / 30 }
        for (speed in listOf("1", "0.5", "0.3", "2")) for (start in listOf(0, 1000)) for (method in FrameInterpolation.entries.filter { it != FrameInterpolation.Ai })
            for (level in SmoothLevel.entries) for (blur in listOf(MotionBlur.Off, MotionBlur.High)) {
                val layer = clip(speed).let { it.copy(node = it.node.with("inTime", "$start")) }
                val smoothing = Smoothing(method, level, blur)
                val plan = smoothPlan(layer, media, 60, smoothing) ?: continue
                val scene = smoothScene(layer.mediaFill!!, media, plan)
                val track = scene.layerTracks().single()
                val case = "$speed from $start $smoothing"
                val imported = mutableSetOf<Long>()
                val held = mutableSetOf<Long>()
                var newest = -1L
                for (k in 0 until minOf(plan.frames, frameCount(scene, plan.fps))) {
                    val mapped = track.timeAt(exportFrameMs(frameTimeMs(scene, plan.fps, k), plan.fps), false) ?: continue
                    val frame = track.layer.smoothFrame(mapped.ms, scene, stamps, plan.fps, mapped.rate, smoothing.copy(blurScale = plan.blurScale)) {
                        stamps.ptsAt(track.layer.videoTimeUs(mapped.ms.toInt(), scene))
                    }
                    assertTrue("$case frame $k is drawn without the frame window", frame != null)
                    val wanted = setOf(frame!!.before, frame.pair.a, frame.pair.b, frame.after)
                    for (pts in wanted) if (pts !in held) {
                        assertTrue("$case frame $k needs $pts again", imported.add(pts))
                        assertTrue("$case frame $k goes back to $pts after $newest", pts > newest)
                        newest = pts
                        held += pts
                    }
                    held.retainAll(wanted)
                }
                assertTrue(case, imported.size > 1)
            }
    }

    @Test fun aSmoothedTrimmedClipPlaysFromTheStartOfItsOwnFileAndRestoresInTheOriginalsTime() {
        val raw = videoLayer(0, 3000, inTime = 4000, outTime = 7000, uri = "raw.mp4")
        val original = MediaDeclaration(XmlNode("media", mapOf("uri" to "raw.mp4")))
        assertEquals(raw, raw.smoothSource(null))
        val first = raw.withSmoothed("one.mp4", "raw.mp4", "Flow:Medium:Off", original, raw.smoothSource(null))
        assertEquals(listOf("one.mp4", "0", "3000"), listOf(first.mediaFill!!.uri, "${first.mediaFill!!.inTime}", "${first.mediaFill!!.outTime}"))
        assertEquals(4000, first.aiMarks.single().shift)
        val tighter = first.copy(node = first.node.with("inTime", "500").with("outTime", "2500"))
        val base = tighter.smoothSource(tighter.aiMarks.single())
        assertEquals(listOf("raw.mp4", "4500", "6500"), listOf(base.mediaFill!!.uri, "${base.mediaFill!!.inTime}", "${base.mediaFill!!.outTime}"))
        val second = tighter.withSmoothed("two.mp4", "raw.mp4", "Blend:High:Off", null, base)
        assertEquals(listOf("two.mp4", "0", "2000"), listOf(second.mediaFill!!.uri, "${second.mediaFill!!.inTime}", "${second.mediaFill!!.outTime}"))
        val mark = second.aiMarks.single()
        assertEquals(4500, mark.shift)
        assertEquals("raw.mp4", mark.original)
        assertEquals(original, mark.media)
        val back = second.withoutAiMark(AiKind.Smooth).mediaFill!!
        assertEquals(listOf("raw.mp4", "4500", "6500"), listOf(back.uri, "${back.inTime}", "${back.outTime}"))
    }

    @Test fun restoringBelowASmoothedResultCountsEveryShiftOnTheWayDown() {
        val raw = videoLayer(0, 3000, inTime = 4000, outTime = 7000, uri = "raw.mp4")
        val enhanced = raw.copy(node = raw.node.with("fillVideo", "enhanced.mp4")).withAiMark(AiKind.Enhance, "raw.mp4", "enhanced.mp4", "upscale2")
        val smoothed = enhanced.withSmoothed("smooth.mp4", "enhanced.mp4", "Flow:Medium:Off", null, enhanced.smoothSource(null))
        assertEquals(listOf(0, 3000), smoothed.mediaFill!!.let { listOf(it.inTime, it.outTime) })
        assertEquals(4000, smoothed.aiMarks.first { it.kind == AiKind.Smooth }.shift)
        val everything = smoothed.withoutAiMark(AiKind.Enhance).mediaFill!!
        assertEquals(listOf("raw.mp4", "4000", "7000"), listOf(everything.uri, "${everything.inTime}", "${everything.outTime}"))
        val onlySmooth = smoothed.withoutAiMark(AiKind.Smooth).mediaFill!!
        assertEquals(listOf("enhanced.mp4", "4000", "7000"), listOf(onlySmooth.uri, "${onlySmooth.inTime}", "${onlySmooth.outTime}"))
        val smoothFirst = raw.withSmoothed("smooth.mp4", "raw.mp4", "Flow:Medium:Off", null, raw.smoothSource(null))
        val onTop = smoothFirst.copy(node = smoothFirst.node.with("fillVideo", "enhanced.mp4")).withAiMark(AiKind.Enhance, "smooth.mp4", "enhanced.mp4", "upscale2")
        val down = onTop.withoutAiMark(AiKind.Smooth).mediaFill!!
        assertEquals(listOf("raw.mp4", "4000", "7000"), listOf(down.uri, "${down.inTime}", "${down.outTime}"))
    }

    @Test fun aSmoothedClipThatStartedAtZeroNeedsNoShift() {
        val raw = videoLayer(0, 3000, inTime = 0, outTime = null, uri = "raw.mp4")
        val smoothed = raw.withSmoothed("one.mp4", "raw.mp4", "Flow:Medium:Off", null, raw.smoothSource(null))
        assertEquals(null, smoothed.aiMarks.single().node["shift"])
        assertEquals(raw.node.with("fillVideo", "one.mp4"), smoothed.node.copy(children = smoothed.node.children.filterNot { it.name == "ai" }))
        assertEquals(raw.node.with("fillVideo", "raw.mp4"), smoothed.withoutAiMark(AiKind.Smooth).node)
    }

    @Test fun theChosenSettingsAreStoredWithTheMark() {
        for (s in listOf(Smoothing(), Smoothing(FrameInterpolation.Flow, SmoothLevel.High, MotionBlur.Low), Smoothing(FrameInterpolation.Blend, SmoothLevel.Low))) {
            assertEquals(s, smoothingOf(s.option))
        }
        assertNull(smoothingOf("Flow:Huge:Off"))
        assertNull(smoothingOf(null))
    }

    private val raw = MediaDeclaration(XmlNode("media", linkedMapOf("uri" to "raw.mp4", "width" to "1920", "height" to "1080", "fps" to "30.00", "duration" to "10000000")))

    private fun smoothed(option: String = "Flow:Medium:Off", facts: Boolean = true): Scene {
        val scene = Scene.parse("""<scene width="100" height="100" fps="60"/>""").withMedia(raw)
            .add(videoLayer(1000, 4999, inTime = 2000, outTime = 6000, uri = "raw.mp4", id = 1))
        val base = scene.layer(1)!!
        val plan = smoothPlan(base, raw, 60, smoothingOf(option)!!)!!
        val result = MediaDeclaration(XmlNode("media", linkedMapOf("uri" to "one.mp4", "fps" to "${plan.fps}", "duration" to "${(plan.seconds * 1_000_000).toLong()}")))
        return scene.update(1) { it.withSmoothed("one.mp4", "raw.mp4", option, raw, base, if (facts) smoothFacts(base, plan, 60) else emptyMap()) }.withMedia(result)
    }

    private fun Scene.trimmed(start: Int, end: Int): Scene = withClip(1, trimmedClip(layer(1)!!, start, end))

    @Test fun aSmoothedClipIsOnlyOutOfDateWhenItNeedsFramesTheBakeDoesNotHave() {
        val scene = smoothed()
        val layer = scene.layer(1)!!
        assertNull(scene.smoothChange(layer))
        assertNull(scene.trimmed(2000, 4999).smoothChange(scene.trimmed(2000, 4999).layer(1)!!))
        val half = scene.update(1) { it.trimmedTo(1000, 2999) }
        assertNull(half.smoothChange(half.layer(1)!!))
        val moved = scene.update(1) { it.movedTo(7000).withTransform("scale", 3f, 3f).withRotation(40f) }
        assertNull(moved.smoothChange(moved.layer(1)!!))
        val back = scene.trimmed(2000, 4999).trimmed(1000, 4999)
        assertEquals("one.mp4", back.layer(1)!!.mediaFill!!.uri)
        assertNull(back.smoothChange(back.layer(1)!!))
    }

    @Test fun draggingATrimPastTheBakeShowsTheOriginalUntilItIsSmoothedAgain() {
        val scene = smoothed()
        val longer = scene.trimmed(1000, 5299)
        val layer = longer.layer(1)!!
        assertEquals(listOf("raw.mp4", "2000", "6299"), layer.mediaFill!!.let { listOf(it.uri, "${it.inTime}", "${it.outTime}") })
        assertEquals(raw, longer.media("raw.mp4"))
        val mark = layer.aiMarks.single()
        assertEquals(0, mark.shift)
        assertEquals("one.mp4", mark.result)
        assertEquals(layer, layer.smoothSource(mark))
        val small = longer.smoothChange(layer)!!
        assertEquals(300.0, small.amountMs, 1.0)
        assertTrue(small.small)
        val earlier = scene.trimmed(0, 4999)
        assertEquals(1000, earlier.layer(1)!!.mediaFill!!.inTime)
        val big = earlier.smoothChange(earlier.layer(1)!!)!!
        assertEquals(1000.0, big.amountMs, 1.0)
        assertFalse(big.small)
        assertEquals("raw.mp4", earlier.withoutAiMark(1, AiKind.Smooth).layer(1)!!.mediaFill!!.uri)
        assertTrue(earlier.withoutAiMark(1, AiKind.Smooth).layer(1)!!.aiMarks.isEmpty())
        assertEquals(9000, scene.trimmedClip(scene.layer(1)!!, 1000, 20_000).endTime)
        val again = longer.update(1) { it.withSmoothed("two.mp4", "raw.mp4", "Flow:Medium:Off", raw, layer) }.layer(1)!!
        assertEquals(listOf("two.mp4", "0", "4299"), again.mediaFill!!.let { listOf(it.uri, "${it.inTime}", "${it.outTime}") })
        assertEquals(2000, again.aiMarks.single().shift)
        val edges = scene.withClipEdgesAt(listOf(1L), 330, end = true, extend = true)
        assertEquals("raw.mp4", edges.layer(1)!!.mediaFill!!.uri)
        assertEquals(scene.frameEnd(330), edges.layer(1)!!.endTime)
    }

    @Test fun slowingDownNeedsMoreFramesAndSpeedingUpOnlyMattersForMotionBlur() {
        val scene = smoothed()
        val slower = scene.update(1) { it.retimedVideo(1000, 1000 + 4210) }
        val small = slower.smoothChange(slower.layer(1)!!)!!
        assertTrue("${small.amountMs}", small.small)
        val half = scene.update(1) { it.retimedVideo(1000, 8999) }
        assertFalse(half.smoothChange(half.layer(1)!!)!!.small)
        val faster = scene.update(1) { it.retimedVideo(1000, 2999) }
        assertNull(faster.smoothChange(faster.layer(1)!!))
        val blurred = smoothed("Off:Medium:Medium")
        val quick = blurred.update(1) { it.retimedVideo(1000, 2999) }
        assertTrue(quick.smoothChange(quick.layer(1)!!) != null)
        val ai = smoothed("Ai:Low:Off").let { s -> s.update(1) { it.retimedVideo(1000, 1000 + 4210) } }
        assertFalse(ai.smoothChange(ai.layer(1)!!)!!.small)
        val old = smoothed(facts = false)
        val oldSlower = old.update(1) { it.retimedVideo(1000, 8999) }
        assertNull(oldSlower.smoothChange(oldSlower.layer(1)!!))
        val oldLonger = old.trimmed(1000, 5299)
        assertFalse(oldLonger.smoothChange(oldLonger.layer(1)!!)!!.small)
    }

    @Test fun restoringASmoothClipBringsBackTheOriginalAndItsMediaDetails() {
        val scene = Scene.parse("""<scene width="100" height="100" fps="30"><media uri="raw.mp4" width="1920" height="1080" fps="30.00" duration="4000000"/>
            <shape id="1" startTime="0" endTime="3999" fillType="media" fillVideo="raw.mp4" s=".rect"/></scene>""")
        val original = scene.media("raw.mp4")!!
        val result = MediaDeclaration(XmlNode("media", linkedMapOf("uri" to "smooth.mp4", "fps" to "60.00")))
        val smoothed = scene.update(1) { it.copy(node = it.node.with("fillVideo", "smooth.mp4")).withAiMark(AiKind.Smooth, "raw.mp4", "smooth.mp4", "Flow:Medium:Off", original) }
            .withMedia(result)
        assertNull(smoothed.media("raw.mp4"))
        val saved = Scene.parse(smoothed.node.toXml())
        val mark = saved.layer(1)!!.aiMarks.single()
        assertEquals(AiKind.Smooth, mark.kind)
        assertTrue(mark.restorable)
        assertEquals(original, mark.media)
        val again = saved.update(1) { it.copy(node = it.node.with("fillVideo", "second.mp4")).withAiMark(AiKind.Smooth, "raw.mp4", "second.mp4", "Blend:High:Off", null) }
        assertEquals(original, again.layer(1)!!.aiMarks.single().media)
        val restored = saved.withoutAiMark(1, AiKind.Smooth)
        assertEquals("raw.mp4", restored.layer(1)!!.node["fillVideo"])
        assertEquals(original, restored.media("raw.mp4"))
        assertNull(restored.media("smooth.mp4"))
        assertTrue(restored.layer(1)!!.aiMarks.isEmpty())
    }
}
