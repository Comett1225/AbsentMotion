package app.absentmotion.export

import app.absentmotion.R
import app.absentmotion.i18n.txt
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ContainerTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private class Element(val id: Long, val start: Int, val dataStart: Int, val size: Long)

    private class Reader(val data: ByteArray) {
        fun vint(at: Int, keepMarker: Boolean): Pair<Long, Int> {
            val first = data[at].toInt() and 0xFF
            var length = 1
            while (length <= 8 && first and (0x80 ushr (length - 1)) == 0) length++
            var value = (if (keepMarker) first else first and (0xFF ushr length)).toLong()
            for (i in 1 until length) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
            return value to length
        }

        fun element(at: Int): Element {
            val (id, idLength) = vint(at, true)
            val (size, sizeLength) = vint(at + idLength, false)
            return Element(id, at, at + idLength + sizeLength, size)
        }

        fun children(parent: Element): List<Element> {
            val out = ArrayList<Element>()
            var at = parent.dataStart
            val end = parent.dataStart + parent.size
            while (at < end) {
                val e = element(at)
                out += e
                at = (e.dataStart + e.size).toInt()
            }
            return out
        }

        fun uint(e: Element): Long = (0 until e.size.toInt()).fold(0L) { v, i -> (v shl 8) or (data[e.dataStart + i].toLong() and 0xFF) }
    }

    @Test fun matroskaFileHasPatchedSizesSeekHeadCuesAndDuration() {
        val file = File.createTempFile("writer", ".mkv")
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val writer = MatroskaWriter(raf.channel)
                writer.start(listOf(
                    MatroskaWriter.Track(true, "V_MPEG4/ISO/AVC", byteArrayOf(1, 2, 3), 320, 240, frameDurationNs(30)),
                    MatroskaWriter.Track(false, "A_AAC", byteArrayOf(0x12, 0x10), sampleRate = 44100.0, channels = 2, codecDelayNs = 46_439_909),
                ))
                for (k in 0 until 90) {
                    writer.write(0, ByteArray(100) { k.toByte() }, k * 1_000_000L / 30, k % 30 == 0)
                    writer.write(1, ByteArray(20), k * 1_000_000L / 30 + 5000, true)
                }
                writer.finish()
            }
            val r = Reader(file.readBytes())
            val header = r.element(0)
            assertEquals(0x1A45DFA3L, header.id)
            val docType = r.children(header).first { it.id == 0x4282L }
            assertEquals("matroska", String(r.data, docType.dataStart, docType.size.toInt()))
            val segment = r.element((header.dataStart + header.size).toInt())
            assertEquals(0x18538067L, segment.id)
            assertEquals(r.data.size.toLong(), segment.dataStart + segment.size)
            val top = r.children(segment)
            assertEquals(listOf(0x114D9B74L, 0xECL, 0x1549A966L, 0x1654AE6BL), top.take(4).map { it.id })
            val seekTargets = r.children(top[0]).map { seek ->
                val (id, position) = r.children(seek)
                Reader(r.data.copyOfRange(id.dataStart, (id.dataStart + id.size).toInt())).vint(0, true).first to r.uint(position)
            }
            for ((id, position) in seekTargets) assertEquals(id, r.element((segment.dataStart + position).toInt()).id)
            assertEquals(3, seekTargets.size)
            val clusters = top.filter { it.id == 0x1F43B675L }
            assertEquals(3, clusters.size)
            assertEquals(listOf(0L, 1000L, 2000L), clusters.map { c -> r.uint(r.children(c).first { it.id == 0xE7L }) })
            val cues = r.children(top.first { it.id == 0x1C53BB6BL })
            assertEquals(clusters.map { (it.start - segment.dataStart).toLong() }, cues.map { cue -> r.uint(r.children(r.children(cue)[1])[1]) })
            val info = r.children(top[2])
            val duration = info.first { it.id == 0x4489L }
            assertEquals(3000.0, ByteBuffer.wrap(r.data, duration.dataStart, 8).double, 1.0)
            val blocks = r.children(clusters[1]).filter { it.id == 0xA3L }
            assertEquals(60, blocks.size)
            val second = blocks[1]
            assertEquals(0x82, r.data[second.dataStart].toInt() and 0xFF)
            assertEquals(5, ByteBuffer.wrap(r.data, second.dataStart + 1, 2).short.toInt())
            val tracks = r.children(top[3])
            val audio = r.children(tracks[1])
            assertEquals(46_439_909L, r.uint(audio.first { it.id == 0x56AAL }))
        } finally {
            file.delete()
        }
    }

    @Test fun annexBBecomesLengthPrefixed() {
        val annexB = hex("00000001674201" + "000001" + "68ce" + "00000001" + "65888000")
        assertEquals(listOf("674201", "68ce", "65888000"), NalUnits.split(annexB).map { it.joinToString("") { b -> "%02x".format(b) } })
        assertArrayEquals(hex("00000003674201" + "0000000268ce" + "0000000465888000"), NalUnits.lengthPrefixed(annexB))
    }

    @Test fun avcConfigurationRecordMatchesTheDeviceOutput() {
        val sps = "6742c0298d68100869080808083c2211a8"
        val pps = "68ce01a835c8"
        val record = NalUnits.avcC(NalUnits.split(hex("00000001$sps")) + NalUnits.split(hex("00000001$pps")))
        assertArrayEquals(hex("0142c029ffe10011${sps}010006$pps"), record)
    }

    @Test fun hevcConfigurationRecordCopiesProfileTierLevel() {
        val vps = "40010c01ffff014000000300a0000003000003003c1b0240"
        val sps = "420101014000000300a0000003000003003ca008080407f9c6e490892e6a02020221"
        val pps = "4401c018c309303324"
        val record = NalUnits.hvcC(NalUnits.split(hex("00000001${vps}00000001${sps}00000001$pps")))
        val expected = "01" + "01" + "40000000" + "a00000000000" + "3c" + "f000fcfdf8f80000" + "0f" + "03" +
            "a00001" + "0018" + vps + "a10001" + "0022" + sps + "a20001" + "0009" + pps
        assertArrayEquals(hex(expected), record)
    }

    @Test fun av1TemporalDelimiterIsDropped() {
        assertArrayEquals(hex("0a0b0c"), NalUnits.withoutTemporalDelimiter(hex("12000a0b0c")))
        assertArrayEquals(hex("0a0b0c"), NalUnits.withoutTemporalDelimiter(hex("0a0b0c")))
        assertArrayEquals(hex("81000c00"), av1Config(hex("81000c00")))
    }

    @Test fun vp9KeyFramesAreMarkedBt709() {
        assertArrayEquals(hex("82498342400ff0"), vp9Bt709(hex("82498342000ff0")))
        assertArrayEquals(hex("86498342000ff0"), vp9Bt709(hex("86498342000ff0")))
        assertArrayEquals(hex("8649834200"), vp9Bt709(hex("8649834200")))
        assertArrayEquals(hex("86000000"), vp9Bt709(hex("86000000")))
        assertArrayEquals(hex("82498342a00ff0"), vp9Bt709(hex("82498342a00ff0")))
    }

    @Test fun androidOpusConfigurationIsSplit() {
        val head = hex("4f707573486561640102380180bb0000000000")
        fun part(marker: String, body: ByteArray) =
            marker.toByteArray() + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(body.size.toLong()).array() + body
        fun nanos(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
        val blob = part("AOPUSHDR", head) + part("AOPUSDLY", nanos(6_500_000)) + part("AOPUSPRL", nanos(80_000_000))
        val config = opusConfig(blob, null, null)
        assertArrayEquals(head, config.head)
        assertEquals(6_500_000L, config.delayNs)
        assertEquals(80_000_000L, config.preRollNs)
        val plain = opusConfig(head, nanos(6_500_000), null)
        assertArrayEquals(head, plain.head)
        assertEquals(6_500_000L, plain.delayNs)
        assertEquals(80_000_000L, plain.preRollNs)
    }

    @Test fun quickTimeBrandReplacesEveryBrand() {
        val ftyp = hex("00000018" + "66747970" + "6d703432" + "00000000" + "69736f6d" + "6d703432") + hex("0000000866726565")
        val patched = quickTimeBrands(ftyp)!!
        assertEquals(24, patched.size)
        assertEquals("ftypqt  ", String(patched, 4, 8))
        assertEquals(0x200, ByteBuffer.wrap(patched, 12, 4).int)
        assertEquals("qt  qt  ", String(patched, 16, 8))
        assertNull(quickTimeBrands(hex("0000000866726565")))
    }

    @Test fun formatRules() {
        assertEquals(listOf(Container.WebM, Container.Mkv), containersFor(Codec.Vp9))
        assertNull(formatProblem(ExportSettings(1080, 30), hasAudio = true))
        assertEquals(txt(R.string.export_problem_container_video, "MP4", "VP9"), formatProblem(ExportSettings(1080, 30, Codec.Vp9), hasAudio = false))
        assertEquals(txt(R.string.export_problem_container_audio, "WebM", "AAC"), formatProblem(ExportSettings(1080, 30, container = Container.WebM, codec = Codec.Vp9, audio = AudioSettings(AudioCodec.Aac)), hasAudio = true))
        assertNull(formatProblem(ExportSettings(1080, 30, container = Container.WebM, codec = Codec.Vp9), hasAudio = false))
        val fixed = ExportSettings(1080, 30, Codec.Vp9).fixedFormat()
        assertEquals(Container.WebM, fixed.container)
        assertEquals(AudioCodec.Opus, fixed.audio.codec)
        assertEquals(48000, fixed.audio.effectiveRate)
        assertEquals(ExportSettings(1080, 30), ExportSettings(1080, 30).fixedFormat())
        assertEquals(AudioSettings(AudioCodec.Aac, 128, 44100, 2), ExportSettings(1080, 30).audio)
    }

    @Test fun settingsSurviveJsonAndBadValuesFallBack() {
        val s = ExportSettings(720, 60, Codec.Hevc, 0.8f, ImageKind.Webp, 70, BackgroundBlack, FrameInterpolation.Blend,
            Container.Mkv, AudioSettings(AudioCodec.Opus, 192, 48000, 1), AudioFormat.Flac)
        assertEquals(s, settingsFromJson(JSONObject(settingsToJson(s).toString()), ExportSettings(1080, 30)))
        val broken = JSONObject().put("codec", "Nope").put("audioKbps", 7).put("fps", -1).put("container", "Mov").put("audioCodec", "Opus")
        val restored = settingsFromJson(broken, ExportSettings(1080, 30))
        assertEquals(Codec.H264, restored.codec)
        assertEquals(128, restored.audio.bitrateKbps)
        assertEquals(30, restored.fps)
        assertEquals(Container.Mov, restored.container)
        assertEquals(AudioCodec.Aac, restored.audio.codec)
    }

    @Test fun monoIsTheAverageOfBothChannels() {
        assertArrayEquals(shortArrayOf(150, -1), toChannels(shortArrayOf(100, 200, -2, 0), 1))
        val stereo = shortArrayOf(1, 2)
        assertTrue(stereo === toChannels(stereo, 2))
    }

    @Test fun aacTimestampsFollowTheSampleRate() {
        val times = AacTimestamps(2048, 48000)
        assertEquals(0L, times.map(42_666))
        assertEquals(21_334L, times.map(64_000))
        assertEquals(48000L * 2 + 2048, aacInputFrames(2_000_000, 2048, 48000))
    }
}
