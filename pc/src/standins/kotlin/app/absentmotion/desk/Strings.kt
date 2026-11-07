package app.absentmotion.desk

import app.absentmotion.RNames
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

object Strings {
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()
    private var language = "en"

    fun resDir(): File = System.getProperty("am.res")?.let(::File)
        ?: System.getProperty("am.assets")?.let { File(it).parentFile.resolve("res") }
        ?: File(System.getProperty("compose.application.resources.dir") ?: ".", "res")

    init {
        load(resDir().resolve("values/strings.xml"))
        val tag = runCatching { android.content.SharedPreferences.named("language").getString("tag", null) }.getOrNull()
            ?: java.util.Locale.getDefault().toLanguageTag()
        folderFor(tag)?.let { load(resDir().resolve("$it/strings.xml")) }
        val reveal = DeskOs.revealLabel
        listOf("export_share", "export_share_video", "export_share_gif", "export_share_sequence", "export_share_image", "export_share_xml", "compress_share").forEach { strings[it] = reveal }
    }

    private fun folderFor(tag: String): String? {
        val match = app.absentmotion.i18n.AppLanguage.match(listOf(tag)) ?: return null
        val code = if (match.language == "id") "in" else match.language
        if (code == "en") return null
        return listOfNotNull(match.region?.let { "values-$code-r$it" }, "values-$code").firstOrNull { resDir().resolve("$it/strings.xml").isFile }
            ?.also { language = code }
    }

    internal fun pluralCategory(language: String, count: Int): String {
        val n = kotlin.math.abs(count)
        val tens = n % 10
        val hundreds = n % 100
        val millions = n != 0 && n % 1_000_000 == 0
        return when (language) {
            "ru", "uk" -> when { tens == 1 && hundreds != 11 -> "one"; tens in 2..4 && hundreds !in 12..14 -> "few"; else -> "many" }
            "pl" -> when { n == 1 -> "one"; tens in 2..4 && hundreds !in 12..14 -> "few"; else -> "many" }
            "ar" -> when { n == 0 -> "zero"; n == 1 -> "one"; n == 2 -> "two"; hundreds in 3..10 -> "few"; hundreds in 11..99 -> "many"; else -> "other" }
            "fr", "pt" -> when { n <= 1 -> "one"; millions -> "many"; else -> "other" }
            "es", "it" -> when { n == 1 -> "one"; millions -> "many"; else -> "other" }
            "fa", "hi", "bn" -> if (n <= 1) "one" else "other"
            "fil" -> if (tens != 4 && tens != 6 && tens != 9) "one" else "other"
            "in", "ms", "th", "vi", "ja", "ko", "zh" -> "other"
            else -> if (n == 1) "one" else "other"
        }
    }

    private fun load(file: File) {
        if (!file.isFile) return
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(file)
        val root = doc.documentElement
        val kids = root.childNodes
        for (i in 0 until kids.length) {
            val node = kids.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> strings[name] = unescape(textOf(node))
                "plurals" -> {
                    val items = HashMap<String, String>()
                    val list = node.getElementsByTagName("item")
                    for (j in 0 until list.length) { val item = list.item(j) as Element; items[item.getAttribute("quantity")] = unescape(textOf(item)) }
                    plurals[name] = items
                }
            }
        }
    }

    private fun textOf(node: Node): String {
        val out = StringBuilder()
        val kids = node.childNodes
        for (i in 0 until kids.length) {
            val k = kids.item(i)
            when (k.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> out.append(k.nodeValue)
                Node.ELEMENT_NODE -> out.append(textOf(k))
            }
        }
        return out.toString()
    }

    private fun unescape(raw: String): String {
        var s = raw
        val quoted = s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")
        if (quoted) s = s.substring(1, s.length - 1) else s = s.replace(Regex("\\s+"), " ").trim()
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'u' -> if (i + 5 < s.length) { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 } else out.append(n)
                    else -> out.append(n)
                }
                i += 2
            } else if (c == '"' && !quoted) { i++ } else { out.append(c); i++ }
        }
        return out.toString()
    }

    fun nameOf(id: Int): String? = RNames.strings.getOrNull(id - 0x7f100000)

    fun raw(id: Int): String = nameOf(id)?.let { strings[it] } ?: "#$id"

    fun get(id: Int, vararg args: Any?): String {
        val text = raw(id)
        return if (args.isEmpty()) text else runCatching { String.format(Locale.ENGLISH, text, *args) }.getOrDefault(text)
    }

    fun plural(id: Int, count: Int, vararg args: Any?): String {
        val forms = RNames.plurals.getOrNull(id - 0x7f110000)?.let { plurals[it] } ?: return "#$id"
        val text = forms[pluralCategory(language, count)] ?: forms["other"] ?: forms.values.firstOrNull() ?: ""
        return runCatching { String.format(Locale.ENGLISH, text, *args) }.getOrDefault(text)
    }

    fun byName(name: String): String? = strings[name]
}
