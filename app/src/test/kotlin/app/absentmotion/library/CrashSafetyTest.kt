package app.absentmotion.library

import app.absentmotion.model.Scene
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CrashSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 24 * 60 * 60 * 1000L
    private val now = System.currentTimeMillis()

    private fun File.written(text: String = "x", age: Long = 0) = apply { parentFile.mkdirs(); writeText(text); setLastModified(now - age) }

    @Test fun aSaveCutOffHalfWayLeavesTheLastGoodProjectAndTheSweepClearsTheRest() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val scene = Scene.create("Kept", 100, 100, 30, 0)
        val id = store.create(scene)
        File(root, "projects/$id.xml.tmp").written("<scene title=\"half", age = 2 * day)
        File(root, "projects/versions/$id/123.xml.tmp").written(age = 2 * day)
        File(root, "recovery/$id.xml.tmp").written(age = 2 * day)
        val fresh = File(root, "projects/other.xml.tmp").written()
        assertEquals(listOf("Kept"), store.list(includeDamaged = true).map { it.title })
        assertEquals("Kept", store.load(id)!!.title)
        assertEquals(3, sweepStaleTemps(root, tmp.newFolder(), now))
        assertTrue(fresh.exists())
        assertEquals("Kept", store.load(id)!!.title)
    }

    @Test fun aFailedWriteNeverDamagesTheProjectOnDisk() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val id = store.create(Scene.create("Before", 100, 100, 30, 0))
        File(root, "projects/$id.xml.tmp").mkdirs()
        try {
            store.save(id, Scene.create("After", 100, 100, 30, 0))
            fail("the write must fail")
        } catch (e: IOException) {
        }
        assertEquals("Before", store.load(id)!!.title)
    }

    @Test fun creatingAProjectWithoutRoomFailsLoudlyAndLeavesNothing() {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val projects = File(root, "projects")
        assertTrue(projects.setWritable(false))
        try {
            org.junit.Assume.assumeFalse(projects.canWrite())
            store.create(Scene.create("New", 100, 100, 30, 0))
            fail("creating must report the failure")
        } catch (e: IOException) {
        } finally {
            projects.setWritable(true)
        }
        assertTrue(projects.listFiles { f -> f.isFile }.orEmpty().isEmpty())
    }

    @Test fun anImportStoppedByAKillIsRemovedLaterButFinishedOnesAreKept() {
        val root = tmp.newFolder()
        val cache = tmp.newFolder()
        val killed = File(root, "media/p1").also { File(it, ImportMarker).written(age = 2 * day); File(it, "clip.mp4").written(age = 2 * day) }
        val finished = File(root, "media/p2").also { File(it, ImportMarker).written(age = 2 * day); File(it, "clip.mp4").written(age = 2 * day) }
        File(root, "projects/p2.xml").written("<scene/>")
        val running = File(root, "media/p3").also { File(it, ImportMarker).written(); File(it, "clip.mp4").written() }
        val kept = File(root, "media/p4/keep-1.part").written(age = 2 * day)
        val model = File(root, "ai/model.bin.part").written(age = 2 * day)
        val waveform = File(cache, "waveforms/a.bin").written(age = 2 * day)
        val oldPcm = File(cache, "ai-input-x.pcm").written(age = 2 * day)
        val newProbe = File(cache, "probe-1.mp4").written()
        sweepStaleTemps(root, cache, now)
        assertFalse(killed.exists())
        assertTrue(File(finished, "clip.mp4").exists())
        assertFalse(File(finished, ImportMarker).exists())
        assertTrue(File(running, ImportMarker).exists())
        assertFalse(kept.exists())
        assertTrue(model.exists())
        assertTrue(waveform.exists())
        assertFalse(oldPcm.exists())
        assertTrue(newProbe.exists())
    }

    @Test fun aFinishedPackageImportLeavesNoMarker() {
        val store = ProjectStore(tmp.newFolder())
        val scene = """<scene title="p" width="100" height="100" fps="30"><media uri="content://a" filename="a.png"/><shape id="1" startTime="0" endTime="999" fillType="media" fillImage="content://a"/></scene>"""
        val manifest = JSONObject().put("format", "absentmotion-package").put("version", 1)
            .put("media", JSONArray().put(JSONObject().put("uri", "content://a").put("entry", "media/1_a.png")))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in listOf("project.xml" to scene.toByteArray(), "media/1_a.png" to "png".toByteArray(), "manifest.json" to manifest.toString().toByteArray())) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
        }
        val report = Importer(store).import { ByteArrayInputStream(out.toByteArray()) }.single()
        val folder = store.mediaDir(report.id)
        assertEquals(listOf("1_a.png"), folder.list()!!.toList())
    }
}
