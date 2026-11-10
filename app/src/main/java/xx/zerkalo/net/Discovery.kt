package xx.zerkalo.net

import xx.zerkalo.ANNOUNCE_INTERVAL_MS
import xx.zerkalo.MAX_DATAGRAM
import xx.zerkalo.RECEIVER_EXPIRY_MS
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import kotlin.concurrent.thread

/** A receiver heard on the network. [id] is its id in hex. */
data class FoundReceiver(val id: String, val name: String, val host: String, val port: Int)

/** The receivers heard lately. Not thread-safe: the listener's own thread owns it. */
class ReceiverList {
    private class Entry(val receiver: FoundReceiver, val lastHeard: Long)

    private val entries = HashMap<String, Entry>()

    /** Records [receiver]; true if the list a user would see has changed. */
    fun update(receiver: FoundReceiver, now: Long): Boolean {
        val previous = entries.put(receiver.id, Entry(receiver, now))
        return previous?.receiver != receiver
    }

    /** Drops receivers silent for too long; true if any went. */
    fun prune(now: Long): Boolean = entries.values.removeAll { now - it.lastHeard > RECEIVER_EXPIRY_MS }

    fun snapshot(): List<FoundReceiver> = entries.values.map { it.receiver }.sortedWith(compareBy({ it.name }, { it.id }))
}

private fun liveInterfaces(): List<NetworkInterface> = try {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
} catch (ignored: IOException) {
    emptyList()
}

/** The addresses an announce goes to: the limited broadcast and each interface's own subnet. */
private fun broadcastTargets(): List<InetAddress> {
    val subnets = liveInterfaces().flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }
    return (subnets + InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))).distinct()
}

/** This device's IPv4 addresses, for a user who has to type one in on the other device. */
fun localIpv4Addresses(): List<String> = liveInterfaces()
    .flatMap { it.inetAddresses.toList() }
    .filterIsInstance<Inet4Address>()
    .mapNotNull { it.hostAddress }

/** Broadcasts [payload] on [port] at a steady pace until [stop] is called. */
class AnnounceSender(private val payload: ByteArray, private val port: Int) {
    private val worker = thread(start = false, isDaemon = true, name = "announce-sender") { run() }

    fun start() = worker.start()

    fun stop() = worker.interrupt()

    private fun run() {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                while (!Thread.currentThread().isInterrupted) {
                    broadcastTargets().forEach { target ->
                        try {
                            socket.send(DatagramPacket(payload, payload.size, target, port))
                        } catch (ignored: IOException) {
                            // One unreachable subnet must not stop the others, or the next round.
                        }
                    }
                    Thread.sleep(ANNOUNCE_INTERVAL_MS)
                }
            }
        } catch (ignored: InterruptedException) {
            // stop() was called.
        } catch (ignored: IOException) {
            // No socket: manual IP entry on the sender still works.
        }
    }
}

/**
 * Hears announces on [port] and calls [onAnnounce] with each and the address it came
 * from, and [onTick] at least every announce interval. Both run on the listener's thread.
 */
class AnnounceListener(
    private val port: Int,
    private val onAnnounce: (Announce, String) -> Unit,
    private val onTick: () -> Unit,
) {
    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var stopped = false

    fun start() {
        thread(isDaemon = true, name = "announce-listener") { run() }
    }

    fun stop() {
        stopped = true
        socket?.close()
    }

    private fun run() {
        val bound = try {
            // A named parameter, not apply: inside apply "port" would be the socket's own.
            DatagramSocket(null as SocketAddress?).also { created ->
                created.reuseAddress = true
                created.soTimeout = ANNOUNCE_INTERVAL_MS.toInt()
                created.bind(InetSocketAddress(port))
            }
        } catch (ignored: IOException) {
            return
        }
        socket = bound
        if (stopped) {
            bound.close()
            return
        }
        val buffer = ByteArray(MAX_DATAGRAM)
        val packet = DatagramPacket(buffer, buffer.size)
        bound.use {
            while (!stopped) {
                try {
                    packet.length = buffer.size
                    bound.receive(packet)
                    val host = packet.address?.hostAddress
                    val announce = Announce.parse(buffer, packet.length)
                    if (announce != null && host != null) onAnnounce(announce, host)
                } catch (ignored: java.net.SocketTimeoutException) {
                    // Only here so onTick runs on a silent network.
                } catch (ignored: IOException) {
                    return
                }
                onTick()
            }
        }
    }
}
