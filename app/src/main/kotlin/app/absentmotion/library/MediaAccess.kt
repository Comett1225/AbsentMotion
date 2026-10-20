package app.absentmotion.library

import app.absentmotion.platform.Context
import java.io.File
import java.net.URI

internal fun modelFolder(uri: String): File? {
    if (!uri.startsWith("file:", ignoreCase = true)) return null
    return runCatching { File(URI(uri)).canonicalFile.parentFile }.getOrNull()?.takeIf { it.name.startsWith("model-") && it.isDirectory }
}

class MediaAccess private constructor(private val filesDir: File?, private val own: List<File> = emptyList()) {
    fun allows(uri: String): Boolean {
        if (filesDir == null) return true
        return when (uri.substringBefore(':', "").lowercase()) {
            "content" -> uri.startsWith("content://", ignoreCase = true) && uri.substring("content://".length).substringBefore('/').isNotEmpty()
            "file" -> inMediaFolder(filesDir, uri) || own.any { inside(it, uri) }
            else -> false
        }
    }

    fun defused(uri: String): String = if (uri.startsWith("file:", ignoreCase = true) && !allows(uri)) "blocked-$uri" else uri

    fun imported(uri: String): String = if (filesDir != null && uri.startsWith("content:", ignoreCase = true)) "blocked-$uri" else defused(uri)

    companion object {
        val Unlimited = MediaAccess(null)

        fun forPhone(filesDir: File, vararg own: File?) = MediaAccess(filesDir, own.filterNotNull())

        fun forApp(context: Context): MediaAccess = if (onPhone) forPhone(context.filesDir) else Unlimited

        private val onPhone = System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)

        private fun inside(dir: File, uri: String): Boolean = try {
            val base = dir.canonicalFile.toPath()
            val path = File(URI(uri)).canonicalFile.toPath()
            path.startsWith(base) && path != base
        } catch (e: Exception) {
            false
        }

        private fun inMediaFolder(filesDir: File, uri: String): Boolean = try {
            val base = filesDir.canonicalFile.toPath()
            val path = File(URI(uri)).canonicalFile.toPath()
            val inside = if (path.startsWith(base) && path != base) base.relativize(path) else null
            when (inside?.getName(0)?.toString()) {
                "media" -> inside.nameCount > 1
                "templates" -> inside.nameCount > 3 && inside.getName(2).toString() == "media"
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
}
