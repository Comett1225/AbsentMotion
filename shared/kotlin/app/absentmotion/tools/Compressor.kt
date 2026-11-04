package app.absentmotion.tools

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import app.absentmotion.R
import app.absentmotion.ai.AudioCopy
import app.absentmotion.audio.AudioClip
import app.absentmotion.audio.OfflineMix
import app.absentmotion.audio.PcmDecoder
import app.absentmotion.audio.Resampler
import app.absentmotion.export.AppFolder
import app.absentmotion.export.AudioCodec
import app.absentmotion.export.AudioFormat
import app.absentmotion.export.AudioSettings
import app.absentmotion.export.ExportError
import app.absentmotion.export.ExportResult
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.MediaStoreExport
import app.absentmotion.export.exportAudio
import app.absentmotion.export.exportCheck
import app.absentmotion.export.formatBytes
import app.absentmotion.export.openVideoEncoder
import app.absentmotion.i18n.txt
import app.absentmotion.media.VideoPlan
import app.absentmotion.media.decodePicture
import app.absentmotion.media.exifTag
import app.absentmotion.media.transcode
import app.absentmotion.media.videoDeclaration
import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.exifOrientation
import app.absentmotion.platform.PlatformFeatures
import app.absentmotion.platform.compressVideoOnDesktop
import app.absentmotion.platform.intOr
import app.absentmotion.platform.shareExport
import app.absentmotion.platform.webpFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min
import kotlin.math.sqrt

class PickedMedia(val uri: Uri, val name: String, val rotation: Int, val source: CompressSource)

class Compressed(val file: File, val mime: String, val video: Boolean, val frames: Int = 0)

fun memorySide(): Int = sqrt(Runtime.getRuntime().maxMemory() / 24.0).toInt()

private fun facts(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment.orEmpty()
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1) }
    }
    if (size <= 0) size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    return name to size
}

suspend fun readPicked(context: Context, uri: Uri): PickedMedia? = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val (name, size) = facts(resolver, uri)
        if (resolver.getType(uri).orEmpty().startsWith("video/")) {
            val video = videoDeclaration(context, uri) ?: return@runCatching null
            val extractor = MediaExtractor()
            val audio = try {
                extractor.setDataSource(context, uri, null)
                (0 until extractor.trackCount).map(extractor::getTrackFormat).firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            } finally { extractor.release() }
            PickedMedia(uri, name, video.orientation, CompressSource(
                true, size, video.width, video.height, video.fps ?: 30f, video.duration, audio != null,
                audio?.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC, (audio?.intOr(MediaFormat.KEY_BIT_RATE, 0) ?: 0) / 1000,
            )).takeIf { video.width > 0 && video.height > 0 && size > 0 }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            PickedMedia(uri, name, exifOrientation(exifTag(resolver, uri)).first, CompressSource(false, size, bounds.outWidth, bounds.outHeight)).takeIf { bounds.outWidth > 0 && bounds.outHeight > 0 && size > 0 }
        }
    }.getOrNull()
}

fun compressFolder(context: Context): File = File(context.getExternalFilesDir(null) ?: throw ExportError(txt(R.string.compress_no_storage)), "compress")

fun clearCompressed(context: Context) {
    runCatching { compressFolder(context).listFiles()?.forEach { it.delete() } }
}

internal fun outputName(picked: PickedMedia, extension: String, tag: String = "compressed"): String =
    picked.name.substringBeforeLast('.').ifBlank { "media" }.replace('/', '_') + (if (tag.isEmpty()) "" else "-$tag") + "." + extension

fun limitText(bytes: Long): String = if (bytes >= 1_000_000_000L) "${bytes / 1_000_000_000L} GB" else "${bytes / 1_000_000L} MB"

suspend fun compressVideo(context: Context, picked: PickedMedia, job: VideoJob, progress: (Float, Boolean) -> Unit): Compressed = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val folder = compressFolder(app).apply { mkdirs() }
    val out = File(folder, outputName(picked, "mp4"))
    val sound = File(folder, "sound.m4a")
    val uri = picked.uri.toString()
    val durationUs = picked.source.durationUs
    var finished = false
    try {
        val encoded = job.audioKbps > 0 && !job.copyAudio && !PlatformFeatures.desktop
        if (encoded) encodeSound(app, uri, durationUs, job.audioKbps, sound) { progress(it * 0.1f, false) }
        val soundFrom = if (job.audioKbps <= 0) null else if (encoded) Uri.fromFile(sound).toString() else uri
        val start = if (encoded) 0.1f else 0f
        var current = encodable(job)
        for (attempt in 0..3) {
            runVideo(app, uri, current, picked.rotation, soundFrom, out, durationUs) { progress(start + (1 - start) * it, attempt > 0) }
            val got = out.length()
            if (current.limitBytes <= 0 || got <= current.limitBytes) { finished = true; return@withContext Compressed(out, "video/mp4", true) }
            val next = smallerBitrate(current, durationUs, got)
            if (next < MIN_VIDEO_BITRATE) break
            current = current.copy(bitrate = next)
        }
        throw ExportError(txt(R.string.compress_over_limit, limitText(job.limitBytes)))
    } finally {
        sound.delete()
        if (!finished) out.delete()
    }
}

