package app.absentmotion.pc

import androidx.compose.ui.text.SpanStyle
import app.absentmotion.ui.home.HelpBlock
import app.absentmotion.ui.home.helpBlocks
import app.absentmotion.ui.home.helpTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class DeskHelpTest {
    @Test fun relativeLinksStayInTheirFolder() {
        assertEquals("ar/x.html", helpTarget("ar/editing.html", "x.html"))
        assertEquals("ar/sub/y.html", helpTarget("ar/editing.html", "sub/y.html"))
        assertEquals("x.html", helpTarget("editing.html", "x.html"))
        assertEquals("index.html", helpTarget("ar/editing.html", "../index.html"))
    }

    @Test fun textBeforeASkippedTagIsKept() {
        val blocks = helpBlocks("<p>Before <figure><img src=\"a.png\"></figure>after</p><h2>Next</h2>", SpanStyle(), {})
        val paragraph = blocks.filterIsInstance<HelpBlock.Para>().first()
        assertEquals("Before after", paragraph.text.text.trim())
        assertEquals(1, blocks.filterIsInstance<HelpBlock.Heading>().size)
    }

    @Test fun textBeforeATocAndHeaderIsKept() {
        val blocks = helpBlocks("<p>Intro<header>skip</header> tail</p><p>Second<details class=\"toc\"><summary>x</summary></details></p>", SpanStyle(), {})
        val texts = blocks.filterIsInstance<HelpBlock.Para>().map { it.text.text.trim() }
        assertEquals(listOf("Intro tail", "Second"), texts)
    }
}
