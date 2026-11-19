package app.absentmotion.pc

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskScale
import app.absentmotion.editor.presetScene
import app.absentmotion.export.CommunityDetails
import app.absentmotion.export.ExportKind
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.Exporter
import app.absentmotion.export.MediaStoreExport
import app.absentmotion.library.PresetPlaceholder
import app.absentmotion.library.PresetStore
import app.absentmotion.library.SavedPreset
import app.absentmotion.library.placeholderScene
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.LocalTablet
import app.absentmotion.ui.editor.LocalWide
import app.absentmotion.ui.export.BundlePage
import app.absentmotion.ui.export.PresetShare
import app.absentmotion.ui.export.PresetSharing
import app.absentmotion.ui.home.TemplatesTab
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class PresetShareDeskTest {
    private val shots = File("build/ui-shots/preset").also { it.mkdirs() }
    private val repo = File("..").canonicalFile
    private val photo = repo.resolve("testdata/media/test-image.jpg").toURI().toString()

    private fun prepare(): DeskAppState {
        System.setProperty("am.data", File("build/preset-share-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("user.home", File("build/preset-share-home").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("am.exports", File("build/preset-share-exports").also { it.deleteRecursively() }.absolutePath)
        val app = DeskAppState()
        val presets = PresetStore(app.context)
        fun save(title: String, layer: String) {
            val scene = Scene.parse("""<scene width="1080" height="1920" fps="30" totalTime="2999" bgcolor="#ff000000">$layer</scene>""")
            presets.save(presetScene(scene, scene.layers.single(), title))
        }
        save("Warm Glow Photo", """<shape id="1" startTime="0" endTime="2999" fillType="media" fillImage="$photo" mediaFillMode="fill" s=".rect"><transform><location value="540,960,0"/></transform><property name="size" type="vec2" value="540,540"/><effect id="absent.glow"><property name="radius" type="float" value="160"/><property name="alpha" type="float" value="1"/><property name="hardness" type="float" value="0"/><property name="color" type="color" value="#ffff2d95"/></effect></shape>""")
        save("Shake Title", """<text id="1" startTime="0" endTime="1999" fillType="color" size="150" font="googlefonts?name=Bangers&amp;weight=400" wrapWidth="1000" align="center"><transform><location value="540,960,0"/></transform><fillColor value="#ffffd166"/><content>SHAKE</content><effect id="absent.autoshake"><property name="magnitude" type="float" value="45"/><property name="frequency" type="float" value="12"/></effect></text>""")
        save("Spin In Square", """<shape id="1" startTime="0" endTime="2999" fillType="color" s=".rect"><transform><location value="540,960,0"/><rotation><kf t="0" v="0"/><kf t="1" v="180"/></rotation><scale><kf t="0" v="0.2,0.2"/><kf t="0.5" v="1,1"/></scale></transform><property name="size" type="vec2" value="300,300"/><fillColor value="#ff3d5ae0"/></shape>""")
        return app
    }

    private fun ComposeUiTest.wait(seconds: Int) = repeat(seconds * 5) { mainClock.advanceTimeBy(200); Thread.sleep(200) }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    private fun presets(app: DeskAppState) = PresetStore(app.context).list().sortedBy { it.title }

    @Test fun myPresetsCardsOnAPhoneATabletAndWithLargeText() {
        val app = prepare()
        for ((name, size, density, tablet) in listOf(
            Quad("phone", Size(720f, 1560f), Density(2f), false),
            Quad("phone-large-text", Size(720f, 1560f), Density(2f, 1.6f), false),
            Quad("tablet", Size(1200f, 1920f), Density(1.5f), true),
        )) runSkikoComposeUiTest(size = size, density = density) {
            setContent {
                CompositionLocalProvider(LocalWide provides tablet, LocalTablet provides tablet) {
                    Box(Modifier.fillMaxSize().background(Am.HomeBg)) { TemplatesTab(1, {}, {}, Modifier.fillMaxSize(), "", { app.engine }) }
                }
            }
            onNodeWithText("My Presets").performClick()
            wait(6)
            listOf("Warm Glow Photo", "Shake Title", "Spin In Square").forEach { onNodeWithText(it).assertExists() }
            shot("cards-$name")
            onAllNodesWithContentDescription("More").onFirst().performClick()
            wait(1)
            listOf("Rename", "Delete", "Share to Community").forEach { onNodeWithText(it).assertExists() }
            if (name == "phone") shot("cards-phone-menu")
            onNodeWithText("Share to Community").performClick()
            assertNotNull(PresetSharing.preset)
            PresetSharing.preset = null
        }
        assertTrue(app.context.cacheDir.resolve("presets").listFiles().orEmpty().size >= 3)
    }

    @Test fun theSharePageOnAPhoneAndATablet() {
        val app = prepare()
        for (preset in presets(app).filter { it.title != "Spin In Square" }) for ((name, size, density) in listOf(
            Triple("phone", Size(720f, 1560f), Density(2f)),
            Triple("tablet", Size(1200f, 1920f), Density(1.5f)),
        )) runSkikoComposeUiTest(size = size, density = density) {
            val tag = if (preset.ownLook) "text" else "photo"
            val scene = checkNotNull(runBlocking { placeholderScene(app.context, preset, preset.placeholder) })
            setContent {
                var community by remember { mutableStateOf<CommunityDetails?>(CommunityDetails(preset.title, preset = true)) }
                CompositionLocalProvider(LocalWide provides (name == "tablet"), LocalTablet provides (name == "tablet")) {
                    BundlePage(scene, app.engine, app.settings, community, { community = it }, {}, {}, PresetShare(preset, preset.placeholder) {}, {})
                }
            }
            wait(5)
            listOf("Share to Community", "Placeholder", "Random picture", "Choose picture or video…").forEach { onNodeWithText(it).assertExists() }
            if (preset.ownLook) onNodeWithText("Its own text").assertExists()
            else onNodeWithText("Its own text").assertDoesNotExist()
            onNodeWithText("Shuffle").assertExists()
            shot("share-$name-$tag")
            onAllNodesWithText("I made this", substring = true).onFirst().performScrollTo()
            wait(1)
            shot("share-$name-$tag-end")
        }
    }

    @Test fun aComputerSharesFromTheTemplatesTabAndTheBundleOpensAsAPreset() {
        val app0 = prepare()
        DeskScale.set(100)
        for ((w, h) in listOf(1280 to 800, 820 to 560)) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
            val app = DeskAppState()
            setContent { DeskApp(app) }
            wait(1)
            onNodeWithText("Templates").performClick()
            wait(1)
            onNodeWithText("My Presets").performClick()
            wait(6)
            shot("desk-$w-cards")
            onNodeWithText("Warm Glow Photo").performMouseInput { rightClick() }
            wait(1)
            onNodeWithText("Share to Community").assertExists()
            shot("desk-$w-menu")
            onNodeWithText("Share to Community").performClick()
            wait(6)
            assertEquals("Warm Glow Photo", PresetSharing.preset?.title)
            listOf("Placeholder", "Random picture", "Choose picture or video…", "Shuffle").forEach { onAllNodesWithText(it).onFirst().assertExists() }
            onAllNodesWithText("Choose thumbnail").assertCountEquals(0)
            shot("desk-$w-share")
            onNodeWithText("Shuffle").performClick()
            wait(4)
            shot("desk-$w-share-shuffled")
            PresetSharing.preset = null
            wait(1)
        }

        runBlocking {
            val photoPreset = presets(app0).first { it.title == "Warm Glow Photo" }
            val scene = checkNotNull(placeholderScene(app0.context, photoPreset, PresetPlaceholder.Picture(2)))
            val result = Exporter(app0.context, app0.engine, MediaStoreExport(app0.context)).export(
                ExportKind.Package, scene, ExportSettings(1080, 30), 0, "Warm Glow Photo",
                community = CommunityDetails("Warm Glow Photo", "Glow on anything", listOf("Glow"), "Tester", confirmed = true, guidelinesConfirmed = true, preset = true),
            ) {}
            val bundle = checkNotNull(result.uris.single().toFile())
            ZipFile(bundle).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertEquals(listOf("project.xml", "media/1_hills.webp", "manifest.json"), names)
                val project = zip.getInputStream(zip.getEntry("project.xml")).readBytes().toString(Charsets.UTF_8)
                assertFalse("test-image" in project)
                assertFalse("file:" in project)
                assertEquals("preset", JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8)).getJSONObject("community").getString("kind"))
            }
            val before = presets(app0).size
            val projects = app0.store.list().size
            val reports = app0.importFiles(listOf(Uri.fromFile(bundle)))
            assertTrue(reports.isEmpty())
            assertEquals(projects, app0.store.list().size)
            val added = presets(app0)
            assertEquals(before + 1, added.size)
            val imported: SavedPreset = added.single { it.scene.node.child("placeholder") != null }
            val kept = (imported.placeholder as PresetPlaceholder.Media).media.uri
            assertTrue(kept, kept.contains("/media/preset-${imported.id}/"))
        }
    }

    private data class Quad(val name: String, val size: Size, val density: Density, val tablet: Boolean)
}
