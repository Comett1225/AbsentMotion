package app.absentmotion.pc

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskGlobalKeys
import app.absentmotion.desk.DeskScale
import app.absentmotion.export.writeProjectPackage
import app.absentmotion.library.LinkChannel
import app.absentmotion.library.MediaAccess
import app.absentmotion.library.Offer
import app.absentmotion.library.Peer
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.greet
import app.absentmotion.library.linkConnect
import app.absentmotion.library.sendProject
import app.absentmotion.model.Scene
import app.absentmotion.ui.home.DeviceSend
import app.absentmotion.ui.home.SendView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeviceSendShotsTest {
    private val shots = File("build/ui-shots/device-send").also { it.mkdirs() }

    private fun ComposeUiTest.settle(ms: Long = 600) { mainClock.advanceTimeBy(ms); waitForIdle() }

    private fun ComposeUiTest.shot(name: String) {
        onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(3f, 3f)) }
        settle(900)
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    private fun ComposeUiTest.shown(text: String) = waitUntil(timeoutMillis = 15_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun phonePackage(root: File): File {
        val store = ProjectStore(root)
        val id = store.create(Scene.create("Phone styles", 1080, 1920, 30, 0xFF203040.toInt()))
        val scene = store.load(id)!!
        return File(root, "phone.absproj").also { file ->
            file.outputStream().use { out -> runBlocking { writeProjectPackage(scene, scene.toXml(), out, MediaAccess.forPhone(root), { null }) {} } }
        }
    }

    @Test fun toolPageReceivesFromAPhoneAndTheCardMenuSends() {
        val data = File("build/device-send-data").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", data.absolutePath)
        DeviceSend.serverAddress = InetAddress.getLoopbackAddress()
        DeviceSend.serverHosts = { listOf("127.0.0.1") }
        DeskScale.set(100)
        val phoneRoot = File("build/device-send-phone").also { it.deleteRecursively(); it.mkdirs() }
        val upload = phonePackage(phoneRoot)
        val seed = DeskAppState()
        seed.store.create(Scene.create("Styles", 1080, 1920, 30, 0xFF000000.toInt()))
        try {
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                val app = DeskAppState()
                val focus = FocusRequester()
                setContent { Box(Modifier.focusRequester(focus).focusable().onKeyEvent { DeskGlobalKeys.handle(app, it) }) { DeskApp(app) } }
                settle()
                onAllNodesWithText("Tools").onFirst().performClick()
                shown("Send between devices")
                shot("01-tools-card")
                onNodeWithText("Send between devices").performClick()
                settle()
                if (onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()) {
                    shot("02-first-time-explainer")
                    onNodeWithText("Continue").performClick()
                }
                waitUntil(timeoutMillis = 15_000) { DeviceSend.code != null }
                shown("Receive from phone")
                shot("03-tool-page")

                val code = DeviceSend.code!!
                val phone = CompletableFuture.supplyAsync {
                    runCatching {
                        runBlocking {
                            val link: LinkChannel = linkConnect(code)
                            greet(link, Peer("test-phone", "Phone", computer = false, sends = true), client = true)
                            sendProject(link, Offer("Phone styles", upload.length(), 0, "phone-1"), upload, {}, {}).also { link.close() }
                        }
                    }
                }
                waitUntil(timeoutMillis = 30_000) { DeviceSend.view == SendView.Done }
                shown("Received from Phone")
                shot("04-received")
                assertTrue(phone.get().isSuccess)
                assertTrue(app.store.list().any { it.title == "Phone styles" })
                onNodeWithText("Stay here").performClick()
                settle()
                onAllNodesWithContentDescription("Back").onFirst().performClick()
                settle()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Receive from phone").fetchSemanticsNodes().isEmpty() }
                assertEquals(null, DeviceSend.view)
                onAllNodesWithText("Projects").onFirst().performClick()
                shown("Styles")
                onAllNodesWithText("Styles").onFirst().performMouseInput { rightClick() }
                shown("Send to phone")
                shot("05-card-menu")
                onNodeWithText("Send to phone").performClick()
                waitUntil(timeoutMillis = 15_000) { DeviceSend.code != null }
                shown("Scan with AbsentMotion on the other device: Tools › Send between devices")
                shot("06-send-code")
                assertEquals(SendView.Code, DeviceSend.view)
                onAllNodesWithText("Cancel").onLast().performClick()
                settle()
                assertEquals(null, DeviceSend.view)
            }
        } finally {
            DeviceSend.cancel()
            DeviceSend.serverAddress = null
            DeviceSend.serverHosts = null
        }
    }
}
