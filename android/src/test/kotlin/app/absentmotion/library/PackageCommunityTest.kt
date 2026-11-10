package app.absentmotion.library

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackageCommunityTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun bundle(community: String?) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            val manifest = """{"format":"absentmotion-package","version":1,"project":"project.xml","media":[]${community?.let { ",\"community\":$it" } ?: ""}}"""
            val entries = listOf("project.xml" to """<scene width="100" height="100" title="Glow"/>""".toByteArray(), "community/thumb.webp" to byteArrayOf(1, 2, 3), "manifest.json" to manifest.toByteArray())
            for ((name, data) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
        }
    }.toByteArray()

    private fun import(bytes: ByteArray) = Importer(ProjectStore(tmp.newFolder())).import { bytes.inputStream() }.single()

    @Test fun aCommunityBundleCarriesItsDetailsToTheImportCard() {
        val tags = (1..12).joinToString(",") { "\"t$it\"" }
        val info = import(bundle("""{"title":" Glow intro ","creator":"Someone","description":"${"d".repeat(900)}","tags":[$tags,""],"thumbnail":"community/thumb.webp"}""")).community!!
        assertEquals("Glow intro", info.title)
        assertEquals("Someone", info.creator)
        assertEquals(500, info.description.length)
        assertEquals((1..8).map { "t$it" }, info.tags)
        assertArrayEquals(byteArrayOf(1, 2, 3), info.thumbnail)
    }

    @Test fun aPlainBundleOrAMissingThumbnailStaysSafe() {
        assertNull(import(bundle(null)).community)
        val info = import(bundle("""{"title":"x","thumbnail":"community/none.webp"}""")).community!!
        assertNull(info.thumbnail)
        assertTrue(info.tags.isEmpty())
    }
}
