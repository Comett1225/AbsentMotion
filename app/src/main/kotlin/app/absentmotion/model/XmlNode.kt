package app.absentmotion.model

import app.absentmotion.platform.xmlParser
import org.xmlpull.v1.XmlPullParser

data class XmlNode(
    val name: String,
    val attrs: Map<String, String> = emptyMap(),
    val children: List<XmlNode> = emptyList(),
    val text: String = "",
) {
    operator fun get(attr: String): String? = attrs[attr]

    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }

    fun with(attr: String, value: String?): XmlNode =
        copy(attrs = if (value == null) attrs - attr else attrs + (attr to value))

    fun withChild(name: String, node: XmlNode?): XmlNode {
        val index = children.indexOfFirst { it.name == name }
        return copy(
            children = when {
                node == null -> children.filterNot { it.name == name }
                index < 0 -> children + node
                else -> children.toMutableList().also { it[index] = node }
            },
        )
    }

    fun toXml(): String = StringBuilder("<?xml version='1.0' encoding='UTF-8' ?>\n").also { write(it, 0) }.toString()

    private fun write(out: StringBuilder, depth: Int) {
        out.append("  ".repeat(depth)).append('<').append(name)
        for ((key, value) in attrs) out.append(' ').append(key).append("=\"").append(escape(value)).append('"')
        if (children.isEmpty() && text.isEmpty()) {
            out.append(" />\n")
            return
        }
        out.append('>')
        if (children.isEmpty()) {
            out.append(escape(text))
        } else {
            out.append('\n')
            for (child in children) child.write(out, depth + 1)
            out.append("  ".repeat(depth))
        }
        out.append("</").append(name).append(">\n")
    }

    companion object {
        fun parse(xml: String): XmlNode {
            val parser = xmlParser()
            parser.setInput(xml.reader())
            while (parser.eventType != XmlPullParser.START_TAG) {
                if (parser.eventType == XmlPullParser.END_DOCUMENT) throw IllegalArgumentException("No root element")
                parser.next()
            }
            return read(parser)
        }

        private fun read(parser: XmlPullParser): XmlNode {
            val name = parser.name
            val attrs = LinkedHashMap<String, String>()
            for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
            val children = ArrayList<XmlNode>()
            val text = StringBuilder()
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> children += read(parser)
                    XmlPullParser.TEXT -> text.append(parser.text)
                    XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT -> break
                }
            }
            return XmlNode(name, attrs, children, if (children.isEmpty()) text.toString() else "")
        }

        private fun escape(s: String): String = buildString(s.length) {
            for (c in s) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\n' -> append("&#10;")
                    '\r' -> append("&#13;")
                    '\t' -> append("&#9;")
                    else -> if (c >= ' ') append(c)
                }
            }
        }
    }
}
