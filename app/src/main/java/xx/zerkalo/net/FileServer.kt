package xx.zerkalo.net

import xx.zerkalo.HTTP_COPY_BUFFER
import xx.zerkalo.HTTP_MAX_HEAD_BYTES
import xx.zerkalo.HTTP_READ_TIMEOUT_MS
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** A file to serve. [open] gives a stream positioned at the offset, and the caller closes it. */
class ServedFile(val size: Long, val mime: String, val open: (Long) -> InputStream)

sealed interface ByteRange {
    data object Whole : ByteRange

    data object Unsatisfiable : ByteRange

    data class Part(val first: Long, val last: Long) : ByteRange
}

/**
 * The part of a file of [size] bytes that a Range header asks for. A header this server
 * does not understand, or one naming several ranges, is answered with the whole file,
 * which HTTP allows.
 */
fun parseRange(header: String?, size: Long): ByteRange {
    if (header == null) return ByteRange.Whole
    val spec = header.trim()
    if (!spec.startsWith("bytes=", ignoreCase = true)) return ByteRange.Whole
    val value = spec.substring("bytes=".length).trim()
    val dash = value.indexOf('-')
    if (dash < 0 || ',' in value) return ByteRange.Whole
    val from = value.substring(0, dash).trim()
    val to = value.substring(dash + 1).trim()
    if (from.isEmpty()) {
        val count = to.toLongOrNull() ?: return ByteRange.Whole
        return if (count <= 0 || size == 0L) ByteRange.Unsatisfiable else ByteRange.Part(maxOf(size - count, 0), size - 1)
    }
    val first = from.toLongOrNull() ?: return ByteRange.Whole
    if (first >= size) return ByteRange.Unsatisfiable
    val last = if (to.isEmpty()) size - 1 else to.toLongOrNull() ?: return ByteRange.Whole
    if (last < first) return ByteRange.Whole
    return ByteRange.Part(first, minOf(last, size - 1))
}

/**
 * Serves one file over HTTP at /[token]/anything, with the range requests players seek
 * with. Each connection gets its own thread, as a player opens several at once.
 */
class FileServer(private val port: Int, private val token: String, private val file: ServedFile) {
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var stopped = false

    /** The port actually listened on, once [start] has returned. */
    val boundPort: Int
        get() = server?.localPort ?: 0

    /** Binds the port, then accepts in the background. Blocking, and throws if the port is taken. */
    fun start() {
        val socket = ServerSocket(port)
        server = socket
        thread(isDaemon = true, name = "file-accept") { acceptLoop(socket) }
    }

    fun stop() {
        stopped = true
        runCatching { server?.close() }
        clients.forEach { runCatching { it.close() } }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!stopped) {
            val client = try {
                socket.accept()
            } catch (ignored: IOException) {
                return
            }
            clients += client
            thread(isDaemon = true, name = "file-client") {
                try {
                    client.use(::handle)
                } catch (ignored: Exception) {
                    // A player closing its connection, or the file going away: this one request ends.
                } finally {
                    clients -= client
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = HTTP_READ_TIMEOUT_MS
        val output = client.getOutputStream()
        val head = readHead(client.getInputStream()) ?: return respond(output, "400 Bad Request")
        val lines = head.split("\r\n")
        val request = lines.first().split(' ')
        if (request.size != 3) return respond(output, "400 Bad Request")
        val (method, target) = request
        if (method != "GET" && method != "HEAD") return respond(output, "405 Method Not Allowed")
        if (target != "/$token" && !target.startsWith("/$token/")) return respond(output, "404 Not Found")

        val rangeHeader = lines.drop(1).firstNotNullOfOrNull { line ->
            line.substringBefore(':', "").takeIf { it.equals("Range", ignoreCase = true) }?.let { line.substringAfter(':') }
        }
        when (val range = parseRange(rangeHeader, file.size)) {
            ByteRange.Unsatisfiable -> respond(output, "416 Range Not Satisfiable", "Content-Range: bytes */${file.size}\r\n")
            ByteRange.Whole -> send(output, method, "200 OK", 0, file.size - 1, "")
            is ByteRange.Part -> {
                val contentRange = "Content-Range: bytes ${range.first}-${range.last}/${file.size}\r\n"
                send(output, method, "206 Partial Content", range.first, range.last, contentRange)
            }
        }
    }

    /** The request up to its blank line, or null if it is too long or ends early. */
    private fun readHead(input: InputStream): String? {
        val buffer = input.buffered()
        val head = StringBuilder()
        while (head.length < HTTP_MAX_HEAD_BYTES) {
            val byte = buffer.read()
            if (byte < 0) return null
            head.append(byte.toChar())
            if (head.endsWith("\r\n\r\n")) return head.substring(0, head.length - HEAD_END_LENGTH)
        }
        return null
    }

    private fun respond(output: OutputStream, status: String, extra: String = "") {
        output.write("HTTP/1.1 $status\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun send(output: OutputStream, method: String, status: String, first: Long, last: Long, extra: String) {
        val length = last - first + 1
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: ${file.mime}\r\n" +
            "Content-Length: $length\r\n" +
            "Accept-Ranges: bytes\r\n" +
            extra +
            "Connection: close\r\n\r\n"
        if (method == "HEAD") {
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.flush()
            return
        }
        // Opened before the head goes out, so a file that cannot be read fails the connection cleanly.
        file.open(first).use { stream ->
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            val buffer = ByteArray(HTTP_COPY_BUFFER)
            var remaining = length
            while (remaining > 0) {
                val read = stream.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
            output.flush()
        }
    }

    private companion object {
        const val HEAD_END_LENGTH = 4
    }
}
