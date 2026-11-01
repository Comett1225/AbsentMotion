package androidx.exifinterface.media

import java.io.File
import java.io.InputStream

class ExifInterface private constructor(private val values: MutableMap<String, String>) {
    constructor(stream: InputStream) : this(mutableMapOf())
    constructor(file: File) : this(mutableMapOf())
    constructor(path: String) : this(mutableMapOf())
    fun getAttributeInt(tag: String, default: Int): Int = values[tag]?.toIntOrNull() ?: default
    fun getAttribute(tag: String): String? = values[tag]
    fun setAttribute(tag: String, value: String?) { if (value == null) values.remove(tag) else values[tag] = value }
    fun saveAttributes() {}
    val rotationDegrees: Int get() = 0
    companion object {
        const val ORIENTATION_NORMAL = 1
        const val ORIENTATION_ROTATE_90 = 6
        const val ORIENTATION_ROTATE_180 = 3
        const val ORIENTATION_ROTATE_270 = 8
        const val TAG_ORIENTATION = "Orientation"
        const val TAG_DATETIME = "DateTime"
        const val TAG_DATETIME_DIGITIZED = "DateTimeDigitized"
        const val TAG_DATETIME_ORIGINAL = "DateTimeOriginal"
        const val TAG_GPS_DATESTAMP = "GPSDateStamp"
        const val TAG_GPS_TIMESTAMP = "GPSTimeStamp"
        const val TAG_OFFSET_TIME = "OffsetTime"
        const val TAG_OFFSET_TIME_DIGITIZED = "OffsetTimeDigitized"
        const val TAG_OFFSET_TIME_ORIGINAL = "OffsetTimeOriginal"
        const val TAG_SUBSEC_TIME = "SubSecTime"
        const val TAG_SUBSEC_TIME_DIGITIZED = "SubSecTimeDigitized"
        const val TAG_SUBSEC_TIME_ORIGINAL = "SubSecTimeOriginal"
    }
}
