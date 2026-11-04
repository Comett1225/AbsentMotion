package app.absentmotion.desk

import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import java.io.File

object DeskLibrary {
    private const val RECENT_LIMIT = 500
    private const val FOLDER_LIMIT = 4000
    private const val DEPTH = 8
    private val PACKAGES = setOf("app", "band", "logicx", "photoslibrary", "musiclibrary", "tvlibrary", "bundle", "framework", "plugin", "component")
    private val prefs: SharedPreferences get() = SharedPreferences.named("desk_library")
    val revision = mutableIntStateOf(0)

    private fun read(key: String): List<File> = prefs.getString(key, null).orEmpty().split('\n').filter { it.isNotBlank() }.map(::File)

    private fun write(key: String, files: List<File>) {
        prefs.edit().putString(key, files.joinToString("\n") { it.absolutePath }).commit()
        revision.intValue++
        android.content.ContentResolver.notifyLibraryChanged()
    }

    val folders: List<File> get() = read("folders")

    @Synchronized fun remember(files: List<File>) {
        val picked = files.filter { it.isFile }.map { it.absoluteFile }
        if (picked.isEmpty()) return
        write("hidden", read("hidden").filter { it !in picked })
        write("recent", (picked + read("recent").filter { it !in picked }).take(RECENT_LIMIT))
    }

    @Synchronized fun link(folder: File) {
        if (!folder.isDirectory) return
        write("hidden", read("hidden").filter { !it.startsWith(folder.absoluteFile) })
        write("folders", (listOf(folder.absoluteFile) + read("folders").filter { it != folder.absoluteFile }))
    }

    @Synchronized fun unlink(folder: File) = write("folders", read("folders").filter { it != folder.absoluteFile })

    fun folderOf(uri: android.net.Uri): File? {
        val file = uri.toFile()?.absoluteFile ?: return null
        return folders.firstOrNull { file.startsWith(it) }
    }

    @Synchronized fun hide(uri: android.net.Uri, unlinkFolder: Boolean) {
        val file = uri.toFile()?.absoluteFile ?: return
        val folder = folderOf(uri)
        write("recent", read("recent").filter { it != file })
        if (unlinkFolder && folder != null) unlink(folder) else if (folder != null) write("hidden", read("hidden") + file)
    }

    fun files(extensions: Set<String>): List<File> {
        val found = LinkedHashSet<File>()
        val hidden = read("hidden").toHashSet()
        read("recent").filterTo(found) { it.extension.lowercase() in extensions && it.isFile }
        for (root in folders) {
            if (found.size >= FOLDER_LIMIT || !root.isDirectory) continue
            root.walkTopDown().maxDepth(DEPTH)
                .onEnter { it == root || (!it.name.startsWith(".") && it.extension.lowercase() !in PACKAGES) }
                .filter { it.isFile && it !in hidden && !it.name.startsWith(".") && it.extension.lowercase() in extensions && it.length() > 0 }
                .take(FOLDER_LIMIT - found.size).forEach { found += it }
        }
        return found.toList()
    }

    fun chooseFolder(): File? {
        if (DeskOs.linux) return javax.swing.JFileChooser().apply { dialogTitle = "Link a media folder"; fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY }
            .takeIf { it.showOpenDialog(DeskFiles.window) == javax.swing.JFileChooser.APPROVE_OPTION }?.selectedFile?.takeIf { it.isDirectory }
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = java.awt.FileDialog(DeskFiles.window, "Link a media folder", java.awt.FileDialog.LOAD)
            dialog.isVisible = true
            return dialog.files.orEmpty().firstOrNull()?.takeIf { it.isDirectory }
        } finally { System.setProperty("apple.awt.fileDialogForDirectories", "false") }
    }
}
