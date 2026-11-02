package app.absentmotion

import app.absentmotion.editor.navigationStops
import app.absentmotion.model.KeyScope
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.SpeedRef
import app.absentmotion.model.VideoTiming
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import app.absentmotion.model.withKeyframesMoved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Locale
import kotlin.math.roundToInt

class KeyScopeTest {
    private val blank = Scene.create("t", 100, 100, 30, 0)
    private val end = 1999
    private val radius = PropRef.effect(0, "radius")
    private val alpha = PropRef.effect(0, "alpha")
    private val tiles = PropRef.effect(1, "size")

    private fun kf(frame: Int, v: String = "1") =
        XmlNode("kf", mapOf("t" to String.format(Locale.US, "%.6f", blank.frameTime(frame).toFloat() / end), "v" to v))

    private fun property(name: String, vararg frames: Int) = XmlNode("property", mapOf("name" to name, "type" to "float"), frames.map { kf(it) })

    private fun layer(extra: List<XmlNode> = emptyList()) = shapeLayer(end = end, children = listOf(
        XmlNode("transform", children = listOf(XmlNode("location", children = listOf(kf(10), kf(45))))),
        XmlNode("effect", mapOf("id" to "glow"), listOf(property("radius", 10, 30), property("alpha", 10, 50))),
        XmlNode("effect", mapOf("id" to "tiles"), listOf(property("size", 10, 20))),
    ) + extra)

    private val scene = blank.add(layer())

    private fun Layer.frames(scope: KeyScope? = null) = keyframeTimes(scope).map { scene.frameAt(it) }
    private fun Layer.keys(ref: PropRef) = keyframes(ref).map { scene.frameAt(timeOf(it.t)) }

    @Test fun noScopeKeepsEveryKeyframeOfTheLayer() {
        assertEquals(listOf(10, 20, 30, 45, 50), layer().frames())
    }

    @Test fun aSingleParameterShowsOnlyItsOwnKeyframes() {
        val base = layer()
        assertEquals(listOf(10, 30), base.frames(KeyScope(radius)))
        assertEquals(listOf(10, 50), base.frames(KeyScope(alpha)))
        assertEquals(listOf(10, 20), base.frames(KeyScope(tiles)))
        assertEquals(listOf(10, 45), base.frames(KeyScope(PropRef.Location)))
    }

    @Test fun anOpenEffectShowsOnlyItsParametersTogether() {
        val base = layer()
        assertEquals(listOf(10, 30, 50), base.frames(KeyScope(effectIndex = 0)))
        assertEquals(listOf(10, 20), base.frames(KeyScope(effectIndex = 1)))
        assertEquals(emptyList<Int>(), base.frames(KeyScope(effectIndex = 5)))
    }

    @Test fun aParameterWithoutKeyframesOrAPropertyHasNone() {
        assertEquals(emptyList<Int>(), layer().frames(KeyScope(PropRef.effect(0, "missing"))))
        assertEquals(emptyList<Int>(), layer().frames(KeyScope(PropRef.Opacity)))
    }

    @Test fun movingOneParameterLeavesTheOthersInPlace() {
        val moved = layer().withKeyframesMoved(10, 25, scene, KeyScope(radius))
        assertEquals(listOf(25, 30), moved.keys(radius))
        assertEquals(listOf(10, 50), moved.keys(alpha))
        assertEquals(listOf(10, 20), moved.keys(tiles))
        assertEquals(listOf(10, 45), moved.keys(PropRef.Location))
    }

    @Test fun movingOneParameterPastItsOwnKeyKeepsBoth() {
        val moved = layer().withKeyframesMoved(30, 5, scene, KeyScope(radius))
        assertEquals(listOf(5, 10), moved.keys(radius))
        assertEquals(listOf(10, 50), moved.keys(alpha))
    }

