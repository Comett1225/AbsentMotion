package app.absentmotion.pc

import app.absentmotion.model.XmlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeskXmlTest {
    @Test
    fun numericReferencesAboveBmpBecomeRealCharacters() {
        val node = XmlNode.parse("<a t=\"x&#128512;y\"><c>&#x1F600;&#65;</c></a>")
        assertEquals("x😀y", node.attrs["t"])
        assertEquals("😀A", node.children.single().text)
    }

    @Test
    fun namespacesBehaveLikeThePhone() {
        val node = XmlNode.parse("<a xmlns:x=\"urn:u\" x:href=\"v\" />")
        assertEquals("v", node.attrs["href"])
        assertFalse(node.attrs.keys.any { it.startsWith("xmlns") })
    }
}
