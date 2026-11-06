package app.absentmotion.audio

class LiveMix {
    @Volatile var masterLeft = 0f
        private set
    @Volatile var masterRight = 0f
        private set

    val master: Float get() = maxOf(masterLeft, masterRight)

    fun master(left: Float, right: Float) {
        masterLeft = left
        masterRight = right
    }
}
