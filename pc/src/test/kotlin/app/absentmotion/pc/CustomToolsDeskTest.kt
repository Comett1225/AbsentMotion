package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.Judge
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskScale
import app.absentmotion.library.CommunityFeed
import app.absentmotion.library.CommunitySource
import app.absentmotion.model.Scene
import app.absentmotion.tools.InstalledTools
import app.absentmotion.ui.home.LocalCommunity
import app.absentmotion.ui.home.sessionIndex
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class CustomToolsDeskTest {
    private val video = File("../testdata/media/test-video.mp4").canonicalFile
    private val shots = File("build/ui-shots/custom-tools").also { it.mkdirs() }
    private val repo = File("build/custom-tools-repo")

    private val soundBite = """{"id":"sound-bite","name":"Sound bite","version":"1.0.0","api":1,"minVersion":52,"creator":"AbsentMotion","licence":"CC BY 4.0",
        "description":"Cuts a short part out of a video and keeps only its sound, as M4A or MP3.","icon":"icon.png","runs":"recipe","where":["home","editor"],
        "permissions":["media.read","media.write"],
        "inputs":[{"id":"video","type":"video","label":"Video"},{"id":"start","type":"number","label":"Start at","min":0,"max":3600,"step":0.5,"default":0,"unit":"s"},
          {"id":"length","type":"number","label":"Length","min":1,"max":120,"step":1,"default":10,"unit":"s"},
          {"id":"format","type":"choice","label":"Format","options":[{"label":"M4A","value":"m4a"},{"label":"MP3","value":"mp3"}],"default":1}],
        "steps":[{"op":"trim","in":"video","start":"${'$'}start","length":"${'$'}length","out":"part"},{"op":"extractAudio","in":"part","format":"${'$'}format","out":"sound"}]}"""

    private val titleCard = """{"id":"title-card","name":"Title card","version":"1.0.0","api":1,"minVersion":52,"creator":"AbsentMotion","licence":"CC BY 4.0",
        "description":"Adds a title at the playhead that fades in, stays, and fades out, with a soft shadow.","icon":"icon.png","runs":"recipe","where":["editor"],
        "permissions":["project.write"],
        "inputs":[{"id":"title","type":"text","label":"Title","default":"My title","maxLength":80},{"id":"colour","type":"colour","label":"Colour","default":"#ffffff"},
          {"id":"length","type":"number","label":"Length","min":1,"max":20,"step":0.5,"default":3,"unit":"s"},
          {"id":"size","type":"number","label":"Size","min":50,"max":500,"step":10,"default":300,"unit":"%"}],
        "steps":[{"op":"addText","text":"${'$'}title","colour":"${'$'}colour","length":"${'$'}length","out":"card"},
          {"op":"setProperty","layer":"card","property":"scale","value":"${'$'}size"},
          {"op":"addKeyframe","layer":"card","property":"opacity","at":0,"value":0},{"op":"addKeyframe","layer":"card","property":"opacity","at":0.4,"value":100},
          {"op":"addKeyframe","layer":"card","property":"opacity","at":-0.4,"value":100},{"op":"addKeyframe","layer":"card","property":"opacity","at":-0.001,"value":0},
          {"op":"applyEffect","layer":"card","effect":"absent.dropshadow","values":{"opacity":0.5,"blur":16,"x":0,"y":8}}]}"""

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun icon(colour: Color): ByteArray {
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply { color = colour; fillRect(0, 0, 128, 128); color = Color.WHITE; fillOval(32, 32, 64, 64); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun publish(): HttpServer {
        repo.deleteRecursively()
        val items = JSONArray()
        for ((json, colour) in listOf(soundBite to Color(52, 199, 140), titleCard to Color(92, 120, 255))) {
            val tool = JSONObject(json)
            val id = tool.getString("id")
            val folder = File(repo, "tools/$id").apply { mkdirs() }
            val bytes = json.toByteArray()
            val png = icon(colour)
            File(folder, "tool.json").writeBytes(bytes)
            File(folder, "icon.png").writeBytes(png)
            items.put(JSONObject().put("kind", "tool").put("name", id).put("title", tool.getString("name")).put("creator", tool.getString("creator"))
                .put("description", tool.getString("description")).put("tags", JSONArray()).put("added", "2026-11-16")
                .put("file", "tools/$id/tool.json").put("bytes", bytes.size).put("sha256", sha(bytes))
                .put("thumbnail", "tools/$id/icon.png").put("thumbnailBytes", png.size).put("thumbnailSha256", sha(png))
                .put("version", tool.getString("version")).put("api", 1).put("minVersion", 52)
                .put("permissions", tool.getJSONArray("permissions")).put("where", tool.getJSONArray("where")))
        }
        File(repo, "index.json").writeText(JSONObject().put("format", "absentmotion-community").put("version", 2).put("items", items).toString())
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            val file = File(repo, ex.requestURI.path.trimStart('/'))
            if (!file.canonicalPath.startsWith(repo.canonicalPath) || !file.isFile) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            val body = file.readBytes()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        CommunityFeed.source = LocalCommunity("http://127.0.0.1:${server.address.port}/")
        sessionIndex = null
        return server
    }

    private fun prepare(name: String) {
        System.setProperty("am.data", File("build/custom-tools-$name-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("user.home", File("build/custom-tools-$name-home").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("am.exports", File("build/custom-tools-$name-exports").also { it.deleteRecursively() }.absolutePath)
        DeskScale.set(100)
    }

    @org.junit.After fun reset() {
        System.clearProperty("am.pick")
        CommunityFeed.source = CommunitySource
        sessionIndex = null
    }

    private fun ComposeUiTest.settle(ms: Int = 400) {
        repeat(ms / 100) { mainClock.advanceTimeBy(100); Thread.sleep(40) }
    }

    private fun ComposeUiTest.until(seconds: Int = 60, check: () -> Boolean) = waitUntil(timeoutMillis = seconds * 1000L) { settle(200); check() }

    private fun ComposeUiTest.has(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.shot(name: String) {
        settle(600)
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    private fun ComposeUiTest.add(name: String) {
        onNodeWithText(name).performClick()
        until { has("This tool can:") }
        shot("sheet-" + name.lowercase().replace(' ', '-'))
        onNodeWithText("Add tool").performClick()
        until { InstalledTools.list(android.content.Desk.context).any { it.tool.name == name } }
        settle(800)
    }

    @Test fun aCommunityToolIsAddedRunFromHomeAndTheEditorThenRemoved() {
        prepare("round")
        val server = publish()
        try {
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                val app = DeskAppState()
                val context = android.content.Desk.context
                setContent { DeskApp(app) }
                settle(800)
                onNodeWithText("Community").performClick()
                settle(800)
                onAllNodesWithText("Tools").filter(hasClickAction()).onLast().performClick()
                until { has("Sound bite") && has("Title card") }
                shot("community-tools")
                add("Sound bite")
                add("Title card")
                assertEquals(listOf("sound-bite", "title-card"), InstalledTools.list(context).map { it.tool.id })

                onAllNodesWithText("Tools").filter(hasClickAction()).onFirst().performClick()
                until { has("Sound bite") }
                assertFalse("an editor-only tool stays out of Home > Tools", has("Title card"))
                shot("home-tools")
                onNodeWithText("Sound bite").performClick()
                until { has("Start at") }
                System.setProperty("am.pick", video.path)
                onNodeWithText("Choose a video").performClick()
                until { has("test-video.mp4") }
                shot("sound-bite-form")
                onAllNodesWithText("Run").filter(hasClickAction()).onLast().performClick()
                until(120) { has("Done ·") }
                shot("sound-bite-result")
                val made = File(context.getExternalFilesDir(null), "tool-runs/sound-bite").listFiles().orEmpty().toList()
                assertEquals(1, made.size)
                assertTrue(made.single().name.endsWith(".mp3"))
                val probe = ProcessBuilder(Judge.ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", made.single().path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val facts = JSONObject(probe.inputStream.bufferedReader().readText())
                assertEquals("mp3", facts.getJSONArray("streams").getJSONObject(0).getString("codec_name"))
                assertEquals(5.0, facts.getJSONObject("format").getString("duration").toDouble(), 0.3)
                runOnIdle { DeskBack.back() }
                settle(800)

                val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
                runBlocking { app.openNow(id) }
                waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
                val editor = app.editor!!
                val layers = editor.scene.layers.size
                settle(800)
                onAllNodesWithText("Tools").filter(hasClickAction()).onFirst().performClick()
                until { has("Title card") && has("Sound bite") }
                shot("editor-tools")
                onNodeWithText("Sound bite").performClick()
                until { has("Start at") }
                onNodeWithText("Choose a video").performClick()
                until { has("test-video.mp4") }
                onAllNodesWithText("Run").filter(hasClickAction()).onLast().performClick()
                until(120) { has("Add to project") }
                shot("sound-bite-editor-result")
                onAllNodesWithText("Add to project").filter(hasClickAction()).onLast().performClick()
                until { editor.scene.layers.size == layers + 1 }
                assertEquals("audio", editor.scene.layers.last().kind)
                settle(800)

                if (!has("Title card")) onAllNodesWithText("Tools").filter(hasClickAction()).onFirst().performClick()
                until { has("Title card") }
                onNodeWithText("Title card").performClick()
                until { has("Size") }
                shot("title-card-form")
                onAllNodesWithText("Run").filter(hasClickAction()).onLast().performClick()
                until { editor.scene.layers.size == layers + 2 }
                settle(800)
                shot("title-card-result")
                val card = editor.scene.layers.single { it.kind == "text" && it.label == "My title" }
                assertEquals("absent.dropshadow", card.node.children.single { it.name == "effect" }["id"])
                assertTrue(card.node.child("transform")!!.child("opacity")!!.children.count { it.name == "kf" } == 4)
                runOnIdle { editor.undo() }
                settle(400)
                assertEquals(layers + 1, editor.scene.layers.size)
                assertTrue(editor.scene.layers.none { it.label == "My title" })
                runBlocking { app.closeEditor() }
                settle(800)

                onAllNodesWithText("Tools").filter(hasClickAction()).onFirst().performClick()
                until { has("Sound bite") }
                onNodeWithText("Sound bite").performClick()
                until { has("Start at") }
                onNodeWithText("Remove").performScrollTo().performClick()
                until { InstalledTools.list(context).none { it.tool.id == "sound-bite" } }
                settle(800)
                assertFalse(has("Sound bite"))
                onNodeWithText("Community").performClick()
                settle(800)
                onAllNodesWithText("Tools").filter(hasClickAction()).onLast().performClick()
                until { has("Title card") }
                onNodeWithText("Title card").performClick()
                until { has("This tool can:") }
                shot("sheet-installed")
                onNodeWithText("Remove").performClick()
                until { InstalledTools.list(context).isEmpty() }
                app.engine.close()
            }
        } finally {
            server.stop(0)
        }
    }
}
