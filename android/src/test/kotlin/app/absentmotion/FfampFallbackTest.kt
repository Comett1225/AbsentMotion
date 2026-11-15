package app.absentmotion

import android.content.Context
import android.net.Uri
import app.absentmotion.media.DecodedFrame
import app.absentmotion.media.Demuxer
import app.absentmotion.media.DesktopFrameInput
import app.absentmotion.media.FfampReaders
import app.absentmotion.media.FrameGrabber
import app.absentmotion.media.Yuv
import app.absentmotion.media.ffampOrSystem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class FfampFallbackTest {
    private val ffamp = object : FfampReaders {
        override fun demuxer(context: Context, uri: Uri): Demuxer = error("unused")
        override fun grabber(context: Context, uri: Uri): FrameGrabber = error("unused")
        override fun softFrames(context: Context, uri: String): DesktopFrameInput? = error("unused")
        override fun softFrame(yuv: Yuv, pts: Long): DecodedFrame = error("unused")
    }

    private fun read(reader: FfampReaders?, open: () -> String): Pair<String, Int> {
        var fellBack = 0
        return ffampOrSystem(reader, { fellBack++ }, { open() }) { "system" } to fellBack
    }

    @Test fun standardNeverAsksFfamp() {
        assertEquals("system" to 0, read(null) { error("FFamp was asked") })
    }

    @Test fun ffampReadsWhatItCanOpen() {
        assertEquals("ffamp" to 0, read(ffamp) { "ffamp" })
    }

    @Test fun aFileFfampCannotOpenGoesToTheSystemReader() {
        assertEquals("system" to 1, read(ffamp) { throw IOException("FFamp cannot open it") })
    }

    @Test fun aMissingFfampLibraryGoesToTheSystemReader() {
        assertEquals("system" to 1, read(ffamp) { throw UnsatisfiedLinkError("no libamffamp.so") })
    }
}
