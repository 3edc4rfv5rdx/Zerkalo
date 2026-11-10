package xx.zerkalo.net

import xx.zerkalo.ANNOUNCE_MAGIC
import xx.zerkalo.CODEC_H264
import xx.zerkalo.CODEC_H265
import xx.zerkalo.ID_SIZE
import xx.zerkalo.MAX_CONTROL_PAYLOAD
import xx.zerkalo.MAX_DATAGRAM
import xx.zerkalo.MAX_NACK_FRAGMENTS
import xx.zerkalo.MAX_NAME_BYTES
import xx.zerkalo.NONCE_SIZE
import xx.zerkalo.PACKET_ANNOUNCE
import xx.zerkalo.PACKET_NACK
import xx.zerkalo.PACKET_VIDEO
import xx.zerkalo.PROOF_SIZE
import xx.zerkalo.PROTOCOL_VERSION
import xx.zerkalo.VIDEO_FLAG_KEYFRAME
import xx.zerkalo.VIDEO_HEADER_SIZE
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

/** A message that does not follow the protocol. */
open class ProtocolException(message: String) : IOException(message)

/** The peer speaks another protocol version; its HELLO cannot be read past the version byte. */
class UnsupportedVersionException(version: Int) : ProtocolException("Unsupported protocol version $version")

private const val MAX_U16 = 0xFFFF
private const val MAX_U32 = 0xFFFF_FFFFL
private val MAGIC_BYTES = ANNOUNCE_MAGIC.toByteArray(Charsets.US_ASCII)

/** Frame header of a control message: u16 type and u32 length. */
private const val CONTROL_HEADER_SIZE = 6

/** Datagram header of a NACK: u8 type, u8 count and u32 frame id. */
private const val NACK_HEADER_SIZE = 6

/** [text] as UTF-8, cut at a character boundary to fit [maxBytes]. */
internal fun clampUtf8(text: String, maxBytes: Int): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return bytes
    var end = maxBytes
    // A continuation byte at the cut means a character straddles it: drop that character whole.
    while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end--
    return bytes.copyOf(end)
}

private class Writer {
    private val bytes = ByteArrayOutputStream()
    private val out = DataOutputStream(bytes)

    fun u8(value: Int) = apply {
        require(value in 0..0xFF) { "u8 out of range: $value" }
        out.writeByte(value)
    }

    fun u16(value: Int) = apply {
        require(value in 0..MAX_U16) { "u16 out of range: $value" }
        out.writeShort(value)
    }

    fun u32(value: Long) = apply {
        require(value in 0..MAX_U32) { "u32 out of range: $value" }
        out.writeInt(value.toInt())
    }

    fun raw(value: ByteArray) = apply { out.write(value) }

    fun fixed(value: ByteArray, size: Int) = apply {
        require(value.size == size) { "Expected $size bytes, got ${value.size}" }
        out.write(value)
    }

