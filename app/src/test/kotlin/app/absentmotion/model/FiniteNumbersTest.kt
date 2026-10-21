package app.absentmotion.model

import app.absentmotion.audio.gain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FiniteNumbersTest {
    @Test fun nonFiniteTextIsNotANumber() {
        for (text in listOf("NaN", "-NaN", "Infinity", "+Infinity", "-Infinity", "1e40", "abc", "")) assertNull(text, text.finiteFloat())
        for (text in listOf("NaN", "Infinity", "-Infinity", "1e400")) assertNull(text, text.finiteDouble())
        assertEquals(1.5f, "1.5".finiteFloat())
        assertEquals(-2f, "-2".finiteFloat())
        assertEquals(0.25, "0.25".finiteDouble()!!, 0.0)
    }

    @Test fun projectValuesThatAreNotFiniteFallBackToTheirDefaults() {
        assertEquals(18f, Layer(XmlNode("text", mapOf("id" to "1", "size" to "NaN"))).textSize)
        assertEquals(18f, Layer(XmlNode("text", mapOf("id" to "1", "size" to "Infinity"))).textSize)
        assertEquals(1f, Layer(XmlNode("audio", mapOf("id" to "1"), listOf(XmlNode("gain", mapOf("value" to "NaN"))))).gain)
        assertArrayEquals(floatArrayOf(1f, 2f, 0f), floats("NaN,-Infinity", floatArrayOf(1f, 2f, 0f)), 0f)
        val keyed = XmlNode("opacity", emptyMap(), listOf(XmlNode("kf", mapOf("t" to "0", "v" to "0")), XmlNode("kf", mapOf("t" to "1", "v" to "NaN"))))
        assertEquals("0", keyed.valueAt(0.5f))
    }
}
