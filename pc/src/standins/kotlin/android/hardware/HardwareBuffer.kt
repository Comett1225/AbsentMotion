package android.hardware

class HardwareBuffer private constructor(val width: Int, val height: Int, val format: Int, val layers: Int, val usage: Long) : AutoCloseable {
    var isClosed = false
        private set

    override fun close() { isClosed = true }

    companion object {
        const val RGBA_8888 = 1
        const val USAGE_CPU_READ_RARELY = 2L
        const val USAGE_CPU_WRITE_RARELY = 32L
        const val USAGE_GPU_SAMPLED_IMAGE = 256L
        const val USAGE_GPU_COLOR_OUTPUT = 512L
        @JvmStatic fun create(width: Int, height: Int, format: Int, layers: Int, usage: Long) = HardwareBuffer(width, height, format, layers, usage)
    }
}
