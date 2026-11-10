package xx.zerkalo.net

import xx.zerkalo.CONNECT_TIMEOUT_MS
import xx.zerkalo.HANDSHAKE_TIMEOUT_MS
import xx.zerkalo.PING_INTERVAL_MS
import xx.zerkalo.SESSION_TIMEOUT_MS
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** The keys of the devices paired with this one, by the peer's id. */
interface TokenStore {
    fun token(peerId: ByteArray): ByteArray?

    fun save(peerId: ByteArray, token: ByteArray, name: String, host: String)
}

/** One TCP control connection. Every call may block, so none belongs on the main thread except [disconnect]. */
class ControlChannel(private val socket: Socket) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = socket.getOutputStream()
    private val closed = AtomicBoolean(false)

    init {
        socket.tcpNoDelay = true
    }

    val remoteHost: String
        get() = socket.inetAddress?.hostAddress.orEmpty()

    fun setReadTimeout(millis: Int) {
        socket.soTimeout = millis
    }

    fun send(message: ControlMessage) = synchronized(output) { writeControl(output, message) }

    fun receive(): ControlMessage = readControl(input)

    fun close() {
        socket.close()
    }

    /** Says goodbye and closes, off the calling thread. */
    fun disconnect() {
        thread(isDaemon = true, name = "control-disconnect") {
            try {
                send(ControlMessage.Bye)
            } catch (ignored: IOException) {
                // The peer is gone already; closing is all that is left.
            }
            close()
        }
    }

    /**
     * Keeps the connection alive until either side ends it or the peer goes silent.
     * [onMessage] gets everything except PING and BYE. [onClosed] runs once, at the end.
     */
    fun startSession(onMessage: (ControlMessage) -> Unit, onClosed: () -> Unit) {
        val pinger = thread(isDaemon = true, name = "control-ping") {
            try {
                while (true) {
                    Thread.sleep(PING_INTERVAL_MS)
                    send(ControlMessage.Ping)
                }
            } catch (ignored: InterruptedException) {
                // The session is over.
            } catch (ignored: IOException) {
                // Closing wakes the reader, which ends the session.
                close()
            }
        }
        thread(isDaemon = true, name = "control-read") {
            try {
                setReadTimeout(SESSION_TIMEOUT_MS)
                while (true) {
                    when (val message = receive()) {
                        ControlMessage.Ping -> Unit
                        ControlMessage.Bye -> break
                        else -> onMessage(message)
                    }
                }
            } catch (ignored: IOException) {
                // A timeout, a reset or an invalid message: the session is over either way.
            } finally {
                pinger.interrupt()
                close()
                if (closed.compareAndSet(false, true)) onClosed()
            }
        }
    }
}

// ---------- Receiver side ----------

/**
 * Runs the receiver's half of the handshake on an open connection. Returns the
 * sender's name once it is let in, or null after refusing it.
 */
internal fun acceptHandshake(
    channel: ControlChannel,
    receiverId: ByteArray,
    codecs: Int,
    guard: PinGuard,
    tokens: TokenStore,
    isBusy: () -> Boolean,
): String? {
    val hello = try {
        channel.receive() as? ControlMessage.Hello ?: return null
    } catch (ignored: UnsupportedVersionException) {
        channel.send(ControlMessage.Error(ErrorCode.VERSION))
        return null
    }
    fun refuse(code: ErrorCode): String? {
        channel.send(ControlMessage.Error(code))
        return null
    }
    if (isBusy()) return refuse(ErrorCode.BUSY)
    val known = tokens.token(hello.senderId)
    if (hello.mode == AuthMode.TOKEN && known == null) return refuse(ErrorCode.UNKNOWN_TOKEN)

    val nonceR = newNonce()
    channel.send(ControlMessage.Challenge(receiverId, nonceR))
    val auth = channel.receive() as? ControlMessage.Auth ?: return null

    when (hello.mode) {
        AuthMode.PIN -> {
            if (guard.isLocked()) return refuse(ErrorCode.BAD_PIN)
            val key = pairKey(guard.pin, hello.nonceS, nonceR)
            if (!verifyProof(key, hello.nonceS, nonceR, auth.proof)) {
                guard.onFailure()
                return refuse(ErrorCode.BAD_PIN)
            }
            guard.onSuccess()
            tokens.save(hello.senderId, key, hello.name, channel.remoteHost)
        }
        AuthMode.TOKEN -> {
            val key = known ?: return refuse(ErrorCode.UNKNOWN_TOKEN)
            if (!verifyProof(key, hello.nonceS, nonceR, auth.proof)) return refuse(ErrorCode.UNKNOWN_TOKEN)
        }
    }
    channel.send(ControlMessage.AuthOk(codecs))
    return hello.name
}

