package app.absentmotion.export

import android.media.MediaCodec
import android.media.MediaFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MuxTest {
    private class Queued : SampleSink {
        val written = ArrayList<Pair<Int, Long>>()
        private val queues = Interleaver { track, _, timeUs, _ -> written += track to timeUs }
        private var tracks = 0

        override fun addTrack(format: MediaFormat): Int { queues.addTrack(); return tracks++ }
        override fun start() {}
        override fun write(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) =
            queues.add(track, ByteArray(info.size), info.presentationTimeUs, true)
        override fun end(track: Int) = queues.end(track)
        override fun queued(track: Int): Long = queues.queued(track)
        override fun stop() = queues.flush(all = true)
        override fun release() {}
    }

    private fun packet(mux: Mux, track: Int, timeUs: Long, size: Int = 10) =
        mux.write(track, ByteBuffer.allocate(size), MediaCodec.BufferInfo().also { it.size = size; it.presentationTimeUs = timeUs })

    @Test fun samplesLeaveInTimeOrderAndAFinishedTrackHoldsNothingBack() {
        val out = ArrayList<Pair<Int, Long>>()
        val queues = Interleaver { track, _, timeUs, _ -> out += track to timeUs }
        queues.addTrack(); queues.addTrack()
        queues.add(1, ByteArray(4), 0, true)
        queues.add(1, ByteArray(4), 20, true)
        assertEquals(8L, queues.queued(1))
        queues.add(0, ByteArray(4), 10, true)
        assertEquals(listOf(1 to 0L, 0 to 10L), out)
        queues.end(0)
        assertEquals(listOf(1 to 0L, 0 to 10L, 1 to 20L), out)
        queues.add(1, ByteArray(4), 30, true)
        assertEquals(1 to 30L, out.last())
        assertEquals(0L, queues.queued(1))
    }

    @Test fun aTrackThatRunsAheadWaitsUntilTheOtherCatchesUp() {
        val sink = Queued()
        val mux = Mux(sink, 2, budget = 50, onError = {})
        mux.addTrack(MediaFormat()); mux.addTrack(MediaFormat())
        val done = AtomicBoolean()
        val audio = thread { for (k in 0 until 20) packet(mux, 1, k * 10L); done.set(true) }
        Thread.sleep(300)
        assertFalse(done.get())
        assertTrue(sink.queued(1) in 50L..60L)
        for (k in 0 until 20) packet(mux, 0, k * 10L + 5)
        audio.join(5_000)
        assertTrue(done.get())
        mux.end(0); mux.end(1); mux.finish()
        assertEquals(sink.written.map { it.second }.sorted(), sink.written.map { it.second })
        assertEquals(40, sink.written.size)
    }

    @Test fun stoppingTheExportReleasesAWaitingTrack() {
        val stopped = AtomicBoolean()
        val mux = Mux(Queued(), 2, budget = 20, stopped = stopped::get, onError = {})
        mux.addTrack(MediaFormat()); mux.addTrack(MediaFormat())
        val waiting = thread { repeat(10) { packet(mux, 1, it * 10L) } }
        Thread.sleep(200)
        assertTrue(waiting.isAlive)
        stopped.set(true)
        waiting.join(2_000)
        assertFalse(waiting.isAlive)
    }

    @Test fun packetsBeforeTheLastTrackArrivesAreBoundedToo() {
        val sink = Queued()
        val mux = Mux(sink, 2, budget = 30, onError = {})
        mux.addTrack(MediaFormat())
        val audio = thread { repeat(10) { packet(mux, 0, it * 10L) } }
        Thread.sleep(200)
        assertTrue(audio.isAlive)
        mux.addTrack(MediaFormat())
        mux.end(1)
        audio.join(5_000)
        assertFalse(audio.isAlive)
        mux.end(0)
        mux.finish()
        assertEquals((0 until 10).map { 0 to it * 10L }, sink.written)
    }

    private fun bits(vararg fields: Pair<Long, Int>): ByteArray {
        val out = ArrayList<Int>()
        var acc = 0
        var count = 0
        for ((value, width) in fields) for (i in width - 1 downTo 0) {
            acc = (acc shl 1) or ((value ushr i) and 1).toInt()
            if (++count == 8) { out += acc; acc = 0; count = 0 }
        }
        if (count > 0) out += acc shl (8 - count)
        return ByteArray(out.size) { out[it].toByte() }
    }

    private fun sequenceObu(payload: ByteArray) = byteArrayOf(0x0A, payload.size.toByte()) + payload

    @Test fun av1RecordCarriesTheLevelOfTheSequenceHeader() {
        val plain = sequenceObu(bits(0L to 3, 0L to 1, 0L to 1, 0L to 1, 0L to 1, 0L to 5, 0L to 12, 8L to 5, 0L to 1, 0xFFL to 16))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x08, 0x0C, 0) + plain, av1Config(plain))
        val timed = sequenceObu(bits(0L to 3, 0L to 1, 0L to 1, 1L to 1, 1001L to 32, 30000L to 32, 1L to 1, 1L to 3, 1L to 2, 1L to 1, 4L to 5, 1L to 32, 9L to 5, 9L to 5,
            0L to 1, 0L to 5, 0L to 12, 12L to 5, 1L to 1, 0xFFL to 16))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x0C, 0x8C.toByte(), 0) + timed, av1Config(timed))
        val delimited = byteArrayOf(0x12, 0) + plain
        assertEquals(0x08, av1Config(delimited)[1].toInt())
        assertEquals(31, av1Config(byteArrayOf(0x32, 0x01, 0x00))[1].toInt())
    }
}
