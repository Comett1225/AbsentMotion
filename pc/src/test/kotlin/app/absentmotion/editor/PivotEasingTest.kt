package app.absentmotion.editor

import app.absentmotion.model.Scene
import app.absentmotion.model.floats
import app.absentmotion.model.keyframes
import org.junit.Assert.assertEquals
import org.junit.Test

class PivotEasingTest {
    @Test fun movingAKeyedPivotBetweenKeysWithABounceOrPrefixedEasingAddsAKey() {
        for (easing in listOf("bounce 0.4 0.5", "reverse cubicBezier 0.3 0 0.7 1")) {
            val s = Scene.parse("""<scene width="400" height="400" fps="30"><shape id="1" startTime="0" endTime="1000"><transform>""" +
                """<location value="50,50,0"/><scale value="1.5,1.5"/><pivot><kf t="0" v="0,0,0"/><kf t="1" v="20,0,0" e="$easing"/></pivot>""" +
                """</transform></shape></scene>""")
            val keys = s.withPivotMoved(1, 500, 30f, 10f).layer(1)!!.node.child("transform")!!.child("pivot")!!.keyframes
            assertEquals(listOf(0f, 0.5f, 1f), keys.map { it.t })
            assertEquals(listOf(30f, 10f), floats(keys[1].v, floatArrayOf(0f, 0f, 0f)).take(2))
            assertEquals(easing, keys[2].e)
        }
    }
}
