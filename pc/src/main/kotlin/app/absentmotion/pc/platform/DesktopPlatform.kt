package app.absentmotion.pc.platform

import java.awt.Frame
import java.awt.Window
import java.io.File

object DesktopPlatform {
    val windows = app.absentmotion.desk.DeskOs.windows
    val mac = app.absentmotion.desk.DeskOs.mac
    val dataDir: File get() = app.absentmotion.desk.DeskOs.dataDir
    fun assets(): File = System.getProperty("am.assets")?.let(::File) ?: File(System.getProperty("compose.application.resources.dir"), "assets")
    fun nativeLibrary(): String = System.getProperty("am.native") ?: File(System.getProperty("compose.application.resources.dir"), app.absentmotion.desk.DeskOs.nativeName).absolutePath
    fun executable(name: String): String = System.getProperty("am.$name") ?: System.getenv("AM_${name.uppercase()}")
        ?: (if (windows) listOfNotNull(System.getProperty("compose.application.resources.dir"), System.getProperty("jpackage.app-path")?.let { File(it).parent }).map { File(it, "$name.exe") } else listOfNotNull(System.getProperty("compose.application.resources.dir")?.let { File(it, name) }) + listOf(File("/opt/homebrew/bin/$name"), File("/usr/local/bin/$name"))).firstOrNull { it.isFile && it.canExecute() }?.absolutePath ?: name
    fun title(window: Window, file: File?) {
        if (mac) (window as? javax.swing.JFrame)?.rootPane?.putClientProperty("Window.documentFile", file)
        (window as? Frame)?.title = file?.nameWithoutExtension?.let { "$it - AbsentMotion" } ?: "AbsentMotion"
    }
}
