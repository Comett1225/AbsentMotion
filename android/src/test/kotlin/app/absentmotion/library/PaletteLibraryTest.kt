package app.absentmotion.library

import app.absentmotion.effects.Palettes
import app.absentmotion.ui.editor.panels.canKeep
import app.absentmotion.ui.editor.panels.swatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PaletteLibraryTest {
    @get:Rule val folder = TemporaryFolder()

    private val sample = listOf(0xe5484d, 0x30a46c, 0x3e63dd, 0x000000, 0xffffff, 0x808080, 0x0f380f)

    @Test fun gimpPaletteRoundTrip() {
        val text = Palettes.gpl("My palette\nsecond line", sample)
        assertTrue(text.startsWith("GIMP Palette\nName: My palette second line\n"))
        assertEquals(sample, Palettes.fromFile("mine.gpl", text.toByteArray()))
    }

    @Test fun swatchExchangeRoundTrip() {
        val bytes = Palettes.ase(sample)
        assertEquals("ASEF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(sample, Palettes.fromFile("mine.ase", bytes))
        assertEquals(listOf(0x123456), Palettes.fromFile("one.ase", Palettes.ase(listOf(0xff123456.toInt()))))
    }

    @Test fun pictureGivesItsMainColours() {
        val quarters = intArrayOf(0xffe5484d.toInt(), 0xff30a46c.toInt(), 0xff3e63dd.toInt(), 0xfff5d90a.toInt())
        val pixels = IntArray(64 * 64) { i -> quarters[(i / 64 / 32) * 2 + (i % 64) / 32] }
        assertEquals(quarters.map { it and 0xFFFFFF }, Palettes.fromPixels(pixels, Palettes.MAIN_COLORS))

        val gradient = IntArray(256 * 64) { i -> val x = i % 256; (0xFF shl 24) or (x shl 16) or ((255 - x) shl 8) or (i / 256 * 4) }
        val main = Palettes.fromPixels(gradient, Palettes.MAIN_COLORS)
        assertEquals(Palettes.MAIN_COLORS, main.size)
        assertEquals(main, Palettes.fromPixels(gradient.copyOf(), Palettes.MAIN_COLORS))
        assertEquals(main, main.sortedBy { (it shr 16 and 255) * 299 + (it shr 8 and 255) * 587 + (it and 255) * 114 })
        assertTrue(Palettes.fromPixels(gradient).size in 9..Palettes.MAX_PICTURE_COLORS)
    }

    @Test fun swatchStripPicture() {
        val colors = List(20) { it * 0x0a0b0c }
        val (pixels, width, height) = Palettes.strip(colors, cell = 4)
        assertEquals(64, width)
        assertEquals(8, height)
        assertEquals(colors[17] or (0xFF shl 24), pixels[5 * width + 1 * 4 + 2])
        assertEquals(0, pixels[7 * width + 63])
        assertEquals(Triple(4, 4, 1), Palettes.strip(listOf(0xff0000), cell = 4).let { Triple(it.second, it.third, it.first.distinct().size) })
    }

    @Test fun anExportedStripIsReadBackExactly() {
        val many = List(512) { it * 0x7f7f }
        for (colors in listOf(List(20) { it * 0x0a0b0c }, List(16) { 0x102030 * (it % 3) }, listOf(0xff0000, 0x00ff00, 0x0000ff), many)) {
            val (pixels, width, height) = Palettes.strip(colors)
            assertEquals(colors, Palettes.fromStrip(pixels, width, height))
        }
        val (full, width, height) = Palettes.strip(many)
        val half = IntArray(width / 2 * height / 2) { i -> full[(i / (width / 2)) * 2 * width + (i % (width / 2)) * 2] }
        assertEquals(many, Palettes.fromStrip(half, width / 2, height / 2))
        assertNull(Palettes.fromStrip(IntArray(1024 * 64) { (0xFF shl 24) or (it * 977) }, 1024, 64))
        val quarters = intArrayOf(0xffe5484d.toInt(), 0xff30a46c.toInt(), 0xff3e63dd.toInt(), 0xfff5d90a.toInt())
        assertNull(Palettes.fromStrip(IntArray(128 * 128) { i -> quarters[(i / 128 / 64) * 2 + (i % 128) / 64] }, 128, 128))
    }

    @Test fun builtInPalettesKeepColoursInSavedColours() {
        val library = PaletteLibrary(File(folder.root, PaletteLibrary.FILE))
        val saved = listOf(0x123456)
        library.choose("builtin.pastel")
        assertTrue(library.canKeep(0x654321, saved))
        assertFalse(library.canKeep(0x123456, saved))
        assertFalse(library.canKeep(library.chosen.colors.first(), saved))
        assertEquals(library.chosen.colors + saved, library.swatches(saved))
        val mine = library.create("Mine", listOf(0x111111))
        library.choose(mine.id)
        assertEquals(listOf(0x111111), library.swatches(saved))
        assertTrue(library.canKeep(0x654321, saved))
        library.put(mine.locking(true))
        assertFalse(library.canKeep(0x654321, saved))
    }

    @Test fun sortsByHueLightnessAndSaturation() {
        val red = 0xff0000
        val green = 0x00c000
        val blue = 0x0000ff
        val grey = 0x808080
        val pink = 0xff8080
        val colors = listOf(blue, pink, grey, green, red)
        assertEquals(listOf(grey, red, pink, green, blue), sortColors(colors, PaletteSort.Hue))
        assertEquals(listOf(blue, red, green, grey, pink), sortColors(colors, PaletteSort.Lightness))
        assertEquals(listOf(grey, pink, blue, red, green), sortColors(colors, PaletteSort.Saturation))
    }

    @Test fun lockStopsAddingAndDeletingColours() {
        val open = ColourPalette("p", "Mine", listOf(0x111111, 0x222222))
        assertEquals(listOf(0x111111, 0x222222, 0x333333), open.added(0xff333333.toInt()).colors)
        assertEquals(listOf(0x222222), open.deleted(0).colors)
        assertEquals(listOf(0x111111, 0x111111, 0x222222), open.duplicated(0).colors)
        assertEquals(open, open.deleted(5))

        val locked = open.locking(true)
        assertTrue(locked.locked)
        assertFalse(locked.open)
        assertFalse(locked.removable)
        assertEquals(locked, locked.added(0x333333))
        assertEquals(locked, locked.deleted(0))
        assertEquals(locked, locked.duplicated(0))
        assertEquals(listOf(0x111111, 0x222222), locked.copy(colors = listOf(0x222222, 0x111111)).sorted(PaletteSort.Lightness).colors)
        assertEquals("Renamed", locked.renamed("Renamed").name)
        assertTrue(locked.locking(false).open)
    }

    @Test fun builtInsCannotBeRemovedOrChanged() {
        val library = PaletteLibrary(File(folder.root, PaletteLibrary.FILE))
        assertTrue(BuiltInPalettes.size > 3)
        assertEquals(PaletteLibrary.DEFAULT, library.chosen.id)
        for (p in BuiltInPalettes) {
            assertTrue(p.builtIn && !p.editable && !p.open && !p.removable)
            assertFalse(library.remove(p.id))
            assertEquals(p, p.added(0x123456).deleted(0).duplicated(0).sorted(PaletteSort.Hue).renamed("x").locking(false))
            assertFalse(library.put(p.copy(builtIn = false, colors = emptyList())))
            assertEquals(p, library[p.id])
        }
        assertEquals(BuiltInPalettes.size, library.all.size)
        assertFalse(File(folder.root, PaletteLibrary.FILE).exists())
    }

    @Test fun jsonStorageRoundTrip() {
        val file = File(folder.root, PaletteLibrary.FILE)
        val library = PaletteLibrary(file)
        val made = library.create("Sunset", listOf(0xff5e3a, 0xffd23f, 0x3b1f2b))
        library.put(made.locking(true))
        val other = library.create("Sea", listOf(0x003f5c))
        library.choose(made.id)

        val again = PaletteLibrary(file)
        assertEquals(library.user, again.user)
        assertEquals(made.id, again.chosenId)
        assertTrue(again[made.id]!!.locked)
        assertEquals(listOf("Sunset", "Sea"), again.user.map { it.name })

        assertFalse(again.remove(made.id))
        assertTrue(again.remove(other.id))
        again.put(again[made.id]!!.locking(false))
        assertTrue(again.remove(made.id))
        assertEquals(PaletteLibrary.DEFAULT, again.chosenId)
        assertEquals(emptyList<ColourPalette>(), PaletteLibrary(file).user)
    }

    @Test fun anUnreadableFileIsKeptAsideNotOverwritten() {
        val file = File(folder.root, PaletteLibrary.FILE)
        file.writeText("{\"palettes\": [")
        val library = PaletteLibrary(file)
        library.create("Fresh", listOf(0x123456))
        assertEquals("{\"palettes\": [", File(file.path + ".unreadable").readText())
        assertEquals(listOf("Fresh"), PaletteLibrary(file).user.map { it.name })
    }

    @Test fun mergeKeepsLocalPalettesAndIgnoresBadEntries() {
        val library = PaletteLibrary(File(folder.newFolder("a"), PaletteLibrary.FILE))
        val mine = library.create("Mine", listOf(0x010203))
        val text = """{"version":1,"chosen":"x","palettes":[
            {"id":"${mine.id}","name":"Changed","colors":["#ffffff"]},
            {"id":"${PaletteLibrary.DEFAULT}","name":"Fake","colors":["#ffffff"]},
            {"id":"new","name":"New","locked":true,"colors":["#0a0b0c","oops","#d0e0f0"]},
            {"name":"No id","colors":[]}, 5]}"""
        assertEquals(1, library.merge(text))
        assertEquals("Mine", library[mine.id]!!.name)
        assertEquals(listOf(0x0a0b0c, 0xd0e0f0), library["new"]!!.colors)
        assertTrue(library["new"]!!.locked)
        assertEquals(0, library.merge(text))
        assertEquals(0, library.merge("not json"))

        File(folder.root, "b").mkdirs()
        val broken = File(folder.root, "b/" + PaletteLibrary.FILE).apply { writeText("{broken") }
        assertEquals(emptyList<ColourPalette>(), PaletteLibrary(broken).user)
    }

    @Test fun backupCarriesPalettes() {
        val root = folder.newFolder("root")
        assertNull(Backup.files(root, includeMedia = false).firstOrNull { it.first == PaletteLibrary.FILE })
        PaletteLibrary(File(root, PaletteLibrary.FILE)).create("Kept", listOf(0x445566))
        assertEquals(File(root, PaletteLibrary.FILE), Backup.files(root, includeMedia = false).first { it.first == PaletteLibrary.FILE }.second)
    }
}
