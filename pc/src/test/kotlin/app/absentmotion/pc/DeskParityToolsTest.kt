package app.absentmotion.pc

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import app.absentmotion.R
import app.absentmotion.audio.DeviceAudio
import app.absentmotion.audio.DeskOutput
import app.absentmotion.desk.DeskApp
import app.absentmotion.desk.DeskBack
import app.absentmotion.desk.DeskAppState
import app.absentmotion.desk.DeskMedia
import app.absentmotion.desk.DeskOs
import app.absentmotion.desk.Strings
import app.absentmotion.model.Scene
import app.absentmotion.ui.camera.CameraDevice
import app.absentmotion.ui.camera.CameraInput
import app.absentmotion.ui.camera.CameraPhase
import app.absentmotion.ui.camera.DeskCameraController
import app.absentmotion.ui.camera.DeskCameraSource
import app.absentmotion.ui.camera.JpegSplitter
import app.absentmotion.ui.home.ApkAsset
import app.absentmotion.ui.home.DeskStep
import app.absentmotion.ui.home.Release
import app.absentmotion.ui.home.UpdateFailure
import app.absentmotion.ui.home.UpdateSource
import app.absentmotion.ui.home.Updater
import app.absentmotion.ui.home.Updates
import app.absentmotion.ui.home.installerName
import app.absentmotion.ui.home.parseRelease
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class DeskParityToolsTest {
    private val repo = File("..").canonicalFile
    private val shots = File("build/ui-shots/parity-tools").also { it.mkdirs() }
    private val saved = listOf("am.data", "user.home", "am.fakeCamera").associateWith { System.getProperty(it) }

    @org.junit.After fun restoreProperties() {
        saved.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
    }

    private fun prepare(name: String) {
        System.setProperty("am.data", File("build/$name-data").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
        System.setProperty("user.home", File("build/$name-home").also { it.deleteRecursively(); it.mkdirs() }.absolutePath)
    }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        mainClock.advanceTimeBy(600)
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", shots.resolve("$name.png"))
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Local(val server: HttpServer, val payload: ByteArray, var sums: String) {
        val port get() = server.address.port
        val source = object : UpdateSource {
            override val releases = "http://127.0.0.1:$port/releases"
            override val https = false
            override fun asset(tag: String, name: String) = "http://127.0.0.1:$port/$name"
            override fun allows(url: URI, first: Boolean) = url.host == "127.0.0.1"
        }
    }

    private fun local(name: String, honest: Boolean): Local {
        val payload = ByteArray(420_000) { (it * 31 % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val holder = Local(server, payload, "${sha(if (honest) payload else payload.reversedArray())}  $name\n")
        server.createContext("/$name") { ex -> ex.sendResponseHeaders(200, payload.size.toLong()); ex.responseBody.use { it.write(payload) } }
        server.createContext("/SHA256SUMS.txt") { ex -> val body = holder.sums.toByteArray(); ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) } }
        server.start()
        return holder
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!check()) { check(System.currentTimeMillis() < until) { "timed out waiting for $what, step ${Updater.step}" }; Thread.sleep(25) }
    }

    @Test fun anUpdateIsDownloadedCheckedAndOnlyThenOffered() {
        prepare("updater")
        val name = "AbsentMotion-9.0.0.dmg"
        val good = local(name, honest = true)
        try {
            Updates.source = good.source
            val release = Release("9.0.0", "https://github.com/Comett1225/AbsentMotion/releases/tag/v9.0.0", ApkAsset(name, "http://127.0.0.1:${good.port}/$name", good.payload.size.toLong(), null, "http://127.0.0.1:${good.port}/SHA256SUMS.txt"))
            Updater.start(android.content.Desk.context, release)
            waitFor("the verified download") { Updater.step is DeskStep.Ready }
            val ready = Updater.step as DeskStep.Ready
            assertEquals(name, ready.file.name)
            assertTrue(good.payload.contentEquals(ready.file.readBytes()))
            Updater.later()
            assertTrue(DeskStep.Idle == Updater.step)
        } finally { good.server.stop(0) }
        val bad = local(name, honest = false)
        try {
            Updates.source = bad.source
            val release = Release("9.0.1", "https://github.com/Comett1225/AbsentMotion/releases/tag/v9.0.1", ApkAsset(name, "http://127.0.0.1:${bad.port}/$name", bad.payload.size.toLong(), null, "http://127.0.0.1:${bad.port}/SHA256SUMS.txt"))
            Updater.start(android.content.Desk.context, release)
            waitFor("the checksum failure") { Updater.step is DeskStep.Failed }
            assertEquals(UpdateFailure.Checksum, (Updater.step as DeskStep.Failed).failure)
            Updater.later()
            assertTrue(File(android.content.Desk.context.cacheDir, "updates").listFiles().orEmpty().none { it.name.startsWith("update.apk") })
        } finally { bad.server.stop(0); Updates.source = app.absentmotion.ui.home.GitHubSource }
    }

    @Test fun pluralFormsFollowEachLanguage() {
        val s = app.absentmotion.desk.Strings
        assertEquals(listOf("one", "few", "many", "many"), listOf(1, 3, 5, 12).map { s.pluralCategory("pl", it) })
        assertEquals(listOf("one", "few", "many", "one"), listOf(21, 22, 11, 101).map { s.pluralCategory("ru", it) })
        assertEquals(listOf("one", "few", "many", "many"), listOf(1, 3, 5, 11).map { s.pluralCategory("uk", it) })
        assertEquals(listOf("zero", "two", "few", "many", "other"), listOf(0, 2, 7, 11, 100).map { s.pluralCategory("ar", it) })
        assertEquals(listOf("one", "other"), listOf(0, 0).mapIndexed { i, n -> s.pluralCategory(if (i == 0) "fr" else "en", n) })
    }

    @Test fun theRightInstallerIsPickedForTheSystem() {
        val names = listOf("AbsentMotion-5.0.0-foss.apk", "AbsentMotion-5.0.0-macos-arm64.dmg", "AbsentMotion-5.0.0-macos-x64.dmg", "AbsentMotion-5.0.0.msi", "absentmotion_5.0.0-1_amd64.deb", "SHA256SUMS.txt", "Other-5.0.0.dmg", "AbsentMotion-4.9.0.dmg")
        assertEquals("AbsentMotion-5.0.0-macos-arm64.dmg", installerName(names, "5.0.0", "Mac OS X", "aarch64"))
        assertEquals("AbsentMotion-5.0.0-macos-x64.dmg", installerName(names, "5.0.0", "Mac OS X", "x86_64"))
        assertEquals("AbsentMotion-5.0.0.msi", installerName(names, "5.0.0", "Windows 11", "amd64"))
        assertEquals("absentmotion_5.0.0-1_amd64.deb", installerName(names, "5.0.0", "Linux", "amd64", deb = true))
        assertNull(installerName(names + "AbsentMotion-5.0.0-linux-x64.AppImage", "5.0.0", "Linux", "amd64", deb = false))
        assertNull(installerName(listOf("AbsentMotion-5.0.0-foss.apk"), "5.0.0", "Linux", "amd64", deb = true))
        val json = """{"tag_name":"v5.0.0","html_url":"https://github.com/Comett1225/AbsentMotion/releases/tag/v5.0.0","assets":[
            {"name":"AbsentMotion-5.0.0-macos-arm64.dmg","browser_download_url":"https://github.com/Comett1225/AbsentMotion/releases/download/v5.0.0/AbsentMotion-5.0.0-macos-arm64.dmg","size":123456789},
            {"name":"SHA256SUMS.txt","browser_download_url":"https://github.com/Comett1225/AbsentMotion/releases/download/v5.0.0/SHA256SUMS.txt","size":300}]}"""
        val release = parseRelease(json, "desktop")
        assertNotNull(release)
        if (DeskOs.mac && System.getProperty("os.arch") == "aarch64") {
            assertEquals("AbsentMotion-5.0.0-macos-arm64.dmg", release!!.apk!!.name)
            assertEquals("https://github.com/Comett1225/AbsentMotion/releases/download/v5.0.0/SHA256SUMS.txt", release.apk!!.sums)
        }
        assertNull(parseRelease(json.replace("https://github.com/", "https://example.com/"), "desktop")?.apk)
    }

    @Test fun cameraDevicesAndCommandsAreBuiltForEverySystem() {
        val mac = DeskCameraSource.parseAvfoundation("""
            [AVFoundation indev @ 0x1] AVFoundation video devices:
            [AVFoundation indev @ 0x1] [0] FaceTime HD Camera
            [AVFoundation indev @ 0x1] [1] Capture screen 0
            [AVFoundation indev @ 0x1] AVFoundation audio devices:
            [AVFoundation indev @ 0x1] [0] MacBook Pro Microphone
        """.trimIndent())
        assertEquals(listOf("0" to "FaceTime HD Camera"), mac.video.map { it.id to it.name })
        assertEquals(listOf("0" to "MacBook Pro Microphone"), mac.audio.map { it.id to it.name })
        val win = DeskCameraSource.parseDshow("""
            [dshow @ 0000] DirectShow video devices (some may be both video and audio devices)
            [dshow @ 0000]  "Integrated Camera" (video)
            [dshow @ 0000]   Alternative name "@device_pnp_\\?\usb"
            [dshow @ 0000] DirectShow audio devices
            [dshow @ 0000]  "Microphone (Realtek)" (audio)
        """.trimIndent())
        assertEquals(listOf("Integrated Camera"), win.video.map { it.name })
        assertEquals(listOf("Microphone (Realtek)"), win.audio.map { it.name })
        val sys = File("build/fake-sysfs").also { it.deleteRecursively() }
        listOf("video0" to "UVC Camera", "video1" to "UVC Camera").forEachIndexed { i, (node, label) -> File(sys, node).mkdirs(); File(sys, "$node/name").writeText(label); File(sys, "$node/index").writeText("$i") }
        assertEquals(listOf("/dev/video0"), DeskCameraSource.linuxDevices(sys).video.map { it.id })
        val camera = CameraDevice("0", "Cam")
        val mic = CameraDevice("1", "Mic")
        assertEquals(listOf("-f", "avfoundation", "-i", "0:1"), CameraInput(camera, mic, mac = true, windows = false).args())
        assertEquals(listOf("-f", "avfoundation", "-i", "0:none"), CameraInput(camera, null, mac = true, windows = false).args())
        assertEquals(listOf("-f", "dshow", "-rtbufsize", "200M", "-i", "video=Cam:audio=Mic"), CameraInput(CameraDevice("Cam", "Cam"), CameraDevice("Mic", "Mic"), mac = false, windows = true).args())
        assertEquals(listOf("-f", "v4l2", "-i", "0", "-f", "pulse", "-i", "1"), CameraInput(camera, mic, mac = false, windows = false).args())
        assertEquals("1:a", CameraInput(camera, mic, mac = false, windows = false).audioMap)
        assertEquals("0:a", CameraInput(camera, mic, mac = true, windows = false).audioMap)
        val target = File("/tmp/x.mp4")
        val command = DeskCameraSource.command("ffmpeg", CameraInput(camera, mic, mac = true, windows = false), target, DeskCameraSource.videoEncoder(setOf("libx264"), mac = true, windows = false))
        assertTrue(command.containsAll(listOf("pipe:1", "libx264", "aac", target.path, "0:a")))
        assertEquals(listOf("mpeg4"), DeskCameraSource.videoEncoder(emptySet()).filter { it == "mpeg4" })
        val frames = ArrayList<Int>()
        val splitter = JpegSplitter { frames += it.size }
        val one = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val stream = one + byteArrayOf(9, 9) + one + one
        stream.toList().chunked(3).forEach { splitter.feed(it.toByteArray(), it.size) }
        assertEquals(listOf(7, 7, 7), frames)
    }

    @Test fun theCameraTakesPhotosAndRecordsFromAFakeSource() {
        prepare("camera")
        System.setProperty("am.fakeCamera", "true")
        try {
            val scope = CoroutineScope(Dispatchers.Default)
            val folder = File("build/camera-out").also { it.deleteRecursively() }
            val camera = DeskCameraController(DeskMedia.ffmpeg, folder, scope)
            camera.open()
            waitFor("the live preview") { camera.phase == CameraPhase.Live }
            val photo = camera.photo()!!
            val image = ImageIO.read(photo)
            assertTrue("${image.width}x${image.height}", image.width >= 640 && image.height >= 360)
            camera.startRecording()
            Thread.sleep(2500)
            val video = runBlocking { camera.stopRecording() }!!
            assertTrue(video.length() > 10_000)
            val facts = DeskMedia.probe(video)
            assertTrue("probe $facts", (facts["duration"]?.toLong() ?: 0L) in 1000L..6000L && facts["width"] == "1280" && facts["audio"] == "1")
            waitFor("the preview after recording") { camera.phase == CameraPhase.Live }
            camera.close()
        } finally { System.clearProperty("am.fakeCamera") }
    }

    @Test fun aRecordingThatCannotStartShowsTheCameraError() {
        System.setProperty("am.fakeCamera", "true")
        try {
            val blocked = File("build/camera-blocked").apply { writeText("not a folder") }
            val camera = DeskCameraController(DeskMedia.ffmpeg, blocked, CoroutineScope(Dispatchers.Default))
            camera.open()
            waitFor("the live preview") { camera.phase == CameraPhase.Live }
            camera.startRecording()
            waitFor("the camera error") { camera.phase == CameraPhase.Failed }
            assertFalse(camera.recording)
            camera.close()
        } finally { System.clearProperty("am.fakeCamera") }
    }

    @Test fun theCameraDialogAddsAPhotoToTheProject() {
        prepare("camera-ui")
        System.setProperty("am.fakeCamera", "true")
        try {
            runSkikoComposeUiTest(size = Size(1280f, 800f)) {
                val app = DeskAppState()
                setContent { DeskApp(app) }
                val id = app.store.create(Scene.parse(repo.resolve("testdata/scenes/styles.xml").readText().replace("\"testdata/", "\"" + repo.resolve("testdata").toURI().toString().replaceFirst("file:/", "file:///") + "/")))
                runBlocking { app.openNow(id) }
                waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
                val editor = app.editor!!
                val layers = editor.scene.layers.size
                runOnIdle { editor.cameraOpen = true }
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithContentDescription(Strings.get(R.string.camera_take_photo)).fetchSemanticsNodes().isNotEmpty() }
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithText(Strings.get(R.string.desk_camera_starting)).fetchSemanticsNodes().isEmpty() }
                shot("camera-photo")
                onNodeWithText(Strings.get(R.string.camera_video)).performClick()
                mainClock.advanceTimeBy(300)
                shot("camera-video")
                onNodeWithText(Strings.get(R.string.camera_photo)).performClick()
                mainClock.advanceTimeBy(300)
                onNodeWithContentDescription(Strings.get(R.string.camera_take_photo)).performClick()
                waitUntil(timeoutMillis = 30_000) { editor.scene.layers.size > layers }
                assertTrue(!editor.cameraOpen)
                runBlocking { app.closeEditor() }
            }
        } finally { System.clearProperty("am.fakeCamera") }
    }

    @Test fun theSoundBrowserListsSongsAlbumsArtistsAndFolders() {
        prepare("sound-browser")
        val music = File(System.getProperty("user.home"), "Music").also { it.mkdirs() }
        fun make(folder: String, name: String, freq: Int, vararg tags: String) {
            val dir = File(music, folder).also { it.mkdirs() }
            val p = ProcessBuilder(listOf(DeskMedia.ffmpeg, "-nostdin", "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=$freq:duration=2") + tags + File(dir, name).path).start()
            check(p.waitFor() == 0)
        }
        make("Album One", "alpha.m4a", 330, "-metadata", "title=Alpha", "-metadata", "artist=Ada", "-metadata", "album=First")
        make("Album One", "beta.m4a", 440, "-metadata", "title=Beta", "-metadata", "artist=Ada", "-metadata", "album=First")
        make("Other", "gamma.flac", 550, "-metadata", "title=Gamma", "-metadata", "artist=Bo", "-metadata", "album=Second")
        app.absentmotion.desk.DeskLibrary.link(music)
        app.absentmotion.audio.DeskOutput.capture = { _, _ -> }
        try {
            for ((w, h) in listOf(1280 to 800, 1024 to 700, 1728 to 1080)) runSkikoComposeUiTest(size = Size(w.toFloat(), h.toFloat())) {
                val app = DeskAppState()
                setContent { DeskApp(app) }
                val id = app.store.create(Scene.parse(repo.resolve("testdata/scenes/styles.xml").readText().replace("\"testdata/", "\"" + repo.resolve("testdata").toURI().toString().replaceFirst("file:/", "file:///") + "/")))
                runBlocking { app.openNow(id) }
                waitUntil(timeoutMillis = 30_000) { app.engine.frame != null }
                onAllNodesWithText(Strings.get(R.string.add_tab_audio)).filter(hasClickAction()).onFirst().performClick()
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Alpha").fetchSemanticsNodes().isNotEmpty() }
                shot("$w-audio-tab")
                onNodeWithText(Strings.get(R.string.view_all)).performClick()
                mainClock.advanceTimeBy(500)
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Gamma").fetchSemanticsNodes().size >= 2 }
                shot("$w-audio-browser-songs")
                for (group in listOf(R.string.sounds_tab_albums, R.string.sounds_tab_artists, R.string.sounds_tab_folders)) {
                    onNodeWithText(Strings.get(group)).performClick()
                    mainClock.advanceTimeBy(500)
                    waitForIdle()
                    shot("$w-audio-browser-${Strings.get(group).lowercase()}")
                }
                onNodeWithText(Strings.get(R.string.sounds_tab_songs)).performClick()
                mainClock.advanceTimeBy(300)
                onNodeWithText(Strings.get(R.string.sounds_sort_added), substring = true).performClick()
                mainClock.advanceTimeBy(300)
                shot("$w-audio-browser-sort")
                DeskBack.back()
                DeskBack.back()
                mainClock.advanceTimeBy(300)
                val before = app.editor!!.scene.layers.count { it.kind == "audio" }
                onNodeWithText(Strings.get(R.string.view_all)).performClick()
                mainClock.advanceTimeBy(500)
                onAllNodesWithContentDescription(Strings.get(R.string.audiotab_add)).onFirst().performClick()
                waitUntil(timeoutMillis = 30_000) { app.editor!!.scene.layers.count { it.kind == "audio" } > before }
                runBlocking { app.closeEditor() }
            }
        } finally { app.absentmotion.audio.DeskOutput.capture = null; app.absentmotion.desk.DeskLibrary.unlink(music) }
    }
}
