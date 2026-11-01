package app.absentmotion.pc.platform

fun exportEncoders(hevc: Boolean): List<String> = if (System.getProperty("os.name").startsWith("Mac")) {
    if (hevc) listOf("hevc_videotoolbox", "libx265") else listOf("h264_videotoolbox", "libx264")
} else if (DesktopPlatform.windows) {
    if (hevc) listOf("hevc_nvenc", "hevc_qsv", "hevc_amf", "hevc_mf", "libx265") else listOf("h264_nvenc", "h264_qsv", "h264_amf", "h264_mf", "libx264")
} else if (hevc) listOf("libx265") else listOf("libx264")

fun chooseExport(window: java.awt.Window, suggested: java.io.File): java.io.File? {
    val dialog = java.awt.FileDialog(window as java.awt.Frame, "Export", java.awt.FileDialog.SAVE)
    dialog.directory = suggested.parent
    dialog.file = suggested.name
    dialog.isVisible = true
    return dialog.file?.let { java.io.File(dialog.directory, it) }
}
