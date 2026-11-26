package app.absentmotion.editor

import app.absentmotion.shapeLayer
import app.absentmotion.shapeNode
import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class StylePasteTest {
    @Test fun videoStyleIncludesTrimSpeedAndLoopAndStillFillsClearThem() {
        val source = shapeLayer(attrs = mapOf("fillType" to "media", "fillVideo" to "content://clip", "inTime" to "100", "outTime" to "900", "speed" to "0.5", "loop" to "true"),
            children = listOf(XmlNode("gain", mapOf("value" to "0.4")), XmlNode("speedMap", mapOf("value" to "0.5"))))
        val target = Scene.create("p", 100, 100, 30, 0).add(shapeLayer())
        val pasted = target.withPastedStyle(listOf(1L), 0, source, emptyList(), setOf(StylePart.ColorFill)).scene.layer(1)!!
        for (attr in listOf("inTime", "outTime", "speed", "loop")) assertEquals(attr, source.node[attr], pasted.node[attr])
        assertEquals(source.node.child("speedMap"), pasted.node.child("speedMap"))
        assertEquals(source.node.child("gain"), pasted.node.child("gain"))
        val cleared = Scene(target.node.copy(children = target.node.children.filterNot { it.name in Scene.LAYER_TAGS } + pasted.node))
            .withPastedStyle(listOf(1L), 0, shapeLayer(), emptyList(), setOf(StylePart.ColorFill)).scene.layer(1)!!
        assertNull(cleared.node["fillVideo"])
        assertNull(cleared.node["inTime"])
        assertNull(cleared.node.child("speedMap"))
    }

    @Test fun defaultOpacityReplacesDimmedOpacity() {
        val target = shapeLayer(children = listOf(XmlNode("transform", children = listOf(XmlNode("opacity", mapOf("value" to "0.3")), XmlNode("location", mapOf("value" to "12,34,0"))))))
        val out = Scene.create("p", 100, 100, 30, 0).add(target).withPastedStyle(listOf(1L), 0, shapeLayer(), emptyList(), setOf(StylePart.Blending)).scene.layer(1)!!
        assertNull(out.node.child("transform")?.child("opacity"))
        assertEquals("12,34,0", out.node.child("transform")?.child("location")?.get("value"))
    }

    @Test fun blendingOnAFadedClipTakesTheOpacityAndKeepsTheFade() {
        val faded = Scene.create("p", 100, 100, 30, 0).add(box(1, 0, 2000)).withAnimation(1, MotionSlot.In, "fade", 400)
        val source = box(9, 0, 2000, children = listOf(XmlNode("transform", children = listOf(XmlNode("opacity", mapOf("value" to "0.5"))))))
        val out = faded.paste(source, setOf(StylePart.Blending), 1).scene.layer(1)!!
        assertEquals("fade", out.motion(MotionSlot.In)?.id)
        val keys = out.node.child("transform")!!.child("opacity")!!.keyframes
        assertTrue(keys.any { it.gen != null })
        assertEquals(0.5f, keys.last().v.toFloat(), 0.001f)
        val all = faded.paste(source, StylePart.entries.toSet(), 1).scene.layer(1)!!
        assertNull(all.motion(MotionSlot.In))
        assertEquals("0.5", all.node.child("transform")?.child("opacity")?.get("value"))
    }

    @Test fun fillPasteLeavesAudioAndGroupTimingAlone() {
        val audio = Layer(XmlNode("audio", mapOf("id" to "1", "startTime" to "0", "endTime" to "999", "src" to "file:///a.wav", "inTime" to "200", "speed" to "2"), listOf(XmlNode("gain", mapOf("value" to "0.3")))))
        val scene = Scene.create("p", 100, 100, 30, 0).add(audio)
        val out = scene.withPastedStyle(listOf(1L), 0, shapeLayer(), emptyList(), setOf(StylePart.ColorFill)).scene.layer(1)!!
        assertEquals("200", out.node["inTime"])
        assertEquals("2", out.node["speed"])
        assertNotNull(out.node.child("gain"))
    }

    private fun box(id: Long, start: Int, end: Int, kind: String = "shape", children: List<XmlNode> = emptyList()) =
        Layer(shapeNode(kind, mapOf("fillType" to "color", "s" to ".rect"), children, id, start, end))

    private fun effect(id: String, gen: String? = null, src: String? = null) =
        XmlNode("effect", listOfNotNull("id" to id, "hidden" to "false", gen?.let { "gen" to it }, src?.let { "src" to it }).toMap())

    private fun clips(): Scene = listOf(box(1, 0, 2000), box(2, 2000, 4000), box(3, 4000, 6000), box(4, 6000, 8000))
        .fold(Scene.create("p", 1080, 1920, 30, 0)) { s, l -> s.add(l) }
        .withTransition(1, 2, TransitionKind.CrossDissolve, 500).scene

    private fun Scene.paste(from: Layer, parts: Set<StylePart>, vararg ids: Long, media: List<MediaDeclaration> = emptyList()) =
        withPastedStyle(ids.toList(), 0, from, media, parts)

    @Test fun effectsReplaceTheTargetsOwnAndBringTheirMedia() {
        val source = box(9, 0, 2000, "text", listOf(effect("absent.blur"), effect("absent.textblips", src = "u9"), effect("absent.solid", gen = "in")))
        val target = Scene.create("p", 100, 100, 30, 0).add(box(1, 0, 2000, "text", listOf(effect("absent.glow"), effect("absent.blur", gen = "out"))))
        val media = listOf(MediaDeclaration(XmlNode("media", mapOf("uri" to "u9", "filename" to "voice.wav"))))
        val out = target.paste(source, setOf(StylePart.Effects), 1, media = media).scene
        assertEquals(listOf("absent.blur:out", "absent.blur:null", "absent.textblips:null"), out.layer(1)!!.effects.map { "${it["id"]}:${it["gen"]}" })
        assertEquals("voice.wav", out.media("u9")?.filename)
    }

    @Test fun animationsAndTransitionsPasteOntoTheNextClipAndUndoInOneStep() {
        val start = clips().withAnimation(1, MotionSlot.Loop, "pulse", 1000)
        val source = start.layer(1)!!
        val pasted = start.paste(source, setOf(StylePart.Motions), 3)
        val out = pasted.scene
        assertEquals(0, pasted.unpaired)
        assertEquals(MotionRecord(true, "cross-dissolve", 500, 250, 0, 4), out.layer(3)!!.motion(MotionSlot.Out))
        assertEquals(3L, out.layer(4)!!.motion(MotionSlot.In)?.partner)
        assertEquals("pulse", out.layer(3)!!.motion(MotionSlot.Loop)?.id)
        assertTrue(out.layer(3)!!.node.child("transform")!!.child("opacity")!!.keyframes.any { it.gen == "out" })
        assertEquals(start.layer(1), out.layer(1))
        assertEquals(start.layer(2), out.layer(2))
        var current = start
        val history = EditHistory({ current }) { next, _ -> current = next }
        history.edit(null, out)
        history.undo()
        assertEquals(start, current)
    }

    @Test fun aTransitionWithNoClipToPairWithIsSkippedAndCounted() {
        val start = clips()
        val pasted = start.paste(start.layer(1)!!, setOf(StylePart.Motions), 4)
        assertEquals(1, pasted.unpaired)
        assertEquals(start.layer(4), pasted.scene.layer(4))
    }

    @Test fun aTransitionNeverTakesAClipAlreadyPairedWithAnother() {
        val start = clips().add(box(5, 2250, 3000))
        val pasted = start.paste(start.layer(2)!!, setOf(StylePart.Motions), 5)
        assertEquals(1, pasted.unpaired)
        assertEquals(start, pasted.scene)
    }

    @Test fun transitionsBetweenChosenClipsStayPaired() {
        val start = clips().add(box(5, 8000, 10000))
        val out = start.paste(start.layer(1)!!, setOf(StylePart.Motions), 3, 4).scene
        assertEquals(4L, out.layer(3)!!.motion(MotionSlot.Out)?.partner)
        assertEquals(3L, out.layer(4)!!.motion(MotionSlot.In)?.partner)
        assertEquals(5L, out.layer(4)!!.motion(MotionSlot.Out)?.partner)
        assertEquals(4L, out.layer(5)!!.motion(MotionSlot.In)?.partner)
        val all = start.paste(start.layer(1)!!, StylePart.entries.toSet(), 3, 4).scene.layer(4)!!
        assertEquals(3L, all.motion(MotionSlot.In)?.partner)
        assertTrue(all.node.child("transform")!!.child("opacity")!!.keyframes.any { it.gen == "in" })
    }

    @Test fun audioCamerasAndNullsKeepTheirsAndAreCounted() {
        val audio = Layer(XmlNode("audio", mapOf("id" to "2", "startTime" to "0", "endTime" to "999", "src" to "file:///a.wav")))
        val scene = Scene.create("p", 100, 100, 30, 0).add(audio).add(box(3, 0, 999, "nullobj", listOf(effect("absent.glow")))).add(box(4, 0, 999))
        val source = box(9, 0, 999, children = listOf(effect("absent.blur"))).let { Scene.create("p", 100, 100, 30, 0).add(it).withAnimation(9, MotionSlot.In, "fade", 300).layer(9)!! }
        val pasted = scene.paste(source, StylePart.entries.toSet(), 2, 3, 4)
        assertEquals(2, pasted.skipped)
        assertTrue(pasted.scene.layer(2)!!.effects.isEmpty())
        assertEquals(listOf("absent.glow"), pasted.scene.layer(3)!!.effects.map { it["id"] })
        for (id in listOf(2L, 3L)) assertNull(pasted.scene.layer(id)!!.motion(MotionSlot.In))
        assertEquals(listOf("absent.blur"), pasted.scene.layer(4)!!.effects.filter { it["gen"] == null }.map { it["id"] })
        assertEquals("fade", pasted.scene.layer(4)!!.motion(MotionSlot.In)?.id)
    }

    @Test fun eachPartPastesOnlyItsPart() {
        val styled = box(9, 0, 2000, children = listOf(XmlNode("fillColor", mapOf("value" to "#ff0000")), XmlNode("border", mapOf("size" to "4")), effect("absent.blur"),
            XmlNode("transform", children = listOf(XmlNode("opacity", mapOf("value" to "0.5"))))))
        val source = Scene.create("p", 100, 100, 30, 0).add(styled).withAnimation(9, MotionSlot.In, "fade", 300).layer(9)!!
        val target = Scene.create("p", 100, 100, 30, 0).add(box(1, 0, 2000, children = listOf(effect("absent.glow"))))
        val fx = target.paste(source, setOf(StylePart.Effects), 1).scene.layer(1)!!
        assertEquals(listOf("absent.blur"), fx.effects.map { it["id"] })
        assertNull(fx.motion(MotionSlot.In))
        assertNull(fx.node.child("border"))
        assertNull(fx.node.child("fillColor"))
        val blending = target.paste(source, setOf(StylePart.Blending), 1).scene.layer(1)!!
        assertEquals(0.5f, blending.node.child("transform")?.child("opacity")?.get("value")?.toFloat())
        assertTrue(blending.node.child("transform")!!.child("opacity")!!.keyframes.isEmpty())
        assertNull(blending.motion(MotionSlot.In))
        assertEquals(listOf("absent.glow"), blending.effects.map { it["id"] })
        val motions = target.paste(source, setOf(StylePart.Motions), 1).scene.layer(1)!!
        assertEquals("fade", motions.motion(MotionSlot.In)?.id)
        assertEquals(listOf("absent.glow"), motions.effects.map { it["id"] })
        assertNull(motions.node.child("border"))
        val all = target.paste(source, StylePart.entries.toSet(), 1).scene.layer(1)!!
        assertEquals(listOf("absent.blur"), all.effects.map { it["id"] })
        assertEquals("fade", all.motion(MotionSlot.In)?.id)
        assertNotNull(all.node.child("border"))
    }
}
