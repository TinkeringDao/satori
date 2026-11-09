package com.satori.data.datasource

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * An HTTP/1.1 server on the loopback address for the download tests, one request per connection,
 * which answers each request as [answer] says, each from a thread of its own. The JDK's own server
 * is not on the Android classpath the tests compile against.
 */
internal class TestFileServer(private val answer: (Request) -> Answer) : AutoCloseable {

    /** A request: its method, path and Range header, if any. */
    data class Request(val method: String, val path: String, val range: String?)

    /** What happens to the connection once an answer's body is sent. */
    enum class End {
        /** It closes. */
        CLOSE,

        /** It stays open and sends nothing more, for [Answer.stallMs], as a network that hangs. */
        STALL,

        /** It is reset, as by a router that drops it. */
        RESET
    }

    /**
     * A response.
     *
     * @property declaresLength false sends no length, so [body] ends where the connection does
     * @property length The length sent instead of [body]'s: a body cut short on purpose
     * @property pieceBytes A long body goes out in pieces of this size, each after [pieceDelayMs]
     */
    class Answer(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
        val declaresLength: Boolean = true,
        val length: Long? = null,
        val end: End = End.CLOSE,
        val stallMs: Long = 0L,
        val pieceBytes: Int = Int.MAX_VALUE,
        val pieceDelayMs: Long = 0L
    )

    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    val port: Int get() = socket.localPort

    init {
        Thread { serve() }.apply { isDaemon = true }.start()
    }

    private fun serve() {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                return
            }
            // Each connection on a thread of its own: an answer that hangs holds up no other.
            Thread {
                try {
                    handle(client)
                } catch (e: IOException) {
                    // The client went away mid-answer, as a paused download does.
                } finally {
                    runCatching { client.close() }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val parts = requestLine.split(' ')
        val response = answer(Request(parts[0], parts[1], headers["range"]))
        val head = buildString {
            append("HTTP/1.1 ${response.status} Answer\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            if (response.declaresLength) append("Content-Length: ${response.length ?: response.body.size.toLong()}\r\n")
            append("Connection: close\r\n\r\n")
        }
        val output = client.getOutputStream()
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (parts[0] != "HEAD") {
            var sent = 0
            while (sent < response.body.size) {
                val piece = minOf(response.pieceBytes, response.body.size - sent)
                output.write(response.body, sent, piece)
                output.flush()
                sent += piece
                if (response.pieceDelayMs > 0 && sent < response.body.size) Thread.sleep(response.pieceDelayMs)
            }
        }
        output.flush()
        when (response.end) {
            End.CLOSE -> Unit
            End.STALL -> Thread.sleep(response.stallMs)
            End.RESET -> client.setSoLinger(true, 0)
        }
    }

    override fun close() = socket.close()
}
