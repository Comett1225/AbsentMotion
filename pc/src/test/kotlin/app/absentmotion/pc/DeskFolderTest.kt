package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskLibrary
import app.absentmotion.model.Scene
import app.absentmotion.ui.editor.groupByFolder
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DeskFolderTest {
    private val images = setOf("jpg", "png", "mp4")

    private fun fresh(name: String): File {
        val data = File("build/folder-data-$name").also { it.deleteRecursively(); it.mkdirs() }
        System.setProperty("am.data", data.absolutePath)
        DeskLibrary.close()
        return data
    }

    private fun media(root: String, vararg folders: String): List<File> {
        val base = File("build/folder-media-$root").also { it.deleteRecursively() }
        return folders.mapIndexed { index, name ->
            File(base, name).also { dir ->
                dir.mkdirs()
                File("../testdata/media").listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(dir.resolve(it.name)) }
                File("../testdata/media/test-image.jpg").copyTo(dir.resolve("beach-${index + 1}.jpg"))
            }.canonicalFile
        }
    }

    @Test fun mediaBelongsToTheProject() {
        fresh("project")
        val (holiday, footage) = media("project", "Holiday", "Footage")
        DeskLibrary.open("a", emptyList())
        DeskLibrary.link(holiday)
        assertTrue(DeskLibrary.files(images).any { it.parentFile == holiday })
        DeskLibrary.open("b", emptyList())
        assertTrue(DeskLibrary.files(images).isEmpty())
        assertTrue(DeskLibrary.folders.isEmpty())
        DeskLibrary.link(footage)
        assertTrue(DeskLibrary.files(images).all { it.parentFile == footage })
        DeskLibrary.open("a", emptyList())
        assertEquals(listOf(holiday), DeskLibrary.folders)
        DeskLibrary.close()
    }

    @Test fun pinnedFoldersShowEverywhere() {
        fresh("pinned")
        val (holiday, footage) = media("pinned", "Holiday", "Footage")
        DeskLibrary.open("a", emptyList())
        DeskLibrary.link(holiday)
        DeskLibrary.link(footage)
        DeskLibrary.pin(footage, true)
        assertTrue(DeskLibrary.isPinned(footage))
        DeskLibrary.open("new", emptyList())
        assertEquals(listOf(footage), DeskLibrary.folders)
        assertTrue(DeskLibrary.files(images).all { it.parentFile == footage })
        DeskLibrary.pin(footage, false)
        assertTrue(DeskLibrary.folders.isEmpty())
        DeskLibrary.pin(footage, true)
        DeskLibrary.unlink(footage)
        assertFalse(DeskLibrary.isPinned(footage))
        DeskLibrary.open("a", emptyList())
        assertEquals(setOf(holiday, footage), DeskLibrary.folders.toSet())
        DeskLibrary.close()
    }

    @Test fun pickedFilesAndUsedFiles() {
        fresh("picked")
        val (holiday) = media("picked", "Holiday")
        val one = holiday.resolve("test-image.jpg")
        val two = holiday.resolve("test-alpha.png")
        DeskLibrary.open("a", emptyList())
        DeskLibrary.remember(listOf(one))
        assertEquals(listOf(one), DeskLibrary.files(images))
        DeskLibrary.open("b", emptyList())
        assertTrue(DeskLibrary.files(images).isEmpty())
        DeskLibrary.open("c", listOf(two.toURI().toString()))
        assertEquals(listOf(two), DeskLibrary.files(images))
        DeskLibrary.close()
        DeskLibrary.remember(listOf(one))
        DeskLibrary.open("d", emptyList())
        assertEquals(listOf(one), DeskLibrary.files(images))
        DeskLibrary.close()
    }

    @Test fun oldGlobalListsMigrate() {
        fresh("migrate")
        val (holiday) = media("migrate", "Holiday")
        val old = android.content.SharedPreferences.named("desk_library")
        old.edit().putString("folders", holiday.absolutePath).putString("recent", holiday.resolve("test-image.jpg").absolutePath).commit()
        assertEquals(listOf(holiday), DeskLibrary.pinned)
        DeskLibrary.open("any", emptyList())
        assertEquals(listOf(holiday), DeskLibrary.folders)
        assertTrue(old.getString("recent", null) == null && old.getString("folders", null) == null)
        DeskLibrary.close()
    }

    @Test fun searchGroupsFilesUnderTheirFolder() {
        val (holiday, footage) = media("search", "Holiday", "Footage")
        val files = (holiday.listFiles().orEmpty().toList() + footage.listFiles().orEmpty().toList()).filter { it.isFile }.sortedBy { it.name }
        val groups = groupByFolder(files, "beach", listOf(holiday, footage), { it }, { arrayOf(it.name) })
        assertEquals(listOf(footage, holiday), groups.map { it.folder })
        assertEquals(listOf("beach-2.jpg"), groups[0].items.map { it.name })
        assertEquals(listOf("beach-1.jpg"), groups[1].items.map { it.name })
        val byName = groupByFolder(files, "holiday", listOf(holiday, footage), { it }, { arrayOf(it.name) })
        assertEquals(listOf(holiday), byName.map { it.folder })
        assertTrue(byName[0].items.isEmpty())
        assertTrue(groupByFolder(files, "zzz", listOf(holiday), { it }, { arrayOf(it.name) }).isEmpty())
    }

    private fun ComposeUiTest.shot(dir: File, name: String) {
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", dir.resolve("$name.png"))
    }

    private fun ComposeUiTest.open(app: DeskAppState): String {
        val id = app.store.create(Scene.parse(File("../testdata/scenes/styles.xml").readText()))
        runBlocking { app.openNow(id) }
        waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
        mainClock.advanceTimeBy(1000)
        return id
    }

    private fun ComposeUiTest.rail(name: String) {
        onAllNodesWithText(name, substring = false).filter(hasClickAction()).onFirst().performClick()
        mainClock.advanceTimeBy(250)
    }

    private fun ComposeUiTest.type(text: String) {
        onNode(hasSetTextAction() and hasText("Search photos and videos", substring = true).or(hasSetTextAction() and hasText(text.take(1), substring = true))).performTextInput(text)
        mainClock.advanceTimeBy(800)
    }

    @Test fun mediaPanelScreens() {
        fresh("ui")
        val (holiday, footage) = media("ui", "Holiday", "Footage")
        val shots = File("build/ui-shots/folders").also { it.mkdirs() }
        for ((width, height) in listOf(1024 to 700, 1280 to 800, 1728 to 1080, 2560 to 1440)) {
            fresh("ui-$width")
            DeskLibrary.pin(holiday, false)
            DeskLibrary.pin(footage, false)
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                val app = DeskAppState()
                setContent { DeskApp(app) }
                open(app)
                if (onAllNodesWithText("Search", substring = true).fetchSemanticsNodes().isEmpty()) rail("Media")
                shot(shots, "$width-1-new-project-nothing")
                onAllNodesWithText("Holiday").assertCountEquals(0)
                runOnIdle { DeskLibrary.link(holiday); DeskLibrary.link(footage); DeskLibrary.pin(footage, true) }
                mainClock.advanceTimeBy(1200)
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Holiday").fetchSemanticsNodes().isNotEmpty() }
                shot(shots, "$width-2-folders")
                onAllNodesWithText("Holiday").onFirst().performMouseInput { rightClick() }
                mainClock.advanceTimeBy(300)
                onNodeWithText("Pin to all projects").assertExists()
                shot(shots, "$width-3-menu")
                onNodeWithText("Pin to all projects").performClick()
                mainClock.advanceTimeBy(1200)
                runOnIdle { assertTrue(DeskLibrary.isPinned(holiday)) }
                onAllNodesWithText("Holiday").onFirst().performClick()
                mainClock.advanceTimeBy(600)
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("beach-1.jpg", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("beach-2.jpg", substring = true).assertCountEquals(0)
                shot(shots, "$width-4-opened")
                runOnIdle { DeskBack.back() }
                mainClock.advanceTimeBy(500)
                type("beach")
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("beach-2.jpg", substring = true).fetchSemanticsNodes().isNotEmpty() }
                shot(shots, "$width-5-search")
                runBlocking { app.closeEditor() }
                mainClock.advanceTimeBy(800)
                open(app)
                if (onAllNodesWithText("Search", substring = true).fetchSemanticsNodes().isEmpty()) rail("Media")
                mainClock.advanceTimeBy(1200)
                onAllNodesWithText("Footage").onFirst().assertExists()
                onAllNodesWithText("Holiday").onFirst().assertExists()
                shot(shots, "$width-6-another-project-pinned")
                runBlocking { app.closeEditor() }
            }
        }
    }
}
