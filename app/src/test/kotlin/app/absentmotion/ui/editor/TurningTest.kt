package app.absentmotion.ui.editor

import androidx.compose.ui.geometry.Offset
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.XmlNode
import app.absentmotion.model.addEffect
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.isKeyframed
import app.absentmotion.model.withEffect
import app.absentmotion.ui.editor.panels.Turn
import app.absentmotion.ui.editor.panels.TURN_PER_DP
import app.absentmotion.ui.editor.panels.turnFor
import app.absentmotion.ui.editor.panels.turnStep
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TurningTest {
    private val catalog = EffectCatalog.parse { File("src/main/assets/effects", it).inputStream() }

    private fun layer(kind: String = "shape") = Layer(XmlNode(kind, mapOf("id" to "1", "startTime" to "0", "endTime" to "1000")))

    private fun effect(id: String, hidden: Boolean = false) = catalog.find("absent.$id")!!.instance().let { if (hidden) it.with("hidden", "true") else it }

    @Test fun aTrackballDragTurnsTheFrontTheWayTheFingerGoes() {
        val right = turnStep(10f, 0f, 0f, 0f)
        assertArrayEquals(floatArrayOf(0f, 10f * TURN_PER_DP, -0f), right, 1e-5f)
        val down = turnStep(0f, 10f, 0f, 0f)
        assertArrayEquals(floatArrayOf(-10f * TURN_PER_DP, 0f, -0f), down, 1e-5f)
        assertEquals(-30f, turnStep(0f, 0f, 30f, 0f)[2], 0f)
    }

    @Test fun theDragFollowsTheScreenWhenTheObjectIsTurnedFlat() {
        val quarter = turnStep(10f, 0f, 0f, 90f)
        assertEquals(-10f * TURN_PER_DP, quarter[0], 1e-5f)
        assertEquals(0f, quarter[1], 1e-5f)
        val upright = turnStep(0f, -10f, 0f, 90f)
        assertEquals(0f, upright[0], 1e-5f)
        assertEquals(10f * TURN_PER_DP, upright[1], 1e-5f)
    }

    @Test fun onlyThreeDObjectsTurnOnTheRotateTabButEveryLayerOnTheTiltTab() {
        assertNull(turnFor(layer(), catalog, ROTATE_TAB))
        assertEquals(-1, turnFor(layer(), catalog, TILT_TAB)!!.effect)
        assertEquals(-1, turnFor(layer("model"), catalog, ROTATE_TAB)!!.effect)
        assertEquals(-1, turnFor(layer().withTilt(0f, 15f), catalog, ROTATE_TAB)!!.effect)
        assertNull(turnFor(layer(), catalog, 0))
        assertNull(turnFor(layer("model"), catalog, 1))
        assertNull(turnFor(layer("camera").addEffect(effect("cube")), catalog, ROTATE_TAB))
    }

    @Test fun theTopmostVisibleThreeDEffectIsTheOneThatTurns() {
        val blurred = layer().addEffect(effect("blur")).addEffect(effect("cube", hidden = true)).addEffect(effect("torus")).addEffect(effect("flip"))
        assertEquals(2, turnFor(blurred, catalog, ROTATE_TAB)!!.effect)
        assertEquals(2, turnFor(blurred, catalog, TILT_TAB)!!.effect)
        assertNull(turnFor(layer().addEffect(effect("blur")), catalog, ROTATE_TAB))
    }

    @Test fun turningAnEffectWritesItsRotationsAndLeavesTheTiltAlone() {
        val cube = layer().addEffect(effect("cube"))
        val turn = turnFor(cube, catalog, ROTATE_TAB)!!
        assertArrayEquals(floatArrayOf(-20f, -35f, 0f), turn.values(cube), 1e-4f)
        val turned = turn.turned(cube, Offset(20f, 0f), 0f)
        assertArrayEquals(floatArrayOf(-20f, -35f + 20f * TURN_PER_DP, 0f), turn.values(turned), 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 0f), turned.tilt, 0f)
        assertEquals(0f, turned.rotation, 0f)
        assertArrayEquals(floatArrayOf(-20f, -35f, 0f), turn.values(turn.reset(turned)), 1e-4f)
    }

    @Test fun turningAModelWritesTiltAndRotation() {
        val model = layer("model")
        val turn = turnFor(model, catalog, ROTATE_TAB)!!
        val turned = turn.turned(model, Offset(0f, 10f), 0f)
        assertArrayEquals(floatArrayOf(-10f * TURN_PER_DP, 0f), turned.tilt, 1e-4f)
        assertNull(turned.node.child("transform")?.child("rotation"))
        val twisted = turn.turned(turned, Offset.Zero, 45f)
        assertEquals(-45f, twisted.rotation, 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), turn.values(turn.reset(twisted)), 1e-4f)
    }

    @Test fun anUnchangedAxisGetsNoKeyframe() {
        val keyed = layer().addEffect(effect("cube")).withEffect(0) { node ->
            val rotX = node.effectProperty("rotX")!!
            node.copy(children = node.children.map { if (it === rotX) XmlNode("property", rotX.attrs - "value", listOf(XmlNode("kf", mapOf("t" to "0.000000", "v" to "-20.000000")))) else it })
        }.copy(t = 0.5f, snap = 0.01f)
        val turn = turnFor(keyed, catalog, ROTATE_TAB)!!
        val sideways = turn.turned(keyed, Offset(30f, 0f), 0f)
        assertEquals(1, sideways.effects[0].effectProperty("rotX")!!.children.size)
        assertFalse(sideways.effects[0].effectProperty("rotY")!!.isKeyframed)
        val typed = turn.withValues(keyed, floatArrayOf(-20f, -35f, 12f))
        assertEquals(1, typed.effects[0].effectProperty("rotX")!!.children.size)
        assertEquals("12.000000", typed.effects[0].effectProperty("rotZ")!!["value"])
    }

    @Test fun theKeyframeButtonFollowsTheChosenAxis() {
        val turn = turnFor(layer().addEffect(effect("cube")), catalog, ROTATE_TAB)!!
        assertEquals(PropRef.effect(0, "rotY"), turn.target(1).ref)
        val tilt = turnFor(layer("model"), catalog, ROTATE_TAB)!!
        assertEquals(PropRef.Tilt, tilt.target(0).ref)
        assertEquals(PropRef.Tilt, tilt.target(1).ref)
        assertEquals(PropRef.Rotation, tilt.target(2).ref)
        assertTrue(tilt.linked(0, 1))
        assertFalse(turn.linked(0, 1))
        assertNotNull(turn.target(2).default.toFloatOrNull())
    }
}
