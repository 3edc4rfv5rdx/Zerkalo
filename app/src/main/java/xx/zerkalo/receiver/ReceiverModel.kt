package xx.zerkalo.receiver

import android.app.Application
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xx.zerkalo.CODEC_AUDIO_AAC
import xx.zerkalo.CONTROL_PORT
import xx.zerkalo.DISCOVERY_PORT
import xx.zerkalo.net.Announce
import xx.zerkalo.net.AnnounceSender
import xx.zerkalo.net.ControlMessage
import xx.zerkalo.net.PinGuard
import xx.zerkalo.net.ReceiverServer
import xx.zerkalo.net.codecMaskOf
import xx.zerkalo.net.fileUrl
import xx.zerkalo.net.localIpv4Addresses
import xx.zerkalo.settings.AppSettings
import xx.zerkalo.settings.PairedDevices
import xx.zerkalo.video.acquireLowLatencyWifi
import xx.zerkalo.video.hardwareCodecMask
import java.io.IOException
import kotlin.concurrent.thread

/**
 * [connectedTo] is the sender's name during a session. [portTaken]: another app holds
 * the control port. [videoWidth] and [videoHeight] are zero until the stream is described.
 * [noPlayer]: nothing on this device could open the last file.
 */
data class ReceiverState(
    val connectedTo: String? = null,
    val portTaken: Boolean = false,
    val addresses: List<String> = emptyList(),
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val noPlayer: Boolean = false,
)

/** A file the sender wants played, not yet handed to a player. */
data class PlayRequest(val url: String, val mime: String)

/** The receiver's network side, running while its screen is on show. */
class ReceiverModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ReceiverState())
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private val _playRequest = MutableStateFlow<PlayRequest?>(null)
    val playRequest: StateFlow<PlayRequest?> = _playRequest.asStateFlow()

    private val _pin = MutableStateFlow("")
    val pin: StateFlow<String> = _pin.asStateFlow()

    // Kept across stop and start, so a guesser cannot clear the lockout by reopening the screen.
    private val guard = PinGuard(now = SystemClock::elapsedRealtime, onChanged = { _pin.value = it })
        .also { _pin.value = it.pin }

    private var server: ReceiverServer? = null
    private var announcer: AnnounceSender? = null

    @Volatile
    private var session: ReceiverSession? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var senderHost = ""

    private var wifiLock: WifiManager.WifiLock? = null

    fun start() {
        if (server != null) return
        val context = getApplication<Application>()
        val id = AppSettings.deviceId(context)
        lateinit var started: ReceiverServer
        val listener = object : ReceiverServer.Listener {
            override fun onConnected(senderName: String, host: String) = beginSession(started, senderName, host)

            override fun onDisconnected() = endSessionState()

            override fun onMessage(message: ControlMessage) {
                when (message) {
                    is ControlMessage.Config -> session?.onConfig(message)
                    is ControlMessage.AudioConfig -> session?.onAudioConfig(message)
                    is ControlMessage.PlayFile ->
                        _playRequest.value = PlayRequest(fileUrl(senderHost, message.port, message.token, message.name), message.mime)
                    else -> Unit
                }
            }
        }
        val codecs = { hardwareCodecMask(encoder = false) or codecMaskOf(CODEC_AUDIO_AAC) }
        started = ReceiverServer(CONTROL_PORT, id, codecs, guard, PairedDevices.senders, listener)
        server = started
        update { copy(portTaken = false) }
        thread(isDaemon = true, name = "receiver-start") {
            try {
                started.start()
                update { copy(addresses = localIpv4Addresses()) }
            } catch (ignored: IOException) {
                update { copy(portTaken = true) }
            }
        }
        announcer = AnnounceSender(Announce(id, CONTROL_PORT, AppSettings.deviceName.value).encode(), DISCOVERY_PORT)
            .also { it.start() }
    }

    private fun beginSession(owner: ReceiverServer, senderName: String, host: String) {
        senderHost = host
        update { copy(noPlayer = false) }
        val created = ReceiverSession(
            senderHost = host,
            sendReady = { configId -> owner.send(ControlMessage.Ready(configId)) },
            sendKeyframeRequest = { owner.send(ControlMessage.KeyframeRequest) },
            onVideoSize = { width, height -> update { copy(videoWidth = width, videoHeight = height) } },
            audioAutoSync = AppSettings.audioAutoSync.value,
            audioOffsetMs = AppSettings.audioOffsetMs.value,
        )
        created.setSurface(surface)
        session = created
        created.start()
        synchronized(this) { wifiLock = acquireLowLatencyWifi(getApplication()) }
        // Last: this is what puts the surface on screen, and the session must be there for it.
        update { copy(connectedTo = senderName) }
    }

    private fun endSessionState() {
        session?.stop()
        session = null
        synchronized(this) {
            wifiLock?.release()
            wifiLock = null
        }
        update { copy(connectedTo = null, videoWidth = 0, videoHeight = 0) }
    }

    /** Called with the surface as it comes and goes; null blocks until the decoder has let go. */
    fun setSurface(newSurface: Surface?) {
        surface = newSurface
        session?.setSurface(newSurface)
    }

    /** The current request has been handed over, or [opened] says that nothing could take it. */
    fun onPlayRequestDone(opened: Boolean) {
        _playRequest.value = null
        if (!opened) update { copy(noPlayer = true) }
    }

    /** Ends the current session; the receiver goes back to waiting. */
    fun endSession() {
        server?.endSession()
    }

    fun stop() {
        server?.stop()
        server = null
        announcer?.stop()
        announcer = null
    }

    override fun onCleared() = stop()

    private fun update(change: ReceiverState.() -> ReceiverState) {
        synchronized(_state) { _state.value = _state.value.change() }
    }
}
