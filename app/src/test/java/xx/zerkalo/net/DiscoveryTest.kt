package xx.zerkalo.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.zerkalo.RECEIVER_EXPIRY_MS
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DiscoveryTest {
    private fun receiver(id: String, name: String = "TV", host: String = "10.0.0.1") =
        FoundReceiver(id, name, host, 47700)

    @Test
    fun listenerHearsAnnounceOnItsPort() {
        val port = DatagramSocket(0).use { it.localPort }
        val heard = CountDownLatch(1)
        var name: String? = null
        var host: String? = null
        val listener = AnnounceListener(
            port = port,
            onAnnounce = { announce, from ->
                name = announce.name
                host = from
                heard.countDown()
            },
            onTick = {},
        )
        listener.start()
        val bytes = Announce(ByteArray(16) { 1 }, 47700, "TV").encode()
        DatagramSocket().use { socket ->
            val packet = DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port)
            // Repeated, because the listener binds on its own thread some time after start().
            repeat(50) {
                socket.send(packet)
                if (heard.await(100, TimeUnit.MILLISECONDS)) return@use
            }
        }
        listener.stop()
        assertEquals(0L, heard.count)
        assertEquals("TV", name)
        assertEquals("127.0.0.1", host)
    }

    @Test
    fun newReceiverChangesTheList() {
        assertTrue(ReceiverList().update(receiver("a"), 0))
    }

    @Test
    fun repeatedAnnounceChangesNothing() {
        val list = ReceiverList()
        list.update(receiver("a"), 0)
        assertFalse(list.update(receiver("a"), 1000))
    }

    @Test
    fun newAddressOrNameChangesTheList() {
        val list = ReceiverList()
        list.update(receiver("a"), 0)
        assertTrue(list.update(receiver("a", host = "10.0.0.2"), 1))
        assertTrue(list.update(receiver("a", name = "Box", host = "10.0.0.2"), 2))
        assertEquals(1, list.snapshot().size)
    }

    @Test
    fun silentReceiverExpiresOnlyPastTheLimit() {
        val list = ReceiverList()
        list.update(receiver("a"), 0)
        assertFalse(list.prune(RECEIVER_EXPIRY_MS))
        assertTrue(list.prune(RECEIVER_EXPIRY_MS + 1))
        assertTrue(list.snapshot().isEmpty())
    }

    @Test
    fun announceKeepsReceiverAlive() {
        val list = ReceiverList()
        list.update(receiver("a"), 0)
        list.update(receiver("a"), RECEIVER_EXPIRY_MS)
        assertFalse(list.prune(RECEIVER_EXPIRY_MS + 1))
    }

    @Test
    fun listIsSortedByName() {
        val list = ReceiverList()
        list.update(receiver("a", name = "Zed"), 0)
        list.update(receiver("b", name = "Box"), 0)
        assertEquals(listOf("Box", "Zed"), list.snapshot().map { it.name })
    }
}
