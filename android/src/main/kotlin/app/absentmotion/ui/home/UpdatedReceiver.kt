package app.absentmotion.ui.home

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.absentmotion.R
import app.absentmotion.platform.versionName

class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        manager.createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName(context.getString(R.string.update_notify_channel)).build())
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_notify_done, versionName(context)))
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setTimeoutAfter(TIMEOUT_MS)
            .setAutoCancel(true)
            .build()
        try { manager.notify(ID, notification) } catch (e: SecurityException) { return }
    }

    private companion object {
        const val CHANNEL = "updates"
        const val ID = 4103
        const val TIMEOUT_MS = 60L * 60 * 1000
    }
}
