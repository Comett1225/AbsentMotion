package android.content

import android.net.Uri

object ContentUris {
    @JvmStatic fun withAppendedId(uri: Uri, id: Long): Uri = app.absentmotion.desk.DeskGallery.fileOf(id)?.let(Uri::fromFile) ?: Uri.parse("$uri/$id")
    @JvmStatic fun parseId(uri: Uri): Long = uri.lastPathSegment?.toLongOrNull() ?: -1
}
