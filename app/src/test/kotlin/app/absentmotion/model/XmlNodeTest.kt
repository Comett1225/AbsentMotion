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
}
