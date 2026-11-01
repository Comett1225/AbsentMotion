package android.provider

import android.net.Uri

object MediaStore {
    const val MATCH_INCLUDE = 1
    const val QUERY_ARG_MATCH_PENDING = "android:query-arg-match-pending"
    const val UNKNOWN_STRING = "<unknown>"
    const val VOLUME_EXTERNAL_PRIMARY = "external_primary"
    const val VOLUME_EXTERNAL = "external"
    @JvmStatic fun setIncludePending(uri: Uri): Uri = uri
    object MediaColumns {
        const val _ID = "_id"; const val DATA = "_data"; const val DISPLAY_NAME = "_display_name"; const val IS_PENDING = "is_pending"
        const val MIME_TYPE = "mime_type"; const val RELATIVE_PATH = "relative_path"; const val SIZE = "_size"
        const val DATE_ADDED = "date_added"; const val DATE_MODIFIED = "date_modified"; const val DURATION = "duration"
        const val WIDTH = "width"; const val HEIGHT = "height"; const val ORIENTATION = "orientation"; const val BUCKET_DISPLAY_NAME = "bucket_display_name"
    }
    object Images {
        object Media { @JvmField val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/images/media"); @JvmStatic fun getContentUri(volume: String): Uri = EXTERNAL_CONTENT_URI }
        object Thumbnails { const val MINI_KIND = 1; @JvmStatic fun getThumbnail(resolver: android.content.ContentResolver, id: Long, kind: Int, options: android.graphics.BitmapFactory.Options?): android.graphics.Bitmap? = null }
    }
    object Video {
        object Media { @JvmField val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/video/media"); @JvmStatic fun getContentUri(volume: String): Uri = EXTERNAL_CONTENT_URI }
        object Thumbnails { const val MINI_KIND = 1; @JvmStatic fun getThumbnail(resolver: android.content.ContentResolver, id: Long, kind: Int, options: android.graphics.BitmapFactory.Options?): android.graphics.Bitmap? = null }
    }
    object Audio {
        object Media { @JvmField val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/audio/media"); @JvmStatic fun getContentUri(volume: String): Uri = EXTERNAL_CONTENT_URI }
    }
    object Files { @JvmStatic fun getContentUri(volume: String): Uri = Uri.parse("content://media/external/file") }
}

object OpenableColumns {
    const val DISPLAY_NAME = "_display_name"
    const val SIZE = "_size"
}
