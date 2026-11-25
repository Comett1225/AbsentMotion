package app.absentmotion.ai

import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Test

class SrtCaptionsTest {
    private fun clip(id: Long, start: Int, end: Int, extra: Map<String, String> = emptyMap()) =
        Layer(XmlNode("audio", linkedMapOf("id" to "$id", "src" to "voice.m4a", "startTime" to "$start", "endTime" to "$end") + extra))

    @Test fun bomCrlfNumbersTagsAndLinesAreRead() {
        val text = "﻿1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Hello</i> <B>there</B>\r\n<font color=\"#ff0000\">second line</font>\r\n\r\n" +
            "2\r\n00:00:03,000 --> 00:00:04,000 X1:10 X2:20\r\n{\\an8}Top\r\n"
        assertEquals(listOf(Chunk("Hello there\nsecond line", 1000, 2500), Chunk("Top", 3000, 4000)), srtCues(text))
    }

    @Test fun missingNumbersAndBlankLinesStillSplitCues() {
        val text = "00:00:01,000 --> 00:00:02,000\nOne\n2\n00:00:02.5 --> 00:00:03,000\nTwo\n\n\n3\n00:00:05,000 --> 00:00:04,000\nBackwards\n\n4\n00:00:06,000 --> 00:00:07,000\n<i></i>\n"
        assertEquals(listOf(Chunk("One", 1000, 2000), Chunk("Two", 2500, 3000)), srtCues(text))
        assertEquals(emptyList<Chunk>(), srtCues("not a subtitle file"))
    }

    @Test fun utf16AndUtf8FilesDecode() {
        val text = "1\n00:00:00,000 --> 00:00:01,000\nÜber café\n"
        assertEquals(text, srtText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()))
        assertEquals(text, srtText(text.toByteArray()))
        assertEquals(text, srtText(text.toByteArray(Charsets.UTF_16LE).let { byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + it }))
    }

    @Test fun overlappingCuesShowTogetherAndSameStartsMerge() {
        val scene = Scene.create("srt", 1080, 1920, 30, 0).add(clip(1, 0, 9999))
        val text = "00:00:00,000 --> 00:00:04,000\nA\n\n00:00:02,000 --> 00:00:03,000\nB\n\n00:00:05,000 --> 00:00:06,000\nC\n\n00:00:05,000 --> 00:00:06,000\nD\n"
        assertEquals(
            listOf(Chunk("A", 0, 2000), Chunk("A\nB", 2000, 3000), Chunk("A", 3000, 4000), Chunk("C\nD", 5000, 6000)),
            scene.srtChunks(text),
        )
    }

    @Test fun cuesPastTheProjectEndAreCutOrDropped() {
        val scene = Scene.create("srt", 1080, 1920, 30, 0).add(clip(1, 0, 4999))
        val text = "00:00:04,000 --> 00:00:08,000\nLate\n\n00:00:06,000 --> 00:00:07,000\nGone\n"
        assertEquals(listOf(Chunk("Late", 4000, 5000)), scene.srtChunks(text))
        assertEquals(
            listOf(Chunk("Late", 4000, 6000), Chunk("Late\nGone", 6000, 7000), Chunk("Late", 7000, 8000)),
            Scene.create("srt", 1080, 1920, 30, 0).srtChunks(text),
        )
    }

    @Test fun aClipsCuesFollowItsTrimAndSpeed() {
        val scene = Scene.create("srt", 1080, 1920, 30, 0).add(clip(1, 5000, 7999, mapOf("inTime" to "2000", "outTime" to "8000", "speed" to "2")))
        val text = "00:00:01,000 --> 00:00:01,500\nBefore\n\n00:00:04,000 --> 00:00:05,000\nInside\n\n00:00:07,000 --> 00:00:12,000\nEnding\n\n00:00:09,000 --> 00:00:12,000\nAfter\n"
        assertEquals(listOf(Chunk("Inside", 6000, 6500), Chunk("Ending", 7500, 8000)), scene.srtChunks(text, 1))
    }

    @Test fun importedCuesBecomeCaptionLayers() {
        val scene = Scene.create("srt", 1080, 1920, 30, 0).add(clip(1, 0, 9999))
        val chunks = scene.srtChunks("00:00:01,000 --> 00:00:02,000\nHi\n\n00:00:02,000 --> 00:00:03,000\nYou\n")
        val layers = scene.captionLayers(chunks, CaptionStyle(), { "Caption $it" }) { _, size -> size }
        assertEquals(listOf(1000 to 1999, 2000 to 2999), layers.map { it.startTime to it.endTime })
    }
}
