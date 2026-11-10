package xx.zerkalo.sender

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xx.zerkalo.FILE_PORT
import xx.zerkalo.FILE_TOKEN_SIZE
import xx.zerkalo.R
import xx.zerkalo.net.ControlChannel
import xx.zerkalo.net.ControlMessage
import xx.zerkalo.net.FileServer
import xx.zerkalo.net.ServedFile
import xx.zerkalo.settings.localizedContext
import xx.zerkalo.toHex
import xx.zerkalo.video.acquireLowLatencyWifi
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import kotlin.concurrent.thread

private const val ACTION_STOP = "xx.zerkalo.STOP_FILE"

/** What a player is told when the provider has no better answer, or calls a film a binary. */
private const val FALLBACK_MIME = "video/*"

class FileInfo(val name: String, val size: Long, val mime: String)

/** What can be said about the file at [uri], or null if it cannot be served. May block. */
fun describeFile(context: Context, uri: Uri): FileInfo? {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
        .orEmpty()
    val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
    if (size <= 0) return null
    val mime = resolver.getType(uri)?.takeIf { it.startsWith("video/") || it.startsWith("audio/") } ?: FALLBACK_MIME
    return FileInfo(name, size, mime)
}

/** Which file is being served. Outlives the screens, as serving goes on behind other apps. */
object FileSession {
    private val _serving = MutableStateFlow<String?>(null)
    val serving: StateFlow<String?> = _serving.asStateFlow()

    internal fun set(name: String?) {
        _serving.value = name
    }
}

/**
 * Serves a file to the receiver's player. The control connection only delivers the
 * address: the receiver leaves its screen for the player, and this goes on regardless.
 */
class FileService : Service() {
    @Volatile
    private var server: FileServer? = null

    @Volatile
    private var destroyed = false

    private var started = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // First of all: a service started in the foreground has only seconds to say so.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, senderNotification(this, getString(R.string.serving_file), ACTION_STOP), type)

        if (started) return START_NOT_STICKY
        val uri = intent?.data
        val channel = SenderSession.channel
        if (uri == null || channel == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        wifiLock = acquireLowLatencyWifi(this)
        cpuLock = keepCpuAwake()
        thread(isDaemon = true, name = "file-start") { begin(uri, channel) }
        return START_NOT_STICKY
    }

    private fun begin(uri: Uri, channel: ControlChannel) {
        try {
            val info = checkNotNull(describeFile(this, uri)) { "Nothing to serve" }
            val token = ByteArray(FILE_TOKEN_SIZE).also(SecureRandom()::nextBytes).toHex()
            val serving = FileServer(FILE_PORT, token, ServedFile(info.size, info.mime) { offset -> openAt(uri, offset) })
            serving.start()
            server = serving
            // Stopped while this was starting: onDestroy did not see the server yet.
            if (destroyed) {
                serving.stop()
                return
            }
            channel.send(ControlMessage.PlayFile(serving.boundPort, token, info.name, info.mime))
            FileSession.set(info.name)
            SenderSession.disconnect()
        } catch (e: Exception) {
            // Providers and sockets fail in many ways; each one means this file does not get sent.
            Log.w("FileService", "Could not start serving", e)
            SenderSession.fail(R.string.error_file)
            stopSelf()
        }
    }

    private fun openAt(uri: Uri, offset: Long): InputStream {
        val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("No descriptor for $uri")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        try {
            stream.channel.position(offset)
            return stream
        } catch (e: IOException) {
            stream.close()
            throw e
        }
    }

    @SuppressLint("WakelockTimeout") // Held while serving and released in onDestroy.
    private fun keepCpuAwake(): PowerManager.WakeLock =
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zerkalo:file").also {
            it.setReferenceCounted(false)
            it.acquire()
        }

    override fun onDestroy() {
        destroyed = true
        server?.stop()
        server = null
        wifiLock?.release()
        wifiLock = null
        cpuLock?.release()
        cpuLock = null
        FileSession.set(null)
        super.onDestroy()
    }

    companion object {
        fun start(context: Context, uri: Uri) {
            ContextCompat.startForegroundService(context, Intent(context, FileService::class.java).setData(uri))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FileService::class.java))
        }
    }
}
