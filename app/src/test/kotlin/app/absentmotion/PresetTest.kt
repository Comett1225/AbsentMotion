package app.absentmotion

import app.absentmotion.editor.applyPreset
import app.absentmotion.editor.canSavePreset
import app.absentmotion.editor.presetScene
import app.absentmotion.editor.presetTitle
import app.absentmotion.library.PresetStore
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.effects
import app.absentmotion.model.keyframes
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

private fun assertEquals(expected: Any?, actual: Any?) = check(expected == actual) { "expected <$expected> but was <$actual>" }
private fun assertEquals(message: String, expected: Any?, actual: Any?) = check(expected == actual) { "$message: expected <$expected> but was <$actual>" }
private fun assertEquals(message: String, expected: Double, actual: Double, delta: Double) = check(Math.abs(expected - actual) <= delta) { "$message: expected <$expected> but was <$actual>" }
private fun assertEquals(expected: Double, actual: Double, delta: Double) = assertEquals("", expected, actual, delta)
private fun assertTrue(message: String, value: Boolean) = check(value) { message }
private fun assertTrue(value: Boolean) = assertTrue("not true", value)
private fun assertFalse(value: Boolean) = assertTrue("not false", !value)
private fun assertNull(message: String, value: Any?) = check(value == null) { "$message: <$value> is not null" }

fun main() {
    val test = PresetTest()
    test.auditLayersSurviveSaveAndApplyToAnotherLength()
    test.effectsStyleAndSpeedReplaceTheTargetsAndKeepItsOwnValues()
    test.fileIsAScenePresetAndTitlesAreUnique()
    println("PresetTest: 3 checks passed")
}

class PresetTest {
    private fun node(e: Element): XmlNode {
        val attrs = LinkedHashMap<String, String>()
        for (i in 0 until e.attributes.length) e.attributes.item(i).let { attrs[it.nodeName] = it.nodeValue }
        val kids = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>().map(::node)
        return XmlNode(e.tagName, attrs, kids, if (kids.isEmpty()) e.textContent else "")
    }

    private fun parse(file: File) = node(DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement)

    private fun shape(start: Int, end: Int, vararg children: XmlNode) =
        Layer(XmlNode("shape", linkedMapOf("id" to "1", "label" to "Fresh", "startTime" to "$start", "endTime" to "$end", "fillType" to "color", "s" to ".rect"), children.toList()))

    private fun times(l: Layer, name: String) = l.node.child("transform")?.child(name)?.keyframes?.map { it.t }

    private fun hasMotion(n: XmlNode): Boolean = n.name == "kf" || n.name == "effect" || n.children.any(::hasMotion)

    @Test fun auditLayersSurviveSaveAndApplyToAnotherLength() {
        val project = Scene(parse(TestData.scene("audit")))
        val animated = project.layers.filter { hasMotion(it.node) }
        assertTrue("the audit file has an animated layer", animated.isNotEmpty())
        for (layer in animated) {
            val preset = presetScene(project, layer, "Preset 1", 0)
            assertEquals("preset", preset.node["type"])
            assertEquals("107", preset.node["ffver"])
            val saved = preset.layers.single()
            assertFalse(saved.hidden)
            val fresh = shape(500, 1500)
            val applied = applyPreset(fresh, saved, "id-1")
            for (name in listOf("scale", "location", "rotation", "skew", "pivot")) {
                val wanted = times(saved, name)
                if (wanted != null && wanted.isNotEmpty()) assertEquals("${layer.label} $name times", wanted, times(applied, name))
                else assertNull("${layer.label} $name stays the target's", times(applied, name))
            }
            assertEquals(saved.effects, applied.effects)
            assertEquals("id-1", applied.node["presetId"])
            val ratio = (applied.endTime - applied.startTime).toDouble() / (saved.endTime - saved.startTime)
            for (t in times(saved, "skew").orEmpty()) assertEquals(500 + t * 1000.0, applied.timeOf(t).toDouble(), 1.0)
            assertEquals(1000, applied.endTime - applied.startTime)
            assertTrue(ratio < 1)
        }
    }

    @Test fun effectsStyleAndSpeedReplaceTheTargetsAndKeepItsOwnValues() {
        fun effect(id: String) = XmlNode("effect", linkedMapOf("id" to id), listOf(XmlNode("property", linkedMapOf("name" to "radius", "type" to "float", "value" to "4"))))
        val source = shape(
            0, 2000,
            XmlNode("transform", children = listOf(
                XmlNode("location", mapOf("value" to "10,10,0")),
                XmlNode("scale", children = listOf(XmlNode("kf", mapOf("t" to "0.250000", "v" to "1,1")), XmlNode("kf", mapOf("t" to "0.750000", "v" to "2,2")))),
                XmlNode("opacity", mapOf("value" to "0.500000")),
            )),
            effect("a"), effect("b"),
            XmlNode("border", mapOf("direction" to "outside")), XmlNode("shadow"),
        ).let { it.copy(node = it.node.with("blending", "multiply").with("speed", "2")) }
        assertTrue(canSavePreset(source))
        assertFalse(canSavePreset(shape(0, 1000)))
        val target = shape(0, 1000, XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "99,99,0")))), effect("old"))
        val applied = applyPreset(target, source)
        assertEquals(listOf("a", "b"), applied.effects.map { it["id"] })
        assertEquals("multiply", applied.node["blending"])
        assertEquals("0.500000", applied.node.child("transform")?.child("opacity")?.get("value"))
        assertEquals("99,99,0", applied.node.child("transform")?.child("location")?.get("value"))
        assertEquals(listOf(0.25f, 0.75f), times(applied, "scale"))
        assertEquals(1, applied.node.children.count { it.name == "border" })
        assertEquals("2", applied.node["speed"])
        assertEquals(500, applied.endTime)
        assertEquals("Fresh", applied.label)
    }

    @Test fun fileIsAScenePresetAndTitlesAreUnique() {
        val project = Scene.create("p", 1080, 1920, 60, 0)
        val layer = shape(0, 1000, XmlNode("shadow")).let { it.copy(node = it.node.with("label", null).with("parent", null)) }
        val dir = File.createTempFile("presets", "").apply { delete(); mkdirs() }
        val id = PresetStore(dir).save(presetScene(project, layer, presetTitle(layer, listOf("Preset 1")), 0))
        val file = parse(File(dir, "$id.xml"))
        assertEquals("preset", file["type"])
        assertEquals("Preset 2", file["title"])
        assertEquals("1080", file["width"])
        assertEquals("shape", file.children.single { it.name == "shape" }.name)
        dir.deleteRecursively()
    }
}
