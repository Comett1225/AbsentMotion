package app.absentmotion

import app.absentmotion.model.Animations
import app.absentmotion.model.Easing
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.MotionRecord
import app.absentmotion.model.MotionSlot
import app.absentmotion.model.Scene
import app.absentmotion.model.TransitionKind
import app.absentmotion.model.XmlNode
import app.absentmotion.model.cutAt
import app.absentmotion.model.cutBetween
import app.absentmotion.model.effects
import app.absentmotion.model.formatFloats
import app.absentmotion.model.keyframes
import app.absentmotion.model.motion
import app.absentmotion.model.withAnimation
import app.absentmotion.model.withKeyframes
import app.absentmotion.model.withTransition
import app.absentmotion.model.withSplit
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import app.absentmotion.i18n.txt

class MotionsTest {
    private fun f(vararg v: Float) = formatFloats(v)

    private fun transform() = XmlNode("transform", children = listOf(
        XmlNode("location", linkedMapOf("value" to f(540f, 960f, 0f))),
        XmlNode("scale", linkedMapOf("value" to f(1f, 1f))),
        XmlNode("rotation", linkedMapOf("value" to f(0f))),
        XmlNode("opacity", linkedMapOf("value" to f(1f))),
    ))

    private fun image(id: Long, start: Int, end: Int) = XmlNode("shape", linkedMapOf("id" to "$id", "label" to "Clip $id", "startTime" to "$start",
        "endTime" to "$end", "fillType" to "media", "fillImage" to "content://img$id", "mediaFillMode" to "fill", "s" to ".rect"), listOf(transform()))

    private fun shape(id: Long, start: Int, end: Int) = XmlNode("shape", linkedMapOf("id" to "$id", "label" to "Box $id", "startTime" to "$start",
        "endTime" to "$end", "fillType" to "color", "s" to ".rect"), listOf(transform()))

    private fun video(id: Long, start: Int, end: Int, inTime: Int, outTime: Int) = videoNode(
        start, end, id, inTime, outTime, uri = "content://vid$id", attrs = mapOf("label" to "Clip $id", "mediaFillMode" to "fill", "s" to ".rect"),
        children = listOf(transform()),
    )

    private fun media(id: Long, ms: Int) = XmlNode("media", linkedMapOf("uri" to "content://vid$id", "type" to "video", "duration" to "${ms * 1000L}"))

    private fun scene(vararg children: XmlNode): Scene {
        val end = children.filter { it.name == "shape" }.maxOf { it["endTime"]!!.toInt() }
        return Scene(XmlNode("scene", linkedMapOf("width" to "1080", "height" to "1920", "fps" to "30", "totalTime" to "$end"), children.toList()))
    }

    private fun Layer.keys(prop: String): List<Keyframe> = node.child("transform")?.child(prop)?.keyframes.orEmpty()
    private fun Layer.ms(t: Float) = startTime + t * (endTime - startTime)
    private fun Layer.made(slot: MotionSlot) = effects.filter { it["gen"] == slot.xml }
    private fun Layer.madeKeys(slot: MotionSlot) = listOf("location", "scale", "rotation", "opacity").associateWith { p -> keys(p).filter { it.gen == slot.xml } }.filterValues { it.isNotEmpty() }

    private fun checkSide(layer: Layer, slot: MotionSlot, inSide: Boolean, from: Float, to: Float, curve: String?) {
        for ((prop, keys) in layer.madeKeys(slot)) {
            assertEquals("$prop has two generated keys", 2, keys.size)
            assertEquals("$prop starts at the window", from, layer.ms(keys[0].t), 1.5f)
            assertEquals("$prop ends at the window", to, layer.ms(keys[1].t), 1.5f)
            assertEquals("$prop easing", curve, keys[1].e)
            assertNull(keys[0].e)
            val rest = (if (inSide) keys[1] else keys[0]).v.split(',').map { it.toFloat() }
            val expected = mapOf("location" to listOf(540f, 960f, 0f), "scale" to listOf(1f, 1f), "rotation" to listOf(0f), "opacity" to listOf(1f)).getValue(prop)
            assertEquals("$prop rests on the layer's own value", expected, rest)
        }
        for (effect in layer.made(slot)) for (property in effect.children.filter { it.name == "property" && it.keyframes.isNotEmpty() }) {
            val keys = property.keyframes
            assertEquals(2, keys.size)
            assertEquals(from, layer.ms(keys[0].t), 1.5f)
            assertEquals(to, layer.ms(keys[1].t), 1.5f)
            assertEquals(0f, (if (inSide) keys[1] else keys[0]).v.toFloat(), 0f)
            assertEquals(curve, keys[1].e)
        }
    }

