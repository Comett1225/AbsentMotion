package app.absentmotion.export

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.absentmotion.R
import app.absentmotion.i18n.inAppLanguage

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
        try {
            ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
        } catch (e: Exception) {
            android.util.Log.w("AMExport", "the export runs without its notification", e)
        }
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
    private var again: (() -> Unit)? = null

    fun ready(retry: () -> Unit): Boolean {
        if (sdk < 33 || granted() || asked()) return true
        remember()
        again = retry
        ask()
        return false
    }

    fun answered() {
        again?.let { again = null; it() }
    }
}

class ExportService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { update() }
    private var shown = -1

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(newBase.inAppLanguage())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionCancel) {
            ExportRun.cancel()
            if (!ExportRun.running) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val channel = NotificationChannelCompat.Builder(Channel, NotificationManagerCompat.IMPORTANCE_LOW).setName(getString(R.string.export_notify_channel)).build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
        shown = percent()
        try {
            val notification = progressNotification(shown)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NotificationId, notification, serviceType()) else startForeground(NotificationId, notification)
        } catch (e: Exception) {
            android.util.Log.w("AMExport", "the export runs without its notification", e)
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TickMs)
        return START_NOT_STICKY
    }

    private fun percent() = (ExportRun.progress * 100).toInt().coerceIn(0, 100)

    private fun update() {
        if (!ExportRun.running) return finish()
        val now = percent()
        if (now != shown) {
            shown = now
            post(progressNotification(now))
        }
        handler.postDelayed(tick, TickMs)
    }

    private fun finish() {
        val outcome = ExportRun.outcome
        if (outcome == null || ExportRun.visible) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            post(NotificationCompat.Builder(this, Channel).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(if (outcome) R.string.export_notify_done else R.string.export_notify_failed))
                .setContentIntent(openApp()).setAutoCancel(true).build())
        }
        stopSelf()
    }

    private fun post(notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NotificationId, notification)
        } catch (e: SecurityException) {
            android.util.Log.w("AMExport", "notifications are off", e)
        }
    }

    private fun progressNotification(percent: Int) = NotificationCompat.Builder(this, Channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(ExportRun.title))
        .setContentText(java.text.NumberFormat.getPercentInstance().format(percent / 100.0))
        .setProgress(100, percent, false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openApp())
        .addAction(0, getString(R.string.cancel),
            PendingIntent.getService(this, 1, Intent(this, ExportService::class.java).setAction(ActionCancel), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    private fun openApp(): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
        PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (ExportRun.running) {
            ExportRun.cancel()
            ExportRun.end(false)
        }
        handler.removeCallbacks(tick)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private companion object {
        const val Channel = "export"
        const val NotificationId = 7101
        const val ActionCancel = "app.absentmotion.export.CANCEL"
        const val TickMs = 500L

        fun serviceType(): Int =
            if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }
}
