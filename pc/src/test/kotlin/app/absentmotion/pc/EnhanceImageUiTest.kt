package app.absentmotion.pc

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.absentmotion.ui.editor.effects.CompareImages
import app.absentmotion.ui.editor.effects.EnhanceCompare
import app.absentmotion.ui.home.EnhanceImageScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class EnhanceImageUiTest {
    private val shots = File(System.getenv("ENH_SHOTS") ?: "build/ui-shots/enhance").also { it.mkdirs() }

    @Test fun compareAndToolRender() {
        val input = File.createTempFile("enhance-ui-", ".png")
        val previousData = System.getProperty("am.data")
        val data = File("build/enhance-ui-data").absoluteFile.also { it.mkdirs() }
        System.setProperty("am.data", data.path)
        try {
            val image = BufferedImage(160, 100, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, (x * 255 / image.width shl 16) or (y * 255 / image.height shl 8) or 80)
            ImageIO.write(image, "png", input)
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                setContent { Box(Modifier.fillMaxSize().background(Color.DarkGray).padding(32.dp), Alignment.Center) {
                    val uri = android.net.Uri.fromFile(input).toString()
                    EnhanceCompare(CompareImages(uri, uri))
                } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Before").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                onNodeWithText("Before").assertExists()
                onNodeWithText("After").assertExists()
                ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("compare.png"))
            }
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                setContent { EnhanceImageScreen {} }
                waitForIdle()
                onNodeWithText("Enhance Image").assertExists()
                ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", shots.resolve("enhance-image.png"))
            }
        } finally {
            input.delete()
            if (previousData == null) System.clearProperty("am.data") else System.setProperty("am.data", previousData)
            data.deleteRecursively()
        }
    }

    private fun gradient(file: File, blue: Int) {
        val image = BufferedImage(160, 100, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, (x * 255 / 159 shl 16) or (y * 255 / 99 shl 8) or blue)
        ImageIO.write(image, "png", file)
    }

    private fun Int.red() = this shr 16 and 255
    private fun Int.green() = this shr 8 and 255
    private fun Int.blue() = this and 255

    private fun assertSamePlace(expected: Int, actual: Int) {
        assertTrue("red ${expected.red()} vs ${actual.red()}", kotlin.math.abs(expected.red() - actual.red()) <= 10)
        assertTrue("green ${expected.green()} vs ${actual.green()}", kotlin.math.abs(expected.green() - actual.green()) <= 10)
    }

    private val zoomLabel = hasText("×", substring = true)

    @Test fun compareZoomsAboutThePointerPansAndKeepsTheSlider() {
        val before = File.createTempFile("compare-before-", ".png")
        val after = File.createTempFile("compare-after-", ".png")
        try {
            gradient(before, 0)
            gradient(after, 255)
            val page = ScrollState(0)
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                setContent {
                    Column(Modifier.fillMaxSize().background(Color.DarkGray).verticalScroll(page), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(100.dp))
                        EnhanceCompare(CompareImages(android.net.Uri.fromFile(before).toString(), android.net.Uri.fromFile(after).toString()), Modifier.width(800.dp).testTag("compare"))
                        Spacer(Modifier.height(2000.dp))
                    }
                }
                val compare = onNodeWithTag("compare")
                fun pixel(x: Float, y: Float): Int {
                    waitForIdle()
                    return compare.captureToImage().toAwtImage().getRGB(x.toInt(), y.toInt())
                }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("compare").fetchSemanticsNodes().isNotEmpty() && pixel(100f, 420f).blue() < 60 && pixel(700f, 420f).blue() > 200
                }
                onAllNodes(zoomLabel).assertCountEquals(0)
                val focus = Offset(600f, 150f)
                val still = pixel(focus.x, focus.y)
                val corner = pixel(50f, 450f)

                compare.performMouseInput { moveTo(focus); scroll(-2f); scroll(-2f) }
                onAllNodes(zoomLabel).assertCountEquals(1)
                assertSamePlace(still, pixel(focus.x, focus.y))
                assertTrue(kotlin.math.abs(corner.red() - pixel(50f, 450f).red()) > 50)
                assertEquals(0, page.value)
                assertTrue(pixel(370f, 420f).blue() < 60)
                assertTrue(pixel(430f, 420f).blue() > 200)

                compare.performMouseInput { moveTo(Offset(600f, 400f)); press(); moveBy(Offset(-30f, 0f)); moveBy(Offset(-70f, 0f)); release() }
                assertSamePlace(still, pixel(focus.x - 100f, focus.y))
                assertTrue(pixel(370f, 420f).blue() < 60)
                assertTrue(pixel(430f, 420f).blue() > 200)
                assertEquals(0, page.value)

                compare.performMouseInput { moveTo(Offset(405f, 420f)); press(); moveBy(Offset(40f, 0f)); moveBy(Offset(255f, 0f)); release() }
                assertTrue(pixel(670f, 420f).blue() < 60)
                assertTrue(pixel(730f, 420f).blue() > 200)
                assertSamePlace(still, pixel(focus.x - 100f, focus.y))

                compare.performMouseInput { doubleClick(Offset(200f, 300f)) }
                onAllNodes(zoomLabel).assertCountEquals(0)
                assertSamePlace(still, pixel(focus.x, focus.y))
                assertTrue(pixel(670f, 420f).blue() < 60)

                compare.performMouseInput { click(Offset(200f, 300f)) }
                assertTrue(pixel(170f, 420f).blue() < 60)
                assertTrue(pixel(230f, 420f).blue() > 200)

                compare.performTouchInput { pinch(Offset(550f, 150f), Offset(450f, 150f), Offset(650f, 150f), Offset(750f, 150f), durationMillis = 400) }
                onAllNodes(zoomLabel).assertCountEquals(1)
                assertSamePlace(still, pixel(focus.x, focus.y))
                assertTrue(pixel(focus.x, focus.y).blue() > 200)
                assertTrue(kotlin.math.abs(corner.red() - pixel(50f, 450f).red()) > 50)
                assertTrue(pixel(170f, 420f).blue() < 60)
                assertTrue(pixel(230f, 420f).blue() > 200)

                compare.performMouseInput { moveTo(focus); repeat(40) { scroll(-3f) } }
                onNode(zoomLabel).assertTextEquals(app.absentmotion.ui.editor.effects.compareZoomLabel(8f)!!)
                compare.performMouseInput { repeat(40) { scroll(3f) } }
                onAllNodes(zoomLabel).assertCountEquals(0)
                assertSamePlace(still, pixel(focus.x, focus.y))
            }
        } finally {
            before.delete()
            after.delete()
        }
    }

    @Test fun compareZoomShots() {
        val after = File("../shared/assets/tutorial/picture.jpg")
        val before = File.createTempFile("compare-soft-", ".png")
        try {
            val sharp = ImageIO.read(after)
            val small = BufferedImage(sharp.width / 8, sharp.height / 8, BufferedImage.TYPE_INT_RGB)
            small.createGraphics().apply { setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); drawImage(sharp, 0, 0, small.width, small.height, null); dispose() }
            val soft = BufferedImage(sharp.width, sharp.height, BufferedImage.TYPE_INT_RGB)
            soft.createGraphics().apply { setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); drawImage(small, 0, 0, soft.width, soft.height, null); dispose() }
            ImageIO.write(soft, "png", before)
            val images = CompareImages(android.net.Uri.fromFile(before).toString(), android.net.Uri.fromFile(after.absoluteFile).toString())
            for ((name, size) in listOf("desktop" to Size(1280f, 800f), "phone" to Size(400f, 860f))) runSkikoComposeUiTest(size = size) {
                setContent {
                    Box(Modifier.fillMaxSize().background(Color(0xFF15151C)).padding(16.dp), Alignment.Center) {
                        EnhanceCompare(images, Modifier.widthIn(max = 500.dp).testTag("compare"))
                    }
                }
                val compare = onNodeWithTag("compare")
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("compare").fetchSemanticsNodes().isNotEmpty() }
                Thread.sleep(1000)
                waitForIdle()
                fun shot(file: String) {
                    waitForIdle()
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("zoom-$name-$file.png"))
                }
                val box = compare.fetchSemanticsNode().size
                val hill = Offset(box.width * 0.5f, box.height * 0.6f)
                shot("1-start")
                compare.performMouseInput { moveTo(hill); repeat(3) { scroll(-2f) } }
                shot("2-wheel")
                compare.performTouchInput { pinch(hill - Offset(30f, 0f), hill - Offset(54f, 0f), hill + Offset(30f, 0f), hill + Offset(54f, 0f), durationMillis = 300) }
                shot("3-pinch")
                compare.performMouseInput { moveTo(Offset(box.width * 0.8f, box.height * 0.3f)); press(); moveBy(Offset(-30f, 0f)); moveBy(Offset(-box.width * 0.2f, box.height * 0.2f)); release() }
                shot("4-pan")
                compare.performMouseInput { moveTo(Offset(box.width * 0.5f + 4f, box.height * 0.5f)); press(); moveBy(Offset(30f, 0f)); moveBy(Offset(box.width * 0.15f, 0f)); release() }
                shot("5-slider")
                compare.performMouseInput { doubleClick(Offset(box.width * 0.2f, box.height * 0.5f)) }
                shot("6-reset")
            }
        } finally {
            before.delete()
        }
    }
}
