package app.absentmotion

import app.absentmotion.model.Layer
import app.absentmotion.model.ShapeCatalog
import app.absentmotion.model.ShapeParam
import app.absentmotion.model.XmlNode
import app.absentmotion.model.finiteFloat
import app.absentmotion.model.isShapeTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeCatalogTest {
    private val entries = XmlNode.parse(TestData.project.resolve("shared/assets/shapes.xml").readText()).children

    private fun layerOf(entry: XmlNode) = Layer(XmlNode("shape", linkedMapOf("id" to "1") + entry.attrs, entry.children))

    @Test fun nineteenParametricEntriesThenThirtySevenTemplates() {
        assertEquals(56, entries.size)
        val ids = entries.take(19).map { it["s"].orEmpty() }
        assertTrue("every parametric entry has an id", ids.all { it.startsWith(".") })
        assertEquals("ids are unique", 19, ids.toSet().size)
        val templates = entries.drop(19)
        assertTrue("templates have no parametric id", templates.all { it["s"].isNullOrEmpty() })
        val outlines = templates.map { it["outlineShape"].orEmpty() }
        assertEquals("template ids are unique", 37, outlines.toSet().size)
        assertEquals("every template is known to the options schema", ShapeCatalog.templates, outlines.toSet())
        assertEquals("names are unique", entries.size, entries.map { it["name"] }.toSet().size)
        assertTrue("the outline is written when a layer is made, not stored twice", templates.all { it.child("path") == null })
        assertTrue(templates.all { layerOf(it).isShapeTemplate })
    }

    @Test fun templateDefaultsInTheCatalogueMatchTheOptionSchema() {
        val problems = ArrayList<String>()
        for (entry in entries.drop(19)) {
            val name = entry["name"]
            val params = ShapeCatalog.paramsFor(layerOf(entry))
            val props = entry.children.filter { it.name == "property" }.associateBy { it["name"] }
            if (params.map { it.prop }.toSet() != props.keys) problems += "$name: schema ${params.map { it.prop }} but catalogue ${props.keys}"
            for (param in params) {
                val prop = props[param.prop] ?: continue
                if (prop["type"] != param.kind.type) problems += "$name.${param.prop}: type ${prop["type"]} vs ${param.kind.type}"
                val stored = prop["value"].orEmpty().split(',').map { it.trim().finiteFloat() ?: it.trim() }
                val wanted = param.default.split(',').map { it.trim().finiteFloat() ?: it.trim() }
                if (stored != wanted) problems += "$name.${param.prop}: $stored vs $wanted"
                if (param.kind != ShapeParam.Kind.Toggle) wanted.filterIsInstance<Float>().forEach { if (param.bounded(it) != it) problems += "$name.${param.prop}: default $it is out of bounds" }
            }
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    @Test fun roundCornersComesFirstAndNeverTwice() {
        for (entry in entries) {
            val params = ShapeCatalog.paramsFor(layerOf(entry))
            val rounding = params.filter { it.label == R.string.shape_param_round_corners }
            val id = entry["s"].orEmpty()
            if (id == ".circle" || id == ".line") {
                assertTrue("$id has no corners to round", rounding.isEmpty())
                continue
            }
            assertEquals("${entry["name"]} has one Round Corners row", 1, rounding.size)
            assertEquals("${entry["name"]} lists Round Corners first", rounding.single(), params.first())
            assertTrue("${entry["name"]}: Round Corners never goes below 0", params.first().min == 0f)
        }
        for (id in listOf(".roundrect", ".callout.roundrect", ".stamp")) {
            val params = ShapeCatalog.paramsFor(layerOf(entries.single { it["s"] == id }))
            assertEquals("$id keeps its own corner radius as Round Corners", "cornerRadius", params.first().prop)
            assertFalse("$id gets no second rounding", params.any { it.prop == "roundCorners" })
        }
        val drawn = Layer(XmlNode("shape", mapOf("id" to "1"), listOf(XmlNode("path", mapOf("d" to "M 0 0 L 10 0 L 10 10 Z")))))
        assertEquals("a point-edited outline offers Round Corners only", listOf("roundCorners"), ShapeCatalog.paramsFor(drawn).map { it.prop })
        assertTrue(ShapeCatalog.paramsFor(Layer(XmlNode("text", mapOf("id" to "1")))).isEmpty())
        assertTrue("an outline from a newer version shows no options", ShapeCatalog.paramsFor(Layer(XmlNode("shape", mapOf("id" to "1", "outlineShape" to "future")))).isEmpty())
    }

    @Test fun countsRoundToTheNearestWholeNumberInsideTheirBounds() {
        val gear = ShapeCatalog.paramsFor(layerOf(entries.single { it["outlineShape"] == "gear" }))
        val teeth = gear.single { it.prop == "teeth" }
        assertEquals(64f, teeth.bounded(1000f))
        assertEquals(3f, teeth.bounded(-5f))
        assertEquals(9f, teeth.bounded(8.5f))
        assertEquals(8f, teeth.bounded(8.49f))
        assertEquals(100f, gear.single { it.prop == "toothWidth" }.bounded(150f))
        assertTrue("extras are behind Advanced", gear.single { it.prop == "toothBase" }.advanced)
        assertEquals(listOf("roundCorners", "size"), gear.take(2).map { it.prop })
    }
}