    @Test fun everyTransitionWritesKeysAroundTheCutAndRemovesCleanly() {
        val start = scene(image(1, 0, 2000), image(2, 2000, 4000))
        for (kind in TransitionKind.entries) {
            val done = start.withTransition(1, 2, kind, 500)
            val a = done.scene.layer(1)!!
            val b = done.scene.layer(2)!!
            assertEquals(MotionRecord(true, kind.id, 500, if (kind.overlap) 250 else 0, 0, 2), a.motion(MotionSlot.Out))
            assertEquals(MotionRecord(true, kind.id, 500, if (kind.overlap) 250 else 0, 0, 1), b.motion(MotionSlot.In))
            assertTrue(kind.id, a.madeKeys(MotionSlot.Out).isNotEmpty() || a.made(MotionSlot.Out).isNotEmpty() ||
                b.madeKeys(MotionSlot.In).isNotEmpty() || b.made(MotionSlot.In).isNotEmpty())
            val (outSide, inSide) = kind.sides(bOnTop = true, bothFade = false)
            if (kind.overlap) {
                assertEquals(kind.id, 2250, a.endTime)
                assertEquals(kind.id, 1750, b.startTime)
                assertEquals(4000, b.endTime)
                checkSide(a, MotionSlot.Out, false, 1750f, 2250f, outSide.curve(false).toString())
                checkSide(b, MotionSlot.In, true, 1750f, 2250f, inSide.curve(true).toString())
            } else {
                assertEquals(kind.id, 2000, a.endTime)
                assertEquals(kind.id, 2000, b.startTime)
                checkSide(a, MotionSlot.Out, false, 1750f, 2000f, outSide.curve(false).toString())
                checkSide(b, MotionSlot.In, true, 2000f, 2250f, inSide.curve(true).toString())
            }
            assertNull(done.note)
            assertEquals(kind.id, start.node, done.scene.withTransition(1, 2, null, 0).scene.node)
        }
    }

    @Test fun crossDissolveFadesOnlyTheTopClipOverPicturesAndBothOverShapes() {
        val pictures = scene(image(1, 0, 2000), image(2, 2000, 4000)).withTransition(1, 2, TransitionKind.CrossDissolve, 500).scene
        assertTrue(pictures.layer(1)!!.keys("opacity").isEmpty())
        assertEquals(listOf(f(0f), f(1f)), pictures.layer(2)!!.keys("opacity").map { it.v })
        assertEquals("cubicBezier 0.42 0.0 0.58 1.0", pictures.layer(2)!!.keys("opacity")[1].e)
        val shapes = scene(shape(1, 0, 2000), shape(2, 2000, 4000)).withTransition(1, 2, TransitionKind.CrossDissolve, 500).scene
        assertEquals(listOf(f(1f), f(0f)), shapes.layer(1)!!.keys("opacity").map { it.v })
        assertEquals(listOf(f(0f), f(1f)), shapes.layer(2)!!.keys("opacity").map { it.v })
        val under = scene(image(2, 2000, 4000), image(1, 0, 2000)).withTransition(1, 2, TransitionKind.CrossDissolve, 500).scene
        assertEquals(listOf(f(1f), f(0f)), under.layer(1)!!.keys("opacity").map { it.v })
        assertTrue(under.layer(2)!!.keys("opacity").isEmpty())
    }

