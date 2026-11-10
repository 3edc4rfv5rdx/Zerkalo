package xx.zerkalo.net

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.Socket

class FileServerTest {
    private val content = ByteArray(200_000) { (it % 251).toByte() }
    private val server = FileServer(
        port = 0,
        token = "ab12",
        file = ServedFile(content.size.toLong(), "video/mp4") { offset ->
            ByteArrayInputStream(content, offset.toInt(), content.size - offset.toInt())
        },
    )

    private class Reply(val head: String, val body: ByteArray)

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.stop()

    private fun request(line: String, vararg headers: String): Reply {
        Socket("127.0.0.1", server.boundPort).use { socket ->
            val text = (listOf(line) + headers + "" + "").joinToString("\r\n")
            socket.getOutputStream().write(text.toByteArray())
            val all = socket.getInputStream().readBytes()
            var split = 0
            while (!(all[split] == CR && all[split + 1] == LF && all[split + 2] == CR && all[split + 3] == LF)) split++
            return Reply(String(all, 0, split, Charsets.ISO_8859_1), all.copyOfRange(split + 4, all.size))
        }
    }

    @Test
    fun wholeFileIsServed() {
        val reply = request("GET /ab12/film.mp4 HTTP/1.1")
        assertTrue(reply.head, reply.head.startsWith("HTTP/1.1 200"))
        assertTrue(reply.head, "Accept-Ranges: bytes" in reply.head)
        assertTrue(reply.head, "Content-Type: video/mp4" in reply.head)
        assertArrayEquals(content, reply.body)
    }

    @Test
    fun rangeIsServed() {
        val reply = request("GET /ab12/film.mp4 HTTP/1.1", "range: bytes=100-199")
        assertTrue(reply.head, reply.head.startsWith("HTTP/1.1 206"))
        assertTrue(reply.head, "Content-Range: bytes 100-199/200000" in reply.head)
        assertArrayEquals(content.copyOfRange(100, 200), reply.body)
    }

    @Test
    fun rangePastTheEndIsRefused() {
        val reply = request("GET /ab12/film.mp4 HTTP/1.1", "Range: bytes=200000-")
        assertTrue(reply.head, reply.head.startsWith("HTTP/1.1 416"))
        assertTrue(reply.head, "Content-Range: bytes */200000" in reply.head)
    }

    @Test
    fun wrongTokenIsNotFound() {
        assertTrue(request("GET /ffff/film.mp4 HTTP/1.1").head.startsWith("HTTP/1.1 404"))
    }

    @Test
    fun tokenMustBeAWholePathSegment() {
        assertTrue(request("GET /ab123/film.mp4 HTTP/1.1").head.startsWith("HTTP/1.1 404"))
    }

    @Test
    fun headHasNoBody() {
        val reply = request("HEAD /ab12/film.mp4 HTTP/1.1")
        assertTrue(reply.head, "Content-Length: 200000" in reply.head)
        assertEquals(0, reply.body.size)
    }

    @Test
    fun otherMethodsAreRefused() {
        assertTrue(request("POST /ab12/film.mp4 HTTP/1.1").head.startsWith("HTTP/1.1 405"))
    }

    @Test
    fun rangesAreParsed() {
        assertEquals(ByteRange.Part(0, 99), parseRange("bytes=0-", 100))
        assertEquals(ByteRange.Part(10, 20), parseRange("bytes=10-20", 100))
        assertEquals(ByteRange.Part(90, 99), parseRange("bytes=90-500", 100))
        assertEquals(ByteRange.Part(90, 99), parseRange("bytes=-10", 100))
        assertEquals(ByteRange.Part(0, 99), parseRange("bytes=-500", 100))
    }

    @Test
    fun oddRangesFallBackToTheWholeFile() {
        assertEquals(ByteRange.Whole, parseRange(null, 100))
        assertEquals(ByteRange.Whole, parseRange("items=0-1", 100))
        assertEquals(ByteRange.Whole, parseRange("bytes=0-1,5-6", 100))
        assertEquals(ByteRange.Whole, parseRange("bytes=20-10", 100))
        assertEquals(ByteRange.Whole, parseRange("bytes=x-y", 100))
    }

    @Test
    fun impossibleRangesAreUnsatisfiable() {
        assertEquals(ByteRange.Unsatisfiable, parseRange("bytes=100-", 100))
        assertEquals(ByteRange.Unsatisfiable, parseRange("bytes=-0", 100))
    }

    private companion object {
        const val CR: Byte = 13
        const val LF: Byte = 10
    }
}
