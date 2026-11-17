package app.absentmotion.platform

private const val ALIGHT_PACKAGE = "com.alightcreative.motion"

fun alightInstalled(context: Context): Boolean = runCatching { context.packageManager.getPackageInfo(ALIGHT_PACKAGE, 0); true }.getOrDefault(false)

fun openAlight(context: Context, link: String, installed: Boolean): Boolean = runCatching {
    val target = if (installed) link else "https://play.google.com/store/apps/details?id=$ALIGHT_PACKAGE"
    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(target)).apply {
        if (installed) setPackage(ALIGHT_PACKAGE)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    })
    true
}.getOrDefault(false)
