package app.absentmotion.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportNameTest {
    @Test fun aNewVisitStartsWithTheProjectTitle() {
        val first = ExportName("My project")
        first.value = "A different name"
        assertEquals("A different name", first.next(ExportKind.Video))
        assertEquals("My project", ExportName("My project").next(ExportKind.Video))
        assertFalse(ExportName("My project").random)
    }

    @Test fun numericNamesAreFreshAndOutsideTheExcludedRange() {
        val name = ExportName("Project").apply { random = true }
        val values = List(128) { name.next(ExportKind.Video) }
        assertEquals(values.size, values.toSet().size)
        assertTrue(values.all { it.matches(Regex("[1-9][0-9]{15}")) && it.toLong() > 100_000_000L })
    }

    @Test fun randomNamesApplyOnlyToImagesAndVideos() {
        val name = ExportName("My project").apply { random = true }
        for (kind in listOf(ExportKind.Frame, ExportKind.FrameAs, ExportKind.Gif, ExportKind.Sequence)) {
            assertTrue(name.next(kind).matches(Regex("[1-9][0-9]{15}")))
        }
        for (kind in listOf(ExportKind.Package, ExportKind.Xml, ExportKind.Audio)) assertEquals("My project", name.next(kind))
    }

    @Test fun disablingRandomNamesRestoresTheCustomName() {
        val name = ExportName("Project").apply { value = "Custom"; random = true }
        name.next(ExportKind.Video)
        name.random = false
        assertEquals("Custom", name.next(ExportKind.Video))
    }

    @Test fun invalidNamesFallBackToTheProjectAndCannotEscapeTheExportFolder() {
        val name = ExportName("My project").apply { value = "../\\\n" }
        assertEquals("My project", name.next(ExportKind.Frame))
        name.value = " /A: clip? "
        assertEquals("A clip", name.next(ExportKind.Frame))
        name.value = "x".repeat(300)
        assertEquals(120, name.next(ExportKind.Frame).length)
        name.value = "অ".repeat(160)
        assertTrue(name.next(ExportKind.Frame).toByteArray(Charsets.UTF_8).size <= 180)
        assertTrue(name.next(ExportKind.Frame).all { it == 'অ' })
    }
}
