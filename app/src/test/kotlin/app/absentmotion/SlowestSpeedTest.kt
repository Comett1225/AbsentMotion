package app.absentmotion

import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.withSlowestSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SlowestSpeedTest {
    private fun clip(speed: String, start: Int, end: Int) = Layer(XmlNode("shape", mapOf("id" to "1", "startTime" to "$start", "endTime" to "$end", "speed" to speed)))

    @Test fun anOldClipBelowTheFloorIsShortenedToTheSameFootage() {
        val fixed = clip("0.001", 1000, 1000 + 457 * 60_000).withSlowestSpeed()
        assertEquals("0.05", fixed.node["speed"])
        assertEquals(1000 + 548_400, fixed.endTime)
    }

    @Test fun clipsAtOrAboveTheFloorStayAsTheyAre() {
        val clip = clip("0.05", 0, 4000)
        assertSame(clip, clip.withSlowestSpeed())
        val normal = clip("1", 0, 4000)
        assertSame(normal, normal.withSlowestSpeed())
    }

    @Test fun aSpeedMapValueBelowTheFloorIsRaised() {
        val layer = Layer(XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "10000"), listOf(XmlNode("speedMap", mapOf("value" to "0.01")))))
        val fixed = layer.withSlowestSpeed()
        assertEquals("0.05", fixed.node.child("speedMap")?.get("value"))
        assertEquals(2000, fixed.endTime)
    }
}
