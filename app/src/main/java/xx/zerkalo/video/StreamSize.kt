package xx.zerkalo.video

import xx.zerkalo.MAX_STREAM_HEIGHT
import xx.zerkalo.MAX_STREAM_WIDTH
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class StreamSize(val width: Int, val height: Int)

/**
 * A screen of [screenWidth] by [screenHeight] fitted inside the largest stream size with
 * its proportions kept, never enlarged, and each side a multiple of its codec alignment.
 */
fun fitStreamSize(screenWidth: Int, screenHeight: Int, widthAlignment: Int, heightAlignment: Int): StreamSize {
    val scale = min(1.0, min(MAX_STREAM_WIDTH.toDouble() / screenWidth, MAX_STREAM_HEIGHT.toDouble() / screenHeight))
    fun fit(side: Int, alignment: Int): Int {
        // Video codecs need even sides, whatever alignment they report.
        val step = max(alignment, 2)
        return max(step, (side * scale).roundToInt() / step * step)
    }
    return StreamSize(fit(screenWidth, widthAlignment), fit(screenHeight, heightAlignment))
}
