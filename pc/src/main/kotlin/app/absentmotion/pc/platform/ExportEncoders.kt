package app.absentmotion.pc.platform

fun exportEncoders(hevc: Boolean): List<String> = if (System.getProperty("os.name").startsWith("Mac")) {
    if (hevc) listOf("hevc_videotoolbox", "libx265") else listOf("h264_videotoolbox", "libx264")
} else if (hevc) listOf("libx265") else listOf("libx264")