    fun name(value: String) = apply {
        val encoded = clampUtf8(value, MAX_NAME_BYTES)
        u16(encoded.size)
        out.write(encoded)
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

private class Reader(data: ByteArray, length: Int = data.size) {
    private val buffer = ByteBuffer.wrap(data, 0, length)

    private fun need(count: Int) {
        if (buffer.remaining() < count) throw ProtocolException("Message ends early")
    }

    fun u8(): Int {
        need(1)
        return buffer.get().toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        return buffer.short.toInt() and MAX_U16
    }

    fun u32(): Long {
        need(4)
        return buffer.int.toLong() and MAX_U32
    }

    fun fixed(size: Int): ByteArray {
        need(size)
        return ByteArray(size).also { buffer.get(it) }
    }

    fun rest(): ByteArray = fixed(buffer.remaining())

    fun name(): String {
        val size = u16()
        if (size > MAX_NAME_BYTES) throw ProtocolException("Name too long: $size")
        return String(fixed(size), Charsets.UTF_8)
    }

    fun end() {
        if (buffer.hasRemaining()) throw ProtocolException("${buffer.remaining()} bytes left over")
    }
}

// ---------- Control channel ----------

enum class AuthMode(val wire: Int) {
    PIN(0),
    TOKEN(1);

    companion object {
        fun of(wire: Int): AuthMode =
            entries.firstOrNull { it.wire == wire } ?: throw ProtocolException("Unknown auth mode $wire")
    }
}

enum class ErrorCode(val wire: Int) {
    BUSY(1),
    BAD_PIN(2),
    UNKNOWN_TOKEN(3),
    VERSION(4);

    companion object {
        fun of(wire: Int): ErrorCode =
            entries.firstOrNull { it.wire == wire } ?: throw ProtocolException("Unknown error code $wire")
    }
}

/** Whether a codec mask, as AUTH_OK carries it, includes [codec]. */
fun codecMaskHas(mask: Int, codec: Int): Boolean = mask and (1 shl codec) != 0

/** The mask for [codecs]. */
fun codecMaskOf(vararg codecs: Int): Int = codecs.fold(0) { mask, codec -> mask or (1 shl codec) }

sealed class ControlMessage(internal val type: Int) {
    class Hello(val senderId: ByteArray, val name: String, val nonceS: ByteArray, val mode: AuthMode) :
        ControlMessage(TYPE_HELLO)

    class Challenge(val receiverId: ByteArray, val nonceR: ByteArray) : ControlMessage(TYPE_CHALLENGE)

    class Auth(val proof: ByteArray) : ControlMessage(TYPE_AUTH)

    data class AuthOk(val codecs: Int) : ControlMessage(TYPE_AUTH_OK)

    class Config(val configId: Int, val codec: Int, val width: Int, val height: Int, val csd: ByteArray) :
        ControlMessage(TYPE_CONFIG)

    data class Ready(val configId: Int) : ControlMessage(TYPE_READY)

    data object KeyframeRequest : ControlMessage(TYPE_KEYFRAME_REQUEST)

    data object Ping : ControlMessage(TYPE_PING)

    data object Bye : ControlMessage(TYPE_BYE)

    data class Error(val code: ErrorCode) : ControlMessage(TYPE_ERROR)

    internal fun payload(): ByteArray {
        val writer = Writer()
        when (this) {
            is Hello -> writer.u8(PROTOCOL_VERSION).fixed(senderId, ID_SIZE).name(name)
                .fixed(nonceS, NONCE_SIZE).u8(mode.wire)
            is Challenge -> writer.fixed(receiverId, ID_SIZE).fixed(nonceR, NONCE_SIZE)
            is Auth -> writer.fixed(proof, PROOF_SIZE)
            is AuthOk -> writer.u8(codecs)
            is Config -> writer.u16(configId).u8(codec).u16(width).u16(height).raw(csd)
            is Ready -> writer.u16(configId)
            is Error -> writer.u8(code.wire)
            KeyframeRequest, Ping, Bye -> Unit
        }
        return writer.toByteArray()
    }

    internal companion object {
        const val TYPE_HELLO = 0x01
        const val TYPE_CHALLENGE = 0x02
        const val TYPE_AUTH = 0x03
        const val TYPE_AUTH_OK = 0x04
        const val TYPE_CONFIG = 0x05
        const val TYPE_READY = 0x06
        const val TYPE_KEYFRAME_REQUEST = 0x07
        const val TYPE_PING = 0x08
        const val TYPE_BYE = 0x09

        // 0x0A is reserved for AUDIO_CONFIG. A sender only sends it to a receiver whose
        // AUTH_OK mask offers sound, so a receiver without sound never sees it.

        const val TYPE_ERROR = 0x0F
    }
}

/** Parses one control message's [payload]; throws [ProtocolException] if it is not valid. */
internal fun decodeControl(type: Int, payload: ByteArray): ControlMessage {
    val reader = Reader(payload)
    val message = when (type) {
        ControlMessage.TYPE_HELLO -> {
            val version = reader.u8()
            if (version != PROTOCOL_VERSION) throw UnsupportedVersionException(version)
            ControlMessage.Hello(reader.fixed(ID_SIZE), reader.name(), reader.fixed(NONCE_SIZE), AuthMode.of(reader.u8()))
        }
        ControlMessage.TYPE_CHALLENGE -> ControlMessage.Challenge(reader.fixed(ID_SIZE), reader.fixed(NONCE_SIZE))
        ControlMessage.TYPE_AUTH -> ControlMessage.Auth(reader.fixed(PROOF_SIZE))
        ControlMessage.TYPE_AUTH_OK -> ControlMessage.AuthOk(reader.u8())
        ControlMessage.TYPE_CONFIG -> decodeConfig(reader)
        ControlMessage.TYPE_READY -> ControlMessage.Ready(reader.u16())
        ControlMessage.TYPE_KEYFRAME_REQUEST -> ControlMessage.KeyframeRequest
        ControlMessage.TYPE_PING -> ControlMessage.Ping
        ControlMessage.TYPE_BYE -> ControlMessage.Bye
        ControlMessage.TYPE_ERROR -> ControlMessage.Error(ErrorCode.of(reader.u8()))
        else -> throw ProtocolException("Unknown message type $type")
    }
    reader.end()
    return message
}

private fun decodeConfig(reader: Reader): ControlMessage.Config {
    val configId = reader.u16()
    val codec = reader.u8()
    if (codec != CODEC_H264 && codec != CODEC_H265) throw ProtocolException("Unknown codec $codec")
    val width = reader.u16()
    val height = reader.u16()
    if (width == 0 || height == 0) throw ProtocolException("Empty frame size")
    val csd = reader.rest()
    if (csd.isEmpty()) throw ProtocolException("No codec configuration")
    return ControlMessage.Config(configId, codec, width, height, csd)
}

/** Writes [message] in a single call. Callers sharing [out] between threads must serialise their calls. */
fun writeControl(out: OutputStream, message: ControlMessage) {
    val payload = message.payload()
    require(payload.size <= MAX_CONTROL_PAYLOAD) { "Control payload too long: ${payload.size}" }
    out.write(Writer().u16(message.type).u32(payload.size.toLong()).raw(payload).toByteArray())
    out.flush()
}

/**
 * Reads the next control message, blocking until it has arrived whole. Throws
 * [ProtocolException] for an invalid one and [java.io.EOFException] when the peer has closed.
 */
fun readControl(input: DataInputStream): ControlMessage {
    val header = ByteArray(CONTROL_HEADER_SIZE)
    input.readFully(header)
    val reader = Reader(header)
    val type = reader.u16()
    val length = reader.u32()
    // Checked before allocating: the length is whatever the peer chose to send.
    if (length > MAX_CONTROL_PAYLOAD) throw ProtocolException("Control payload too long: $length")
    val payload = ByteArray(length.toInt())
    input.readFully(payload)
    return decodeControl(type, payload)
}

// ---------- Datagrams ----------
//
// Unlike a control message, a bad datagram is not an error: anything on the network can
// land on these ports. Parsing returns null and the caller just drops the packet.

class Announce(val receiverId: ByteArray, val controlPort: Int, val name: String) {
    fun encode(): ByteArray = Writer().raw(MAGIC_BYTES).u8(PROTOCOL_VERSION).u8(PACKET_ANNOUNCE)
        .fixed(receiverId, ID_SIZE).u16(controlPort).name(name).toByteArray()

    companion object {
        fun parse(data: ByteArray, length: Int): Announce? = try {
            val reader = Reader(data, length)
            val valid = reader.fixed(MAGIC_BYTES.size).contentEquals(MAGIC_BYTES) &&
                reader.u8() == PROTOCOL_VERSION && reader.u8() == PACKET_ANNOUNCE
            if (valid) {
                Announce(reader.fixed(ID_SIZE), reader.u16(), reader.name()).also { reader.end() }
            } else {
                null
            }
        } catch (ignored: ProtocolException) {
            null
        }
    }
}

/** The header of one video datagram; the payload follows it in the same buffer. */
data class VideoHeader(
    val keyframe: Boolean,
    val configId: Int,
    val frameId: Long,
    val fragIndex: Int,
    val fragCount: Int,
    val ptsMs: Long,
) {
    /** Writes the header over the start of [target], which the payload then follows. */
    fun writeTo(target: ByteArray) {
        val header = Writer().u8(PACKET_VIDEO).u8(if (keyframe) VIDEO_FLAG_KEYFRAME else 0).u16(configId)
            .u32(frameId).u16(fragIndex).u16(fragCount).u32(ptsMs).toByteArray()
        header.copyInto(target)
    }

    companion object {
        /** The header of a video datagram of [length] bytes, or null if it is not a valid one. */
        fun parse(data: ByteArray, length: Int): VideoHeader? {
            if (length <= VIDEO_HEADER_SIZE || length > MAX_DATAGRAM) return null
            val reader = Reader(data, VIDEO_HEADER_SIZE)
            if (reader.u8() != PACKET_VIDEO) return null
            val flags = reader.u8()
            val configId = reader.u16()
            val frameId = reader.u32()
            val fragIndex = reader.u16()
            val fragCount = reader.u16()
            val ptsMs = reader.u32()
            if (fragIndex >= fragCount) return null
            return VideoHeader(flags and VIDEO_FLAG_KEYFRAME != 0, configId, frameId, fragIndex, fragCount, ptsMs)
        }
    }
}

/** The receiver's request to send [fragments] of [frameId] again. */
class Nack(val frameId: Long, val fragments: IntArray) {
    init {
        require(fragments.size in 1..MAX_NACK_FRAGMENTS) { "Bad NACK size: ${fragments.size}" }
    }

    fun encode(): ByteArray {
        val writer = Writer().u8(PACKET_NACK).u8(fragments.size).u32(frameId)
        fragments.forEach { writer.u16(it) }
        return writer.toByteArray()
    }

    companion object {
        fun parse(data: ByteArray, length: Int): Nack? {
            if (length < NACK_HEADER_SIZE + 2) return null
            val reader = Reader(data, length)
            if (reader.u8() != PACKET_NACK) return null
            val count = reader.u8()
            if (count == 0 || length != NACK_HEADER_SIZE + 2 * count) return null
            val frameId = reader.u32()
            return Nack(frameId, IntArray(count) { reader.u16() })
        }
    }
}
