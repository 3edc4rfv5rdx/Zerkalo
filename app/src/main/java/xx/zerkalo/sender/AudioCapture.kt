package xx.zerkalo.sender

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import xx.zerkalo.AUDIO_BIT_RATE
import xx.zerkalo.AUDIO_CHANNELS
import xx.zerkalo.AUDIO_READ_BYTES
import xx.zerkalo.AUDIO_SAMPLE_RATE
import xx.zerkalo.MAX_AUDIO_PAYLOAD
import xx.zerkalo.PTS_MASK
import xx.zerkalo.VIDEO_PORT
import xx.zerkalo.net.AudioPacket
import xx.zerkalo.net.ControlChannel
import xx.zerkalo.net.ControlMessage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

private const val TAG = "AudioCapture"
private const val INPUT_TIMEOUT_US = 10_000L
private const val STOP_WAIT_MS = 500L
private const val RECORD_BUFFER_READS = 4
private const val BYTES_PER_FRAME = 4
private const val US_PER_S = 1_000_000L

/**
 * Captures what apps are playing, encodes it and sends it beside the picture. Whatever
 * goes wrong here ends the sound only: the picture has no need of it.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AudioCapture(
    private val projection: MediaProjection,
    private val channel: ControlChannel,
    private val baseUs: Long,
) {
    @Volatile
    private var stopped = false

    @Volatile
    private var record: AudioRecord? = null

    private var worker: Thread? = null

    fun start() {
        worker = thread(isDaemon = true, name = "audio-capture") { run() }
    }

    fun stop() {
        stopped = true
        // A blocked read only returns once the recording is stopped.
        runCatching { record?.stop() }
        worker?.join(STOP_WAIT_MS)
    }

    private fun run() {
        try {
            val address = InetAddress.getByName(channel.remoteHost)
            val capture = createRecord()
            try {
                record = capture
                val encoder = createEncoder()
                try {
                    DatagramSocket().use { socket -> encodeLoop(capture, encoder, socket, address) }
                } finally {
                    runCatching { encoder.stop() }
                    encoder.release()
                }
            } finally {
                record = null
                runCatching { capture.stop() }
                capture.release()
            }
        } catch (e: Exception) {
            // Audio and codecs fail in many ways on real hardware; each one just means no sound.
            if (!stopped) Log.w(TAG, "Sound capture ended", e)
        }
    }

    @SuppressLint("MissingPermission") // SenderService starts this only with the permission granted.
    private fun createRecord(): AudioRecord {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minimum = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(capture)
            .setBufferSizeInBytes(maxOf(minimum, AUDIO_READ_BYTES * RECORD_BUFFER_READS))
            .build()
    }

    private fun createEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_READ_BYTES)
        }
        val created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            return created
        } catch (e: Exception) {
            created.release()
            throw e
        }
    }

    private fun encodeLoop(capture: AudioRecord, encoder: MediaCodec, socket: DatagramSocket, address: InetAddress) {
        capture.startRecording()
        val info = MediaCodec.BufferInfo()
        var seq = 0L
        var previous: ByteArray? = null

        fun send(bytes: ByteArray) = socket.send(DatagramPacket(bytes, bytes.size, address, VIDEO_PORT))

        while (!stopped) {
            val input = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (input >= 0) feed(capture, encoder, input)
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, 0)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (index < 0) continue
                val buffer = encoder.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        channel.send(ControlMessage.AudioConfig(AUDIO_SAMPLE_RATE, AUDIO_CHANNELS, bytes))
                    } else if (bytes.size <= MAX_AUDIO_PAYLOAD) {
                        seq++
                        val ptsMs = (info.presentationTimeUs - baseUs) / 1000 and PTS_MASK
                        val packet = AudioPacket(seq, ptsMs, bytes).encode()
                        // The repeat goes first, so that the frames still arrive in order
                        // when it is the one that saves a lost packet.
                        previous?.let(::send)
                        send(packet)
                        previous = packet
                    }
                }
                encoder.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun feed(capture: AudioRecord, encoder: MediaCodec, index: Int) {
        val buffer = checkNotNull(encoder.getInputBuffer(index))
        buffer.clear()
        val read = capture.read(buffer, minOf(buffer.remaining(), AUDIO_READ_BYTES), AudioRecord.READ_BLOCKING)
        check(read >= 0) { "AudioRecord error $read" }
        // Stamped as of the block's start, which is when these samples were played.
        val ptsUs = System.nanoTime() / 1000 - read / BYTES_PER_FRAME * US_PER_S / AUDIO_SAMPLE_RATE
        encoder.queueInputBuffer(index, 0, read, ptsUs, 0)
    }
}
