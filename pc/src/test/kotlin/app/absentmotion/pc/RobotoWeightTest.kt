package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.engine.EngineThread
import app.absentmotion.engine.FONT_WEIGHT_EFFECT
import app.absentmotion.model.Scene
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotoWeightTest {
    private val w = 640
    private val h = 200

    private fun scene(weight: Int, effect: Int?) = Scene.parse(
        """<scene title="t" width="$w" height="$h" fps="30" totalTime="1000" bgcolor="#ff000000"><text id="1" startTime="0" endTime="1000" fillType="color" size="60" """ +
            """font="googlefonts?name=Roboto&amp;weight=$weight" wrapWidth="${w - 40}" align="center"><transform><location value="${w / 2},${h / 2},0"/></transform>""" +
            """<fillColor value="#ffffffff"/><content>Weight</content>""" +
            (effect?.let { """<effect id="$FONT_WEIGHT_EFFECT" hidden="false"><property name="weight" type="float" value="$it"/></effect>""" } ?: "") + "</text></scene>",
    )

    @Test fun fontWeightChangesTheDefaultRobotoText() = runBlocking {
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        try {
            engine.effects.first { it.find(FONT_WEIGHT_EFFECT) != null }
            suspend fun ink(weight: Int, effect: Int? = null): Long =
                checkNotNull(engine.exportSession(scene(weight, effect).toXml()) { still(0, w, h) }).let { p -> (p.indices step 4).sumOf { (p[it].toInt() and 255).toLong() } }
            val regular = ink(400)
            val bold = ink(700)
            val thin = ink(400, 100)
            val black = ink(400, 900)
            assertTrue("text is drawn ($regular)", regular > 0)
            assertEquals("Font Weight 400 keeps Regular", regular, ink(400, 400))
            assertTrue("Bold stays bold ($regular, $bold)", bold > regular * 1.2)
            assertTrue("Font Weight 100 thins Roboto ($thin, $regular)", thin < regular * 0.8)
            assertTrue("Font Weight 900 thickens Roboto ($black, $bold)", black > bold * 1.1)
            assertEquals("Font Weight 700 matches Bold", bold.toDouble(), ink(400, 700).toDouble(), bold * 0.01)
        } finally { engine.close() }
    }
}
