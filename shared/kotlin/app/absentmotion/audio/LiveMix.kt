package app.absentmotion.audio

class LiveMix(private val capacity: Int = 64) {
    private val ids = LongArray(capacity)
    private val peaks = FloatArray(capacity)
    private val blocks = IntArray(capacity)
    @Volatile private var used = 0
    @Volatile private var block = 0

    @Volatile var master = 0f

    @Volatile var solo = LongArray(0)

    fun level(id: Long): Float {
        val n = used
        val now = block
        for (i in 0 until n) if (ids[i] == id) return if (now - blocks[i] <= 1) peaks[i] else 0f
        return 0f
    }

    fun put(id: Long, peak: Float) {
        val n = used
        for (i in 0 until n) if (ids[i] == id) { peaks[i] = peak; blocks[i] = block; return }
        if (n == capacity) return
        ids[n] = id
        peaks[n] = peak
        blocks[n] = block
        used = n + 1
    }

    fun next() { block++ }

    fun silenced(id: Long): Boolean {
        val s = solo
        return s.isNotEmpty() && id !in s
    }
}
