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
    val videosFolder by lazy { if (mac) "Movies" else userFolder("VIDEOS") ?: "Videos" }
    val picturesFolder by lazy { userFolder("PICTURES") ?: "Pictures" }
    val musicFolder by lazy { userFolder("MUSIC") ?: "Music" }
    val documentsFolder by lazy { userFolder("DOCUMENTS") ?: "Documents" }
    val commandKey = if (mac) "⌘" else "Ctrl+"
    val titleBarDp = if (mac) 28f else 0f

    val systemEmoji: File? by lazy {
        when {
            mac -> listOf("/System/Library/Fonts/Apple Color Emoji.ttc")
            windows -> listOf((System.getenv("WINDIR") ?: "C:\\Windows") + "\\Fonts\\seguiemj.ttf")
            else -> listOf("/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf", "/usr/share/fonts/noto/NotoColorEmoji.ttf", "/usr/share/fonts/google-noto-emoji/NotoColorEmoji.ttf", "/usr/share/fonts/google-noto-color-emoji-fonts/Noto-COLRv1.ttf")
        }.map(::File).firstOrNull { it.isFile }
    }

    private val shell = System.getProperty("am.shell") != "off"
    private fun userFolder(name: String): String? = if (!linux || System.getenv("HOME")?.let { File(it) != home } == true) null else runCatching {
        val process = ProcessBuilder("xdg-user-dir", name).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val dir = File(process.inputStream.bufferedReader().readText().trim())
        if (process.waitFor() != 0 || !dir.isAbsolute || dir == home) null else if (dir.startsWith(home)) dir.relativeTo(home).path else dir.path
    }.getOrNull()

    internal fun showItemsCommand(file: File): List<String> = listOf("dbus-send", "--session", "--print-reply", "--reply-timeout=5000", "--dest=org.freedesktop.FileManager1",
        "--type=method_call", "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems", "array:string:" + file.toPath().toUri().toString().replace(",", "%2C"), "string:")

    internal fun openCommand(mac: Boolean, windows: Boolean, path: String): List<String> = when {
        mac -> listOf("open", path)
        windows -> listOf("cmd", "/c", "start", "", path)
        else -> listOf("xdg-open", path)
    }

    internal fun cameraSettingsCommand(mac: Boolean, windows: Boolean): List<String>? = when {
        mac -> listOf("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_Camera")
        windows -> listOf("cmd", "/c", "start", "", "ms-settings:privacy-webcam")
        else -> null
    }

    fun openCameraSettings() { cameraSettingsCommand(mac, windows)?.let { command -> runCatching { ProcessBuilder(command).start() } } }

    fun open(file: File): Boolean = !shell || runCatching { ProcessBuilder(openCommand(mac, windows, file.absolutePath)).start(); true }.getOrDefault(false)

    fun reveal(file: File) {
        if (shell) runCatching {
            when {
                mac -> ProcessBuilder("open", "-R", file.absolutePath).start()
                windows -> ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
                else -> kotlin.concurrent.thread(isDaemon = true) {
                    val shown = runCatching { ProcessBuilder(showItemsCommand(file)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0 }.getOrDefault(false)
                    if (!shown) open(file.parentFile)
                }
            }
        }
    }
}
