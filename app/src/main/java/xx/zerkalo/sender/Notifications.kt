package xx.zerkalo.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat
import xx.zerkalo.MainActivity
import xx.zerkalo.R

private const val CHANNEL_ID = "mirroring"

internal const val NOTIFICATION_ID = 1

/** The notification of a service working for a receiver: opens the app, with a Stop button sending [stopAction]. */
internal fun senderNotification(service: Service, title: String, stopAction: String): Notification {
    val manager = service.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, service.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
    )
    val open = PendingIntent.getActivity(
        service,
        0,
        Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val stop = PendingIntent.getService(
        service,
        1,
        Intent(service, service.javaClass).setAction(stopAction),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Builder(service, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentIntent(open)
        .setOngoing(true)
        .addAction(0, service.getString(R.string.stop), stop)
        .build()
}
