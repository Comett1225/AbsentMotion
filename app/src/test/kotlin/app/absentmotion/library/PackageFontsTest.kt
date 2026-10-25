package app.absentmotion.library

import app.absentmotion.TestData
import app.absentmotion.export.writeProjectPackage
import app.absentmotion.model.Scene
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class PackageFontsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val font = File(TestData.project, "app/src/main/assets/fonts/AbrilFatface/AbrilFatface-Regular.ttf").readBytes()

    private fun packed(fontName: String, bytes: ByteArray = font): ByteArray = runBlocking {
        val files = tmp.newFolder()
        File(files, "fonts/imported/My Font.ttf").apply { parentFile.mkdirs(); writeBytes(bytes) }
        val scene = Scene.parse("""<scene title="T" width="10" height="10"><text id="1" font="$fontName"/><embedScene id="2"><scene width="10" height="10"><text id="3" font="$fontName"/></scene></embedScene></scene>""")
        ByteArrayOutputStream().also { writeProjectPackage(scene, scene.toXml(), it, MediaAccess.forPhone(files), { null }, {}) }.toByteArray()
    }

    private fun import(bytes: ByteArray, fonts: File): Scene {
        val store = ProjectStore(tmp.newFolder())
        return store.load(Importer(store, fonts = fonts).import { bytes.inputStream() }.single().id)!!
    }

    private fun fontsIn(scene: Scene) = listOf(scene.layers[0].node["font"], scene.layers[1].nested!!.layers[0].node["font"])

    @Test fun anImportedFontTravelsInThePackageAndIsInstalledOnTheOtherSide() {
        val bytes = packed("imported?name=My Font.ttf")
        val names = ArrayList<String>()
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) names += (zip.nextEntry ?: break).name }
        assertEquals(listOf("project.xml", "fonts/1_My Font.ttf", "manifest.json"), names)
        val fonts = tmp.newFolder("fonts")
        val scene = import(bytes, fonts)
        assertArrayEquals(font, File(fonts, "My Font.ttf").readBytes())
        assertEquals(listOf("imported?name=My Font.ttf", "imported?name=My Font.ttf"), fontsIn(scene))
        import(bytes, fonts)
        assertEquals(listOf("My Font.ttf"), fonts.list()!!.toList())
    }

    @Test fun aDifferentFontWithTheSameNameGetsANewNameAndTheTextFollowsIt() {
        val fonts = tmp.newFolder("fonts")
        val other = File(fonts, "My Font.ttf").apply { writeBytes(font.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }) }.readBytes()
        val scene = import(packed("imported?name=My Font.ttf"), fonts)
        assertEquals(listOf("imported?name=My Font-1.ttf", "imported?name=My Font-1.ttf"), fontsIn(scene))
        assertArrayEquals(font, File(fonts, "My Font-1.ttf").readBytes())
        assertArrayEquals(other, File(fonts, "My Font.ttf").readBytes())
        File(fonts, "Same.ttf").writeBytes(font)
        File(fonts, "My Font-1.ttf").delete()
        assertEquals(listOf("imported?name=Same.ttf", "imported?name=Same.ttf"), fontsIn(import(packed("imported?name=My Font.ttf"), fonts)))
    }

    @Test fun somethingThatIsNotAFontIsNeverInstalled() {
        val fonts = tmp.newFolder("fonts")
        val scene = import(packed("imported?name=My Font.ttf", "not a font at all".toByteArray()), fonts)
        assertTrue(fonts.list()!!.isEmpty())
        assertEquals(listOf("imported?name=My Font.ttf", "imported?name=My Font.ttf"), fontsIn(scene))
    }
}
