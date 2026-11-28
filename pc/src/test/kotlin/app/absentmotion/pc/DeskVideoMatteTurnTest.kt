package app.absentmotion.pc

import app.absentmotion.Judge
import app.absentmotion.ai.storedIndex
import app.absentmotion.ai.uprightMatte
import app.absentmotion.desk.DeskProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.math.abs

class DeskVideoMatteTurnTest {
    private val dir = File("build/matte-turn").absoluteFile.also { it.deleteRecursively(); it.mkdirs() }

    private fun run(vararg args: String): ByteArray {
        val p = ProcessBuilder(listOf(Judge.ffmpeg, "-nostdin", "-v", "error", "-y") + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.readBytes()
        check(p.waitFor() == 0)
        return out
    }

    private fun pixels(bytes: ByteArray) = IntArray(bytes.size / 3) { (bytes[3 * it].toInt() and 255 shl 16) or (bytes[3 * it + 1].toInt() and 255 shl 8) or (bytes[3 * it + 2].toInt() and 255) }

    @Test fun phoneVideosAreTurnedUprightLikeTheirPicture() {
        val flat = dir.resolve("flat.mp4")
        run("-f", "lavfi", "-i", "color=red:s=96x48:d=0.2,drawbox=x=0:y=0:w=48:h=24:c=blue:t=fill,drawbox=x=48:y=24:w=48:h=24:c=lime:t=fill", "-pix_fmt", "yuv444p", "-c:v", "libx264", "-qp", "0", flat.path)
        for (degrees in listOf(90, 180, 270)) {
            val file = dir.resolve("turn$degrees.mp4")
            run("-display_rotation", "$degrees", "-i", flat.path, "-c", "copy", file.path)
            val stream = DeskProbe.of(file).streams.first { it.type == "video" }
            val turn = ((stream.rotation % 360) + 360) % 360
            assertTrue(turn != 0)
            val stored = pixels(run("-noautorotate", "-i", file.path, "-frames:v", "1", "-pix_fmt", "rgb24", "-f", "rawvideo", "-"))
            val upright = pixels(run("-i", file.path, "-frames:v", "1", "-pix_fmt", "rgb24", "-f", "rawvideo", "-"))
            val mine = IntArray(stored.size) { stored[storedIndex(it, stream.width, stream.height, turn)] }
            assertEquals(upright.size, mine.size)
            val off = mine.indices.count { i -> (0..2).any { c -> abs((mine[i] shr (8 * c) and 255) - (upright[i] shr (8 * c) and 255)) > 40 } }
            assertTrue("$degrees degrees: $off pixels differ", off < mine.size / 50)
        }
    }

    @Test fun theMaskComesBackInTheVideosOwnOrientation() = runBlocking {
        val width = 6
        val height = 4
        val pixels = IntArray(width * height) { it }
        for (turn in listOf(0, 90, 180, 270, -90)) {
            var seen = 0 to 0
            val alpha = uprightMatte({ upright, w, h -> seen = w to h; ByteArray(upright.size) { upright[it].toByte() } }, pixels, width, height, turn)
            val sideways = turn == 90 || turn == 270 || turn == -90
            assertEquals(if (sideways) height to width else width to height, seen)
            assertEquals(pixels.map { it.toByte() }, alpha.toList())
        }
    }
}
