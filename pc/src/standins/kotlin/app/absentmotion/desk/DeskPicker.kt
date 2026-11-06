package app.absentmotion.desk

import androidx.compose.ui.awt.ComposeWindow
import app.absentmotion.pc.engine.DesktopNative
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Toolkit
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

object DeskPicker {
    class Filter(val name: String, val extensions: Collection<String>)

    internal const val OPEN = 0
    internal const val FOLDER = 1
    internal const val SAVE = 2
    private var showing = false

    fun openFiles(title: String, filters: List<Filter> = emptyList(), multiple: Boolean = false): List<File> = pick(OPEN, multiple, title, "", filters)

    fun openFolder(title: String): File? = pick(FOLDER, false, title, "", emptyList()).firstOrNull()?.takeIf { it.isDirectory }

    fun saveFile(title: String, suggestedName: String, filter: Filter? = null): File? = pick(SAVE, false, title, suggestedName, listOfNotNull(filter)).firstOrNull()

    private fun pick(mode: Int, multiple: Boolean, title: String, name: String, filters: List<Filter>): List<File> {
        if (showing) return emptyList()
        showing = true
        try {
            fun owner() = runCatching { (DeskFiles.window as? ComposeWindow)?.windowHandle }.getOrNull() ?: 0L
            val system = when {
                DeskOs.windows -> {
                    val window = owner()
                    waiting { DesktopNative.pickFiles(window, mode, multiple, title, name, windowsFilters(filters))?.map(::File) }
                }
                DeskOs.linux -> {
                    val x11 = if (Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit") owner() else 0L
                    waiting { portal(x11, mode, multiple, title, name, filters) ?: tool(mode, multiple, title, name, filters) }
                }
                else -> null
            }
            return system ?: if (DeskOs.mac || DeskOs.windows && mode != FOLDER) awt(mode, multiple, title, name, filters) else swing(mode, multiple, title, name, filters)
        } finally { showing = false }
    }

    private fun waiting(work: () -> List<File>?): List<File>? {
        if (!EventQueue.isDispatchThread()) return runCatching(work).getOrNull()
        val result = CompletableFuture<List<File>?>()
        val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        EventQueue.invokeLater {
            kotlin.concurrent.thread(name = "Picker", isDaemon = true) {
                result.complete(runCatching(work).getOrNull())
                loop.exit()
            }
        }
        loop.enter()
        return result.getNow(null)
    }

    private fun portal(owner: Long, mode: Int, multiple: Boolean, title: String, name: String, filters: List<Filter>): List<File>? =
        runCatching { DesktopNative.pickFiles(owner, mode, multiple, title, name, linuxFilters(filters))?.mapNotNull(::fileOf) }.getOrNull()

    private fun tool(mode: Int, multiple: Boolean, title: String, name: String, filters: List<Filter>): List<File>? {
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotEmpty() }
        fun has(program: String) = path.any { File(it, program).canExecute() }
        val tool = linuxTool(System.getenv("XDG_CURRENT_DESKTOP"), has("kdialog"), has("zenity")) ?: return null
        return runCatching {
            val process = ProcessBuilder(toolCommand(tool, mode, multiple, title, name, filters, System.getProperty("user.home")))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val output = process.inputStream.bufferedReader().readText()
            when (process.waitFor()) {
                0 -> output.lines().filter { it.isNotEmpty() }.map(::File)
                1 -> emptyList()
                else -> null
            }
        }.getOrNull()
    }

