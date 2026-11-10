package xx.zerkalo.sender

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import xx.zerkalo.CODEC_H264
import xx.zerkalo.DEFAULT_BIT_RATE
import xx.zerkalo.DEFAULT_FRAME_RATE
import xx.zerkalo.KEY_FRAME_INTERVAL_S
import xx.zerkalo.MAX_DATAGRAM
import xx.zerkalo.REPEAT_FRAME_AFTER_US
import xx.zerkalo.VIDEO_PORT
import xx.zerkalo.net.ControlChannel
import xx.zerkalo.net.ControlMessage
import xx.zerkalo.net.Nack
import xx.zerkalo.video.Fragmenter
import xx.zerkalo.video.RetransmitBuffer
import xx.zerkalo.video.SentFrame
import xx.zerkalo.video.StreamSize
import xx.zerkalo.video.fitStreamSize
import xx.zerkalo.video.mimeOf
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

private const val TAG = "ScreenEncoder"
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val STOP_WAIT_MS = 1_000L
private const val PTS_MASK = 0xFFFF_FFFFL

/** Captures the screen, encodes it and sends it to the far end of [channel]. */
class ScreenEncoder(
    private val context: Context,
    private val projection: MediaProjection,
    private val channel: ControlChannel,
    private val onFailed: () -> Unit,
) {
    /** One encoder instance; a new one comes with every change of the screen's shape. */
    private class Active(val codec: MediaCodec, val surface: Surface, val size: StreamSize, val screen: DisplayMetrics)

    private val displays = context.getSystemService(DisplayManager::class.java)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) screenChanged = true
        }
    }

    @Volatile
    private var stopped = false

    @Volatile
    private var screenChanged = false

    /** The configuration the receiver has said it can decode. No frame is sent before that. */
    @Volatile
    private var readyConfig = -1

    @Volatile
    private var codec: MediaCodec? = null

    private val sent = RetransmitBuffer()
    private var worker: Thread? = null

    // Touched by the worker thread only; they run on across encoder restarts.
    private var configId = 0
    private var frameId = 0L
    private var firstPtsUs = -1L

    fun start() {
        displays.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        worker = thread(name = "screen-encoder") { run() }
    }

    fun stop() {
        stopped = true
        displays.unregisterDisplayListener(displayListener)
        worker?.join(STOP_WAIT_MS)
    }

    /** The receiver is set up for [configId], and needs a key frame to begin with. */
    fun onReady(configId: Int) {
        readyConfig = configId
        requestKeyFrame()
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Key frame request while the codec is stopping", e)
        }
    }

    private fun run() {
        var display: VirtualDisplay? = null
        var active: Active? = null
        try {
            val address = InetAddress.getByName(channel.remoteHost)
            DatagramSocket().use { socket ->
                startNackListener(socket, address)
                val fragmenter = fragmenterFor(socket, address, pause = { Thread.sleep(1) })
                while (!stopped) {
                    val next = openEncoder()
                    active = next
                    codec = next.codec
                    val existing = display
                    if (existing == null) {
                        display = createDisplay(next)
                    } else {
                        // The projection allows one virtual display, so it is reshaped, not replaced.
                        existing.resize(next.size.width, next.size.height, next.screen.densityDpi)
                        existing.surface = next.surface
                    }
                    sendLoop(next, fragmenter)
                    codec = null
                    display?.surface = null
                    closeEncoder(next)
                    active = null
                }
            }
        } catch (e: Exception) {
            // Codecs and sockets fail in many ways on real hardware; each one ends the session the same.
            Log.e(TAG, "Encoding failed", e)
            if (!stopped) onFailed()
        } finally {
            codec = null
            display?.release()
            active?.let(::closeEncoder)
        }
    }

    private fun openEncoder(): Active {
        val mime = mimeOf(CODEC_H264)
        val created = MediaCodec.createEncoderByType(mime)
        try {
            val capabilities = created.codecInfo.getCapabilitiesForType(mime)
            val screen = screenMetrics()
            val video = checkNotNull(capabilities.videoCapabilities) { "Not a video codec" }
            val size = fitStreamSize(screen.widthPixels, screen.heightPixels, video.widthAlignment, video.heightAlignment)
            val format = MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, DEFAULT_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_S)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_AFTER_US)
                val vbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                if (capabilities.encoderCapabilities?.isBitrateModeSupported(vbr) == true) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, vbr)
                }
            }
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = created.createInputSurface()
            created.start()
            return Active(created, surface, size, screen)
        } catch (e: Exception) {
            created.release()
            throw e
        }
    }

    private fun closeEncoder(encoder: Active) {
        runCatching { encoder.codec.stop() }
        encoder.codec.release()
        encoder.surface.release()
    }

    private fun createDisplay(encoder: Active): VirtualDisplay = projection.createVirtualDisplay(
        "zerkalo",
        encoder.size.width,
        encoder.size.height,
        encoder.screen.densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        encoder.surface,
        null,
        null,
    )

    private fun fragmenterFor(socket: DatagramSocket, address: InetAddress, pause: () -> Unit): Fragmenter {
        val datagram = DatagramPacket(ByteArray(MAX_DATAGRAM), MAX_DATAGRAM, address, VIDEO_PORT)
        return Fragmenter(
            send = { bytes, length ->
                datagram.setData(bytes, 0, length)
                socket.send(datagram)
            },
            pause = pause,
        )
    }

    /** Repeats what the receiver reports lost. Ends by itself when the socket is closed. */
    private fun startNackListener(socket: DatagramSocket, address: InetAddress) {
        thread(isDaemon = true, name = "nack-listener") {
            val resender = fragmenterFor(socket, address, pause = {})
            val buffer = ByteArray(MAX_DATAGRAM)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                while (true) {
                    packet.length = buffer.size
                    socket.receive(packet)
                    if (packet.address != address) continue
                    val nack = Nack.parse(buffer, packet.length) ?: continue
                    val frame = sent.get(nack.frameId) ?: continue
                    nack.fragments.forEach { resender.resend(frame, it) }
                }
            } catch (ignored: IOException) {
                // The socket was closed: the session is over.
            }
        }
    }

    /** Runs until the encoder has to be replaced or the session stops. */
    private fun sendLoop(encoder: Active, fragmenter: Fragmenter) {
        val info = MediaCodec.BufferInfo()
        while (!stopped) {
            if (screenChanged) {
                screenChanged = false
                val screen = screenMetrics()
                if (screen.widthPixels != encoder.screen.widthPixels || screen.heightPixels != encoder.screen.heightPixels) return
            }
            val index = encoder.codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            if (index < 0) continue
            val buffer = encoder.codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    configId++
                    channel.send(ControlMessage.Config(configId, CODEC_H264, encoder.size.width, encoder.size.height, bytes))
                } else if (readyConfig == configId) {
                    if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                    val ptsMs = (info.presentationTimeUs - firstPtsUs) / 1000 and PTS_MASK
                    val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val frame = SentFrame(bytes, keyframe, configId, frameId++, ptsMs)
                    sent.put(frame)
                    fragmenter.sendFrame(frame)
                }
            }
            encoder.codec.releaseOutputBuffer(index, false)
        }
    }

    private fun screenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        displays.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return metrics
    }
}
