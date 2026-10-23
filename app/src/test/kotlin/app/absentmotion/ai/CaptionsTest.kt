package app.absentmotion.ai

import app.absentmotion.model.Scene
import app.absentmotion.model.StyleColors
import app.absentmotion.model.borders
import app.absentmotion.model.content
import app.absentmotion.model.textAlign
import app.absentmotion.model.textSize
import app.absentmotion.model.wrapWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

class CaptionsTest {
    private val spoken = listOf(
        Word("Hello", 0, 500), Word("there,", 500, 1110), Word("this", 1110, 1330), Word("is", 1330, 1490),
        Word("a", 1490, 1520), Word("quick", 1520, 1790), Word("test", 1790, 2030), Word("of", 2030, 2160),
        Word("automatic", 2160, 2880), Word("captions", 2880, 3770), Word("made", 3770, 3870), Word("on", 3870, 4030),
        Word("the", 4030, 4330), Word("phone.", 4330, 4950), Word("Does", 5600, 5800), Word("it", 5800, 5900), Word("work?", 5900, 6400),
    )

    @Test fun wordsBecomeCaptionsOfAtMostNWordsEndingAtSentencesAndPauses() {
        val chunks = chunkWords(spoken, 4)
        assertEquals(
            listOf("Hello there, this is", "a quick test of", "automatic captions made on", "the phone.", "Does it work?"),
            chunks.map { it.text },
        )
        assertEquals(listOf(0 to 1490, 1490 to 2160, 2160 to 4030, 4030 to 4950, 5600 to 6400), chunks.map { it.startMs to it.endMs })
        assertEquals(spoken.size, chunkWords(spoken, 1).size)
        assertEquals(listOf("Hello there, this is a quick test of automatic", "captions made on the phone.", "Does it work?"),
            chunkWords(spoken, 10).map { it.text })
    }

    @Test fun noCaptionIsLongerThanThreeSeconds() {
        val slow = List(12) { Word("word$it", it * 900, it * 900 + 850) } + Word("held", 11_000, 16_000)
        val chunks = chunkWords(slow, 10)
        assertTrue(chunks.all { it.endMs - it.startMs <= 3000 })
        assertEquals(slow.size, chunks.sumOf { it.text.split(' ').size })
        assertEquals(Chunk("held", 11_000, 14_000), chunks.last())
        assertTrue(chunkWords(emptyList(), 4).isEmpty())
    }

    @Test fun soundIsResampledTo16kMonoWithoutSeams() = runBlocking {
        val ranges = mutableListOf<Pair<Long, Long>>()
        fun tone(frame: Long) = (0.5 * sin(2 * PI * 440 * frame / 44100)).toFloat()
        val mix = { startUs: Long, endUs: Long ->
            ranges += startUs to endUs
            val first = Math.round(startUs * 44100 / 1e6)
            val frames = (Math.round(endUs * 44100 / 1e6) - first).toInt()
            ShortArray(frames * 2) { (tone(first + it / 2) * 32767).toInt().toShort() }
        }
        var done = 0f
        val out = speechPcm(mix, 61_000, 63_500) { done = it }
        assertEquals(40_000, out.size)
        assertEquals(1f, done)
        assertTrue(ranges.zipWithNext().all { (a, b) -> a.second == b.first })
        val first = 61L * 44100
        val worst = (8 until out.size).maxOf { abs(out[it] - (0.5 * sin(2 * PI * 440 * (first + it * 44100.0 / 16000) / 44100)).toFloat()) }
        assertTrue("worst error $worst", worst < 0.01f)
        assertEquals(16_000, speechPcm(mix, 0, 300) {}.size)
    }

