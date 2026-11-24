package app.absentmotion.pc

import app.absentmotion.model.Scene
import app.absentmotion.pc.engine.DesktopEngine
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextSpaceWrapTest {
    private class Shot(val box: FloatArray, val ink: IntArray, val pixels: IntArray) {
        val height get() = box[3] - box[1]
        override fun toString() = "box ${box.joinToString()} ink ${ink.joinToString()}"
    }

    private val run = " ".repeat(150)
    private val pitch by lazy { shot(xml("x\nx")).height - shot(xml("x")).height }

    private fun spacing(letters: Float = 0f, lines: Float = 1f) =
        """<effect id="com.alightcreative.effects.textspacing" hidden="false"><property name="letterspacing" type="float" value="$letters"/><property name="linespacing" type="float" value="$lines"/></effect>"""

    private fun effect(id: String, vararg values: Pair<String, Float>) =
        """<effect id="absent.$id" hidden="false">""" + values.joinToString("") { """<property name="${it.first}" type="float" value="${it.second}"/>""" } + "</effect>"

    private fun line(content: String, baseline: Number, spacing: Float = 0f, rtl: Boolean = false, from: Int? = null) =
        """<_line baseline="$baseline" rtl="$rtl" wordSpacing="$spacing"${from?.let { " from=\"$it\"" } ?: ""}>$content</_line>"""

    private fun xml(content: String, align: String = "left", extra: String = "", width: Int = 300, font: String = "Roboto") =
        """<scene width="$W" height="$H" fps="30" totalTime="1000" bgcolor="#ff000000"><text id="1" startTime="0" endTime="999" fillType="color" size="20.000000" """ +
            """font="googlefonts?name=$font&amp;weight=400" wrapWidth="$width" align="$align"><transform><location value="${W / 2}.000000,${H / 2}.000000,0.000000" /></transform>""" +
            """<fillColor value="#ffffffff" /><content>${content.replace("\n", "&#10;").replace("\r", "&#13;")}</content>$extra</text></scene>"""

    private fun band(s: Shot, top: Int, bottom: Int): IntArray {
        var left = W; var right = -1
        for (y in maxOf(top, 0) until minOf(bottom, H)) for (x in 0 until W) {
            val c = s.pixels[y * W + x]
            if (maxOf(c shr 16 and 255, c shr 8 and 255, c and 255) > 60) { left = minOf(left, x); right = maxOf(right, x + 1) }
        }
        return intArrayOf(left, right)
    }

    private fun firstRow(s: Shot) = band(s, s.ink[1], s.ink[1] + (pitch * 0.8f).toInt())

    private fun lastRow(s: Shot) = band(s, s.ink[3] - (pitch * 0.8f).toInt(), s.ink[3])

    private fun shot(xml: String): Shot {
        engine.setScene(Scene.parse(xml))
        val q = engine.quad(1, 0)!!
        val xs = (0..3).map { q[2 * it] }
        val ys = (0..3).map { q[2 * it + 1] }
        val pixels = engine.render(0, W, H).getRGB(0, 0, W, H, null, 0, W)
        var left = W; var top = H; var right = -1; var bottom = -1
        for (y in 0 until H) for (x in 0 until W) {
            val c = pixels[y * W + x]
            if (maxOf(c shr 16 and 255, c shr 8 and 255, c and 255) > 60) { left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1) }
        }
        return Shot(floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max()), intArrayOf(left, top, right, bottom), pixels)
    }

    private fun inside(label: String, s: Shot) {
        assertTrue("$label: something is drawn ($s)", s.ink[2] > s.ink[0])
        assertTrue("$label: the text stays inside the box ($s)", s.ink[0] >= s.box[0] - 2 && s.ink[2] <= s.box[2] + 2 && s.ink[3] <= s.box[3] + 2)
    }

    private fun pushed(label: String, plain: Shot, spaced: Shot, lines: Int = 4) {
        inside(label, spaced)
        val grown = spaced.height - plain.height
        assertTrue("$label: the box grows by the extra lines (plain $plain, spaced $spaced)", grown > pitch * lines)
        assertEquals("$label: the text after the spaces moves down as far as the box grows (plain $plain, spaced $spaced)", grown, (spaced.ink[3] - spaced.box[1]) - (plain.ink[3] - plain.box[1]), 4f)
    }

    @Test fun aLongSpaceRunWrapsAndPushesTheNextWordDownInEveryAlignment() {
        for (align in listOf("left", "center", "right", "justify")) {
            pushed(align, shot(xml("hello\nworld", align)), shot(xml("hello${run}world", align)))
        }
    }

    @Test fun spacesAtTheStartAndTheEndTakeRoomToo() {
        val one = shot(xml("hello"))
        pushed("start", shot(xml("x\nhello")), shot(xml("${run}hello")))
        val end = shot(xml("hello$run"))
        inside("end", end)
        assertTrue("a run at the end grows the box ($one, $end)", end.height > one.height * 4)
        assertEquals("the word before the run stays where it was", one.ink[1].toFloat(), end.ink[1] + (end.height - one.height) / 2, 2f)
        val between = shot(xml("hello$run world"))
        pushed("between words", shot(xml("hello\nworld")), between)
    }

    @Test fun moreWordsAfterARunFollowOnLaterLines() {
        val plain = shot(xml("hello\none two three four five six"))
        val spaced = shot(xml("hello${run}one two three four five six"))
        pushed("words after", plain, spaced)
        val again = shot(xml("hello${run}one two$run three four"))
        val twice = shot(xml("hello\none two\nthree four"))
        pushed("two runs", twice, again, lines = 8)
    }

    @Test fun rightToLeftEmojiAndLetterSpacingWrapTheSameWay() {
        pushed("right to left", shot(xml("שלום\nעולם", "right")), shot(xml("שלום${run}עולם", "right")))
        pushed("emoji", shot(xml("😀\n🎉")), shot(xml("😀$run🎉")))
        pushed("letter spacing", shot(xml("hello\nworld", extra = spacing(letters = 0.3f))), shot(xml("hello${run}world", extra = spacing(letters = 0.3f))), lines = 6)
    }

    @Test fun lineSpacingSpacesTheExtraLinesLikeTheOthers() {
        val normal = shot(xml("hello${run}world")).height - shot(xml("hello\nworld")).height
        val plain = shot(xml("hello\nworld", extra = spacing(lines = 2f)))
        val spaced = shot(xml("hello${run}world", extra = spacing(lines = 2f)))
        pushed("line spacing", plain, spaced)
        assertEquals("double line spacing doubles the room the extra lines take", normal * 2, spaced.height - plain.height, normal * 0.1f)
    }

    @Test fun theBoxGrowsWithEverySpaceTypedOnceTheLineIsFull() {
        val heights = (0..300 step 10).map { shot(xml("hello" + " ".repeat(it))).height }
        assertTrue("$heights", heights.zipWithNext().all { (a, b) -> b >= a })
        assertEquals("$heights", heights[0], heights[1], 0.01f)
        assertTrue("$heights", heights.last() > heights[0] * 8)
    }

    @Test fun spacesThatFitAndOrdinaryWrapsKeepTheirLayout() {
        assertEquals(shot(xml("ab")).height, shot(xml("a     b")).height, 0.01f)
        val words = "one two three four five six seven eight nine ten"
        val wrapped = shot(xml(words))
        assertTrue(wrapped.height > shot(xml("one")).height * 2)
        inside("ordinary wrap", wrapped)
    }

    @Test fun animatedTextDrawsTheSameSplitLines() {
        for (content in listOf("hello${run}world", "${run}hello", "hello${run}one two$run three")) {
            val plain = shot(xml(content, "center"))
            val shown = shot(xml(content, "center", effect("textprogress", "progress" to 1f)))
            assertArrayEquals(content.trim(), plain.pixels, shown.pixels)
            assertArrayEquals(plain.box, shown.box, 0.01f)
            val scrambled = shot(xml(content, "center", effect("textrandomizer", "progress" to 1f, "keepSpaces" to 0f)))
            assertArrayEquals(content.trim(), plain.pixels, scrambled.pixels)
        }
    }

    @Test fun androidPreparedLinesWrapTheirSpacesToo() {
        val layout = { first: String, spacing: Float, rtl: Boolean -> "<_layout height=\"152\">${line(first, 64, spacing, rtl)}${line(if (rtl) "עולם" else "world", 135, 0f, rtl)}</_layout>" }
        for (align in listOf("left", "center", "right", "justify")) {
            val plain = shot(xml("hello\nworld", align, layout("hello", 0f, false)))
            val squeeze = if (align == "justify") -40f else 0f
            pushed("prepared $align", plain, shot(xml("hello${run}world", align, layout("hello" + run.drop(1), squeeze, false))))
        }
        pushed("prepared right to left", shot(xml("שלום\nעולם", "right", layout("שלום", 0f, true))), shot(xml("שלום${run}עולם", "right", layout("שלום" + run.drop(1), 0f, true))))
        val start = shot(xml("${run}hello", "left", "<_layout height=\"152\">${line(run.drop(1), 64)}${line("hello", 135)}</_layout>"))
        pushed("prepared start", shot(xml("\nhello", "left", "<_layout height=\"152\">${line("", 64)}${line("hello", 135)}</_layout>")), start)
    }

    @Test fun textOnAPathKeepsItsLines() {
        val path = effect("textpath", "shape" to 0f)
        assertArrayEquals(shot(xml("hello world", width = 600, extra = path)).pixels, shot(xml("hello world", width = 40, extra = path)).pixels)
    }

    @Test fun rightToLeftTextEndsOnTheLeftOnTheComputerToo() {
        for (align in listOf("left", "right")) {
            assertArrayEquals(align, shot(xml("שלום עולם!\u200F", align)).pixels, shot(xml("שלום עולם!", align)).pixels)
        }
    }

    @Test fun aSpaceOrTwoAtTheEndOfALineStillHangLikeBefore() {
        for ((width, plain, spaced) in listOf(
            Triple(300, "iiiiiiiiiiiiiiiiiiii\nb", "iiiiiiiiiiiiiiiiiiii \nb"),
            Triple(286, "abcdefghij\nxyz", "abcdefghij \nxyz"),
            Triple(300, "abcdefghij. xyz", "abcdefghij.  xyz"),
        )) {
            val a = shot(xml(plain, width = width))
            val b = shot(xml(spaced, width = width))
            assertArrayEquals(spaced, a.box, b.box, 0.01f)
            assertArrayEquals(spaced, a.pixels, b.pixels)
        }
        val layout = { first: String -> "<_layout height=\"152\" step=\"71\">${line(first, 64, from = 0)}${line("xyz", 135, from = first.length + 1)}</_layout>" }
        assertEquals(shot(xml("abcdefghij\nxyz", extra = layout("abcdefghij"), width = 286)).height,
            shot(xml("abcdefghij \nxyz", extra = layout("abcdefghij "), width = 286)).height, 0.01f)
    }

    @Test fun theTextAfterARunCarriesOnRightAfterTheSpacesThatAreLeft() {
        assertEquals(shot(xml("hello\nworld")).height, shot(xml("hello" + " ".repeat(13) + "world")).height, 0.01f)
        val starts = (20..60).map { lastRow(shot(xml("hello" + " ".repeat(it) + "world")))[0] }
        assertTrue("$starts", starts.toSet().size > 10)
        val sentence = shot(xml("hello${run}one two three four five six seven eight nine ten"))
        pushed("words after", shot(xml("hello\none two three four five six seven eight nine ten")), sentence)
    }

    @Test fun windowsLineEndsAndFullWidthSpacesWrapToo() {
        pushed("crlf", shot(xml("hello\r\nworld")), shot(xml("hello$run\r\nworld")))
        pushed("ideographic spaces", shot(xml("hello\nworld")), shot(xml("hello" + "\u3000".repeat(60) + "world")))
    }

    @Test fun rightToLeftWordsKeepTheirSpacesOnTheirLeft() {
        val hebrew = "שלום${run}עולם"
        val layout = "<_layout height=\"152\" step=\"71\">${line("שלום" + run.drop(1), 64, rtl = true, from = 0)}${line("עולם", 135, rtl = true, from = 154)}</_layout>"
        for (align in listOf("left", "center", "right")) {
            for ((label, s) in listOf("computer" to shot(xml(hebrew, align)), "phone" to shot(xml(hebrew, align, layout)))) {
                inside("$label $align", s)
                assertEquals("$label $align: the first word sits at the right edge ($s)", s.box[2], firstRow(s)[1].toFloat(), 4f)
            }
        }
    }

    @Test fun theAddedLinesStepLikeTheFontsOwnLines() {
        for (font in listOf("Roboto", "Poppins", "Tajawal")) {
            val one = shot(xml("x", font = font)).height
            val step = shot(xml("x\nx", font = font)).height - one
            val rows = (shot(xml("x$run", font = font)).height - one) / step
            assertEquals("$font: rows of $step", Math.round(rows).toFloat(), rows, 0.01f)
        }
        val prepared = { content: String, first: String -> shot(xml(content, extra = "<_layout>${line(first, 64, from = 0)}</_layout>")) }
        val grown = prepared("x$run", "x" + run.drop(1)).height - prepared("x", "x").height
        assertEquals("$grown", Math.round(grown / pitch).toFloat(), grown / pitch, 0.001f)
    }

    @Test fun aJustifiedLineWhoseSpacesAllMoveDownStaysJustified() {
        val words = "aaaa bbbb cccc"
        val wide = shot(xml(words, width = 800))
        val width = wide.ink[2] - wide.ink[0] + 10
        val s = shot(xml("$words${run}dddd eeee", "justify", width = width))
        inside("justify", s)
        assertEquals("$s", s.box[2], firstRow(s)[1].toFloat(), 2f)
    }

    @Test fun phoneLinesAndComputerLinesGiveTheSameRows() {
        val words = listOf("one two", "three four", "five six")
        val cases = listOf(
            listOf("hello" + run.drop(1), "world") to listOf("hello", "world"),
            (listOf("hello" + run.drop(1)) + words) to (listOf("hello") + words),
            listOf(run.drop(1), "hello") to listOf("x", "hello"),
        )
        val content = { lines: List<String>, hard: Boolean -> lines.joinToString(if (hard) "\n" else " ") }
        for (align in listOf("left", "center", "right", "justify")) for ((spaced, plain) in cases) {
            val prepared = { lines: List<String>, height: Float ->
                var from = 0
                "<_layout height=\"$height\" step=\"$pitch\">" + lines.mapIndexed { i, text -> line(text, 64 + pitch * i, from = from).also { from += text.length + 1 } }.joinToString("") + "</_layout>"
            }
            val text = content(spaced, false)
            val plainText = "${plain[0]}\n" + content(plain.drop(1), false)
            val computer = shot(xml(text, align))
            val computerPlain = shot(xml(plainText, align))
            val phone = shot(xml(text, align, prepared(spaced, computerPlain.height)))
            val phonePlain = shot(xml(plainText, align, prepared(plain, computerPlain.height)))
            val label = "$align ${text.trim()}"
            assertEquals(label, computer.height - computerPlain.height, phone.height - phonePlain.height, 0.01f)
            assertArrayEquals("$label first row", firstRow(computer), firstRow(phone))
            assertArrayEquals("$label last row", lastRow(computer), lastRow(phone))
            assertEquals(label, (computer.ink[3] - computer.ink[1]).toFloat(), (phone.ink[3] - phone.ink[1]).toFloat(), 1f)
        }
    }

    companion object {
        private const val W = 900
        private const val H = 1600
        private val root = File("..").canonicalFile
        private val engine by lazy { DesktopEngine(root.resolve("shared/assets"), root) }

        @AfterClass @JvmStatic fun close() = engine.close()
    }
}