    @Test fun pushMovesBothClipsAFrameWidthAndDipsUseAColourOverlay() {
        val push = scene(image(1, 0, 2000), image(2, 2000, 4000)).withTransition(1, 2, TransitionKind.PushLeft, 500).scene
        assertEquals(listOf(f(540f, 960f, 0f), f(-540f, 960f, 0f)), push.layer(1)!!.keys("location").map { it.v })
        assertEquals(listOf(f(1620f, 960f, 0f), f(540f, 960f, 0f)), push.layer(2)!!.keys("location").map { it.v })
        val dip = scene(image(1, 0, 2000), image(2, 2000, 4000)).withTransition(1, 2, TransitionKind.DipBlack, 1000).scene
        val overlay = dip.layer(1)!!.made(MotionSlot.Out).single()
        assertEquals("absent.solid", overlay["id"])
        assertEquals("#ff000000", overlay.children.first { it["name"] == "color" }["value"])
        assertEquals(listOf(f(0f), f(1f)), overlay.children.first { it["name"] == "amount" }.keyframes.map { it.v })
        assertEquals(listOf(f(1f), f(0f)), dip.layer(2)!!.made(MotionSlot.In).single().children.first { it["name"] == "amount" }.keyframes.map { it.v })
    }

    @Test fun clipsWithoutMediaHandlesAreMovedTogetherAndTheUserIsTold() {
        val start = scene(media(1, 2000), media(2, 5000), video(1, 0, 2000, 0, 2000), video(2, 2000, 4000, 0, 2000))
        val done = start.withTransition(1, 2, TransitionKind.CrossDissolve, 500)
        val a = done.scene.layer(1)!!
        val b = done.scene.layer(2)!!
        assertEquals(2000, a.endTime)
        assertEquals(1500, b.startTime)
        assertEquals(3500, b.endTime)
        assertEquals("0", b.node["inTime"])
        assertEquals(500, b.motion(MotionSlot.In)!!.shift)
        assertNotNull(done.note)
        assertEquals(txt(R.string.library_text_value, listOf(txt(R.string.transition_moved, "Clip 2", "0.5 s"))), done.note)
        assertEquals(start.node, done.scene.withTransition(1, 2, null, 0).scene.node)
    }

    @Test fun splittingAClipLeavesItsInTransitionOnTheFirstHalfAndItsOutTransitionOnTheSecond() {
        val start = scene(media(1, 2100), media(2, 5000), media(3, 5000), video(1, 0, 2000, 0, 2000), video(2, 2000, 4000, 300, 2300), video(3, 4000, 6000, 300, 2300))
        val ab = start.withTransition(1, 2, TransitionKind.PushLeft, 500).scene
        val abc = ab.withTransition(2, 3, TransitionKind.DipBlack, 600).scene
        val split = abc.withSplit(abc.layer(2)!!, 90, 99)!!
        val first = split.layer(2)!!
        val second = split.layer(99)!!
        assertNotNull(first.motion(MotionSlot.In))
        assertNull(first.motion(MotionSlot.Out))
        assertTrue(first.made(MotionSlot.Out).isEmpty() && first.madeKeys(MotionSlot.Out).isEmpty())
        assertNull(second.motion(MotionSlot.In))
        assertEquals(3L, second.motion(MotionSlot.Out)!!.partner)
        assertTrue(second.made(MotionSlot.In).isEmpty() && second.madeKeys(MotionSlot.In).isEmpty())
        assertEquals(99L, split.layer(3)!!.motion(MotionSlot.In)!!.partner)
        val noAb = split.withTransition(1, 2, null, 0).scene
        assertTrue(noAb.layer(2)!!.endTime + 1 >= noAb.layer(99)!!.startTime)
        assertEquals(second.node, noAb.layer(99)!!.node)
        val noBc = split.withTransition(99, 3, null, 0).scene
        assertEquals(ab.layer(3)!!.node, noBc.layer(3)!!.node)
        assertEquals(ab.layer(2)!!.endTime, noBc.layer(99)!!.endTime)
        assertEquals(first.node, noBc.layer(2)!!.node)
    }

    @Test fun mediaHandlesAreUsedBeforeAnythingMoves() {
        val start = scene(media(1, 2100), media(2, 5000), video(1, 0, 2000, 0, 2000), video(2, 2000, 4000, 300, 2300))
        val done = start.withTransition(1, 2, TransitionKind.PushLeft, 500).scene
        val a = done.layer(1)!!
        val b = done.layer(2)!!
        assertEquals(2100, a.endTime)
        assertEquals("2100", a.node["outTime"])
        assertEquals(1600, b.startTime)
        assertEquals("0", b.node["inTime"])
        assertEquals(MotionRecord(true, "push-left", 500, 300, 100, 1), b.motion(MotionSlot.In))
        assertEquals(100, a.motion(MotionSlot.Out)!!.extend)
        assertEquals(start.node, done.withTransition(1, 2, null, 0).scene.node)
    }

