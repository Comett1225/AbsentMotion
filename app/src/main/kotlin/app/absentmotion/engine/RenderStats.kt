package app.absentmotion.engine

object RenderStats {
    @Volatile var recording = false
    private val times = ArrayList<Double>()
    private val shown = HashSet<Int>()

    @Synchronized fun add(ms: Double, timeMs: Int) {
        if (!recording) return
        times += ms
        shown += timeMs
    }

    @Synchronized fun take(): Pair<List<Double>, Int> = (times.toList() to shown.size).also { times.clear(); shown.clear() }
}
