package android.os

import java.util.Locale

class LocaleList(private vararg val list: Locale) {
    fun size() = list.size
    operator fun get(i: Int): Locale = list[i]
    fun toLanguageTags() = list.joinToString(",") { it.toLanguageTag() }
    val isEmpty: Boolean get() = list.isEmpty()
    companion object {
        @JvmStatic fun getDefault() = LocaleList(Locale.getDefault())
        @JvmStatic fun forLanguageTags(tags: String?) = LocaleList(*(tags ?: "").split(',').filter { it.isNotBlank() }.map(Locale::forLanguageTag).toTypedArray())
        @JvmStatic fun getEmptyLocaleList() = LocaleList()
    }
}
