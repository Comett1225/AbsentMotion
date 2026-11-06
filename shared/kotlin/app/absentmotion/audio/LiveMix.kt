package app.absentmotion.audio

class LiveMix(private val capacity: Int = 64) {
    private val ids = LongArray(capacity)
    private val lefts = FloatArray(capacity)
    private val rights = FloatArray(capacity)
    private val channels = IntArray(capacity)
    private val blocks = IntArray(capacity)
    @Volatile private var used = 0
    @Volatile private var block = 0

    @Volatile var masterLeft = 0f
        private set
    @Volatile var masterRight = 0f
        private set

    val master: Float get() = maxOf(masterLeft, masterRight)

    @Volatile var solo = LongArray(0)

    fun master(left: Float, right: Float) {
        masterLeft = left
        masterRight = right
    }

    fun left(id: Long): Float {
        val i = fresh(id)
        return if (i < 0) 0f else lefts[i]
    }

    fun right(id: Long): Float {
        val i = fresh(id)
        return if (i < 0) 0f else rights[i]
    }

    fun level(id: Long): Float = maxOf(left(id), right(id))

    fun mono(id: Long): Boolean {
        val i = find(id)
        return i >= 0 && channels[i] == 1
    }

    fun put(id: Long, left: Float, right: Float = left, channels: Int = 0) {
        var i = find(id)
        if (i < 0) {
            val n = used
            if (n < capacity) {
                set(n, id, left, right, channels)
                used = n + 1
                return
            }
            for (k in 0 until n) if (block - blocks[k] > 1) { i = k; break }
            if (i < 0) return
            this.channels[i] = 0
        }
        set(i, id, left, right, channels)
    }

    fun next() { block++ }

    fun silenced(id: Long): Boolean {
        val s = solo
        return s.isNotEmpty() && id !in s
    }

    private fun set(i: Int, id: Long, left: Float, right: Float, channels: Int) {
        ids[i] = id
        lefts[i] = left
        rights[i] = right
        if (channels > 0) this.channels[i] = channels
        blocks[i] = block
    }

    private fun find(id: Long): Int {
        val n = used
        for (i in 0 until n) if (ids[i] == id) return i
        return -1
    }

    private fun fresh(id: Long): Int {
        val i = find(id)
        return if (i >= 0 && block - blocks[i] <= 1) i else -1
    }
}