internal suspend fun runVideo(
    context: Context, uri: String, job: VideoJob, rotation: Int, soundFrom: String?, out: File, durationUs: Long,
    fromUs: Long = 0, toUs: Long = Long.MAX_VALUE, soundRanged: Boolean = false, progress: (Float) -> Unit,
) {
    if (PlatformFeatures.desktop) return compressVideoOnDesktop(uri, job, soundFrom != null, durationUs, out, progress, fromUs, if (toUs == Long.MAX_VALUE) 0 else toUs)
    val work = currentCoroutineContext().job
    val sound = soundFrom?.let { if (soundRanged) AudioCopy(context, it, fromUs, toUs) else AudioCopy(context, it) }
    try {
        val plan = VideoPlan(job.width, job.height, job.fps) {
            openVideoEncoder(ExportSettings(min(job.width, job.height), job.fps), job.width, job.height, false, bitrate = job.bitrate)
        }
        val turn = ((rotation % 360) + 360) % 360 / 90 * 90
        if (!transcode(context, uri, out, { !work.isActive }, { _, _, _ -> plan }, turn, sound, fromUs, toUs, progress)) throw CancellationException()
    } finally {
        sound?.close()
    }
}

private fun avcEncodes(width: Int, height: Int, fps: Int): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
    info.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in info.supportedTypes &&
        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities?.areSizeAndRateSupported(width, height, fps.toDouble()) == true
}

internal fun encodable(job: VideoJob): VideoJob {
    if (PlatformFeatures.desktop) return job
    if (avcEncodes(job.width, job.height, job.fps)) return job
    for (side in listOf(2160, 1440, 1080, 720, 480)) {
        val (w, h) = scaledToShort(job.width, job.height, side)
        if (min(w, h) >= min(job.width, job.height) || !avcEncodes(w, h, job.fps)) continue
        val area = w.toDouble() * h / (job.width.toDouble() * job.height)
        return job.copy(width = w, height = h, bitrate = (job.bitrate * area).toInt().coerceAtLeast(min(job.bitrate, MIN_VIDEO_BITRATE)))
    }
    return job
}

internal suspend fun encodeSound(context: Context, uri: String, durationUs: Long, kbps: Int, out: File, mp3: Boolean = false, fromUs: Long = 0, progress: (Float) -> Unit) {
    val ms = (durationUs / 1000).coerceAtLeast(1)
    val from = fromUs / 1000
    val layer = Layer(XmlNode("audio", linkedMapOf("id" to "1", "startTime" to "0", "endTime" to "$ms", "src" to uri, "outTime" to "${from + ms}", "mediaFillMode" to "fill").also {
        if (from > 0) it["inTime"] = "$from"
    }))
    OfflineMix({ PcmDecoder(context, it) }, listOf(AudioClip(layer, uri)), Resampler.ULTRA).use { mix ->
        RandomAccessFile(out, "rw").use { file ->
            file.setLength(0)
            exportAudio(ExportSettings(0, 30, audio = AudioSettings(AudioCodec.Aac, kbps), audioFormat = if (mp3) AudioFormat.Mp3 else AudioFormat.M4a), durationUs, mix, file.fd, progress)
        }
    }
}

suspend fun compressPicture(context: Context, picked: PickedMedia, job: PictureJob): Compressed = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val extension = if (job.webp) "webp" else "jpg"
    val out = File(compressFolder(context).apply { mkdirs() }, outputName(picked, extension))
    val side = pictureSide(job, picked.source.width, picked.source.height, memorySide())
    var picture = decodePicture(resolver, picked.uri, side) ?: throw ExportError(txt(R.string.compress_unreadable))
    var finished = false
    try {
        val turn = picked.rotation
        if (turn != 0) picture = replaced(picture, Bitmap.createBitmap(picture, 0, 0, picture.width, picture.height, Matrix().apply { postRotate(turn.toFloat()) }, true))
        if (!job.webp && picture.hasAlpha()) picture = replaced(picture, Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(picture, 0f, 0f, null) }
        })
        val format = if (job.webp) webpFormat(false) else Bitmap.CompressFormat.JPEG
        var quality = job.quality
        repeat(8) {
            currentCoroutineContext().ensureActive()
            out.outputStream().use { stream -> exportCheck(picture.compress(format, quality, stream)) { txt(R.string.compress_unreadable) } }
            if (job.limitBytes <= 0 || out.length() <= job.limitBytes) {
                finished = true
                return@withContext Compressed(out, if (job.webp) "image/webp" else "image/jpeg", false)
            }
            if (quality > 40) quality -= 15
            else picture = replaced(picture, Bitmap.createScaledBitmap(picture, (picture.width * 3 / 4).coerceAtLeast(1), (picture.height * 3 / 4).coerceAtLeast(1), true))
        }
        throw ExportError(txt(R.string.compress_over_limit, limitText(job.limitBytes)))
    } finally {
        picture.recycle()
        if (!finished) out.delete()
    }
}

private fun replaced(old: Bitmap, new: Bitmap): Bitmap {
    if (new !== old) old.recycle()
    return new
}

suspend fun saveToGallery(context: Context, result: Compressed): String = withContext(Dispatchers.IO) {
    MediaStoreExport(context).save(null, result.file.name, result.mime) { out -> result.file.inputStream().use { it.copyTo(out) } }
    (if (result.mime.startsWith("audio/")) Environment.DIRECTORY_MUSIC else if (result.video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + AppFolder
}

fun shareCompressed(context: Context, result: Compressed) =
    shareExport(context, ExportResult(listOf(FileProvider.getUriForFile(context, "${context.packageName}.files", result.file)), result.mime, ""))

fun sizeChange(before: Long, after: Long): Pair<String, Int> =
    "${formatBytes(before)} → ${formatBytes(after)}" to if (before > 0) ((1 - after.toDouble() / before) * 100).toInt() else 0
