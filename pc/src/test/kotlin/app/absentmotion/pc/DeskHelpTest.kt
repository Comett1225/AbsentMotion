package app.absentmotion.pc

import androidx.compose.ui.text.SpanStyle
import app.absentmotion.ui.home.HelpBlock
import app.absentmotion.ui.home.helpBlocks
import app.absentmotion.ui.home.helpTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class DeskHelpTest {
    @Test fun htmlEntitiesAreDecodedOnce() {
        assertEquals("60° to 360°, it's a ‘x’ &lt; ×  ", app.absentmotion.ui.home.entities("60&deg; to 360&#176;, it&#x27;s a &lsquo;x&rsquo; &amp;lt; &times; &nbsp;"))
        assertEquals("&bogus; &#xZZ;", app.absentmotion.ui.home.entities("&bogus; &#xZZ;"))
    }

    @Test fun relativeLinksStayInTheirFolder() {
        assertEquals("ar/x.html", helpTarget("ar/editing.html", "x.html"))
        assertEquals("ar/sub/y.html", helpTarget("ar/editing.html", "sub/y.html"))
        assertEquals("x.html", helpTarget("editing.html", "x.html"))
        assertEquals("index.html", helpTarget("ar/editing.html", "../index.html"))
    }

    @Test fun textAroundAFigureIsKept() {
        val blocks = helpBlocks("<p>Before <figure><img src=\"a.png\" width=\"720\" height=\"572\" alt=\"Alt &amp; text\"><figcaption>Cap</figcaption></figure>after</p><h2>Next</h2>", SpanStyle(), {})
        assertEquals(listOf("Before", "after"), blocks.filterIsInstance<HelpBlock.Para>().map { it.text.text.trim() })
        val picture = blocks.filterIsInstance<HelpBlock.Picture>().single()
        assertEquals("a.png", picture.src)
        assertEquals("Alt & text", picture.alt)
        assertEquals(720, picture.width)
        assertEquals("Cap", blocks.filterIsInstance<HelpBlock.Caption>().single().text.text)
        assertEquals(1, blocks.filterIsInstance<HelpBlock.Heading>().size)
    }

    @Test fun loosePicturesAreSkippedButThumbnailsKept() {
        val blocks = helpBlocks("<ul><li><a href=\"x.html\"><img src=\"s.webp\"> Name</a></li></ul><img class=\"fx-thumb\" src=\"t.webp\" width=\"192\" height=\"192\" alt=\"\">", SpanStyle(), {})
        assertEquals(listOf("t.webp"), blocks.filterIsInstance<HelpBlock.Picture>().map { it.src })
    }

    @Test fun tablesBecomeRowsAndCalloutsNotes() {
        val html = "<div class=\"callout tip\"><p>Careful</p></div><p>Plain</p><div class=\"table-wrap\"><table><thead><tr><th>Setting</th><th>Default</th></tr></thead><tbody><tr><td><strong>Strength</strong></td><td>10</td></tr></tbody></table></div>"
        val blocks = helpBlocks(html, SpanStyle(), {})
        assertEquals(listOf(true, false), blocks.filterIsInstance<HelpBlock.Para>().map { it.note })
        val rows = blocks.filterIsInstance<HelpBlock.Row>()
        assertEquals(listOf(true, false), rows.map { it.head })
        assertEquals(listOf("Strength", "10"), rows[1].cells.map { it.text })
    }

    @Test fun textBeforeATocAndHeaderIsKept() {
        val blocks = helpBlocks("<p>Intro<header>skip</header> tail</p><p>Second<details class=\"toc\"><summary>x</summary></details></p>", SpanStyle(), {})
        val texts = blocks.filterIsInstance<HelpBlock.Para>().map { it.text.text.trim() }
        assertEquals(listOf("Intro tail", "Second"), texts)
    }
}
