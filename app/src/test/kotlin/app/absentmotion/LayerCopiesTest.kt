package app.absentmotion

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class LayerCopiesTest {
    private fun layer(id: Long, kind: String, start: Int, end: Int, parent: Long? = null): Layer = Layer(
        XmlNode(kind, mapOf("id" to "$id", "startTime" to "$start", "endTime" to "$end", "label" to kind) +
            (parent?.let { mapOf("parent" to "$it") } ?: emptyMap()),
            listOf(XmlNode("gain", children = listOf(XmlNode("kf", mapOf("t" to "0.25", "v" to "0.5")))))))

    @Test fun pasteKeepsTheStackTimingAndMixedClipTypes() {
        val source = listOf(layer(1, "shape", 500, 999), layer(2, "audio", 1250, 1999))
        var next = 100L
        val result = copyLayers(source, 2000) { next++ }
        assertEquals(listOf("shape", "audio"), result.map { it.kind })
        assertEquals(listOf(2000, 2750), result.map { it.startTime })
        assertEquals(listOf(2499, 3499), result.map { it.endTime })
        assertEquals(listOf(100L, 101L), result.map { it.id })
        assertEquals(source.map { it.node.children }, result.map { it.node.children })
        assertEquals(listOf(500, 1250), source.map { it.startTime })
    }

    @Test fun copiedParentAndNestedGroupIdsPointToTheirCopies() {
        val child = layer(3, "shape", 0, 999, 2)
        val group = layer(2, "embedScene", 0, 999, 1).copy(node = layer(2, "embedScene", 0, 999, 1).node.copy(
            children = listOf(XmlNode("scene", children = listOf(child.node)))))
        var next = 50L
        val result = copyLayers(listOf(layer(1, "nullobj", 0, 999), group)) { next++ }
        assertEquals("50", result[1].node["parent"])
        val nested = result[1].node.child("scene")!!.children.single()
        assertEquals("52", nested["id"])
        assertEquals("51", nested["parent"])
        assertEquals("2", child.node["parent"])
    }

    @Test fun duplicationCanRetainAParentThatIsAlreadyInTheScene() {
        val source = layer(3, "shape", 0, 999, 2)
        assertEquals("2", copyLayers(listOf(source), preserveOutsideParents = true, newId = { 4 }).single().node["parent"])
        assertNull(copyLayers(listOf(source), newId = { 4 }).single().node["parent"])
    }

    @Test fun duplicateKeepsTimesAndAnEmptyClipboardIsHarmless() {
        val source = layer(1, "audio", 700, 1800)
        assertEquals(700, copyLayers(listOf(source), newId = { 2 }).single().startTime)
        assertTrue(copyLayers(emptyList(), 2500).isEmpty())
    }

    private val thirty = Scene.create("t", 100, 100, 30, 0)

    private fun Scene.shownFrames(l: Layer) = (0..200).filter { f -> frameTime(f) in l.startTime..l.endTime }

    @Test fun splitHalvesMeetWithoutSharingAFrame() {
        val (first, second) = thirty.splitAt(layer(1, "shape", 0, 2999), 30, 2)!!
        assertEquals(1000, second.startTime)
        assertEquals(first.endTime + 1, second.startTime)
        assertEquals(listOf(0 to 999, 1000 to 2999), listOf(first, second).map { it.startTime to it.endTime })
        assertEquals((0..29).toList(), thirty.shownFrames(first))
        assertEquals((30..89).toList(), thirty.shownFrames(second))
        assertEquals(2L, second.id)
    }

    @Test fun cutsThatWouldLeaveAPartWithNoFrameAreRefused() {
        val unalignedEnd = layer(1, "shape", 0, 1005)
        assertNull(thirty.trimStartAt(unalignedEnd, 30))
        assertEquals(966 to 1005, thirty.trimStartAt(unalignedEnd, 29)!!.let { it.startTime to it.endTime })
        assertNull(thirty.splitAt(unalignedEnd, 30, 2))
        val unalignedStart = layer(1, "shape", 995, 2999)
        assertNull(thirty.trimEndAt(unalignedStart, 29))
        assertEquals(995 to 1032, thirty.trimEndAt(unalignedStart, 30)!!.let { it.startTime to it.endTime })
        assertNull(thirty.splitAt(unalignedStart, 30, 2))
        assertFalse(thirty.showsAnyFrame(1000, 1015))
        assertTrue(thirty.showsAnyFrame(1000, 1016))
        assertEquals(30, thirty.firstShownFrame(984))
        assertEquals(29, thirty.lastShownFrame(1015))
    }
}
