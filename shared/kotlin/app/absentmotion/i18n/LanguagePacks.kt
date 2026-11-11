package app.absentmotion.i18n

import app.absentmotion.platform.Context
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

object LanguagePacks {
    private fun root(context: Context) = File(context.filesDir, "lang")

    fun file(context: Context, tag: String, path: String): File? = File(root(context), "$tag/$path").takeIf { it.isFile }

    fun open(context: Context, tag: String, path: String): InputStream = file(context, tag, path)?.inputStream() ?: context.assets.open(path)

    @Synchronized
    fun prepare(context: Context, tag: String): Boolean = runCatching {
        if (tag == "en") return true
        val pack = runCatching { context.assets.open("langpacks/$tag.pack") }.getOrNull() ?: return true
        DataInputStream(GZIPInputStream(pack.buffered())).use { input ->
            val stamp = input.readUTF()
            val done = File(root(context), tag)
            val mark = File(root(context), "$tag.stamp")
            if (done.isDirectory && runCatching { mark.readText() }.getOrNull() == stamp) return true
            val temp = File(root(context), "$tag.part").apply { deleteRecursively() }
            val inside = temp.canonicalPath + File.separator
            while (true) {
                val path = input.readUTF()
                if (path.isEmpty()) break
                val target = File(temp, path)
                check(target.canonicalPath.startsWith(inside))
                val bytes = ByteArray(input.readInt())
                input.readFully(bytes)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            mark.delete()
            done.deleteRecursively()
            check(temp.renameTo(done))
            mark.writeText(stamp)
        }
        true
    }.getOrDefault(false)
}
