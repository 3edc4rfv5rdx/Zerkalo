package xx.zerkalo.net

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.zerkalo.CODEC_H264
import xx.zerkalo.PIN_MAX_FAILURES
import xx.zerkalo.hex
import xx.zerkalo.toHex
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The real handshake over real loopback sockets. */
class ControlChannelTest {
    private class MemoryTokens : TokenStore {
        val saved = ConcurrentHashMap<String, ByteArray>()

        override fun token(peerId: ByteArray): ByteArray? = saved[peerId.toHex()]

        override fun save(peerId: ByteArray, token: ByteArray, name: String, host: String) {
            saved[peerId.toHex()] = token
        }
    }

    private val receiverId = ByteArray(16) { 1 }
    private val senderId = ByteArray(16) { 2 }
    private val otherSenderId = ByteArray(16) { 3 }
    private val tokens = MemoryTokens()
    private val connected = CountDownLatch(1)
    private val disconnected = CountDownLatch(1)

    @Volatile
    private var senderName: String? = null

    @Volatile
    private var time = 1_000L

    private val guard = PinGuard(now = { time }, generate = { "4321" })
    private val mask = codecMaskOf(CODEC_H264)

    private val server = ReceiverServer(
        port = 0,
        receiverId = receiverId,
        readCodecs = { mask },
        guard = guard,
        tokens = tokens,
        listener = object : ReceiverServer.Listener {
            override fun onConnected(senderName: String, host: String) {
                this@ControlChannelTest.senderName = senderName
                connected.countDown()
            }

            override fun onDisconnected() = disconnected.countDown()
        },
    ).also { it.start() }

    @After
    fun stopServer() = server.stop()

    private fun connect(credential: Credential, id: ByteArray = senderId): ConnectResult =
        connectToReceiver("127.0.0.1", server.boundPort, id, "Phone", credential)

    private fun connectedWithPin(): ConnectResult.Connected = connect(Credential.Pin("4321")) as ConnectResult.Connected

    @Test
    fun rightPinConnectsAndPairsBothSides() {
        val result = connectedWithPin()
        assertArrayEquals(receiverId, result.receiverId)
        assertEquals(mask, result.codecs)
        assertArrayEquals(result.key, tokens.token(senderId))
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        assertEquals("Phone", senderName)
        result.channel.close()
    }

    @Test
    fun savedTokenConnectsWithoutPin() {
        val first = connectedWithPin()
        first.channel.disconnect()
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        val second = connect(Credential.Token(first.key)) as ConnectResult.Connected
        assertArrayEquals(first.key, second.key)
        second.channel.close()
    }

    @Test
    fun wrongPinIsRefusedAndNothingIsSaved() {
        assertSame(ConnectResult.BadPin, connect(Credential.Pin("0000")))
        assertNull(tokens.token(senderId))
    }

    @Test
    fun lockoutRefusesEvenTheRightPin() {
        repeat(PIN_MAX_FAILURES) { connect(Credential.Pin("0000")) }
        assertSame(ConnectResult.BadPin, connect(Credential.Pin("4321")))
        assertNull(tokens.token(senderId))
    }

    @Test
    fun pinWorksAgainAfterLockout() {
        repeat(PIN_MAX_FAILURES) { connect(Credential.Pin("0000")) }
        time += 60_000
        assertNotNull(connect(Credential.Pin("4321")) as? ConnectResult.Connected)
    }

    @Test
    fun unknownSenderWithTokenIsAskedForPin() {
        assertSame(ConnectResult.NeedPin, connect(Credential.Token(ByteArray(32))))
    }

    @Test
    fun knownSenderWithWrongTokenIsAskedForPin() {
        tokens.saved[senderId.toHex()] = ByteArray(32) { 9 }
        assertSame(ConnectResult.NeedPin, connect(Credential.Token(ByteArray(32) { 8 })))
    }

    @Test
    fun secondSenderIsToldBusy() {
        val first = connectedWithPin()
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        assertSame(ConnectResult.Busy, connect(Credential.Pin("4321"), otherSenderId))
        first.channel.close()
    }

    @Test
    fun receiverIsFreeAgainAfterSessionEnds() {
        val first = connectedWithPin()
        first.channel.disconnect()
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        val second = connect(Credential.Pin("4321"), otherSenderId) as ConnectResult.Connected
        second.channel.close()
    }

    @Test
    fun otherProtocolVersionIsTold() {
        Socket("127.0.0.1", server.boundPort).use { socket ->
            socket.getOutputStream().write(hex("0001 00000001 02"))
            socket.soTimeout = 5_000
            val reply = readControl(DataInputStream(socket.getInputStream()))
            assertEquals(ControlMessage.Error(ErrorCode.VERSION), reply)
        }
    }

    @Test
    fun nobodyListeningIsUnreachable() {
        val freePort = ServerSocket(0).use { it.localPort }
        assertSame(
            ConnectResult.Unreachable,
            connectToReceiver("127.0.0.1", freePort, senderId, "Phone", Credential.Pin("4321")),
        )
    }

    @Test
    fun peerThatDropsTheConnectionEndsTheSession() {
        val result = connectedWithPin()
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        result.channel.close()
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun stoppingTheServerEndsTheSession() {
        val result = connectedWithPin()
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        server.stop()
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        result.channel.close()
    }
}
