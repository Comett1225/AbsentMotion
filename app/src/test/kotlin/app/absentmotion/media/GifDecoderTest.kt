package app.absentmotion.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode

class GifDecoderTest {
    private class Frame(val pixels: ByteArray, val w: Int, val h: Int, val left: Int = 0, val top: Int = 0, val delay: Int = 10,
                        val dispose: Int = 0, val transparent: Int = -1, val interlaced: Boolean = false)

    private val palette = intArrayOf(0x000000, 0xFF0000, 0x00FF00, 0x0000FF)

    private fun gif(width: Int, height: Int, frames: List<Frame>): ByteArray {
        val out = ByteArrayOutputStream()
        fun short(v: Int) { out.write(v and 255); out.write(v shr 8 and 255) }
        out.write("GIF89a".toByteArray()); short(width); short(height); out.write(0x80 or 7); out.write(0); out.write(0)
        for (i in 0 until 256) { val c = palette.getOrElse(i) { 0 }; out.write(c shr 16 and 255); out.write(c shr 8 and 255); out.write(c and 255) }
        for (f in frames) {
            out.write(0x21); out.write(0xF9); out.write(4); out.write((f.dispose shl 2) or (if (f.transparent >= 0) 1 else 0)); short(f.delay)
            out.write(maxOf(0, f.transparent)); out.write(0)
            out.write(0x2C); short(f.left); short(f.top); short(f.w); short(f.h); out.write(if (f.interlaced) 0x40 else 0)
            val rows = if (!f.interlaced) (0 until f.h).toList() else (0 until f.h step 8) + (4 until f.h step 8) + (2 until f.h step 4) + (1 until f.h step 2)
            val order = rows.flatMap { r -> (0 until f.w).map { f.pixels[r * f.w + it] } }
            out.write(8)
            val bits = ArrayList<Boolean>()
            fun code(c: Int) { for (b in 0 until 9) bits += (c shr b and 1) == 1 }
            order.chunked(250).forEach { chunk -> code(256); chunk.forEach { code(it.toInt() and 255) } }
            code(257)
            val bytes = bits.chunked(8).map { byte -> byte.foldIndexed(0) { i, acc, b -> if (b) acc or (1 shl i) else acc } }
            bytes.chunked(255).forEach { block -> out.write(block.size); block.forEach(out::write) }
            out.write(0)
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): List<Pair<IntArray, Int>> {
        val frames = ArrayList<Pair<IntArray, Int>>()
        GifDecoder(ByteArrayInputStream(bytes)).read { canvas, delay -> frames += canvas.copyOf() to delay }
        return frames
    }

    private fun opaque(i: Int) = 0xFF000000.toInt() or palette[i]

    @Test fun timingFollowsBrowsersForTinyDelays() {
        assertEquals(100, gifDelayMs(0))
        assertEquals(100, gifDelayMs(1))
        assertEquals(20, gifDelayMs(2))
        assertEquals(70, gifDelayMs(7))
        assertArrayEquals(longArrayOf(0, 100_000, 120_000), gifStartsUs(intArrayOf(100, 20, 70)))
    }

    @Test fun framesCompositeWithDisposalAndTransparency() {
        val full = Frame(ByteArray(16) { 1 }, 4, 4, delay = 0)
        val patch = Frame(byteArrayOf(2, 3, 3, 2), 2, 2, 1, 1, delay = 5, dispose = 2, transparent = 3)
        val after = Frame(byteArrayOf(3), 1, 1, 3, 3, delay = 3, dispose = 3)
        val last = Frame(byteArrayOf(2), 1, 1, 0, 0, delay = 4)
        val frames = decode(gif(4, 4, listOf(full, patch, after, last)))
        assertEquals(listOf(100, 50, 30, 40), frames.map { it.second })
        assertTrue(frames[0].first.all { it == opaque(1) })
        assertEquals(opaque(2), frames[1].first[5])
        assertEquals(opaque(1), frames[1].first[6])
        assertEquals(opaque(3), frames[2].first[15])
        assertEquals(0, frames[2].first[5])
        assertEquals(opaque(1), frames[3].first[15])
        assertEquals(opaque(2), frames[3].first[0])
    }

    @Test fun interlacedRowsLandInPlace() {
        val rows = ByteArray(5 * 2) { (it / 2 % 4).toByte() }
        val frames = decode(gif(2, 5, listOf(Frame(rows, 2, 5, interlaced = true), Frame(rows, 2, 5))))
        assertArrayEquals(frames[1].first, frames[0].first)
        assertEquals(opaque(1), frames[0].first[2])
    }

    @Test fun summaryCountsFramesAndSeesTransparency() {
        val solid = gif(4, 4, listOf(Frame(ByteArray(16) { 1 }, 4, 4), Frame(ByteArray(4) { 3 }, 2, 2, transparent = 3)))
        val summary = summarizeGif { ByteArrayInputStream(solid) }
        assertEquals(2, summary.frames)
        assertFalse(summary.transparent)
        assertEquals(200L, summary.durationMs)
        val holes = gif(4, 4, listOf(Frame(ByteArray(4) { 1 }, 2, 2), Frame(ByteArray(4) { 2 }, 2, 2)))
        assertTrue(summarizeGif { ByteArrayInputStream(holes) }.transparent)
    }

    @Test fun badFilesGiveClearProblems() {
        fun problem(bytes: ByteArray) = try { summarizeGif { ByteArrayInputStream(bytes) }; null } catch (e: GifError) { e.problem }
        assertEquals(GifProblem.NotGif, problem("\u0089PNG\r\n".toByteArray() + ByteArray(20)))
        val good = gif(4, 4, listOf(Frame(ByteArray(16) { 1 }, 4, 4)))
        assertEquals(GifProblem.Damaged, problem(good.copyOf(20)))
        val huge = good.copyOf().also { it[6] = 0xFF.toByte(); it[7] = 0x7F; it[8] = 0xFF.toByte(); it[9] = 0x7F }
        assertEquals(GifProblem.TooLarge, problem(huge))
    }

    @Test fun longOrCancelledGifsStopAsFramesArrive() {
        val long = gif(4, 4, List(3) { Frame(ByteArray(16) { 1 }, 4, 4, delay = 40_000) })
        var seen = 0
        val problem = try { summarizeGif({ seen++ }) { ByteArrayInputStream(long) }; null } catch (e: GifError) { e.problem }
        assertEquals(GifProblem.TooLarge, problem)
        assertEquals(2, seen)
        val frames = gif(4, 4, List(5) { Frame(ByteArray(16) { 1 }, 4, 4) })
        var calls = 0
        val stopped = try {
            summarizeGif({ if (++calls == 2) throw java.util.concurrent.CancellationException() }) { ByteArrayInputStream(frames) }
            false
        } catch (_: java.util.concurrent.CancellationException) { true }
        assertTrue(stopped)
        assertEquals(2, calls)
    }

    @Test fun realCompressedGifMatchesImageIo() {
        val image = BufferedImage(37, 23, BufferedImage.TYPE_BYTE_INDEXED, IndexColorModel(8, 4,
            palette.map { (it shr 16).toByte() }.toByteArray(), palette.map { (it shr 8).toByte() }.toByteArray(), palette.map { it.toByte() }.toByteArray()))
        for (y in 0 until 23) for (x in 0 until 37) image.raster.setSample(x, y, 0, ((x / 3) xor (y / 2)) % 4)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "gif", it) }.toByteArray()
        val canvas = decode(bytes).single().first
        for (y in 0 until 23) for (x in 0 until 37) assertEquals("$x,$y", image.getRGB(x, y), canvas[y * 37 + x])
    }

    @Test fun animatedCompressedGifMatchesImageIo() {
        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        ImageIO.createImageOutputStream(output).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            repeat(3) { index ->
                val image = BufferedImage(37, 23, BufferedImage.TYPE_INT_RGB)
                for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, palette[((x / 3) xor (y / 2) xor index) % 4])
                val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null)
                val root = metadata.getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
                (root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode).setAttribute("delayTime", ((index + 1) * 7).toString())
                metadata.setFromTree("javax_imageio_gif_image_1.0", root)
                writer.writeToSequence(IIOImage(image, null, metadata), null)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
        val bytes = output.toByteArray()
        val frames = decode(bytes)
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        val delays = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            reader.input = stream
            val count = reader.getNumImages(true)
            assertEquals(3, count)
            assertEquals(count, frames.size)
            List(count) { i ->
                val image = reader.read(i)
                for (y in 0 until image.height) for (x in 0 until image.width) assertEquals("frame $i at $x,$y", image.getRGB(x, y), frames[i].first[y * image.width + x])
                val root = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
                gifDelayMs((root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode).getAttribute("delayTime").toInt())
            }
        }
        reader.dispose()
        val summary = summarizeGif { ByteArrayInputStream(bytes) }
        assertEquals(delays, summary.delaysMs.toList())
        assertEquals(listOf(70, 140, 210), delays)
        assertEquals(3, summary.frames)
    }

    @Test fun encodeSizeIsEvenAndFallsBackWhenTheEncoderRefuses() {
        assertEquals(302 to 200, gifEncodeSize(301, 199) { _, _ -> true })
        assertEquals(1536 to 1536, gifEncodeSize(2048, 2048) { w, h -> w <= 1920 && h <= 1920 })
        assertEquals(64 to 32, gifEncodeSize(16, 8) { w, h -> w >= 64 && h >= 32 })
        assertEquals(null, gifEncodeSize(100, 100) { _, _ -> false })
    }
}
