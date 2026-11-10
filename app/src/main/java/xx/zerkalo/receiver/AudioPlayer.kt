package xx.zerkalo.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import xx.zerkalo.AUDIO_MAX_LATE_MS
import xx.zerkalo.AUDIO_MAX_QUEUE_MS
import xx.zerkalo.AUDIO_QUEUE_PACKETS
import xx.zerkalo.net.AudioPacket
import xx.zerkalo.net.ControlMessage
import xx.zerkalo.video.PlayoutClock
import xx.zerkalo.video.audioWaitMs
import xx.zerkalo.video.monotonicMs
import xx.zerkalo.video.outputQueueMs
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "AudioPlayer"
private const val POLL_MS = 5L
private const val INPUT_TIMEOUT_US = 10_000L
private const val STOP_WAIT_MS = 500L
private const val US_PER_MS = 1_000L
private const val MS_PER_S = 1_000
private const val BYTES_PER_SAMPLE = 2
private const val TRACK_BUFFER_MS = 60
private const val STATS_INTERVAL_MS = 2_000L
private const val MAX_WAIT_MS = 500L
private const val NANOS_PER_MS = 1_000_000L

/**
 * Plays the sender's sound on the picture's clock, so the two stay together. Takes its
 * timing from frames of video only: sound arrives sooner than a picture does, and would
 * otherwise eat up the delay the picture needs.
 */
class AudioPlayer(private val clock: PlayoutClock, private val autoSync: Boolean, private val offsetMs: Int) {
    private val packets = ArrayBlockingQueue<AudioPacket>(AUDIO_QUEUE_PACKETS)

    @Volatile
    private var stopped = false

    @Volatile
    private var config: ControlMessage.AudioConfig? = null

    /** The receive thread's own: each frame arrives twice. */
    private var lastSeq = 0L

    private var worker: Thread? = null

    // The play thread's own, for the log.
    private var dropped = 0
    private var lastStats = 0L

    fun start() {
        worker = thread(isDaemon = true, name = "audio-play") { run() }
    }

    fun stop() {
        stopped = true
        worker?.join(STOP_WAIT_MS)
    }

    fun onConfig(message: ControlMessage.AudioConfig) {
        config = message
    }

    fun offer(packet: AudioPacket) {
        if (packet.seq <= lastSeq) return
        lastSeq = packet.seq
        packets.offer(packet)
    }

    private fun run() {
        try {
            val current = awaitConfig() ?: return
            val decoder = createDecoder(current)
            try {
                val track = createTrack(current)
                try {
                    playLoop(decoder, track, current)
                } finally {
                    runCatching { track.stop() }
                    track.release()
                }
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "Interrupted", e)
        } catch (e: Exception) {
            // Audio and codecs fail in many ways on real hardware; each one just means no sound.
            Log.w(TAG, "Sound playback ended", e)
        }
    }

    private fun awaitConfig(): ControlMessage.AudioConfig? {
        while (!stopped) {
            config?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    private fun createDecoder(current: ControlMessage.AudioConfig): MediaCodec {
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, current.sampleRate, current.channels)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(current.csd))
        val created = MediaCodec.createDecoderByType(mime)
        try {
            created.configure(format, null, null, 0)
            created.start()
            return created
        } catch (e: Exception) {
            created.release()
            throw e
        }
    }

    private fun createTrack(current: ControlMessage.AudioConfig): AudioTrack {
        val mask = if (current.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(current.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val wanted = bytesFor(TRACK_BUFFER_MS.toLong(), current)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(current.sampleRate)
                    .setChannelMask(mask)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimum, wanted))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        return track
    }

    private fun bytesFor(millis: Long, current: ControlMessage.AudioConfig): Int =
        (millis * current.sampleRate / MS_PER_S).toInt() * current.channels * BYTES_PER_SAMPLE

    private fun playLoop(decoder: MediaCodec, track: AudioTrack, current: ControlMessage.AudioConfig) {
        val bytesPerFrame = current.channels * BYTES_PER_SAMPLE
        // A track stays silent until its buffer has been full once, so it is filled at the
        // start: otherwise that wait would become a lasting delay of the whole sound.
        val silence = ByteArray(track.bufferSizeInFrames * bytesPerFrame)
        var writtenBytes = maxOf(track.write(silence, 0, silence.size), 0).toLong()
        val bufferMs = track.bufferSizeInFrames * 1000L / current.sampleRate
        val stamp = AudioTimestamp()
        val info = MediaCodec.BufferInfo()
        var pcm = ByteArray(0)
        while (!stopped) {
            packets.poll(POLL_MS, TimeUnit.MILLISECONDS)?.let { feed(decoder, it) }
            while (true) {
                val index = decoder.dequeueOutputBuffer(info, 0)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (index < 0) continue
                val buffer = decoder.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    if (pcm.size < info.size) pcm = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(pcm, 0, info.size)
                    val queueMs = measuredQueueMs(track, stamp, writtenBytes / bytesPerFrame, current.sampleRate) ?: bufferMs
                    if (waitForTurn(info.presentationTimeUs / US_PER_MS, queueMs)) {
                        writtenBytes += maxOf(track.write(pcm, 0, info.size), 0)
                    }
                }
                decoder.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun feed(decoder: MediaCodec, packet: AudioPacket) {
        val index = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) return
        val input = decoder.getInputBuffer(index) ?: return
        input.clear()
        input.put(packet.data)
        decoder.queueInputBuffer(index, 0, packet.data.size, packet.ptsMs * US_PER_MS, 0)
    }

    private fun logStats(wait: Long, queueMs: Long) {
        val now = monotonicMs()
        if (now - lastStats < STATS_INTERVAL_MS) return
        lastStats = now
        Log.i(TAG, "wait=$wait queue=$queueMs dropped=$dropped auto=$autoSync offset=$offsetMs")
        dropped = 0
    }

    /** How long sound written now takes to be heard, when the system can say and is asked to. */
    private fun measuredQueueMs(track: AudioTrack, stamp: AudioTimestamp, writtenFrames: Long, sampleRate: Int): Long? {
        if (!autoSync || !track.getTimestamp(stamp)) return null
        val queue = outputQueueMs(writtenFrames, stamp.framePosition, stamp.nanoTime / NANOS_PER_MS, monotonicMs(), sampleRate)
        return queue.takeIf { it <= AUDIO_MAX_QUEUE_MS }
    }

    /** Sleeps until the sound stamped [ptsMs] should be written; false if it should be dropped. */
    private fun waitForTurn(ptsMs: Long, queueMs: Long): Boolean {
        val target = clock.targetMs(ptsMs) ?: return false
        val wait = audioWaitMs(target, monotonicMs(), queueMs, offsetMs)
        logStats(wait, queueMs)
        if (wait < -AUDIO_MAX_LATE_MS) {
            dropped++
            return false
        }
        if (wait > 0) Thread.sleep(minOf(wait, MAX_WAIT_MS))
        return true
    }
}
