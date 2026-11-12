package app.absentmotion.effects

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.feedbackStep
import app.absentmotion.model.feedbackTrail
import app.absentmotion.model.trailBudgetMb
import app.absentmotion.model.videoTimeUs
import app.absentmotion.shapeLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedbackTrailTest {
    private val scene = Scene.parse("""<scene width="100" height="100" fps="30" totalTime="12000"/>""")

    private fun property(name: String, value: String) = XmlNode("property", mapOf("name" to name, "type" to "float", "value" to value))

    private fun layer(vararg params: Pair<String, String>, hidden: Boolean = false, fill: String = "fillVideo", attrs: Map<String, String> = emptyMap()) =
        shapeLayer(id = 3, start = 1000, end = 12000, attrs = mapOf("fillType" to "media", fill to "file:///v.mp4", "inTime" to "0", "outTime" to "20000") + attrs,
            children = listOf(XmlNode("effect", mapOf("id" to "absent.videofeedback", "hidden" to "$hidden"), params.map { (name, value) -> property(name, value) })))

    @Test fun onlyAVideoLayerWithFrameByFrameOnKeepsATrail() {
        assertNull(layer("trails" to "0").feedbackTrail(3000.0, scene))
        assertNull(layer().feedbackTrail(3000.0, scene))
        assertNull(layer("trails" to "1", hidden = true).feedbackTrail(3000.0, scene))
        assertNull(layer("trails" to "1", fill = "fillImage").feedbackTrail(3000.0, scene))
        assertEquals(24, layer("trails" to "1").feedbackTrail(3000.0, scene)!!.size)
    }

    @Test fun eachCopyIsOneProjectFrameEarlier() {
        val trail = layer("trails" to "1", "copies" to "5").feedbackTrail(scene.frameStart(100).toDouble(), scene)!!
        assertEquals((99 downTo 95).map { scene.frameStart(it) }, trail)
    }

    @Test fun delaySpacesTheCopiesInSeconds() {
        assertEquals(listOf(2750, 2500, 2250), layer("trails" to "1", "copies" to "3", "delay" to "0.25").feedbackTrail(3000.0, scene))
    }

    @Test fun copiesBeforeTheLayerStartShowItsFirstFrame() {
        val trail = layer("trails" to "1", "copies" to "4").feedbackTrail(scene.frameStart(32).toDouble(), scene)!!
        assertEquals(listOf(scene.frameStart(31), 1000, 1000, 1000), trail)
    }

    @Test fun fadedOutCopiesAreNotAskedFor() {
        assertEquals(7, layer("trails" to "1", "copies" to "64", "fade" to "0.5").feedbackTrail(6000.0, scene)!!.size)
        assertNull(layer("trails" to "1", "fade" to "0").feedbackTrail(6000.0, scene))
    }

    @Test fun mediaTimesFollowTheLayersOwnTiming() {
        val plain = layer("trails" to "1", "copies" to "2")
        val trimmed = layer("trails" to "1", "copies" to "2", attrs = mapOf("inTime" to "4000"))
        val at = scene.frameStart(90)
        val a = plain.feedbackTrail(at.toDouble(), scene)!!.map { plain.videoTimeUs(it, scene) }
        val b = trimmed.feedbackTrail(at.toDouble(), scene)!!.map { trimmed.videoTimeUs(it, scene) }
        assertEquals(listOf(plain.videoTimeUs(scene.frameStart(89), scene), plain.videoTimeUs(scene.frameStart(88), scene)), a)
        assertEquals(a.map { it + 4_000_000 }, b)
        assertEquals(33_333.0, (a[0] - a[1]).toDouble(), 1000.0)
    }

    @Test fun eachCopyShrinksByTheZoomAndTheLayersOwnScale() {
        assertEquals(0.85f, layer("trails" to "1").feedbackStep(3000.0, scene), 1e-4f)
        assertEquals(0.5f, layer("trails" to "1", "zoom" to "0.5").feedbackStep(3000.0, scene), 1e-4f)
        val scaled = shapeLayer(id = 3, start = 1000, end = 12000, children = listOf(
            XmlNode("transform", children = listOf(XmlNode("scale", mapOf("value" to "2,0.5")))),
            XmlNode("effect", mapOf("id" to "absent.videofeedback"), listOf(property("zoom", "0.5")))))
        assertEquals(1f, scaled.feedbackStep(3000.0, scene), 1e-4f)
        assertEquals(0.5f, scaled.copy(node = scaled.node.copy(children = scaled.node.children.map {
            if (it.name == "effect") it.copy(children = it.children + property("useLayerTransform", "0")) else it
        })).feedbackStep(3000.0, scene), 1e-4f)
    }

    @Test fun weakDevicesKeepTheSmallTrailBudget() {
        assertEquals(24, trailBudgetMb(3700, lowRam = false, memoryClassMb = 256, compatible = false))
        assertEquals(24, trailBudgetMb(16000, lowRam = true, memoryClassMb = 512, compatible = false))
        assertEquals(24, trailBudgetMb(16000, lowRam = false, memoryClassMb = 512, compatible = true))
        assertEquals(24, trailBudgetMb(7600, lowRam = false, memoryClassMb = 192, compatible = false))
        assertEquals(48, trailBudgetMb(7600, lowRam = false, memoryClassMb = 256, compatible = false))
        assertEquals(64, trailBudgetMb(11500, lowRam = false, memoryClassMb = 512, compatible = false))
    }
}
