package android.os

import java.io.File

object Environment {
    const val DIRECTORY_MOVIES = "Movies"; const val DIRECTORY_PICTURES = "Pictures"; const val DIRECTORY_MUSIC = "Music"
    const val DIRECTORY_DOWNLOADS = "Downloads"; const val DIRECTORY_DCIM = "DCIM"; const val DIRECTORY_DOCUMENTS = "Documents"
    const val MEDIA_MOUNTED = "mounted"
    private val home = File(System.getProperty("user.home"))
    @JvmStatic fun getExternalStorageDirectory(): File = home
    @JvmStatic fun getExternalStoragePublicDirectory(type: String): File = home.resolve(type)
    @JvmStatic fun getDataDirectory(): File = android.content.Desk.dataDir
    @JvmStatic fun getExternalStorageState(): String = MEDIA_MOUNTED
    @JvmStatic fun isExternalStorageManager(): Boolean = true
}
