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
        for (id in listOf("triprism")) {
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
        for (id in listOf("heart", "heart2")) {
            val heart = migrate(alight(id))
            assertEquals(id, listOf(1f, 550f, 2.5f, 1f), listOf("alight", "alZ", "heartSize", "shading").map { heart.floats(it)[0] })
            near(listOf(0f, 76f), heart.floats("position"))
        }
        val star = migrate(alight("starprism"))
        assertEquals(listOf(1f, 250f, 0.8f, 1f), listOf("alight", "alZ", "shine", "shading").map { star.floats(it)[0] })
        near(listOf(100f, -75f), star.floats("alLight"))
        assertEquals(listOf(1.5f, 0.2f, 0.547f), listOf("radius", "depth", "inner").map { star.floats(it)[0] })
    }

    @Test fun cubeUsesAlightsCameraAndLight() {
        val e = migrate(alight("cube2"))
        assertEquals(1f, e.floats("alight")[0])
        assertEquals(600f, e.floats("alZ")[0], 1e-6f)
        near(listOf(0f, 100f), e.floats("position"))
        assertEquals(0.1f, e.floats("ambient")[0], 1e-6f)
        assertEquals(1f, e.floats("shading")[0], 1e-6f)
        assertEquals(30f, e.floats("glossiness")[0], 1e-4f)
        assertEquals(45f, e.floats("light")[0], 1e-6f)
        assertEquals(3.8654f, e.floats("lightHeight")[0], 1e-4f)
        val moved = migrate(alight("cube2", p("position", "vec3", "-40,150,900") + p("lightDir", "vec2", "0,-50") + p("lightDepth", "float", "200") + p("lightIntensity", "float", "1.6")))
        near(listOf(-40f, 150f), moved.floats("position"))
        assertEquals(900f, moved.floats("alZ")[0], 1e-4f)
        assertEquals(90f, moved.floats("light")[0], 1e-4f)
        assertEquals(16.4f, moved.floats("lightHeight")[0], 1e-3f)
        assertEquals(1.6f, moved.floats("shading")[0], 1e-6f)
        near(listOf(75f, -75f), e.floats("alLight"))
        assertEquals(100f, e.floats("alElev")[0], 1e-6f)
        near(listOf(0f, -50f), moved.floats("alLight"))
        assertEquals(200f, moved.floats("alElev")[0], 1e-4f)
    }

    @Test fun oldCubeIsABoxWithThePictureStretchedOverEachFace() {
        val e = migrate(alight("cube"))
        assertEquals("absent.box", e["id"])
        near(listOf(1f, 1f, 2f, 100f, 2f), listOf("alight", "faceStyle", "texMode", "alZ", "scale").map { e.floats(it)[0] })
        near(listOf(0.5f, 0.5f, 0.5f, 0.895f, 0.021f), listOf("width", "height", "depth", "shading", "shine").map { e.floats(it)[0] })
        near(listOf(0f, 100f), e.floats("position"))
        val sized = migrate(alight("cube", p("width", "float", "2") + p("height", "float", "0.5") + p("enableLight", "bool", "false")))
        near(listOf(1f, 0.25f, 1f, 0f, 1f), listOf("width", "height", "shading", "shine", "lightStyle").map { sized.floats(it)[0] })
        assertEquals(150f, migrate(alight("cube", p("zdist", "float", "0.5"))).floats("alZ")[0], 1e-4f)
    }

    @Test fun hexagonalPrismStandsUpInAlightsCamera() {
        val e = migrate(alight("hexprism"))
        assertEquals(1f, e.floats("alight")[0])
        assertEquals(500f, e.floats("alZ")[0], 1e-6f)
        near(listOf(0f, 55f), e.floats("position"))
        assertEquals(listOf(1f, 3f), listOf("radius", "depth").map { e.floats(it)[0] })
        val sized = migrate(alight("hexprism", p("radius", "float", "1.6") + p("height", "float", "1.5")))
        assertEquals(listOf(1.6f, 1.5f), listOf("radius", "depth").map { sized.floats(it)[0] })
    }

    @Test fun ellipsoidUsesAlightsCameraAndItsOwnHeight() {
        val e = migrate(alight("ellipsoid"))
        assertEquals(1f, e.floats("alight")[0])
        assertEquals(550f, e.floats("alZ")[0], 1e-6f)
        near(listOf(0f, 80f), e.floats("position"))
        assertEquals(2.5f, e.floats("radius")[0], 1e-6f)
        val moved = migrate(alight("ellipsoid", p("position", "vec3", "30,-20,700") + p("radius", "float", "1.5")))
        near(listOf(30f, 80f), moved.floats("position"))
        assertEquals(700f, moved.floats("alZ")[0], 1e-4f)
        assertEquals(1.5f, moved.floats("radius")[0], 1e-6f)
    }

    @Test fun alightsLightSettingsBecomeTheSoftLight() {
        val r = result(alight("triprism", p("shadingType", "int", "1") + p("lightIntensity", "float", "1.25") + p("ambient", "float", "0.325") +
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

    @Test fun untouchedPlusUsesAlightsCameraWithoutTheLayer() {
        val e = migrate(alight("plus"))
        assertEquals("false", e.text("keep"))
        assertEquals(listOf(1f, 500f, 1f), listOf("alight", "alZ", "scale").map { e.floats(it)[0] })
        assertEquals(listOf(0f, 55f), e.floats("position"))
        assertEquals(1f, e.floats("reach")[0], 1e-6f)
        assertEquals(0.5f, e.floats("thickness")[0], 1e-6f)
    }

    @Test fun pyramidUsesAlightsCameraWithAnEdgeFacingYou() {
        val e = migrate(alight("pyramid"))
        assertEquals(listOf(0f, 0f, 0f, 3f), listOf("rotX", "rotY", "rotZ", "scale").map { e.floats(it)[0] })
        assertEquals(listOf(1f, 500f, 1f, 1f), listOf("alight", "alZ", "base", "height").map { e.floats(it)[0] })
        near(listOf(0f, 55f), e.floats("position"))
        assertEquals(1.5f, migrate(alight("pyramid", p("scale", "float", "1.5"))).floats("scale")[0], 1e-6f)
    }

    @Test fun starPolyhedronKeepsAlightsSettings() {
        val e = migrate(alight("starpoly", p("size", "float", "0.5") + p("height", "float", "0.5") + p("pointLength", "float", "1") +
            p("position", "vec3", "0,100,500") + p("scale", "float", "3")))
        assertEquals("absent.starpolyhedron", e["id"])
        near(listOf(1f, 50f, 50f, 100f, 500f, 3f), listOf("style", "starSize", "starDepth", "pointLength", "alZ", "alScale").map { e.floats(it)[0] })
        near(listOf(0f, 100f), e.floats("alPos"))
        val seven = migrate(alight("starpoly", p("pointCount", "float", "7") + p("pointLength", "float", "2")))
        assertEquals(7f, seven.floats("points")[0], 1e-6f)
        assertEquals(200f, seven.floats("pointLength")[0], 1e-4f)
    }

    @Test fun oldSpherizeSwitchesItsDirectLightWithAlightsLightSwitch() {
        val lit = migrate(alight("spherize"))
        near(listOf(2f, 0.975f, 0.025f, 1.886f, 45f, 0f), listOf("lighting", "shading", "ambient", "lightHeight", "light", "shine").map { lit.floats(it)[0] })
        val flat = migrate(alight("spherize", p("enableLight", "bool", "false")))
        near(listOf(0f, 0f), listOf("lighting", "shading").map { flat.floats(it)[0] })
        val aimed = migrate(alight("spherize", p("lightDir", "vec2", "115,-100") + p("lightDepth", "float", "225") + p("lightColor", "color", "#FF3366CC")))
        assertEquals(Math.toDegrees(atan2(100.0, 115.0)).toFloat(), aimed.floats("light")[0], 1e-3f)
        assertEquals(225f / hypot(115f, 100f), aimed.floats("lightHeight")[0], 1e-3f)
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

    @Test fun starPrismIsAFatterStarWithItsOwnSizeAndRepeats() {
        val e = migrate(alight("starprism", p("size", "float", "2") + p("thickness", "float", "0.4")))
        near(listOf(0.547f, 3f, 0.4f), listOf("inner", "radius", "depth").map { e.floats(it)[0] })
        val tiled = migrate(alight("starprism", p("texRepeatX", "float", "2") + p("texRepeatY", "float", "3")))
        near(listOf(2f, 3f), listOf("texRepeatX", "texRepeatY").map { tiled.floats(it)[0] })
    }

    @Test fun spherizeTwoWrapsThePictureRoundTheGlobeInAlightsCamera() {
        val e = migrate(alight("spherize2"))
        assertEquals(listOf(1f, 1f, 550f, 2.5f), listOf("projection", "alight", "alZ", "radius").map { e.floats(it)[0] })
        near(listOf(0f, 80f), e.floats("position"))
    }

    @Test fun octahedronUsesAlightsCameraAndSize() {
        val e = migrate(alight("octahedron"))
        near(listOf(0f, 55f), e.floats("position"))
        assertEquals(listOf(1f, 500f, 3f), listOf("alight", "alZ", "radius").map { e.floats(it)[0] })
        assertEquals(2f, migrate(alight("octahedron", p("size", "float", "2"))).floats("radius")[0], 1e-6f)
    }

    @Test fun untouchedPageCurlRollsTheBottomRightCorner() {
        val e = migrate(alight("pagecurl"))
        near(listOf(0.4f, 0.1f, 38f, 0f, 1f, 1f), listOf("progress", "radius", "angle", "backAlpha", "roll", "space").map { e.floats(it)[0] })
    }
}
