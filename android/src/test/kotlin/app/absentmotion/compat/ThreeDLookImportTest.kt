package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow

class ThreeDLookImportTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun result(effect: String): EffectMigration.Result = migration.migrate(
        Scene.parse("""<scene title="c" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
            $effect<property name="size" type="vec2" value="110,82.5" /></shape></scene>"""),
    )

    private fun migrate(effect: String): XmlNode = result(effect).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }

    private fun XmlNode.text(name: String): String = children.single { it["name"] == name }["value"]!!

    private fun XmlNode.floats(name: String): List<Float> = text(name).split(',').map { it.trim().toFloat() }

    private fun near(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals("$expected vs $actual", expected[it], actual[it], 1e-4f) }
    }

    private fun alight(id: String, props: String = "") = """<effect id="com.alightcreative.effects.$id">$props</effect>"""

    private fun p(name: String, type: String, value: String) = """<property name="$name" type="$type" value="$value" />"""

    @Test fun untouchedSolidsGetAlightsSoftLightAndTiledPicture() {
        for (id in listOf("box", "cube2", "cylinder", "hexprism", "octahedron", "torus", "spherize2")) {
            val e = migrate(alight(id))
            assertEquals(id, 2f, e.floats("lighting")[0])
            assertEquals(id, 0.027f, e.floats("ambient")[0], 1e-6f)
            assertEquals(id, 0.8f, e.floats("shading")[0], 1e-6f)
            assertEquals(id, 0.8f, e.floats("shine")[0], 1e-6f)
            assertEquals(id, 44.1f, e.floats("glossiness")[0], 1e-4f)
            assertEquals(id, 4.714f, e.floats("lightHeight")[0], 1e-4f)
            assertEquals(id, 45f, e.floats("light")[0], 1e-6f)
            assertEquals(id, 1f, e.floats("texMode")[0])
        }
        assertEquals(2f, migrate(alight("extrude")).floats("lighting")[0])
        val star = migrate(alight("starprism"))
        assertEquals(listOf(0f, 0.64f), listOf("shine", "shading").map { star.floats(it)[0] })
    }

    @Test fun alightsLightSettingsBecomeTheSoftLight() {
        val r = result(alight("cylinder", p("shadingType", "int", "1") + p("lightIntensity", "float", "1.25") + p("ambient", "float", "0.325") +
            p("spec", "float", "60") + p("shine", "float", "0.85") + p("lightColor", "color", "#FF3366CC") + p("lightDir", "vec2", "115,-100") + p("lightDepth", "float", "275")))
        val e = r.scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
        assertEquals(1f, e.floats("shading")[0], 1e-5f)
        assertEquals(0.325f * 0.27f, e.floats("ambient")[0], 1e-5f)
        assertEquals(88.2f, e.floats("glossiness")[0], 1e-4f)
        assertEquals(0.85f, e.floats("shine")[0], 1e-6f)
        assertEquals("#ff3366cc", e.text("lightColor").lowercase())
        assertEquals(Math.toDegrees(atan2(100.0, 115.0)).toFloat(), e.floats("light")[0], 1e-3f)
        assertEquals(5f * 275f / hypot(115f, 100f), e.floats("lightHeight")[0], 1e-3f)
        assertTrue(r.dropped.toString(), r.dropped.isEmpty())
        val plain = migrate(alight("box", p("lightDir", "vec2", "75,-75") + p("lightDepth", "float", "100")))
        assertEquals(migrate(alight("box")).floats("lightHeight")[0], plain.floats("lightHeight")[0], 0.01f)
        assertEquals(45f, plain.floats("light")[0], 1e-4f)
    }

    @Test fun textureSettingsTileMirrorTurnAndCrop() {
        val e = migrate(alight("box", p("mirror", "bool", "true") + p("texStretch", "bool", "true") + p("texRepeatX", "float", "5.75") + p("texRepeatY", "float", "4") +
            p("texAngle", "float", "900") + p("texCrop", "float", "1.25") + p("texOffset", "vec2", "40,-25")))
        assertEquals("true", e.text("texMirror"))
        assertEquals(2f, e.floats("texMode")[0])
        assertEquals(listOf(5.75f, 4f, 900f, 1.25f), listOf("texRepeatX", "texRepeatY", "texAngle", "texCrop").map { e.floats(it)[0] })
        assertEquals(listOf(0.4f, 0.25f), e.floats("texOffset"))
        assertEquals(1f, migrate(alight("box", p("texStretch", "bool", "false"))).floats("texMode")[0])
    }

    @Test fun untouchedCombinedViewerLeavesThePanoramaAndFlattenedOneLooksThroughIt() {
        val panorama = migrate(alight("vrcombined"))
        assertEquals("absent.viewer360", panorama["id"])
        assertEquals(1f, panorama.floats("projection")[0])
        assertEquals(listOf(0f, 0f, 0f), listOf("yaw", "pitch", "roll").map { panorama.floats(it)[0] })
        val view = migrate(alight("vrcombined", p("flatten", "bool", "true") + p("dist", "float", "1.25") + p("w", "float", "20") + p("h", "float", "14.75")))
        assertEquals(0f, view.floats("projection")[0])
        assertEquals(0.25f, view.floats("eyeDistance")[0], 1e-6f)
        assertEquals(20f / 14.75f, view.floats("frame")[0], 1e-4f)
    }

    @Test fun playerLooksFromTheCentreOfTheSphereThroughA16By9Frame() {
        val e = migrate(alight("vrplayer"))
        assertEquals(listOf(90f, 0f, 0f), listOf("yaw", "pitch", "roll").map { e.floats(it)[0] })
        assertEquals(listOf(0f, 0f, 1.7778f, 70f), listOf("projection", "eyeDistance", "frame", "fov").map { e.floats(it)[0] })
        val zoomed = migrate(alight("vrplayer", p("scale", "float", "1.25") + p("aspectRatio", "int", "1") + p("dist", "float", "1.5")))
        assertEquals(70f * 1.25f.pow(-0.8f), zoomed.floats("fov")[0], 1e-3f)
        assertEquals(0.5f, zoomed.floats("eyeDistance")[0], 1e-6f)
        assertEquals(1.3333f, zoomed.floats("frame")[0], 1e-4f)
    }

    @Test fun untouchedPlusIsASmallLowCrossWithoutTheLayer() {
        val e = migrate(alight("plus"))
        assertEquals("false", e.text("keep"))
        assertEquals(listOf(0f, 13.5f), e.floats("position"))
        assertEquals(0.36f, e.floats("reach")[0], 1e-6f)
        assertEquals(0.18f, e.floats("thickness")[0], 1e-6f)
    }

    @Test fun untouchedPyramidShowsAnEdgeFromSlightlyAbove() {
        val e = migrate(alight("pyramid"))
        assertEquals(listOf(-8.45f, 44.69f, 5.97f, 1f), listOf("rotX", "rotY", "rotZ", "scale").map { e.floats(it)[0] })
        assertEquals(0.69f, e.floats("base")[0], 1e-6f)
        assertEquals(0.5f, e.floats("perspective")[0], 1e-6f)
        assertEquals(0.95f, e.floats("height")[0], 1e-6f)
        assertEquals(1.5f, migrate(alight("pyramid", p("scale", "float", "1.5"))).floats("scale")[0], 1e-6f)
    }

    @Test fun starPolyhedronBecomesAPointedStar() {
        val e = migrate(alight("starpoly"))
        assertEquals("absent.starprism", e["id"])
        assertEquals(listOf(1f, 5f, 0.89f, 0.5f), listOf("starStyle", "points", "radius", "inner").map { e.floats(it)[0] })
        val seven = migrate(alight("starpoly", p("pointCount", "float", "7") + p("pointLength", "float", "2")))
        assertEquals(7f, seven.floats("points")[0], 1e-6f)
        assertEquals(1.78f, seven.floats("radius")[0], 1e-5f)
    }

    @Test fun oldSpherizeSwitchesItsDirectLightWithAlightsLightSwitch() {
        val lit = migrate(alight("spherize"))
        near(listOf(2f, 0.984f, 0.008f, 1.886f, 45f, 0f), listOf("lighting", "shading", "ambient", "lightHeight", "light", "shine").map { lit.floats(it)[0] })
        val flat = migrate(alight("spherize", p("enableLight", "bool", "false")))
        near(listOf(0f, 0f), listOf("lighting", "shading").map { flat.floats(it)[0] })
        val aimed = migrate(alight("spherize", p("lightDir", "vec2", "115,-100") + p("lightDepth", "float", "225") + p("lightColor", "color", "#FF3366CC")))
        assertEquals(Math.toDegrees(atan2(100.0, 115.0)).toFloat(), aimed.floats("light")[0], 1e-3f)
        assertEquals(2f * 225f / hypot(115f, 100f), aimed.floats("lightHeight")[0], 1e-3f)
        assertEquals("#ff3366cc", aimed.text("lightColor").lowercase())
    }

    @Test fun oldSpherizeIsAnOrthographicGlobeTiledFromTheCorner() {
        val plain = migrate(alight("spherize"))
        near(listOf(1f, 0f, 0.985f, 1f, 1f, 1f), listOf("projection", "perspective", "radius", "scale", "texMode", "texAnchor").map { plain.floats(it)[0] })
        near(listOf(0f, 90f, 0f), listOf("rotX", "rotY", "rotZ").map { plain.floats(it)[0] })
        val r = result(alight("spherize", p("repeatx", "float", "5.75") + p("repeaty", "float", "4") + p("mirror", "bool", "true") + p("zdist", "float", "2.5")))
        val e = r.scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
        near(listOf(5.75f, 4f, 1f), listOf("texRepeatX", "texRepeatY", "scale").map { e.floats(it)[0] })
        assertEquals("true", e.text("texMirror"))
        assertTrue(r.dropped.toString(), r.dropped.isEmpty())
    }

    @Test fun oldSpherizeTurnsTheOtherWayRoundToTheSolids() {
        val rule = { inverse: Boolean -> EffectMigration.parse("""{"com.alightcreative.effects.spherize": {"to": "spherize", "params": {}, "set": {"rotY": 90},
            "orientation": {"quat": "orient", "to": ["rotX", "rotY", "rotZ"], "inverse": $inverse}}}""", catalog) }
        val turned = { m: EffectMigration, quat: String -> m.migrate(Scene.parse("""<scene title="c" width="1080" height="1080" fps="30" totalTime="1000"><shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect">
            <effect id="com.alightcreative.effects.spherize"><property name="orient" type="quat" value="$quat" /></effect><property name="size" type="vec2" value="110,82.5" /></shape></scene>"""))
            .scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }.let { e -> listOf("rotX", "rotY", "rotZ").map { e.floats(it)[0] } } }
        val tilt = "0.130526,0.200000,0.050000,0.970000"
        val inverted = turned(rule(true), tilt)
        val plain = turned(rule(false), "-0.130526,-0.200000,-0.050000,0.970000")
        for (i in 0..2) assertEquals(plain[i], inverted[i], 1e-3f)
        assertTrue((0..2).any { abs(turned(rule(false), tilt)[it] - inverted[it]) > 1f })
        val real = migrate(alight("spherize", p("orient", "quat", tilt)))
        assertEquals(inverted, listOf("rotX", "rotY", "rotZ").map { real.floats(it)[0] })
    }

    @Test fun flipShowsItsMirroredBackFarBehindItsPivot() {
        val r = result(alight("flip3", p("angle", "float", "900") + p("crop", "float", "1.25") + p("zdist", "float", "2.5") + p("pivot", "vec2", "40,-25") + p("solid", "bool", "true")))
        val e = r.scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
        near(listOf(-900f, 0f, 2f, 1.25f, 1.25f, 2.5f, 0.633f), listOf("rotY", "back", "texMode", "texRepeatX", "texRepeatY", "pivotDepth", "perspective").map { e.floats(it)[0] })
        near(listOf(40f, 25f), e.floats("pivot"))
        assertTrue(r.dropped.toString(), r.dropped.any { it.endsWith("solid") })
        val plain = migrate(alight("flip"))
        near(listOf(0f, 2f), listOf("back", "texMode").map { plain.floats(it)[0] })
        assertTrue(plain.children.none { it["name"] == "pivotDepth" })
    }

    @Test fun untouchedStarPrismIsAFatterStarWithThePictureOnItsBoundingBox() {
        val e = migrate(alight("starprism"))
        near(listOf(0.5f, 1.0515f, 1.1056f), listOf("inner", "texRepeatX", "texRepeatY").map { e.floats(it)[0] })
        near(listOf(0f, -0.0527f), e.floats("texOffset"))
        val tiled = migrate(alight("starprism", p("texRepeatX", "float", "2") + p("texRepeatY", "float", "3")))
        near(listOf(2.103f, 3.3168f), listOf("texRepeatX", "texRepeatY").map { tiled.floats(it)[0] })
    }

    @Test fun spherizeTwoWrapsThePictureRoundTheGlobe() {
        assertEquals(1f, migrate(alight("spherize2")).floats("projection")[0])
    }

    @Test fun untouchedOctahedronSitsLowerAndALittleLarger() {
        val e = migrate(alight("octahedron"))
        near(listOf(0f, 12.6f), e.floats("position"))
        assertEquals(0.99f, e.floats("radius")[0], 1e-6f)
    }

    @Test fun untouchedPageCurlRollsTheBottomRightCorner() {
        val e = migrate(alight("pagecurl"))
        near(listOf(0.31f, 0.24f, 56f, 0f), listOf("progress", "radius", "angle", "backAlpha").map { e.floats(it)[0] })
    }
}
