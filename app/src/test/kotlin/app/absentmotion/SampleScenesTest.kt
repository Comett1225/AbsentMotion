package app.absentmotion

import app.absentmotion.model.Easing
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.mediaUris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleScenesTest {
    private fun scene(name: String) = Scene.parse(TestData.scene(name).readText())

    private fun XmlNode.all(): Sequence<XmlNode> = sequenceOf(this) + children.asSequence().flatMap { it.all() }

    private fun easings(root: XmlNode) = root.all().filter { it.name == "kf" }.mapNotNull { it["e"] }.toList()

    @Test fun everySampleIsAProjectWhoseLengthIsItsLastLayerEnd() {
        assertTrue(TestData.scenes.size >= 6)
        for (file in TestData.scenes) {
            val s = Scene.parse(file.readText())
            assertTrue("${file.name} has a size", s.width > 0 && s.height > 0 && s.fps > 0)
            assertEquals("${file.name} totalTime", s.layers.maxOf { it.endTime }, s.totalTime)
            assertEquals("${file.name} layer ids are unique", s.layers.size, s.layers.map { it.id }.toSet().size)
            assertTrue("${file.name} keyframe times stay inside the layer", s.node.all().filter { it.name == "kf" }
                .all { it["t"]!!.toFloat() in 0f..1f })
        }
    }

    @Test fun shapesHasOneLayerForEveryBuiltInShapeWithItsParameters() {
        val catalog = XmlNode.parse(TestData.project.resolve("app/src/main/assets/shapes.xml").readText()).children.filter { !it["s"].isNullOrEmpty() }
        assertTrue(catalog.size >= 19)
        val layers = scene("shapes").layers
        for (def in catalog) {
            val layer = layers.single { it.shape == def["s"] }
            assertEquals(def["s"]!!, def.children.filter { it.name == "property" }.map { it["name"]!! }.sorted(), layer.node.children.filter { it.name == "property" }.map { it["name"]!! }.sorted())
        }
        assertTrue("drawn shapes with a path", layers.count { it.shape.isEmpty() && it.node.child("path") != null } >= 3)
    }

    @Test fun easingHasOneMovingLayerPerEasingTypeAndItsVariants() {
        val strings = easings(XmlNode.parse(TestData.scene("easing").readText()))
        val parsed = strings.map { it to Easing.parse(it) }
        assertTrue("every string parses", parsed.none { (_, e) -> e == Easing.Linear })
        assertEquals(setOf("cubicBezier", "bounce", "elastic", "cyclic", "random", "step", "elasticStep"), parsed.map { it.second.type }.toSet())
        assertEquals(setOf("reverse", "local"), parsed.flatMap { it.second.prefixes }.toSet())
        assertEquals(setOf(3, 6), parsed.filter { it.second.type == "random" }.map { it.second.p.size }.toSet())
        val moving = scene("easing").layers.filter { it.node.child("transform")?.child("location")?.children?.any { k -> k.name == "kf" } == true }
        assertTrue(moving.size >= 17)
        assertTrue("a layer with no easing is linear", moving.any { it.node.all().none { n -> n["e"] != null } })
    }

    @Test fun auditHasParentGroupElementHiddenSplitBookmarksAndEasings() {
        val s = scene("audit")
        val child = s.layers.single { it.node["parent"] != null }
        assertTrue(s.layer(child.node["parent"]!!.toLong()) != null)
        val group = s.layers.single { it.kind == "embedScene" && it.link == null }
        assertTrue(group.nested!!.layers.size >= 2 && group.nested!!.layers.any { it.node["parent"] != null })
        assertTrue(s.layers.single { it.kind == "embedScene" && it.link != null }.nested!!.retime == "loop")
        assertTrue(s.layers.any { it.hidden })
        val (a, b) = s.layers.filter { it.label.startsWith("Split") }
        assertEquals(a.endTime + 1, b.startTime)
        assertTrue(s.bookmarks.size >= 3)
        assertTrue(s.layers.all { it.endTime - it.startTime > 1000 || it.label.startsWith("Backdrop") })
        val types = easings(s.node).map { Easing.parse(it).type }.toSet()
        assertTrue(types.containsAll(listOf("cubicBezier", "bounce", "elastic", "cyclic", "random", "step", "elasticStep")))
    }

    @Test fun textHasGoogleFontsAlignmentsAndAGradient() {
        val texts = scene("text").layers.filter { it.kind == "text" }
        assertTrue(texts.all { it.node["font"]!!.startsWith("googlefonts?name=") })
        assertTrue(texts.map { it.node["align"] }.containsAll(listOf("left", "center", "right")))
        assertTrue(texts.any { it.fillType == "gradient" && it.node.child("gradient") != null })
        assertTrue(texts.all { it.node.child("content")!!.text.isNotEmpty() })
    }

    @Test fun mediaPointsAtFilesThatExistWithTheirRecords() {
        val s = scene("media")
        val uris = s.node.mediaUris()
        assertEquals(4, uris.size)
        for (uri in uris) {
            val file = TestData.project.resolve(uri)
            assertTrue("$uri exists", file.isFile)
            assertEquals("$uri size", file.length(), s.media(uri)!!.size)
            assertTrue(s.media(uri)!!.filename.isNotEmpty())
        }
    }
}
