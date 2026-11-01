package android.content.res

import app.absentmotion.desk.Strings

object Resources {
    fun getString(id: Int): String = Strings.get(id)
    fun getString(id: Int, vararg args: Any?): String = Strings.get(id, *args)
    fun getText(id: Int): CharSequence = Strings.get(id)
    fun getQuantityString(id: Int, count: Int, vararg args: Any?): String = Strings.plural(id, count, *args)
    fun getIdentifier(name: String, type: String, pkg: String): Int = 0
    val displayMetrics = android.content.DisplayMetrics()
    val configuration = Configuration()
    @JvmStatic fun getSystem() = this
}

class AssetFileDescriptor(val file: java.io.File) : java.io.Closeable {
    val length: Long get() = file.length()
    override fun close() {}
}
