package xx.zerkalo.sender

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import xx.zerkalo.MainActivity
import xx.zerkalo.R
import xx.zerkalo.settings.localizedContext
import xx.zerkalo.video.acquireLowLatencyWifi

private const val CHANNEL_ID = "mirroring"
private const val NOTIFICATION_ID = 1
private const val ACTION_STOP = "xx.zerkalo.STOP"
private const val EXTRA_RESULT_CODE = "result_code"
private const val EXTRA_RESULT_DATA = "result_data"

/** Keeps the capture alive while the user is in other apps. */
class SenderService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = SenderSession.disconnect()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SenderSession.disconnect()
            return START_NOT_STICKY
        }
        // First of all: a service started in the foreground has only seconds to say so.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

        val channel = SenderSession.channel
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        if (encoder != null) return START_NOT_STICKY
        if (channel == null || data == null || resultCode != Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val captured = manager.getMediaProjection(resultCode, data)
        if (captured == null) {
            SenderSession.fail(R.string.error_encoder)
            stopSelf()
            return START_NOT_STICKY
        }
        projection = captured
        // Android 14 refuses a virtual display unless this is registered first.
        captured.registerCallback(projectionCallback, mainHandler)
        wifiLock = acquireLowLatencyWifi(this)

        val started = ScreenEncoder(this, captured, channel, onFailed = { SenderSession.fail(R.string.error_encoder) })
        encoder = started
        SenderSession.onReady = started::onReady
        SenderSession.onKeyframeRequest = started::requestKeyFrame
        SenderSession.onClosed = { mainHandler.post { stopSelf() } }
        started.start()
        SenderSession.markStreaming()
        // The connection may have ended before onClosed was listening.
        if (SenderSession.channel == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        SenderSession.onReady = null
        SenderSession.onKeyframeRequest = null
        SenderSession.onClosed = null
        encoder?.stop()
        encoder = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        wifiLock?.release()
        wifiLock = null
        // Stopped from outside (by the system) with the connection still up.
        SenderSession.disconnect()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SenderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.mirroring))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    companion object {
        /** Starts mirroring with the result of the system's screen capture dialog. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, SenderService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
