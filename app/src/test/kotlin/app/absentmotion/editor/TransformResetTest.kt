package app.absentmotion.editor

import app.absentmotion.effects.EffectParam
import app.absentmotion.model.CameraFov
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.cameraFov
import app.absentmotion.model.keyframes
import app.absentmotion.sceneOf
import app.absentmotion.shapeNode
import app.absentmotion.ui.editor.panels.Turn
import org.junit.Assert.*
import org.junit.Test

class TransformResetTest {
    private fun animated(name: String, first: String, last: String) = XmlNode(name, children = listOf(
        XmlNode("kf", mapOf("t" to "0", "v" to first)), XmlNode("kf", mapOf("t" to "1", "v" to last)),
    ))

    private fun undoOnce(before: Scene, after: Scene) {
        var current = before
        val history = EditHistory({ current }) { next, _ -> current = next }
        history.edit(null, after)
        assertEquals(before, history.undo())
        assertFalse(history.canUndo)
        assertEquals(after, history.redo())
    }

    @Test fun locationAndScaleResetClearKeysAndUndoRestoresThem() {
        val before = sceneOf(shapeNode(end = 1000, children = listOf(XmlNode("transform", children = listOf(
            animated("location", "10,20,0", "50,80,0"), animated("scale", "2,3", "4,5"),
        )))))
        for ((tab, ref, expected) in listOf(
            Triple(0, PropRef.Location, listOf(200f, 200f, 0f)), Triple(2, PropRef.Scale, listOf(1f, 1f)),
        )) {
            val after = before.update(1, 500) { before.resetTransform(it, 500, listOf(tab)) }
            val layer = after.layer(1)!!
            assertTrue(layer.keyframes(ref).isEmpty())
            assertEquals(expected, if (tab == 0) layer.location.toList() else layer.scale.toList())
            assertEquals(2, layer.keyframes(if (tab == 0) PropRef.Scale else PropRef.Location).size)
            undoOnce(before, after)
        }
    }

    @Test fun allTransformResetsClearTheirKeysInOneStep() {
        val names = listOf("location", "rotation", "scale", "tilt", "skew", "pivot")
        val refs = listOf(PropRef.Location, PropRef.Rotation, PropRef.Scale, PropRef.Tilt, PropRef.Skew, PropRef.Pivot)
        val values = listOf("10,20,0", "20", "2,3", "10,20", "0.1,0.2", "10,20,0")
        val before = sceneOf(shapeNode(end = 1000, children = listOf(XmlNode("transform", children = names.indices.map { animated(names[it], values[it], values[it]) }))))
        val after = before.update(1, 500) { before.resetTransform(it, 500, names.indices.toList()) }
        val layer = after.layer(1)!!
        assertTrue(refs.all { layer.keyframes(it).isEmpty() })
        assertEquals(listOf(200f, 200f, 0f), layer.location.toList())
        assertEquals(listOf(1f, 1f), layer.scale.toList())
        assertEquals(listOf(0f, 0f, 0f), layer.pivot.toList())
        assertEquals(0f, layer.rotation, 0f)
        assertEquals(listOf(0f, 0f), layer.tilt.toList())
        assertEquals(listOf(0f, 0f), layer.skew.toList())
        undoOnce(before, after)
    }

    @Test fun pivotResetClearsKeysEvenWhenTheCurrentValueIsZero() {
        val before = sceneOf(shapeNode(end = 1000, children = listOf(XmlNode("transform", children = listOf(
            animated("pivot", "0,0,0", "20,0,0"),
        )))))
        val after = before.withPivotReset(1, 0)
        assertTrue(after.layer(1)!!.keyframes(PropRef.Pivot).isEmpty())
        assertEquals(listOf(0f, 0f, 0f), after.layer(1)!!.pivot.toList())
        undoOnce(before, after)
    }

    @Test fun pivotResetKeepsTheCurrentPictureForAPlainPivot() {
        val before = sceneOf(shapeNode(end = 1000, children = listOf(XmlNode("transform", children = listOf(
            XmlNode("pivot", mapOf("value" to "10,0,0")), XmlNode("scale", mapOf("value" to "2,2")),
        )))))
        val after = before.withPivotReset(1, 0)
        val layer = after.layer(1)!!
        assertEquals(listOf(0f, 0f, 0f), layer.pivot.toList())
        assertEquals(-20f, layer.location[0], 0.001f)
        undoOnce(before, after)
    }

    @Test fun cameraFovResetClearsKeys() {
        val before = sceneOf(shapeNode(kind = "camera", end = 1000, children = listOf(animated("fov", "30", "90"))))
        val after = before.update(1, 500) { before.resetTransform(it, 500, listOf(2)) }
        assertTrue(after.layer(1)!!.keyframes(CameraFov).isEmpty())
        assertEquals(60f, after.layer(1)!!.cameraFov, 0f)
        undoOnce(before, after)
    }

    @Test fun effectRotationResetClearsAllAxesEvenAtTheirDefaults() {
        val axes = listOf("rotX", "rotY", "rotZ").map { EffectParam(it, it, "float", "0") }
        val effect = XmlNode("effect", mapOf("id" to "test"), axes.map {
            animated("property", "0", "0").copy(attrs = mapOf("name" to it.id, "type" to "float"))
        })
        val before = sceneOf(shapeNode(end = 1000, children = listOf(effect)))
        val after = before.update(1, 500) { Turn(0, axes).reset(it) }
        for (axis in axes) {
            val part = after.layer(1)!!.element(PropRef.effect(0, axis.id))!!
            assertTrue(part.keyframes.isEmpty())
            assertEquals(0f, part["value"]!!.toFloat(), 0f)
        }
        undoOnce(before, after)
    }
}
