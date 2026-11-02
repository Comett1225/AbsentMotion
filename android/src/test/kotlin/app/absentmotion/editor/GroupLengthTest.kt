package app.absentmotion.editor

import app.absentmotion.ai.withMattesFollowing
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.copyLayers
import app.absentmotion.model.layerTracks
import app.absentmotion.model.trimEndAt
import app.absentmotion.model.trimStartAt
import app.absentmotion.model.withSplit
import app.absentmotion.model.withVideoSpeedMap
import app.absentmotion.sceneOf
import app.absentmotion.shapeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.roundToInt

class GroupLengthTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun spin(from: Float, to: Float) = XmlNode("transform", children = listOf(
        XmlNode("rotation", children = listOf(XmlNode("kf", mapOf("t" to "0.2", "v" to "$from")), XmlNode("kf", mapOf("t" to "0.8", "v" to "$to")))),
    ))

    private fun leaf(id: Long, start: Int, end: Int) = shapeNode(id = id, start = start, end = end, attrs = mapOf("label" to "L$id"), children = listOf(spin(0f, id * 10f)))

    private val start = Scene(sceneOf(leaf(1, 0, 2999), leaf(2, 500, 3499), leaf(3, 1000, 3999), leaf(4, 0, 999), width = 1080, height = 1920).node.with("totalTime", "3999"))

    private fun groups(scene: Scene): Map<String, Int> = buildMap {
        fun walk(s: Scene) { s.layers.forEach { l -> l.nested?.let { put(l.label, it.totalTime); walk(it) } } }
        walk(scene)
    }

    private fun leaves(scene: Scene): Map<String, Pair<Int, Int>> = buildMap {
        fun walk(s: Scene) { s.layers.forEach { l -> val inner = l.nested; if (inner != null) walk(inner) else put(l.label, l.startTime to l.endTime) } }
        walk(scene)
    }

    private fun shown(scene: Scene, frames: IntRange = 0..scene.lastFrame): Map<Int, Set<Pair<String, Int>>> = frames.associateWith { f ->
        scene.layerTracks().mapNotNull { track -> track.timeAt(scene.frameTime(f).toDouble(), true)?.let { track.layer.label to it.ms.roundToInt() } }.toSet()
    }

    private fun Scene.group(label: String) = layers.single { it.label == label }

    private fun Scene.labelled(id: Long, label: String) = update(id) { it.withLabel(label) }

    @Test fun editingAroundNestedGroupsNeverShortensThemOrLosesLayers() {
        var scene = start
        val history = EditHistory({ scene }) { next, _ -> scene = next }
        val states = mutableListOf(scene)
        fun edit(change: (Scene) -> Scene) { history.edit(null, change(scene).withMattesFollowing(scene)); states += scene }

        edit { it.grouped(setOf(2, 3), null, 100, "G1") }
        edit { it.grouped(setOf(100, 1), null, 200, "G2") }
        val inner = groups(scene)
        val parts = leaves(scene)
        assertEquals(mapOf("G2" to 3999, "G1" to 3499), inner)
        assertEquals(setOf("L1", "L2", "L3", "L4"), parts.keys)
        val original = shown(scene)

        fun same() {
            assertEquals(inner, groups(scene).filterKeys { it in inner })
            for ((label, times) in leaves(scene)) assertEquals(label, parts[label], times)
        }

        edit { it.update(200) { g -> it.movedClip(g, 600) } }
        same()
        val moved = shown(scene)
        for (f in 0..start.lastFrame) {
            val later = scene.frameAt(start.frameTime(f) + 600)
            assertEquals(original[f]!!.filter { it.first != "L4" }.toSet(), moved[later]!!.filter { it.first != "L4" }.toSet())
        }
        edit { it.update(200) { g -> it.movedClip(g, 0) } }
        assertEquals(original, shown(scene))

        edit { s -> s.update(200) { g -> s.trimStartAt(g, 30)!! } }
        edit { s -> s.update(200) { g -> s.trimEndAt(g, 90)!! } }
        same()
        val trimmed = shown(scene)
        for (f in 30..90) assertEquals(original[f], trimmed[f])
        assertTrue((0 until 30).all { f -> trimmed[f]!!.none { it.first != "L4" } })

        edit { it.withClipEdgesAt(listOf(200), 0, end = false, extend = true) }
        edit { it.withClipEdgesAt(listOf(200), start.frameAt(3999), end = true, extend = true) }
        same()
        assertEquals(original, shown(scene))

        edit { s -> s.withSplit(s.group("G2"), 55, 300)!!.labelled(300, "G2b") }
        same()
        assertEquals(setOf("G2", "G2b", "G1"), groups(scene).keys)
        assertEquals(original, shown(scene))

        edit { it.remove(300) }
        assertEquals((0 until 55).associateWith { original[it] }, shown(scene).filterKeys { it < 55 })
        history.undo(); states.removeAt(states.lastIndex)
        assertEquals(original, shown(scene))

        edit { s -> s.addAbove(copyLayers(listOf(s.layer(300)!!), preserveOutsideParents = true).single().withLabel("G2c"), 300) }
        same()
        assertEquals(original.filterKeys { it <= 54 }, shown(scene, 0..54))

        edit { s -> s.update(200) { it.withVideoSpeedMap(XmlNode("speedMap", mapOf("value" to "2"))) } }
        val fast = scene.group("G2")
        assertEquals(1833 / 2.0, (fast.endTime - fast.startTime).toDouble(), 34.0)
        assertEquals(inner, groups(scene).filterKeys { it in inner })
        assertTrue(shown(scene, 0..27).values.flatten().map { it.first }.containsAll(listOf("L1", "L2", "L3")))

        val finalScene = scene
        repeat(states.size - 1) { history.undo() }
        assertEquals(states.first().node, scene.node)
        repeat(states.size - 1) { history.redo() }
        assertEquals(finalScene.node, scene.node)
    }

    @Test fun keyframesInsideGroupsKeepTheirTimesThroughGroupingAndSplitting() {
        val grouped = start.grouped(setOf(2, 3), null, 100, "G1")
        val before = grouped.group("G1").nested!!.layers.map { it.label to it.node.child("transform") }
        val split = grouped.withSplit(grouped.group("G1"), 60, 300)!!
        for (half in split.layers.filter { it.nested != null }) {
            assertEquals(before, half.nested!!.layers.map { it.label to it.node.child("transform") })
        }
    }

    @Test fun aChangedElementKeepsItsPlaceAndLengthInTheHostProject() {
        val store = ProjectStore(tmp.root)
        val element = Scene(sceneOf(leaf(1, 0, 1999), leaf(2, 0, 2999)).node.with("type", "element").with("retime", "freeze").with("totalTime", "2999"))
        val id = store.createElement(element)
        val host = sceneOf(shapeNode("embedScene", id = 9, start = 1000, end = 5999, attrs = mapOf("link" to store.linkOf(id), "label" to "E"), children = listOf(element.node)))
        val hostId = store.create(host)
        store.save(id, element.remove(2))
        val reopened = store.load(hostId)!!
        val layer = reopened.layer(9)!!
        assertEquals(1000 to 5999, layer.startTime to layer.endTime)
        assertEquals(1999, layer.nested!!.totalTime)
        assertEquals(listOf("L1"), layer.nested!!.layers.map { it.label })
    }

    @Test fun aVideoCutOutGroupKeepsItsLengthWhenItsMaskFollowsTheVideo() {
        val video = shapeNode(id = 1, start = 0, end = 2499, attrs = mapOf("label" to "v", "fillType" to "media", "fillVideo" to "content://v", "s" to ".rect"))
        val matte = shapeNode(id = 2, start = 0, end = 2999, attrs = mapOf("label" to "m", "fillType" to "media", "fillVideo" to "content://m", "s" to ".rect"),
            children = listOf(XmlNode("gain", mapOf("value" to "0")), XmlNode("effect", mapOf("id" to "absent.lumakey"))))
        val inner = XmlNode("scene", linkedMapOf("fps" to "30", "totalTime" to "4999", "retime" to "off"), listOf(video, matte))
        val group = shapeNode("embedScene", id = 9, start = 0, end = 4999, attrs = mapOf("label" to "G"), children = listOf(XmlNode("ai", mapOf("type" to "video")), inner))
        val followed = sceneOf(group).withMattesFollowing()
        val nested = followed.layer(9)!!.nested!!
        assertEquals(2499, nested.layer(2)!!.endTime)
        assertEquals(4999, nested.totalTime)
    }
}
