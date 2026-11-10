package xx.zerkalo.sender

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xx.zerkalo.net.ControlChannel
import xx.zerkalo.net.ControlMessage

sealed interface SenderStatus {
    data object Idle : SenderStatus

    data class Connecting(val name: String) : SenderStatus

    /** [wrong]: the last PIN was refused. */
    data class NeedPin(val name: String, val wrong: Boolean) : SenderStatus

    /** Connected, and waiting for the user to allow screen capture. */
    data class Connected(val name: String) : SenderStatus

    data class Streaming(val name: String) : SenderStatus

    data class Failed(@param:StringRes val message: Int) : SenderStatus
}

/**
 * The connection to a receiver. A process-wide object and not part of the screen's
 * model, because mirroring goes on after the user has left the app.
 */
object SenderSession {
    private val _status = MutableStateFlow<SenderStatus>(SenderStatus.Idle)
    val status: StateFlow<SenderStatus> = _status.asStateFlow()

    @Volatile
    var channel: ControlChannel? = null
        private set

    /** Told which configuration the receiver is ready for. */
    @Volatile
    var onReady: ((Int) -> Unit)? = null

    /** Told when the connection has ended. */
    @Volatile
    var onClosed: (() -> Unit)? = null

    @StringRes
    @Volatile
    private var failure: Int? = null

    fun setStatus(status: SenderStatus) {
        _status.value = status
    }

    fun attach(connected: ControlChannel, name: String) {
        channel = connected
        _status.value = SenderStatus.Connected(name)
        connected.startSession(
            onMessage = { message -> if (message is ControlMessage.Ready) onReady?.invoke(message.configId) },
            onClosed = {
                channel = null
                _status.value = failure?.let { SenderStatus.Failed(it) } ?: SenderStatus.Idle
                failure = null
                onClosed?.invoke()
            },
        )
    }

    /** Marks the session as mirroring, if it is still waiting to start. */
    fun markStreaming() {
        (_status.value as? SenderStatus.Connected)?.let { _status.value = SenderStatus.Streaming(it.name) }
    }

    fun disconnect() {
        channel?.disconnect()
    }

    /** Ends the session and shows [message] once it is over. */
    fun fail(@StringRes message: Int) {
        failure = message
        disconnect()
    }
}