/** Listens for senders and holds at most one session. Handshakes run one at a time, each with a deadline. */
class ReceiverServer(
    private val port: Int,
    private val receiverId: ByteArray,
    private val readCodecs: () -> Int,
    private val guard: PinGuard,
    private val tokens: TokenStore,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected(senderName: String)

        fun onDisconnected()
    }

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var active: ControlChannel? = null

    @Volatile
    private var stopped = false

    /** The port actually listened on, once [start] has returned. */
    val boundPort: Int
        get() = server?.localPort ?: 0

    /** Binds the port, then accepts in the background. Blocking, and throws if the port is taken. */
    fun start() {
        val socket = ServerSocket(port)
        server = socket
        if (stopped) {
            socket.close()
            return
        }
        // Asked here and not earlier: listing a device's codecs can take a while.
        val codecs = readCodecs()
        thread(isDaemon = true, name = "control-accept") { acceptLoop(socket, codecs) }
    }

    fun stop() {
        stopped = true
        server?.close()
        active?.disconnect()
    }

    private fun acceptLoop(socket: ServerSocket, codecs: Int) {
        while (!stopped) {
            val client = try {
                socket.accept()
            } catch (ignored: IOException) {
                return
            }
            handle(client, codecs)
        }
    }

    private fun handle(client: Socket, codecs: Int) {
        try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val channel = ControlChannel(client)
            val name = acceptHandshake(channel, receiverId, codecs, guard, tokens) { active != null }
            if (name == null) {
                channel.close()
                return
            }
            active = channel
            listener.onConnected(name)
            channel.startSession(onMessage = {}, onClosed = {
                active = null
                listener.onDisconnected()
            })
            // stop() may have read active just before it was set.
            if (stopped) channel.disconnect()
        } catch (ignored: IOException) {
            client.close()
        }
    }
}

// ---------- Sender side ----------

/** What the sender proves itself with. */
sealed interface Credential {
    class Pin(val pin: String) : Credential

    class Token(val token: ByteArray) : Credential
}

sealed interface ConnectResult {
    /** [key] is what to keep as the token for [receiverId]. */
    class Connected(val channel: ControlChannel, val receiverId: ByteArray, val codecs: Int, val key: ByteArray) :
        ConnectResult

    /** The receiver does not recognise this sender's token. */
    data object NeedPin : ConnectResult

    data object BadPin : ConnectResult

    data object Busy : ConnectResult

    data object VersionMismatch : ConnectResult

    data object Unreachable : ConnectResult
}

private fun refusal(code: ErrorCode): ConnectResult = when (code) {
    ErrorCode.BUSY -> ConnectResult.Busy
    ErrorCode.BAD_PIN -> ConnectResult.BadPin
    ErrorCode.UNKNOWN_TOKEN -> ConnectResult.NeedPin
    ErrorCode.VERSION -> ConnectResult.VersionMismatch
}

/** Connects and runs the sender's half of the handshake. Blocking. */
fun connectToReceiver(
    host: String,
    port: Int,
    senderId: ByteArray,
    senderName: String,
    credential: Credential,
): ConnectResult {
    val socket = Socket()
    try {
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val channel = ControlChannel(socket)
        val nonceS = newNonce()
        val mode = if (credential is Credential.Pin) AuthMode.PIN else AuthMode.TOKEN
        channel.send(ControlMessage.Hello(senderId, senderName, nonceS, mode))

        val challenge = when (val reply = channel.receive()) {
            is ControlMessage.Challenge -> reply
            is ControlMessage.Error -> return refusal(reply.code).also { socket.close() }
            else -> throw ProtocolException("Expected CHALLENGE")
        }
        val key = when (credential) {
            is Credential.Pin -> pairKey(credential.pin, nonceS, challenge.nonceR)
            is Credential.Token -> credential.token
        }
        channel.send(ControlMessage.Auth(authProof(key, nonceS, challenge.nonceR)))

        return when (val reply = channel.receive()) {
            is ControlMessage.AuthOk -> ConnectResult.Connected(channel, challenge.receiverId, reply.codecs, key)
            is ControlMessage.Error -> refusal(reply.code).also { socket.close() }
            else -> throw ProtocolException("Expected AUTH_OK")
        }
    } catch (ignored: IOException) {
        socket.close()
        return ConnectResult.Unreachable
    }
}
