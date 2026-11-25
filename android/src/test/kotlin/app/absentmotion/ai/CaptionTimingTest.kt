package app.absentmotion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CaptionTimingTest {
    private fun tone(ms: Int) = FloatArray(16 * ms) { i -> 0.3f * sin(2 * PI * 220 * i / 16000).toFloat() }
    private fun silence(ms: Int) = FloatArray(16 * ms)

    @Test fun speechStartsAndEndsAreFoundAfterPauses() {
        val pcm = silence(500) + tone(1000) + silence(400) + tone(1000) + silence(300)
        val edges = speechEdges(pcm, 16000)
        assertEquals(listOf(500, 1900), edges.onsets)
        assertEquals(listOf(1500, 2900), edges.offsets)
    }

    @Test fun shortGapsInsideSpeechAreNotPauses() {
        val pcm = silence(500) + tone(600) + silence(100) + tone(600) + silence(300)
        assertEquals(listOf(500), speechEdges(pcm, 16000).onsets)
    }

    @Test fun silentOrEmptySoundHasNoEdges() {
        assertTrue(speechEdges(silence(1000), 16000).onsets.isEmpty())
        assertTrue(speechEdges(FloatArray(0), 16000).onsets.isEmpty())
    }

    @Test fun wordsAfterAPauseStartWhereTheSpeechStarts() {
        val raw = listOf(RawWord("Welcome", 600, 900, 980), RawWord("back.", 900, 1200, 1250), RawWord("Today", 4000, 4300, 3880), RawWord("we", 4300, 4500, 4100))
        val words = alignWords(raw, SpeechEdges(listOf(710, 3670), listOf(1400)))
        assertEquals(listOf(710, 1070, 3670, 3920), words.map { it.startMs })
        assertEquals(1350, words[1].endMs)
        assertEquals(3920, words[2].endMs)
    }

    @Test fun aWordEndsWhenItIsSpokenNotWhenTheNextWordStarts() {
        val words = alignWords(listOf(RawWord("them!", 0, 0, 1180), RawWord("You", 0, 0, 30180)), SpeechEdges(emptyList(), emptyList()))
        assertEquals(1000 + 70 * 4, words[0].endMs)
    }

    @Test fun aLoopOfTheSameLineSqueezedIntoAMomentIsDropped() {
        val line = listOf("Oh,", "I'll", "get", "that", "arm.")
        val spoken = line.mapIndexed { i, w -> RawWord(w, 0, 0, 116_940 + 220 * i) }
        val loop = List(3) { line }.flatten().map { RawWord(it, 0, 0, 118_000) }
        val next = RawWord("You're", 0, 0, 127_620)
        val words = alignWords(spoken + loop + next, SpeechEdges(emptyList(), emptyList()))
        assertEquals(line + "You're", words.map { it.text })
    }

    @Test fun aSqueezedLineThatIsSaidOnceIsSpreadOverItsTime() {
        val line = listOf("By", "the", "way,", "this", "is", "a", "friend", "of", "mine.")
        val words = alignWords(line.map { RawWord(it, 0, 0, 130_980) } + RawWord("Tree.", 0, 0, 134_820), SpeechEdges(emptyList(), emptyList()))
        assertEquals(line + "Tree.", words.map { it.text })
        val gaps = words.zipWithNext { a, b -> b.startMs - a.startMs }
        assertTrue(gaps.all { it >= 100 })
        assertTrue(words[8].startMs < words[9].startMs)
    }

    @Test fun lonePhantomLinesAreDroppedButRealOnesStay() {
        fun said(vararg pieces: Pair<String, Int>) = alignWords(pieces.map { (w, at) -> RawWord(w, 0, 0, at) }, SpeechEdges(emptyList(), emptyList())).map { it.text }
        assertEquals(listOf("I", "am", "Steve", "Rogers."), said("The" to 26_740, "End" to 26_880, "I" to 40_000, "am" to 40_200, "Steve" to 40_400, "Rogers." to 40_800, "Thank" to 58_000, "you." to 58_040))
        assertEquals(listOf("Rogers.", "Thank", "you."), said("Rogers." to 1000, "Thank" to 1600, "you." to 1900))
        assertEquals(listOf("Rogers."), said("Rogers." to 1000, "you" to 20_000))
    }

    @Test fun withoutPausesTheAccurateTimesAreMovedEarlier() {
        val raw = listOf(RawWord("one", 0, 400, 300), RawWord("two", 1000, 1300, 520))
        val words = alignWords(raw, SpeechEdges(emptyList(), emptyList()))
        assertEquals(listOf(120, 340), words.map { it.startMs })
        assertTrue(words.zipWithNext().all { (a, b) -> a.startMs < b.startMs && a.endMs <= b.startMs })
    }

    @Test fun sentencesWithoutSpacesAreSplitAndTimedByLength() {
        val sentence = "今日は天気がいいですね。我们今天去公园散步吧。"
        val pieces = splitSpaceless(listOf(RawWord(sentence, 1000, 8000, 1100), RawWord("Hello", 8000, 8400, -1)))
        assertEquals(RawWord("Hello", 8000, 8400, -1), pieces.last())
        val split = pieces.dropLast(1)
        assertTrue(split.size > 3)
        assertEquals(sentence, split.joinToString("") { it.text })
        assertEquals(1000, split.first().t0)
        assertEquals(1100, split.first().dtw)
        assertEquals(8000, split.last().t1)
        assertTrue(split.zipWithNext().all { (a, b) -> a.t1 == b.t0 && b.dtw == -1 })
        val chunks = chunkWords(alignWords(split, SpeechEdges(emptyList(), emptyList())), 100)
        assertEquals(sentence, chunks.joinToString("") { it.text })
        assertTrue(chunks.size > 1)
        assertEquals("ok today", chunkWords(listOf(Word("ok", 0, 100), Word("today", 120, 300)), 4).single().text)
    }

    @Test fun splitPiecesKeepTheTranscriptSpacing() {
        fun caption(vararg words: String) = chunkWords(alignWords(splitSpaceless(words.mapIndexed { i, w -> RawWord(w, i * 800, i * 800 + 800, -1) }),
            SpeechEdges(emptyList(), emptyList())), 100).single().text
        assertEquals("今日は2026年11月18日です。", caption("今日は2026年11月18日です。"))
        assertEquals("iPhoneを買いました。", caption("iPhoneを買いました。"))
        assertEquals("我们在3点见面吧。", caption("我们在3点见面吧。"))
        assertEquals("สวัสดีครับ วันนี้อากาศดีมาก", caption("สวัสดีครับ", "วันนี้อากาศดีมาก"))
    }

    @Test fun wordsWithoutAccurateTimesKeepTheirOwnStart() {
        val words = alignWords(listOf(RawWord("hi", 250, 600, -1)), SpeechEdges(emptyList(), emptyList()))
        assertEquals(250, words.single().startMs)
        assertEquals(600, words.single().endMs)
    }
}
