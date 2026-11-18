package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.editor.presetScene
import app.absentmotion.engine.EngineThread
import app.absentmotion.library.CommunityClient
import app.absentmotion.library.CommunityFeed
import app.absentmotion.library.CommunityKind
import app.absentmotion.model.Scene
import app.absentmotion.ui.home.LocalCommunity
import app.absentmotion.ui.home.TrustedHttp
import app.absentmotion.ui.home.sha256Of
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

class CommunityPresetPictureTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun preset(title: String, layer: String): String {
        val scene = Scene.parse("""<scene width="1080" height="1080" fps="30" totalTime="2999" bgcolor="#ff000000">$layer</scene>""")
        return presetScene(scene, scene.layers.single(), title).toXml()
    }

    private val redFade = preset(
        "Red Fade",
        """<shape id="1" startTime="0" endTime="2999" fillType="color" s=".rect"><transform><location value="540,540,0"/><opacity><kf t="0" v="0"/><kf t="0.75" v="1"/></opacity></transform><property name="size" type="vec2" value="400,400"/><fillColor value="#ffff0000"/></shape>"""
    )
    private val blueSlide = preset(
        "Blue Slide",
        """<shape id="2" startTime="1000" endTime="3999" fillType="color" s=".circle"><transform><location><kf t="0" v="240,540,0"/><kf t="1" v="840,540,0"/></location></transform><property name="size" type="vec2" value="300,300"/><fillColor value="#ff0040ff"/></shape>"""
    )

    private fun item(name: String, xml: ByteArray, thumbnail: String) =
        """{"kind":"preset","name":"$name","title":"$name","added":"2026-11-17","file":"presets/$name/$name.xml","bytes":${xml.size},"sha256":"${sha256Of(xml.inputStream())}","thumbnail":"$thumbnail"}"""

    private fun BufferedImage.pixel(x: Int, y: Int): IntArray {
        val argb = getRGB(x, y)
        return intArrayOf(argb ushr 24, argb shr 16 and 0xff, argb shr 8 and 0xff, argb and 0xff)
    }

    private fun BufferedImage.count(test: (IntArray) -> Boolean): Int =
        (0 until height).sumOf { y -> (0 until width).count { x -> test(pixel(x, y)) } }

    @Test fun presetPicturesAreDrawnFromThePresetAndIgnoreAThumbnailField() = runBlocking<Unit> {
        val red = redFade.toByteArray()
        val blue = blueSlide.toByteArray()
        val bogusHits = AtomicInteger()
        val index = """{"format":"absentmotion-community","version":2,"items":[${item("red-fade", red, "presets/red-fade/thumb.webp")},${item("blue-slide", blue, "../../outside.webp")}]}"""
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        fun serve(path: String, body: () -> ByteArray) = server.createContext(path) { ex ->
            val bytes = body()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        serve("/feed/index.json") { index.toByteArray() }
        serve("/feed/presets/red-fade/red-fade.xml") { red }
        serve("/feed/presets/blue-slide/blue-slide.xml") { blue }
        serve("/feed/presets/red-fade/thumb.webp") { bogusHits.incrementAndGet(); "not this picture".toByteArray() }
        server.start()
        val before = CommunityFeed.source
        CommunityFeed.source = LocalCommunity("http://127.0.0.1:${server.address.port}/feed/")
        val engine = EngineThread(0, Desk.context)
        try {
            val cache = tmp.newFolder("cache")
            val client = CommunityClient(cache) { TrustedHttp(CommunityFeed.source) }
            val items = client.index().items
            assertEquals(listOf("red-fade", "blue-slide"), items.map { it.name })
            assertTrue(items.all { it.kind == CommunityKind.Preset && it.thumbnail == null })
            assertNull(client.thumbnail(items[0]))

            val redPicture = checkNotNull(client.presetPicture(items[0], engine))
            val bluePicture = checkNotNull(client.presetPicture(items[1], engine))
            assertFalse(redPicture.contentEquals(bluePicture))
            assertEquals(0, bogusHits.get())

            val redImage = ImageIO.read(redPicture.inputStream())
            val blueImage = ImageIO.read(bluePicture.inputStream())
            assertEquals(512, redImage.width)
            assertEquals(512, redImage.height)
            val centre = redImage.pixel(256, 256)
            assertTrue("the most visible frame is picked: ${centre.toList()}", centre[0] > 240 && centre[1] > 200 && centre[3] < 60)
            assertEquals(0, redImage.pixel(4, 4)[0])
            assertTrue(redImage.count { it[0] > 200 && it[3] > 200 && it[1] < 60 } == 0)
            assertTrue(blueImage.count { it[0] > 200 && it[3] > 200 && it[1] < 60 } > 1000)
            assertTrue(blueImage.count { it[0] > 200 && it[1] > 200 && it[3] < 60 } == 0)

            val cached = File(cache, "community/thumbs/${items[0].sha256}.png")
            assertTrue(cached.isFile)
            server.stop(0)
            assertArrayEquals(redPicture, client.presetPicture(items[0], engine))
            ImageIO.write(redImage, "png", File("build/ui-shots/community").also { it.mkdirs() }.resolve("preset-red.png"))
            ImageIO.write(blueImage, "png", File("build/ui-shots/community").resolve("preset-blue.png"))
        } finally {
            CommunityFeed.source = before
            server.stop(0)
            engine.close()
        }
    }
}
