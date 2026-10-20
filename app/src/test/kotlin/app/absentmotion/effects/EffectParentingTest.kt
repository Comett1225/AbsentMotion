package app.absentmotion.effects

import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.applyingToChildren
import app.absentmotion.model.appliesToChildren
import app.absentmotion.model.effects
import app.absentmotion.model.hasChildren
import app.absentmotion.model.withEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectParentingTest {
    private val scene = Scene.parse("""<scene width="100" height="100" fps="30" totalTime="1000">
        <shape id="1" startTime="0" endTime="1000"><effect id="absent.invert" hidden="false"/></shape>
        <shape id="2" parent="1" startTime="0" endTime="1000"/>
        <shape id="3" parent="3" startTime="0" endTime="1000"/></scene>""")

    @Test fun onlyLayersWithChildrenOfferTheSwitch() {
        assertTrue(scene.hasChildren(scene.layer(1)!!))
        assertFalse(scene.hasChildren(scene.layer(2)!!))
        assertFalse(scene.hasChildren(scene.layer(3)!!))
    }

    @Test fun theSwitchIsSavedOnTheEffectAndCanBeTurnedOff() {
        val parent = scene.layer(1)!!
        assertFalse(parent.effects[0].appliesToChildren)
        val shared = parent.withEffect(0) { it.applyingToChildren(true) }
        val saved = XmlNode.parse(shared.node.toXml()).children.single { it.name == "effect" }
        assertEquals("true", saved["applyToChildren"])
        assertTrue(saved.appliesToChildren)
        assertNull(shared.withEffect(0) { it.applyingToChildren(false) }.effects[0]["applyToChildren"])
    }
}
