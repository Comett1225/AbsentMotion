package app.absentmotion.library

import java.io.File
import java.time.Instant
import java.time.ZoneId

class ProjectVersions(private val root: File, private val now: () -> Long = System::currentTimeMillis, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    data class Version(val file: File, val time: Long) {
        val thumbnail: File get() = File(file.parentFile, "$time.png")
    }

    fun dir(id: String) = root.child(id)

    fun list(id: String): List<Version> = dir(id).listFiles { f -> f.extension == "xml" }.orEmpty()
        .mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { Version(f, it) } }
        .sortedByDescending { it.time }

    private val written = HashMap<String, Long>()

    @Synchronized
    fun snapshot(id: String, xml: String, minGapMs: Long = 0): Boolean {
        if (xml.isBlank()) return false
        val newest = list(id).firstOrNull()
        val time = now()
        val last = newest?.time?.takeIf { it <= time } ?: written[id]
        if (newest != null && (last != null && time - last in 0 until minGapMs || runCatching { sameScene(newest.file.readText(), xml) }.getOrDefault(false))) return false
        var name = maxOf(time, (newest?.time ?: 0) + 1)
        while (File(dir(id), "$name.xml").exists()) name++
        writeAtomically(File(dir(id), "$name.xml"), xml)
        written[id] = time
        prune(id)
        return true
    }

    @Synchronized
    fun keepDamaged(id: String, text: String) = writeAtomically(File(dir(id), "${now()}.damaged"), text)

    fun prune(id: String) {
        val all = list(id)
        val keep = retained(all.map { it.time }, now(), zone())
        all.filter { it.time !in keep }.forEach { it.file.delete(); it.thumbnail.delete() }
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    companion object {
        const val NEWEST = 20
        const val DAYS = 7L
    }
}

internal fun retained(times: List<Long>, now: Long, zone: ZoneId, newest: Int = ProjectVersions.NEWEST, days: Long = ProjectVersions.DAYS): Set<Long> {
    fun day(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    val first = day(now).minusDays(days - 1)
    val sorted = times.sortedDescending()
    val daily = sorted.filter { !day(it).isBefore(first) }.groupBy(::day).values.map { it.first() }
    return (sorted.take(newest) + daily).toSet()
}

private val Stamp = Regex(""" modifiedTime="[^"]*"""")

internal fun sameScene(a: String, b: String): Boolean = a == b || Stamp.replaceFirst(a, "") == Stamp.replaceFirst(b, "")
