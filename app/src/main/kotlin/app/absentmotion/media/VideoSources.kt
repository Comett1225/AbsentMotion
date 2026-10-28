package app.absentmotion.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.LruCache
import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.library.openableUri

internal object VideoSources {
    private class Index(val timestamps: LongArray, val syncGapUs: Long)

    private val indices = object : LruCache<String, Index>(INDEX_BYTES) {
        override fun sizeOf(key: String, value: Index) = value.timestamps.size * 8 + 64
    }

    fun timestamps(context: Context, uri: String): LongArray = index(context, uri).timestamps

    fun syncGapUs(context: Context, uri: String): Long = index(context, uri).syncGapUs

    @Synchronized private fun index(context: Context, uri: String): Index {
        val key = uri + "\n" + fileVersion(context.contentResolver, Uri.parse(uri))
        indices.get(key)?.let { return it }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(openableUri(uri)), null)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(track)
            val samples = Stamps()
            val syncs = Stamps()
            while (extractor.sampleSize >= 0) {
                val time = extractor.sampleTime
                if (time >= 0) {
                    if (samples.count >= MAX_SAMPLES) throw IllegalStateException(AppText.get(R.string.media_error_too_many_frames))
                    samples.add(time)
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) syncs.add(time)
                }
                if (!extractor.advance()) break
            }
            val stamps = samples.sortedDistinct()
            check(stamps.isNotEmpty())
            return Index(stamps, longestSyncGap(syncs.sortedDistinct(), stamps.last())).also { indices.put(key, it) }
        } finally { extractor.release() }
    }

    private const val INDEX_BYTES = 16 shl 20
    private const val MAX_SAMPLES = 1_000_000
}

internal class Stamps {
    private var values = LongArray(256)
    private var size = 0

    val count: Int get() = size

    fun add(value: Long) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    fun sortedDistinct(): LongArray {
        val sorted = values.copyOf(size).apply { sort() }
        var kept = 0
        for (value in sorted) if (kept == 0 || sorted[kept - 1] != value) sorted[kept++] = value
        return if (kept == sorted.size) sorted else sorted.copyOf(kept)
    }
}

internal fun longestSyncGap(sorted: LongArray, lastUs: Long): Long {
    if (sorted.isEmpty()) return Long.MAX_VALUE
    var longest = lastUs - sorted.last()
    for (i in 1 until sorted.size) longest = maxOf(longest, sorted[i] - sorted[i - 1])
    return longest
}

internal fun estimatedFps(stamps: LongArray): Float? =
    if (stamps.size < 2 || stamps.last() <= stamps.first()) null else (stamps.size - 1) * 1_000_000f / (stamps.last() - stamps.first())

internal fun LongArray.frameNumber(pts: Long): String = binarySearch(pts).let { if (it >= 0) it else -it - 1 }.toString()

internal fun LongArray.ptsAt(timeUs: Long): Long {
    val found = binarySearch(timeUs)
    return this[if (found >= 0) found else (-found - 2).coerceAtLeast(0)]
}
