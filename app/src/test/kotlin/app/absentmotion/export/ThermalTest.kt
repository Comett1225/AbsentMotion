package app.absentmotion.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThermalTest {
    @Test fun statusHeadroomAndBatteryPickTheLevel() {
        assertEquals(ThermalLevel.Normal, thermalLevel(0, Float.NaN, Float.NaN))
        assertEquals(ThermalLevel.Normal, thermalLevel(1, 0.9f, 35f))
        assertEquals(ThermalLevel.Warm, thermalLevel(2, Float.NaN, 30f))
        assertEquals(ThermalLevel.Warm, thermalLevel(0, 0.96f, 30f))
        assertEquals(ThermalLevel.Warm, thermalLevel(0, Float.NaN, 40.5f))
        assertEquals(ThermalLevel.Hot, thermalLevel(3, 0.5f, 30f))
        assertEquals(ThermalLevel.Hot, thermalLevel(0, 1.02f, 30f))
        assertEquals(ThermalLevel.Hot, thermalLevel(0, Float.NaN, 42.5f))
        assertEquals(ThermalLevel.Critical, thermalLevel(4, Float.NaN, 30f))
        assertEquals(ThermalLevel.Critical, thermalLevel(6, Float.NaN, 30f))
        assertEquals(ThermalLevel.Critical, thermalLevel(0, Float.NaN, 46f))
    }

    @Test fun hotterMeansLongerPausesAndAMessage() {
        val pauses = ThermalLevel.entries.map(::pauseMs)
        assertEquals(pauses.sorted(), pauses)
        assertEquals(0L, pauseMs(ThermalLevel.Normal))
        assertNull(ThermalState().message)
        ThermalLevel.entries.drop(1).forEach { assertNotNull(thermalMessage(it)) }
    }
}
