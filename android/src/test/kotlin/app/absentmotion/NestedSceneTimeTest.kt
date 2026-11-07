package app.absentmotion

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class NestedSceneTimeTest {
    private val scene = Scene.parse("""<scene width="200" height="200" fps="60"><embedScene id="1" startTime="2000" endTime="3999"><scene fps="24" totalTime="1999" retime="off">
        <text id="2" font="nested-font" startTime="0" endTime="1999"><effect id="com.alightcreative.effects.textspacing"><property name="letterspacing"><kf t="0" v="0"/><kf t="1" v="1"/></property></effect></text>
        <embedScene id="3" startTime="500" endTime="1500"><scene fps="30" totalTime="1000" retime="off"><text id="4" font="second-font" startTime="0" endTime="1000"/></scene></embedScene>
        <shape id="5" startTime="0" endTime="1999" fillType="media" fillVideo="clip"/>
        </scene></embedScene></scene>""")

    @Test fun allNestedFontsAreResolvedEvenBeforeTheirLayersStart() {
        assertEquals(listOf("nested-font", "second-font"), scene.fontNames())
    }

    @Test fun nestedTextUsesEachContainingScenesTimeAndFrameRate() {
        val seen = mutableMapOf<Long, Pair<Int, Double>>()
        scene.node.mapTimedLayers(scene.fps, 2600.0) { layer, fps, time -> seen[layer.id] = fps to time; layer.node }
        assertEquals(24 to 600.0, seen[2])
        assertEquals(30 to 100.0, seen[4])
    }

    @Test fun videoTracksExposeLocalTimeAndSkipInactiveAncestors() {
        val track = scene.layerTracks().single { it.layer.id == 5L }
        assertNull(track.timeAt(1000.0, true))
        assertEquals(600.0, track.timeAt(2600.0, true)!!.ms, 0.0)
        assertNull(track.timeAt(4000.0, true))
    }

    @Test fun blankRetimingRejectsFramesPastTheNestedScene() {
        val group = scene.layers.single()
        val nested = group.nested!!
        val blank = group.copy(node = group.node.withChild("scene", nested.node.with("retime", "blank")))
        assertEquals(-1.0, blank.nestedTime(4500.0, 60).ms, 0.0)
    }

    @Test fun hostTimeInvertsTheGroupsOwnClock() {
        val group = scene.layers.single()
        val cut = group.copy(node = group.node.with("inTime", "500"))
        assertEquals(1000.0, cut.nestedTime(2500.0, 60, quantize = false).ms, 0.0)
        assertEquals(2500.0, cut.nestedHostTime(1000.0, 60), 0.0)
        val half = cut.copy(node = cut.node.with("speed", "0.5"))
        assertEquals(500.0, half.nestedTime(2500.0, 60, quantize = false).ms, 0.0)
        assertEquals(2500.0, half.nestedHostTime(500.0, 60), 0.0)
    }

    @Test fun hostTimeInvertsAStretchedGroup() {
        val group = scene.layers.single()
        val long = group.copy(node = group.node.with("endTime", "5999").withChild("scene", group.nested!!.node.with("retime", "stretch")))
        for (host in listOf(2000.0, 3000.0, 4500.0, 5800.0)) {
            val inner = long.nestedTime(host, 60, quantize = false).ms
            assertEquals(host, long.nestedHostTime(inner, 60), 1.0)
        }
    }
}
