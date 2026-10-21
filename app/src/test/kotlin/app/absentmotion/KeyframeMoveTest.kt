package app.absentmotion

import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import app.absentmotion.model.withKeyframesMoved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Locale
import kotlin.math.roundToInt

class KeyframeMoveTest {
    private val scene = Scene.create("t", 100, 100, 30, 0)
    private val end = 1999
    private val strength = PropRef.effect(0, "strength")

    private fun kf(frame: Int, v: String) = kfAt(scene.frameTime(frame).toFloat() / end, v)
    private fun kfAt(t: Float, v: String) = XmlNode("kf", mapOf("t" to String.format(Locale.US, "%.6f", t), "v" to v))

    private fun layer(location: List<XmlNode>, rotation: List<XmlNode> = emptyList(), effect: List<XmlNode> = emptyList(), extra: List<XmlNode> = emptyList()) =
        shapeLayer(end = end, children = listOf(
            XmlNode("transform", children = listOf(XmlNode("location", children = location), XmlNode("rotation", children = rotation))),
            XmlNode("effect", mapOf("id" to "blur"), listOf(XmlNode("property", mapOf("name" to "strength", "type" to "float"), effect))),
        ) + extra)

    private fun Layer.keys(ref: PropRef) = keyframes(ref).map { scene.frameAt(timeOf(it.t)) to it.v }

    @Test fun movesEveryPropertyAtTheFrameLater() {
        val moved = layer(listOf(kf(10, "a"), kf(40, "b")), listOf(kf(10, "r")), listOf(kf(10, "s"), kf(50, "z"))).withKeyframesMoved(10, 25, scene)
        assertEquals(listOf(25 to "a", 40 to "b"), moved.keys(PropRef.Location))
        assertEquals(listOf(25 to "r"), moved.keys(PropRef.Rotation))
        assertEquals(listOf(25 to "s", 50 to "z"), moved.keys(strength))
    }

    @Test fun movesEarlierPastAnotherKeyWithoutRemovingIt() {
        val moved = layer(listOf(kf(10, "a"), kf(40, "b"))).withKeyframesMoved(40, 5, scene)
        assertEquals(listOf(5 to "b", 10 to "a"), moved.keys(PropRef.Location))
    }

    @Test fun clampsInsideTheLayerAtBothEnds() {
        val base = layer(listOf(kf(10, "a"), kf(40, "b")))
        val late = base.withKeyframesMoved(10, 500, scene)
        assertEquals(listOf(40 to "b", 59 to "a"), late.keys(PropRef.Location))
        assertEquals(1f, late.keyframes(PropRef.Location).last().t, 0f)
        val early = base.withKeyframesMoved(40, -20, scene)
        assertEquals(listOf(0 to "b", 10 to "a"), early.keys(PropRef.Location))
        assertEquals(0f, early.keyframes(PropRef.Location).first().t, 0f)
    }

    @Test fun replacesAKeyOfTheSamePropertyOnlyAtTheDropFrame() {
        val moved = layer(listOf(kf(10, "a"), kf(40, "b")), listOf(kf(40, "r"))).withKeyframesMoved(10, 40, scene)
        assertEquals(listOf(40 to "a"), moved.keys(PropRef.Location))
        assertEquals(listOf(40 to "r"), moved.keys(PropRef.Rotation))
    }

    @Test fun sameFrameChangesNothing() {
        val base = layer(listOf(kf(10, "a"), kf(59, "b")))
        assertSame(base, base.withKeyframesMoved(10, 10, scene))
        assertSame(base, base.withKeyframesMoved(59, 80, scene))
    }

    @Test fun speedAndEffectKeysMoveTogether() {
        val speed = XmlNode("speedMap", children = listOf(
            XmlNode("kf", mapOf("t" to "0", "v" to "1")),
            XmlNode("kf", mapOf("t" to "1000000000", "v" to "2")),
        ))
        val plain = layer(emptyList(), extra = listOf(speed))
        val local = VideoTiming(plain).localMs(1000.0)
        val base = layer(emptyList(), effect = listOf(kfAt((local / end).toFloat(), "s")), extra = listOf(speed))
        val from = scene.frameAt(local.roundToInt())
        assertEquals(listOf(0, from), base.keyframeTimes().map { scene.frameAt(it) }.distinct())

        val moved = base.withKeyframesMoved(from, 40, scene)
        val timing = VideoTiming(moved)
        val speedFrames = moved.node.child("speedMap")!!.keyframes.map { scene.frameAt(timing.localMs(it.t / 1e6).roundToInt()) to it.v }
        assertEquals(listOf(0 to "1", 40 to "2"), speedFrames)
        assertEquals(listOf(40 to "s"), moved.keys(strength))
        assertEquals(listOf(0, 40), moved.keyframeTimes().map { scene.frameAt(it) }.distinct())
    }
}