    @Test fun aTransitionLongerThanAClipIsShortened() {
        val done = scene(image(1, 0, 800), image(2, 800, 3000)).withTransition(1, 2, TransitionKind.CrossDissolve, 2000)
        assertEquals(1200, done.scene.layer(1)!!.endTime)
        assertEquals(400, done.scene.layer(2)!!.startTime)
        assertEquals(txt(R.string.library_text_value, listOf(txt(R.string.transition_shortened, "0.8 s"))), done.note)
        assertEquals(2000, done.scene.layer(2)!!.motion(MotionSlot.In)!!.ms)
    }

    @Test fun applyingAgainReplacesThePreviousTransition() {
        val start = scene(image(1, 0, 2000), image(2, 2000, 4000))
        val first = start.withTransition(1, 2, TransitionKind.CrossDissolve, 500).scene
        val again = first.withTransition(1, 2, TransitionKind.PushLeft, 1000).scene
        assertEquals(start.withTransition(1, 2, TransitionKind.PushLeft, 1000).scene.node, again.node)
        val longer = first.withTransition(1, 2, TransitionKind.CrossDissolve, 1000).scene
        assertEquals(2500, longer.layer(1)!!.endTime)
        assertEquals(1500, longer.layer(2)!!.startTime)
        assertEquals(2, longer.layer(2)!!.keys("opacity").size)
    }

    @Test fun oneCallChangesBothClipsAndLeavesTheUndoSnapshotUntouched() {
        val start = scene(image(1, 0, 2000), image(2, 2000, 4000))
        val before = start.node.toXml()
        val done = start.withTransition(1, 2, TransitionKind.WhipPan, 600).scene
        assertEquals(before, start.node.toXml())
        assertNotEquals(start.layer(1)!!.node, done.layer(1)!!.node)
        assertNotEquals(start.layer(2)!!.node, done.layer(2)!!.node)
    }

    @Test fun theCutIsFoundNearThePlayheadAndBetweenTwoChosenClips() {
        val s = scene(image(1, 0, 2000), image(2, 2000, 4000), XmlNode("audio", linkedMapOf("id" to "3", "startTime" to "1000", "endTime" to "3000")))
        assertEquals(1L to 2L, s.cutAt(2000)?.let { it.first.id to it.second.id })
        assertEquals(1L to 2L, s.cutAt(2060)?.let { it.first.id to it.second.id })
        assertNull(s.cutAt(2100))
        assertNull(s.cutAt(500))
        assertEquals(1L to 2L, s.cutBetween(2, 1)?.let { it.first.id to it.second.id })
        assertNull(s.cutBetween(1, 3))
        assertNull(scene(image(1, 0, 4000), image(2, 1000, 3000)).cutBetween(1, 2))
        val overlapped = s.withTransition(1, 2, TransitionKind.CrossDissolve, 1000).scene
        assertEquals(1L to 2L, overlapped.cutAt(1600)?.let { it.first.id to it.second.id })
    }

    @Test fun everyInAndOutAnimationWritesEditableKeys() {
        val start = scene(image(1, 1000, 3000))
        for (motion in Animations.InOut) for (slot in listOf(MotionSlot.In, MotionSlot.Out)) {
            val done = start.withAnimation(1, slot, motion.id, 500)
            val layer = done.layer(1)!!
            val inSide = slot == MotionSlot.In
            assertEquals(MotionRecord(false, motion.id, 500), layer.motion(slot))
            assertEquals(motion.looks.map { it.prop }.toSet(), layer.madeKeys(slot).keys)
            assertEquals(motion.fx.size, layer.made(slot).size)
            val curve = motion.curve(inSide).takeUnless { it.isLinear }?.toString()
            if (inSide) checkSide(layer, slot, true, 1000f, 1500f, curve) else checkSide(layer, slot, false, 2500f, 3000f, curve)
            assertEquals("${motion.id} $slot", start.node, done.withAnimation(1, slot, null, 0).node)
        }
    }

