package android.database

class DeskCursor(override val columnNames: Array<String>, private val rows: List<Array<Any?>>) : Cursor {
    override var position = -1
        private set
    override var isClosed = false
        private set
    override val count: Int get() = rows.size
    override val columnCount: Int get() = columnNames.size
    override fun moveToFirst() = moveToPosition(0)
    override fun moveToNext() = moveToPosition(position + 1)
    override fun moveToPosition(position: Int): Boolean { this.position = position.coerceIn(-1, rows.size); return position in rows.indices }
    override fun getColumnIndex(name: String) = columnNames.indexOf(name)
    override fun getColumnIndexOrThrow(name: String) = getColumnIndex(name).also { require(it >= 0) { "No column $name" } }
    private fun at(i: Int): Any? = rows[position][i]
    override fun getString(index: Int): String? = at(index)?.toString()
    override fun getLong(index: Int): Long = (at(index) as? Number)?.toLong() ?: at(index)?.toString()?.toLongOrNull() ?: 0L
    override fun getInt(index: Int): Int = getLong(index).toInt()
    override fun getFloat(index: Int): Float = (at(index) as? Number)?.toFloat() ?: 0f
    override fun getDouble(index: Int): Double = (at(index) as? Number)?.toDouble() ?: 0.0
    override fun isNull(index: Int) = at(index) == null
    override fun getType(index: Int): Int = when (at(index)) { null -> 0; is Long, is Int -> 1; is Float, is Double -> 2; is ByteArray -> 4; else -> 3 }
    override fun getBlob(index: Int): ByteArray? = at(index) as? ByteArray
    override fun close() { isClosed = true }
}
