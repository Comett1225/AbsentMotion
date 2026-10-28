package app.absentmotion.editor

import app.absentmotion.model.Easing
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import app.absentmotion.sceneOf
import app.absentmotion.shapeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object PivotEngine {
    val loaded: Boolean by lazy {
        val lib = File("build/hostpivot").listFiles { f -> f.name.startsWith("libampivot.") }?.firstOrNull() ?: return@lazy false
        runCatching { System.load(lib.absolutePath) }.isSuccess
    }

    @JvmStatic external fun nativePlaces(xml: String, ids: LongArray, times: DoubleArray): FloatArray?
}

class PivotEngineTest {
    @Before fun needsTheHostEngine() = Assume.assumeTrue("build it with tools/pivot.sh --lib", PivotEngine.loaded)

    private fun prop(name: String, vararg keys: Pair<Float, String>, ease: String? = null): XmlNode =
        if (keys.size == 1) XmlNode(name, mapOf("value" to keys[0].second))
        else XmlNode(name, children = keys.mapIndexed { i, (t, v) ->
            XmlNode("kf", linkedMapOf("t" to "$t", "v" to v) + (if (i > 0 && ease != null) mapOf("e" to ease) else emptyMap()))
        })

    private fun keyedEffect(id: String, name: String, vararg keys: Pair<Float, String>) = XmlNode(
        "effect", linkedMapOf("id" to id),
        listOf(XmlNode("property", linkedMapOf("name" to name, "type" to "float"), keys.map { (t, v) -> XmlNode("kf", linkedMapOf("t" to "$t", "v" to v)) })),
    )

    private fun effect(id: String, vararg values: Pair<String, String>) = XmlNode(
        "effect", linkedMapOf("id" to id),
        values.map { (name, value) -> XmlNode("property", linkedMapOf("name" to name, "type" to if (value.contains(',')) "vec2" else "float", "value" to value)) },
    )

    private fun layer(id: Long, vararg children: XmlNode, parent: Long? = null, kind: String = "shape", start: Int = 0, end: Int = 1000) = shapeNode(
        kind = kind, id = id, start = start, end = end, parent = parent,
        attrs = mapOf("s" to ".rect", "fillType" to "color"),
        children = listOf(XmlNode("transform", children = children.filter { it.name != "effect" }), XmlNode("property", linkedMapOf("name" to "size", "type" to "vec2", "value" to "80,50"))) +
            children.filter { it.name == "effect" },
    )

    private val probes = listOf(0f to 0f, 40f to -25f, -60f to 80f)

