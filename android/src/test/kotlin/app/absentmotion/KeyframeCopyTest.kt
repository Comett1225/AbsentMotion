package app.absentmotion

import app.absentmotion.editor.keysAtPlayhead
import app.absentmotion.editor.withKeysPasted
import app.absentmotion.model.KeyScope
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.effects
import app.absentmotion.model.keyframes
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyframeCopyTest {
    private val scene = Scene.parse(
        """<scene title="t" width="100" height="100" totalTime="2999" fps="30">
          <shape id="1" startTime="0" endTime="2999" s=".rect">
            <transform>
              <location><kf t="0.100000" v="10,20,0" /><kf t="0.500000" v="90,80,0" /></location>
              <rotation value="0" />
            </transform>
            <effect id="blur"><property name="strength" type="float"><kf t="0.100000" v="3" e="cubicBezier 0.4 0 0.2 1" /></property></effect>
          </shape>
          <shape id="2" startTime="0" endTime="2999" s=".rect"><transform><location value="5,5,0" /></transform></shape>
          <shape id="3" startTime="0" endTime="999" s=".rect"><transform><location value="1,1,0" /></transform></shape>
        </scene>""",
    )

    private fun layer(id: Long, ms: Int) = scene.layerAt(id, ms)!!

    @Test fun copiesEveryKeyframeUnderThePlayheadAndPastesThemLater() {
        val keys = layer(1, 300).keysAtPlayhead(null)
        assertEquals(2, keys.size)
        val pasted = layer(1, 2400).withKeysPasted(keys)
        assertEquals(listOf("10,20,0", "90,80,0", "10,20,0"), pasted.keyframes(PropRef.Location).map { it.v })
        val strength = pasted.keyframes(PropRef.effect(0, "strength"))
        assertEquals(listOf("3", "3"), strength.map { it.v })
        assertEquals("cubicBezier 0.4 0 0.2 1", strength.last().e)
        assertEquals(pasted.t, strength.last().t, 1e-6f)
    }

    @Test fun aScopeCopiesOnlyThatProperty() {
        assertEquals(1, layer(1, 300).keysAtPlayhead(KeyScope(PropRef.Location)).size)
        assertEquals(1, layer(1, 300).keysAtPlayhead(KeyScope(effectIndex = 0)).size)
        assertEquals(0, layer(1, 1000).keysAtPlayhead(null).size)
    }

    @Test fun pastingOnAnotherLayerKeysTheSameProperty() {
        val keys = layer(1, 300).keysAtPlayhead(KeyScope(PropRef.Location))
        val other = layer(2, 1500).withKeysPasted(keys)
        assertEquals(listOf("10,20,0"), other.keyframes(PropRef.Location).map { it.v })
        assertEquals(null, other.element(PropRef.Location)!!["value"])
        assertEquals(0, other.effects.size)
    }

    @Test fun pastingFindsPropertiesByNameAndAddsMissingOnes() {
        val from = Scene.parse(
            """<scene title="t" width="100" height="100" totalTime="2999" fps="30">
              <shape id="1" startTime="0" endTime="2999" s=".rect"><transform>
                <location><kf t="0.100000" v="10,20,0" /></location><scale><kf t="0.100000" v="2,2" /></scale><rotation><kf t="0.100000" v="45" /></rotation>
              </transform><timeRemap><kf t="0.100000" v="500" /></timeRemap></shape>
              <shape id="2" startTime="0" endTime="2999" s=".rect"><transform><location value="5,5,0" /><rotation value="10" /></transform></shape>
            </scene>""",
        )
        val keys = from.layerAt(1, 300)!!.keysAtPlayhead(null)
        val other = from.layerAt(2, 1500)!!.withKeysPasted(keys)
        assertEquals(listOf("10,20,0"), other.keyframes(PropRef.Location).map { it.v })
        assertEquals(listOf("2,2"), other.keyframes(PropRef.Scale).map { it.v })
        assertEquals(listOf("45"), other.keyframes(PropRef.Rotation).map { it.v })
        assertEquals(null, other.node.child("timeRemap"))
    }

    @Test fun aKeyframeAlreadyAtThePlayheadIsReplaced() {
        val keys = layer(1, 300).keysAtPlayhead(KeyScope(PropRef.Location))
        assertEquals(listOf("10,20,0", "10,20,0"), layer(1, 1500).withKeysPasted(keys).keyframes(PropRef.Location).map { it.v })
    }

    @Test fun aLayerThatIsNotAtThePlayheadIsLeftAlone() {
        val keys = layer(1, 300).keysAtPlayhead(null)
        val outside = layer(3, 2400)
        assertEquals(outside, outside.withKeysPasted(keys))
    }
}
