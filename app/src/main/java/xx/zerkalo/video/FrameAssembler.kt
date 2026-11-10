package xx.zerkalo.video

import xx.zerkalo.FRAME_DEADLINE_MS
import xx.zerkalo.MAX_NACKS_PER_FRAME
import xx.zerkalo.MAX_NACK_FRAGMENTS
import xx.zerkalo.MAX_PARTIAL_FRAMES
import xx.zerkalo.NACK_DELAY_MS
import xx.zerkalo.NACK_INTERVAL_MS
import xx.zerkalo.VIDEO_HEADER_SIZE
import xx.zerkalo.net.Nack
import xx.zerkalo.net.VideoHeader
import java.util.TreeMap

class AssembledFrame(val configId: Int, val keyframe: Boolean, val ptsMs: Long, val data: ByteArray)

/**
 * Puts datagrams back together into frames of one configuration and hands them out in
 * order. Decoding depends on every frame since the last key frame, so a frame that cannot
 * be completed in time throws the stream back to waiting for a key frame.
 * Not thread-safe: one thread feeds it.
 */
class FrameAssembler {
    /** A frame on its way; [parts] is null while it is only known from a gap in the numbers. */
    private class Partial(val firstSeen: Long) {
        var parts: Array<ByteArray?>? = null
        var configId = 0
        var keyframe = false
        var ptsMs = 0L
        var received = 0
        var lastHeard = firstSeen
        var lastNack = NEVER
        var nacks = 0

        val complete: Boolean
            get() = parts?.size == received
    }

    sealed interface Step {
        data object Idle : Step

        /** A frame was given up on; the stream now waits for a key frame. */
        data object Lost : Step

        class Ready(val frame: AssembledFrame) : Step
    }

    private val frames = TreeMap<Long, Partial>()

    /** The next frame to hand out, or [NEVER] while waiting for a key frame. */
    private var base = NEVER
    private var highest = NEVER

    /** Frames up to here belong to a stretch already given up on. */
    private var floor = NEVER

    val waitingForKey: Boolean
        get() = base == NEVER

    fun add(header: VideoHeader, datagram: ByteArray, length: Int, now: Long) {
        val id = header.frameId
        if (id <= floor || (base != NEVER && id < base)) return
        if (base == NEVER) {
            if (!header.keyframe) return
            base = id
            highest = id - 1
        }
        if (id > highest) {
            // Each number skipped is a frame lost entirely, or still on its way.
            if (id - highest > MAX_PARTIAL_FRAMES) return resync()
            for (skipped in highest + 1 until id) frames[skipped] = Partial(now)
            highest = id
        }
        val partial = frames.getOrPut(id) { Partial(now) }
        val parts = partial.parts ?: arrayOfNulls<ByteArray>(header.fragCount).also {
            partial.parts = it
            partial.configId = header.configId
            partial.keyframe = header.keyframe
            partial.ptsMs = header.ptsMs
        }
        if (parts.size != header.fragCount || parts[header.fragIndex] != null) return
        parts[header.fragIndex] = datagram.copyOfRange(VIDEO_HEADER_SIZE, length)
        partial.received++
        partial.lastHeard = now
        if (frames.size > MAX_PARTIAL_FRAMES) resync()
    }

    /** The next thing to act on. Call until it says [Step.Idle]. */
    fun next(now: Long): Step {
        if (base == NEVER) return Step.Idle
        val partial = frames[base] ?: return Step.Idle
        if (partial.complete) {
            frames.remove(base)
            base++
            return Step.Ready(join(partial))
        }
        if (now - partial.firstSeen >= FRAME_DEADLINE_MS) {
            resync()
            return Step.Lost
        }
        return Step.Idle
    }

    /** Requests for whatever has gone missing and is still worth asking for again. */
    fun nacks(now: Long): List<Nack> {
        val requests = ArrayList<Nack>()
        for ((id, partial) in frames) {
            if (partial.complete || partial.nacks >= MAX_NACKS_PER_FRAME) continue
            if (now - partial.lastHeard < NACK_DELAY_MS) continue
            if (partial.lastNack != NEVER && now - partial.lastNack < NACK_INTERVAL_MS) continue
            partial.lastNack = now
            partial.nacks++
            // Of a frame known only from the gap, the first fragment tells how many there are.
            val holes = partial.parts?.indices?.filter { partial.parts?.get(it) == null } ?: listOf(0)
            holes.chunked(MAX_NACK_FRAGMENTS).forEach { requests += Nack(id, it.toIntArray()) }
        }
        return requests
    }

    /** Abandons everything so far and waits for a key frame newer than anything seen. */
    fun resync() {
        frames.clear()
        base = NEVER
        floor = maxOf(floor, highest)
    }

    private fun join(partial: Partial): AssembledFrame {
        val pieces = checkNotNull(partial.parts).requireNoNulls()
        val data = ByteArray(pieces.sumOf { it.size })
        var offset = 0
        for (piece in pieces) {
            piece.copyInto(data, offset)
            offset += piece.size
        }
        return AssembledFrame(partial.configId, partial.keyframe, partial.ptsMs, data)
    }

    private companion object {
        const val NEVER = -1L
    }
}
