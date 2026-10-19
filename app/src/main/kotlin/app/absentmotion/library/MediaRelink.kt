package app.absentmotion.library

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.replaceMedia
import app.absentmotion.model.withLegacyExifOrientation
import app.absentmotion.media.exifTag

object MediaRelink {
    fun opens(context: Context, uri: String) =
        runCatching { context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } }.getOrNull() == true

    fun find(context: Context, media: MediaDeclaration): String? {
        if (media.filename.isEmpty()) return null
        val collection = when {
            media.type.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            media.type.startsWith("image") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            media.type.startsWith("audio") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> return null
        }
        val sized = media.size > 0
        return runCatching {
            context.contentResolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?" + if (sized) " AND ${MediaStore.MediaColumns.SIZE} = ?" else "",
                if (sized) arrayOf(media.filename, media.size.toString()) else arrayOf(media.filename), null,
            )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()).toString() else null }
        }.getOrNull()
    }

    fun relink(context: Context, scene: Scene): Scene = scene.media.fold(scene) { s, m ->
        val uri = if (opens(context, m.uri)) m.uri else find(context, m)
        if (uri == null) s else {
            val linked = if (uri == m.uri) s else s.replaceMedia(m.uri, MediaDeclaration(m.node.with("uri", uri).with("missing", null)))
            if (m.type.startsWith("image/") && m.orientation == 0) linked.withLegacyExifOrientation(uri, exifTag(context.contentResolver, Uri.parse(uri))) else linked
        }
    }
}
