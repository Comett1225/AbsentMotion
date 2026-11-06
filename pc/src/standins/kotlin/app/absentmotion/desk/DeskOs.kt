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
        else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: home.resolve(".local/share").path).resolve("absentmotion")
    }
    val nativeName = when { mac -> "libamdesktop.dylib"; windows -> "amdesktop.dll"; else -> "libamdesktop.so" }
    val revealLabel = when { mac -> "Show in Finder"; windows -> "Show in Explorer"; else -> "Show in Folder" }
    val videosFolder by lazy { if (mac) "Movies" else userFolder("VIDEOS") ?: "Videos" }
    val picturesFolder by lazy { userFolder("PICTURES") ?: "Pictures" }
    val musicFolder by lazy { userFolder("MUSIC") ?: "Music" }
    val documentsFolder by lazy { userFolder("DOCUMENTS") ?: "Documents" }
    val commandKey = if (mac) "⌘" else "Ctrl+"
    val windowButtons: Pair<List<WindowButton>, Boolean> by lazy {
        val config = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: home.resolve(".config").path)
        val kde = System.getenv("XDG_CURRENT_DESKTOP").orEmpty().contains("KDE", ignoreCase = true)
        if (!linux || !shell) buttonLayout(null)
        else if (kde) {
            val text = runCatching { config.resolve("kwinrc").readText() }.getOrDefault("")
            fun key(name: String) = Regex("(?m)^$name=(.*)$").find(text)?.groupValues?.get(1)?.trim()
            kdeButtonLayout(key("ButtonsOnLeft") ?: "MS", key("ButtonsOnRight") ?: "HIAX")
        } else buttonLayout(runCatching {
            val process = ProcessBuilder("gsettings", "get", "org.gnome.desktop.wm.preferences", "button-layout").redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) process.inputStream.bufferedReader().readText() else { process.destroyForcibly(); null }
        }.getOrNull())
    }

    private val defaultButtons = listOf(WindowButton.Minimize, WindowButton.Maximize, WindowButton.Close)

    internal fun buttonLayout(gnome: String?): Pair<List<WindowButton>, Boolean> {
        val sides = gnome?.trim()?.trim('\'', '"')?.split(':') ?: return defaultButtons to false
        fun parse(part: String) = part.split(',').mapNotNull { when (it.trim()) { "minimize" -> WindowButton.Minimize; "maximize" -> WindowButton.Maximize; "close" -> WindowButton.Close; else -> null } }
        return pick(parse(sides[0]), parse(sides.getOrElse(1) { "" }))
    }

    internal fun kdeButtonLayout(left: String, right: String): Pair<List<WindowButton>, Boolean> {
        fun parse(part: String) = part.mapNotNull { when (it) { 'I' -> WindowButton.Minimize; 'A' -> WindowButton.Maximize; 'X' -> WindowButton.Close; else -> null } }
        return pick(parse(left), parse(right))
    }

    private fun pick(left: List<WindowButton>, right: List<WindowButton>): Pair<List<WindowButton>, Boolean> = when {
        WindowButton.Close in left || right.isEmpty() && left.isNotEmpty() -> left to true
        right.isNotEmpty() -> right to false
        else -> defaultButtons to false
    }

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
        windows -> listOf("rundll32", "url.dll,FileProtocolHandler", path)
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
