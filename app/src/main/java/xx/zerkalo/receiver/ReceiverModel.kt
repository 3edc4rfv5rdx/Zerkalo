package xx.zerkalo.receiver

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xx.zerkalo.CONTROL_PORT
import xx.zerkalo.DISCOVERY_PORT
import xx.zerkalo.net.Announce
import xx.zerkalo.net.AnnounceSender
import xx.zerkalo.net.PinGuard
import xx.zerkalo.net.ReceiverServer
import xx.zerkalo.net.localIpv4Addresses
import xx.zerkalo.settings.AppSettings
import xx.zerkalo.settings.PairedDevices
import xx.zerkalo.video.hardwareCodecMask
import java.io.IOException
import kotlin.concurrent.thread

/** [connectedTo] is the sender's name during a session. [portTaken]: another app holds the control port. */
data class ReceiverState(
    val connectedTo: String? = null,
    val portTaken: Boolean = false,
    val addresses: List<String> = emptyList(),
)

/** The receiver's network side, running while its screen is on show. */
class ReceiverModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ReceiverState())
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private val _pin = MutableStateFlow("")
    val pin: StateFlow<String> = _pin.asStateFlow()

    // Kept across stop and start, so a guesser cannot clear the lockout by reopening the screen.
    private val guard = PinGuard(now = SystemClock::elapsedRealtime, onChanged = { _pin.value = it })
        .also { _pin.value = it.pin }

    private var server: ReceiverServer? = null
    private var announcer: AnnounceSender? = null

    fun start() {
        if (server != null) return
        val context = getApplication<Application>()
        val id = AppSettings.deviceId(context)
        val listener = object : ReceiverServer.Listener {
            override fun onConnected(senderName: String) = update { copy(connectedTo = senderName) }

            override fun onDisconnected() = update { copy(connectedTo = null) }
        }
        val started = ReceiverServer(CONTROL_PORT, id, { hardwareCodecMask(encoder = false) }, guard, PairedDevices, listener)
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
