package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class TilesWholeLayerTest {
    private fun picture(): File {
        val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 100) for (x in 0 until 100) image.setRGB(x, y, if (x < 50) Color.RED.rgb else Color.BLUE.rgb)
        return File.createTempFile("tiles", ".png").also { ImageIO.write(image, "png", it); it.deleteOnExit() }
    }

    private fun scene(file: File, x: Int, mirror: Int) = Scene.parse(
        """<scene width="200" height="200" fps="30" totalTime="1000" bgcolor="#ff000000"><shape id="1" startTime="0" endTime="1000" fillType="media" mediaFillMode="stretch" s=".rect" fillImage="${file.toURI().toString().replaceFirst("file:/", "file:///")}">
        <transform><location value="$x,100,0"/></transform><property name="size" type="vec2" value="50,50"/>
        <effect id="absent.tiles" hidden="false"><property name="zoom" type="float" value="1"/><property name="mirror" type="float" value="$mirror"/></effect></shape></scene>"""
    )

    private fun groupScene(x: Int) = Scene.parse(
        """<scene width="200" height="200" fps="30" totalTime="1000" bgcolor="#ff000000"><embedScene id="10" label="Group" startTime="0" endTime="1000">
        <transform><location value="100,100,0"/></transform>
        <scene width="200" height="200" fps="30" totalTime="1000" bgcolor="#00000000">
        <shape id="11" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="${x - 25},100,0"/></transform>
        <property name="size" type="vec2" value="25,50"/><fillColor value="#ffff0000"/></shape>
        <shape id="12" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="${x + 25},100,0"/></transform>
        <property name="size" type="vec2" value="25,50"/><fillColor value="#ff0000ff"/></shape></scene>
        <effect id="absent.tiles" hidden="false"><property name="zoom" type="float" value="1"/></effect></embedScene></scene>"""
    )

    private fun colourAt(x: Int, y: Int, image: BufferedImage): String {
        val p = image.getRGB(x, y)
        val r = p shr 16 and 255
        val b = p and 255
        return when { r > 200 && b < 60 -> "red"; b > 200 && r < 60 -> "blue"; r < 30 && b < 30 -> "black"; else -> "other" }
    }

    private fun render(x: Int, mirror: Int): List<String> {
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(scene(picture(), x, mirror), null)
            val image = engine.render(0, 200, 200)
            return listOf(25, 75, 125, 175).map { colourAt(it, 100, image) }
        }
    }

    @Test fun aLayerBesideTheCanvasStillFillsItWithTiles() {
        assertEquals(listOf("red", "blue", "red", "blue"), render(250, 0))
    }

    @Test fun aLayerPartlyOffTheCanvasKeepsItsHiddenHalfInTheTiles() {
        assertEquals(listOf("red", "blue", "red", "blue"), render(230, 0))
    }

    @Test fun mirroredTilesAlsoFillAnOffCanvasLayer() {
        assertEquals(listOf("red", "blue", "blue", "red"), render(250, 1))
    }

    @Test fun aLayerInsideTheCanvasIsUnchanged() {
        assertEquals(listOf("blue", "red", "blue", "red"), render(100, 0))
    }

    @Test fun aGroupBesideTheCanvasStillFillsItWithTiles() {
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(groupScene(250), null)
            val image = engine.render(0, 200, 200)
            assertEquals(listOf("red", "blue", "red", "blue"), listOf(25, 75, 125, 175).map { colourAt(it, 100, image) })
        }
    }

    @Test fun aGroupInsideTheCanvasTilesFromItsOwnContent() {
        val root = File("..").canonicalFile
        DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
            engine.setScene(groupScene(100), null)
            val image = engine.render(0, 200, 200)
            assertEquals(listOf("blue", "red", "blue", "red"), listOf(25, 75, 125, 175).map { colourAt(it, 100, image) })
        }
    }
}
