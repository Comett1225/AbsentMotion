package app.absentmotion.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.LruCache

internal object VideoSources {
    private val indices = LruCache<String, LongArray>(16)
    @Synchronized fun timestamps(context: Context, uri: String): LongArray {
        indices.get(uri)?.let { return it }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(track)
            val samples = ArrayList<Long>()
            while (extractor.sampleTime >= 0) { samples += extractor.sampleTime; extractor.advance() }
            return samples.distinct().sorted().toLongArray().also { check(it.isNotEmpty()); indices.put(uri, it) }
        } finally { extractor.release() }
    }
}

internal fun LongArray.ptsAt(timeUs: Long): Long {
    val found = binarySearch(timeUs)
    return this[if (found >= 0) found else (-found - 2).coerceAtLeast(0)]
}
