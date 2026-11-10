package xx.zerkalo

// Everything both ends of a connection must agree on. Free of the Android framework,
// so the protocol stays covered by plain JVM tests. SPEC.md describes the wire format.

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
const val AUDIO_HEADER_SIZE = 9
const val MAX_AUDIO_PAYLOAD = MAX_DATAGRAM - AUDIO_HEADER_SIZE
const val MAX_NACK_FRAGMENTS = 255

const val PACKET_ANNOUNCE = 0x01
const val PACKET_VIDEO = 0x10
const val PACKET_NACK = 0x11

/** Audio datagrams share the video port, told apart by this first byte. */
const val PACKET_AUDIO = 0x12

const val VIDEO_FLAG_KEYFRAME = 0x01

const val CODEC_H264 = 0
const val CODEC_H265 = 1

/** A receiver that can play sound sets this bit in AUTH_OK's mask. AAC, as every Android since 9 decodes it the same way. */
const val CODEC_AUDIO_AAC = 4

const val PIN_LENGTH = 4
const val PIN_MAX_FAILURES = 3
const val PIN_LOCKOUT_MS = 10_000L

const val ANNOUNCE_INTERVAL_MS = 2_000L
const val RECEIVER_EXPIRY_MS = 7_000L
const val CONNECT_TIMEOUT_MS = 3_000
const val HANDSHAKE_TIMEOUT_MS = 5_000
const val PING_INTERVAL_MS = 1_000L
const val SESSION_TIMEOUT_MS = 4_000

const val MAX_STREAM_WIDTH = 1920
const val MAX_STREAM_HEIGHT = 1080
const val BIT_RATE_LOW = 4_000_000
const val BIT_RATE_MEDIUM = 8_000_000
const val BIT_RATE_HIGH = 16_000_000

/** H.265 reaches the same picture at about this share of H.264's bit rate. */
const val H265_BIT_RATE_PERCENT = 65
const val FRAME_RATE_STANDARD = 30
const val FRAME_RATE_HIGH = 60
const val KEY_FRAME_INTERVAL_S = 2

/** Timestamps travel as u32 milliseconds. */
const val PTS_MASK = 0xFFFF_FFFFL

const val AUDIO_SAMPLE_RATE = 48_000
const val AUDIO_CHANNELS = 2
const val AUDIO_BIT_RATE = 160_000

/** One read from the capture: 1024 stereo samples, an AAC frame's worth. */
const val AUDIO_READ_BYTES = 4096
const val AUDIO_QUEUE_PACKETS = 32

/**
 * Only sound this far behind the picture is dropped. A smaller lag is played as it is:
 * the output path can take longer than the picture waits, and dropping would mean silence.
 */
const val AUDIO_MAX_LATE_MS = 500L

/** Makes the encoder repeat a still picture, so a static photo keeps producing frames. */
const val REPEAT_FRAME_AFTER_US = 100_000L

/** Sent together, then a pause, so a large key frame does not overflow the access point's queue. */
const val PACKETS_PER_BURST = 32
const val MAX_PARTIAL_FRAMES = 16
const val DECODER_QUEUE_FRAMES = 8

/** How many sent frames stay available for repeating lost packets. */
const val RETRANSMIT_FRAMES = 32
/** Silence on an unfinished frame before its holes count as lost and not as still on the way. */
const val NACK_DELAY_MS = 10L
const val NACK_INTERVAL_MS = 20L
const val MAX_NACKS_PER_FRAME = 8

/**
 * How long a frame may stay unfinished before it is given up on. Generous on purpose: a frame
 * that comes late costs one hiccup, and giving up costs a freeze until the next key frame.
 */
const val FRAME_DEADLINE_MS = 250L
const val KEYFRAME_REQUEST_INTERVAL_MS = 500L
const val PLAYOUT_DELAY_MS = 80L
const val PLAYOUT_WINDOW_MS = 2_000L

/** Room asked of the decoder's input buffers: a detailed key frame can outgrow its default. */
const val MAX_ENCODED_FRAME_BYTES = 4 * 1024 * 1024
const val VIDEO_RECEIVE_BUFFER = 4 * 1024 * 1024

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
