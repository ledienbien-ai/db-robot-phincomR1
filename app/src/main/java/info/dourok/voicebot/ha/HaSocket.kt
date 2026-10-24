package info.dourok.voicebot.ha

import info.dourok.voicebot.data.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One connection to Home Assistant's WebSocket API (/api/websocket): authenticate with the
 * long-lived token, then send numbered commands and receive their results and events.
 *
 * Assist pipelines (listing them, and running one with the speaker's microphone) only exist on this
 * API, not on REST. The addresses are tried like [HomeAssistant]'s: home first, then the internet one.
 */
class HaSocket(private val listener: Listener) {

    interface Listener {
        fun onMessage(message: JSONObject)
        fun onClosed(reason: String)
    }

    private val ids = AtomicInteger(1)
    @Volatile private var ws: WebSocket? = null
    @Volatile var connected = false
        private set

    /** Connect and authenticate, trying the saved addresses in turn; throws with a sentence for people. */
    fun connect() {
        val bases = HomeAssistant.bases()
        if (bases.isEmpty()) throw HomeAssistant.HaException("Chưa nhập địa chỉ Home Assistant.")
        if (Settings.haToken.isBlank()) throw HomeAssistant.HaException("Chưa nhập token truy cập dài hạn của Home Assistant.")
        var last: Exception? = null
        for (b in bases) {
            try {
                open(b)
                return
            } catch (e: HomeAssistant.HaException) {
                if (e !is HomeAssistant.Unreachable) throw e
                last = e
            } catch (e: Exception) {
                last = e
            }
        }
        throw HomeAssistant.HaException("Không nối được Home Assistant: ${last?.message}")
    }

    private fun open(base: String) {
        val u = HomeAssistant.target(URL(base.trim().trimEnd('/') + "/api/websocket"))
        val wsUrl = (if (u.protocol == "https") "wss" else "ws") + "://" + u.host + (if (u.port > 0) ":${u.port}" else "") + u.path
        val authed = CountDownLatch(1)
        var failure: String? = null
        val socket = client().newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (o.optString("type")) {
                    "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", Settings.haToken.trim()).toString())
                    "auth_ok" -> { connected = true; authed.countDown() }
                    "auth_invalid" -> {
                        failure = "Home Assistant từ chối token (sai, hết hạn hoặc đã bị xoá)."
                        authed.countDown()
                        webSocket.close(1000, null)
                    }
                    else -> listener.onMessage(o)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val wasUp = connected
                connected = false
                if (failure == null) failure = t.message ?: t.javaClass.simpleName
                authed.countDown()
                if (wasUp) listener.onClosed(failure ?: "")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val wasUp = connected
                connected = false
                authed.countDown()
                if (wasUp) listener.onClosed(reason)
            }
        })
        ws = socket
        if (!authed.await(if (HomeAssistant.isLan(base)) 6 else 12, TimeUnit.SECONDS)) {
            socket.cancel()
            throw HomeAssistant.Unreachable("Home Assistant ở $base không trả lời qua WebSocket.")
        }
        if (!connected) {
            val f = failure ?: "lỗi"
            if (f.contains("token")) throw HomeAssistant.HaException(f)
            throw HomeAssistant.Unreachable("Không nối được WebSocket của Home Assistant ở $base: $f")
        }
    }

    /** An id for the next command, known before it is sent (its reply can come back at once). */
    fun nextId(): Int = ids.getAndIncrement()

    /** Send one command with [id] (from [nextId]). */
    fun send(command: JSONObject, id: Int = nextId()): Int {
        command.put("id", id)
        ws?.send(command.toString())
        return id
    }

    fun sendBinary(bytes: ByteArray): Boolean = ws?.send(bytes.toByteString()) ?: false

    fun close() {
        connected = false
        ws?.close(1000, null)
        ws = null
    }

    companion object {
        private var shared: OkHttpClient? = null

        @Synchronized
        fun client(): OkHttpClient {
            shared?.let { return it }
            val b = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
            val f = info.dourok.voicebot.net.Https.socketFactory()
            val t = info.dourok.voicebot.net.Https.trustManager()
            if (f != null && t != null) b.sslSocketFactory(f, t)
            return b.build().also { shared = it }
        }

        /** Connect, run one command, return its "result" message, close. For the panel's lists. */
        fun request(type: String, fields: JSONObject = JSONObject(), timeoutSec: Long = 10): JSONObject {
            val done = CountDownLatch(1)
            var result: JSONObject? = null
            var wanted = -1
            val s = HaSocket(object : Listener {
                override fun onMessage(message: JSONObject) {
                    if (message.optString("type") == "result" && message.optInt("id") == wanted) {
                        result = message
                        done.countDown()
                    }
                }
                override fun onClosed(reason: String) { done.countDown() }
            })
            try {
                s.connect()
                val cmd = JSONObject(fields.toString()).put("type", type)
                wanted = s.nextId()
                s.send(cmd, wanted)
                done.await(timeoutSec, TimeUnit.SECONDS)
            } finally {
                s.close()
            }
            val r = result ?: throw HomeAssistant.HaException("Home Assistant không trả lời lệnh $type.")
            if (!r.optBoolean("success")) {
                throw HomeAssistant.HaException("Home Assistant báo lỗi: ${r.optJSONObject("error")?.optString("message") ?: r}")
            }
            return r
        }
    }
}
