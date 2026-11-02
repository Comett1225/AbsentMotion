package app.absentmotion.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatteRefineTest {
    private class Art(val width: Int, val height: Int, val alpha: ByteArray)

    private fun art(vararg rows: String): Art {
        val bytes = rows.joinToString("").map { when (it) { '#' -> 255; '+' -> 60; else -> 0 }.toByte() }.toByteArray()
        return Art(rows[0].length, rows.size, bytes)
    }

    private fun Art.text(bytes: ByteArray) = (0 until height).joinToString("\n") { y ->
        (0 until width).joinToString("") { x -> val a = bytes[y * width + x].toInt() and 0xFF; if (a >= 128) "#" else if (a > 0) "+" else "." }
    }

    private fun tap(art: Art, x: Int, y: Int): Tap = (x + 0.5f) / art.width to (y + 0.5f) / art.height

    private fun labels(art: Art): Pair<IntArray, Int> {
        val labels = IntArray(art.alpha.size)
        return labels to matteParts(art.alpha, art.width, art.height, labels, IntArray(art.alpha.size))
    }

    @Test fun partsAreSolidAreasAndSoftPixelsJoinTheNearestPart() {
        val a = art(
            "##+....+##",
            "##+....+##",
            "..........",
        )
        val (labels, count) = labels(a)
        assertEquals(2, count)
        assertEquals(labels[0], labels[2])
        assertEquals(labels[9], labels[7])
        assertTrue(labels[0] != labels[9])
        assertEquals(0, labels[4])
    }

    @Test fun aTapKeepsThePartUnderItAndANearMissStillCounts() {
        val a = art(
            "##........................##",
            "##........................##",
            "............................",
            "............................",
        )
        val (labels, count) = labels(a)
        assertEquals(listOf(false, true, false), pickedParts(labels, count, a.width, a.height, listOf(tap(a, 0, 0))).toList())
        assertEquals(listOf(false, false, true), pickedParts(labels, count, a.width, a.height, listOf(tap(a, 24, 3))).toList())
        assertEquals(listOf(false, false, false), pickedParts(labels, count, a.width, a.height, listOf(tap(a, 14, 3))).toList())
    }

    @Test fun theFollowerKeepsAMovingPartDropsNewOnesAndKeepsWhatMergesWithIt() {
        val first = art(
            "##..........##",
            "##..........##",
        )
        val follower = PartFollower(listOf(tap(first, 0, 0)))
        assertEquals("##............\n##............", first.text(follower.next(first.alpha, first.width, first.height)))
        val moved = art(
            ".##.....##..##",
            ".##.....##..##",
        )
        assertEquals(".##...........\n.##...........", moved.text(follower.next(moved.alpha, moved.width, moved.height)))
        val merged = art(
            ".#######....##",
            ".#######....##",
        )
        assertEquals(".#######......\n.#######......", merged.text(follower.next(merged.alpha, merged.width, merged.height)))
        val apart = art(
            ".##....##...##",
            ".##....##...##",
        )
        assertEquals(".##....##.....\n.##....##.....", apart.text(follower.next(apart.alpha, apart.width, apart.height)))
    }

    @Test fun aLostPartIsFoundAgainWhereItWasAndAMissedTapKeepsLooking() {
        val part = art("##......", "##......")
        val empty = art("........", "........")
        val follower = PartFollower(listOf(tap(part, 0, 0)))
        follower.next(part.alpha, part.width, part.height)
        assertEquals(empty.text(empty.alpha), empty.text(follower.next(empty.alpha, empty.width, empty.height)))
        assertEquals("##......\n##......", part.text(follower.next(part.alpha, part.width, part.height)))
        val waiting = PartFollower(listOf(tap(part, 0, 0)))
        assertEquals(empty.text(empty.alpha), empty.text(waiting.next(empty.alpha, empty.width, empty.height)))
        assertEquals("##......\n##......", part.text(waiting.next(part.alpha, part.width, part.height)))
    }

    @Test fun bigPicturesArePickedAtALowerResolution() {
        val width = 3000
        val height = 1000
        val alpha = ByteArray(width * height)
        for (y in 100 until 900) {
            for (x in 100 until 900) alpha[y * width + x] = -1
            for (x in 2000 until 2900) alpha[y * width + x] = -1
        }
        clearUnpicked(alpha, width, height, listOf(0.85f to 0.5f))
        assertEquals(0, alpha[500 * width + 500].toInt())
        assertEquals(-1, alpha[500 * width + 2500].toInt())
        assertEquals(-1, alpha[100 * width + 2000].toInt())
    }

    private fun keyed(code: Byte, edge: MatteEdge): Float {
        val (low, span) = edge.key()
        val x = (((code.toInt() and 0xFF) / 255f - low) / span).coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    private fun step(width: Int = 40, edgeAt: Int = 20): Art = Art(width, 1, ByteArray(width) { if (it < edgeAt) -1 else 0 })

    private fun crossing(values: List<Float>): Float {
        val i = values.indexOfFirst { it < 0.5f }
        return i - 1 + (values[i - 1] - 0.5f) / (values[i - 1] - values[i])
    }

    @Test fun theNeutralEdgeLooksLikeTheModelsOwnEdge() {
        val ramp = Art(9, 1, ByteArray(9) { (255 - it * 32).coerceIn(0, 255).toByte() })
        val coded = encodeMatte(ramp.alpha, ramp.width, ramp.height)
        for (i in 0 until ramp.width) {
            val a = (ramp.alpha[i].toInt() and 0xFF) / 255f
            val expected = if (a <= 6 / 255f) 0f else if (a >= 249 / 255f) 1f else a * a * (3 - 2 * a)
            assertEquals("pixel $i", expected, keyed(coded[i], MatteEdge()), 0.05f)
        }
    }

    @Test fun growAndShrinkMoveTheEdgeAndSoftnessWidensIt() {
        val hard = step()
        val coded = encodeMatte(hard.alpha, hard.width, hard.height)
        val neutral = crossing(coded.map { keyed(it, MatteEdge()) })
        assertEquals(19.5f, neutral, 0.1f)
        assertEquals(1.2f, crossing(coded.map { keyed(it, MatteEdge(grow = 4)) }) - neutral, 0.3f)
        assertEquals(3f, crossing(coded.map { keyed(it, MatteEdge(grow = 10)) }) - neutral, 0.3f)
        assertEquals(-3f, crossing(coded.map { keyed(it, MatteEdge(grow = -10)) }) - neutral, 0.3f)
        fun partial(edge: MatteEdge) = coded.count { keyed(it, edge) in 0.02f..0.98f }
        assertTrue(partial(MatteEdge(softness = 10)) > partial(MatteEdge()))
        assertTrue(keyed(coded[21], MatteEdge(softness = 10)) > keyed(coded[21], MatteEdge()) + 0.1f)
    }

    @Test fun theFrameBorderIsNotAnEdge() {
        val full = Art(30, 20, ByteArray(600) { -1 })
        val coded = encodeMatte(full.alpha, full.width, full.height)
        assertTrue(coded.all { keyed(it, MatteEdge(softness = 10, grow = -10)) > 0.99f })
        val empty = Art(30, 20, ByteArray(600))
        assertTrue(encodeMatte(empty.alpha, 30, 20).all { keyed(it, MatteEdge(softness = 10, grow = 10)) < 0.01f })
    }

    @Test fun picturesBakeTheSameCurveAsTheVideoKey() {
        val hard = step()
        val coded = encodeMatte(hard.alpha, hard.width, hard.height)
        for (edge in listOf(MatteEdge(), MatteEdge(5, 3), MatteEdge(10, -6), MatteEdge(10, 10))) {
            val baked = bakeEdge(hard.alpha, hard.width, hard.height, edge, 1f)
            for (i in baked.indices) assertEquals("$edge $i", keyed(coded[i], edge), (baked[i].toInt() and 0xFF) / 255f, 0.03f)
        }
        val big = bakeEdge(step(200, 100).alpha, 200, 1, MatteEdge(grow = 6), 4f)
        assertEquals(106.7f, crossing(big.map { (it.toInt() and 0xFF) / 255f }), 1f)
        assertEquals(1f, pictureUnit(400, 300))
        assertEquals(5.5f, pictureUnit(8000, 6000))
    }

    @Test fun everyEdgeSettingSurvivesTheLumaKey() {
        assertEquals(5f / 12, MatteEdge().key().first, 1e-6f)
        assertEquals(1f / 6, MatteEdge().key().second, 1e-6f)
        for (softness in 0..MatteEdge.MAX_SOFTNESS) for (grow in -MatteEdge.MAX_GROW..MatteEdge.MAX_GROW) {
            val edge = MatteEdge(softness, grow)
            val (threshold, span) = edge.key()
            assertTrue("$edge", threshold >= 0f && span > 0f && threshold + span <= 1f)
            assertEquals(edge, MatteEdge.of(threshold, span))
        }
        assertEquals(MatteEdge(10, 0), MatteEdge.of(0f, 1f))
    }

    @Test fun cutOutSettingsAreStoredAndReadBack() {
        val cutout = Cutout(listOf(0.25f to 0.5f, 0.75f to 0.125f), MatteEdge(3, -2))
        val read = Cutout.parse(cutout.option)
        assertEquals(cutout.keep, read.keep)
        assertEquals(cutout.edge, read.edge)
        assertNull(Cutout().option)
        assertTrue(Cutout.parse(null).plain)
        assertTrue(Cutout.parse("voice").plain)
        val odd = Cutout.parse("soft=99;grow=-99;keep=0.5:0.5,2:0.1,NaN:0.2,x,0.1:0.2:0.3")
        assertEquals(listOf(0.5f to 0.5f), odd.keep)
        assertEquals(MatteEdge(MatteEdge.MAX_SOFTNESS, -MatteEdge.MAX_GROW), odd.edge)
        assertEquals(MAX_TAPS, Cutout.parse("keep=" + List(20) { "0.1:0.1" }.joinToString(",")).keep.size)
    }

    @Test fun plainCutOutsLeaveTheMatteAlone() {
        val a = art("#+.#")
        val copy = a.alpha.copyOf()
        assertArrayEquals(copy, Cutout().refine(a.alpha, a.width, a.height))
    }

    @Test fun tapsMapBetweenTheShownPictureAndTheFrame() {
        for (turn in listOf(0, 90, 180, 270, -90, 450)) for ((u, v) in listOf(0.1f to 0.2f, 0.9f to 0.3f, 0.5f to 0.5f)) {
            val (x, y) = frameToView(u, v, turn)
            val (bu, bv) = viewToFrame(x, y, turn)
            assertEquals(u, bu, 1e-6f)
            assertEquals(v, bv, 1e-6f)
        }
        assertEquals(1f to 0f, frameToView(0f, 0f, 90))
        assertEquals(0f to 1f, frameToView(0f, 0f, 270))
        assertEquals(1f to 1f, frameToView(0f, 0f, 180))
    }

    @Test fun theMatteScalesThePixelsOwnAlpha() {
        assertEquals(0x80112233.toInt(), withMatte(0xFF112233.toInt(), 128.toByte()))
        assertEquals(0x00112233, withMatte(0xFF112233.toInt(), 0))
        assertEquals(0x40112233, withMatte(0x80112233.toInt(), 128.toByte()))
    }
}
