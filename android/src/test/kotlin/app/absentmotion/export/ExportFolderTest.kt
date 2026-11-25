package app.absentmotion.export

import android.net.Uri
import app.absentmotion.R
import app.absentmotion.i18n.txt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import java.io.OutputStream

class ExportFolderTest {
    private class Folder(val label: String, val failure: Exception? = null) : ExportStore {
        override fun save(folder: String?, name: String, mime: String, write: (OutputStream) -> Unit): Uri = error("unused")
        override fun open(folder: String?, name: String, mime: String): OpenExport = error("unused")
        override fun delete(uri: Uri) = Unit
        override fun sequenceFolder(name: String, mime: String) = name
        fun write(): String = failure?.let { throw it } ?: label
    }

    private val default = Folder("default")
    private fun write(chosen: Folder?) = savedIn(chosen, default) { (it as Folder).write() }

    @Test fun exportsGoToTheChosenFolderOrTheDefaultOne() {
        val chosen = Folder("chosen")
        assertEquals("chosen", write(chosen).first)
        assertSame(chosen, write(chosen).second)
        assertEquals("default" to default, write(null))
        assertEquals("default" to default, write(default))
    }

    @Test fun aFolderThatCanNoLongerBeReachedFallsBackToTheDefault() {
        val lost = Folder("lost", ExportError(txt(R.string.export_error_create, "a.mp4", "lost")))
        assertEquals("default" to default, write(lost))
    }

    @Test fun otherWriteFailuresAreNotHidden() {
        val failing = Folder("full", IOException("no space"))
        assertEquals("no space", runCatching { write(failing) }.exceptionOrNull()?.message)
    }
}
