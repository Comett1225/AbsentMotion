package app.absentmotion.pc

import app.absentmotion.pc.media.addMedia
import app.absentmotion.pc.media.importMedia
import app.absentmotion.pc.media.mediaThumbnail
import app.absentmotion.pc.project.ProjectState
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MediaImportTest {
    @Test fun importsReferencesCopiesAndAddsMedia() {
        val folder = File("build/media-import-test").also { it.mkdirs() }
        val sources = listOf("test-image.jpg", "test-video.mp4", "test-audio.m4a").map { File("../testdata/media/$it") }
        val refs = importMedia(sources, null)
        assertEquals(3, refs.size)
        assertEquals(sources[0].canonicalFile, File(java.net.URI(refs[0].uri)).canonicalFile)
        val project = folder.resolve("project.xml")
        val copied = importMedia(sources, project)
        assertEquals(3, copied.size)
        assertEquals(project.parentFile.resolve("project_media").canonicalFile, File(java.net.URI(copied[0].uri)).parentFile.canonicalFile)
        val thumbnail = mediaThumbnail(copied[1], folder, folder.resolve("thumbnails"))
        assertNotNull(thumbnail)
        assertTrue(thumbnail!!.length() > 0)
        assertEquals(thumbnail, mediaThumbnail(copied[1], folder, folder.resolve("thumbnails")))
        val state = ProjectState()
        copied.forEach { media ->
            addMedia(state, media)
            assertEquals(if (media.type.startsWith("audio/")) "audio" else "shape", state.layer!!.kind)
            assertEquals(media.uri, state.layer!!.node[when { media.type.startsWith("image/") -> "fillImage"; media.type.startsWith("video/") -> "fillVideo"; else -> "src" }])
            state.undo()
            assertNull(state.layer)
        }
    }
}
