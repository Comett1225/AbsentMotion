package app.absentmotion

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class LegacyExifTest {
    @Test fun legacyTransposedPhotosUpdateTheDeclarationAndKeepLayerEdits() {
        for ((tag, rotation) in listOf(5 to 270f, 7 to 90f)) {
            val original = Scene.parse("""<scene width="100" height="100"><media uri="picture" type="image/jpeg" orientation="0"/>
                <embedScene id="1"><scene><shape id="2" fillImage="picture"><transform><rotation value="15"><kf t="0" v="30"/><kf t="1" v="45"/></rotation></transform></shape></scene></embedScene></scene>""")
            val updated = original.withLegacyExifOrientation("picture", tag)
            assertEquals(rotation.toInt(), updated.media("picture")!!.orientation)
            val value = updated.layers.single().nested!!.layers.single().node.child("transform")!!.child("rotation")!!
            assertEquals(15f + rotation, value["value"]!!.toFloat(), 0f)
            assertEquals(listOf(30f + rotation, 45f + rotation), value.children.map { it["v"]!!.toFloat() })
            assertEquals(updated, updated.withLegacyExifOrientation("picture", tag))
        }
    }

    @Test fun unrelatedOrientationsAndAlreadyMigratedPhotosStayUnchanged() {
        val scene = Scene.parse("""<scene><media uri="picture" type="image/jpeg" orientation="90"/></scene>""")
        assertEquals(scene, scene.withLegacyExifOrientation("picture", 5))
        assertEquals(scene, scene.withLegacyExifOrientation("picture", 1))
    }
}
