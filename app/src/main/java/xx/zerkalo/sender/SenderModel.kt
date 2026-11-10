package xx.zerkalo.sender

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xx.zerkalo.CONTROL_PORT
import xx.zerkalo.DISCOVERY_PORT
import xx.zerkalo.R
import xx.zerkalo.net.AnnounceListener
import xx.zerkalo.net.ConnectResult
import xx.zerkalo.net.ControlChannel
import xx.zerkalo.net.Credential
import xx.zerkalo.net.FoundReceiver
import xx.zerkalo.net.ReceiverList
import xx.zerkalo.net.connectToReceiver
import xx.zerkalo.settings.AppSettings
import xx.zerkalo.settings.PairedDevices
import xx.zerkalo.toHex
import kotlin.concurrent.thread

/** Where to connect. [id] is null for an address typed in by hand. */
data class ConnectTarget(val id: String?, val name: String, val host: String, val port: Int)

sealed interface SenderStatus {
    data object Idle : SenderStatus

    data class Connecting(val name: String) : SenderStatus

    /** [wrong]: the last PIN was refused. */
    data class NeedPin(val name: String, val wrong: Boolean) : SenderStatus

    data class Connected(val name: String) : SenderStatus

    data class Failed(@param:StringRes val message: Int) : SenderStatus
}

/** The sender's network side: finding receivers and holding the connection to one. */
class SenderModel(application: Application) : AndroidViewModel(application) {
    private val _found = MutableStateFlow<List<FoundReceiver>>(emptyList())
    val found: StateFlow<List<FoundReceiver>> = _found.asStateFlow()

    private val _status = MutableStateFlow<SenderStatus>(SenderStatus.Idle)
    val status: StateFlow<SenderStatus> = _status.asStateFlow()

    private var listener: AnnounceListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var pending: ConnectTarget? = null

    @Volatile
    private var channel: ControlChannel? = null

    fun startDiscovery() {
        if (listener != null) return
        val context = getApplication<Application>()
        // Without this lock some phones drop broadcast packets before the app sees them.
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("zerkalo-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        val receivers = ReceiverList()
        listener = AnnounceListener(
            port = DISCOVERY_PORT,
            onAnnounce = { announce, host ->
                val found = FoundReceiver(announce.receiverId.toHex(), announce.name, host, announce.controlPort)
                if (receivers.update(found, SystemClock.elapsedRealtime())) _found.value = receivers.snapshot()
            },
            onTick = {
                if (receivers.prune(SystemClock.elapsedRealtime())) _found.value = receivers.snapshot()
            },
        ).also { it.start() }
    }

    fun stopDiscovery() {
        listener?.stop()
        listener = null
        multicastLock?.release()
        multicastLock = null
        _found.value = emptyList()
    }

    fun connect(receiver: FoundReceiver) {
        connectTo(ConnectTarget(receiver.id, receiver.name, receiver.host, receiver.port))
    }

    fun connectByAddress(host: String) {
        connectTo(ConnectTarget(null, host, host, CONTROL_PORT))
    }

    private fun connectTo(target: ConnectTarget) {
        if (_status.value is SenderStatus.Connecting || _status.value is SenderStatus.Connected) return
        val paired = PairedDevices.find(target.id, target.host)
        if (paired == null) {
            askPin(target, wrong = false)
        } else {
            launch(target, Credential.Token(paired.token))
        }
    }

    private fun askPin(target: ConnectTarget, wrong: Boolean) {
        pending = target
        _status.value = SenderStatus.NeedPin(target.name, wrong)
    }

    fun submitPin(pin: String) {
        val target = pending ?: return
        launch(target, Credential.Pin(pin))
    }

    fun cancelPin() {
        pending = null
        _status.value = SenderStatus.Idle
    }

    fun dismissFailure() {
        _status.value = SenderStatus.Idle
    }

    private fun launch(target: ConnectTarget, credential: Credential) {
        _status.value = SenderStatus.Connecting(target.name)
        val context = getApplication<Application>()
        val senderId = AppSettings.deviceId(context)
        val senderName = AppSettings.deviceName.value
        thread(isDaemon = true, name = "sender-connect") {
            when (val result = connectToReceiver(target.host, target.port, senderId, senderName, credential)) {
                is ConnectResult.Connected -> onConnected(target, result)
                ConnectResult.NeedPin -> {
                    PairedDevices.find(target.id, target.host)?.let { PairedDevices.forget(it.id) }
                    askPin(target, wrong = false)
                }
                ConnectResult.BadPin -> askPin(target, wrong = true)
                ConnectResult.Busy -> fail(R.string.error_busy)
                ConnectResult.VersionMismatch -> fail(R.string.error_version)
                ConnectResult.Unreachable -> fail(R.string.error_unreachable)
            }
        }
    }

    private fun fail(@StringRes message: Int) {
        _status.value = SenderStatus.Failed(message)
    }

    private fun onConnected(target: ConnectTarget, result: ConnectResult.Connected) {
        PairedDevices.save(result.receiverId, result.key, target.name, target.host)
        pending = null
        val connected = result.channel
        channel = connected
        _status.value = SenderStatus.Connected(target.name)
        connected.startSession(onMessage = {}, onClosed = {
            channel = null
            _status.value = SenderStatus.Idle
        })
    }

    fun disconnect() {
        channel?.disconnect()
    }

    override fun onCleared() {
        stopDiscovery()
        disconnect()
    }
}
