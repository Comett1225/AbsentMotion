package app.absentmotion.editor

import app.absentmotion.model.Easing
import app.absentmotion.model.PropRef
import app.absentmotion.model.SpeedRef
import org.junit.Assert.*
import org.junit.Test

class CurveCompatibilityTest {
    @Test fun speedCurvesRejectNonMonotonicFamilies() {
        for (type in listOf("bounce", "elastic", "back")) assertNull(compatibleCurve(SpeedRef, Easing(type)))
        assertNull(compatibleCurve(SpeedRef, null))
        assertEquals(Easing("linear"), compatibleCurve(SpeedRef, Easing("linear")))
    }

    @Test fun speedCurvePastesClampOvershootConsistently() {
        val curve = Easing("cubicBezier", listOf(0.25f, -0.5f, 0.75f, 1.5f))
        assertEquals(listOf(0.25f, 0f, 0.75f, 1f), compatibleCurve(SpeedRef, curve)!!.p)
        assertEquals(curve, compatibleCurve(PropRef.Location, curve))
    }
}
