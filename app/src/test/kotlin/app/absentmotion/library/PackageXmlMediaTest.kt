package app.absentmotion.library

import app.absentmotion.model.mediaUris
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackageXmlMediaTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun archive(manifest: String) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, data) in listOf("media/other.xml" to "<data>fixture</data>", "project.xml" to """<scene width="100" height="100"><shape id="1" fillImage="x://data"/></scene>""", "manifest.json" to manifest)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(data.toByteArray()); zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun mediaXmlIsCopiedRatherThanImportedAsAnotherScene() {
        for (project in listOf("", "\"project\":\"project.xml\",")) {
            val bytes = archive("""{$project"media":[{"uri":"x://data","entry":"media/other.xml"}]}""")
            val store = ProjectStore(tmp.newFolder())
            val report = Importer(store).import { bytes.inputStream() }.single()
            val uri = store.load(report.id)!!.node.mediaUris().single()
            assertEquals("<data>fixture</data>", java.io.File(java.net.URI(uri)).readText())
        }
    }

    @Test fun aMissingManifestProjectDoesNotSilentlySelectSomeOtherXml() {
        val bytes = archive("""{"project":"absent.xml","media":[]}""")
        try { Importer(ProjectStore(tmp.newFolder())).import { bytes.inputStream() }; fail("missing project accepted") }
        catch (e: ImportFailed) { assertTrue(e.message!!.contains("project", true)) }
    }
}
