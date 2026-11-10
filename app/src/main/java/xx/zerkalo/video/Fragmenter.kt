package xx.zerkalo.video

import xx.zerkalo.MAX_DATAGRAM
import xx.zerkalo.MAX_VIDEO_PAYLOAD
import xx.zerkalo.PACKETS_PER_BURST
import xx.zerkalo.RETRANSMIT_FRAMES
import xx.zerkalo.VIDEO_HEADER_SIZE
import xx.zerkalo.net.VideoHeader

/** One encoded frame as it went out. */
class SentFrame(val data: ByteArray, val keyframe: Boolean, val configId: Int, val frameId: Long, val ptsMs: Long) {
    val fragmentCount: Int
        get() = (data.size + MAX_VIDEO_PAYLOAD - 1) / MAX_VIDEO_PAYLOAD
}

/**
 * Cuts encoded frames into datagrams. [send] gets a buffer that is reused for the next
 * packet, so it must finish with it before returning. [pause] runs between bursts.
 * One instance belongs to one thread.
 */
class Fragmenter(private val send: (ByteArray, Int) -> Unit, private val pause: () -> Unit) {
    private val packet = ByteArray(MAX_DATAGRAM)

    fun sendFrame(frame: SentFrame) {
        val count = frame.fragmentCount
        for (index in 0 until count) {
            sendFragment(frame, index, count)
            if ((index + 1) % PACKETS_PER_BURST == 0 && index + 1 < count) pause()
        }
    }

    /** Repeats one fragment; an index the frame does not have is ignored, as the network chose it. */
    fun resend(frame: SentFrame, index: Int) {
        val count = frame.fragmentCount
        if (index < count) sendFragment(frame, index, count)
    }

    private fun sendFragment(frame: SentFrame, index: Int, count: Int) {
        val start = index * MAX_VIDEO_PAYLOAD
        val size = minOf(MAX_VIDEO_PAYLOAD, frame.data.size - start)
        VideoHeader(frame.keyframe, frame.configId, frame.frameId, index, count, frame.ptsMs).writeTo(packet)
        frame.data.copyInto(packet, VIDEO_HEADER_SIZE, start, start + size)
        send(packet, VIDEO_HEADER_SIZE + size)
    }
}

/** The latest frames sent, for repeating lost packets. Used by the sending and the NACK threads. */
class RetransmitBuffer(private val capacity: Int = RETRANSMIT_FRAMES) {
    private val frames = LinkedHashMap<Long, SentFrame>()

    @Synchronized
    fun put(frame: SentFrame) {
        frames[frame.frameId] = frame
        if (frames.size > capacity) frames.remove(frames.keys.first())
    }

    @Synchronized
    fun get(frameId: Long): SentFrame? = frames[frameId]
}
