package app.absentmotion

import app.absentmotion.editor.withClipEdgesAt
import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class ClipTimingTest {
    private val video = """<shape id="5" startTime="0" endTime="3999" fillType="media" fillVideo="clip"/>"""

    private fun group(start: Int, end: Int, children: String = "", inner: String = "", retime: String = "off", out: Int = 0) =
        """<embedScene id="1" startTime="$start" endTime="$end">$children<scene width="100" height="100" fps="30" totalTime="3999" retime="$retime" retimeOut="$out">$inner</scene></embedScene>"""

    private fun scene(vararg layers: String) = Scene.parse("""<scene width="100" height="100" fps="30">${layers.joinToString("")}</scene>""")

    @Test fun splitAndDuplicateInsertDirectlyAboveTheSource() {
        val s = scene("""<shape id="1" startTime="0" endTime="3999"/>""", """<text id="2" startTime="0" endTime="3999"/>""")
        assertEquals(listOf(1L, 3L, 2L), s.withSplit(s.layer(1)!!, 60, 3)!!.layers.map { it.id })
        assertEquals(listOf(1L, 4L, 2L), s.addAbove(Layer(XmlNode("shape", mapOf("id" to "4"))), 1).layers.map { it.id })
    }

    @Test fun splittingOrTrimmingAGroupCutsItsHeadInsteadOfRestartingIt() {
        val s = scene(group(0, 3999, inner = video))
        val split = s.withSplit(s.layer(1)!!, 60, 2)!!
        val second = split.layer(2)!!
        assertEquals(2000, second.startTime)
        assertEquals(1500.0, split.layer(1)!!.nestedTime(1500.0, 30).ms, 0.0)
        assertEquals(2500.0, second.nestedTime(2500.0, 30).ms, 0.0)
        assertEquals(2500.0, split.layerTracks().single { it.parents.single().first.id == 2L }.timeAt(2500.0, true)!!.ms, 0.0)
        val extended = second.trimmedTo(1000, second.endTime)
        assertEquals(1500.0, extended.nestedTime(1500.0, 30).ms, 0.0)
        assertNull(extended.trimmedTo(0, extended.endTime).node["inTime"])
    }

    @Test fun aSplitElementKeepsItsOutroWhereItWas() {
        val s = scene(group(0, 3999, retime = "freeze", out = 3000))
        val second = s.withSplit(s.layer(1)!!, 60, 2)!!.layer(2)!!
        for (t in listOf(2100.0, 2900.0, 3500.0, 3990.0)) assertEquals(s.layer(1)!!.nestedTime(t, 30).ms, second.nestedTime(t, 30).ms, 0.0)
    }

    @Test fun aGroupsTimeQuantizationStepsTheVideoInsideButNotItsSound() {
        val s = scene(group(0, 3999, children = """<effect id="absent.timequant"><property name="fps" value="5"/></effect>""", inner = video))
        val track = s.layerTracks().single()
        assertEquals(2200.0, track.timeAt(2300.0, true)!!.ms, 0.0)
        assertEquals(2300.0, track.timeAt(2300.0, true, quantize = false)!!.ms, 0.0)
    }

    @Test fun aKeyframedGroupSpeedUsesItsFirstKeyLikeTheEngine() {
        val s = scene(group(0, 3999, children = """<speedMap><kf t="0" v="2"/><kf t="1000000000" v="4"/></speedMap>"""))
        assertEquals(2000.0, s.layer(1)!!.nestedTime(1000.0, 30).ms, 0.0)
        assertEquals(30, Scene.parse("""<scene fps="29.97"/>""").fps)
    }

    @Test fun aNegativeNestedLengthDoesNotCrash() {
        val s = Scene.parse("""<scene width="100" height="100" fps="30"><embedScene id="1" startTime="0" endTime="999"><scene totalTime="-5000" retime="freeze">
            <shape id="2" startTime="0" endTime="999" fillType="media" fillVideo="v"/></scene></embedScene></scene>""")
        assertEquals(1, s.layerTracks().size)
        assertEquals(16.0, s.layer(1)!!.nestedTime(500.0, 30).ms, 0.0)
    }

    @Test fun keyframesWinOverAStaticValueLikeInTheEngine() {
        val node = XmlNode.parse("""<rotation value="15"><kf t="0" v="30"/><kf t="1" v="45"/></rotation>""")
        assertEquals("30", node.valueAt(0f))
        assertEquals("45", node.valueAt(1f))
        assertEquals("15", XmlNode("rotation", mapOf("value" to "15")).valueAt(0.5f))
    }

    @Test fun extendingToThePlayheadOnlyMovesEdgesOutwardAndStopsAtTheSourceEnd() {
        val s = Scene.parse("""<scene width="100" height="100" fps="30"><media uri="v" duration="5000000"/>
            <shape id="1" startTime="0" endTime="4999" fillType="media" fillVideo="v"/><shape id="2" startTime="6000" endTime="7999"/></scene>""")
        val starts = s.withClipEdgesAt(listOf(1, 2), 120, end = false, extend = true)
        assertEquals(0 to 4999, starts.layer(1)!!.let { it.startTime to it.endTime })
        assertEquals(4000 to 7999, starts.layer(2)!!.let { it.startTime to it.endTime })
        assertEquals(4999, s.withClipEdgesAt(listOf(1), 120, end = true, extend = true).layer(1)!!.endTime)
        val ends = s.withClipEdgesAt(listOf(1, 2), 240, end = true, extend = true)
        assertEquals(5000, ends.layer(1)!!.endTime)
        assertEquals("5000", ends.layer(1)!!.node["outTime"])
        assertEquals(8032, ends.layer(2)!!.endTime)
    }
}
