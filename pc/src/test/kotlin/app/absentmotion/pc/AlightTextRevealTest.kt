package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AlightTextRevealTest {
    private fun brightness(effect: String): Float {
        val scene = Scene.parse(
            """<scene width="200" height="200" fps="30" totalTime="1000" bgcolor="#ff000000" alightImport="true">
            <text id="1" startTime="0" endTime="1000" fillType="color" size="80" wrapWidth="200" align="center"><transform><location value="100,100,0"/></transform>
            <fillColor value="#ffffffff"/>$effect<content>I</content></text></scene>"""
        )
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(scene, null)
            val image = engine.render(0, 200, 200)
            return (0 until 200).maxOf { x -> (0 until 200).maxOf { y -> image.getRGB(x, y) and 0xff } } / 255f
        }
    }

    private fun reveal(easeIn: Float, easeOut: Float, style: Int?) = """<effect id="absent.texttransform">
        <property name="version" type="float" value="1"/><property name="start" type="float" value="0.25"/><property name="overlap" type="float" value="10"/>
        <property name="alpha" type="float" value="-1"/><property name="easeIn" type="float" value="$easeIn"/><property name="easeOut" type="float" value="$easeOut"/>""" +
        (style?.let { """<property name="style" type="float" value="$it"/>""" } ?: "") + "</effect>"

    @Test fun halfRevealedCharactersFollowAlightsEaseCurves() {
        assertEquals(1f, brightness(""), 0.02f)
        assertEquals(0.67f, brightness(reveal(0f, 0f, 1)), 0.04f)
        assertEquals(0.25f, brightness(reveal(-1f, 1f, 1)), 0.04f)
        assertEquals(0.90f, brightness(reveal(1f, -1f, 1)), 0.04f)
    }

    @Test fun savedProjectsKeepTheLinearReveal() {
        assertEquals(0.25f, brightness(reveal(0f, 0f, null)), 0.04f)
    }
}
