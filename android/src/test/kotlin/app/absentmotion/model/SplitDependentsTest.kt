package app.absentmotion.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitDependentsTest {
    private fun layer(id: Long, start: Int = 0, end: Int = 2999, mask: Boolean = false, effect: String? = null) = Layer(XmlNode(
        "shape", linkedMapOf("id" to "$id", "label" to "L$id", "startTime" to "$start", "endTime" to "$end") + (if (mask) mapOf("clippingMask" to "true") else emptyMap()),
        listOfNotNull(effect?.let { XmlNode("effect", mapOf("id" to it)) }),
    ))

    private fun scene(vararg layers: Layer) = Scene.create("t", 100, 100, 30, 0).withLayers(layers.toList())

    private fun Scene.ids() = layers.map { it.id }

    private fun Scene.drawn(timeMs: Int): List<Pair<String, List<String>>> {
        val list = layers
        val out = mutableListOf<Pair<String, List<String>>>()
        fun active(l: Layer) = !l.hidden && timeMs >= l.startTime && timeMs < l.endTime + 1
        var i = 0
        while (i < list.size) {
            val base = list[i]
            var end = i + 1
            while (end < list.size && list.isMask(end)) end++
            if (active(base)) out += base.label to list.subList(i + 1, end).filter(::active).map { it.label }
            i = end
        }
        return out
    }

    private fun Scene.assertDrawsLike(before: Scene, what: String) {
        for (frame in 0..lastFrame + 2) assertEquals("$what at frame $frame", before.drawn(frameStart(frame)), drawn(frameStart(frame)))
    }

    private var next = 100L
    private fun fresh(): Long = next++

    @Test fun splittingAMaskedBaseSplitsItsMasksSoBothHalvesStayMasked() {
        val s = scene(layer(1), layer(2, mask = true), layer(3, 500, 2500, mask = true), layer(4, 2200, 2999, mask = true), layer(5))
        for (frame in listOf(10, 30, 66, 70, 80)) {
            val split = s.withSplit(s.layer(1)!!, frame, 50, ::fresh)!!
            split.assertDrawsLike(s, "cut at $frame")
            assertEquals(1L, split.layers.first().id)
            assertEquals(5L, split.layers.last().id)
        }
    }

    @Test fun aMaskThatEndsBeforeTheCutStaysWithTheFirstHalfAndOneThatStartsAfterGoesWithTheSecond() {
        val s = scene(layer(1), layer(2, 0, 999, mask = true), layer(3, 2000, 2999, mask = true))
        val split = s.withSplit(s.layer(1)!!, 45, 9, ::fresh)!!
        assertEquals(listOf(1L, 2L, 9L, 3L), split.ids())
        split.assertDrawsLike(s, "cut at 45")
    }

    @Test fun splittingAMaskKeepsBothHalvesInTheSameRun() {
        val s = scene(layer(1), layer(2, mask = true), layer(3))
        val split = s.withSplit(s.layer(2)!!, 30, 9, ::fresh)!!
        assertEquals(listOf(1L, 2L, 9L, 3L), split.ids())
        split.assertDrawsLike(s, "mask split")
    }

    @Test fun duplicatingABaseLeavesItsMasksOnTheOriginal() {
        val s = scene(layer(1), layer(2, mask = true), layer(3, mask = true), layer(4))
        val copy = layer(9)
        assertEquals(listOf(1L, 2L, 3L, 9L, 4L), s.addAbove(copy, 1).ids())
        assertEquals(listOf(1L, 2L, 9L, 3L, 4L), s.addAbove(copy.copy(node = copy.node.with("clippingMask", "true")), 2).ids())
        val follower = scene(layer(1), layer(2, effect = "absent.movealongpath"), layer(3))
        assertEquals(listOf(1L, 2L, 9L, 3L), follower.addAbove(copy, 1).ids())
        assertEquals(listOf(1L, 2L, 9L, 3L), follower.addAbove(copy, 2).ids())
    }

    @Test fun duplicatingSeveralLayersKeepsEveryPairOfBaseAndMask() {
        val s = scene(layer(1), layer(2, mask = true), layer(3), layer(4, mask = true), layer(5, mask = true), layer(6))
        fun dup(vararg ids: Long) = s.withCopiesAbove(ids.associateWith { id ->
            val source = s.layer(id)!!
            source.copy(node = source.node.with("id", "${id * 10}").with("label", "L${id}c"))
        })
        assertEquals(listOf(1L, 2L, 10L, 20L, 3L, 4L, 5L, 6L), dup(1, 2).ids())
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 30L, 50L, 6L), dup(3, 5).ids())
        assertEquals(listOf(1L, 2L, 3L, 4L, 40L, 5L, 6L), dup(4).ids())
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 30L, 6L), dup(3).ids())
        assertEquals(listOf(1L, 2L, 10L, 3L, 4L, 5L, 6L, 60L), dup(1, 6).ids())
        val drawn = dup(1, 2).drawn(1000)
        assertEquals(listOf("L1" to listOf("L2"), "L1c" to listOf("L2c"), "L3" to listOf("L4", "L5"), "L6" to emptyList<String>()), drawn)
    }

    @Test fun bothHalvesAndACopyOfAPathFollowerKeepFollowingThePathLayer() {
        val s = scene(layer(1), layer(2, effect = "absent.movealongpath"), layer(3))
        val split = s.withSplit(s.pinnedPath(s.layer(2)!!), 45, 9, ::fresh)!!
        assertEquals(listOf(1L, 2L, 9L, 3L), split.ids())
        assertEquals(listOf("1", "1"), listOf(2L, 9L).map { split.layer(it)!!.node["pathLayer"] })
        assertEquals(null, s.pinnedPath(s.layer(3)!!).node["pathLayer"])
        val copies = copyLayers(listOf(s.layer(1)!!, s.pinnedPath(s.layer(2)!!)), newId = ::fresh)
        assertEquals(copies[0].node["id"], copies[1].node["pathLayer"])
        assertEquals("1", copyLayers(listOf(s.pinnedPath(s.layer(2)!!)), newId = ::fresh).single().node["pathLayer"])
    }

    @Test fun splittingAStretchedGroupWithAnOutroKeepsItsClock() {
        val group = Scene.parse("""<scene width="100" height="100" fps="30"><embedScene id="1" startTime="1000" endTime="4999" inTime="200">
            <scene width="100" height="100" fps="30" totalTime="2000" retime="stretch" retimeIn="300" retimeOut="1500"/></embedScene></scene>""")
        val whole = group.layer(1)!!
        val split = group.withSplit(whole.withPinnedClock(), 75, 9, ::fresh)!!
        val first = split.layer(1)!!
        val second = split.layer(9)!!
        for (ms in 1000..2499 step 37) assertEquals("first half at $ms", whole.nestedTime(ms.toDouble(), 30).ms, first.nestedTime(ms.toDouble(), 30).ms, 0.0)
        for (ms in 2500..4999 step 37) assertEquals("second half at $ms", whole.nestedTime(ms.toDouble(), 30).ms, second.nestedTime(ms.toDouble(), 30).ms, 0.0)
    }
}
