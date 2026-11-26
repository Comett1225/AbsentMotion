package app.absentmotion.effects

import app.absentmotion.compat.EffectMigration
import app.absentmotion.compat.ForeignEffectIds
import app.absentmotion.editor.PivotEngine
import app.absentmotion.engine.TEXT_SPACING_EFFECT
import app.absentmotion.engine.animatesTextSpacing
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.effects
import app.absentmotion.model.mapTimedLayers
import app.absentmotion.model.textSpacing
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TextSpacingEffectTest {
    private val pack = File("../shared/assets/effects")
    private val catalog = EffectCatalog.parse { File(pack, it).inputStream() }
    private val migration = EffectMigration.parse(File(pack, "foreign.json").readText(), catalog)
    private val roboto = File("../shared/assets/fonts/Roboto/Roboto-Regular.ttf").canonicalPath
    private val keyed = """<property name="letterspacing" type="float"><kf t="0" v="-0.1"/><kf t="1" v="0.5"/></property>""" +
        """<property name="linespacing" type="float"><kf t="0" v="1"/><kf t="0.5" v="2"/></property>"""

    private fun scene(effects: String) = Scene.parse(
        """<scene width="1080" height="1080" fps="30" totalTime="1000"><text id="1" startTime="0" endTime="1000"><content>AB&#10;CD</content>$effects</text></scene>""",
    )

    private fun still(id: String, letters: Float, lines: Float, hidden: Boolean = false) =
        """<effect id="$id" hidden="$hidden"><property name="letterspacing" type="float" value="$letters"/><property name="linespacing" type="float" value="$lines"/></effect>"""

    private fun text(content: String, effects: String = "") =
        """<scene width="$W" height="$H" fps="30" totalTime="1000"><text id="1" startTime="0" endTime="1000" fillType="color" size="20" wrapWidth="${W - 100}" align="center" _fontPath="$roboto" _fontFamily="Roboto">""" +
            """<transform><location value="${W / 2},${H / 2},0"/></transform><fillColor value="#ffffffff"/><content>$content</content>$effects</text></scene>"""

    private fun prepared(xml: String, ms: Int) = Scene.parse(xml).let { scene ->
        scene.node.mapTimedLayers(scene.fps, ms.toDouble()) { raw, fps, time ->
            raw.at(time.toInt(), fps).let { it.node.with("_letterSpacing", "${it.textSpacing("letterspacing", 0f)}").with("_lineSpacing", "${it.textSpacing("linespacing", 1f)}") }
        }.toXml()
    }

    private fun drawn(xml: String, ms: Int = 0) = PivotEngine.nativeDrawn(xml, ms.toDouble())!!

    private fun ink(alpha: ByteArray): IntArray {
        var left = W; var top = H; var right = -1; var bottom = -1
        for (y in 0 until H) for (x in 0 until W) if (alpha[y * W + x].toInt() and 255 > 60) {
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
        }
        return intArrayOf(left, top, right, bottom)
    }

    private fun width(xml: String, ms: Int = 0) = ink(drawn(xml, ms)).let { it[2] - it[0] }

    private fun height(xml: String) = ink(drawn(xml)).let { it[3] - it[1] }

    private fun Layer.letters(ms: Int = 0) = at(ms, 30).textSpacing("letterspacing", 0f)

    private fun Layer.lines(ms: Int = 0) = at(ms, 30).textSpacing("linespacing", 1f)

    @Test fun itIsABuiltInTextEffectWithLetterAndLineSpacing() {
        val effect = catalog.find(TEXT_SPACING_EFFECT)!!
        assertEquals("text", effect.category)
        assertTrue(effect.native)
        assertNull(effect.sksl)
        assertEquals("5.3.2", effect.since)
        val (letters, lines) = effect.params
        assertEquals(listOf("letterspacing", "linespacing"), effect.params.map { it.id })
        assertTrue(letters.percent && letters.default.toFloat() == 0f && letters.min < 0f && letters.max > 1f)
        assertTrue(lines.percent && lines.default.toFloat() == 1f && lines.min == 0f && lines.max > 2f)
        assertTrue(TEXT_SPACING_EFFECT in catalog.listed("new").map { it.id })
        val plain = scene("").layers.single()
        val added = plain.copy(node = plain.node.copy(children = plain.node.children + effect.instance()))
        assertEquals(0f, added.letters(), 0f)
        assertEquals(1f, added.lines(), 0f)
    }

    @Test fun theBuiltInEffectAndAlightsBothSpaceTheTextButNotWhenHidden() {
        for (id in listOf(TEXT_SPACING_EFFECT, ForeignEffectIds.TEXT_SPACING)) {
            for (letters in listOf(0.3f, -0.2f)) {
                val layer = scene(still(id, letters, 1.5f)).layers.single()
                assertEquals(id, letters, layer.letters(), 1e-6f)
                assertEquals(id, 1.5f, layer.lines(), 1e-6f)
            }
            val hidden = scene(still(id, 0.3f, 1.5f, hidden = true)).layers.single()
            assertEquals(id, 0f, hidden.letters(), 0f)
            assertEquals(id, 1f, hidden.lines(), 0f)
        }
    }

    @Test fun keyframedSpacingChangesOverTimeAndLaysTheTextOutEveryFrame() {
        val moving = scene("""<effect id="$TEXT_SPACING_EFFECT">$keyed</effect>""")
        val layer = moving.layers.single()
        assertEquals(-0.1f, layer.letters(0), 1e-6f)
        assertEquals(0.2f, layer.letters(500), 1e-6f)
        assertEquals(0.5f, layer.letters(1000), 1e-6f)
        assertEquals(1.5f, layer.lines(250), 1e-6f)
        assertEquals(2f, layer.lines(750), 1e-6f)
        assertTrue(animatesTextSpacing(moving.toXml()))
        assertFalse(animatesTextSpacing(scene(still(TEXT_SPACING_EFFECT, 0.3f, 1.5f)).toXml()))
    }

    @Test fun alightTextSpacingImportsAsTheBuiltInEffectWithTheSameValues() {
        val alight = scene("""<effect id="${ForeignEffectIds.TEXT_SPACING}">$keyed</effect>""")
        val result = migration.migrate(alight)
        assertEquals(mapOf("textspacing → Text Spacing" to 1), result.converted)
        assertTrue(result.dropped.isEmpty())
        val before = alight.layers.single()
        val after = result.scene.layers.single()
        assertEquals(listOf(TEXT_SPACING_EFFECT), after.effects.map { it["id"] })
        for (ms in 0..1000 step 125) {
            assertEquals(before.letters(ms), after.letters(ms), 0f)
            assertEquals(before.lines(ms), after.lines(ms), 0f)
        }
        val half = scene("""<effect id="${ForeignEffectIds.TEXT_SPACING}" hidden="true"><property name="letterspacing" type="float" value="0.25"/></effect>""")
        val hidden = migration.migrate(half).scene.layers.single().effects.single()
        assertEquals(TEXT_SPACING_EFFECT to "true", hidden["id"] to hidden["hidden"])
        assertEquals(listOf("letterspacing"), hidden.children.map { it["name"] })
    }

    @Test fun theEngineSpacesTheLettersAndTheLinesAndFollowsTheKeyframes() {
        assumeTrue("build it with tools/pivot.sh --lib", PivotEngine.loaded)
        val plain = width(text("HELLO"))
        val wide = width(text("HELLO", still(TEXT_SPACING_EFFECT, 0.5f, 1f)))
        val tight = width(text("HELLO", still(TEXT_SPACING_EFFECT, -0.1f, 1f)))
        assertTrue("something is drawn: $plain", plain > 100)
        assertTrue("positive letter spacing makes the text wider: $plain, $wide", wide > plain + 100)
        assertTrue("negative letter spacing makes it tighter: $plain, $tight", tight < plain - 10)
        val two = height(text("HELLO&#10;WORLD"))
        val spread = height(text("HELLO&#10;WORLD", still(TEXT_SPACING_EFFECT, 0f, 2f)))
        assertTrue("line spacing above 100% makes two lines taller: $two, $spread", spread > two + 40)
        assertArrayEquals("a hidden effect changes nothing", drawn(text("HELLO")), drawn(text("HELLO", still(TEXT_SPACING_EFFECT, 0.5f, 2f, hidden = true))))
        assertArrayEquals("Alight's Text Spacing draws the same", drawn(text("HELLO", still(TEXT_SPACING_EFFECT, 0.5f, 1.5f))),
            drawn(text("HELLO", still(ForeignEffectIds.TEXT_SPACING, 0.5f, 1.5f))))
        val keyed = text("HELLO", """<effect id="$TEXT_SPACING_EFFECT"><property name="letterspacing" type="float"><kf t="0" v="0"/><kf t="1" v="0.5"/></property></effect>""")
        val widths = listOf(0, 500, 1000).map { width(prepared(keyed, it), it) }
        assertEquals("$widths", plain.toFloat(), widths[0].toFloat(), 2f)
        assertTrue("keyframed letter spacing widens the text over time: $widths", widths[1] > widths[0] + 40 && widths[2] > widths[1] + 40)
        assertEquals("$widths", wide.toFloat(), widths[2].toFloat(), 2f)
    }

    private companion object {
        const val W = 1200
        const val H = 600
    }
}
