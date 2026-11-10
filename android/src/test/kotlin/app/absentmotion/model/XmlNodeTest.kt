package app.absentmotion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XmlNodeTest {
    private val document = XmlNode(
        "scene", linkedMapOf("width" to "10", "height" to "10", "title" to "Cut & <paste> \"me\""),
        listOf(
            XmlNode("shape", linkedMapOf("id" to "1"), listOf(XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "1,2,3")))))),
            XmlNode("text", linkedMapOf("id" to "2"), text = "words & more"),
            XmlNode("shape", linkedMapOf("id" to "3")),
        ),
    ).toXml()

    @Test fun editingAKeyframedValueKeepsEveryAttributeOfTheOtherKeyframes() {
        val property = XmlNode.parse(
            "<location lock=\"yes\"><kf t=\"0.000000\" v=\"1,2,3\" x-note=\"first\" e=\"linear\"/><kf t=\"1.000000\" v=\"4,5,6\" x-note=\"last\"/></location>",
        )
        val moved = property.withValueAt(1f, "7,8,9", 1f..1f)
        assertEquals("yes", moved["lock"])
        assertEquals(listOf("first", "last"), moved.children.map { it["x-note"] })
        assertEquals("7,8,9", moved.keyframes.last().v)
        assertEquals(mapOf("x-note" to "first"), moved.keyframes.first().extra)
        assertEquals("linear", moved.keyframes.first().e)
        val added = property.withValueAt(0.5f, "2,3,4", 0.5f..0.5f)
        assertEquals(listOf("first", null, "last"), added.children.map { it["x-note"] })
        assertEquals(listOf("first", "last"), property.withKeyframeToggled(0.5f, 0.5f..0.5f).children.map { it["x-note"] }.filterNotNull())
    }

    @Test fun writingEscapesMarkupAndDropsControlCharacters() {
        val node = XmlNode("a", linkedMapOf("v" to "x&<>\"\n\r\t\u0001y", "plain" to "1,2"), text = "<b>&\uFFFE")
        assertEquals("<?xml version='1.0' encoding='UTF-8' ?>\n<a v=\"x&amp;&lt;&gt;&quot;&#10;&#13;&#9;y\" plain=\"1,2\">&lt;b&gt;&amp;</a>\n", node.toXml())
        val nested = XmlNode("a", children = listOf(XmlNode("b", children = listOf(XmlNode("c")))))
        assertEquals("<?xml version='1.0' encoding='UTF-8' ?>\n<a>\n  <b>\n    <c />\n  </b>\n</a>\n", nested.toXml())
    }

    @Test fun aMediaRecordGoesOnlyWhenNoLayerUsesItsFile() {
        fun picture(id: Int, uri: String) = XmlNode("shape", linkedMapOf("id" to "$id", "startTime" to "0", "endTime" to "999", "fillType" to "media", "fillImage" to uri))
        val group = XmlNode("embedScene", linkedMapOf("id" to "3", "startTime" to "0", "endTime" to "999"), listOf(XmlNode("scene", children = listOf(picture(4, "b")))))
        val scene = Scene(XmlNode("scene", linkedMapOf("width" to "10", "height" to "10", "fps" to "30"),
            listOf(XmlNode("media", mapOf("uri" to "a")), XmlNode("media", mapOf("uri" to "b")), XmlNode("media", mapOf("uri" to "c")), picture(1, "a"), picture(2, "a"), group, picture(5, "c"))))
        fun records(s: Scene) = s.media.map { it.uri }
        assertEquals(listOf("a", "b", "c"), records(scene.update(1) { it.withLabel("renamed") }))
        assertEquals(listOf("a", "b", "c"), records(scene.remove(1)))
        assertEquals(listOf("b", "c"), records(scene.remove(1).remove(2)))
        assertEquals(listOf("a", "c"), records(scene.remove(3)))
        assertEquals(listOf("a", "b"), records(scene.update(5) { it.copy(node = it.node.with("fillImage", "a")) }))
        assertEquals(listOf("a", "b", "c"), records(scene.update(1) { it.copy(node = it.node.with("fillImage", "c")) }))
    }

    @Test fun aCompleteDocumentParsesBackToTheSameTree() {
        assertEquals(document, XmlNode.parse(document).toXml())
    }

    @Test fun everyTruncationOfADocumentIsAnError() {
        val whole = document.trimEnd()
        for (cut in 0 until whole.length) {
            try {
                XmlNode.parse(whole.substring(0, cut))
                fail("a document cut at $cut of ${whole.length} parsed: ${whole.substring(0, cut).takeLast(30)}")
            } catch (_: Exception) {
            }
        }
        XmlNode.parse(whole)
    }

    @Test fun charactersXmlCannotCarryAreLeftOutWhenWriting() {
        val node = XmlNode("scene", linkedMapOf("title" to "a￾b￿c\u0001d\u0000e"), text = "x￾y")
        val xml = node.toXml()
        assertFalse(xml.any { it == '￾' || it == '￿' || it == '\u0001' || it == '\u0000' })
        val back = XmlNode.parse(xml)
        assertEquals("abcde", back["title"])
        assertEquals("xy", back.text)
    }

    @Test fun supplementaryCharactersAndTabsAreKept() {
        val node = XmlNode("scene", linkedMapOf("title" to "smile 😀\t\n\ré퟿�"))
        assertEquals(node["title"], XmlNode.parse(node.toXml())["title"])
        assertTrue(node.toXml().contains("😀"))
    }

    @Test fun entityDeclarationsAreNotExpanded() {
        val laughs = buildString {
            append("<!DOCTYPE scene [<!ENTITY a0 \"lol lol lol lol lol lol lol lol lol lol\">")
            for (i in 1..12) append("<!ENTITY a$i \"" + "&a${i - 1};".repeat(10) + "\">")
            append("]><scene title=\"&a12;\"/>")
        }
        val start = System.nanoTime()
        val title = runCatching { XmlNode.parse(laughs)["title"].orEmpty() }.getOrDefault("")
        assertTrue(title.length < 100)
        assertTrue(System.nanoTime() - start < 2_000_000_000L)
    }
}
