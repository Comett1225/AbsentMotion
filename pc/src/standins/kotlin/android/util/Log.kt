package android.util

object Log {
    private val on = System.getProperty("am.log") != null
    private fun out(level: String, tag: String?, msg: String?, tr: Throwable? = null): Int {
        if (on || level == "E") System.err.println("$level/$tag: $msg" + (tr?.let { "\n" + it.stackTraceToString() } ?: ""))
        return 0
    }
    @JvmStatic fun v(tag: String?, msg: String?) = out("V", tag, msg)
    @JvmStatic fun d(tag: String?, msg: String?) = out("D", tag, msg)
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?) = out("D", tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String?) = out("I", tag, msg)
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?) = out("I", tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String?) = out("W", tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?) = out("W", tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?) = out("W", tag, null, tr)
    @JvmStatic fun e(tag: String?, msg: String?) = out("E", tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?) = out("E", tag, msg, tr)
    @JvmStatic fun getStackTraceString(tr: Throwable?) = tr?.stackTraceToString().orEmpty()
}

open class LruCache<K : Any, V : Any>(private var max: Int) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var used = 0
    protected open fun sizeOf(key: K, value: V): Int = 1
    protected open fun entryRemoved(evicted: Boolean, key: K, oldValue: V, newValue: V?) {}
    @Synchronized fun get(key: K): V? = map[key]
    @Synchronized fun put(key: K, value: V): V? {
        used += sizeOf(key, value)
        val old = map.put(key, value)
        if (old != null) { used -= sizeOf(key, old); entryRemoved(false, key, old, value) }
        trimToSize(max)
        return old
    }
    @Synchronized fun remove(key: K): V? = map.remove(key)?.also { used -= sizeOf(key, it); entryRemoved(false, key, it, null) }
    @Synchronized fun evictAll() = trimToSize(-1)
    @Synchronized fun size() = used
    @Synchronized fun maxSize() = max
    @Synchronized fun resize(size: Int) { max = size; trimToSize(size) }
    @Synchronized fun snapshot(): Map<K, V> = LinkedHashMap(map)
    @Synchronized fun trimToSize(size: Int) {
        while (used > size && map.isNotEmpty()) {
            val e = map.entries.iterator().next()
            map.remove(e.key); used -= sizeOf(e.key, e.value); entryRemoved(true, e.key, e.value, null)
        }
    }
}

data class Size(val width: Int, val height: Int)