    @Test fun animationValuesMirrorBetweenInAndOut() {
        val start = scene(image(1, 0, 2000))
        val slideIn = start.withAnimation(1, MotionSlot.In, "slide-left", 500).layer(1)!!
        assertEquals(listOf(f(1620f, 960f, 0f), f(540f, 960f, 0f)), slideIn.keys("location").map { it.v })
        val slideOut = start.withAnimation(1, MotionSlot.Out, "slide-left", 500).layer(1)!!
        assertEquals(listOf(f(540f, 960f, 0f), f(-540f, 960f, 0f)), slideOut.keys("location").map { it.v })
        assertEquals(listOf(f(0.4f, 0.4f), f(1f, 1f)), start.withAnimation(1, MotionSlot.In, "zoom-in", 500).layer(1)!!.keys("scale").map { it.v })
        assertEquals(listOf(f(1f, 1f), f(1.8f, 1.8f)), start.withAnimation(1, MotionSlot.Out, "zoom-in", 500).layer(1)!!.keys("scale").map { it.v })
        assertEquals("cubicBezier 0.22 1.0 0.36 1.0", slideIn.keys("location")[1].e)
        val mirrored = Easing.parse(slideOut.keys("location")[1].e)
        assertEquals("cubicBezier", mirrored.type)
        listOf(0.64f, 0f, 0.78f, 0f).zip(mirrored.p).forEach { (want, got) -> assertEquals(want, got, 1e-5f) }
        assertEquals("reverse bounce 0.4 0.5", start.withAnimation(1, MotionSlot.Out, "bounce", 500).layer(1)!!.keys("location")[1].e)
    }

    @Test fun inAndOutLiveSideBySideAndReplaceOnlyThemselves() {
        var s = scene(image(1, 0, 2000)).withAnimation(1, MotionSlot.In, "fade", 500).withAnimation(1, MotionSlot.Out, "fade", 500)
        assertEquals(listOf(0f, 0.25f, 0.75f, 1f), s.layer(1)!!.keys("opacity").map { it.t })
        assertEquals(listOf(f(0f), f(1f), f(1f), f(0f)), s.layer(1)!!.keys("opacity").map { it.v })
        s = s.withAnimation(1, MotionSlot.In, "slide-up", 500)
        assertEquals(listOf(0.75f, 1f), s.layer(1)!!.keys("opacity").map { it.t })
        assertEquals(2, s.layer(1)!!.keys("location").size)
        s = s.withAnimation(1, MotionSlot.Out, null, 0)
        assertEquals(f(1f), s.layer(1)!!.node.child("transform")!!.child("opacity")!!["value"])
        assertNull(s.layer(1)!!.motion(MotionSlot.Out))
    }

    @Test fun userKeysInsideTheWindowGiveWayAndOthersStay() {
        val moving = image(1, 0, 2000).let { n ->
            val t = n.child("transform")!!
            n.withChild("transform", t.withChild("location", t.child("location")!!.withKeyframes(listOf(
                Keyframe(0.1f, f(100f, 100f, 0f)), Keyframe(0.6f, f(600f, 100f, 0f))))))
        }
        val s = scene(moving).withAnimation(1, MotionSlot.In, "slide-down", 500)
        val keys = s.layer(1)!!.keys("location")
        assertEquals(listOf(0f, 0.25f, 0.6f), keys.map { it.t })
        assertEquals(listOf("in", "in", null), keys.map { it.gen })
        listOf(250f, 100f, 0f).zip(keys[1].v.split(',').map { it.toFloat() }).forEach { (want, got) -> assertEquals(want, got, 1e-3f) }
        assertEquals(-1820f, keys[0].v.split(',')[1].toFloat(), 1e-3f)
        assertEquals(listOf(0.6f), s.withAnimation(1, MotionSlot.In, null, 0).layer(1)!!.keys("location").map { it.t })
    }

