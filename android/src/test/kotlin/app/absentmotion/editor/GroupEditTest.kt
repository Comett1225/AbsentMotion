package app.absentmotion.editor

import app.absentmotion.model.Layer
import app.absentmotion.model.NumberRef
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.ValueLink
import app.absentmotion.model.XmlNode
import app.absentmotion.sceneOf
import app.absentmotion.shapeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEditTest {
    private fun at(x: Float, y: Float) = XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "$x,$y,0"))))

    private fun leaf(id: Long, start: Int, end: Int, x: Float, y: Float, media: String? = null) =
        shapeNode(id = id, start = start, end = end, attrs = buildMap { put("label", "L$id"); media?.let { put("fillImage", it); put("fillType", "media") } }, children = listOf(at(x, y)))

    private fun media(uri: String) = XmlNode("media", mapOf("uri" to uri, "type" to "image/png"))

    private val start = Scene(sceneOf(media("file:///a.png"), leaf(1, 0, 3000, 100f, 100f, "file:///a.png"), leaf(2, 1000, 2500, 200f, 300f), leaf(3, 1500, 4000, 50f, 60f), width = 1080, height = 1920).node.with("totalTime", "4000"))

    @Test fun openingAGroupShowsItsLayersAndProjectMedia() {
        val grouped = start.grouped(setOf(1, 2), null, 100, "G")
        val inside = grouped.focused(listOf(100))
        assertNotNull(inside)
        assertEquals(listOf(1L, 2L), inside!!.layers.map { it.id })
        assertEquals(listOf(0, 1000), inside.layers.map { it.startTime })
        assertTrue(inside.media.any { it.uri == "file:///a.png" })
        assertNull(grouped.focused(listOf(1)))
        assertEquals(emptyList<Long>(), grouped.openablePath(listOf(999)))
    }

    @Test fun editsInsideAGroupGoBackIntoTheGroup() {
        val grouped = start.grouped(setOf(1, 2), null, 100, "G")
        val inside = grouped.focused(listOf(100))!!
        val edited = inside.update(2) { it.withLabel("Renamed") }.withMedia(app.absentmotion.model.MediaDeclaration(media("file:///b.png")))
            .add(Layer(leaf(9, 0, 500, 10f, 10f, "file:///b.png")), emptyList())
        val back = grouped.withFocused(listOf(100), edited)
        assertEquals(grouped.layers.map { it.id }, back.layers.map { it.id })
        val group = back.layer(100)!!.nested!!
        assertEquals("Renamed", group.layer(2)!!.label)
        assertNotNull(group.layer(9))
        assertTrue("new media is declared at the top", back.media.any { it.uri == "file:///b.png" })
        assertTrue("project media is not copied into the group", group.media.none { it.uri == "file:///a.png" })
        assertEquals(1, back.media.count { it.uri == "file:///a.png" })
    }

    @Test fun nestedGroupsOpenAndWriteBack() {
        val inner = start.grouped(setOf(1, 2), null, 100, "G1")
        val outer = inner.grouped(setOf(100, 3), null, 200, "G2")
        val path = listOf(200L, 100L)
        val deep = outer.focused(path)!!
        assertEquals(setOf(1L, 2L), deep.layers.map { it.id }.toSet())
        val back = outer.withFocused(path, deep.update(1) { it.withLabel("Deep") })
        assertEquals("Deep", back.layer(200)!!.nested!!.layer(100)!!.nested!!.layer(1)!!.label)
    }

    @Test fun ungroupPutsLayersBackWhereTheyWere() {
        val grouped = start.grouped(setOf(1, 2), null, 100, "G")
        val back = grouped.ungrouped(100)!!
        assertNull(back.layer(100))
        assertEquals(listOf(1L, 2L, 3L), back.layers.map { it.id })
        for (id in listOf(1L, 2L, 3L)) {
            val before = start.layer(id)!!
            val after = back.layer(id)!!
            assertEquals("start of $id", before.startTime, after.startTime)
            assertEquals("end of $id", before.endTime, after.endTime)
            assertEquals("x of $id", before.location[0], after.location[0], 0.01f)
            assertEquals("y of $id", before.location[1], after.location[1], 0.01f)
        }
        assertEquals(1, back.media.count { it.uri == "file:///a.png" })
    }

    @Test fun ungroupOfASplitGroupKeepsTheVisiblePart() {
        val grouped = start.grouped(setOf(1, 2), null, 100, "G")
        val second = grouped.update(100) { Layer(it.node.with("startTime", "1500").with("inTime", "1500")) }
        val back = second.ungrouped(100)!!
        assertEquals(1500 to 3000, back.layer(1)!!.let { it.startTime to it.endTime })
        assertEquals(1500 to 2500, back.layer(2)!!.let { it.startTime to it.endTime })
        val first = grouped.update(100) { Layer(it.node.with("endTime", "800")) }.ungrouped(100)!!
        assertEquals(0 to 800, first.layer(1)!!.let { it.startTime to it.endTime })
        assertNull(first.layer(2))
    }

    @Test fun ungroupKeepsAMovedGroupsPlacement() {
        val grouped = start.grouped(setOf(1, 2), null, 100, "G")
        val group = grouped.layer(100)!!
        val moved = grouped.update(100) { Layer(it.node.withChild("transform", at(540f + 40f, 960f - 20f))) }
        assertTrue(!moved.layer(100)!!.ungroupLosesStyle())
        val back = moved.ungrouped(100)!!
        assertEquals(start.layer(1)!!.location[0] + 40f, back.layer(1)!!.location[0], 0.5f)
        assertEquals(start.layer(1)!!.location[1] - 20f, back.layer(1)!!.location[1], 0.5f)
        assertEquals(group.startTime, back.layers.filter { it.id in setOf(1L, 2L) }.minOf { it.startTime })
    }

    @Test fun ungroupKeepsALayerThatFollowedTheGroupMovingWithItAndDropsLinksToTheGroup() {
        val sliding = XmlNode("transform", children = listOf(XmlNode("location", children = listOf(
            XmlNode("kf", linkedMapOf("t" to "0", "v" to "100,50,0")), XmlNode("kf", linkedMapOf("t" to "1", "v" to "400,50,0"))))))
        val linked = XmlNode("transform", children = listOf(XmlNode("opacity", mapOf("value" to "1"), listOf(
            ValueLink(100, NumberRef(PropRef.Opacity)).node(0)))))
        val grouped = start.grouped(setOf(1, 2), null, 100, "G").update(100) { Layer(it.node.withChild("transform", sliding)) }
        val before = Scene(grouped.node.copy(children = grouped.node.children +
            shapeNode(id = 4, start = 0, end = 3000, parent = 100, children = listOf(at(10f, 10f))) +
            shapeNode(id = 5, start = 0, end = 3000, children = listOf(linked))))
        val back = before.ungrouped(100)!!
        assertNull(back.layer(4)!!.node["parent"])
        for (ms in listOf(0, 750, 1500, 3000)) {
            val was = before.place(before.layer(4)!!, ms)
            val now = back.place(back.layer(4)!!, ms)
            assertEquals("x at $ms", was.x, now.x, 0.5f)
            assertEquals("y at $ms", was.y, now.y, 0.5f)
        }
        assertNull(back.layer(5)!!.node.child("transform")!!.child("opacity")!!.child("valueLink"))
    }

    @Test fun ungroupOfAStyledGroupAsksFirstAndStillWorks() {
        val grouped = start.grouped(setOf(1, 2), null, 100, "G").update(100) { Layer(it.node.with("opacity", "0.5")) }
        assertTrue(grouped.layer(100)!!.ungroupLosesStyle())
        val back = grouped.ungrouped(100)!!
        assertEquals(listOf(1L, 2L, 3L), back.layers.map { it.id })
    }
}
