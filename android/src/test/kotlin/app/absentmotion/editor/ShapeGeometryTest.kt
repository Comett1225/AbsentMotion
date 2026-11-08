package app.absentmotion.editor

import app.absentmotion.TestData
import app.absentmotion.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ShapeGeometryTest {
    private val catalogue = XmlNode.parse(TestData.project.resolve("shared/assets/shapes.xml").readText()).children
    private val original = ShapeOutlines.native

    @Before fun needsTheHostEngine() {
        Assume.assumeTrue("build it with tools/pivot.sh --lib", PivotEngine.loaded)
        ShapeOutlines.native = { scene, t, rounded -> PivotEngine.nativeShapeOutline(scene, t, rounded) }
    }

    @After fun restore() {
        ShapeOutlines.native = original
    }

    private fun prop(name: String, value: String, type: String = if (',' in value) "vec2" else "float") =
        XmlNode("property", linkedMapOf("name" to name, "type" to type, "value" to value))

    private fun keyed(name: String, from: String, to: String) =
        XmlNode("property", linkedMapOf("name" to name, "type" to "float"), listOf(XmlNode("kf", linkedMapOf("t" to "0", "v" to from)), XmlNode("kf", linkedMapOf("t" to "1", "v" to to))))

    private fun template(id: String, vararg changes: XmlNode, bare: Boolean = false): Layer {
        val entry = catalogue.single { it["outlineShape"] == id }
        val names = changes.map { it["name"] }.toSet()
        val kept = if (bare) emptyList() else entry.children.filter { it["name"] !in names }
        return Layer(XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "1000", "fillType" to "color", "outlineShape" to id), kept + changes))
    }

    private fun shape(attrs: Map<String, String>, vararg children: XmlNode) =
        Layer(XmlNode("shape", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "1000", "fillType" to "color") + attrs, children.toList()))

    private fun xml(layer: Layer) = XmlNode("scene", mapOf("width" to "1", "height" to "1"), listOf(layer.node)).toXml()

    private fun svg(layer: Layer, t: Float = 0f, rounded: Boolean = true) = PivotEngine.nativeShapeOutline(xml(layer), t, rounded)!!

    private fun inside(layer: Layer, vararg points: Pair<Float, Float>, t: Float = 0f): List<Boolean> =
        PivotEngine.nativeShapeHits(xml(layer), t, points.flatMap { listOf(it.first, it.second) }.toFloatArray())!!.toList()

    private fun grid(layer: Layer, half: Float = 120f, steps: Int = 48): List<Boolean> =
        inside(layer, *Array((steps + 1) * (steps + 1)) { i -> Pair(-half + 2 * half * (i % (steps + 1)) / steps, -half + 2 * half * (i / (steps + 1)) / steps) })

    private fun along(c: Contour): List<Pt> = c.knots.indices.flatMap { i ->
        val a = c.knots[i]
        val b = c.knots[(i + 1) % c.knots.size]
        val c1 = a.outHandle ?: a.p
        val c2 = b.inHandle ?: b.p
        (0 until 16).map { k ->
            val t = k / 16f
            val u = 1 - t
            a.p * (u * u * u) + c1 * (3 * u * u * t) + c2 * (3 * u * t * t) + b.p * (t * t * t)
        }
    }

    private fun teeth(layer: Layer, t: Float = 0f): Int {
        val ring = inside(layer, *Array(1440) { i -> Pair(90f * cos(i * Math.PI / 720).toFloat(), 90f * sin(i * Math.PI / 720).toFloat()) }, t = t)
        return ring.indices.count { ring[it] && !ring[(it + ring.size - 1) % ring.size] }
    }

    @Test fun everyTemplateDrawsClosedOutlinesThatFillItsSize() {
        val problems = ArrayList<String>()
        for (entry in catalogue.filter { it["outlineShape"] != null }) {
            val id = entry["outlineShape"]!!
            val contours = parseSvgPath(svg(template(id)))
            if (contours.isEmpty() || contours.any { !it.closed || it.knots.size < 2 }) { problems += "$id has an open or empty outline"; continue }
            val size = entry.children.single { it["name"] == "size" }["value"]!!.split(',').map { it.toFloat() }
            val points = contours.flatMap(::along)
            val w = (points.maxOf { it.x } - points.minOf { it.x }) / (2 * size[0])
            val h = (points.maxOf { it.y } - points.minOf { it.y }) / (2 * size[1])
            if (maxOf(w, h) !in 0.85f..1.25f) problems += "$id spans ${w}x$h of its size"
            if (abs(points.maxOf { it.x } + points.minOf { it.x }) > 0.3f * size[0] || abs(points.maxOf { it.y } + points.minOf { it.y }) > 0.3f * size[1]) problems += "$id is not centred"
            val doubled = contours.any { c -> c.knots.indices.any { c.knots[it].p.distanceTo(c.knots[(it + 1) % c.knots.size].p) < 1e-3f } }
            if (doubled) problems += "$id repeats a point"
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    @Test fun catalogueDefaultsAndEngineFallbacksDrawTheSameShape() {
        for (id in ShapeCatalog.templates) assertEquals(id, svg(template(id)), svg(template(id, bare = true)))
    }

    @Test fun roundCornersRoundsParametricTemplateAndPointEditedShapes() {
        val probes = arrayOf(97f to 97f, 0f to 0f, 99f to 0f)
        val square = shape(mapOf("s" to ".rect"), prop("size", "100,100"))
        assertEquals(listOf(true, true, true), inside(square, *probes))
        assertEquals(listOf(false, true, true), inside(shape(mapOf("s" to ".rect"), prop("size", "100,100"), prop("roundCorners", "30")), *probes))
        val path = XmlNode("path", mapOf("d" to "M -100 -100 L 100 -100 L 100 100 L -100 100 Z"))
        assertEquals(listOf(true, true, true), inside(shape(emptyMap(), path), *probes))
        assertEquals(listOf(false, true, true), inside(shape(emptyMap(), path, prop("roundCorners", "30")), *probes))
        assertEquals("a huge radius stops at half of each edge", listOf(false, true, true), inside(shape(emptyMap(), path, prop("roundCorners", "1e9")), *probes))
        for (bad in listOf("-20", "NaN", "inf")) assertEquals(bad, svg(shape(emptyMap(), path)), svg(shape(emptyMap(), path, prop("roundCorners", bad))))
        assertEquals("the heart tip is a curve-to-curve corner", listOf(true), inside(template("heart"), 0f to 87f))
        assertEquals(listOf(false), inside(template("heart", prop("roundCorners", "30")), 0f to 87f))
        assertEquals(listOf(true), inside(template("parallelogram", prop("roundCorners", "0")), -98f to 61f))
        assertEquals(listOf(false), inside(template("parallelogram", prop("roundCorners", "40")), -98f to 61f))
    }

    @Test fun gearTeethFollowOneCountRuleWhenStillAndWhenAnimated() {
        assertEquals(8, teeth(template("gear")))
        assertEquals(12, teeth(template("gear", prop("teeth", "12"))))
        assertEquals(64, teeth(template("gear", prop("teeth", "1000"))))
        assertEquals(3, teeth(template("gear", prop("teeth", "2"))))
        assertEquals(9, teeth(template("gear", prop("teeth", "8.5"))))
        assertEquals(8, teeth(template("gear", prop("teeth", "8.49"))))
        val growing = template("gear", keyed("teeth", "6", "12"))
        assertEquals(6, teeth(growing, 0f))
        assertEquals(9, teeth(growing, 0.5f))
        assertEquals(12, teeth(growing, 1f))
        assertEquals("the panel rounds counts the same way", 9f, ShapeCatalog.paramsFor(growing).single { it.prop == "teeth" }.bounded(8.5f))
    }

    @Test fun animatedOptionsAreEvaluatedAtTheirTime() {
        val heart = template("heart", keyed("notch", "0", "80"))
        assertEquals(svg(template("heart", prop("notch", "0"))), svg(heart, 0f))
        assertEquals(svg(template("heart", prop("notch", "40"))), svg(heart, 0.5f))
        assertNotEquals(svg(heart, 0f), svg(heart, 1f))
        val ring = template("ring", keyed("sweep", "90", "360"))
        assertEquals(listOf(true, false), inside(ring, 60f to -60f, -60f to -60f, t = 0f))
        assertEquals(listOf(true, true), inside(ring, 60f to -60f, -60f to -60f, t = 1f))
        assertEquals(svg(template("ring", prop("sweep", "225"))), svg(ring, 0.5f))
    }

    @Test fun holesStayHolesAndIslandsStayFilled() {
        assertEquals(listOf(false, true), inside(template("ring"), 0f to 0f, 80f to 0f))
        assertEquals(listOf(true, false, true), inside(template("record"), 0f to 0f, 72f to 0f, 95f to 0f))
        assertEquals(listOf(true, false, true), inside(template("eye"), 0f to 0f, 32f to 0f, -80f to 0f))
        assertEquals(listOf(false, true), inside(template("ticket"), 44f to 0f, 0f to 0f))
        assertEquals(listOf(false, true), inside(template("gear"), 0f to 0f, 50f to 0f))
        assertEquals("screen, border, camera", listOf(false, true, true), inside(template("phone"), 0f to 0f, 54f to 0f, 0f to -83.6f))
        assertEquals("the notch fills the top of the screen", listOf(true), inside(template("phone", prop("notch", "true", "bool")), 0f to -90f))
        assertEquals("side buttons stick out", listOf(false, true), inside(template("phone"), 58f to -30f).plus(inside(template("phone", prop("buttons", "true", "bool")), 58f to -30f)))
        assertEquals("no border, no screen", listOf(true), inside(template("phone", prop("border", "0")), 0f to 0f))
        assertEquals("header, cell", listOf(true, false), inside(template("panel"), 0f to -62f, 0f to 20f))
        assertEquals("columns are split by the border", listOf(false, true, false), inside(template("panel", prop("columns", "3")), -60f to 20f, -32.3f to 20f, 0f to 20f))
    }

    @Test fun badValuesFallBackOrDrawNothing() {
        assertTrue("no width, nothing to draw", parseSvgPath(svg(template("heart", prop("size", "0,90")))).isEmpty())
        assertEquals("a mirrored size draws the same", svg(template("heart")), svg(template("heart", prop("size", "-100,-90"))))
        assertEquals(svg(template("gear")), svg(template("gear", prop("toothDepth", "NaN"), prop("hole", "inf"))))
        assertEquals("a thickness over 100% is a full disc", svg(template("ring", prop("thickness", "100"))), svg(template("ring", prop("thickness", "1e30"))))
        val stored = XmlNode("path", mapOf("d" to "M -10 -10 L 10 -10 L 10 10 L -10 10 Z"))
        val future = shape(mapOf("outlineShape" to "future-shape"), stored, prop("roundCorners", "8"))
        assertEquals("an unknown outline from a newer version draws its stored outline as it is", svg(shape(emptyMap(), stored)), svg(future))
    }

    @Test fun theFinishedOutlineIsWhatOlderReadersDraw() {
        for (layer in listOf(template("gear", prop("teeth", "11"), prop("roundCorners", "6")), template("phone", prop("notch", "true", "bool")), template("heart", keyed("notch", "10", "80")))) {
            val finished = layer.withFinishedOutline()
            assertEquals("evenodd", finished.node.child("path")!!["fillRule"])
            val older = finished.copy(node = finished.node.with("outlineShape", "not-known-yet"))
            val now = grid(layer)
            val then = grid(older)
            assertTrue("${layer.outlineShape}: ${now.indices.count { now[it] != then[it] }} grid points differ", now.indices.count { now[it] != then[it] } <= now.size / 200)
        }
    }

    @Test fun anOutlineEditedInAnOlderVersionStaysAsItWasEdited() {
        val finished = template("gear", prop("roundCorners", "6")).withFinishedOutline()
        assertTrue(finished.isShapeTemplate)
        val square = XmlNode("path", mapOf("d" to "M -50 -50 L 50 -50 L 50 50 L -50 50 Z"))
        val edited = finished.copy(node = finished.node.withChild("path", square))
        assertFalse(edited.isShapeTemplate)
        assertTrue("its options are hidden until it becomes points", ShapeCatalog.paramsFor(edited).isEmpty())
        assertEquals("drawn as edited and not rounded twice", listOf(true, false), inside(edited, 49f to 49f, 80f to 0f))
        val scene = Scene(Scene.create("t", 400, 400, 30, 0).node.copy(children = listOf(edited.node)))
        val changed = scene.update(1) { it.withProp("teeth", "float", "20") }
        assertSame(square, changed.withOutlinesFollowing(scene).layer(1)!!.node.child("path"))
        val points = edited.asPoints()!!
        assertNull(points.node["outlineMark"])
        assertTrue(points.node.children.none { it.name == "property" })
        assertEquals(grid(edited), grid(points))
        val animated = finished.copy(node = finished.node.withChild("path", XmlNode("path", children = listOf(XmlNode("knot")))))
        assertFalse("a path animated point by point is an edit too", animated.isShapeTemplate)
    }

    @Test fun editsRewriteTheFinishedOutlineOnlyWhenOptionsChange() {
        val drawn = shape(mapOf("label" to "old heart"), XmlNode("path", mapOf("d" to "M 0 0 L 10 0 L 10 10 Z")))
        val start = Scene(Scene.create("t", 400, 400, 30, 0).node.copy(children = listOf(template("gear").node, drawn.withId(2).node)))
        val made = start.withOutlinesFollowing(Scene(start.node.copy(children = emptyList())))
        val path = made.layer(1)!!.node.child("path")!!
        assertEquals(svg(template("gear")), path["d"])
        assertTrue(made.layer(1)!!.isShapeTemplate)
        assertSame("a plain outline is left alone", start.layer(2)!!.node, made.layer(2)!!.node)
        val moved = made.update(1) { it.withTransform("location", 50f, 50f, 0f) }
        assertSame("moving does not redraw the outline", path, moved.withOutlinesFollowing(made).layer(1)!!.node.child("path"))
        val changed = made.update(1) { it.withProp("teeth", "float", "16") }.withOutlinesFollowing(made)
        assertEquals(svg(template("gear", prop("teeth", "16"))), changed.layer(1)!!.node.child("path")!!["d"])
        val reopened = Scene.parse(changed.toXml())
        assertEquals("a saved project opens with the same options and outline", changed.layer(1)!!.node.children, reopened.layer(1)!!.node.children)
        val group = XmlNode("embedScene", linkedMapOf("id" to "9", "startTime" to "0", "endTime" to "1000"), listOf(XmlNode("scene", mapOf("width" to "400", "height" to "400"), listOf(template("star-outline").node))))
        val grouped = Scene(start.node.copy(children = listOf(group))).withOutlinesFollowing(Scene(start.node.copy(children = emptyList())))
        assertNotNull("layers inside a group get their outline too", grouped.layer(9)!!.nested!!.layer(1)!!.node.child("path"))
    }

    @Test fun convertingToPointsKeepsTheLookAndRoundCornersStaysLive() {
        val gear = template("gear", prop("teeth", "10"), prop("roundCorners", "6"))
        val points = gear.asPoints()!!
        assertNull(points.outlineShape)
        assertEquals(listOf("roundCorners"), points.node.children.filter { it.name == "property" }.map { it["name"] })
        assertEquals("the outer edge and the hole", 2, points.contours!!.size)
        assertTrue(points.contours!!.drop(1).all { it.exclude })
        val before = grid(gear)
        val after = grid(points)
        assertTrue(before.indices.count { before[it] != after[it] } <= before.size / 200)
        assertFalse(gear.pointsLoseAnimation)
        assertFalse("Round Corners keeps its keyframes", template("gear", keyed("roundCorners", "0", "9")).pointsLoseAnimation)
        assertTrue(template("gear", keyed("teeth", "6", "12")).pointsLoseAnimation)
        val star = shape(mapOf("s" to ".star"), prop("pointCount", "6"), prop("outerRadius", "100"), prop("innerRadius", "40"), prop("roundCorners", "10"))
        val starPoints = star.asPoints()!!
        assertEquals("", starPoints.shape)
        val sharp = grid(star)
        val converted = grid(starPoints)
        assertTrue(sharp.indices.count { sharp[it] != converted[it] } <= sharp.size / 200)
        val future = shape(mapOf("outlineShape" to "future-shape"), XmlNode("path", mapOf("d" to "M -10 -10 L 10 -10 L 10 10 L -10 10 Z")), prop("roundCorners", "8"))
        val plain = future.asPoints()!!
        assertTrue("an unknown outline keeps its finished points and drops options that would round it twice", plain.node.children.none { it.name == "property" })
        assertEquals(grid(future), grid(plain))
        assertNull("a plain outline is already points", shape(emptyMap(), XmlNode("path", mapOf("d" to "M 0 0 L 9 0 L 9 9 Z"))).asPoints())
    }

    @Test fun anOpenLineKeepsItsOutlineAndPhoneRoundingIsBakedWithAWarning() {
        val line = shape(mapOf("s" to ".line"))
        assertTrue("an open line is not simplified away", svg(line).contains("L"))
        assertNotNull(line.asPoints())
        val phone = template("phone", keyed("roundCorners", "0", "20"))
        assertTrue(phone.pointsLoseAnimation)
        assertTrue(phone.asPoints()!!.node.children.none { it.name == "property" })
    }

    @Test fun oldProjectsOpenUnchanged() {
        val heart = XmlNode("path", mapOf("d" to "M 0 90 C -25 70 -100 20 -100 -30 C -100 -70 -70 -90 -45 -90 C -22 -90 -6 -75 0 -57 C 6 -75 22 -90 45 -90 C 70 -90 100 -70 100 -30 C 100 20 25 70 0 90 Z"))
        val old = shape(mapOf("label" to "Heart 1"), heart)
        assertFalse(old.isShapeTemplate)
        assertEquals(listOf("roundCorners"), ShapeCatalog.paramsFor(old).map { it.prop })
        val scene = Scene(Scene.create("t", 400, 400, 30, 0).node.copy(children = listOf(old.node)))
        assertSame(scene.node, scene.withOutlinesFollowing(Scene(scene.node.copy(children = emptyList()))).node)
        val probes = arrayOf(0f to 0f, 0f to 88f, -99f to -30f, 0f to -75f)
        assertEquals(listOf(true, true, true, false), inside(old, *probes))
        assertEquals("Round Corners on an old outline", listOf(true, false, true, false), inside(shape(mapOf("label" to "Heart 1"), heart, prop("roundCorners", "30")), *probes))
    }
}
