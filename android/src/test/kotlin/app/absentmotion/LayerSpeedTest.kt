package app.absentmotion

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class LayerSpeedTest {
    private val scene = Scene.parse("""<scene width="200" height="200" fps="60">
        <text id="1" startTime="1000" endTime="3000"><transform><opacity><kf t="0" v="0"/><kf t="1" v="1"/></opacity></transform></text>
        <nullobj id="2" startTime="0" endTime="1000"/>
        <embedScene id="3" startTime="2000" endTime="3999" inTime="400"><scene fps="60" totalTime="4000" retime="off"><text id="4" startTime="0" endTime="4000"/></scene></embedScene>
        <embedScene id="5" startTime="0" endTime="1000"><scene fps="60" totalTime="1000" retime="loop"/></embedScene>
        <shape id="6" startTime="0" endTime="1000" fillType="color"/>
        <shape id="7" startTime="0" endTime="1000" fillType="media" fillVideo="clip"/>
        </scene>""")

    private fun layer(id: Long) = scene.layer(id)!!

    @Test fun textNullAndPlainGroupsCanChangeSpeed() {
        assertTrue(layer(1).speedable)
        assertTrue(layer(2).speedable)
        assertTrue(layer(3).speedable)
        assertFalse(layer(5).speedable)
        assertFalse(layer(6).speedable)
        assertTrue(layer(7).speedable)
    }

    @Test fun textAnimationPlaysFasterAndReturnsToNormal() {
        val fast = layer(1).withLayerSpeed(2f)
        assertEquals(1000, fast.startTime)
        assertEquals(2000, fast.endTime)
        assertEquals(2f, fast.layerSpeed())
        assertEquals(layer(1).node.child("transform"), fast.node.child("transform"))
        val back = fast.withLayerSpeed(1f)
        assertEquals(3000, back.endTime)
        assertNull(back.node["speed"])
        assertEquals(1500, layer(1).withLayerSpeed(0.5f).withLayerSpeed(4f).endTime)
    }

    @Test fun groupShowsTheSameContentFasterWithItsCutScaled() {
        val group = layer(3)
        val fast = group.withLayerSpeed(2f)
        assertEquals(2000 + 1000, fast.endTime)
        assertEquals("200", fast.node["inTime"])
        for (u in listOf(0.0, 0.25, 0.5, 1.0)) {
            val before = group.nestedTime(group.startTime + u * (group.endTime - group.startTime), 60, quantize = false).ms
            val after = fast.nestedTime(fast.startTime + u * (fast.endTime - fast.startTime), 60, quantize = false).ms
            assertEquals(before, after, 2.0)
        }
        assertEquals(2.0, fast.nestedTime(2500.0, 60, quantize = false).rate, 0.0)
    }
}