    @Test fun soundArrivesInBoundedChunksThatAddUpToTheWholeBuffer() = runBlocking {
        fun tone(frame: Long) = (0.5 * sin(2 * PI * 440 * frame / 44100)).toFloat()
        val mix = { startUs: Long, endUs: Long ->
            val first = Math.round(startUs * 44100 / 1e6)
            val frames = (Math.round(endUs * 44100 / 1e6) - first).toInt()
            ShortArray(frames * 2) { (tone(first + it / 2) * 32767).toInt().toShort() }
        }
        val whole = speechPcm(mix, 1_000, 8_500) {}
        val parts = ArrayList<FloatArray>()
        speechChunks(mix, 1_000, 8_500, {}) { chunk, count -> parts += chunk.copyOf(count) }
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.size <= 16_000 })
        assertEquals(whole.toList(), parts.flatMap { it.toList() })
        assertEquals(120_000, whole.size)
    }

    @Test fun longRecordingsAreRecognisedInWindowsThatCoverTheWholeSpan() {
        assertEquals(listOf(5_000 to 65_000), captionWindows(5_000, 65_000))
        val windows = captionWindows(0, 45 * 60_000)
        assertEquals(5, windows.size)
        assertTrue(windows.all { (a, b) -> b - a <= CAPTION_WINDOW_MS })
        assertEquals(0, windows.first().first)
        assertEquals(45 * 60_000, windows.last().second)
        assertTrue(windows.zipWithNext().all { (a, b) -> a.second == b.first })
    }

    @Test fun captionsFitInsideTheFrameForEveryShape() {
        val shapes = listOf(1080 to 1920, 1080 to 1080, 1920 to 1080, 1080 to 1350)
        val texts = listOf("Hi", "Hello there, this is", "Extraordinarily unbelievable internationalisation responsibilities",
            List(10) { "incomprehensibilities" }.joinToString(" "))
        for ((w, h) in shapes) {
            val scene = Scene.create("t", w, h, 30, 0)
            val wrap = captionWrap(w)
            assertEquals((w * 0.85f).toInt(), wrap)
            assertEquals(0.06f * minOf(w, h) / 3f, captionSize(w, h, 100), 1e-4f)
            val measure = { text: String, size: Float ->
                val px = size * 3
                ceil(text.length * 0.55f * px / wrap) * px * 1.17f + px * 0.2f
            }
            for (percent in listOf(50, 100, 150, 200)) for (position in CaptionPosition.entries) {
                val style = CaptionStyle(sizePercent = percent, position = position)
                val chunks = texts.mapIndexed { i, text -> Chunk(text, i * 1000, i * 1000 + 900) }
                val layers = scene.captionLayers(chunks, style, measure = measure)
                for (layer in layers) {
                    val (x, y) = layer.location
                    val tall = measure(layer.content, layer.textSize)
                    val where = "${w}x$h $percent% $position '${layer.content}'"
                    assertEquals(where, wrap, layer.wrapWidth)
                    assertTrue(where, x - layer.wrapWidth / 2f >= 0 && x + layer.wrapWidth / 2f <= w)
                    assertTrue(where, y - tall / 2 >= 0 && y + tall / 2 <= h)
                    assertEquals("center", layer.textAlign)
                }
                val line = when (position) { CaptionPosition.Top -> 0.18f; CaptionPosition.Middle -> 0.5f; else -> if (w > h) 0.85f else 0.82f }
                if (percent == 100) assertEquals(h * line, layers[0].location[1], 0.5f)
                assertEquals(captionSize(w, h, percent), layers[1].textSize, 1e-4f)
            }
        }
    }

    @Test fun layersAreFrameTimedNumberedAndOutlined() {
        val scene = Scene.create("t", 1080, 1920, 30, 0)
        val chunks = listOf(Chunk("one two", 0, 1000), Chunk("three", 1000, 1010), Chunk("four", 2950, 4000))
        val layers = scene.captionLayers(chunks, CaptionStyle(color = 0xFFFFE14D.toInt()), { "Caption $it" }) { _, size -> size * 3.5f }
        assertEquals(listOf("Caption 1", "Caption 2", "Caption 3"), layers.map { it.label })
        assertEquals(listOf(0 to 999, 1000 to 1032, 2933 to 3999), layers.map { it.startTime to it.endTime })
        assertEquals(0xFFFFE14D.toInt(), layers[0].fillColor)
        assertEquals(listOf("outside"), layers[0].borders.map { it.direction })
        assertEquals(StyleColors.Black, layers[0].borders[0].color)
        assertTrue(scene.captionLayers(chunks, CaptionStyle(outline = false)) { _, s -> s }.all { it.borders.isEmpty() })
        val again = layers.fold(scene) { s, l -> s.add(l) }.captionLayers(chunks.take(1), CaptionStyle(), { "Caption $it" }) { _, s -> s }
        assertEquals("Caption 4", again.single().label)
    }

    @Test fun theCaptionModelIsKeptOnlyWhenItsSha256Matches() {
        val dir = java.nio.file.Files.createTempDirectory("model").toFile()
        try {
            val good = "speech model bytes".toByteArray()
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(good).joinToString("") { "%02x".format(it) }
            val target = java.io.File(dir, "model.bin")
            val part = java.io.File(dir, "model.bin.part")
            val forged = "SPEECH MODEL BYTES".toByteArray()
            val refused = runCatching { CaptionModel.receive(forged.inputStream(), part, target, good.size.toLong(), hash, {}, {}) }
            assertTrue(refused.exceptionOrNull() is IllegalStateException)
            assertTrue(!target.exists())
            CaptionModel.receive(good.inputStream(), part, target, good.size.toLong(), hash, {}, {})
            assertTrue(target.readBytes().contentEquals(good))
            assertEquals(64, CaptionModel.SHA256.length)
        } finally {
            dir.deleteRecursively()
        }
    }
}
