package xx.zerkalo.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.zerkalo.CODEC_AUDIO_OPUS
import xx.zerkalo.CODEC_H264
import xx.zerkalo.CODEC_H265
import xx.zerkalo.MAX_DATAGRAM
import xx.zerkalo.MAX_NAME_BYTES
import xx.zerkalo.PACKET_AUDIO
import xx.zerkalo.VIDEO_HEADER_SIZE
import xx.zerkalo.hex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException

class ProtocolTest {
    private val id = hex("000102030405060708090a0b0c0d0e0f")
    private val nonce = ByteArray(16) { 0x11 }

    private fun encoded(message: ControlMessage): ByteArray =
        ByteArrayOutputStream().also { writeControl(it, message) }.toByteArray()

    private fun read(bytes: ByteArray): ControlMessage = readControl(DataInputStream(ByteArrayInputStream(bytes)))

    // ---------- Golden bytes: the layout in SPEC.md, written out by hand ----------

    @Test
    fun helloMatchesSpec() {
        val expected = hex("0001 00000026 01 000102030405060708090a0b0c0d0e0f 0002 5456 11111111111111111111111111111111 01")
        assertArrayEquals(expected, encoded(ControlMessage.Hello(id, "TV", nonce, AuthMode.TOKEN)))
    }

    @Test
    fun configMatchesSpec() {
        val message = ControlMessage.Config(7, CODEC_H265, 498, 1080, hex("AABB"))
        assertArrayEquals(hex("0005 00000009 0007 01 01F2 0438 AABB"), encoded(message))
    }

    @Test
    fun emptyMessageMatchesSpec() {
        assertArrayEquals(hex("0008 00000000"), encoded(ControlMessage.Ping))
    }

    @Test
    fun announceMatchesSpec() {
        val expected = hex("5a524b4c 01 01 000102030405060708090a0b0c0d0e0f BA54 0002 5456")
        assertArrayEquals(expected, Announce(id, 47700, "TV").encode())
    }

    @Test
    fun videoHeaderMatchesSpec() {
        val header = VideoHeader(true, 3, 0x01020304, 5, 6, 0x0A0B0C0D)
        val target = ByteArray(VIDEO_HEADER_SIZE)
        header.writeTo(target)
        assertArrayEquals(hex("10 01 0003 01020304 0005 0006 0A0B0C0D"), target)
    }

    @Test
    fun nackMatchesSpec() {
        assertArrayEquals(hex("11 02 00000009 0001 012C"), Nack(9, intArrayOf(1, 300)).encode())
    }

    // ---------- Control channel ----------

    @Test
    fun helloRoundTrips() {
        val message = read(encoded(ControlMessage.Hello(id, "Телефон", nonce, AuthMode.PIN))) as ControlMessage.Hello
        assertArrayEquals(id, message.senderId)
        assertEquals("Телефон", message.name)
        assertArrayEquals(nonce, message.nonceS)
        assertEquals(AuthMode.PIN, message.mode)
    }

    @Test
    fun simpleMessagesRoundTrip() {
        val messages = listOf(
            ControlMessage.AuthOk(codecMaskOf(CODEC_H264, CODEC_H265)),
            ControlMessage.Ready(65535),
            ControlMessage.KeyframeRequest,
            ControlMessage.Ping,
            ControlMessage.Bye,
            ControlMessage.Error(ErrorCode.UNKNOWN_TOKEN),
        )
        messages.forEach { assertEquals(it, read(encoded(it))) }
    }

    @Test
    fun configRoundTrips() {
        val message = read(encoded(ControlMessage.Config(9, CODEC_H264, 1920, 1080, hex("010203")))) as ControlMessage.Config
        assertEquals(9, message.configId)
        assertEquals(CODEC_H264, message.codec)
        assertEquals(1920, message.width)
        assertEquals(1080, message.height)
        assertArrayEquals(hex("010203"), message.csd)
    }

    @Test
    fun consecutiveMessagesAreReadInOrder() {
        val stream = DataInputStream(ByteArrayInputStream(encoded(ControlMessage.Ping) + encoded(ControlMessage.Bye)))
        assertSame(ControlMessage.Ping, readControl(stream))
        assertSame(ControlMessage.Bye, readControl(stream))
        assertThrows(EOFException::class.java) { readControl(stream) }
    }

    @Test
    fun otherVersionIsReportedAsSuch() {
        assertThrows(UnsupportedVersionException::class.java) { read(hex("0001 00000001 02")) }
    }

    @Test
    fun truncatedPayloadIsRejected() {
        assertThrows(ProtocolException::class.java) { read(hex("0002 00000001 00")) }
    }

    @Test
    fun trailingBytesAreRejected() {
        assertThrows(ProtocolException::class.java) { read(hex("0008 00000001 00")) }
    }

    @Test
    fun unknownTypeIsRejected() {
        assertThrows(ProtocolException::class.java) { read(hex("00FF 00000000")) }
    }

    @Test
    fun oversizedLengthIsRejectedBeforeAllocating() {
        assertThrows(ProtocolException::class.java) { read(hex("0005 FFFFFFFF")) }
        assertThrows(ProtocolException::class.java) { read(hex("0005 00010001")) }
    }

    @Test
    fun invalidFieldsAreRejected() {
        val rejected = listOf(
            "0005 00000008 0001 05 0010 0010 AA", // unknown codec
            "0005 00000008 0001 00 0000 0010 AA", // zero width
            "0005 00000007 0001 00 0010 0010", // no CSD
            "000F 00000001 63", // unknown error code
        )
        rejected.forEach { line -> assertThrows(line, ProtocolException::class.java) { read(hex(line)) } }
    }

