package xx.zerkalo.video

import xx.zerkalo.MAX_DATAGRAM
import xx.zerkalo.MAX_VIDEO_PAYLOAD
import xx.zerkalo.PACKETS_PER_BURST
import xx.zerkalo.VIDEO_HEADER_SIZE
import xx.zerkalo.net.VideoHeader

/**
 * Cuts encoded frames into datagrams. [send] gets a buffer that is reused for the next
 * packet, so it must finish with it before returning. [pause] runs between bursts.
 */
class Fragmenter(private val send: (ByteArray, Int) -> Unit, private val pause: () -> Unit) {
    private val packet = ByteArray(MAX_DATAGRAM)

    fun sendFrame(frame: ByteArray, keyframe: Boolean, configId: Int, frameId: Long, ptsMs: Long) {
        val count = (frame.size + MAX_VIDEO_PAYLOAD - 1) / MAX_VIDEO_PAYLOAD
        for (index in 0 until count) {
            val start = index * MAX_VIDEO_PAYLOAD
            val size = minOf(MAX_VIDEO_PAYLOAD, frame.size - start)
            VideoHeader(keyframe, configId, frameId, index, count, ptsMs).writeTo(packet)
            frame.copyInto(packet, VIDEO_HEADER_SIZE, start, start + size)
            send(packet, VIDEO_HEADER_SIZE + size)
            if ((index + 1) % PACKETS_PER_BURST == 0 && index + 1 < count) pause()
        }
    }
}