    private fun screen(scene: Scene, ids: List<Long>, times: List<Int>): List<List<Pair<Float, Float>>> {
        val pairs = ids.flatMap { id -> times.map { id to it } }
        val out = PivotEngine.nativePlaces(Scene.parse(scene.toXml()).toXml(), pairs.map { it.first }.toLongArray(), pairs.map { it.second.toDouble() }.toDoubleArray())
        assertNotNull(out)
        return pairs.indices.map { i ->
            val m = out!!.copyOfRange(33 * i, 33 * i + 9)
            probes.map { (x, y) ->
                val w = m[6] * x + m[7] * y + m[8]
                (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
            }
        }
    }

    private fun drift(before: Scene, after: Scene, ids: List<Long>, times: List<Int>): Float {
        val a = screen(before, ids, times)
        val b = screen(after, ids, times)
        var most = 0f
        for (i in a.indices) for (k in probes.indices) most = maxOf(most, abs(a[i][k].first - b[i][k].first), abs(a[i][k].second - b[i][k].second))
        return most
    }

    private fun assertStays(before: Scene, after: Scene, ids: List<Long>, times: List<Int>, tolerance: Float = 0.02f) {
        val most = drift(before, after, ids, times)
        assertTrue("moved by $most px", most < tolerance)
    }

    private val frames = (0..30).map { it * 1000 / 30 }

    @Test fun aChildUnderARotatedAndScaledParentStaysWhereItIs() {
        val s = sceneOf(
            layer(1, prop("location", 0f to "200,200,0"), prop("rotation", 0f to "45"), prop("scale", 0f to "2,1.5"), prop("pivot", 0f to "5,-3,0")),
            layer(2, prop("location", 0f to "30,10,0"), prop("rotation", 0f to "-20"), prop("scale", 0f to "0.7,1.3"), prop("skew", 0f to "0.3,0"), parent = 1),
            layer(3, prop("location", 0f to "15,-5,0"), parent = 2),
        )
        val out = s.withPivotMoved(2, 500, 10f, 10f)
        assertEquals(listOf(10f, 10f), out.layer(2)!!.pivot.take(2))
        assertStays(s, out, listOf(1, 2, 3), frames)
        assertStays(out, out.withPivotMoved(2, 500, 0f, 0f), listOf(1, 2, 3), frames)
    }

    @Test fun aLoneLayerWithEveryTransformStaysWhereItIs() {
        val s = sceneOf(layer(1, prop("location", 0f to "120,90,0"), prop("rotation", 0f to "30"), prop("scale", 0f to "-2,0.5"), prop("skew", 0f to "0.2,-0.1"), prop("tilt", 0f to "25,-35")))
        assertStays(s, s.withPivotMoved(1, 500, 17f, -9f), listOf(1), frames)
    }

    @Test fun aTiltedParentKeepsItsChildrenInPlace() {
        for (tilt in listOf("30,0", "0,40")) {
            val s = sceneOf(
                layer(1, prop("location", 0f to "200,200,0"), prop("tilt", 0f to tilt), prop("rotation", 0f to "20"), prop("scale", 0f to "1.4,0.8")),
                layer(2, prop("location", 0f to "40,25,0"), parent = 1),
                layer(3, prop("location", 0f to "-10,5,0"), parent = 2),
            )
            assertStays(s, s.withPivotMoved(1, 500, 10f, 10f), listOf(1, 2, 3), frames)
        }
    }

    @Test fun motionEffectsAreTakenIntoAccount() {
        val s = sceneOf(
            layer(1, prop("location", 0f to "200,200,0"), effect("absent.spin", "rpm" to "45"), effect("absent.pulsesize", "frequency" to "1.3"), effect("absent.swing", "frequency" to "0.7")),
            layer(2, prop("location", 0f to "40,0,0"), parent = 1),
        )
        val out = s.withPivotMoved(1, 517, 10f, 0f, 500)
        assertStays(s, out, listOf(1, 2), listOf(500))
    }

    @Test fun aLayerOnAPathStaysWhereItIs() {
        val s = sceneOf(
            layer(1, prop("location", 0f to "200,200,0"), prop("scale", 0f to "3,2")),
            layer(2, prop("location", 0f to "0,0,0"), prop("rotation", 0f to "15"), effect("absent.movealongpath", "progress" to "0.3")),
        )
        val out = s.withPivotMoved(2, 517, 12f, -7f, 500)
        assertStays(s, out, listOf(2), listOf(500))
        fun plain(scene: Scene) = scene.update(2) { Layer(it.node.copy(children = it.node.children.filter { c -> c.name != "effect" })) }
        assertStays(plain(s), plain(out), listOf(2), listOf(500))
    }

    @Test fun anAnimatedParentKeepsEveryFrameOfItsChildren() {
        val s = sceneOf(
            layer(1, prop("location", 0f to "200,200,0"), prop("rotation", 0f to "0", 1f to "180"), prop("scale", 0f to "1,1", 1f to "2,2")),
            layer(2, prop("location", 0f to "40,0,0", 1f to "60,20,0"), parent = 1),
        )
        val out = s.withPivotMoved(1, 517, 10f, 10f, 500)
        val keys = out.layer(2)!!.node.child("transform")!!.child("location")!!.keyframes.map { it.t }
        assertTrue(keys.containsAll(listOf(0f, 0.5f, 1f)))
        assertTrue(keys.size < 60)
        assertEquals("1", out.layer(2)!!.node["parent"])
        assertStays(s, out, listOf(1, 2), keyFrames)
        assertStays(s, out, listOf(1, 2), listOf(500))
        assertStays(s, out, listOf(1, 2), frames, 1f)
    }

    @Test fun aPivotKeyInsideTheFrameButOffTheSampleTimeKeepsTheLayerInPlace() {
        val s = sceneOf(layer(1, prop("location", 0f to "100,100,0"), prop("scale", 0f to "2,2"), prop("rotation", 0f to "30"), prop("pivot", 0f to "0,0,0", 0.505f to "5,0,0", 1f to "0,0,0")))
        val out = s.withPivotMoved(1, 517, 15f, 0f, 500)
        val location = out.layer(1)!!.node.child("transform")!!.child("location")!!
        assertEquals(listOf(0f, 0.505f, 1f), location.keyframes.map { it.t })
        assertStays(s, out, listOf(1), frames)
    }

    @Test fun anEasedPivotWithAStillLocationStaysExact() {
        val ease = "cubicBezier 0.42 0.0 0.58 1.0"
        val s = sceneOf(layer(1, prop("location", 0f to "100,100,0"), prop("rotation", 0f to "40"), prop("scale", 0f to "1.5,0.5"), prop("pivot", 0f to "0,0,0", 1f to "30,10,0", ease = ease)))
        val out = s.withPivotMoved(1, 350, -12f, 22f)
        assertEquals(3, out.layer(1)!!.node.child("transform")!!.child("pivot")!!.keyframes.size)
        assertStays(s, out, listOf(1), frames)
    }

    @Test fun anEasedLocationKeepsItsEaseWhenTheAnimatedPivotMoves() {
        val ease = "cubicBezier 0.42 0.0 0.58 1.0"
        val s = sceneOf(layer(1, prop("location", 0f to "0,100,0", 1f to "100,100,0", ease = ease), prop("pivot", 0f to "0,0,0", 1f to "0,0,0")))
        val out = s.withPivotMoved(1, 500, 0f, 10f)
        val keys = out.layer(1)!!.node.child("transform")!!.child("location")!!.keyframes
        assertEquals(listOf(0f, 0.5f, 1f), keys.map { it.t })
        assertTrue(keys.drop(1).all { Easing.parse(it.e).type == "cubicBezier" })
        assertStays(s, out, listOf(1), listOf(0, 500, 1000))
        assertTrue(drift(s, out, listOf(1), frames) < 3f)
        val still = sceneOf(layer(1, prop("location", 0f to "0,100,0", 0.4f to "100,100,0", ease = ease), prop("pivot", 0f to "0,0,0", 0.5f to "0,0,0", 1f to "0,0,0")))
        val moved = still.withPivotMoved(1, 750, 0f, 10f)
        assertEquals(ease, moved.layer(1)!!.node.child("transform")!!.child("location")!!.keyframes[1].e)
        assertStays(still, moved, listOf(1), frames)
    }

    private val keyFrames = listOf(0, 1000)

    @Test fun aLayerWithAnimatedRotationStaysWhereItIsOnEveryFrame() {
        val s = sceneOf(layer(1, prop("location", 0f to "200,200,0"), prop("rotation", 0f to "0", 1f to "90"), prop("scale", 0f to "1.5,1")))
        val out = s.withPivotMoved(1, 500, 30f, 20f)
        assertStays(s, out, listOf(1), keyFrames)
        assertStays(s, out, listOf(1), frames, 0.5f)
    }

    @Test fun aLayerWithAnimatedScaleStaysWhereItIsWithTwoKeys() {
        val s = sceneOf(layer(1, prop("location", 0f to "150,120,0"), prop("rotation", 0f to "35"), prop("scale", 0f to "1,1", 1f to "3,2")))
        val out = s.withPivotMoved(1, 250, -20f, 14f, 250)
        assertEquals(listOf(0f, 1f), out.layer(1)!!.node.child("transform")!!.child("location")!!.keyframes.map { it.t })
        assertStays(s, out, listOf(1), frames)
    }

    @Test fun keyedSpinAndSwingSettingsKeepTheirKeyedFrames() {
        val s = sceneOf(
            layer(1, prop("location", 0f to "200,200,0"), keyedEffect("absent.spin", "rpm", 0f to "0", 0.6f to "40", 1f to "10")),
            layer(2, prop("location", 0f to "40,0,0"), parent = 1),
            layer(3, prop("location", 0f to "120,90,0"), keyedEffect("absent.swing", "angle2", 0f to "10", 1f to "70")),
        )
        val out = s.withPivotMoved(1, 500, 24f, -16f).withPivotMoved(3, 500, 18f, 12f)
        assertStays(s, out, listOf(1, 2), listOf(0, 600, 1000))
        assertStays(s, out, listOf(3), listOf(0, 1000))
        assertStays(s, out, listOf(1, 2, 3), frames, 1.5f)
    }

    @Test fun aRunningSpinStaysWithinAPixelAndKeepsItsKeysBounded() {
        val s = sceneOf(layer(1, prop("location", 0f to "200,200,0"), effect("absent.spin", "rpm" to "45")))
        val out = s.withPivotMoved(1, 500, 30f, 20f)
        val keys = out.layer(1)!!.node.child("transform")!!.child("location")!!.keyframes
        assertTrue(keys.size in 8..100)
        assertStays(s, out, listOf(1), frames, 1f)
        assertStays(s, out, listOf(1), listOf(0, 500, 1000))
    }

    @Test fun aKeyedPivotAndAnAnimatedRotationTogetherKeepEveryKeyedFrame() {
        val s = sceneOf(layer(1, prop("location", 0f to "100,100,0"), prop("rotation", 0f to "10", 1f to "100"), prop("pivot", 0f to "0,0,0", 1f to "20,0,0")))
        val out = s.withPivotMoved(1, 500, 15f, 5f)
        assertStays(s, out, listOf(1), listOf(0, 500, 1000))
        assertStays(s, out, listOf(1), frames, 1f)
    }

    @Test fun aChildWithAnotherLifetimeStaysWhereItIsOnEveryFrame() {
        val s = sceneOf(
            layer(1, prop("location", 0f to "200,200,0"), prop("rotation", 0f to "0", 1f to "120"), prop("scale", 0f to "1,1", 1f to "1.5,1.5")),
            layer(2, prop("location", 0f to "40,10,0"), parent = 1, start = 250, end = 750),
        )
        val out = s.withPivotMoved(1, 500, 20f, -10f)
        assertStays(s, out, listOf(1), frames, 1f)
        assertStays(s, out, listOf(2), (250..750 step 50).toList(), 1f)
        assertStays(s, out, listOf(2), listOf(250, 500, 750))
    }

    @Test fun aKeyedPivotParentKeepsAChildWithAnotherLifetime() {
        val s = sceneOf(
            layer(1, prop("location", 0f to "200,200,0"), prop("rotation", 0f to "25"), prop("pivot", 0f to "0,0,0", 1f to "30,0,0")),
            layer(2, prop("location", 0f to "40,10,0"), parent = 1, start = 250, end = 750),
        )
        val out = s.withPivotMoved(1, 500, 20f, 10f)
        assertStays(s, out, listOf(1), frames)
        assertStays(s, out, listOf(2), (250..750 step 25).toList())
    }

    @Test fun aPivotDragInManySmallStepsAddsNoKeysAfterTheFirstAndKeepsEveryFrame() {
        val s = sceneOf(
            layer(1, prop("location", 0f to "200,200,0"), prop("rotation", 0f to "0", 1f to "90")),
            layer(2, prop("location", 0f to "40,0,0"), parent = 1),
        )
        var out = s
        val counts = (1..20).map { step ->
            out = out.withPivotMoved(1, 500, step * 3f, step * 2f)
            listOf(1L, 2L).map { out.layer(it)!!.node.child("transform")!!.child("location")!!.keyframes.size }
        }
        assertEquals(1, counts.distinct().size)
        assertTrue(counts[0].all { it > 2 })
        assertStays(s, out, listOf(1, 2), keyFrames)
        assertStays(s, out, listOf(1, 2), frames, 1.5f)
    }

    @Test fun aModelLayerIgnoresSkewLikeTheEngine() {
        val s = sceneOf(layer(1, prop("location", 0f to "100,100,0"), prop("skew", 0f to "0.4,0"), prop("tilt", 0f to "20,0"), kind = "model"))
        val out = s.withPivotMoved(1, 500, 10f, 10f)
        fun points(scene: Scene): List<Float> {
            val p = PivotEngine.nativePlaces(scene.toXml(), longArrayOf(1), doubleArrayOf(500.0))!!
            val tilt = p.copyOfRange(17, 33)
            val r = Math.toRadians(p[16].toDouble())
            return probes.flatMap { (x, y) ->
                val lx = (x - p[12]) * p[14]
                val ly = (y - p[13]) * p[15]
                val tx = tilt[0] * lx + tilt[1] * ly
                val ty = tilt[4] * lx + tilt[5] * ly
                val tz = tilt[8] * lx + tilt[9] * ly
                listOf((p[9] + tx * cos(r) - ty * sin(r)).toFloat(), (p[10] + tx * sin(r) + ty * cos(r)).toFloat(), p[11] + tz)
            }
        }
        points(s).zip(points(out)).forEach { (a, b) -> assertEquals(a, b, 0.02f) }
    }
}
