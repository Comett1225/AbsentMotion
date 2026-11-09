package app.absentmotion.ui.home

import java.io.File

internal enum class InstallKind { Msi, MacApp, Tarball, AppImage, Deb }

internal data class InstallSite(val kind: InstallKind, val target: File)

internal fun installSite(
    os: String = System.getProperty("os.name").orEmpty(),
    home: String = System.getProperty("user.home").orEmpty(),
    javaHome: String = System.getProperty("java.home").orEmpty(),
    appPath: String? = System.getProperty("jpackage.app-path"),
    appImage: String? = System.getenv("APPIMAGE"),
): InstallSite? = when {
    os.startsWith("windows", ignoreCase = true) ->
        appPath?.let(::File)?.takeIf { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }?.let { InstallSite(InstallKind.Msi, it) }
    os.startsWith("mac", ignoreCase = true) -> {
        val bundle = generateSequence(appPath?.let(::File)) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }
        bundle?.takeIf { "AppTranslocation" !in it.path && it.isDirectory && it.canWrite() && it.parentFile?.canWrite() == true }?.let { InstallSite(InstallKind.MacApp, it) }
    }
    !appImage.isNullOrBlank() ->
        File(appImage).takeIf { it.isFile && it.parentFile?.canWrite() == true }?.let { InstallSite(InstallKind.AppImage, it) }
    javaHome.startsWith("/opt/absentmotion/") -> InstallSite(InstallKind.Deb, File("/opt/absentmotion"))
    home.isNotEmpty() && javaHome.startsWith("$home/.local/opt/absentmotion/") ->
        File(home, ".local/opt/absentmotion").takeIf { it.isDirectory && it.parentFile?.canWrite() == true }?.let { InstallSite(InstallKind.Tarball, it) }
    else -> null
}
