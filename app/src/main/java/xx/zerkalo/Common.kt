package xx.zerkalo

// Everything both ends of a connection must agree on. Free of the Android framework,
// so the protocol stays covered by plain JVM tests. SPEC.ru.md describes the wire format.

const val PROTOCOL_VERSION = 1

const val CONTROL_PORT = 47700
const val DISCOVERY_PORT = 47701
const val VIDEO_PORT = 47702

const val ANNOUNCE_MAGIC = "ZRKL"

const val ID_SIZE = 16
const val NONCE_SIZE = 16
const val PROOF_SIZE = 32
const val MAX_NAME_BYTES = 128
const val MAX_CONTROL_PAYLOAD = 65_536

/** Small enough never to fragment at the IP level on a standard Ethernet or Wi-Fi MTU. */
const val MAX_DATAGRAM = 1400
const val VIDEO_HEADER_SIZE = 16
const val MAX_VIDEO_PAYLOAD = MAX_DATAGRAM - VIDEO_HEADER_SIZE
const val MAX_NACK_FRAGMENTS = 255

const val PACKET_ANNOUNCE = 0x01
const val PACKET_VIDEO = 0x10
const val PACKET_NACK = 0x11

/** Reserved for the sound stage: audio datagrams share the video port, told apart by this first byte. */
const val PACKET_AUDIO = 0x12

const val VIDEO_FLAG_KEYFRAME = 0x01

const val CODEC_H264 = 0
const val CODEC_H265 = 1

/** Reserved for the sound stage: a receiver that can play it sets this bit in AUTH_OK's mask. */
const val CODEC_AUDIO_OPUS = 4

const val PIN_LENGTH = 4
const val PIN_MAX_FAILURES = 3
const val PIN_LOCKOUT_MS = 10_000L

const val ANNOUNCE_INTERVAL_MS = 2_000L
const val RECEIVER_EXPIRY_MS = 7_000L
const val CONNECT_TIMEOUT_MS = 3_000
const val HANDSHAKE_TIMEOUT_MS = 5_000
const val PING_INTERVAL_MS = 1_000L
const val SESSION_TIMEOUT_MS = 4_000

fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** The bytes [text] spells in hex, or null if it does not. */
fun parseHex(text: String): ByteArray? {
    if (text.length % 2 != 0) return null
    return ByteArray(text.length / 2) { index ->
        val high = text[index * 2].digitToIntOrNull(16) ?: return null
        val low = text[index * 2 + 1].digitToIntOrNull(16) ?: return null
        (high * 16 + low).toByte()
    }
}
