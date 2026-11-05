package app.absentmotion.desk

import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import java.io.File

object DeskLibrary {
    private const val RECENT_LIMIT = 500
    private const val FOLDER_LIMIT = 4000
    private const val DEPTH = 8
    private val PACKAGES = setOf("app", "band", "logicx", "photoslibrary", "musiclibrary", "tvlibrary", "bundle", "framework", "plugin", "component")
    private val prefs: SharedPreferences get() = SharedPreferences.named("desk_library").also { if (it.getInt("version", 0) < 2) migrate(it) }
    val revision = mutableIntStateOf(0)

    @Volatile private var project = ""
    @Volatile private var used = emptySet<File>()

    private fun read(key: String): List<File> = prefs.getString(key, null).orEmpty().split('\n').filter { it.isNotBlank() }.map(::File)

    private fun write(key: String, files: List<File>) {
        prefs.edit().putString(key, files.joinToString("\n") { it.absolutePath }).commit()
        revision.intValue++
        android.content.ContentResolver.notifyLibraryChanged()
    }

    private fun migrate(old: SharedPreferences) {
        val folders = old.getString("folders", null).orEmpty().split('\n').filter { it.isNotBlank() }
        val pinned = (old.getString("pinned", null).orEmpty().split('\n').filter { it.isNotBlank() } + folders).distinct()
        old.edit().putString("pinned", pinned.joinToString("\n")).remove("folders").remove("recent").putString("version", "2").commit()
    }

    fun open(id: String, mediaUris: List<String>) {
        project = id
        val waiting = read("recent:")
        if (waiting.isNotEmpty() && id.isNotEmpty()) {
            write("recent:", emptyList())
            write("recent:$id", (waiting + read("recent:$id").filter { it !in waiting }).take(RECENT_LIMIT))
        }
        used = mediaUris.mapNotNull { runCatching { android.net.Uri.parse(it).toFile()?.absoluteFile }.getOrNull() }.filter { it.isFile }.toSet()
        revision.intValue++
        android.content.ContentResolver.notifyLibraryChanged()
    }

    fun close() {
        project = ""
        used = emptySet()
    }

    val pinned: List<File> get() = read("pinned")

    val folders: List<File> get() = (read("folders:$project") + pinned).distinct()

    fun isPinned(folder: File) = folder.absoluteFile in pinned

    @Synchronized fun pin(folder: File, on: Boolean) {
        val root = folder.absoluteFile
        write("pinned", if (on) listOf(root) + pinned.filter { it != root } else pinned.filter { it != root })
    }

    @Synchronized fun remember(files: List<File>) {
        val picked = files.filter { it.isFile }.map { it.absoluteFile }
        if (picked.isEmpty()) return
        write("hidden", read("hidden").filter { it !in picked })
        write("recent:$project", (picked + read("recent:$project").filter { it !in picked }).take(RECENT_LIMIT))
    }

    @Synchronized fun link(folder: File) {
        if (!folder.isDirectory) return
        write("hidden", read("hidden").filter { !it.startsWith(folder.absoluteFile) })
        if (project.isEmpty()) return pin(folder, true)
        write("folders:$project", (listOf(folder.absoluteFile) + read("folders:$project").filter { it != folder.absoluteFile }))
    }

    @Synchronized fun unlink(folder: File) {
        val root = folder.absoluteFile
        write("folders:$project", read("folders:$project").filter { it != root })
        if (root in pinned) write("pinned", pinned.filter { it != root })
    }

    fun folderOf(uri: android.net.Uri): File? {
        val file = uri.toFile()?.absoluteFile ?: return null
        return folders.firstOrNull { file.startsWith(it) }
    }

    @Synchronized fun hide(uri: android.net.Uri, unlinkFolder: Boolean) {
        val file = uri.toFile()?.absoluteFile ?: return
        val folder = folderOf(uri)
        write("recent:$project", read("recent:$project").filter { it != file })
        if (unlinkFolder && folder != null) unlink(folder) else if (folder != null) write("hidden", read("hidden") + file)
    }

    fun files(extensions: Set<String>): List<File> {
        val found = LinkedHashSet<File>()
        val hidden = read("hidden").toHashSet()
        read("recent:$project").filterTo(found) { it.extension.lowercase() in extensions && it.isFile }
        used.filterTo(found) { it.extension.lowercase() in extensions }
        for (root in folders) {
            if (found.size >= FOLDER_LIMIT || !root.isDirectory) continue
            root.walkTopDown().maxDepth(DEPTH)
                .onEnter { it == root || (!it.name.startsWith(".") && it.extension.lowercase() !in PACKAGES) }
                .filter { it.isFile && it !in hidden && !it.name.startsWith(".") && it.extension.lowercase() in extensions && it.length() > 0 }
                .take(FOLDER_LIMIT - found.size).forEach { found += it }
        }
        return found.toList()
    }

    fun chooseFolder(): File? = DeskPicker.openFolder("Link a media folder")
}
