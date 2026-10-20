package app.absentmotion.editor

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class TiltTest {
    private fun layer(children: List<XmlNode> = emptyList()) =
        Layer(XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "1000"), children))

    @Test fun anOldLayerHasNoTiltAndKeepsItsFile() {
        val old = Scene.parse("""<scene width="100" height="100"><shape id="1" startTime="0" endTime="1000"><transform><rotation value="-30"/></transform></shape></scene>""")
        val l = old.layer(1)!!
        assertArrayEquals(floatArrayOf(0f, 0f), l.tilt, 0f)
        assertEquals(30f, l.rotation, 0f)
        assertNull(l.node.child("transform")?.child("tilt"))
    }

    @Test fun tiltIsStoredInDegreesNextToTheRotation() {
        val l = layer().withRotation(15f).withTilt(20f, -45f)
        assertArrayEquals(floatArrayOf(20f, -45f), l.tilt, 1e-5f)
        assertEquals(15f, l.rotation, 1e-5f)
        assertEquals(floatArrayOf(20f, -45f).let(::formatFloats), l.node.child("transform")?.child("tilt")?.get("value"))
        val reread = Layer(XmlNode.parse(l.node.toXml()))
        assertArrayEquals(l.tilt, reread.tilt, 0f)
    }

    @Test fun tiltCanBeKeyframed() {
        val start = layer().withTilt(0f, 0f).withKeyframeToggled(PropRef.Tilt, "0,0")
        val end = start.copy(t = 1f).withTilt(0f, 180f)
        assertEquals(2, end.keyframes(PropRef.Tilt).size)
        assertArrayEquals(floatArrayOf(0f, 90f), end.copy(t = 0.5f).tilt, 1e-3f)
    }

    @Test fun aPresetCarriesAKeyframedTilt() {
        val moving = layer().withTilt(0f, 0f).withKeyframeToggled(PropRef.Tilt, "0,0").copy(t = 1f).withTilt(60f, 0f)
        val applied = applyPreset(layer(), moving)
        assertEquals(moving.node.child("transform")?.child("tilt"), applied.node.child("transform")?.child("tilt"))
    }
}
