package app.absentmotion.audio

import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioClipTest {
    private fun clip(vararg attrs: Pair<String, String>) =
        AudioClip(Layer(XmlNode("audio", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "9999", "src" to "a.m4a", *attrs))), "a.m4a")

    @Test fun aLoopWithoutAnEndPlaysOnInsteadOfRepeatingItsFirstBlock() {
        val open = clip("loop" to "true", "inTime" to "200")
        assertEquals(5000.0, open.wrap(5000.0), 1e-9)
        val span = open.span(0.0, 1024, 1000.0 / 48000, 0)!!
        assertTrue(span.step > 0)
        assertEquals(open.mediaAt(0.0), span.pos, 1e-9)
        val bounded = clip("loop" to "true", "inTime" to "200", "outTime" to "1200")
        assertEquals(200.0, bounded.wrap(5200.0), 1e-9)
        assertEquals(700.0, bounded.wrap(1700.0), 1e-9)
        assertEquals(5000.0, clip("outTime" to "1200").wrap(5000.0), 1e-9)
    }
}
