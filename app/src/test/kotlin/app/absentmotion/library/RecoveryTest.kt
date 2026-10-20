package app.absentmotion.library

import app.absentmotion.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecoveryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun xml(title: String, stamp: Long = 1) = "<scene width=\"10\" height=\"10\" title=\"$title\" modifiedTime=\"$stamp\" />"

    private fun setup(): Triple<ProjectStore, Recovery, String> {
        val root = tmp.newFolder()
        val store = ProjectStore(root)
        val id = store.create(Scene.parse(xml("saved")))
        return Triple(store, Recovery(root.resolve("recovery")), id)
    }

    @Test fun restoreBringsBackTheUnsavedEditsAndKeepsTheSavedOne() {
        val (store, recovery, id) = setup()
        recovery.write(id, xml("unsaved"))
        val offer = recovery.pending(store)!!
        assertEquals(id, offer.id)
        assertEquals("unsaved", offer.title)
        recovery.restore(store, offer)
        assertEquals("unsaved", store.load(id)!!.title)
        assertTrue(store.versions.list(id).any { Scene.parse(it.file.readText()).title == "saved" })
        assertNull(recovery.pending(store))
    }

    @Test fun discardKeepsTheEditsAsAVersion() {
        val (store, recovery, id) = setup()
        recovery.write(id, xml("unsaved"))
        recovery.discard(store, recovery.pending(store)!!)
        assertEquals("saved", store.load(id)!!.title)
        assertEquals("unsaved", Scene.parse(store.versions.list(id).first().file.readText()).title)
        assertNull(recovery.pending(store))
    }

    @Test fun nothingIsOfferedWhenTheProjectAlreadyHasTheEdits() {
        val (store, recovery, id) = setup()
        store.save(id, xml("same", 5))
        recovery.write(id, xml("same", 9))
        assertNull(recovery.pending(store))
        assertFalse(recovery.file(id).exists())
        recovery.write(id, "<scene")
        assertNull(recovery.pending(store))
    }

    @Test fun crashHandlerWritesTheOpenSceneAndPassesTheCrashOn() {
        val (store, recovery, id) = setup()
        var passedOn: Throwable? = null
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> passedOn = e }
        try {
            val handler = RecoveryHandler.install(recovery) { id to xml("in memory") }
            assertSame(handler, RecoveryHandler.install(recovery) { id to xml("in memory") })
            val crash = IllegalStateException("boom")
            handler.uncaughtException(Thread.currentThread(), crash)
            assertSame(crash, passedOn)
            assertEquals("in memory", recovery.pending(store)!!.title)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test fun saverKeepsGestureDraftsUntilTheEditIsCommitted() {
        val (store, recovery, id) = setup()
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            val saver = ProjectSaver(id, store, recovery, failed = { throw AssertionError(it) }, executor = executor)
            saver.draft(xml("dragging"))
            saver.flush().get(5, TimeUnit.SECONDS)
            executor.submit { }.get(5, TimeUnit.SECONDS)
            assertEquals("dragging", recovery.pending(store)!!.title)
            saver.commit(xml("dropped"))
            saver.flush().get(5, TimeUnit.SECONDS)
            executor.submit { }.get(5, TimeUnit.SECONDS)
            assertFalse(recovery.file(id).exists())
            assertEquals("dropped", store.load(id)!!.title)
        } finally {
            executor.shutdownNow()
        }
    }
}
