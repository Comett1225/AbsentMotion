package app.absentmotion.tools

import app.absentmotion.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DownloadFormatsTest {
    private fun info(vararg formats: String, pictures: String = "[]") =
        parseLinkInfo("""{"title":"T","site":"Youtube","items":[{"title":"T","duration":19,"formats":[${formats.joinToString(",")}],"pictures":$pictures}]}""")

    private val youtube = info(
        """{"id":"18","ext":"mp4","v":"avc1.42001E","a":"mp4a.40.2","w":640,"h":360,"tbr":500,"size":1000000,"proto":"https"}""",
        """{"id":"140","ext":"m4a","v":"none","a":"mp4a.40.2","abr":129.5,"size":300000,"proto":"https","note":"medium, original"}""",
        """{"id":"251","ext":"webm","v":"none","a":"opus","abr":135,"size":280000,"proto":"https"}""",
        """{"id":"137","ext":"mp4","v":"avc1.640028","a":"none","w":1920,"h":1080,"fps":30,"tbr":4000,"size":9000000,"proto":"https"}""",
        """{"id":"96","ext":"mp4","v":"avc1.640028","a":"mp4a.40.2","w":1920,"h":1080,"fps":30,"tbr":4500,"proto":"m3u8_native"}""",
        """{"id":"248","ext":"webm","v":"vp9","a":"none","w":1920,"h":1080,"fps":30,"tbr":2500,"size":6000000,"proto":"https"}""",
        """{"id":"401","ext":"mp4","v":"av01.0.12M.08","a":"none","w":3840,"h":2160,"fps":60,"tbr":20000,"size":70000000,"proto":"https"}""",
        """{"id":"315","ext":"webm","v":"vp9","a":"none","w":3840,"h":2160,"fps":60,"tbr":26000,"size":90000000,"proto":"https"}""",
        """{"id":"sb0","ext":"mhtml","v":"none","a":"none","proto":"mhtml"}""",
    ).first!!

    @Test
    fun youtubeQualitiesPreferDirectMp4AndFallBackToWebm() {
        val withAv1 = qualities(youtube, SaveMode.Both, av1Decoder = true)
        assertEquals(listOf(2160, 1080, 360), withAv1.map { it.side })
        assertEquals(60, withAv1[0].fps)
        assertEquals("401" to "140", withAv1[0].plan.video!!.id to withAv1[0].plan.audio!!.id)
        assertEquals("137" to "140", withAv1[1].plan.video!!.id to withAv1[1].plan.audio!!.id)
        assertEquals("18", withAv1[2].plan.video!!.id)
        assertNull(withAv1[2].plan.audio)
        val noAv1 = qualities(youtube, SaveMode.Both, av1Decoder = false)
        assertEquals("315" to "251", noAv1[0].plan.video!!.id to noAv1[0].plan.audio!!.id)
        assertTrue(noAv1[0].plan.webm)
        assertEquals(false, withAv1[0].plan.webm)
        assertEquals(1, defaultQuality(withAv1, SaveMode.Both))
        val facebook = info(
            """{"id":"sd","ext":"mp4","proto":"https"}""", """{"id":"hd","ext":"mp4","proto":"https"}""",
            """{"id":"1a","ext":"m4a","v":"none","a":"mp4a.40.5","proto":"https"}""",
            """{"id":"2v","ext":"mp4","v":"vp09.00.31.08","a":"none","w":720,"h":1280,"proto":"https"}""",
        ).first!!
        val fb = qualities(facebook, SaveMode.Both)
        assertEquals(listOf(0), fb.map { it.side })
        assertEquals("hd", fb[0].plan.video!!.id)
        assertEquals(listOf(720), qualities(facebook, SaveMode.VideoOnly).map { it.side })
    }

    @Test
    fun oddSizesUseTheUsualQualityNames() {
        assertEquals(listOf(1080, 1080, 720, 540, 540, 360, 240, 144, 144, 0), listOf(1080, 1076, 720, 608, 576, 406, 270, 136, 82, 0).map(::rung))
        val short = info(
            """{"id":"137","ext":"mp4","v":"avc1.640028","a":"none","w":608,"h":1080,"tbr":1000,"proto":"https"}""",
            """{"id":"779","ext":"webm","v":"vp9","a":"none","w":608,"h":1080,"tbr":600,"proto":"https"}""",
            """{"id":"247","ext":"webm","v":"vp9","a":"none","w":720,"h":1280,"tbr":1500,"proto":"https"}""",
            """{"id":"136","ext":"mp4","v":"avc1.4d401f","a":"none","w":406,"h":720,"tbr":500,"proto":"https"}""",
            """{"id":"243","ext":"webm","v":"vp9","a":"none","w":360,"h":640,"tbr":400,"proto":"https"}""",
            """{"id":"140","ext":"m4a","v":"none","a":"mp4a.40.2","abr":129,"proto":"https"}""",
            """{"id":"251","ext":"webm","v":"none","a":"opus","abr":120,"proto":"https"}""",
        ).first!!
        val options = qualities(short, SaveMode.Both)
        assertEquals(listOf(720, 540, 360), options.map { it.side })
        assertEquals(listOf("247" to "251", "137" to "140", "136" to "140"), options.map { it.plan.video!!.id to it.plan.audio!!.id })
    }

    @Test
    fun videoOnlyAndAudioOnly() {
        val video = qualities(youtube, SaveMode.VideoOnly, av1Decoder = true)
        assertTrue(video.all { it.plan.audio == null })
        assertEquals("137", video.first { it.side == 1080 }.plan.video!!.id)
        val audio = qualities(youtube, SaveMode.AudioOnly)
        assertEquals("140", audio[0].plan.audio!!.id)
        assertEquals(false, audio[0].plan.reencodeAudio)
        assertEquals(130, audio[0].kbps)
        assertEquals(128, usualKbps(audio[0].kbps))
        assertEquals(1, audio.size)
        assertEquals(listOf(48, 96, 110), listOf(49, 100, 110).map(::usualKbps))
    }

    @Test
    fun progressiveOnlySitesAndWatermarks() {
        val tiktok = info(
            """{"id":"download","ext":"mp4","v":"h264","a":"aac","proto":"https","note":"Download video, watermarked"}""",
            """{"id":"h264_540p_1","ext":"mp4","v":"h264","a":"aac","w":576,"h":1024,"tbr":1895,"size":5000000,"proto":"https"}""",
            """{"id":"h264_540p_2","ext":"mp4","v":"h264","a":"aac","w":576,"h":1024,"tbr":815,"size":2000000,"proto":"https"}""",
            """{"id":"bytevc1_540p","ext":"mp4","v":"bytevc1","a":"aac","w":576,"h":1024,"tbr":668,"size":1700000,"proto":"https"}""",
        ).first!!
        val both = qualities(tiktok, SaveMode.Both)
        assertEquals(1, both.size)
        assertEquals("h264_540p_1", both[0].plan.video!!.id)
        val sound = qualities(tiktok, SaveMode.AudioOnly)
        assertEquals(1, sound.size)
        assertEquals(0, sound[0].kbps)
        val twitter = info("""{"id":"http-2176","ext":"mp4","w":1280,"h":720,"tbr":2176,"proto":"https"}""", """{"id":"http-832","ext":"mp4","w":640,"h":360,"tbr":832,"proto":"https"}""").first!!
        assertEquals(listOf(720, 360), qualities(twitter, SaveMode.Both).map { it.side })
        assertEquals(VideoCodec.Unknown, twitter.formats[0].video)
    }

    @Test
    fun silentVideosStillHaveQualities() {
        val silent = info(
            """{"id":"0","ext":"mp4","a":"none","proto":"https"}""",
            """{"id":"dash-1v","ext":"mp4","v":"avc1.4d401e","a":"none","w":640,"h":640,"proto":"https"}""",
        ).first!!
        assertEquals(listOf("dash-1v"), qualities(silent, SaveMode.Both).map { it.plan.video!!.id })
        assertTrue(qualities(silent, SaveMode.AudioOnly).isEmpty())
    }

    @Test
    fun picturePostsHaveNoQualities() {
        val post = info(pictures = """[["https://example.com/a.jpg","https://example.com/b.jpg"]]""")
        assertEquals(listOf(listOf("https://example.com/a.jpg", "https://example.com/b.jpg")), post.first!!.pictures)
        assertTrue(post.pictures)
        assertTrue(picturesOnly(post.first!!))
        assertTrue(qualities(post.first!!, SaveMode.Both).isEmpty())
    }

    @Test
    fun errorsBecomePlainMessages() {
        assertEquals(R.string.downloader_error_login, problemFor("ERROR: [Reddit] 6rrwyj: Account authentication is required. Use --cookies-from-browser"))
        assertEquals(R.string.downloader_error_blocked, problemFor("ERROR: [vimeo] 1: This request has been blocked due to its TLS fingerprint"))
        assertEquals(R.string.downloader_error_unsupported, problemFor("ERROR: Unsupported URL: https://example.com"))
        assertEquals(R.string.downloader_error_bot, problemFor("not a bot: [youtube] Sign in to confirm you’re not a bot. Use --cookies-from-browser"))
        assertEquals(R.string.downloader_error_private, problemFor("ERROR: [youtube] abc: Private video"))
        assertEquals(R.string.downloader_error_gone, problemFor("ERROR: [youtube] abc: Video unavailable"))
        assertEquals(R.string.downloader_error_unknown, problemFor("something odd"))
    }

    @Test
    fun namesAndPictureTypes() {
        assertEquals("a b c", safeName(" a/b:c? "))
        assertEquals("Download", safeName("..."))
        assertEquals(80, safeName("x".repeat(200)).length)
        assertEquals("image/png" to "png", pictureType(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertEquals("image/webp" to "webp", pictureType("RIFF0000WEBPVP8 ".toByteArray()))
        assertEquals("image/jpeg" to "jpg", pictureType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
    }

    @Test
    fun pythonPackageKeepsOnlyWhatRuns() {
        assertEquals("lib/libpython3.14.so", pythonEntry("./prefix/lib/libpython3.14.so"))
        assertEquals("lib/libssl_python.so", pythonEntry("./prefix/lib/libssl_python.so"))
        assertEquals("lib/python3.14/os.py", pythonEntry("./prefix/lib/python3.14/os.py"))
        assertEquals("lib/python3.14/lib-dynload/_ssl.cpython-314-aarch64-linux-android.so", pythonEntry("./prefix/lib/python3.14/lib-dynload/_ssl.cpython-314-aarch64-linux-android.so"))
        for (skipped in listOf(
            "./prefix/lib/libcrypto.so", "./prefix/lib/libsqlite3_python.so", "./prefix/lib/libpython3.so", "./prefix/include/python3.14/Python.h",
            "./testbed/app/build.gradle.kts", "./prefix/lib/python3.14/test/test_os.py", "./prefix/lib/python3.14/idlelib/idle.py",
            "./prefix/lib/python3.14/lib-dynload/_testcapi.cpython-314-aarch64-linux-android.so", "./prefix/lib/python3.14/sqlite3/__init__.py",
            "./android.py",
        )) assertNull(skipped, pythonEntry(skipped))
    }

    private fun header(name: String, size: Int, type: Char): ByteArray {
        val block = ByteArray(512)
        name.toByteArray().copyInto(block, 0)
        String.format("%011o", size).toByteArray().copyInto(block, 124)
        block[156] = type.code.toByte()
        "ustar".toByteArray().copyInto(block, 257)
        return block
    }

    private fun ByteArrayOutputStream.entry(name: String, type: Char, body: ByteArray) {
        write(header(name, body.size, type))
        write(body)
        write(ByteArray((512 - body.size % 512) % 512))
    }

    @Test
    fun readsPaxTarArchives() {
        val long = "./prefix/lib/python3.14/" + "deep/".repeat(30) + "file.py"
        val record = "path=$long\n"
        var length = record.length + 3
        if ("$length $record".length != length) length = "$length $record".length
        val tar = ByteArrayOutputStream().apply {
            entry("./prefix/lib/python3.14/", '5', ByteArray(0))
            entry("./prefix/lib/python3.14/os.py", '0', "print(1)\n".toByteArray())
            entry("././@PaxHeader", 'x', "$length $record".toByteArray())
            entry("./prefix/lib/python3.14/short", '0', "long".toByteArray())
            write(ByteArray(1024))
        }.toByteArray()
        val seen = ArrayList<Triple<String, Char, String>>()
        readTar(ByteArrayInputStream(tar)) { name, type, body -> seen += Triple(name, type, String(body.readBytes())) }
        assertEquals(listOf(
            Triple("./prefix/lib/python3.14/", '5', ""),
            Triple("./prefix/lib/python3.14/os.py", '0', "print(1)\n"),
            Triple(long, '0', "long"),
        ), seen)
    }
}
