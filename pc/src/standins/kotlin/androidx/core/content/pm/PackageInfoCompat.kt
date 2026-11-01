package androidx.core.content.pm

object PackageInfoCompat {
    @JvmStatic fun getLongVersionCode(info: android.content.pm.PackageInfo): Long = info.versionCode.toLong()
}
