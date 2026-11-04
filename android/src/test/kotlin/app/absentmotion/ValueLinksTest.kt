package app.absentmotion

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class ValueLinksTest {
    private val x = NumberRef(PropRef.Location)
    private val y = NumberRef(PropRef.Location, 1)
    private val rotation = NumberRef(PropRef.Rotation)
    private fun layer(id: Long) = Layer(XmlNode("shape", mapOf("id" to "$id", "startTime" to "0", "endTime" to "2000"), listOf(XmlNode("transform", children = listOf(
        XmlNode("location").withKeyframes(listOf(Keyframe(0f, "10,20,0"), Keyframe(1f, "110,220,0"))),
        XmlNode("rotation", mapOf("value" to "7")),
    )))))
    private fun scene() = Scene.create("Links", 512, 512, 30, 0).add(layer(1)).add(layer(2)).add(layer(3))

    @Test fun multiplyAddDelayAreKept() {
        val link = ValueLink(1, x, 2f, 3f, .5f)
        val scene = scene().withValueLink(2, rotation, link)
        assertEquals(link, scene.layer(2)!!.element(rotation.ref)!!.valueLink(0))
    }

    @Test fun anOldProjectLosesItsLinksOnLoadAndKeepsItsOwnValues() {
        val before = scene()
        val linked = before.withValueLink(2, rotation, ValueLink(1, x, 2f)).withValueLink(2, x, ValueLink(3, y))
            .update(3) { it.copy(node = it.node.copy(children = listOf(XmlNode("scene", children = listOf(layer(4).withValueLink(rotation, ValueLink(1, x)).node))))) }
        val loaded = Scene.parse(linked.toXml())
        assertFalse(loaded.toXml().contains("valueLink"))
        assertEquals(before.layer(2)!!.node, loaded.layer(2)!!.node)
        val keyed = loaded.layer(2)!!.element(rotation.ref)!!.withKeyframeToggled(0f, 0f..0f)
        assertEquals(listOf(Keyframe(0f, "7")), keyed.keyframes)
    }

    @Test fun linkingOneAxisLeavesTheOthersEditable() {
        val linked = scene().withValueLink(2, x, ValueLink(1, rotation))
        val location = linked.layer(2)!!.element(PropRef.Location)!!
        val moved = location.withValueAt(1f, "999,333,5", 1f..1f)
        assertEquals("110,333,5", moved.keyframes.last().v.split(",").joinToString(",") { it.toFloat().toInt().toString() })
        assertEquals(ValueLink(1, rotation), moved.valueLink(0))
        val turned = scene().withValueLink(2, rotation, ValueLink(1, x)).layer(2)!!.element(PropRef.Rotation)!!
        assertSame(turned, turned.withValueAt(0f, "45", 0f..0f))
    }

    @Test fun chainsAndSameLayer() {
        val scene = scene().withValueLink(1, rotation, ValueLink(1, y)).withValueLink(2, rotation, ValueLink(1, rotation, .5f))
        assertEquals(ValueLink(1, rotation, .5f), scene.layer(2)!!.element(rotation.ref)!!.valueLink(0))
        assertTrue(scene.canLink(1, x, ValueLink(1, y)))
    }

    @Test fun cyclesAreRefused() {
        val scene = scene().withValueLink(2, x, ValueLink(1, x)).withValueLink(3, x, ValueLink(2, x))
        assertFalse(scene.canLink(1, x, ValueLink(3, x)))
        assertFalse(scene.canLink(1, x, ValueLink(1, x)))
        assertEquals(scene, scene.withValueLink(1, x, ValueLink(3, x)))
        assertFalse(scene.canLink(2, x, ValueLink(99, x)))
    }

    @Test fun unlinkRestoresExactKeyframes() {
        val before = scene()
        val linked = before.withValueLink(2, x, ValueLink(1, y))
        assertEquals(before.layer(2)!!.keyframes(PropRef.Location), linked.layer(2)!!.keyframes(PropRef.Location))
        assertEquals(before.node, linked.withValueLink(2, x, null).node)
    }

    @Test fun deletingSourceRemovesOnlyItsLinks() {
        val scene = scene().withValueLink(2, x, ValueLink(1, x)).withValueLink(2, y, ValueLink(3, y))
        val next = scene.remove(1)
        assertNull(next.layer(2)!!.element(x.ref)!!.valueLink(0))
        assertEquals(3, next.layer(2)!!.element(y.ref)!!.valueLink(1)!!.layer)
        assertEquals(2, next.layer(2)!!.keyframes(x.ref).size)
    }

    @Test fun copiesRemapTheirLinks() {
        val scene = scene().withValueLink(2, rotation, ValueLink(1, x, -2f, 20f, .25f))
        var id = 10L
        val copies = copyLayers(scene.layers, newId = { id++ })
        assertEquals(copies[0].id, copies[1].element(rotation.ref)!!.valueLink(0)!!.layer)
    }

    @Test fun retainedKeysKeepTheLinkedAxisWhileOtherAxesChange() {
        val linked = layer(1).withValueLink(x, ValueLink(2, y))
        val edited = linked.withValue(x.ref, "50,60,0").element(x.ref)!!
        assertEquals("10,60,0", edited.keyframes.first().v)
        assertEquals(ValueLink(2, y), edited.valueLink(0))
        assertEquals(ValueLink(2, y), linked.withKeyframeToggled(x.ref, "0,0,0").element(x.ref)!!.valueLink(0))
        val whole = layer(1).withValueLink(rotation, ValueLink(2, y))
        assertEquals(whole, whole.withValue(rotation.ref, "45"))
    }

    @Test fun effectLinksFollowReorderingAndRemoval() {
        val effect = XmlNode("effect", mapOf("id" to "absent.spin"), listOf(XmlNode("property", mapOf("name" to "rpm", "type" to "float", "value" to "10"))))
        val other = XmlNode("effect", mapOf("id" to "absent.swing"))
        val before = scene().update(1) { it.addEffect(effect).addEffect(other) }.withValueLink(2, rotation, ValueLink(1, NumberRef(PropRef.effect(0, "rpm"))))
        val moved = before.update(1) { it.moveEffect(0, 1) }.withValueLinksFollowing(before)
        assertEquals(1, moved.layer(2)!!.element(rotation.ref)!!.valueLink(0)!!.source.ref.effectIndex)
        val removed = moved.update(1) { it.removeEffect(1) }.withValueLinksFollowing(moved)
        assertNull(removed.layer(2)!!.element(rotation.ref)!!.valueLink(0))
        assertEquals(before.layer(2)!!.keyframes(rotation.ref), removed.layer(2)!!.keyframes(rotation.ref))
    }

    @Test fun parentAndEffectCyclesAreRefused() {
        val base = scene().update(2) { it.copy(node = it.node.with("parent", "1")) }
        assertFalse(base.canLink(1, x, ValueLink(2, x)))
        val effect = XmlNode("effect", mapOf("id" to "absent.spin"), listOf(XmlNode("property", mapOf("name" to "rpm", "value" to "10"), listOf(ValueLink(1, rotation).node(0)))))
        val next = base.update(2) { it.addEffect(effect) }
        assertFalse(next.canLink(1, rotation, ValueLink(2, rotation)))
    }
}
