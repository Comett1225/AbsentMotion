package app.absentmotion.ui.home

import java.io.File

internal enum class InstallKind { Msi, Setup, MacApp, Tarball, AppImage, Deb }

internal const val SETUP_EXE = "AbsentMotionSetup.exe"

internal data class InstallSite(val kind: InstallKind, val target: File)

internal fun installSite(
    os: String = System.getProperty("os.name").orEmpty(),
    home: String = System.getProperty("user.home").orEmpty(),
    javaHome: String = System.getProperty("java.home").orEmpty(),
    appPath: String? = System.getProperty("jpackage.app-path"),
    appImage: String? = System.getenv("APPIMAGE"),
    appDir: String? = System.getenv("APPDIR"),
    debInstalled: Boolean = File("/usr/bin/apt-get").canExecute() && File("/var/lib/dpkg/info").listFiles { f -> f.name.startsWith("absentmotion") && f.name.endsWith(".list") }.orEmpty().isNotEmpty(),
): InstallSite? = when {
    os.startsWith("windows", ignoreCase = true) ->
        appPath?.let(::File)?.takeIf { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
            ?.let { InstallSite(if (File(it.parentFile, SETUP_EXE).isFile) InstallKind.Setup else InstallKind.Msi, it) }
    os.startsWith("mac", ignoreCase = true) -> {
        val bundle = generateSequence(appPath?.let(::File)) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }
        bundle?.takeIf { "AppTranslocation" !in it.path && it.isDirectory && it.canWrite() && it.parentFile?.canWrite() == true }?.let { InstallSite(InstallKind.MacApp, it) }
    }
    !appImage.isNullOrBlank() && !appDir.isNullOrBlank() && javaHome.startsWith(appDir.trimEnd('/') + "/") ->
        File(appImage).takeIf { it.isFile && it.parentFile?.canWrite() == true }?.let { InstallSite(InstallKind.AppImage, it) }
    javaHome.startsWith("/opt/absentmotion/") && debInstalled -> InstallSite(InstallKind.Deb, File("/opt/absentmotion"))
    home.isNotEmpty() && javaHome.startsWith("$home/.local/opt/absentmotion/") ->
        File(home, ".local/opt/absentmotion").takeIf { it.isDirectory && it.parentFile?.canWrite() == true }?.let { InstallSite(InstallKind.Tarball, it) }
    else -> null
}