    @Test fun movingAnOpenEffectMovesAllItsParametersAndNothingElse() {
        val moved = layer().withKeyframesMoved(10, 25, scene, KeyScope(effectIndex = 0))
        assertEquals(listOf(25, 30), moved.keys(radius))
        assertEquals(listOf(25, 50), moved.keys(alpha))
        assertEquals(listOf(10, 20), moved.keys(tiles))
        assertEquals(listOf(10, 45), moved.keys(PropRef.Location))
    }

    @Test fun movingATransformPropertyLeavesEffectsAlone() {
        val moved = layer().withKeyframesMoved(10, 25, scene, KeyScope(PropRef.Location))
        assertEquals(listOf(25, 45), moved.keys(PropRef.Location))
        assertEquals(listOf(10, 30), moved.keys(radius))
        assertEquals(listOf(10, 20), moved.keys(tiles))
    }

    @Test fun movingWhereTheScopeHasNoKeyChangesNothing() {
        val base = layer()
        assertEquals(base, base.withKeyframesMoved(20, 40, scene, KeyScope(radius)))
        assertSame(base, base.withKeyframesMoved(10, 10, scene, KeyScope(radius)))
    }

    @Test fun noScopeStillMovesEveryPropertyAtTheFrame() {
        val moved = layer().withKeyframesMoved(10, 25, scene)
        assertEquals(listOf(25, 30), moved.keys(radius))
        assertEquals(listOf(25, 50), moved.keys(alpha))
        assertEquals(listOf(20, 25), moved.keys(tiles))
        assertEquals(listOf(25, 45), moved.keys(PropRef.Location))
    }

    @Test fun theSpeedMapMovesOnlyWithTheSpeedTarget() {
        val speed = XmlNode("speedMap", children = listOf(
            XmlNode("kf", mapOf("t" to "0", "v" to "1")),
            XmlNode("kf", mapOf("t" to "1000000000", "v" to "2")),
        ))
        val plain = layer(listOf(speed))
        val at = scene.frameAt(VideoTiming(plain).localMs(1000.0).roundToInt())
        val glowAtSpeed = Layer(plain.node.copy(children = plain.node.children.map { if (it.name == "effect" && it["id"] == "glow") it.copy(children = listOf(property("radius", at, 30))) else it }))
        fun speedFrames(l: Layer) = l.node.child("speedMap")!!.keyframes.map { scene.frameAt(l.startTime + VideoTiming(l).localMs(it.t / 1e6).roundToInt()) }

        assertEquals(listOf(0, at), speedFrames(glowAtSpeed))
        assertEquals(listOf(0, at), glowAtSpeed.frames(KeyScope(SpeedRef)))
        assertEquals(listOf(at, 30), glowAtSpeed.frames(KeyScope(radius)))

        val glowMoved = glowAtSpeed.withKeyframesMoved(at, 40, scene, KeyScope(radius))
        assertEquals(listOf(30, 40), glowMoved.keys(radius))
        assertEquals(glowAtSpeed.node.child("speedMap"), glowMoved.node.child("speedMap"))

        val speedMoved = glowAtSpeed.withKeyframesMoved(at, 40, scene, KeyScope(SpeedRef))
        assertEquals(listOf(0, 40), speedFrames(speedMoved))
        fun media(l: Layer) = l.keyframes(radius).map { VideoTiming(l).mediaMs(it.t.toDouble() * (l.endTime - l.startTime)) }
        media(glowAtSpeed).zip(media(speedMoved)).forEach { (before, after) -> assertEquals(before, after, 1.0) }
    }

    @Test fun skippingStepsOnlyThroughTheScopedKeyframes() {
        val base = layer()
        assertEquals(listOf(0, 10, 20, 30, 45, 50, 59), scene.navigationStops(base))
        assertEquals(listOf(0, 10, 30, 59), scene.navigationStops(base, KeyScope(radius)))
        assertEquals(listOf(0, 10, 20, 59), scene.navigationStops(base, KeyScope(effectIndex = 1)))
    }
}
