package xx.zerkalo.sender

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import xx.zerkalo.CODEC_AUDIO_AAC
import xx.zerkalo.R
import xx.zerkalo.net.codecMaskHas
import xx.zerkalo.settings.AppSettings
import xx.zerkalo.settings.localizedContext
import xx.zerkalo.video.acquireLowLatencyWifi

private const val ACTION_STOP = "xx.zerkalo.STOP"
private const val EXTRA_RESULT_CODE = "result_code"
private const val EXTRA_RESULT_DATA = "result_data"

/** Keeps the capture alive while the user is in other apps. */
class SenderService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var screenLock: PowerManager.WakeLock? = null
    private var stopAudio: (() -> Unit)? = null

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
        val canCaptureSound = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        // Declaring this without the permission granted would crash the service.
        if (canCaptureSound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, senderNotification(this, getString(R.string.mirroring), ACTION_STOP), type)

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
        screenLock = keepScreenDimmed()
        // Before either stream starts, so that picture and sound share one time origin.
        val baseUs = System.nanoTime() / 1000

        val started = ScreenEncoder(
            this,
            captured,
            channel,
            SenderSession.receiverCodecs,
            AppSettings.streamOptions(),
            baseUs,
            onFailed = { SenderSession.fail(R.string.error_encoder) },
        )
        encoder = started
        SenderSession.onReady = started::onReady
        SenderSession.onKeyframeRequest = started::requestKeyFrame
        SenderSession.onClosed = { mainHandler.post { stopSelf() } }
        started.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canCaptureSound &&
            codecMaskHas(SenderSession.receiverCodecs, CODEC_AUDIO_AAC)
        ) {
            val capture = AudioCapture(captured, channel, baseUs)
            capture.start()
            stopAudio = capture::stop
        }
        SenderSession.markStreaming()
        // The connection may have ended before onClosed was listening.
        if (SenderSession.channel == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        SenderSession.onReady = null
        SenderSession.onKeyframeRequest = null
        SenderSession.onClosed = null
        stopAudio?.invoke()
        stopAudio = null
        encoder?.stop()
        encoder = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        wifiLock?.release()
        wifiLock = null
        screenLock?.release()
        screenLock = null
        // Stopped from outside (by the system) with the connection still up.
        SenderSession.disconnect()
        super.onDestroy()
    }

    /** Capture ends when the screen goes off, so the timeout must not get there; dimming is fine. */
    @SuppressLint("WakelockTimeout") // Held for the session and released in onDestroy.
    @Suppress("DEPRECATION") // The replacement, FLAG_KEEP_SCREEN_ON, needs a window, and this runs behind other apps.
    private fun keepScreenDimmed(): PowerManager.WakeLock =
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "zerkalo:mirroring").also {
            it.setReferenceCounted(false)
            it.acquire()
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
