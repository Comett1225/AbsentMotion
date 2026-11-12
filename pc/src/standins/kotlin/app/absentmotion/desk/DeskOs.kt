package app.absentmotion.desk

import java.io.File

object DeskOs {
    private val name = System.getProperty("os.name").orEmpty().lowercase()
    val mac = name.startsWith("mac")
    val windows = name.startsWith("windows")
    val linux = !mac && !windows
    val home: File get() = File(System.getProperty("user.home"))

    fun systemDark(): Boolean = runCatching {
        System.getProperty("am.systemDark")?.let { return it == "true" }
        when (org.jetbrains.skiko.currentSystemTheme) {
            org.jetbrains.skiko.SystemTheme.DARK -> true
            org.jetbrains.skiko.SystemTheme.LIGHT -> false
            else -> linux && gnomeDark()
        }
    }.getOrDefault(false)

    @Volatile private var gsettings = true
    @Volatile private var gnome = false
    @Volatile private var gnomeAt = 0L

    private fun gnomeDark(): Boolean {
        val now = System.nanoTime()
        if (!gsettings || gnomeAt != 0L && now - gnomeAt < 10_000_000_000L) return gnome
        gnomeAt = now
        val process = try {
            ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme").redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            gsettings = false
            return false
        }
        if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); return gnome }
        gnome = process.inputStream.bufferedReader().use { it.readText() }.contains("dark")
        return gnome
    }
    val dataDir: File get() = when {
        mac -> home.resolve("Library/Application Support/AbsentMotion")
        windows -> File(System.getenv("APPDATA") ?: home.path).resolve("AbsentMotion")
        else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: home.resolve(".local/share").path).resolve("absentmotion")
    }
    val nativeName = when { mac -> "libamdesktop.dylib"; windows -> "amdesktop.dll"; else -> "libamdesktop.so" }
    val revealLabel: String get() = Strings.byName(when { mac -> "desk_reveal_finder"; windows -> "desk_reveal_explorer"; else -> "desk_reveal_folder" }) ?: when { mac -> "Show in Finder"; windows -> "Show in Explorer"; else -> "Show in Folder" }
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

    internal fun isTool(command: String): Boolean = File(command).name.lowercase().let { name -> listOf("ffmpeg", "ffprobe", "yt-dlp").any { name == it || name.startsWith("$it.") || name.startsWith("${it}_") } }

    fun stopTools() {
        runCatching { ProcessHandle.current().descendants().filter { handle -> handle.info().command().map(::isTool).orElse(false) }.forEach { it.destroyForcibly() } }
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
