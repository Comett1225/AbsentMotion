package android.content.pm

open class PackageInfo {
    @JvmField var versionName: String? = app.absentmotion.desk.DeskInfo.version
    @JvmField var versionCode: Int = 0
    @JvmField var lastUpdateTime: Long = app.absentmotion.desk.DeskInfo.stamp
    @JvmField var firstInstallTime: Long = 0
    @JvmField var packageName: String = "app.absentmotion"
    val longVersionCode: Long get() = versionCode.toLong()
}

open class PackageManager {
    open fun getPackageInfo(name: String, flags: Int): PackageInfo = PackageInfo()
    open fun getPackageInfo(name: String, flags: PackageInfoFlags): PackageInfo = PackageInfo()
    open fun hasSystemFeature(name: String): Boolean = false
    class PackageInfoFlags private constructor(val value: Long) { companion object { @JvmStatic fun of(v: Long) = PackageInfoFlags(v) } }
    companion object {
        const val PERMISSION_GRANTED = 0
        const val PERMISSION_DENIED = -1
        const val GET_SIGNATURES = 64
        const val GET_SIGNING_CERTIFICATES = 134217728
        val Desktop = PackageManager()
    }
}
