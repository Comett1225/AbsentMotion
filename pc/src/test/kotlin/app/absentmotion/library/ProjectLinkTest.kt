package app.absentmotion.library

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.ui.home.ProjectLinkDialog
import app.absentmotion.ui.home.TrustedHttp
import app.absentmotion.ui.home.UpdateProblem
import app.absentmotion.ui.home.UpdateSource
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectLinkTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val origin = URI("http://127.0.0.1:${server.address.port}")
    private val http = TrustedHttp(object : UpdateSource {
        override val https = false
        override val releases = ""
        override fun asset(tag: String, name: String) = ""
        override fun allows(url: URI, first: Boolean) = url.scheme == origin.scheme && url.authority == origin.authority
    })

    @After fun stop() = server.stop(0)

    @Test fun onlySecureFileLinksAndRecognizedAlightLinksAreAccepted() {
        assertNotNull(projectFileLink(" https://example.com/export?id=3 "))
        assertTrue(isAlightShare(projectFileLink("http://alight.link/public")!!))
        assertTrue(isAlightShare(projectFileLink("https://alight.page.link/public")!!))
        assertTrue(isAlightShare(projectFileLink("https://alightcreative.com/am/share/u/owner/p/project")!!))
        assertFalse(isAlightShare(projectFileLink("https://alight.link.example.com/project")!!))
        assertFalse(isAlightShare(projectFileLink("https://alightcreative.com/project.xml")!!))
        assertNull(projectFileLink("http://example.com/project.xml"))
        assertNull(projectFileLink("file:///sdcard/project.xml"))
        assertNull(projectFileLink("https://alight.link@example.com/project.xml"))
        assertNull(projectFileLink("https://example.com/project.xml#fragment"))
        assertThrows(UpdateProblem::class.java) { projectFileHttp(origin).get(origin.toString()) {} }
    }

    @Test fun pastedShareLinksIgnoreInvisibleBoundaryMarksAndHandleHttp() {
        val short = "https://alight.link/SharedProject"
        assertEquals(URI(short), projectFileLink("\u2060$short\u2060 \u200b"))
        assertEquals(URI(short), alightHttps(projectFileLink("http://alight.link/SharedProject\u2060")!!))
        assertEquals(URI(short), projectFileLink("alight.link/SharedProject"))
        assertFalse(alightPage(projectFileLink("https://alight.link/Share\u2060dProject")!!))
    }

    @Test fun incomingLinkIntentsAcceptOneUnambiguousAlightLink() {
        val short = "https://alight.link/SharedProject"
        assertEquals(URI(short), externalAlightLink("My project\nhttp://alight.link/SharedProject\u2060"))
        assertEquals(URI(short), externalAlightLink(short))
        assertNull(externalAlightLink("https://alight.link/A https://alight.link/B"))
        assertNull(externalAlightLink("https://alight.link.example.com/A"))
        assertNull(externalAlightLink("https://example.com/project.xml"))
    }

    @Test fun alightPreviewsReadMetadataAndRestrictThumbnailsToTheSharedProject() {
        val fields = alightPreviewFields("""
            <META content='Cats &amp; Dogs &#x1F98B;' property='og:title'>
            <meta property="og:description" content="A project &quot;from Alight&quot;">
            <meta name="og:image" content="https://firebasestorage.googleapis.com:443/v0/b/alight-creative.appspot.com/o/share%2Fu%2Fowner%2Fp%2Fproject%2Fthumb-small.jpg?alt=media&amp;token=c0d33023-90bd-45bf-a241-d5e2e36fec31"/>
        """.trimIndent())
        assertEquals("Cats & Dogs 🦋", fields["og:title"])
        assertEquals("A project \"from Alight\"", fields["og:description"])
        val page = URI("https://alightcreative.com/am/share/u/owner/p/project")
        val image = fields.getValue("og:image")
        assertTrue(alightThumbnail(page, URI(image)))
        for (invalid in listOf(
            image.replace("project%2F", "other%2F"),
            image.replace("thumb-small.jpg", "projectfiles.zip"),
            image.replace("googleapis.com", "googleapis.com.example.com"),
            image.replace("https://", "http://"),
        )) assertFalse(alightThumbnail(page, URI(invalid)))
    }

    @Test fun alightSharesIdentifyOnlyTheirPublicProjectBundles() {
        assertTrue(alightPage(URI("https://alight.link/ShortId")))
        assertTrue(alightPage(URI("https://alight.page.link/?link=https%3A%2F%2Falightcreative.com")))
        val canonical = URI("https://www.alightcreative.com/am/share/u/owner/p/project-123/?ref=share")
        assertEquals(
            "https://firebasestorage.googleapis.com/v0/b/alight-creative.appspot.com/o/share%2Fu%2Fowner%2Fp%2Fproject-123%2Fprojectfiles.zip?alt=media",
            alightPackageUrl(canonical).toString(),
        )
        for (invalid in listOf(
            "https://alightcreative.com/am/share/u/owner/p/%2e%2e",
            "https://alightcreative.com/am/share/u/owner/p/project#fragment",
            "https://alightcreative.com/am/share/u/owner/p/project/asset",
            "https://alightcreative.com:8443/am/share/u/owner/p/project",
            "https://alightcreative.com.example.test/am/share/u/owner/p/project",
            "https://example@alightcreative.com/am/share/u/owner/p/project",
        )) assertNull(alightPackageUrl(URI(invalid)))
    }

    @Test fun linkedXmlIsImportedAndItsDownloadedCopyIsRemoved() {
        respond("/export.xml", "application/xml", scene("Linked XML"))
        server.createContext("/share") { request ->
            request.responseHeaders.set("Location", "/export.xml")
            request.sendResponseHeaders(302, -1)
            request.close()
        }
        server.start()
        val cache = temporary.newFolder("cache")
        val store = ProjectStore(temporary.newFolder("projects"))
        val report = runBlocking {
            withProjectLinkFile(origin.resolve("/share"), cache, http) { file ->
                Importer(store).import(file::inputStream).single()
            }
        }
        assertEquals("Linked XML", store.load(report.id)?.title)
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun linkedAlightPackageUsesTheTemplateImportPath() {
        val bytes = ByteArrayOutputStream().also { data ->
            ZipOutputStream(data).use { zip ->
                for ((name, content) in listOf("project.xml" to scene("Linked Package"), "manifest.json" to """{"project":"project.xml"}""".toByteArray())) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        respond("/download", "application/octet-stream", bytes)
        server.start()
        val cache = temporary.newFolder("cache")
        val store = ProjectStore(temporary.newFolder("projects"))
        val report = runBlocking {
            withProjectLinkFile(origin.resolve("/download"), cache, http) { file ->
                Importer(store).import(file::inputStream).single()
            }
        }
        assertTrue(report.template)
        assertEquals("Linked Package", store.load(report.id)?.title)
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun anInvalidAlightShareLinkFailsWithoutDownloading() {
        val error = assertThrows(ImportFailed::class.java) {
            runBlocking {
                withProjectLinkFile(URI("https://alight.link/invalid/share"), temporary.root, http) { error("Unexpected file") }
            }
        }
        assertEquals(R.string.project_link_alight, error.text.id)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun invalidAlightShareErrorAppearsInTheImportDialog() {
        runSkikoComposeUiTest(size = Size(720f, 520f)) {
            setContent { ProjectLinkDialog({}) { _, _ -> error("Unexpected file") } }
            onNode(hasSetTextAction()).performTextReplacement("https://alight.link/invalid/share")
            onNodeWithText(AppText.get(R.string.desk_import)).performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText(AppText.get(R.string.project_link_alight)).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(AppText.get(R.string.project_link_alight)).assertIsDisplayed()
        }
    }

    @Test fun webPagesAndOversizedDownloadsNeverReachTheImporter() {
        respond("/web", "text/html; charset=utf-8", "<html>Share page</html>".toByteArray())
        server.createContext("/large") { request ->
            request.sendResponseHeaders(200, (256L shl 20) + 1)
            request.close()
        }
        server.start()
        val expected = listOf("/web" to R.string.project_link_web_page, "/large" to R.string.project_link_too_large)
        for ((path, message) in expected) {
            val error = assertThrows(ImportFailed::class.java) {
                runBlocking {
                    withProjectLinkFile(origin.resolve(path), temporary.root, http) { error("Unexpected file") }
                }
            }
            assertEquals(message, error.text.id)
        }
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellingImportRemovesTheTemporaryDownload() {
        respond("/project", "text/xml", scene("Cancelled"))
        server.start()
        var download: File? = null
        assertThrows(CancellationException::class.java) {
            runBlocking {
                withProjectLinkFile(origin.resolve("/project"), temporary.root, http) {
                    download = it
                    throw CancellationException()
                }
            }
        }
        assertNotNull(download)
        assertFalse(download!!.exists())
    }

    private fun respond(path: String, mime: String, bytes: ByteArray) = server.createContext(path) { request ->
        request.responseHeaders.set("Content-Type", mime)
        request.sendResponseHeaders(200, bytes.size.toLong())
        request.responseBody.use { it.write(bytes) }
    }

    private fun scene(title: String) = """<scene title="$title" width="160" height="160" fps="30" totalTime="1000"/>""".toByteArray()
}
