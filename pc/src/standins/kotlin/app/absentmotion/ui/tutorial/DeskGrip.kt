package app.absentmotion.ui.tutorial

internal class GripWatch(private val publish: (Boolean) -> Unit) {
    var held: Boolean = false
        private set

    fun touch(action: Int) {
        when (action and 0xff) {
            0, 5, 2 -> set(true)
            1, 3 -> set(false)
        }
    }

    fun interrupt() = set(false)

    private fun set(value: Boolean) {
        if (held == value) return
        held = value
        publish(value)
    }
}

internal class DeskWindowOwner(val window: Any?)

internal class GripLink private constructor() {
    fun release() {}
    companion object {
        fun attach(window: Any, watch: GripWatch): GripLink? = null
        fun activityOf(context: Any?): DeskWindowOwner? = null
    }
}
