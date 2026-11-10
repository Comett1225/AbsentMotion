package androidx.exifinterface.media

import java.io.File
import java.io.InputStream

class ExifInterface private constructor(private val values: MutableMap<String, String>, private val bytes: () -> ByteArray?) {
    constructor(stream: InputStream) : this(mutableMapOf(), stream.readBytes().let { read -> { read } })
    constructor(file: File) : this(mutableMapOf(), { runCatching { file.readBytes() }.getOrNull() })
    constructor(path: String) : this(File(path))
    private val orientation: Int by lazy {
        runCatching {
            org.jetbrains.skia.Data.makeFromBytes(bytes()!!).use { data -> org.jetbrains.skia.Codec.makeFromData(data).use { it.encodedOrigin.ordinal } }
        }.getOrNull()?.takeIf { it in 1..8 } ?: ORIENTATION_NORMAL
    }
    fun getAttributeInt(tag: String, default: Int): Int = values[tag]?.toIntOrNull() ?: if (tag == TAG_ORIENTATION) orientation else default
    fun getAttribute(tag: String): String? = values[tag]
    fun setAttribute(tag: String, value: String?) { if (value == null) values.remove(tag) else values[tag] = value }
    fun saveAttributes() {}
    val rotationDegrees: Int get() = when (getAttributeInt(TAG_ORIENTATION, ORIENTATION_NORMAL)) { 6, 7 -> 90; 3, 4 -> 180; 5, 8 -> 270; else -> 0 }
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