    private fun awt(mode: Int, multiple: Boolean, title: String, name: String, filters: List<Filter>): List<File> {
        if (mode == FOLDER) System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(DeskFiles.window, title, if (mode == SAVE) FileDialog.SAVE else FileDialog.LOAD)
            dialog.isMultipleMode = multiple
            if (mode == SAVE) dialog.file = name
            val extensions = filters.flatMap { it.extensions }.toSet()
            if (mode == OPEN && extensions.isNotEmpty()) dialog.setFilenameFilter { _, file -> file.substringAfterLast('.', "").lowercase() in extensions }
            dialog.isVisible = true
            return dialog.files.orEmpty().toList()
        } finally { if (mode == FOLDER) System.setProperty("apple.awt.fileDialogForDirectories", "false") }
    }

    private fun swing(mode: Int, multiple: Boolean, title: String, name: String, filters: List<Filter>): List<File> {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.fileSelectionMode = if (mode == FOLDER) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
        chooser.isMultiSelectionEnabled = multiple
        if (mode == SAVE) chooser.selectedFile = File(chooser.currentDirectory, name)
        filters.forEach { chooser.addChoosableFileFilter(FileNameExtensionFilter(it.name, *it.extensions.toTypedArray())) }
        chooser.choosableFileFilters.getOrNull(1)?.let { chooser.fileFilter = it }
        val answer = if (mode == SAVE) chooser.showSaveDialog(DeskFiles.window) else chooser.showOpenDialog(DeskFiles.window)
        if (answer != JFileChooser.APPROVE_OPTION) return emptyList()
        return if (multiple) chooser.selectedFiles.toList().ifEmpty { listOfNotNull(chooser.selectedFile) } else listOfNotNull(chooser.selectedFile)
    }

    internal fun windowsFilters(filters: List<Filter>): Array<String> =
        filters.flatMap { filter -> listOf(filter.name, filter.extensions.joinToString(";") { "*.$it" }) }.toTypedArray()

    internal fun glob(extension: String, separator: String): String =
        listOf(extension.lowercase(), extension.uppercase()).distinct().joinToString(separator) { "*.$it" }

    internal fun linuxFilters(filters: List<Filter>): Array<String> =
        filters.flatMap { filter -> listOf(filter.name, filter.extensions.joinToString(";") { glob(it, ";") }) }.toTypedArray()

    internal fun linuxTool(desktop: String?, kdialog: Boolean, zenity: Boolean): String? {
        val kde = desktop.orEmpty().split(':').any { it.equals("KDE", ignoreCase = true) }
        return when {
            kde && kdialog -> "kdialog"
            zenity -> "zenity"
            kdialog -> "kdialog"
            else -> null
        }
    }

    internal fun toolCommand(tool: String, mode: Int, multiple: Boolean, title: String, name: String, filters: List<Filter>, home: String): List<String> =
        if (tool == "kdialog") buildList {
            add("kdialog"); add("--title"); add(title)
            when (mode) {
                FOLDER -> { add("--getexistingdirectory"); add(home) }
                SAVE -> { add("--getsavefilename"); add(home.trimEnd('/') + "/" + name) }
                else -> { add("--getopenfilename"); add(home) }
            }
            if (mode != FOLDER && filters.isNotEmpty()) add(filters.joinToString("\n") { filter -> filter.extensions.joinToString(" ") { glob(it, " ") } + "|" + filter.name })
            if (mode == OPEN && multiple) { add("--multiple"); add("--separate-output") }
        } else buildList {
            add("zenity"); add("--file-selection"); add("--title=$title")
            if (mode == FOLDER) add("--directory")
            if (mode == SAVE) { add("--save"); add("--filename=" + home.trimEnd('/') + "/" + name) }
            if (mode == OPEN && multiple) { add("--multiple"); add("--separator=\n") }
            if (mode != FOLDER) filters.forEach { filter -> add("--file-filter=${filter.name} | " + filter.extensions.joinToString(" ") { glob(it, " ") }) }
        }

    internal fun fileOf(uri: String): File? {
        if (!uri.startsWith("file://", ignoreCase = true)) return null
        val rest = uri.substring(7)
        val path = when {
            rest.startsWith("/") -> rest
            rest.startsWith("localhost/", ignoreCase = true) -> rest.substring(9)
            else -> return null
        }
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < path.length) {
            val code = if (path[i] == '%' && i + 3 <= path.length) path.substring(i + 1, i + 3).takeIf { hex -> hex.all { it.isLetterOrDigit() } }?.toIntOrNull(16) else null
            if (code != null) {
                bytes.write(code)
                i += 3
            } else {
                val point = path.codePointAt(i)
                bytes.writeBytes(String(Character.toChars(point)).toByteArray())
                i += Character.charCount(point)
            }
        }
        return File(bytes.toString(Charsets.UTF_8))
    }
}
