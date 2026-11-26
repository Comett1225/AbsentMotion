package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.compat.ForeignEffectIds
import app.absentmotion.engine.EngineThread
import app.absentmotion.engine.TEXT_SPACING_EFFECT
import app.absentmotion.model.Scene
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSpacingEffectTest {
    private class Ink(val left: Int, val top: Int, val right: Int, val bottom: Int, val pixels: ByteArray) {
        val width get() = right - left
        val height get() = bottom - top
        override fun toString() = "$left,$top..$right,$bottom"
    }

    private fun property(name: String, value: String) =
        if ("<kf" in value) """<property name="$name" type="float">$value</property>""" else """<property name="$name" type="float" value="$value"/>"""

    private fun scene(content: String, letters: String = "0", lines: String = "1", id: String = TEXT_SPACING_EFFECT) = Scene.parse(
        """<scene title="t" width="$W" height="$H" fps="30" totalTime="2000" bgcolor="#ff000000"><text id="1" startTime="0" endTime="1000" fillType="color" size="20" """ +
            """font="googlefonts?name=Roboto&amp;weight=400" wrapWidth="${W - 100}" align="center"><transform><location value="${W / 2},${H / 2},0"/></transform>""" +
            """<fillColor value="#ffffffff"/><content>$content</content><effect id="$id" hidden="false">${property("letterspacing", letters)}${property("linespacing", lines)}</effect></text></scene>""",
    )

    private fun ink(pixels: ByteArray): Ink {
        var left = W; var top = H; var right = -1; var bottom = -1
        for (y in 0 until H) for (x in 0 until W) {
            val i = (y * W + x) * 4
            if ((0..2).any { pixels[i + it].toInt() and 255 > 60 }) { left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1) }
        }
        return Ink(left, top, right, bottom, pixels)
    }

    @Test fun spacingWidensTightensAndHeightensTheTextAndFollowsKeyframes() = runBlocking {
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        try {
            engine.effects.first { it.find(TEXT_SPACING_EFFECT) != null }
            suspend fun shot(scene: Scene, ms: Int = 0) = ink(checkNotNull(engine.exportSession(scene.toXml()) { still(ms, W, H) }))
            val plain = shot(scene("HELLO"))
            val wide = shot(scene("HELLO", letters = "0.5"))
            val tight = shot(scene("HELLO", letters = "-0.1"))
            assertTrue("something is drawn ($plain)", plain.width > 100)
            assertTrue("positive letter spacing widens the text (plain $plain, wide $wide)", wide.width > plain.width + 100)
            assertTrue("negative letter spacing tightens the text (plain $plain, tight $tight)", tight.width < plain.width - 10)
            val lines = shot(scene("HELLO&#10;WORLD"))
            val spread = shot(scene("HELLO&#10;WORLD", lines = "2"))
            assertTrue("line spacing above 100% makes two lines taller (plain $lines, spread $spread)", spread.height > lines.height + 40)
            assertEquals("line spacing leaves one line alone", plain.height, shot(scene("HELLO", lines = "2")).height)
            val keyed = scene("HELLO", letters = """<kf t="0" v="0"/><kf t="1" v="0.5"/>""")
            val start = shot(keyed, 0)
            val middle = shot(keyed, 500)
            val end = shot(keyed, 1000)
            assertEquals("keyframed spacing starts at 0% ($start)", plain.width.toFloat(), start.width.toFloat(), 2f)
            assertTrue("keyframed spacing grows over time ($start, $middle, $end)", middle.width > start.width + 40 && end.width > middle.width + 40)
            assertEquals("keyframed spacing ends at 50% ($end)", wide.width.toFloat(), end.width.toFloat(), 2f)
            assertArrayEquals("Alight's Text Spacing draws the same", wide.pixels, shot(scene("HELLO", letters = "0.5", id = ForeignEffectIds.TEXT_SPACING)).pixels)
        } finally {
            engine.close()
        }
    }

    companion object {
        private const val W = 1200
        private const val H = 600
    }
}
