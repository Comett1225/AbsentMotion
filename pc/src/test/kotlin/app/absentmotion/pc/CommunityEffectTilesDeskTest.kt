package app.absentmotion.pc

import android.content.Desk
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.engine.EngineThread
import app.absentmotion.library.CommunityFeed
import app.absentmotion.ui.home.CommunityTab
import app.absentmotion.ui.home.LocalCommunity
import app.absentmotion.ui.home.sessionAllowed
import app.absentmotion.ui.home.sessionIndex
import app.absentmotion.ui.home.sha256Of
import app.absentmotion.ui.theme.Am
import com.sun.net.httpserver.HttpServer
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class CommunityEffectTilesDeskTest {
    private fun effect(id: String, name: String, tint: String) = JSONObject().put("id", id).put("name", name).put("author", "Tester").put("category", "color")
        .put("params", JSONArray()).put("sksl", "uniform shader image; half4 main(float2 p) { return image.eval(p) * half4($tint, 1); }").toString()

    private fun pack(vararg effects: Pair<String, String>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> effects.forEach { (name, json) -> zip.putNextEntry(ZipEntry(name)); zip.write(json.toByteArray()); zip.closeEntry() } }
    }.toByteArray()

    private fun webp(colour: Int): ByteArray = Surface.makeRasterN32Premul(192, 192).run {
        canvas.clear(colour)
        requireNotNull(makeImageSnapshot().encodeToData(EncodedImageFormat.WEBP, 80)).bytes
    }

    @Test fun effectsIncludedShowEveryEffectAsATileWithItsThumbnailAndName() {
        val withPictures = pack("warm.json" to effect("warm", "Warm", "1, 0.7, 0.4"), "cool.json" to effect("cool", "Cool", "0.4, 0.7, 1"))
        val older = pack("plain.json" to effect("plain", "Plain", "1, 1, 1"))
        fun item(name: String, title: String, bytes: ByteArray, contents: JSONArray) = JSONObject().put("kind", "effect").put("name", name).put("title", title)
            .put("creator", "Tester").put("added", "2026-11-24T10:00:00Z").put("file", "effects/$name/$name.abmfx").put("bytes", bytes.size)
            .put("sha256", sha256Of(bytes.inputStream())).put("contents", contents)
        fun content(id: String, name: String, pictures: Boolean) = JSONObject().put("id", "tester.$id").put("name", name).put("version", 1).apply {
            if (pictures) put("thumbnail", "effects/tint-pack/thumbs/$id.webp").put("hero", "effects/tint-pack/thumbs/$id.hero.webp")
        }
        val index = JSONObject().put("format", "absentmotion-community").put("version", 2).put("items", JSONArray()
            .put(item("tint-pack", "Tint Pack", withPictures, JSONArray().put(content("warm", "Warm", true)).put(content("cool", "Cool", true))))
            .put(item("old-pack", "Old Pack", older, JSONArray().put(content("plain", "Plain", false))))).toString()
        val files = mapOf("index.json" to index.toByteArray(), "effects/tint-pack/tint-pack.abmfx" to withPictures, "effects/old-pack/old-pack.abmfx" to older,
            "effects/tint-pack/thumbs/warm.webp" to webp(0xffff8800.toInt()), "effects/tint-pack/thumbs/cool.webp" to webp(0xff0088ff.toInt()))
        val asked = Collections.synchronizedSet(mutableSetOf<String>())
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path.trimStart('/')
            asked += path
            val bytes = files[path]
            ex.sendResponseHeaders(if (bytes != null) 200 else 404, bytes?.size?.toLong() ?: -1L)
            ex.responseBody.use { if (bytes != null) it.write(bytes) }
        }
        server.start()
        val cache = Desk.context.cacheDir.resolve("community").also { it.deleteRecursively() }
        val before = Triple(CommunityFeed.source, sessionIndex, sessionAllowed)
        CommunityFeed.source = LocalCommunity("http://127.0.0.1:${server.address.port}/")
        sessionIndex = null
        sessionAllowed = true
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        val shots = File("build/ui-shots/community").also { it.mkdirs() }
        try {
            runSkikoComposeUiTest(size = Size(390f, 844f)) {
                setContent { CommunityTab(Modifier.fillMaxSize().background(Am.HomeBg), { engine }) {} }
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Tint Pack").fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("Tint Pack").onFirst().performClick()
                waitUntil(timeoutMillis = 30_000) { "effects/tint-pack/thumbs/cool.webp" in asked }
                onNodeWithText("Cool").performScrollTo()
                onNodeWithText("Warm").assertIsDisplayed()
                onNodeWithText("Cool").assertIsDisplayed()
                repeat(10) { mainClock.advanceTimeBy(200); Thread.sleep(50) }
                ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("effect-tiles.png"))
            }
            assertEquals(setOf("index.json", "effects/tint-pack/thumbs/warm.webp", "effects/tint-pack/thumbs/cool.webp"),
                asked.filterNot { it.endsWith(".abmfx") || it.endsWith(".hero.webp") || it.startsWith("effects/old-pack") }.toSet())
            runSkikoComposeUiTest(size = Size(390f, 844f)) {
                setContent { CommunityTab(Modifier.fillMaxSize().background(Am.HomeBg), { engine }) {} }
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Old Pack").fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("Old Pack").onFirst().performClick()
                waitUntil(timeoutMillis = 60_000) { cache.resolve("thumbs").listFiles().orEmpty().any { it.isDirectory && it.resolve("tester.plain.webp").isFile } }
                onNodeWithText("Plain").performScrollTo().assertIsDisplayed()
            }
        } finally {
            CommunityFeed.source = before.first
            sessionIndex = before.second
            sessionAllowed = before.third
            server.stop(0)
            engine.close()
        }
    }
}
