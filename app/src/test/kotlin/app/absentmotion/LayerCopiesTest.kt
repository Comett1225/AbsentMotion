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
}
