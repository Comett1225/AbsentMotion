package android.database

import android.net.Uri

abstract class ContentObserver(handler: android.os.Handler?) {
    open fun onChange(selfChange: Boolean) {}
    open fun onChange(selfChange: Boolean, uri: Uri?) = onChange(selfChange)
    open fun deliverSelfNotifications() = false
}

interface Cursor : java.io.Closeable {
    val count: Int
    val position: Int
    val columnCount: Int
    val columnNames: Array<String>
    val isClosed: Boolean
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun moveToPosition(position: Int): Boolean
    fun getColumnIndex(name: String): Int
    fun getColumnIndexOrThrow(name: String): Int
    fun getString(index: Int): String?
    fun getLong(index: Int): Long
    fun getInt(index: Int): Int
    fun getFloat(index: Int): Float
    fun getDouble(index: Int): Double
    fun isNull(index: Int): Boolean
    fun getType(index: Int): Int
    fun getBlob(index: Int): ByteArray?
}
