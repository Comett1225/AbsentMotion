package app.absentmotion.pc

import app.absentmotion.desk.DeskPicker
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskPickerTest {
    private val media = DeskPicker.Filter("Media", listOf("mp4", "mov"))

    @Test fun portalUrisBecomeFiles() {
        assertEquals(File("/home/a b/café.mp4"), DeskPicker.fileOf("file:///home/a%20b/caf%C3%A9.mp4"))
        assertEquals(File("/tmp/😀 x.png"), DeskPicker.fileOf("file:///tmp/%F0%9F%98%80%20x.png"))
        assertEquals(File("/tmp/x"), DeskPicker.fileOf("file://localhost/tmp/x"))
        assertEquals(File("/tmp/a b"), DeskPicker.fileOf("file:///tmp/a b"))
        assertEquals(File("/tmp/100%zz"), DeskPicker.fileOf("file:///tmp/100%zz"))
        assertEquals(File("/tmp/50%"), DeskPicker.fileOf("file:///tmp/50%"))
        assertEquals(File("/run/user/1000/doc/ab12/clip.MP4"), DeskPicker.fileOf("FILE:///run/user/1000/doc/ab12/clip.MP4"))
        assertNull(DeskPicker.fileOf("sftp://host/tmp/x"))
        assertNull(DeskPicker.fileOf("file://server/share/x"))
        assertNull(DeskPicker.fileOf("/tmp/x"))
    }

    @Test fun filterStrings() {
        assertEquals("*.mp4;*.MP4", DeskPicker.glob("mp4", ";"))
        assertEquals("*.m4v *.M4V", DeskPicker.glob("m4v", " "))
        assertArrayEquals(arrayOf("Media", "*.mp4;*.mov"), DeskPicker.windowsFilters(listOf(media)))
        assertArrayEquals(arrayOf("Media", "*.mp4;*.MP4;*.mov;*.MOV"), DeskPicker.linuxFilters(listOf(media)))
        assertArrayEquals(emptyArray<String>(), DeskPicker.windowsFilters(emptyList()))
    }

    @Test fun linuxFallbackChoice() {
        assertEquals("kdialog", DeskPicker.linuxTool("KDE", kdialog = true, zenity = true))
        assertEquals("zenity", DeskPicker.linuxTool("ubuntu:GNOME", kdialog = true, zenity = true))
        assertEquals("zenity", DeskPicker.linuxTool("X-Cinnamon", kdialog = false, zenity = true))
        assertEquals("zenity", DeskPicker.linuxTool("KDE", kdialog = false, zenity = true))
        assertEquals("kdialog", DeskPicker.linuxTool("GNOME", kdialog = true, zenity = false))
        assertEquals("kdialog", DeskPicker.linuxTool("plasma:kde", kdialog = true, zenity = true))
        assertNull(DeskPicker.linuxTool(null, kdialog = false, zenity = false))
    }

    @Test fun linuxToolCommands() {
        val home = "/home/u"
        assertEquals(listOf("zenity", "--file-selection", "--title=Link a media folder", "--directory"),
            DeskPicker.toolCommand("zenity", DeskPicker.FOLDER, false, "Link a media folder", "", listOf(media), home))
        assertEquals(listOf("zenity", "--file-selection", "--title=Choose media", "--multiple", "--separator=\n", "--file-filter=Media | *.mp4 *.MP4 *.mov *.MOV"),
            DeskPicker.toolCommand("zenity", DeskPicker.OPEN, true, "Choose media", "", listOf(media), home))
        assertEquals(listOf("zenity", "--file-selection", "--title=Save", "--save", "--filename=/home/u/a.mp4"),
            DeskPicker.toolCommand("zenity", DeskPicker.SAVE, false, "Save", "a.mp4", emptyList(), home))
        assertEquals(listOf("kdialog", "--title", "Save", "--getsavefilename", "/home/u/a.mp4"),
            DeskPicker.toolCommand("kdialog", DeskPicker.SAVE, false, "Save", "a.mp4", emptyList(), home))
        assertEquals(listOf("kdialog", "--title", "Link a media folder", "--getexistingdirectory", home),
            DeskPicker.toolCommand("kdialog", DeskPicker.FOLDER, false, "Link a media folder", "", emptyList(), home))
        val open = DeskPicker.toolCommand("kdialog", DeskPicker.OPEN, true, "Choose media", "", listOf(media), home)
        assertEquals(listOf("kdialog", "--title", "Choose media", "--getopenfilename", home, "*.mp4 *.MP4 *.mov *.MOV|Media", "--multiple", "--separate-output"), open)
        assertTrue(DeskPicker.toolCommand("kdialog", DeskPicker.OPEN, false, "Choose a file", "", emptyList(), home).none { it.startsWith("--multiple") || it.contains('|') })
    }
}