    @Test
    fun codecMask() {
        val mask = codecMaskOf(CODEC_H264)
        assertTrue(codecMaskHas(mask, CODEC_H264))
        assertFalse(codecMaskHas(mask, CODEC_H265))
    }

    @Test
    fun audioBitTravelsInTheCodecMask() {
        val message = read(encoded(ControlMessage.AuthOk(codecMaskOf(CODEC_H264, CODEC_AUDIO_OPUS)))) as ControlMessage.AuthOk
        assertTrue(codecMaskHas(message.codecs, CODEC_AUDIO_OPUS))
        assertTrue(codecMaskHas(message.codecs, CODEC_H264))
        assertFalse(codecMaskHas(message.codecs, CODEC_H265))
    }

    @Test
    fun audioDatagramIsNotTakenForVideoOrNack() {
        val data = ByteArray(VIDEO_HEADER_SIZE + 1).also { it[0] = PACKET_AUDIO.toByte() }
        assertNull(VideoHeader.parse(data, data.size))
        assertNull(Nack.parse(data, data.size))
    }

    // ---------- Names ----------

    @Test
    fun longNameIsCutToFit() {
        val message = read(encoded(ControlMessage.Hello(id, "x".repeat(500), nonce, AuthMode.PIN))) as ControlMessage.Hello
        assertEquals(MAX_NAME_BYTES, message.name.length)
    }

    @Test
    fun cutNeverSplitsCharacter() {
        assertArrayEquals("яя".toByteArray(), clampUtf8("яяя", 5))
        assertArrayEquals("яя".toByteArray(), clampUtf8("яяя", 4))
        assertArrayEquals(ByteArray(0), clampUtf8("😀", 3))
        assertArrayEquals("😀".toByteArray(), clampUtf8("😀😀", 7))
    }

    @Test
    fun cutLeavesShortNameAlone() {
        assertArrayEquals("яя".toByteArray(), clampUtf8("яя", 4))
    }

    // ---------- Datagrams ----------

    @Test
    fun announceRoundTrips() {
        val bytes = Announce(id, 47700, "Приставка").encode()
        val parsed = Announce.parse(bytes, bytes.size)!!
        assertArrayEquals(id, parsed.receiverId)
        assertEquals(47700, parsed.controlPort)
        assertEquals("Приставка", parsed.name)
    }

    @Test
    fun announceParsesOnlyTheReceivedLength() {
        val bytes = Announce(id, 47700, "TV").encode()
        val buffer = bytes + ByteArray(100) { 0x7F }
        assertEquals("TV", Announce.parse(buffer, bytes.size)!!.name)
    }

    @Test
    fun badAnnounceIsDropped() {
        val good = Announce(id, 47700, "TV").encode()
        val wrongMagic = good.copyOf().also { it[0] = 0 }
        val wrongVersion = good.copyOf().also { it[4] = 9 }
        val wrongType = good.copyOf().also { it[5] = 9 }
        listOf(wrongMagic, wrongVersion, wrongType).forEach { assertNull(Announce.parse(it, it.size)) }
        assertNull(Announce.parse(good, good.size - 1))
        assertNull(Announce.parse(good + 0, good.size + 1))
        assertNull(Announce.parse(ByteArray(0), 0))
    }

    private fun videoDatagram(header: VideoHeader, payloadSize: Int = 1): ByteArray =
        ByteArray(VIDEO_HEADER_SIZE + payloadSize).also(header::writeTo)

    @Test
    fun videoHeaderRoundTrips() {
        val header = VideoHeader(false, 65535, 0xFFFF_FFFFL, 1, 2, 0xFFFF_FFFFL)
        val data = videoDatagram(header)
        assertEquals(header, VideoHeader.parse(data, data.size))
    }

    @Test
    fun badVideoDatagramIsDropped() {
        val good = VideoHeader(true, 1, 1, 0, 1, 0)
        val data = videoDatagram(good)
        val headerOnly = videoDatagram(good, 0)
        assertNull(VideoHeader.parse(headerOnly, headerOnly.size))
        val tooLong = videoDatagram(good, MAX_DATAGRAM - VIDEO_HEADER_SIZE + 1)
        assertNull(VideoHeader.parse(tooLong, tooLong.size))
        val wrongType = data.copyOf().also { it[0] = 0x11 }
        assertNull(VideoHeader.parse(wrongType, wrongType.size))
        val indexPastCount = videoDatagram(VideoHeader(true, 1, 1, 2, 2, 0))
        assertNull(VideoHeader.parse(indexPastCount, indexPastCount.size))
    }

    @Test
    fun largestVideoDatagramIsAccepted() {
        val header = VideoHeader(true, 1, 1, 0, 1, 0)
        val data = videoDatagram(header, MAX_DATAGRAM - VIDEO_HEADER_SIZE)
        assertEquals(header, VideoHeader.parse(data, data.size))
    }

    @Test
    fun nackRoundTrips() {
        val bytes = Nack(0xFFFF_FFFFL, intArrayOf(0, 65535)).encode()
        val parsed = Nack.parse(bytes, bytes.size)!!
        assertEquals(0xFFFF_FFFFL, parsed.frameId)
        assertArrayEquals(intArrayOf(0, 65535), parsed.fragments)
    }

    @Test
    fun badNackIsDropped() {
        val good = Nack(1, intArrayOf(1, 2)).encode()
        assertNull(Nack.parse(good, good.size - 1))
        assertNull(Nack.parse(good + 0, good.size + 1))
        val zeroCount = hex("11 00 00000001 0001")
        assertNull(Nack.parse(zeroCount, zeroCount.size))
        val wrongType = good.copyOf().also { it[0] = 0x10 }
        assertNull(Nack.parse(wrongType, wrongType.size))
    }
}
