package app.absentmotion.pc

import android.content.Desk
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.editor.presetScene
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.CommunityDetails
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.Exporter
import app.absentmotion.export.MediaStoreExport
import app.absentmotion.library.CommunityFeed
import app.absentmotion.model.Scene
import app.absentmotion.ui.home.CommunityTab
import app.absentmotion.ui.home.LocalCommunity
import app.absentmotion.ui.home.sessionAllowed
import app.absentmotion.ui.home.sessionIndex
import app.absentmotion.ui.theme.Am
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class CommunityFixtureDeskTest {
    private class Bundle(val file: String, val xml: String, val details: CommunityDetails)

    private val bundles = listOf(
        Bundle(
            "bold-title-pop",
            """<scene title="Bold Title Pop" width="1080" height="1080" fps="30" totalTime="2999" bgcolor="#ff14213d">
              <shape id="1" label="Bar" startTime="0" endTime="2999" fillType="color" s=".rect"><transform><location value="540,650,0"/><scale><kf t="0.1" v="0,1"/><kf t="0.35" v="1,1"/></scale></transform><property name="size" type="vec2" value="310,13"/><fillColor value="#fffca311"/></shape>
              <text id="2" label="Title" startTime="0" endTime="2999" fillType="color" size="64" font="googlefonts?name=Anton&amp;weight=400" wrapWidth="1000" align="center"><transform><location value="540,500,0"/><scale><kf t="0" v="0.4,0.4"/><kf t="0.2" v="1.12,1.12"/><kf t="0.3" v="1,1"/></scale><opacity><kf t="0" v="0"/><kf t="0.15" v="1"/></opacity></transform><fillColor value="#ffffffff"/><content>BOLD TITLE</content></text>
            </scene>""",
            CommunityDetails("Bold Title Pop", "A heavy white title that pops in, overshoots a little and settles above an orange bar. Change the word and keep the timing.",
                listOf("Intro", "Title", "Text"), "Mika", thumbMs = 1500)
        ),
        Bundle(
            "name-lower-third",
            """<scene title="Name Lower Third" width="1080" height="1920" fps="30" totalTime="2999" bgcolor="#ff22313f">
              <shape id="1" label="Bar" startTime="0" endTime="2999" fillType="color" s=".rect"><transform><location><kf t="0" v="-420,1500,0"/><kf t="0.25" v="500,1500,0"/></location></transform><property name="size" type="vec2" value="390,85"/><fillColor value="#fff8f9fa"/></shape>
              <shape id="2" label="Accent" startTime="0" endTime="2999" fillType="color" s=".rect"><transform><location><kf t="0.05" v="-420,1500,0"/><kf t="0.3" v="98,1500,0"/></location></transform><property name="size" type="vec2" value="13,85"/><fillColor value="#fffb8500"/></shape>
              <text id="3" label="Name" startTime="0" endTime="2999" fillType="color" size="34" font="googlefonts?name=Montserrat&amp;weight=700" wrapWidth="700" align="center"><transform><location value="510,1475,0"/><opacity><kf t="0.25" v="0"/><kf t="0.4" v="1"/></opacity></transform><fillColor value="#ff14213d"/><content>JORDAN LEE</content></text>
              <text id="4" label="Role" startTime="0" endTime="2999" fillType="color" size="18" font="googlefonts?name=Montserrat&amp;weight=400" wrapWidth="700" align="center"><transform><location value="510,1540,0"/><opacity><kf t="0.32" v="0"/><kf t="0.47" v="1"/></opacity></transform><fillColor value="#ff5c677d"/><content>Weekend Host</content></text>
            </scene>""",
            CommunityDetails("Name Lower Third", "A white name bar slides in from the left with an orange edge, then the name and role fade in. Made for vertical videos.",
                listOf("Lower third", "Text", "Clean"), "Sam", thumbMs = 2000, squareY = 0.78f)
        ),
        Bundle(
            "glitch-word",
            """<scene title="Glitch Word" width="1080" height="1080" fps="30" totalTime="1999" bgcolor="#ff0b0b0f">
              <text id="1" label="Word" startTime="0" endTime="1999" fillType="color" size="58" font="googlefonts?name=Orbitron&amp;weight=900" wrapWidth="1000" align="center"><transform><location value="540,540,0"/></transform><fillColor value="#fff1f1f1"/><content>GLITCH</content>
                <effect id="absent.glitch"><property name="intensity" type="float" value="0.9"/><property name="blockHeight" type="float" value="30"/><property name="seed" type="float" value="7"/></effect></text>
            </scene>""",
            CommunityDetails("Glitch Word", "One word that tears into sliding blocks with split colours a few times a second. Short enough to drop between two clips.",
                listOf("Glitch", "Text", "Edit"), "Rin", thumbMs = 700)
        ),
        Bundle(
            "neon-outline",
            """<scene title="Neon Outline" width="1080" height="1920" fps="30" totalTime="3999" bgcolor="#ff0d0221">
              <shape id="1" label="Frame" startTime="0" endTime="3999" fillType="none" s=".roundrect"><transform><location value="540,960,0"/><rotation><kf t="0" v="0"/><kf t="1" v="90"/></rotation></transform><property name="size" type="vec2" value="280,280"/><property name="cornerRadius" type="float" value="70"/>
                <path-stroke direction="centered"><color value="#ff2de2e6"/><size value="16"/></path-stroke>
                <effect id="absent.glow"><property name="radius" type="float" value="60"/><property name="alpha" type="float" value="0.9"/><property name="hardness" type="float" value="0.2"/><property name="color" type="color" value="#ff2de2e6"/></effect></shape>
              <shape id="2" label="Ring" startTime="0" endTime="3999" fillType="none" s=".circle"><transform><location value="540,960,0"/></transform><property name="size" type="vec2" value="130,130"/>
                <path-stroke direction="centered"><color value="#ffff3cac"/><size value="12"/></path-stroke>
                <effect id="absent.glow"><property name="radius" type="float" value="45"/><property name="alpha" type="float" value="0.9"/><property name="hardness" type="float" value="0.2"/><property name="color" type="color" value="#ffff3cac"/></effect></shape>
            </scene>""",
            CommunityDetails("Neon Outline", "A cyan rounded square turns slowly around a pink ring, both drawn as glowing outlines on a dark purple background.",
                listOf("Neon", "Shape", "Glow"), "Ayo", thumbMs = 1800)
        ),
        Bundle(
            "shake-transition",
            """<scene title="Shake Transition" width="1080" height="1080" fps="30" totalTime="1999" bgcolor="#ff000000">
              <shape id="1" label="Pink" startTime="0" endTime="1199" fillType="color" s=".rect"><transform><location value="540,540,0"/><scale value="1.1,1.1"/></transform><property name="size" type="vec2" value="540,540"/><fillColor value="#ffef476f"/>
                <effect id="absent.autoshake"><property name="magnitude" type="float" value="40"/><property name="frequency" type="float" value="9"/></effect></shape>
              <shape id="2" label="Teal" startTime="800" endTime="1999" fillType="color" s=".rect"><transform><location><kf t="0" v="1700,540,0"/><kf t="0.33" v="540,540,0"/></location><scale value="1.1,1.1"/></transform><property name="size" type="vec2" value="540,540"/><fillColor value="#ff06d6a0"/>
                <effect id="absent.autoshake"><property name="magnitude" type="float" value="40"/><property name="frequency" type="float" value="9"/><property name="seed" type="float" value="2"/></effect></shape>
            </scene>""",
            CommunityDetails("Shake Transition", "A pink block shakes while a teal block pushes in from the right, so the cut lands in the middle of the shake.",
                listOf("Transition", "Shake"), "Lena", thumbMs = 1000)
        )
    )

    private val presets = mapOf(
        "fade-up" to ("Fade Up" to """<text id="1" label="Fade Up" startTime="0" endTime="1999" fillType="color" size="56" font="googlefonts?name=Montserrat&amp;weight=800" wrapWidth="1000" align="center"><transform><location><kf t="0" v="540,620,0"/><kf t="0.6" v="540,540,0"/></location><opacity><kf t="0" v="0"/><kf t="0.6" v="1"/></opacity></transform><fillColor value="#ff4361ee"/><content>FADE UP</content></text>"""),
        "bounce-in" to ("Bounce In" to """<shape id="1" label="Bounce In" startTime="0" endTime="1499" fillType="color" s=".circle"><transform><location value="540,540,0"/><scale><kf t="0" v="0,0"/><kf t="0.35" v="1.2,1.2"/><kf t="0.55" v="0.92,0.92"/><kf t="0.7" v="1,1"/></scale></transform><property name="size" type="vec2" value="210,210"/><fillColor value="#fff77f00"/></shape>"""),
        "hard-shake" to ("Hard Shake" to """<text id="1" label="Hard Shake" startTime="0" endTime="1999" fillType="color" size="90" font="googlefonts?name=Bangers&amp;weight=400" wrapWidth="1000" align="center"><transform><location value="540,540,0"/></transform><fillColor value="#ffe63946"/><content>SHAKE</content><effect id="absent.autoshake"><property name="magnitude" type="float" value="45"/><property name="frequency" type="float" value="12"/></effect></text>"""),
        "soft-glow" to ("Soft Glow" to """<shape id="1" label="Soft Glow" startTime="0" endTime="2999" fillType="color" s=".star"><transform><location value="540,540,0"/></transform><property name="pointCount" type="float" value="5"/><property name="outerRadius" type="float" value="230"/><property name="innerRadius" type="float" value="110"/><fillColor value="#ffffe3ef"/><effect id="absent.glow"><property name="radius" type="float" value="220"/><property name="alpha" type="float" value="1"/><property name="hardness" type="float" value="0"/><property name="color" type="color" value="#ffff2d95"/></effect></shape>""")
    )

    @Test fun exportsTheVideoFixtureItems() = runBlocking<Unit> {
        val out = System.getProperty("am.fixtureItems")?.let(::File)
        assumeTrue("pass -PfixtureItems=<folder> to run", out != null)
        out!!.mkdirs()
        val exports = File("build/fixture-exports").canonicalFile.also { it.deleteRecursively() }
        System.setProperty("am.exports", exports.path)
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        try {
            val exporter = Exporter(Desk.context, engine, MediaStoreExport(Desk.context))
            for (bundle in bundles) {
                val result = exporter.export(ExportKind.Package, Scene.parse(bundle.xml), ExportSettings(1080, 30), 0, bundle.file,
                    community = bundle.details.copy(confirmed = true, guidelinesConfirmed = true)) {}
                result.uris.single().toFile()!!.copyTo(out.resolve("${bundle.file}.absproj"), overwrite = true)
            }
            for ((name, preset) in presets) {
                val scene = Scene.parse("""<scene width="1080" height="1080" fps="30" totalTime="2999" bgcolor="#ff000000">${preset.second}</scene>""")
                out.resolve("$name.xml").writeText(presetScene(scene, scene.layers.single(), preset.first).toXml())
            }
        } finally {
            engine.close()
            exports.deleteRecursively()
        }
    }

    @Test fun screenshotsTheNewPageAndEffectTilesFromAFixtureFolder() {
        val feed = System.getProperty("am.fixtureFeed")?.let(::File)
        assumeTrue("pass -PfixtureFeed=<community folder> to run", feed != null)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            val file = feed!!.resolve(ex.requestURI.path.trimStart('/')).canonicalFile
            val bytes = if (file.isFile && file.path.startsWith(feed.canonicalPath)) file.readBytes() else null
            ex.sendResponseHeaders(if (bytes != null) 200 else 404, bytes?.size?.toLong() ?: -1L)
            ex.responseBody.use { if (bytes != null) it.write(bytes) }
        }
        server.start()
        val cache = Desk.context.cacheDir.resolve("community").also { it.deleteRecursively() }
        val before = CommunityFeed.source to sessionIndex
        CommunityFeed.source = LocalCommunity("http://127.0.0.1:${server.address.port}/")
        sessionIndex = null
        sessionAllowed = true
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        val shots = File("build/ui-shots/community").also { it.mkdirs() }
        fun pictures() = cache.resolve("thumbs").listFiles { f -> f.isFile && !f.name.endsWith(".part") }.orEmpty().size
        fun settle(test: ComposeUiTest) = repeat(15) { test.mainClock.advanceTimeBy(200); Thread.sleep(80) }
        try {
            for ((name, size) in listOf("phone" to Size(390f, 844f), "desktop" to Size(1280f, 800f))) {
                cache.deleteRecursively()
                runSkikoComposeUiTest(size = size) {
                    setContent { CommunityTab(Modifier.fillMaxSize().background(Am.HomeBg), { engine }) {} }
                    waitUntil(timeoutMillis = 60_000) { pictures() >= 4 }
                    settle(this)
                    ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("new-$name.png"))
                    val seen = pictures()
                    onAllNodesWithText("Glow, Light & Fire").onFirst().performScrollTo().performClick()
                    waitUntil(timeoutMillis = 60_000) { pictures() >= seen + 16 }
                    settle(this)
                    ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("item-$name.png"))
                    onNodeWithText("Soft Glow").performScrollTo()
                    settle(this)
                    ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("tiles-$name.png"))
                }
            }
        } finally {
            CommunityFeed.source = before.first
            sessionIndex = before.second
            server.stop(0)
            engine.close()
        }
    }

    @Test fun screenshotsTheCommunityTabFromAFixtureFolder() {
        val feed = System.getProperty("am.fixtureFeed")?.let(::File)
        assumeTrue("pass -PfixtureFeed=<community folder> to run", feed != null)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            val file = feed!!.resolve(ex.requestURI.path.trimStart('/')).canonicalFile
            val bytes = if (file.isFile && file.path.startsWith(feed.canonicalPath)) file.readBytes() else null
            ex.sendResponseHeaders(if (bytes != null) 200 else 404, bytes?.size?.toLong() ?: -1L)
            ex.responseBody.use { if (bytes != null) it.write(bytes) }
        }
        server.start()
        val cache = Desk.context.cacheDir.resolve("community").also { it.deleteRecursively() }
        val before = CommunityFeed.source to sessionIndex
        CommunityFeed.source = LocalCommunity("http://127.0.0.1:${server.address.port}/")
        sessionIndex = null
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        val shots = File("build/ui-shots/community").also { it.mkdirs() }
        fun pictures() = cache.resolve("thumbs").listFiles { f -> !f.name.endsWith(".part") }.orEmpty().size
        try {
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                setContent { CommunityTab(Modifier.fillMaxSize().background(Am.HomeBg), { engine }) {} }
                waitUntil(timeoutMillis = 120_000) { pictures() >= 9 }
                repeat(10) { mainClock.advanceTimeBy(200); Thread.sleep(50) }
                ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("fixture-projects.png"))
                onNodeWithText("Presets").performClick()
                repeat(10) { mainClock.advanceTimeBy(200); Thread.sleep(50) }
                ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("fixture-presets.png"))
                onNodeWithText("Soft Glow").performClick()
                repeat(10) { mainClock.advanceTimeBy(200); Thread.sleep(50) }
                ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("fixture-preset-sheet.png"))
            }
        } finally {
            CommunityFeed.source = before.first
            sessionIndex = before.second
            server.stop(0)
            engine.close()
        }
    }
}