    @Test fun aShortClipGetsTheWholeClipAndDurationsStayInRange() {
        val layer = scene(image(1, 0, 300)).withAnimation(1, MotionSlot.In, "fade", 1000).layer(1)!!
        assertEquals(listOf(0f, 1f), layer.keys("opacity").map { it.t })
        assertEquals(2000, scene(image(1, 0, 3000)).withAnimation(1, MotionSlot.In, "fade", 9000).layer(1)!!.motion(MotionSlot.In)!!.ms)
        assertEquals(100, scene(image(1, 0, 3000)).withAnimation(1, MotionSlot.In, "fade", 1).layer(1)!!.motion(MotionSlot.In)!!.ms)
    }

    @Test fun loopsUseMoveEffectsTimedByTheDuration() {
        val start = scene(image(1, 0, 4000))
        val expected = mapOf("pulse" to "absent.pulsesize", "wobble" to "absent.swing", "float" to "absent.oscillate", "rotate" to "absent.spin")
        for ((id, _) in Animations.Loops) {
            val s = start.withAnimation(1, MotionSlot.Loop, id, 500)
            val effect = s.layer(1)!!.made(MotionSlot.Loop).single()
            assertEquals(expected[id], effect["id"])
            val timing = effect.children.first { it["name"] == if (id == "rotate") "rpm" else "frequency" }["value"]
            assertEquals(if (id == "rotate") f(120f) else f(2f), timing)
            val other = s.withAnimation(1, MotionSlot.Loop, if (id == "pulse") "wobble" else "pulse", 1000)
            assertEquals(1, other.layer(1)!!.made(MotionSlot.Loop).size)
            assertEquals(start.node, s.withAnimation(1, MotionSlot.Loop, null, 0).node)
        }
    }

    @Test fun anAnimationOnATransitionEndRemovesTheWholeTransition() {
        val start = scene(shape(1, 0, 2000), shape(2, 2000, 4000))
        val joined = start.withTransition(1, 2, TransitionKind.Spin, 500).scene
        val s = joined.withAnimation(2, MotionSlot.In, "fade", 500)
        assertNull(s.layer(1)!!.motion(MotionSlot.Out))
        assertEquals(2000, s.layer(1)!!.endTime)
        assertEquals(2000, s.layer(2)!!.startTime)
        assertTrue(s.layer(1)!!.madeKeys(MotionSlot.Out).isEmpty())
        assertEquals("a fade 500", s.layer(2)!!.node["genIn"])
        assertTrue(s.layer(2)!!.keys("rotation").isEmpty())
    }

    @Test fun projectsStayValidXmlAndOldBuildsSeeOrdinaryKeysAndEffects() {
        var s = scene(image(1, 0, 2000), image(2, 2000, 4000), shape(3, 0, 4000))
        s = s.withTransition(1, 2, TransitionKind.Glitch, 600).scene.withTransition(1, 2, TransitionKind.WhipPan, 600).scene
        s = s.withAnimation(3, MotionSlot.In, "blur", 700).withAnimation(3, MotionSlot.Out, "bounce", 700).withAnimation(3, MotionSlot.Loop, "float", 1500)
        val xml = s.toXml()
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val reread = Scene.parse(xml)
        assertEquals(s.node.with("modifiedTime", null), reread.node.with("modifiedTime", null))
        val ours = setOf("gen", "genIn", "genOut", "genLoop")
        fun strip(n: XmlNode): XmlNode = n.copy(attrs = n.attrs.filterKeys { it !in ours }, children = n.children.map(::strip))
        val old = strip(reread.node)
        fun kfs(n: XmlNode): List<Map<String, String>> = (if (n.name == "kf") listOf(n.attrs) else emptyList()) + n.children.flatMap(::kfs)
        assertEquals(kfs(reread.node).map { it - "gen" }, kfs(old))
        assertTrue(kfs(old).all { it.keys.all { k -> k in setOf("t", "v", "e") } && "t" in it && "v" in it })
        val known = JSONArray(TestData.project.resolve("app/src/main/assets/effects/index.json").readText()).let { a -> List(a.length()) { "absent." + a.getString(it) } }.toSet()
        val used = Scene(old).layers.flatMap { l -> l.effects.map { it["id"] } }.toSet()
        assertTrue(used.toString(), used.isNotEmpty() && used.all { it in known })
    }
}
