package app.absentmotion.library

import app.absentmotion.TestData
import app.absentmotion.editor.presetScene
import app.absentmotion.export.CommunityDetails
import app.absentmotion.export.CommunityPart
import app.absentmotion.export.communityInfo
import app.absentmotion.export.problem
import app.absentmotion.export.writeProjectPackage
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.content
import app.absentmotion.model.effects
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.zip.ZipInputStream

class PresetShareTest {
    @get:Rule val tmp = TemporaryFolder()

    private val files by lazy { tmp.newFolder("files") }
    private val original by lazy { File(files, "media/p1/holiday.jpg").apply { parentFile.mkdirs(); writeText("my own photo") }.toURI().toString() }
    private val picture by lazy { File(files, "media/preset-pictures/hills.webp").apply { parentFile.mkdirs(); writeText("placeholder bytes") }.toURI().toString() }

    private fun placeholder(size: Long = 17) = MediaDeclaration(XmlNode("media", mapOf("uri" to picture, "filename" to "hills.webp", "type" to "image/webp", "width" to "1080", "height" to "1080", "size" to "$size")))

    private fun preset(layer: String): SavedPreset {
        val project = Scene.parse("""<scene title="Holiday" width="1080" height="1920" fps="30" totalTime="2999"><media uri="$original" filename="holiday.jpg" width="1080" height="1080"/>$layer</scene>""")
        return SavedPreset("preset-1", presetScene(project, project.layers.single(), "Glow"))
    }

    private val imagePreset by lazy {
        preset("""<shape id="7" label="holiday.jpg" startTime="0" endTime="2999" fillType="media" fillImage="$original" s=".rect"><effect id="absent.blur"><property name="radius" value="12"/></effect></shape>""")
    }

    private fun pack(scene: Scene, details: CommunityDetails = CommunityDetails("Glow", "Soft blur", listOf("Glow"), "Me", preset = true)): ByteArray = runBlocking {
        ByteArrayOutputStream().also {
            writeProjectPackage(scene, scene.toXml(), it, MediaAccess.forPhone(files), { uri -> File(URI(uri)).inputStream() }, CommunityPart(communityInfo(scene, details, "5.2.0"), null)) {}
        }.toByteArray()
    }

