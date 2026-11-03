package app.absentmotion

import app.absentmotion.model.Scene
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthOrderTest {
    @Test
    fun defaultsAndRoundTrip() {
        val old = Scene.parse("<scene width=\"100\" height=\"100\"/>")
        assertFalse(old.depthOrder)
        assertTrue(Scene.parse(old.withDepthOrder(true).toXml()).depthOrder)
        assertFalse(Scene.parse(old.withDepthOrder(true).withDepthOrder(false).toXml()).depthOrder)
        assertTrue(Scene.create("test", 100, 100, 30, 0).depthOrder)
    }
}
