package app.absentmotion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class PendingSceneTest {
    @Test fun aFontChangeRedrawsTheSceneOnScreenNotAnOlderOne() {
        val pending = PendingScene()
        pending.set("A")
        assertEquals("A", pending.take(null))
        assertNull(pending.take("A"))
        pending.fontsChanged()
        assertEquals("A", pending.take("A"))
        pending.fontsChanged()
        pending.set("B")
        assertEquals("B", pending.take("A"))
        assertNull(pending.take("B"))
    }

    @Test fun editsAndFontChangesFromOtherThreadsNeverBringBackAnOlderScene() {
        val pending = PendingScene()
        val last = 20_000
        val editor = thread { for (n in 1..last) pending.set("$n") }
        val fonts = thread { repeat(20_000) { pending.fontsChanged() } }
        var shown: String? = null
        var newest = 0
        while (editor.isAlive || fonts.isAlive) {
            pending.take(shown)?.let { shown = it }
            val now = shown?.toInt() ?: 0
            assertTrue("$now after $newest", now >= newest)
            newest = now
        }
        editor.join(); fonts.join()
        pending.take(shown)?.let { shown = it }
        assertEquals("$last", shown)
    }
}
