package app.absentmotion.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RepeatEffectsTest {
    private val catalog = EffectCatalog.parse { File("src/main/assets/effects", it).inputStream() }
    private val repeats = listOf("repeat", "linearrepeat", "radialrepeat", "gridrepeat", "repeatalongpath", "scatterfield")
    private val ranged = listOf("Range Position", "Range Scale", "Range Rotation", "Range Opacity", "Fill Color", "Fill Amount",
        "Range Start", "Range End", "Range Offset", "Easing", "Random Order", "Seed")

    private fun effect(key: String) = catalog.find("absent.$key")!!
    private fun param(key: String, id: String) = effect(key).params.first { it.id == id }
    private fun EffectParam.value(): Any = default.split(',').map { it.toFloatOrNull() ?: return default }.let { if (it.size == 1) it[0] else it }
    private fun EffectParam.shape() = listOf(kind, value(), min, max, percent)

    @Test fun theRepeatCategoryHoldsAlightsRepeatEffects() {
        val listed = catalog.listed("repeat").map { it.key }
        assertTrue(listed.containsAll(repeats))
        assertEquals(listOf("Repeat", "Linear Repeat", "Radial Repeat", "Grid Repeat", "Repeat Along Path", "Scatter Field"), repeats.map { effect(it).name })
        for (key in repeats) {
            assertTrue(key, effect(key).native && effect(key).sksl == null && effect(key).since == "3.0.0")
            assertFalse(key, catalog.isNew(effect(key)))
        }
    }

    @Test fun repeatMatchesTheRecordedParameters() {
        assertEquals(listOf("Count", "Time", "Offset", "Angle", "Scale", "Alpha"), effect("repeat").params.map { it.label })
        assertEquals(listOf("float", 10f, 0f, 9999f, false), param("repeat", "count").shape())
        assertEquals(listOf("float", 0f, 0f, 9999f, false), param("repeat", "time").shape())
        assertEquals(listOf("point", listOf(0f, 0f)), param("repeat", "offset").shape().take(2))
        assertEquals(listOf("angle", 0f, -3600f, 3600f, false), param("repeat", "angle").shape())
        assertEquals(listOf("float", 1f, 0f, 10f, false), param("repeat", "scale").shape())
        assertEquals(listOf("float", 1f, 0f, 1f, true), param("repeat", "alpha").shape())
        assertEquals(0.001f, param("repeat", "alpha").step)
    }

    @Test fun theRangedRepeatsMatchTheRecordedParameters() {
        assertEquals(listOf("Count", "Position") + ranged, effect("linearrepeat").params.map { it.label })
        assertEquals(listOf("float", 5f, 0f, 150f, false), param("linearrepeat", "count").shape())
        assertEquals(listOf(500f, 0f), param("linearrepeat", "position").value())
        assertEquals(listOf("Count", "Radius", "Orientation", "Angle", "Sweep", "Scale") + ranged, effect("radialrepeat").params.map { it.label })
        assertEquals(8f, param("radialrepeat", "count").value())
        assertEquals(listOf("float", 300f, 0f, 2000f, false), param("radialrepeat", "radius").shape())
        assertEquals(listOf(0f, 0f, 360f), listOf("orientation", "angle", "sweep").map { param("radialrepeat", it).value() })
        assertTrue(listOf("orientation", "angle", "sweep").all { param("radialrepeat", it).kind == "angle" })
        assertEquals(listOf("Count", "Position", "Stagger") + ranged, effect("gridrepeat").params.map { it.label })
        assertEquals(listOf(500f, 500f), param("gridrepeat", "position").value())
        assertEquals(listOf("float", 0f, -9999f, 9999f, false), param("gridrepeat", "stagger").shape())
        assertEquals(listOf("Count", "Start", "End", "Phase", "Tangent") + ranged, effect("repeatalongpath").params.map { it.label })
        assertEquals(listOf("float", 25f, 0f, 150f, false), param("repeatalongpath", "count").shape())
        assertEquals(listOf("float", 0f, 0f, 1f, true), param("repeatalongpath", "start").shape())
        assertEquals(listOf("float", 1f, 0f, 1f, true), param("repeatalongpath", "end").shape())
        assertEquals(listOf("float", 0f, -999f, 999f, true), param("repeatalongpath", "phase").shape())
        assertEquals(listOf("switch", "false"), param("repeatalongpath", "tangent").shape().take(2))
        for (key in listOf("linearrepeat", "radialrepeat", "gridrepeat", "repeatalongpath")) {
            assertEquals(listOf("Linear", "Ease In", "Ease Out", "Ease In Out"), param(key, "easing").options)
            assertEquals("false", param(key, "randomOrder").default)
            assertEquals(0f, param(key, "fillAmount").value())
            assertEquals(listOf(1f, 1f, 0f, 1f), listOf("rangeScale", "rangeOpacity", "rangeStart", "rangeEnd").map { param(key, it).value() })
        }
    }

    @Test fun scatterFieldMatchesTheRecordedParameters() {
        assertEquals(listOf("Position", "Position Z", "Density", "Evolution", "Seed", "Scale", "Size", "Angle", "Size Variance", "Angle Variance",
            "Amount", "Fade", "Alpha", "Slack", "Blending Mode", "Coloring", "Color 1", "Color 2", "Color 3", "Lock Luminance",
            "Lock Saturation", "Interpolate Hue"), effect("scatterfield").params.map { it.label })
        val defaults = effect("scatterfield").params.associate { it.id to it.value() }
        assertEquals(listOf(listOf(0f, 0f), 0.3f, 0f, 0f, 1f, 0.5f, 0f, 0.5f, 0f, 15f, 1f, 1f, 1f),
            listOf("position", "density", "evolution", "seed", "scale", "size", "angle", "sizeVariance", "angleVariance", "amount", "fade", "alpha", "slack").map { defaults[it] })
        assertEquals(listOf("#ffff55aa", "#ff55aaff", "#ffffaa55"), listOf("color1", "color2", "color3").map { defaults[it] })
        assertEquals(listOf("true", "false", "false"), listOf("lockLuminance", "lockSaturation", "interpolateHue").map { defaults[it] })
        assertEquals("Normal", param("scatterfield", "blending").options.first())
        assertEquals("None", param("scatterfield", "coloring").options.first())
        assertTrue(listOf("sizeVariance", "angleVariance", "fade", "alpha", "slack").all { param("scatterfield", it).percent })
        assertFalse(param("scatterfield", "density").percent)
    }
}
