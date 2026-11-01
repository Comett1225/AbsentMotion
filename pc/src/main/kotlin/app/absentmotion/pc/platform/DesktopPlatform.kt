package app.absentmotion.pc.platform

import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.io.File

object DesktopPlatform {
    val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val mac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val dataDir: File get() = if (windows) File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "AbsentMotion") else File(System.getProperty("user.home"), "Library/Application Support/AbsentMotion")
    fun assets(): File = System.getProperty("am.assets")?.let(::File) ?: File(System.getProperty("compose.application.resources.dir"), "assets")
    fun nativeLibrary(): String = System.getProperty("am.native") ?: File(System.getProperty("compose.application.resources.dir"), if (windows) "amdesktop.dll" else "libamdesktop.dylib").absolutePath
    fun choose(window: Window, save: Boolean): File? {
        val dialog = FileDialog(window as Frame, if (save) "Save Project" else "Open Project", if (save) FileDialog.SAVE else FileDialog.LOAD)
        dialog.isVisible = true
        return dialog.file?.let { File(dialog.directory, it) }
    }
    fun chooseMedia(window: Window): List<File> {
        val dialog = FileDialog(window as Frame, "Import Media", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.isVisible = true
        return dialog.files.toList()
    }
    fun executable(name: String): String = System.getProperty("am.$name") ?: System.getenv("AM_${name.uppercase()}")
        ?: (if (windows) listOfNotNull(System.getProperty("compose.application.resources.dir"), System.getProperty("jpackage.app-path")?.let { File(it).parent }).map { File(it, "$name.exe") } else listOf(File("/opt/homebrew/bin/$name"), File("/usr/local/bin/$name"))).firstOrNull { it.isFile && it.canExecute() }?.absolutePath ?: name
    fun title(window: Window, file: File?) {
        if (mac) (window as? javax.swing.JFrame)?.rootPane?.putClientProperty("Window.documentFile", file)
        (window as? Frame)?.title = file?.nameWithoutExtension?.let { "$it - AbsentMotion" } ?: "AbsentMotion"
    }
}
