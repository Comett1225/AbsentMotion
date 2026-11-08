package app.absentmotion.sweepd

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.ShapeOutlines
import app.absentmotion.model.isShapeTemplate
import app.absentmotion.model.withOutlinesFollowing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepDOutlineTest {
    @Test fun aFinishedOutlineSurvivesSavingAndStaysAnEditableTemplate() {
        val before = ShapeOutlines.native
        try {
            ShapeOutlines.native = { _, _, _ -> "M0 0L10 0L10 10Z\nM2 2L3 2L3 3Z" }
            val empty = Scene.parse("""<scene title="T" width="100" height="100"></scene>""")
            val added = Scene.parse("""<scene title="T" width="100" height="100"><shape id="1" startTime="0" endTime="999" outlineShape="gear"><property name="teeth" type="float" value="12"/></shape></scene>""")
            val saved = added.withOutlinesFollowing(empty)
            val again = Scene.parse(saved.toXml())
            val layer = again.layers.single()
            assertTrue(layer.node.child("path") != null)
            assertEquals(saved.node.children.single().child("path")!!["d"], layer.node.child("path")!!["d"])
            assertTrue(Layer(layer.node).isShapeTemplate || !app.absentmotion.model.ShapeCatalog.isTemplate("gear"))
        } finally {
            ShapeOutlines.native = before
        }
    }
}
