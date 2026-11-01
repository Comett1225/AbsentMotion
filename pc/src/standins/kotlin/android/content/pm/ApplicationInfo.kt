package android.content.pm

open class ApplicationInfo {
    @JvmField var flags = 0
    @JvmField var dataDir: String = android.content.Desk.dataDir.path
    @JvmField var nativeLibraryDir: String = ""
    @JvmField var sourceDir: String = ""
    @JvmField var packageName: String = "app.absentmotion"
    @JvmField var targetSdkVersion = 36
    companion object {
        const val FLAG_DEBUGGABLE = 2
        const val FLAG_LARGE_HEAP = 1048576
        val Desktop = ApplicationInfo()
    }
}
