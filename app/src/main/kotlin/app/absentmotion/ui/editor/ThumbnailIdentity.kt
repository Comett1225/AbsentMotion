package app.absentmotion.ui.editor

internal class BoundedCache<K : Any, V : Any>(private val capacity: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > capacity
    }

    @Synchronized operator fun get(key: K): V? = map[key]
    @Synchronized operator fun set(key: K, value: V) { map[key] = value }
    @Synchronized fun remove(key: K) { map.remove(key) }
    @Synchronized fun clear() = map.clear()
    @Synchronized fun size(): Int = map.size
}

internal data class Stamped<K : Any, V : Any>(val key: K, val value: V?)

internal fun <K : Any, V : Any> Stamped<K, V>?.of(key: K): V? = if (this != null && this.key == key) value else null

internal fun sourceIdentity(uris: Set<String>, installed: Map<String, String>): String? =
    uris.sorted().map { installed[it] ?: return null }.joinToString("|")

internal fun <V : Any> cachedTiles(tiles: List<Pair<Int, Long>>, lookup: (Long) -> V?): List<Pair<Int, V>> =
    tiles.mapNotNull { (projectTime, timeUs) -> lookup(timeUs)?.let { projectTime to it } }

internal data class SourceVersions(val trusted: Boolean = true, val versions: Map<String, String> = emptyMap()) {
    fun with(uri: String, version: String?, limit: Int = 64): SourceVersions {
        if (versions[uri] == version) return this
        if (version == null) return copy(versions = versions - uri)
        val next = versions - uri + (uri to version)
        return copy(versions = if (next.size <= limit) next else next.entries.drop(next.size - limit).associate { it.key to it.value })
    }

    fun seed(uri: String): String? = if (trusted) versions[uri] else null
}
