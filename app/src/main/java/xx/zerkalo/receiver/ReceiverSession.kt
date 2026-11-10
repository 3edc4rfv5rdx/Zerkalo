package xx.zerkalo.receiver

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import xx.zerkalo.DECODER_QUEUE_FRAMES
import xx.zerkalo.MAX_DATAGRAM
import xx.zerkalo.MAX_ENCODED_FRAME_BYTES
import xx.zerkalo.VIDEO_PORT
import xx.zerkalo.VIDEO_RECEIVE_BUFFER
import xx.zerkalo.net.ControlMessage
import xx.zerkalo.net.VideoHeader
import xx.zerkalo.video.AssembledFrame
import xx.zerkalo.video.FrameAssembler
import xx.zerkalo.video.mimeOf
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "ReceiverSession"
private const val SOCKET_POLL_MS = 500
private const val FRAME_POLL_MS = 5L
private const val CSD_INPUT_TIMEOUT_US = 1_000_000L
private const val SURFACE_RELEASE_WAIT_MS = 500L
private const val SURFACE_RELEASE_STEP_MS = 5L

/**
 * One sender's video: reads its datagrams, puts frames together and decodes them onto
 * the surface. The decoder has one owner, the decode thread, which follows [wanted].
 */
class ReceiverSession(
    private val senderHost: String,
    private val sendReady: (Int) -> Unit,
    private val onVideoSize: (Int, Int) -> Unit,
) {
    /** A decoder setup: a new instance whenever the configuration or the surface changes. */
    private class Target(val config: ControlMessage.Config, val surface: Surface)

    private val frames = ArrayBlockingQueue<AssembledFrame>(DECODER_QUEUE_FRAMES)

    @Volatile
    private var stopped = false

    @Volatile
    private var config: ControlMessage.Config? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var wanted: Target? = null

    /** The setup the decoder is running with, or was last tried with. */
    @Volatile
    private var running: Target? = null

    @Volatile
    private var socket: DatagramSocket? = null

    private var receiveThread: Thread? = null
    private var decodeThread: Thread? = null

    fun start() {
        receiveThread = thread(isDaemon = true, name = "video-receive") { receiveLoop() }
        decodeThread = thread(isDaemon = true, name = "video-decode") { decodeLoop() }
    }

    fun stop() {
        stopped = true
        socket?.close()
        decodeThread?.join(SURFACE_RELEASE_WAIT_MS)
    }

    fun onConfig(message: ControlMessage.Config) {
        config = message
        onVideoSize(message.width, message.height)
        updateWanted()
    }

    /** Null means the surface is going away: returns once the decoder has let go of it. */
    fun setSurface(newSurface: Surface?) {
        surface = newSurface
        updateWanted()
        if (newSurface == null) {
            var waited = 0L
            while (running != null && waited < SURFACE_RELEASE_WAIT_MS) {
                Thread.sleep(SURFACE_RELEASE_STEP_MS)
                waited += SURFACE_RELEASE_STEP_MS
            }
        }
    }

    @Synchronized
    private fun updateWanted() {
        val currentConfig = config
        val currentSurface = surface
        wanted = if (currentConfig != null && currentSurface != null) Target(currentConfig, currentSurface) else null
    }

    private fun receiveLoop() {
        val bound = try {
            DatagramSocket(VIDEO_PORT)
        } catch (e: IOException) {
            Log.e(TAG, "Cannot open the video port", e)
            return
        }
        socket = bound
        bound.use {
            bound.receiveBufferSize = VIDEO_RECEIVE_BUFFER
            bound.soTimeout = SOCKET_POLL_MS
            val buffer = ByteArray(MAX_DATAGRAM)
            val packet = DatagramPacket(buffer, buffer.size)
            var assembler = FrameAssembler()
            var assemblerConfig = -1
            while (!stopped) {
                try {
                    packet.length = buffer.size
                    bound.receive(packet)
                } catch (ignored: SocketTimeoutException) {
                    continue
                } catch (ignored: IOException) {
                    return
                }
                if (packet.address?.hostAddress != senderHost) continue
                val header = VideoHeader.parse(buffer, packet.length) ?: continue
                if (header.configId != config?.configId) continue
                if (header.configId != assemblerConfig) {
                    assembler = FrameAssembler()
                    assemblerConfig = header.configId
                }
                val frame = assembler.add(header, buffer, packet.length) ?: continue
                // A full queue means the decoder is behind: this frame is dropped, and
                // decoding goes wrong only until the next key frame.
                frames.offer(frame)
            }
        }
    }

    private fun decodeLoop() {
        var decoder: MediaCodec? = null
        var waitingForKey = true
        var held: AssembledFrame? = null
        val info = MediaCodec.BufferInfo()
        try {
            while (!stopped) {
                val target = wanted
                if (target !== running) {
                    releaseDecoder(decoder)
                    decoder = null
                    held = null
                    waitingForKey = true
                    if (target != null) decoder = createDecoder(target)
                    running = target
                    if (decoder != null && target != null) sendReady(target.config.configId)
                }
                val active = decoder
                if (active == null) {
                    Thread.sleep(FRAME_POLL_MS)
                    continue
                }
                try {
                    drainOutput(active, info)
                    val configId = target?.config?.configId
                    if (held == null) held = frames.poll(FRAME_POLL_MS, TimeUnit.MILLISECONDS)
                    val frame = held
                    if (frame == null) continue
                    if (frame.configId != configId || (waitingForKey && !frame.keyframe)) {
                        held = null
                        continue
                    }
                    if (queueFrame(active, frame)) {
                        waitingForKey = false
                        held = null
                    }
                } catch (e: RuntimeException) {
                    // A codec error, a surface pulled away mid-frame, a frame too big for
                    // the input buffer. A fresh decoder asks the sender for a key frame.
                    Log.w(TAG, "Decoder failed", e)
                    releaseDecoder(active)
                    decoder = null
                    held = null
                    running = null
                }
            }
        } catch (ignored: InterruptedException) {
            // Daemon thread; nothing else to do.
        } finally {
            releaseDecoder(decoder)
            running = null
        }
    }

    private fun createDecoder(target: Target): MediaCodec? {
        var codec: MediaCodec? = null
        return try {
            val config = target.config
            val mime = mimeOf(config.codec)
            val created = MediaCodec.createDecoderByType(mime)
            codec = created
            val format = MediaFormat.createVideoFormat(mime, config.width, config.height)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_ENCODED_FRAME_BYTES)
            val capabilities = created.codecInfo.getCapabilitiesForType(mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            ) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            created.configure(format, target.surface, null, 0)
            created.start()
            val index = created.dequeueInputBuffer(CSD_INPUT_TIMEOUT_US)
            check(index >= 0) { "No input buffer for the codec configuration" }
            checkNotNull(created.getInputBuffer(index)).put(config.csd)
            created.queueInputBuffer(index, 0, config.csd.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            created
        } catch (e: Exception) {
            // Codecs fail in many ways on real hardware; without one there is just no picture.
            Log.e(TAG, "Cannot create the decoder", e)
            releaseDecoder(codec)
            null
        }
    }

    private fun releaseDecoder(decoder: MediaCodec?) {
        if (decoder == null) return
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
    }

    private fun drainOutput(decoder: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            if (index >= 0) {
                decoder.releaseOutputBuffer(index, true)
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return
            }
        }
    }

    /** False if the decoder has no room yet. */
    private fun queueFrame(decoder: MediaCodec, frame: AssembledFrame): Boolean {
        val index = decoder.dequeueInputBuffer(0)
        if (index < 0) return false
        val input = decoder.getInputBuffer(index) ?: return false
        input.clear()
        input.put(frame.data)
        val flags = if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        decoder.queueInputBuffer(index, 0, frame.data.size, frame.ptsMs * 1000, flags)
        return true
    }
}
