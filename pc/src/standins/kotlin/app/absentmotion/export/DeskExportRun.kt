package app.absentmotion.export

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import app.absentmotion.R
import app.absentmotion.engine.EngineThread
import app.absentmotion.platform.Uri
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ExportFinish(val ok: Boolean?, val files: List<Uri>, val where: String, val title: Int)

object DeskExports {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var fraction by mutableFloatStateOf(0f)
    var running by mutableStateOf(false)
        internal set
    var title by mutableStateOf(R.string.export_running)
        internal set
    var finish by mutableStateOf<ExportFinish?>(null)
        internal set
    private var own: EngineThread? = null
    private var saved: ExportResult? = null

    @Synchronized fun engine(): EngineThread = own ?: EngineThread(background = Am.PreviewBg.toArgb(), context = android.content.Desk.context).also { own = it }

    @Synchronized internal fun release() {
        own?.close()
        own = null
    }

    internal fun started(@StringRes name: Int) {
        title = name
        fraction = 0f
        saved = null
        finish = null
        running = true
    }

    internal fun saved(result: ExportResult) { saved = result }

    internal fun ended(outcome: Boolean?) {
        running = false
        release()
        val result = saved
        finish = ExportFinish(outcome, result?.uris.orEmpty(), result?.where.orEmpty(), title)
    }

    fun clearFinish() { finish = null }
}

object ExportRun {
    var progress: Float
        get() = DeskExports.fraction
        set(value) { DeskExports.fraction = value }
    @Volatile var visible = true
    @Volatile internal var title = R.string.export_running
    @Volatile var running = false
        internal set
    @Volatile internal var outcome: Boolean? = null
    @Volatile private var stop: (() -> Unit)? = null
    @Volatile private var stopped = false

    fun begin(context: Context, @StringRes title: Int, stop: () -> Unit) {
        this.title = title
        this.stop = stop
        outcome = null
        stopped = false
        running = true
        DeskExports.started(title)
    }

    fun end(done: Boolean?) {
        outcome = done ?: if (stopped || visible) null else false
        stop = null
        running = false
        DeskExports.ended(outcome)
    }

    fun cancel() {
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
