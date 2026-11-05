package app.absentmotion.editor

import app.absentmotion.model.MotionSlot
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.motion
import app.absentmotion.model.withAnimation
import app.absentmotion.sceneOf
import app.absentmotion.shapeNode
import app.absentmotion.videoNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StretchTimingTest {
    private val spin = XmlNode("transform", children = listOf(XmlNode("rotation", children = listOf(
        XmlNode("kf", mapOf("t" to "0.2", "v" to "0")), XmlNode("kf", mapOf("t" to "0.8", "v" to "90")),
    ))))

    private val group = shapeNode("embedScene", id = 3, start = 3000, end = 4999, children = listOf(
        XmlNode("scene", mapOf("fps" to "30", "totalTime" to "1999", "retime" to "off"), listOf(shapeNode(id = 31, start = 0, end = 1999))),
    ))

    private val start = sceneOf(shapeNode(id = 1, start = 1000, end = 2999, children = listOf(spin)), videoNode(2000, 3999, id = 2, speed = "1"), group)
        .withAnimation(1, MotionSlot.In, "fade", 500)

    private fun Scene.times(id: Long) = layer(id)!!.let { it.startTime to it.endTime }
    private fun Scene.speed(id: Long) = layer(id)!!.node["speed"]!!.toDouble()

    @Test fun longerScalesFromTheEarliestStartAndSlowsVideosAndGroups() {
        val longer = start.stretchedTiming(setOf(1, 2, 3), 150)
        assertEquals(1000 to 3999, longer.times(1))
        assertEquals(2500 to 5499, longer.times(2))
        assertEquals(4000 to 6999, longer.times(3))
        assertEquals(start.layer(1)!!.node.child("transform"), longer.layer(1)!!.node.child("transform"))
        assertEquals(750, longer.layer(1)!!.motion(MotionSlot.In)!!.ms)
        assertEquals(1 / 1.5, longer.speed(2), 1e-3)
        assertEquals(1 / 1.5, longer.speed(3), 1e-3)
        assertEquals(start.layer(3)!!.nested!!.node, longer.layer(3)!!.nested!!.node)
        assertEquals(6999, longer.totalTime)
    }

    @Test fun shorterHalvesTimesAndSpeedsUp() {
        val shorter = start.stretchedTiming(setOf(1, 2, 3), 50)
        assertEquals(1000 to 1999, shorter.times(1))
        assertEquals(1500 to 2499, shorter.times(2))
        assertEquals(2000 to 2999, shorter.times(3))
        assertEquals(start.layer(1)!!.node.child("transform"), shorter.layer(1)!!.node.child("transform"))
        assertEquals(250, shorter.layer(1)!!.motion(MotionSlot.In)!!.ms)
        assertEquals(2.0, shorter.speed(2), 1e-2)
        assertEquals(2.0, shorter.speed(3), 1e-3)
        assertSame(start.node, start.stretchedTiming(setOf(1, 2, 3), 100).node)
    }
}
