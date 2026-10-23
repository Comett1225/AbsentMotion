package app.absentmotion.editor

import app.absentmotion.sceneOf
import app.absentmotion.shapeNode
import app.absentmotion.engine.SnapGuide
import app.absentmotion.model.Layer
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.blend
import app.absentmotion.ui.editor.panels.Snap
import app.absentmotion.ui.editor.panels.SnapAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAndSnapTest {
    private fun layer(id: Long, start: Int, end: Int, x: Float = 0f, y: Float = 0f, parent: Long? = null, extra: Map<String, String> = emptyMap()) =
        shapeNode(
            id = id, start = start, end = end, parent = parent, attrs = mapOf("label" to "L$id") + extra,
            children = listOf(XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "$x,$y,0"))))),
        )

    private fun scene(vararg children: XmlNode) = sceneOf(*children, width = 1080, height = 1920)

    private val project = scene(
        XmlNode("media", mapOf("uri" to "u1", "filename" to "pic.png")),
        layer(1, 0, 999),
        XmlNode("bookmark", mapOf("t" to "100")),
        layer(2, 500, 1999, extra = mapOf("fillType" to "media", "fillImage" to "u1")),
        layer(3, 200, 2999),
        layer(4, 0, 999),
    ).let { Scene(it.node.with("totalTime", "2999")) }

    private fun Scene.group() = layers.single { it.kind == "embedScene" }

    @Test fun theChosenLayersMoveIntoAGroupAtTheTopOnesPlace() {
        val out = project.grouped(setOf(2, 3), null, 77, "Group 1")
        assertEquals(listOf(1L, 77L, 4L), out.layers.map { it.id })
        val group = out.group()
        assertEquals("Group 1", group.label)
        assertEquals(200, group.startTime)
        assertEquals(2999, group.endTime)
        val nested = group.nested!!
        assertEquals(1080, nested.width); assertEquals(1920, nested.height); assertEquals(30, nested.fps)
        assertEquals(2799, nested.totalTime)
        assertEquals(0, nested.bgColor)
        assertEquals(540f, group.location[0]); assertEquals(960f, group.location[1])
        assertEquals(listOf(2L, 3L), nested.layers.map { it.id })
        assertEquals(listOf(300 to 1799, 0 to 2799), nested.layers.map { it.startTime to it.endTime })
        assertEquals("pic.png", out.media("u1")?.filename)
        assertEquals(listOf("media", "shape", "bookmark", "embedScene", "shape"), out.node.children.map { it.name })
        assertEquals(2999, out.totalTime)
        assertEquals(listOf(100), out.bookmarks)
    }

    @Test fun groupingKeepsMasksClippingTheirLayerAndLeavesCamerasOutside() {
        val s = scene(layer(1, 0, 999), layer(2, 0, 999, extra = mapOf("clippingMask" to "true")), layer(3, 0, 999, extra = mapOf("clippingMask" to "true")),
            shapeNode("camera", id = 4, end = 999))
        fun Scene.flags() = layers.map { it.id to (it.node["clippingMask"] == "true") }
        val one = s.grouped(setOf(2), null, 9, "G")
        assertEquals(listOf(1L to false, 9L to true, 3L to true, 4L to false), one.flags())
        assertEquals(listOf(2L to false), one.group().nested!!.flags())
        val both = s.grouped(setOf(2, 3), null, 9, "G")
        assertEquals(listOf(1L to false, 9L to true, 4L to false), both.flags())
        assertEquals(listOf(2L to false, 3L to false), both.group().nested!!.flags())
        val withBase = s.grouped(setOf(1, 2), null, 9, "G")
        assertEquals(listOf(9L to false, 3L to true, 4L to false), withBase.flags())
        assertEquals(listOf(1L to false, 2L to true), withBase.group().nested!!.flags())
        assertEquals(listOf(9L, 2L, 3L, 4L), s.grouped(setOf(1, 4), null, 9, "G").layers.map { it.id })
        assertEquals(s, s.grouped(setOf(4), null, 9, "G"))
    }

    @Test fun maskAndExcludeGoOnTheTopLayerInsideTheGroup() {
        for (mode in listOf("mask", "exclude")) {
            val nested = project.grouped(setOf(1, 3, 4), mode, 9, "G").group().nested!!
            assertEquals(listOf("normal", "normal", mode), nested.layers.map { it.blend })
        }
        assertEquals(listOf("normal", "normal"), project.grouped(setOf(1, 2), null, 9, "G").group().nested!!.layers.map { it.blend })
        assertEquals("normal", project.grouped(setOf(3), "mask", 9, "G").group().nested!!.layers.single().blend)
    }

    @Test fun parentLinksAcrossTheGroupEdgeGoAndEveryLayerStaysPut() {
        val s = scene(
            layer(1, 0, 999, 1000f, 0f),
            layer(2, 0, 999, 100f, 100f, parent = 3),
            layer(3, 0, 999, 5f, 5f, parent = 1),
            layer(4, 0, 999, 10f, 20f, parent = 2),
        )
        val out = s.grouped(setOf(2, 3), null, 9, "G")
        val inner = out.group().nested!!.layers.associateBy { it.id }
        assertEquals("3", inner.getValue(2).node["parent"])
        assertNull(inner.getValue(3).node["parent"])
        assertEquals(1005f, inner.getValue(3).location[0]); assertEquals(5f, inner.getValue(3).location[1])
        val child = out.layer(4)!!
        assertNull(child.node["parent"])
        assertEquals(1115f, child.location[0], 1e-3f); assertEquals(125f, child.location[1], 1e-3f)
    }

    @Test fun nothingToGroupLeavesTheProjectAlone() {
        assertEquals(project, project.grouped(emptySet(), null, 9, "G"))
        assertEquals(project, project.grouped(setOf(42), "mask", 9, "G"))
    }

    private val line = SnapGuide(true, 0f, false)
    private val other = SnapGuide(false, 0f, true)

    @Test fun withoutSnapsTheLayerFollowsTheFinger() {
        val axis = SnapAxis(emptyList())
        for (t in listOf(-300f, -1f, 0f, 2.5f, 400f)) assertEquals(t, axis.map(t), 0f)
        assertEquals(0f, axis.map(Float.NaN), 0f)
    }

    @Test fun snapsInARowEachHoldTheirOwnStretch() {
        val axis = SnapAxis(listOf(Snap(60f, line), Snap(50f, line)))
        assertEquals(50f, axis.map(50f), 1e-4f)
        assertEquals(50f, axis.map(75f), 1e-4f)
        assertEquals(55f, axis.map(80f), 1e-4f)
        assertEquals(60f, axis.map(85f), 1e-4f)
        assertEquals(60f, axis.map(110f), 1e-4f)
        assertEquals(70f, axis.map(120f), 1e-4f)
        val back = SnapAxis(listOf(Snap(-10f, line)), threshold = 4f)
        assertEquals(-10f, back.map(-13f), 1e-4f)
        assertEquals(-11f, back.map(-15f), 1e-4f)
        assertEquals(30f, back.map(30f), 1e-4f)
    }

    @Test fun snapsAtTheSameDistanceHoldOnceAndShowEveryGuide() {
        val axis = SnapAxis(listOf(Snap(40f, line), Snap(40.001f, other)))
        assertEquals(40f, axis.map(64f), 1e-2f)
        assertEquals(41f, axis.map(66f), 1e-2f)
        assertEquals(listOf(line, other), axis.guidesAt(40f))
        assertTrue(axis.guidesAt(41f).isEmpty())
    }

    @Test fun theLayerNeverJumpsOrTurnsBack() {
        val axis = SnapAxis(listOf(Snap(0f, line), Snap(30f, line), Snap(31f, line), Snap(-45f, line), Snap(200f, line)))
        var last = axis.map(-400f)
        var t = -400f
        while (t < 400f) {
            t += 0.5f
            val now = axis.map(t)
            assertTrue("at $t: $last then $now", now >= last && now - last <= 0.5001f)
            last = now
        }
    }

    @Test fun pastingAMediaFillIntoAnotherProjectDeclaresTheMedia() {
        val source = layer(2, 0, 999, extra = mapOf("fillType" to "media", "fillImage" to "u1", "mediaFillMode" to "fit"))
        val media = listOf(MediaDeclaration(XmlNode("media", mapOf("uri" to "u1", "filename" to "pic.png", "width" to "40", "height" to "30"))),
            MediaDeclaration(XmlNode("media", mapOf("uri" to "u2", "filename" to "other.png"))))
        val other = scene(layer(5, 0, 999))
        val pasted = other.withPastedStyle(5, 0, Layer(source), media, setOf(StylePart.ColorFill))
        assertEquals("u1", pasted.layer(5)!!.node["fillImage"])
        assertEquals(40, pasted.media("u1")?.width)
        assertNull(pasted.media("u2"))
        assertNull(other.withPastedStyle(5, 0, Layer(source), media, setOf(StylePart.Blending)).media("u1"))
    }
}
