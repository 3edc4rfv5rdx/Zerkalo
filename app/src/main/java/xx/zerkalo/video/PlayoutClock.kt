package xx.zerkalo.video

import xx.zerkalo.PLAYOUT_DELAY_MS
import xx.zerkalo.PLAYOUT_WINDOW_MS

/** The clock both the frames' deadlines and their moment on screen are measured with. */
fun monotonicMs(): Long = System.nanoTime() / 1_000_000

/**
 * When to show each frame so that network jitter does not reach the screen. The two
 * devices' clocks are never compared: the smallest (arrival - timestamp) over a recent
 * window is the fastest the network has been, and frames are shown a fixed delay behind
 * that. Shared by the receiving and the decoding threads.
 */
class PlayoutClock {
    private class Sample(val arrivalMs: Long, val offsetMs: Long)

    /** Offsets rising from the front, so the front is always the window's smallest. */
    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun onFrame(ptsMs: Long, arrivalMs: Long) {
        val offset = arrivalMs - ptsMs
        while (samples.lastOrNull()?.let { it.offsetMs >= offset } == true) samples.removeLast()
        samples.addLast(Sample(arrivalMs, offset))
        while (samples.first().arrivalMs < arrivalMs - PLAYOUT_WINDOW_MS) samples.removeFirst()
    }

    /** The moment to show the frame stamped [ptsMs]; null before any frame has arrived. */
    @Synchronized
    fun targetMs(ptsMs: Long): Long? = samples.firstOrNull()?.let { ptsMs + it.offsetMs + PLAYOUT_DELAY_MS }
}
