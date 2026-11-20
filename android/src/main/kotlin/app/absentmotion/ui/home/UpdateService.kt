package app.absentmotion.ui.home

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.absentmotion.MainActivity
import app.absentmotion.R
import app.absentmotion.i18n.inAppLanguage
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

internal fun updatePercent(done: Long, total: Long): Int = if (total > 0) (done.coerceIn(0, total) * 100.0 / total).toInt() else 0

internal fun updateMegabytes(bytes: Long, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(bytes / (1024.0 * 1024))

internal class UpdateService : Service() {
    private val scope = MainScope()
    private var watching: Job? = null
    private var downloadId = 0
    private var lastStartId = 0
    private var lock: PowerManager.WakeLock? = null

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(newBase.inAppLanguage())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val id = intent?.getIntExtra(DownloadId, 0) ?: 0
        if (intent?.action == ActionCancel) {
            Updater.cancelDownload(applicationContext, id)
            if (watching?.isActive != true) finish()
            return START_NOT_STICKY
        }
        val version = intent?.getStringExtra(Version)
        if (intent == null || version == null) {
            if (watching?.isActive != true) finish()
            return START_NOT_STICKY
        }
        if (id == downloadId && watching?.isActive == true) return START_NOT_STICKY
        val total = intent.getLongExtra(Total, 0)
        try {
            NotificationManagerCompat.from(this).createNotificationChannel(NotificationChannelCompat.Builder(Channel, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(getString(R.string.update_channel)).build())
            val notification = progressNotification(id, version, 0, total)
            if (Build.VERSION.SDK_INT >= 29) startForeground(ProgressId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ProgressId, notification)
        } catch (e: Exception) {
            android.util.Log.w("AMUpdate", "update service could not enter the foreground", e)
            if (Updater.interruptDownload(id)) postResult(id)
            if (watching?.isActive != true) finish()
            return START_NOT_STICKY
        }
        val job = Updater.download(applicationContext, id)
        if (job == null) {
            if (watching?.isActive != true) finish()
            return START_NOT_STICKY
        }
        watching?.cancel()
        downloadId = id
        if (lock == null) lock = runCatching {
            getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "absentmotion:update").apply { setReferenceCounted(false); acquire(WakeMs) }
        }.getOrNull()
        watching = scope.launch {
            val progress = launch {
                var shown = 0L
                while (isActive) {
                    delay(TickMs)
                    val done = when (val step = Updater.downloadStep(id)) {
                        is UpdateStep.Downloading -> step.done
                        is UpdateStep.Checking -> total
                        else -> break
                    }
                    if (done != shown) {
                        shown = done
                        if (canNotify(this@UpdateService)) post(ProgressId, progressNotification(id, version, done, total))
                    }
                }
            }
            job.join()
            progress.cancel()
            postResult(id)
            finish()
        }
        return START_NOT_STICKY
    }

    private fun progressNotification(id: Int, version: String, done: Long, total: Long): Notification {
        val unit = getString(R.string.compress_megabytes)
        val locale = resources.configuration.locales[0]
        val text = getString(R.string.update_progress, "${updateMegabytes(done, locale)} $unit", "${updateMegabytes(total, locale)} $unit")
        return NotificationCompat.Builder(this, Channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.update_notification_downloading, version))
            .setContentText(text)
            .setProgress(100, updatePercent(done, total), false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.cancel), PendingIntent.getService(this, id,
                Intent(this, UpdateService::class.java).setAction(ActionCancel).putExtra(DownloadId, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }

    private fun postResult(id: Int) {
        if (Updater.calm()) return
        val step = Updater.downloadStep(id) ?: return
        val (title, body) = when (step) {
            is UpdateStep.NotInstalled -> getString(R.string.update_notification_ready, step.release.version) to getString(R.string.update_notification_install)
            is UpdateStep.Failed -> getString(R.string.update_stopped) to step.failure.text(this)
            else -> return
        }
        Updater.keepNotice(applicationContext, step)
        if (!canNotify(this)) return
        post(ResultId, NotificationCompat.Builder(this, Channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).setContentIntent(openApp()).setAutoCancel(true).build())
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).setAction(OpenUpdate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun post(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (e: SecurityException) {
            android.util.Log.w("AMUpdate", "notifications are off", e)
        }
    }

    private fun finish() {
        if (stopSelfResult(lastStartId)) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        watching?.cancel()
        if (Updater.interruptDownload(downloadId)) postResult(downloadId)
    }

    override fun onDestroy() {
        scope.cancel()
        if (Updater.interruptDownload(downloadId)) postResult(downloadId)
        lock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        const val OpenUpdate = "app.absentmotion.update.OPEN"
        private const val ActionCancel = "app.absentmotion.update.CANCEL"
        private const val Channel = "update_download"
        private const val DownloadId = "downloadId"
        private const val Version = "version"
        private const val Total = "total"
        private const val ProgressId = 4104
        private const val ResultId = 4105
        private const val TickMs = 500L
        private const val WakeMs = 6L * 60 * 60 * 1000

        fun start(context: Context, id: Int, step: UpdateStep.Downloading) {
            ContextCompat.startForegroundService(context, Intent(context, UpdateService::class.java)
                .putExtra(DownloadId, id).putExtra(Version, step.release.version).putExtra(Total, step.total))
        }

        fun clearResult(context: Context) = NotificationManagerCompat.from(context).cancel(ResultId)

        fun canNotify(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            return manager.areNotificationsEnabled() && manager.getNotificationChannel(Channel)?.importance != NotificationManagerCompat.IMPORTANCE_NONE
        }
    }
}
