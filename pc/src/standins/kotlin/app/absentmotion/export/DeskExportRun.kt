package app.absentmotion.export

import android.content.Context
import androidx.annotation.StringRes
import app.absentmotion.R

object ExportRun {
    @Volatile var progress = 0f
    @Volatile var visible = true
    @Volatile internal var title = R.string.export_running
    @Volatile internal var running = false
    @Volatile internal var outcome: Boolean? = null
    @Volatile private var stop: (() -> Unit)? = null
    @Volatile private var stopped = false

    fun begin(context: Context, @StringRes title: Int, stop: () -> Unit) {
        this.title = title
        this.stop = stop
        progress = 0f
        outcome = null
        stopped = false
        running = true
    }

    fun end(done: Boolean?) {
        outcome = done ?: if (stopped || visible) null else false
        stop = null
        running = false
    }

    internal fun cancel() {
        stopped = true
        stop?.invoke()
    }
}

internal class NotifyAsk(
    private val sdk: Int, private val granted: () -> Boolean, private val asked: () -> Boolean, private val remember: () -> Unit, private val ask: () -> Unit,
) {
    fun ready(retry: () -> Unit): Boolean = true
    fun answered() {}
}
