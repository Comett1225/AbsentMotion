package app.absentmotion.effects

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EffectControlsTest {
    private val catalog = EffectCatalog.parse { File("../shared/assets/effects", it).inputStream() }

    private fun param(json: String) = EffectParam.parse(JSONObject(json))

    @Test fun parametersReadSectionsSlidersAndDrag() {
        val seed = param("""{"id":"seed","type":"float","default":1,"min":0,"max":5,"section":"random"}""")
        assertEquals("random", seed.section)
        assertFalse(seed.header)
        assertTrue(param("""{"id":"on","type":"switch","default":false,"section":"random","header":true}""").header)
        assertFalse(param("""{"id":"on","type":"switch","default":false,"header":true}""").header)
        assertEquals(0f to 4f, param("""{"id":"t","type":"float","default":1,"min":0,"max":4,"slider":true}""").slider)
        assertEquals(0f to 500f, param("""{"id":"m","type":"float","default":1,"min":0,"max":4000,"slider":[0,500]}""").slider)
        assertNull(param("""{"id":"m","type":"float","default":1,"slider":true}""").slider)
        assertNull(param("""{"id":"m","type":"float","default":1,"slider":[5,1]}""").slider)
        assertNull(param("""{"id":"m","type":"float","default":1,"slider":["a"]}""").slider)
        assertEquals(0.1f, param("""{"id":"f","type":"float","default":1,"step":0.01,"drag":0.1}""").rulerStep, 0f)
        assertEquals(1f, param("""{"id":"a","type":"angle","default":0,"step":0.1}""").rulerStep, 0f)
        assertEquals(5f, param("""{"id":"a","type":"angle","default":0,"step":5}""").rulerStep, 0f)
        assertEquals(0.2f, param("""{"id":"p","type":"float","default":1,"step":1,"slow":5}""").rulerStep, 1e-6f)
    }

    @Test fun sectionRowsSitTogetherAndHeaderSwitchesBelongToASection() {
        assertTrue(catalog.definitions.size > 100)
        for (definition in catalog.definitions) {
            val sections = definition.params.mapNotNull { it.section }
            val runs = sections.zipWithNext().count { (a, b) -> a != b } + 1
            if (sections.isNotEmpty()) assertEquals("${definition.key} has a section split by other sections", sections.distinct().size, runs)
            definition.params.filter { it.header }.forEach { assertEquals("${definition.key}.${it.id}", "switch", it.kind) }
        }
        val text = catalog.find("absent.texttransform")!!
        assertEquals(listOf("range", "transform", "visual", "easing", "random"), text.params.mapNotNull { it.section }.distinct())
        assertTrue(text.params.single { it.id == "randomOrder" }.header)
    }

    @Test fun alightMeasuredSettingsUseAlightsPrecisionAndControls() {
        fun p(effect: String, id: String) = catalog.find("absent.$effect")!!.params.single { it.id == id }
        assertEquals(0.01f, p("exposure", "stops").rulerStep, 0f)
        assertEquals(0.001f, p("exposure", "gamma").step, 0f)
        assertEquals(0.001f, p("tiles", "offset").step, 0f)
        assertEquals(1f, p("oscillate", "angle").rulerStep, 0f)
        assertEquals(0.1f, p("oscillate", "frequency").rulerStep, 0f)
        assertEquals(0f to 500f, p("oscillate", "magnitude").slider)
        assertEquals(0f to 4f, p("motionblur", "tune").slider)
    }
}
