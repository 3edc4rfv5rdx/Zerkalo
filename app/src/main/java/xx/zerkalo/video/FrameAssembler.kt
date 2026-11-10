package xx.zerkalo.video

import xx.zerkalo.MAX_PARTIAL_FRAMES
import xx.zerkalo.VIDEO_HEADER_SIZE
import xx.zerkalo.net.VideoHeader
import java.util.TreeMap

class AssembledFrame(val configId: Int, val keyframe: Boolean, val ptsMs: Long, val data: ByteArray)

/**
 * Puts datagrams back together into frames of one configuration. A frame finishing
 * makes every older unfinished one obsolete. Not thread-safe: one thread feeds it.
 */
class FrameAssembler {
    private class Partial(val count: Int, val keyframe: Boolean, val ptsMs: Long) {
        val parts = arrayOfNulls<ByteArray>(count)
        var received = 0
    }

    private val partials = TreeMap<Long, Partial>()
    private var lastDelivered = -1L

    /** Takes one datagram; returns the frame if this was its last missing piece. */
    fun add(header: VideoHeader, datagram: ByteArray, length: Int): AssembledFrame? {
        if (header.frameId <= lastDelivered) return null
        val partial = partials.getOrPut(header.frameId) {
            Partial(header.fragCount, header.keyframe, header.ptsMs)
        }
        if (partial.count != header.fragCount || partial.parts[header.fragIndex] != null) return null
        partial.parts[header.fragIndex] = datagram.copyOfRange(VIDEO_HEADER_SIZE, length)
        partial.received++
        if (partial.received < partial.count) {
            if (partials.size > MAX_PARTIAL_FRAMES) partials.pollFirstEntry()
            return null
        }
        lastDelivered = header.frameId
        partials.headMap(header.frameId, true).clear()
        return join(header.configId, partial)
    }

    private fun join(configId: Int, partial: Partial): AssembledFrame {
        val pieces = partial.parts.requireNoNulls()
        val data = ByteArray(pieces.sumOf { it.size })
        var offset = 0
        for (piece in pieces) {
            piece.copyInto(data, offset)
            offset += piece.size
        }
        return AssembledFrame(configId, partial.keyframe, partial.ptsMs, data)
    }
}
