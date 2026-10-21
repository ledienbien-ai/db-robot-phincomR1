package info.dourok.voicebot.mcp

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Just enough HTTP/1.1 for the MCP client tests. Unit tests compile against android.jar, which has
 * no com.sun.net.httpserver, so this is built on ServerSocket.
 */
class FakeHttpServer : AutoCloseable {

    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    class Response(private val out: OutputStream) {
        fun send(code: Int, body: String = "", type: String = "application/json", extra: Map<String, String> = emptyMap()) {
            val bytes = body.toByteArray()
            val head = StringBuilder("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
            extra.forEach { (k, v) -> head.append("$k: $v\r\n") }
            out.write(head.append("\r\n").toString().toByteArray())
            out.write(bytes)
            out.flush()
        }

        /** Start an event stream; write events with [event], the connection closes when the handler returns. */
        fun startEvents() {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        }

        fun event(text: String) {
            out.write(text.toByteArray())
            out.flush()
        }
    }

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val routes = mutableMapOf<String, (Request, Response) -> Unit>()
    val base get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val s = try { socket.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    fun route(path: String, handler: (Request, Response) -> Unit) {
        routes[path] = handler
    }

    private fun serve(s: Socket) = s.use {
        val input = BufferedInputStream(s.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val (method, target) = requestLine.split(" ").let { it[0] to it[1] }
        val path = target.substringBefore('?')
        val handler = routes[path]
        val response = Response(s.getOutputStream())
        if (handler == null) response.send(404) else handler(Request(method, path, headers, String(body)), response)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    override fun close() = socket.close()
}
