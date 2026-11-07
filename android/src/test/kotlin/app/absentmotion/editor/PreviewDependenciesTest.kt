package app.absentmotion.editor

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewDependenciesTest {
    private fun scene(first: String = "", second: String = "", extra: String = "") = Scene(XmlNode.parse(
        "<scene width=\"100\" height=\"100\" fps=\"30\" totalTime=\"5999\" $extra>" +
            "<shape id=\"1\" startTime=\"0\" endTime=\"999\" label=\"A\">$first</shape>" +
            "<shape id=\"2\" startTime=\"4000\" endTime=\"5999\" label=\"B\">$second</shape></scene>"))

    @Test fun aLocalEditRetainsAnUnchangedEarlierSection() {
        val ranges = previewDirtyRanges(scene(), scene(second = "<fillColor value=\"#ff00ff00\"/>"))
        assertTrue(ranges.any { 140 in it })
        assertFalse(ranges.any { 10 in it })
    }

    @Test fun renamesBookmarksAndAudioEditsDoNotInvalidateVisualFrames() {
        val before = scene()
        val xml = before.node.toXml().replace("label=\"A\"", "label=\"Renamed\"")
            .replace("</scene>", "<bookmark t=\"500\"/><audio id=\"9\" startTime=\"0\" endTime=\"5000\"><gain value=\"0.5\"/></audio></scene>")
        assertTrue(previewDirtyRanges(before, Scene.parse(xml)).isEmpty())
    }

    @Test fun bookmarksInvalidateFramesOnlyWhenAnEffectFollowsTheBeat() {
        fun marked(scene: Scene) = Scene.parse(scene.node.toXml().replace("</scene>", "<bookmark t=\"500\"/></scene>"))
        val plain = scene()
        assertTrue(previewDirtyRanges(plain, marked(plain)).isEmpty())
        val beat = scene(first = "<effect id=\"absent.beatzoom\"/>")
        assertEquals(listOf(0..beat.lastFrame), previewDirtyRanges(beat, marked(beat)))
        val burst = scene(second = "<effect id=\"absent.particles\"/>")
        assertEquals(listOf(0..burst.lastFrame), previewDirtyRanges(marked(burst), burst))
        val hidden = scene(first = "<effect id=\"absent.beatzoom\" hidden=\"true\"/>")
        assertTrue(previewDirtyRanges(hidden, marked(hidden)).isEmpty())
    }

    @Test fun movingAClipInvalidatesBothItsFormerAndNewLocation() {
        val before = scene()
        val moved = Scene.parse(before.node.toXml().replace("startTime=\"4000\"", "startTime=\"2000\"").replace("endTime=\"5999\"", "endTime=\"3999\""))
        val ranges = previewDirtyRanges(before, moved)
        assertTrue(ranges.any { 90 in it })
        assertTrue(ranges.any { 150 in it })
        assertFalse(ranges.any { 10 in it })
    }

    @Test fun dimensionsResourcesAndUnknownDependenciesInvalidateConservatively() {
        val before = scene()
        assertEquals(listOf(0..before.lastFrame), previewDirtyRanges(before, Scene.parse(before.node.toXml().replace("width=\"100\"", "width=\"200\""))))
        val camera = Scene.parse(before.node.toXml().replace("</scene>", "<camera id=\"7\" startTime=\"0\" endTime=\"5999\"/></scene>"))
        assertEquals(listOf(0..camera.lastFrame), previewDirtyRanges(camera, Scene.parse(camera.node.toXml().replace("<camera id=\"7\" ", "<camera id=\"7\" zoom=\"2\" "))))
    }

    @Test fun layersThatReadTheLayerBelowFollowItsEdits() {
        fun stack(middle: String, top: String) = Scene(XmlNode.parse("<scene width=\"100\" height=\"100\" fps=\"30\" totalTime=\"5999\">" +
            "<shape id=\"1\" startTime=\"0\" endTime=\"5999\"/>$middle<shape id=\"3\" startTime=\"0\" endTime=\"5999\" $top/></scene>"))
        val a = "<shape id=\"2\" startTime=\"0\" endTime=\"1999\"/>"
        val masked = previewDirtyRanges(stack(a, "clippingMask=\"true\""), stack("", "clippingMask=\"true\""))
        assertTrue(masked.any { 150 in it })
        val plain = previewDirtyRanges(stack(a, ""), stack("", ""))
        assertFalse(plain.any { 150 in it })
        val bent = "<shape id=\"2\" startTime=\"0\" endTime=\"1999\"><path d=\"M 0 0 L 10 10\"/></shape>"
        val moved = previewDirtyRanges(stack(a, ""), stack(bent, ""))
        assertFalse(moved.any { 150 in it })
        val along = { m: String -> Scene(XmlNode.parse("<scene width=\"100\" height=\"100\" fps=\"30\" totalTime=\"5999\">$m" +
            "<shape id=\"3\" startTime=\"0\" endTime=\"5999\"><effect id=\"absent.movealongpath\"/></shape></scene>")) }
        assertTrue(previewDirtyRanges(along(a), along(bent)).any { 150 in it })
    }

    @Test fun anEffectOnlyDirtiesItsOwnLayerButLinksParentsAndPathsReachTheirUsers() {
        val temporal = scene(first = "<effect id=\"absent.echo\"/>")
        val echoed = previewDirtyRanges(temporal, scene(first = "<effect id=\"absent.echo\"/>", second = "<fillColor value=\"#ff00ff00\"/>"))
        assertTrue(echoed.any { 150 in it })
        assertFalse(echoed.any { 10 in it })
        val link = "<transform><rotation value=\"0\"><valueLink layer=\"1\" kind=\"transform\" property=\"rotation\"/></rotation></transform>"
        val linked = previewDirtyRanges(scene(second = link), scene(first = "<transform><rotation value=\"90\"/></transform>", second = link))
        assertTrue(linked.any { 10 in it } && linked.any { 150 in it })
        assertFalse(linked.any { 60 in it })
        fun family(fill: String) = Scene(XmlNode.parse("<scene width=\"100\" height=\"100\" fps=\"30\" totalTime=\"5999\">" +
            "<nullobj id=\"1\" startTime=\"0\" endTime=\"999\">$fill</nullobj><shape id=\"2\" startTime=\"4000\" endTime=\"5999\" parent=\"1\"/></scene>"))
        val moved = previewDirtyRanges(family(""), family("<transform><rotation value=\"45\"/></transform>"))
        assertTrue(moved.any { 150 in it })
        assertFalse(moved.any { 60 in it })
    }

    @Test fun preparationIsBoundedPrioritisesThePlayheadAndDoesNotDuplicate() {
        val order = previewOrder(900, 100)
        assertEquals(listOf(101, 102, 99, 103, 104, 98), order.take(6))
        assertEquals(order.size, order.toSet().size)
        assertEquals(900, order.size)
        assertFalse(100 in order)
        assertEquals(listOf(898, 899, 896, 895, 894), previewOrder(899, 897).take(5))
        assertEquals(listOf(1, 2, 3), previewOrder(3, 0))
        assertTrue(previewOrder(0, -50).isEmpty())
        val runs = previewOrder(900, 100, 30)
        assertEquals((101..160).toList() + (70..99).toList(), runs.take(90))
        assertEquals(900, runs.toSet().size)
        assertEquals(listOf(1, 2, 3), previewOrder(3, 0, 30))
    }

    @Test fun sparseFramesAreIndependentAndEvictionIsBounded() {
        val store = FrameStore(0, 10)
        assertTrue(store.put(10, ByteArray(4)))
        assertTrue(store.put(100, ByteArray(4)))
        assertFalse(11 in store)
        assertFalse(store.put(30, ByteArray(4)))
        assertFalse(store.evictFarther(12, 200))
        assertTrue(store.evictFarther(12, 30))
        assertTrue(10 in store)
        assertFalse(100 in store)
        assertTrue(store.put(30, ByteArray(4)))
        store.removeWhere { it in 20..40 }
        assertTrue(10 in store)
        assertFalse(30 in store)
        assertEquals(4L, store.bytes)
        store.close()
        assertFalse(10 in store)
        assertEquals(null, store[10])
        assertFalse(store.put(10, byteArrayOf(1)))
    }

    @Test fun framesBehindThePlayheadCountDoubleWhenEvictingAndRunsAreContiguous() {
        val store = FrameStore(0, 12)
        assertTrue(store.put(40, ByteArray(4)))
        assertTrue(store.put(55, ByteArray(4)))
        assertTrue(store.put(56, ByteArray(4)))
        assertEquals(listOf(40..40, 55..56), store.runs())
        assertTrue(store.evictFarther(50, 51))
        assertFalse(40 in store)
        assertTrue(56 in store)
    }
}
