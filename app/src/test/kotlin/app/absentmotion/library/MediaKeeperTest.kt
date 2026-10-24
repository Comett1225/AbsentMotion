package app.absentmotion.library

import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.mediaUris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaKeeperTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun media(file: File, uri: String = file.toURI().toString()) =
        MediaDeclaration(XmlNode("media", linkedMapOf("uri" to uri, "filename" to file.name, "size" to "${file.length()}", "type" to "image/png")))

    private fun scene(vararg uris: String) = Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10"),
        uris.mapIndexed { i, uri -> XmlNode("shape", linkedMapOf("id" to "${i + 1}", "fillImage" to uri)) }))

    @Test fun copiesIntoTheProjectFolderSoTheOriginalCanGo() {
        val store = ProjectStore(tmp.newFolder())
        val photo = tmp.newFile("photo.png").apply { writeText("pixels") }
        val keeper = MediaKeeper(store, { File(java.net.URI(it)).inputStream() })
        val kept = keeper.keep("p1", media(photo))
        assertTrue(store.inMediaFolder(kept.uri))
        photo.delete()
        assertEquals("pixels", File(java.net.URI(kept.uri)).readText())
        assertEquals(kept.uri, keeper.keep("p1", kept).uri)
        val again = tmp.newFile("photo.png").apply { writeText("pixels") }
        val scene = scene(kept.uri).withMedia(kept)
        assertEquals(kept.uri, keeper.keep("p1", media(again), scene).uri)
        assertEquals(1, store.mediaDir("p1").listFiles()!!.size)
    }

    @Test fun aDifferentFileWithTheSameNameAndSizeGetsItsOwnCopy() {
        val store = ProjectStore(tmp.newFolder())
        val keeper = MediaKeeper(store, { File(java.net.URI(it)).inputStream() })
        val first = File(tmp.newFolder("one"), "take.wav").apply { writeText("first take") }
        val kept = keeper.keep("p1", media(first))
        val second = File(tmp.newFolder("two"), "take.wav").apply { writeText("other take") }
        val scene = scene(kept.uri).withMedia(kept)
        val again = keeper.keep("p1", media(second), scene)
        assertTrue(again.uri != kept.uri)
        assertEquals("other take", File(java.net.URI(again.uri)).readText())
        assertEquals("first take", File(java.net.URI(kept.uri)).readText())
        val collected = keeper.collect("p1", scene(kept.uri, second.toURI().toString()).withMedia(kept).withMedia(media(second)))
        val uris = collected.scene.node.mediaUris()
        assertEquals(2, uris.size)
        assertEquals(setOf("first take", "other take"), uris.map { File(java.net.URI(it)).readText() }.toSet())
    }

    @Test fun refusesWhenStorageIsLow() {
        val store = ProjectStore(tmp.newFolder())
        val photo = tmp.newFile("big.png").apply { writeText("x") }
        val keeper = MediaKeeper(store, { File(java.net.URI(it)).inputStream() }, free = { MediaKeeper.RESERVE })
        try {
            keeper.keep("p1", media(photo))
            fail()
        } catch (e: MediaKeeper.NoSpace) {
            assertTrue(e.needed > MediaKeeper.RESERVE)
        }
        assertFalse(store.mediaDir("p1").listFiles().orEmpty().any { it.isFile })
    }

    @Test fun collectCopiesEverythingItCanReadAndCountsTheRest() {
        val store = ProjectStore(tmp.newFolder())
        val a = tmp.newFile("a.png").apply { writeText("a") }
        val b = tmp.newFile("b.png").apply { writeText("b") }
        val gone = File(tmp.root, "gone.png")
        val scene = scene(a.toURI().toString(), b.toURI().toString(), gone.toURI().toString())
            .withMedia(media(a)).withMedia(media(b)).withMedia(MediaDeclaration(XmlNode("media", linkedMapOf("uri" to gone.toURI().toString(), "filename" to "gone.png"))))
        val result = MediaKeeper(store, { File(java.net.URI(it)).inputStream() }).collect("p1", scene)
        assertEquals(2, result.copied)
        assertEquals(1, result.unreadable)
        assertFalse(result.noSpace)
        val uris = result.scene.node.mediaUris()
        assertEquals(2, uris.count(store::inMediaFolder))
        assertTrue(gone.toURI().toString() in uris)
        assertEquals(setOf("a.png", "b.png"), store.mediaDir("p1").listFiles()!!.map { it.name }.toSet())
    }

    private fun sized(file: File, size: Long = file.length()) =
        MediaDeclaration(XmlNode("media", linkedMapOf("uri" to file.toURI().toString(), "filename" to file.name, "size" to "$size", "type" to "video/mp4")))

    @Test fun aCopyThatEndsShortIsRefusedAndLeavesNothingBehind() {
        val store = ProjectStore(tmp.newFolder())
        val clip = tmp.newFile("clip.mp4").apply { writeText("only ten b") }
        try {
            MediaKeeper(store, { File(java.net.URI(it)).inputStream() }).keep("p1", sized(clip, size = 1_000))
            fail("kept a short copy")
        } catch (e: java.io.IOException) {
            assertFalse(e is MediaKeeper.NoSpace)
        }
        assertTrue(store.mediaDir("p1").listFiles().orEmpty().isEmpty())
    }

    @Test fun leavingMidCopyStopsItAndLeavesNoFile() {
        val store = ProjectStore(tmp.newFolder())
        val clip = tmp.newFile("clip.mp4").apply { writeBytes(ByteArray(1 shl 20) { 7 }) }
        var reads = 0
        val keeper = MediaKeeper(store, { File(java.net.URI(it)).inputStream() })
        try {
            keeper.keep("p1", sized(clip), cancelled = { ++reads > 3 })
            fail("finished the copy")
        } catch (e: java.util.concurrent.CancellationException) {
        }
        assertTrue(store.mediaDir("p1").listFiles().orEmpty().isEmpty())
    }

    @Test fun aFileDeletedFromTheProjectAndAddedAgainIsNotCopiedTwice() {
        val store = ProjectStore(tmp.newFolder())
        val keeper = MediaKeeper(store, { File(java.net.URI(it)).inputStream() })
        val take = File(tmp.newFolder("one"), "take.wav").apply { writeText("first take") }
        val kept = keeper.keep("p1", media(take))
        val again = keeper.keep("p1", media(take), scene())
        assertEquals(kept.uri, again.uri)
        val other = File(tmp.newFolder("two"), "take.wav").apply { writeText("other take") }
        val third = keeper.keep("p1", media(other), scene())
        assertTrue(third.uri != kept.uri)
        assertEquals("other take", File(java.net.URI(third.uri)).readText())
        assertEquals(setOf("take.wav", "2_take.wav"), store.mediaDir("p1").list()!!.toSet())
    }

    @Test fun twoPicksWithTheSameNameAtTheSameTimeKeepTheirOwnContent() {
        val store = ProjectStore(tmp.newFolder())
        val first = File(tmp.newFolder("one"), "take.wav").apply { writeBytes(ByteArray(300_000) { 'a'.code.toByte() }) }
        val second = File(tmp.newFolder("two"), "take.wav").apply { writeBytes(ByteArray(400_000) { 'b'.code.toByte() }) }
        val started = java.util.concurrent.CountDownLatch(1)
        val secondDone = java.util.concurrent.CountDownLatch(1)
        val keeper = MediaKeeper(store, { uri ->
            val file = File(java.net.URI(uri))
            if (file != first) file.inputStream() else object : java.io.FilterInputStream(file.inputStream()) {
                private var waited = false
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, minOf(len, 65_536))
                    if (!waited) { waited = true; started.countDown(); secondDone.await(10, java.util.concurrent.TimeUnit.SECONDS) }
                    return n
                }
            }
        })
        val results = java.util.concurrent.ConcurrentHashMap<String, MediaDeclaration>()
        val a = Thread { results["first"] = keeper.keep("p1", media(first)) }.apply { start() }
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
        results["second"] = keeper.keep("p1", media(second))
        secondDone.countDown()
        a.join(10_000)
        val one = File(java.net.URI(results.getValue("first").uri))
        val two = File(java.net.URI(results.getValue("second").uri))
        assertTrue(one != two)
        assertEquals(300_000L, one.length())
        assertEquals(400_000L, two.length())
        assertTrue(one.readBytes().all { it == 'a'.code.toByte() })
        assertTrue(two.readBytes().all { it == 'b'.code.toByte() })
        assertEquals(2, store.mediaDir("p1").listFiles()!!.size)
    }

    @Test fun collectingMediaKeepsAnOriginalThatAnAiRestoreActionStillNeeds() {
        val store = ProjectStore(tmp.newFolder())
        val original = tmp.newFile("original.wav").apply { writeText("original audio") }
        val result = store.mediaDir("p1").apply { mkdirs() }.resolve("clean.wav").apply { writeText("clean audio") }
        val scene = Scene.parse("""<scene width="10" height="10"><media uri="${result.toURI()}"/><audio id="1" src="${result.toURI()}"><ai type="noise" original="${original.toURI()}" result="${result.toURI()}"><media uri="${original.toURI()}" filename="original.wav" size="${original.length()}"/></ai></audio></scene>""")
        val keeper = MediaKeeper(store, { File(java.net.URI(it)).inputStream() })
        val collected = keeper.collect("p1", scene)
        assertEquals(1, collected.copied)
        val restored = collected.scene.layer(1)!!.node.child("ai")!!["original"]!!
        assertTrue(store.inMediaFolder(restored))
        assertEquals("original audio", File(java.net.URI(restored)).readText())
    }
}
