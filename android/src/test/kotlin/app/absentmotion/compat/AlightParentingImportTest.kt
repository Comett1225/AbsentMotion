package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AlightParentingImportTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)

    private fun result(effect: String): EffectMigration.Result = migration.migrate(
        Scene.parse(
            """<scene title="c" width="1080" height="1080" fps="30" totalTime="1000"><nullobj id="9" startTime="0" endTime="1000"><transform><location value="540,540,0" /></transform></nullobj>
            <shape id="1" parent="9" startTime="0" endTime="1000" fillType="color" s=".rect">$effect<property name="size" type="vec2" value="110,82.5" /></shape></scene>""",
        ),
    )

    private fun migrate(effect: String): XmlNode = result(effect).scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }

    private fun XmlNode.number(name: String): Float = children.single { it["name"] == name }["value"]!!.toFloat()

    private fun XmlNode.keys(name: String) = children.single { it["name"] == name }.keyframes

    private fun quat(axis: Int, degrees: Double): String {
        val v = DoubleArray(4)
        v[axis] = sin(Math.toRadians(degrees) / 2)
        v[3] = cos(Math.toRadians(degrees) / 2)
        return v.joinToString(",") { "%.6f".format(java.util.Locale.US, it) }
    }

    private fun box(vararg props: String) = migrate("""<effect id="com.alightcreative.effects.box" locallyApplied="true">${props.joinToString("")}</effect>""")

    private fun orient(value: String) = """<property name="orient" type="quat" value="$value" />"""

    private fun angles(e: XmlNode) = listOf("rotX", "rotY", "rotZ").map { e.number(it) }

    private fun assertAngles(expected: List<Float>, actual: List<Float>, tolerance: Float = 0.02f) {
        for (i in 0..2) assertEquals("axis $i of $actual", expected[i], actual[i], tolerance)
    }

    @Test fun theParentingHelperKeepsEverySetting() {
        val helper = result(
            """<effect id="com.alightcreative.effects.parenthelper" locallyApplied="true"><property name="scaleMode" type="int" value="2" />
            <property name="rotateMode" type="int" value="1" /><property name="scaleWeight" type="float" value="0.350000" /><property name="rotateWeight" type="float" value="1.000000" />
            <property name="autoRotate" type="int" value="2" /><property name="radiusAdjust" type="float" value="-12.500000" /></effect>""",
        )
        val e = helper.scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
        assertEquals("absent.parenthelper", e["id"])
        assertEquals(listOf("scaleMode", "rotateMode", "scaleWeight", "rotateWeight", "autoRotate", "radiusAdjust"), e.children.map { it["name"] })
        assertEquals(listOf(2f, 1f, 0.35f, 1f, 2f, -12.5f), e.children.map { it["value"]!!.toFloat() })
        assertTrue(helper.dropped.toString(), helper.dropped.isEmpty())
        assertEquals(mapOf("parenthelper → Parenting Helper" to 1), helper.converted)
        assertEquals(listOf("Normal", "Locked", "Weighted"), catalog.find("absent.parenthelper")!!.params.first { it.id == "scaleMode" }.options)
    }

    @Test fun theParentingHelperKeepsKeyframedWeights() {
        val e = migrate(
            """<effect id="com.alightcreative.effects.parenthelper"><property name="scaleMode" type="int" value="2" />
            <property name="scaleWeight" type="float"><kf t="0.000000" v="0.000000" /><kf t="1.000000" v="1.000000" e="cubicBezier 0.42 0 0.58 1" /></property></effect>""",
        )
        assertEquals(listOf(0f, 1f), e.keys("scaleWeight").map { it.v.toFloat() })
        assertEquals("cubicBezier 0.42 0 0.58 1", e.keys("scaleWeight")[1].e)
        assertEquals(listOf("scaleMode", "scaleWeight"), e.children.map { it["name"] })
    }

    @Test fun scaleAssistKeepsAxisScaleAndDamping() {
        val e = migrate(
            """<effect id="com.alightcreative.effects.scaleassist" locallyApplied="true" hidden="true"><property name="axis" type="int" value="2" />
            <property name="scale" type="float" value="369.996277" /><property name="damp" type="float" value="0.500000" /></effect>""",
        )
        assertEquals("absent.scaleassist", e["id"])
        assertEquals("true", e["hidden"])
        assertEquals(listOf(2f, 369.99628f, 0.5f), listOf("axis", "scale", "damp").map { e.number(it) })
        assertEquals(listOf("X", "Y", "X,Y", "X,-Y"), catalog.find("absent.scaleassist")!!.params.first { it.id == "axis" }.options)
    }

    @Test fun anOrientationAboutOneAxisTurnsTheSolidTheWayAlightDoes() {
        for (degrees in listOf(0.0, 40.0, 90.0)) {
            assertAngles(listOf(degrees.toFloat(), 0f, 0f), angles(box(orient(quat(0, degrees)))))
            assertAngles(listOf(0f, -degrees.toFloat(), 0f), angles(box(orient(quat(1, degrees)))))
            assertAngles(listOf(0f, 0f, degrees.toFloat()), angles(box(orient(quat(2, degrees)))))
        }
        assertEquals(180f, abs(angles(box(orient(quat(0, 180.0))))[0]), 0.02f)
        assertEquals(180f, abs(angles(box(orient(quat(2, 180.0))))[2]), 0.02f)
        assertEquals(180f, abs(angles(box(orient(quat(1, 180.0))))[1]), 0.02f)
    }

    @Test fun orientationsAboutTwoAxesAreComposedLikeAlight() {
        val about = { x: Double, y: Double ->
            val qx = doubleArrayOf(sin(Math.toRadians(x) / 2), 0.0, 0.0, cos(Math.toRadians(x) / 2))
            val qy = doubleArrayOf(0.0, sin(Math.toRadians(y) / 2), 0.0, cos(Math.toRadians(y) / 2))
            doubleArrayOf(
                qx[3] * qy[0] + qx[0] * qy[3] + qx[1] * qy[2] - qx[2] * qy[1], qx[3] * qy[1] - qx[0] * qy[2] + qx[1] * qy[3] + qx[2] * qy[0],
                qx[3] * qy[2] + qx[0] * qy[1] - qx[1] * qy[0] + qx[2] * qy[3], qx[3] * qy[3] - qx[0] * qy[0] - qx[1] * qy[1] - qx[2] * qy[2],
            ).joinToString(",") { "%.6f".format(java.util.Locale.US, it) }
        }
        assertAngles(listOf(37.005f, -33.826f, 22.76f), angles(box(orient(about(30.0, 40.0)))), 0.05f)
    }

    @Test fun theRotationRowTurnsAfterTheOrientationAndItsZSignIsAlightsOwn() {
        assertAngles(listOf(-10.628f, 19.683f, -26.384f), angles(box("""<property name="rotate" type="vec3" value="10.000000,20.000000,30.000000" />""")), 0.05f)
        assertAngles(listOf(-37.005f, 33.826f, -27.24f), angles(box("""<property name="rotate" type="vec3" value="30.000000,40.000000,50.000000" />""")), 0.05f)
        assertAngles(listOf(-40f, -40f, 0f), angles(box(orient(quat(1, 40.0)), """<property name="rotate" type="vec3" value="40.000000,0.000000,0.000000" />""")), 0.05f)
        assertAngles(listOf(0f, 0f, -40f), angles(box("""<property name="rotate" type="vec3" value="0.000000,0.000000,40.000000" />""")))
    }

    @Test fun solidsThatStartTurnedKeepTheirOwnStartingPose() {
        val torus = migrate("""<effect id="com.alightcreative.effects.torus"><property name="rotate" type="vec3" value="10.000000,20.000000,30.000000" /></effect>""")
        assertAngles(listOf(34.372f, 19.683f, -26.384f), angles(torus), 0.05f)
        assertAngles(listOf(58f, 0f, 0f), angles(migrate("""<effect id="com.alightcreative.effects.pyramid"></effect>""")))
    }

    @Test fun anOrientationIsNotReportedAsDropped() {
        val r = result("""<effect id="com.alightcreative.effects.box"><property name="orient" type="quat" value="0.104529,0.000000,0.000000,0.994522" /><property name="rotate" type="vec3" value="1.000000,2.000000,3.000000" /></effect>""")
        assertTrue(r.dropped.toString(), r.dropped.isEmpty())
        val e = r.scene.node.children.single { it.name == "shape" }.children.single { it.name == "effect" }
        assertFalse(e.children.any { it["name"] == "orient" || it["name"] == "rotate" })
    }

    @Test fun keyframedOrientationsTakeTheShortWayRound() {
        val across = box(
            """<property name="orient" type="quat"><kf t="0.000000" v="0.000000,0.000000,0.000000,1.000000" /><kf t="1.000000" v="${quat(1, 300.0)}" /></property>""",
        )
        assertEquals(listOf(0f, 60f), across.keys("rotY").map { it.v.toFloat() }.map { Math.round(it * 10) / 10f })
        assertEquals(listOf(0f, 1f), across.keys("rotY").map { it.t })
        val over = box(
            """<property name="orient" type="quat"><kf t="0.000000" v="${quat(2, -170.0)}" /><kf t="1.000000" v="${quat(2, 170.0)}" /></property>""",
        )
        assertEquals(listOf(-170f, -190f), over.keys("rotZ").map { it.v.toFloat() }.map { Math.round(it * 10) / 10f })
    }

    @Test fun aLongTurnAboutSeveralAxesGetsExtraKeysSoItFollowsAlightsPath() {
        val e = box(
            """<property name="orient" type="quat"><kf t="0.000000" v="0.000000,0.000000,0.000000,1.000000" /><kf t="1.000000" v="0.5,0.5,0.5,0.5" e="cubicBezier 0.42 0 0.58 1" /></property>""",
        )
        val keys = e.keys("rotX")
        assertTrue(keys.size > 2)
        assertEquals(listOf(0f, 1f), listOf(keys.first().t, keys.last().t))
        assertNull(keys.dropLast(1).firstOrNull { it.e != null })
        assertTrue(keys.map { it.t }.zipWithNext().all { (a, b) -> b > a })
        val z = e.keys("rotZ").map { it.v.toFloat() }
        assertTrue(z.zipWithNext().all { (a, b) -> abs(b - a) < 180f })
    }

    @Test fun aSpinOnTheRotationRowKeepsAllItsTurns() {
        val e = box(
            """<property name="rotate" type="vec3"><kf t="0.000000" v="0.000000,0.000000,0.000000" /><kf t="1.000000" v="0.000000,0.000000,720.000000" /></property>""",
        )
        assertEquals(listOf(0f, -720f), e.keys("rotZ").map { it.v.toFloat() })
        assertEquals(2, e.keys("rotZ").size)
    }

    @Test fun solidsWithNoOrientationStillStandUpFacingTheCamera() {
        assertAngles(listOf(0f, 0f, 0f), angles(box()))
    }
}
