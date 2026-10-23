package app.absentmotion.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.LruCache
import app.absentmotion.library.openableUri

internal object VideoSources {
    private class Index(val timestamps: LongArray, val syncGapUs: Long)

    private val indices = LruCache<String, Index>(16)

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
            val samples = ArrayList<Long>()
            val syncs = ArrayList<Long>()
            while (extractor.sampleSize >= 0) {
                val time = extractor.sampleTime
                if (time >= 0) {
                    samples += time
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) syncs += time
                }
                if (!extractor.advance()) break
            }
            val stamps = samples.distinct().sorted().toLongArray()
            check(stamps.isNotEmpty())
            return Index(stamps, longestSyncGap(syncs, stamps.last())).also { indices.put(key, it) }
        } finally { extractor.release() }
    }
}

internal fun longestSyncGap(syncs: List<Long>, lastUs: Long): Long {
    if (syncs.isEmpty()) return Long.MAX_VALUE
    val sorted = syncs.sorted()
    return (sorted.zipWithNext { a, b -> b - a } + (lastUs - sorted.last())).max()
}

internal fun estimatedFps(stamps: LongArray): Float? =
    if (stamps.size < 2 || stamps.last() <= stamps.first()) null else (stamps.size - 1) * 1_000_000f / (stamps.last() - stamps.first())

internal fun LongArray.frameNumber(pts: Long): String = binarySearch(pts).let { if (it >= 0) it else -it - 1 }.toString()

internal fun LongArray.ptsAt(timeUs: Long): Long {
    val found = binarySearch(timeUs)
    return this[if (found >= 0) found else (-found - 2).coerceAtLeast(0)]
}
