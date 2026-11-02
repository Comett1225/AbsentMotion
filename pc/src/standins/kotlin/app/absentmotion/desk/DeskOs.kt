package app.absentmotion.desk

import java.io.File

object DeskOs {
    private val name = System.getProperty("os.name").orEmpty().lowercase()
    val mac = name.startsWith("mac")
    val windows = name.startsWith("windows")
    val linux = !mac && !windows
    val home: File get() = File(System.getProperty("user.home"))
    val dataDir: File get() = when {
        mac -> home.resolve("Library/Application Support/AbsentMotion")
        windows -> File(System.getenv("APPDATA") ?: home.path).resolve("AbsentMotion")
        else -> File(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").path).resolve("absentmotion")
    }
    val nativeName = when { mac -> "libamdesktop.dylib"; windows -> "amdesktop.dll"; else -> "libamdesktop.so" }
    val revealLabel = when { mac -> "Show in Finder"; windows -> "Show in Explorer"; else -> "Show in Folder" }
    val videosFolder = if (mac) "Movies" else "Videos"
}
