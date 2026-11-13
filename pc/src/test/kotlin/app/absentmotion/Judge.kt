package app.absentmotion

import app.absentmotion.media.FfmpegEngine
import app.absentmotion.media.MediaEngine
import org.junit.Assume.assumeTrue
import java.io.File

object Judge {
    private fun found(path: String): String {
        assumeTrue("this test needs the ffmpeg and ffprobe programs to make its files or to judge the result", File(path).canExecute())
        return path
    }

    val ffmpeg: String get() = found(FfmpegEngine.ffmpeg)
    val ffprobe: String get() = found(FfmpegEngine.ffprobe)
    val engine: MediaEngine get() { ffmpeg; return FfmpegEngine }
}
