package app.absentmotion.library

import app.absentmotion.i18n.AppText
import app.absentmotion.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.ZoneOffset

class ProjectVersionsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = LocalDateTime.of(2026, 10, 20, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun xml(title: String, stamp: Long = 1) = "<scene width=\"10\" height=\"10\" title=\"$title\" modifiedTime=\"$stamp\" />"

    @Test fun keepsTheNewestTwentyPlusOnePerDayForAWeek() {
        val recent = (0 until 25).map { now - it * 60_000L }
        val older = (1..10).flatMap { d -> listOf(now - d * day, now - d * day - hour) }
        val kept = retained(recent + older, now, ZoneOffset.UTC)
        assertEquals(recent.take(20).toSet(), kept.filter { it > now - day / 2 }.toSet())
        assertEquals((1..6).map { now - it * day }.toSet(), kept.filter { it <= now - day / 2 }.toSet())
    }

    @Test fun pruningDeletesOldSnapshotsAndTheirThumbnails() {
        var clock = now - 40 * day
        val versions = ProjectVersions(tmp.newFolder(), { clock }, { ZoneOffset.UTC })
        repeat(30) { i ->
            clock += day
            assertTrue(versions.snapshot("p1", xml("v$i")))
            versions.list("p1").first().thumbnail.writeText("png")
        }
        val left = versions.list("p1")
        assertEquals(20, left.size)
        assertEquals(left.size, versions.dir("p1").listFiles { f -> f.extension == "png" }!!.size)
        assertEquals("v29", Regex("title=\"([^\"]*)\"").find(left.first().file.readText())!!.groupValues[1])
    }

    @Test fun skipsUnchangedScenesAndRespectsTheGap() {
        var clock = now
        val versions = ProjectVersions(tmp.newFolder(), { clock }, { ZoneOffset.UTC })
        assertTrue(versions.snapshot("p1", xml("a", 1)))
        clock += 5_000
        assertFalse(versions.snapshot("p1", xml("a", 2)))
        assertFalse(versions.snapshot("p1", xml("b"), minGapMs = 120_000))
        clock += 120_000
        assertTrue(versions.snapshot("p1", xml("b"), minGapMs = 120_000))
        assertFalse(versions.snapshot("p1", " "))
        assertEquals(2, versions.list("p1").size)
    }

    private fun titles(versions: ProjectVersions, id: String) =
        versions.list(id).map { Regex("title=\"([^\"]*)\"").find(it.file.readText())!!.groupValues[1] }

    @Test fun aNewestVersionDatedInTheFutureDoesNotStopTheHistory() {
        var clock = now + 30 * day
        val versions = ProjectVersions(tmp.newFolder(), { clock }, { ZoneOffset.UTC })
        assertTrue(versions.snapshot("p1", xml("future")))
        clock = now
        assertTrue(versions.snapshot("p1", xml("fixed clock")))
        assertFalse(versions.snapshot("p1", xml("too soon"), minGapMs = 120_000))
        assertFalse(versions.snapshot("p1", xml("fixed clock", 2)))
        clock += 120_000
        assertTrue(versions.snapshot("p1", xml("later"), minGapMs = 120_000))
        assertEquals(listOf("later", "fixed clock", "future"), titles(versions, "p1"))
    }

    @Test fun restoringWhileTheNewestVersionIsDatedInTheFutureKeepsTheCurrentState() {
        val store = ProjectStore(tmp.newFolder())
        val id = store.create(app.absentmotion.model.Scene.parse(xml("first")))
        val future = System.currentTimeMillis() + 30 * day
        store.versions.dir(id).apply { mkdirs() }.resolve("$future.xml").writeText(xml("future"))
        store.save(id, xml("current"))
        store.restore(id, store.versions.list(id).single())
        assertEquals("future", store.load(id)!!.title)
        assertEquals(listOf("current", "future"), titles(store.versions, id))
    }

    @Test fun restoringKeepsTheCurrentStateAsAVersion() {
        val store = ProjectStore(tmp.newFolder())
        val id = store.create(app.absentmotion.model.Scene.parse(xml("first")))
        store.versions.snapshot(id, store.text(id)!!)
        val first = store.versions.list(id).single()
        store.save(id, xml("second"))
        store.restore(id, first)
        assertEquals("first", store.load(id)!!.title)
        val titles = store.versions.list(id).map { app.absentmotion.model.Scene.parse(it.file.readText()).title }
        assertEquals(listOf("second", "first"), titles)
        store.delete(id)
        assertFalse(store.versions.dir(id).exists())
    }

    @Test fun damagedProjectsStayListedWithTheirTitle() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        store.create(app.absentmotion.model.Scene.parse(xml("fine")))
        root.resolve("projects/p9.xml").writeText("<?xml version='1.0' ?>\n<scene width=\"10\" title=\"Holiday &amp; Beach\" height=\"1")
        root.resolve("projects/p8.xml").writeText("")
        assertEquals(listOf("fine"), store.list().map { it.title })
        val all = store.list(includeDamaged = true).associateBy { it.id }
        assertEquals(3, all.size)
        assertTrue(all.getValue("p9").damaged)
        assertEquals("Holiday & Beach", all.getValue("p9").title)
        assertEquals(AppText.get(R.string.library_damaged_project), all.getValue("p8").title)
        assertFalse(all.values.single { it.title == "fine" }.damaged)
    }
}