    private fun entries(bytes: ByteArray): Map<String, String> = LinkedHashMap<String, String>().apply {
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) { val e = zip.nextEntry ?: break; put(e.name, zip.readBytes().toString(Charsets.ISO_8859_1)) } }
    }

    private fun localize(root: File) = TemplateStore(File(root, "templates")) { uri -> File(URI(uri)).inputStream() }::localize

    @Test fun anImagePresetIsSharedOnItsPlaceholderAndNeverWithTheOriginalMedia() {
        assertFalse(imagePreset.ownLook)
        assertNull(placeholderScene(imagePreset, null))
        val scene = checkNotNull(placeholderScene(imagePreset, placeholder()))
        assertEquals("preset", scene.node["type"])
        assertEquals(picture, scene.layers.single().mediaFill?.uri)
        assertEquals(listOf("absent.blur"), scene.layers.single().effects.map { it["id"] })
        val zip = entries(pack(scene))
        assertEquals(listOf("project.xml", "media/1_hills.webp", "manifest.json"), zip.keys.toList())
        assertEquals("placeholder bytes", zip["media/1_hills.webp"])
        val project = zip.getValue("project.xml")
        for (leak in listOf("holiday", files.path, "file:")) assertFalse(leak, leak in project)
        assertFalse(zip.values.any { "my own photo" in it })
        val community = JSONObject(zip.getValue("manifest.json")).getJSONObject("community")
        assertEquals("preset", community.getString("kind"))
        assertFalse(community.has("thumbnail"))
        assertEquals("Glow", community.getString("title"))
        assertEquals("CC BY 4.0", community.getString("licence"))
    }

    @Test fun aTextPresetKeepsItsOwnTextAndPacksItsFont() {
        File(files, "fonts/imported/My Font.ttf").apply { parentFile.mkdirs() }.writeBytes(File(TestData.project, "shared/assets/fonts/AbrilFatface/AbrilFatface-Regular.ttf").readBytes())
        val text = preset("""<text id="3" startTime="0" endTime="2999" font="imported?name=My Font.ttf" size="64"><content>Hello there</content><border/></text>""")
        assertTrue(text.ownLook)
        assertEquals(PresetPlaceholder.Own, text.placeholder)
        val scene = checkNotNull(placeholderScene(text, null))
        assertEquals("Hello there", scene.layers.single().content)
        val zip = entries(pack(scene))
        assertEquals(listOf("project.xml", "fonts/1_My Font.ttf", "manifest.json"), zip.keys.toList())
        val picked = checkNotNull(placeholderScene(text, placeholder()))
        assertEquals("shape", picked.layers.single().kind)
        assertTrue(picked.layers.single().node.child("border") != null)
    }

    @Test fun openingAPresetBundleAddsItToMyPresetsWithItsPlaceholder() {
        val bundle = pack(checkNotNull(placeholderScene(imagePreset, placeholder())))
        val root = tmp.newFolder("app")
        val projects = ProjectStore(root)
        val report = Importer(projects).import { bundle.inputStream() }.single()
        assertTrue(report.community!!.preset)
        val presets = PresetStore(File(root, "presets"))
        val id = checkNotNull(presets.adopt(projects, report.id, localize(root)))
        assertTrue(projects.list().isEmpty())
        val added = presets.list().single()
        assertEquals(id, added.id)
        assertEquals("preset", added.scene.node["type"])
        val kept = (added.placeholder as PresetPlaceholder.Media).media.uri
        assertEquals(kept, added.layer!!.mediaFill!!.uri)
        val file = File(URI(kept))
        assertEquals(File(root, "media/preset-$id").canonicalFile, file.canonicalFile.parentFile)
        assertEquals("placeholder bytes", file.readText())
        assertEquals("absent.blur", added.layer!!.effects.single()["id"])
        presets.delete(id)
        assertFalse(file.exists())

        val project = runBlocking {
            ByteArrayOutputStream().also { writeProjectPackage(imagePreset.scene, imagePreset.scene.toXml(), it, MediaAccess.forPhone(files), { null }, CommunityPart(communityInfo(imagePreset.scene, CommunityDetails("P"), "5.2.0"), null)) {} }.toByteArray()
        }
        assertFalse(Importer(projects).import { project.inputStream() }.single().community!!.preset)
    }

    @Test fun aPresetCommunityItemMayBeAnAbsprojUpTo20MbAndInstalls() {
        fun item(name: String, file: String, bytes: Long) =
            """{"kind":"preset","name":"$name","title":"$name","file":"presets/$name/$file","bytes":$bytes,"sha256":"${"a".repeat(64)}"}"""
        val index = parseCommunityIndex(
            """{"format":"absentmotion-community","version":2,"items":[${item("big", "big.absproj", 20L shl 20)},${item("huge", "huge.absproj", (20L shl 20) + 1)},""" +
                """${item("old", "old.xml", 1L shl 20)},${item("old-big", "old-big.xml", (1L shl 20) + 1)}]}""", 1_000_000,
        )
        assertEquals(listOf("big", "old"), index.items.map { it.name })

        val root = tmp.newFolder("install")
        val projects = ProjectStore(root)
        val presets = PresetStore(File(root, "presets"))
        val bundle = pack(checkNotNull(placeholderScene(imagePreset, placeholder())))
        val id = presets.addBundle(projects, Importer(projects), localize(root)) { bundle.inputStream() }
        assertEquals(listOf(id), presets.list().map { it.id })
        assertTrue(projects.list().isEmpty())
    }

    @Test fun aPresetBundleOver20MbIsRefusedLikeAProjectBundle() {
        val details = CommunityDetails("Glow", confirmed = true, guidelinesConfirmed = true, preset = true)
        assertNull(details.problem(checkNotNull(placeholderScene(imagePreset, placeholder(1L shl 20))), MediaAccess.forPhone(files)))
        assertNotNull(details.problem(checkNotNull(placeholderScene(imagePreset, placeholder(21L shl 20))), MediaAccess.forPhone(files)))
    }

    @Test fun theRandomPictureIsStableAndAPickedPlaceholderIsRemembered() {
        val again = SavedPreset(imagePreset.id, imagePreset.scene)
        assertEquals(PresetPlaceholder.Picture(Math.floorMod("preset-1".hashCode(), PresetPictures.size)), again.placeholder)
        val root = tmp.newFolder("remember")
        val presets = PresetStore(File(root, "presets"))
        val id = presets.save(imagePreset.scene)
        presets.setPlaceholder(id, PresetPlaceholder.Picture(3))
        assertEquals(PresetPlaceholder.Picture(3), presets.load(id)!!.placeholder)
        presets.setPlaceholder(id, PresetPlaceholder.Media(placeholder()))
        assertEquals(picture, (presets.load(id)!!.placeholder as PresetPlaceholder.Media).media.uri)
        assertEquals(1, presets.load(id)!!.scene.node.children.count { it.name == "placeholder" })
    }
}
