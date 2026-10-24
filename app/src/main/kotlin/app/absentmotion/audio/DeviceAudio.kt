package app.absentmotion.audio

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.XmlNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class DeviceAudio(val uri: String, val title: String, val durationMs: Long, val date: Long, val name: String = "", val album: String = "",
                       val artist: String = "", val folder: String = "")

enum class SoundSort { Name, Added, Length }

enum class SoundGroup { Songs, Albums, Artists, Folders }

data class SoundView(val sort: SoundSort = SoundSort.Added, val descending: Boolean = true, val group: SoundGroup = SoundGroup.Songs) {
    val text: String get() = "${sort.name},$descending,${group.name}"

    companion object {
        fun parse(text: String?): SoundView {
            val parts = text.orEmpty().split(',')
            return SoundView(
                SoundSort.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: SoundSort.Added,
                parts.getOrNull(1)?.toBooleanStrictOrNull() ?: true,
                SoundGroup.entries.firstOrNull { it.name == parts.getOrNull(2) } ?: SoundGroup.Songs,
            )
        }
    }
}

class SoundShelf(val key: String, val label: String, val sounds: List<DeviceAudio>)

fun soundCollator(locale: Locale = Locale.getDefault()): Comparator<String> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    return Comparator { a, b -> naturalCompare(a, b) { x, y -> collator.compare(x, y) } }
}

fun naturalCompare(a: String, b: String, text: (String, String) -> Int): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val digitsA = a[i].isDigit()
        val digitsB = b[j].isDigit()
        var endA = i
        while (endA < a.length && a[endA].isDigit() == digitsA) endA++
        var endB = j
        while (endB < b.length && b[endB].isDigit() == digitsB) endB++
        val result = if (digitsA && digitsB) {
            val x = a.substring(i, endA).trimStart('0')
            val y = b.substring(j, endB).trimStart('0')
            if (x.length != y.length) x.length - y.length else x.compareTo(y)
        } else text(a.substring(i, endA), b.substring(j, endB))
        if (result != 0) return result
        i = endA
        j = endB
    }
    return (a.length - i) - (b.length - j)
}

fun List<DeviceAudio>.sortedSounds(view: SoundView, names: Comparator<String> = soundCollator()): List<DeviceAudio> {
    val byName = compareBy(names) { it: DeviceAudio -> it.title.ifBlank { it.name } }.thenBy(names) { it.name }
    val primary = when (view.sort) {
        SoundSort.Name -> byName
        SoundSort.Added -> compareBy { it.date }
        SoundSort.Length -> compareBy { it.durationMs }
    }
    return sortedWith((if (view.descending) primary.reversed() else primary).then(byName).thenBy { it.uri })
}

fun DeviceAudio.shelfKey(group: SoundGroup): String = when (group) {
    SoundGroup.Songs -> ""
    SoundGroup.Albums -> album
    SoundGroup.Artists -> artist
    SoundGroup.Folders -> folder
}

fun List<DeviceAudio>.shelves(view: SoundView, names: Comparator<String> = soundCollator()): List<SoundShelf> {
    val shelves = groupBy { it.shelfKey(view.group).trim() }.map { (key, sounds) ->
        SoundShelf(key, if (view.group == SoundGroup.Folders) key.trimEnd('/').substringAfterLast('/') else key, sounds.sortedSounds(view, names))
    }
    val order = compareBy<SoundShelf> { it.key.isEmpty() }.then(compareBy(names) { it: SoundShelf -> it.label }).thenBy(names) { it.key }
    val known = shelves.filter { it.key.isNotEmpty() }.sortedWith(order)
    return (if (view.sort == SoundSort.Name && view.descending) known.reversed() else known) + shelves.filter { it.key.isEmpty() }
}

private fun known(value: String?): String = value?.takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty()

suspend fun deviceAudio(context: Context): List<DeviceAudio> = withContext(Dispatchers.IO) {
    val found = mutableListOf<DeviceAudio>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val place = if (Build.VERSION.SDK_INT >= 29) "relative_path" else "_data"
    try {
        context.contentResolver.query(collection, arrayOf("_id", "title", "_display_name", "duration", "date_added", "album", "artist", place),
            "duration > 0", null, "date_added DESC, _id DESC")?.use { c ->
            while (c.moveToNext()) {
                val where = c.getString(7).orEmpty()
                found += DeviceAudio(ContentUris.withAppendedId(collection, c.getLong(0)).toString(),
                    c.getString(1) ?: c.getString(2) ?: "", c.getLong(3), c.getLong(4), c.getString(2) ?: "", known(c.getString(5)), known(c.getString(6)),
                    if (Build.VERSION.SDK_INT >= 29) where.trimEnd('/') else where.substringBeforeLast('/', ""))
            }
        }
    } catch (_: Exception) { }
    found
}

suspend fun audioDeclaration(context: Context, uri: Uri): MediaDeclaration? = withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return@withContext null
        var name = uri.lastPathSegment ?: ""
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1) }
        }
        MediaDeclaration(XmlNode("media", linkedMapOf("uri" to uri.toString(), "filename" to name, "title" to name,
            "type" to (context.contentResolver.getType(uri) ?: "audio/mp4"),
            "duration" to "${if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L}",
            "size" to "$size", "infoUpdated" to "${System.currentTimeMillis()}")))
    } catch (_: Exception) { null } finally { extractor.release() }
}
