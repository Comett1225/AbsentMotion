package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class TileEffectTest {
    private val root = File(System.getProperty("am.repo"))

    private fun picture(white: Boolean): File {
        val image = BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 90) for (x in 0 until 120) image.setRGB(x, y, if (white) Color.WHITE.rgb else Color(130 + x, 130 + y, if (x < 60) 250 else 140).rgb)
        return File.createTempFile("tile", ".png").also { ImageIO.write(image, "png", it); it.deleteOnExit() }
    }

    private fun uri(file: File) = file.toURI().toString().replaceFirst("file:/", "file:///")

    private fun tile(scale: Float, mirror: Boolean) =
        """<effect id="absent.tile" hidden="false"><property name="scale" type="float" value="$scale"/><property name="mirror" type="bool" value="$mirror"/></effect>"""

    private fun layer(file: File, transform: String, effects: String, half: String = "150,150") =
        """<shape id="1" startTime="0" endTime="1000" fillType="media" mediaFillMode="stretch" s=".rect" fillImage="${uri(file)}">
        <transform>$transform</transform><property name="size" type="vec2" value="$half"/>$effects</shape>"""

    private fun render(file: File, body: String): BufferedImage = DesktopEngine(root.resolve("shared/assets"), root).use { engine ->
        engine.setScene(Scene.parse("""<scene width="300" height="300" fps="30" totalTime="1000" bgcolor="#ff000000"><media uri="${uri(file)}" type="image/png" width="120" height="90"/>$body</scene>"""), null)
        engine.render(0, 300, 300)
    }

    private fun count(image: BufferedImage, from: Int, to: Int, test: (Int) -> Boolean) = (from until to).sumOf { y -> (from until to).count { x -> test(image.getRGB(x, y)) } }

    private fun empty(p: Int) = maxOf(p shr 16 and 255, p shr 8 and 255, p and 255) < 110

    @Test fun aTurnedTiledLayerStillCoversTheWholeFrame() {
        val pic = picture(false)
        for (turn in listOf(4.7f, 30f)) {
            val image = render(pic, layer(pic, """<location value="150,150,0"/><rotation value="$turn"/>""", tile(0.26f, true)))
            assertEquals("turned $turn", 0, count(image, 0, 300, ::empty))
        }
    }

    @Test fun aCroppedLayerRepeatsOnlyItsCroppedPicture() {
        val pic = picture(false)
        val crop = """<effect id="absent.crop" hidden="false"><property name="left" type="float" value="0.3"/><property name="right" type="float" value="0.25"/><property name="top" type="float" value="0.1"/></effect>"""
        for (turn in listOf(0f, 30f)) {
            val image = render(pic, layer(pic, """<location value="150,150,0"/><rotation value="$turn"/>""", crop + tile(0.26f, true)))
            assertEquals("turned $turn", 0, count(image, 0, 300, ::empty))
        }
    }

    @Test fun tilesMeetWithoutDarkLinesInAMaskedGroup() {
        val white = picture(true)
        val mask = """<shape id="2" startTime="0" endTime="1000" fillType="color" s=".rect" blending="mask"><transform><location value="150,150,0"/></transform><fillColor value="#ffffffff"/><property name="size" type="vec2" value="100,100"/></shape>"""
        val effects = listOf(tile(0.3f, false), tile(0.3f, true), """<effect id="absent.tiles" hidden="false"><property name="zoom" type="float" value="3"/><property name="mirror" type="bool" value="true"/></effect>""")
        for (effect in effects) {
            val inner = layer(white, """<location value="140.4,160.2,0"/><scale value="1.7,0.8"/>""", effect, "60,40")
            val image = render(white, """<embedScene id="10" label="Group" startTime="0" endTime="1000"><transform><location value="150,150,0"/></transform>
                <scene width="300" height="300" fps="30" totalTime="1000" bgcolor="#00000000">$inner$mask</scene></embedScene>""")
            assertEquals(effect, 0, count(image, 52, 248) { p -> minOf(p shr 16 and 255, p shr 8 and 255, p and 255) < 250 })
        }
    }

    @Test fun aGroupStillTilesFromItsOwnContent() {
        val shapes = listOf(75 to "#ffff0000", 125 to "#ff0000ff").joinToString("") { (x, colour) ->
            """<shape id="1$x" startTime="0" endTime="1000" fillType="color" s=".rect"><transform><location value="$x,100,0"/></transform><property name="size" type="vec2" value="25,50"/><fillColor value="$colour"/></shape>"""
        }
        val image = render(picture(false), """<embedScene id="10" label="Group" startTime="0" endTime="1000"><transform><location value="150,150,0"/></transform>
            <scene width="200" height="200" fps="30" totalTime="1000" bgcolor="#00000000">$shapes</scene>${tile(1f, false)}</embedScene>""")
        val seen = listOf(75, 125, 175, 225).map { x -> image.getRGB(x, 150).let { p -> if (p shr 16 and 255 > 200) "red" else if (p and 255 > 200) "blue" else "other" } }
        assertEquals(listOf("blue", "red", "blue", "red"), seen)
    }
}
