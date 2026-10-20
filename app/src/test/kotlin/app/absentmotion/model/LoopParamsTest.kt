package app.absentmotion.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LoopParamsTest {
    private fun params(id: String): Set<String> {
        val json = JSONObject(File("src/main/assets/effects/${id.removePrefix("absent.")}.json").readText())
        val list = json.getJSONArray("params")
        return List(list.length()) { list.getJSONObject(it).getString("id") }.toSet()
    }

    @Test fun everyLoopWritesOnlySettingsItsEffectHas() {
        for ((id, _) in Animations.Loops) {
            val fx = Animations.loop(id, 2000, 1080f) ?: continue
            val known = params(fx.id)
            for (name in fx.fixed.keys + fx.keyed.keys) assertTrue("$id writes $name, which ${fx.id} does not have", name in known)
        }
    }

    @Test fun rotateTurnsOncePerCycle() {
        assertEquals(30f, Animations.loop("rotate", 2000, 1080f)!!.fixed.getValue("rpm").toFloat(), 0.001f)
    }
}
