package info.dourok.voicebot.protocol
import android.util.Log
import info.dourok.voicebot.domain.voice.AppLog
import info.dourok.voicebot.domain.voice.ServerAudioParams
import info.dourok.voicebot.data.model.DeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// WebsocketProtocol implementation
class WebsocketProtocol(private val deviceInfo: DeviceInfo,
                        private val url: String,
                        private val accessToken: String) : Protocol() {
    companion object {
        private const val TAG = "WS"
        private const val OPUS_FRAME_DURATION_MS = 60
    }

    private var isOpen: Boolean = false
    private var websocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        // The readTimeout above does NOT apply once the socket has been upgraded: OkHttp sets the
        // web socket's SO_TIMEOUT to 0 and waits forever. So without a ping, a connection whose
        // FIN never arrived (routine on this wifi) is only ever discovered by WRITING to it --
        // measured 2026-09-24, the client sat "open" on a connection the server had released
        // hours earlier, and the only two times it ever noticed were EPIPE on a send.
        //
        // That is a deadlock for the state this bug lives in: SPEAKING streams no mic, so nothing
        // writes, so nothing is discovered, so the session never ends. A ping is a write the
        // runtime does not have to remember to make; no pong inside the interval fails the socket
        // and reaches onFailure -> AudioState.CLOSED -> the session is torn down.
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // MUST be reset on every openAudioChannel (reconnect); otherwise a reconnect's await returns
    // immediately (already completed the first time) -> sendStartListening fires before the server
    // is ready -> the first turn is lost.
    private var helloReceived = CompletableDeferred<Boolean>()

    init {
        sessionId = "your_session_id"
    }

    override suspend fun start() {
        // No-op, matches the C++ reference implementation.
    }

    override suspend fun sendAudio(data: ByteArray) {
        // Log.i(TAG, "Sending audio: ${data.size}")
        websocket?.run {
            send(ByteString.of(*data))
        } ?: Log.e(TAG, "WebSocket is null")
    }

    /** OkHttp's send() returns false once the socket is closed or closing. Discarding that result
     *  loses the message silently -- and the client can believe a channel is still open for minutes
     *  after the server closed it, so every command in that window vanished without a trace. */
    override suspend fun sendText(text: String): Boolean {
        Log.i(TAG, "Sending text: $text")
        val ws = websocket ?: run { Log.e(TAG, "WebSocket is null"); return false }
        val sent = ws.send(text)
        if (!sent) Log.w(TAG, "send() refused: socket closed/closing")
        return sent
    }

    override fun isAudioChannelOpened(): Boolean {
        return websocket != null && isOpen
    }

    override fun closeAudioChannel() {
        websocket?.close(1000, "Normal closure")
        websocket = null
    }

    override suspend fun openAudioChannel(): Boolean = withContext(Dispatchers.IO) {
        // Close any previous connection.
        closeAudioChannel()
        helloReceived = CompletableDeferred()  // reset -> await waits for the NEW connection's hello

        // Resolve the endpoint NOW rather than at construction: the OTA check that provisions it
        // runs in the background at start-up and can land after this object was built, and the
        // panel can change it at any time. With none saved yet, ask the OTA endpoint first.
        val settings = info.dourok.voicebot.data.Settings
        if (settings.wsUrl.isBlank() && url.isBlank()) {
            info.dourok.voicebot.data.ServerProvisioner.provision(deviceInfo)
        }
        val target = settings.wsUrl.ifBlank { url }
        val token = settings.wsToken.ifBlank { accessToken }
        if (target.isBlank()) {
            AppLog.e("Chưa có địa chỉ server -- kiểm tra OTA URL trong tab Setup")
            networkErrorFlow.emit("Server not configured")
            return@withContext false
        }

        // Build the WebSocket request.
        val request = Request.Builder()
            .url(target)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Protocol-Version", "1")
            .addHeader("Device-Id", deviceInfo.mac_address) //
            .addHeader("Client-Id", deviceInfo.uuid) //
            .build()
        Log.i(TAG, "WebSocket connecting to $target")
        AppLog.i("Đang nối server $target")
        // Log header
        request.headers.forEach { (name, value) ->
            Log.i(TAG, "Header: $name: $value")
        }

        // Open the WebSocket.
        websocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                Log.i(TAG, "WebSocket connected")
                AppLog.i("Đã nối server")
                scope.launch {
                    audioChannelStateFlow.emit(AudioState.OPENED)
                }

                // Send the Hello handshake.
                val helloMessage = JSONObject().apply {
                    put("type", "hello")
                    put("version", 1)
                    put("transport", "websocket")
                    put("audio_params", JSONObject().apply {
                        put("format", "opus")
                        // What this client will actually decode. The server reads its OWN config for
                        // the output format and ignores these, so the old hardcoded 16000/1 was a
                        // claim nobody checked -- and a wrong one, measured at 48000 stereo.
                        put("sample_rate", info.dourok.voicebot.data.Settings.playbackSampleRate)
                        put("channels", info.dourok.voicebot.data.Settings.playbackChannels)
                        put("frame_duration", OPUS_FRAME_DURATION_MS)
                    })
                    // Per-session BYO LLM: send the client-configured provider so the server builds
                    // a session LLM from it. Only when a provider is actually configured.
                    val base = info.dourok.voicebot.data.Settings.llmBaseUrl
                    val model = info.dourok.voicebot.data.Settings.llmModel
                    if (base.isNotBlank() && model.isNotBlank()) {
                        put("llm_config", JSONObject().apply {
                            put("type", info.dourok.voicebot.data.Settings.llmTransport)
                            put("base_url", base)
                            put("model_name", model)
                            put("api_key", info.dourok.voicebot.data.Settings.llmApiKey)
                        })
                    }
                    // Per-session BYO Home Assistant: send the client-configured HA + device list so
                    // the server injects it into the prompt + hass_* tools use it. Only when configured.
                    val haUrl = info.dourok.voicebot.data.Settings.haUrl
                    val haDevices = info.dourok.voicebot.data.Settings.haDevices
                    if (haUrl.isNotBlank() && haDevices.isNotBlank()) {
                        put("ha_config", JSONObject().apply {
                            put("base_url", haUrl)
                            put("token", info.dourok.voicebot.data.Settings.haToken)
                            put("devices", haDevices)
                        })
                    }
                    // Per-session BYO persona: send the client-configured system-prompt override so
                    // the server substitutes it for its own prompt: for this session. Blank = server default.
                    val customPrompt = info.dourok.voicebot.data.Settings.customPrompt
                    if (customPrompt.isNotBlank()) {
                        put("custom_prompt", customPrompt)
                    }
                }
                Log.i(TAG, "WebSocket hello: $helloMessage")
                webSocket.send(helloMessage.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.i(TAG, "WebSocket message: $text")
                scope.launch {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    when (type) {
                        "hello" -> parseServerHello(json)
                        else -> incomingJsonFlow.emit(json)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Log.i(TAG, "WebSocket binary message: ${bytes.size}")
                scope.launch {
                    incomingAudioFlow.emit(bytes.toByteArray())
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code: $reason")
                super.onClosing(webSocket, code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isOpen = false
                Log.i(TAG, "WebSocket closed: $code: $reason")
                AppLog.i("Server đóng kết nối ($code${if (reason.isNotBlank()) ": $reason" else ""})")
                scope.launch {
                    audioChannelStateFlow.emit(AudioState.CLOSED)
                }
                websocket = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isOpen = false
                t.printStackTrace()
                Log.e(TAG, "WebSocket error: ${t.message}")
                AppLog.e("Lỗi kết nối server: ${t.message}")
                scope.launch {
                    networkErrorFlow.emit("Server not found")
                    // A channel that died by error is every bit as closed as one closed politely,
                    // and on this LAN it is the COMMON way a session ends (EPIPE on the first write
                    // after the server went away). Emitting only networkErrorFlow here meant nobody
                    // downstream was told the session was over: the server-pushed media snapshot
                    // was never cleared and survived into later sessions, where a stale "paused"
                    // then swallowed the `tts stop` that ends a reply. Same event, same listeners.
                    audioChannelStateFlow.emit(AudioState.CLOSED)
                }
                websocket = null
            }
        })
        // Keep the client alive after the connection opens.
        // client.dispatcher.executorService.shutdown()

        // Wait for the server Hello (mirrors the C++ xEventGroupWaitBits).
        try {
            withTimeout(10000) {
                Log.i(TAG, "Waiting for server hello")
                helloReceived.await()
                Log.i(TAG, "Server hello received")
                true
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Failed to receive server hello")
            AppLog.e("Server không trả lời bắt tay (quá 10 giây)")
            networkErrorFlow.emit("Server timeout")
            closeAudioChannel()
            false
        }
    }

    private fun parseServerHello(root: JSONObject) {
        val transport = root.optString("transport")
        if (transport != "websocket") {
            Log.e(TAG, "Unsupported transport: $transport")
            return
        }

        // What the server will actually encode. Kept rather than dropped: this used to be written
        // into a private field nothing ever read, so the one fact that could catch a client/server
        // format mismatch was being thrown away on every connection.
        val audioParams = root.optJSONObject("audio_params")
        audioParams?.let {
            val sampleRate = it.optInt("sample_rate", -1)
            if (sampleRate != -1) ServerAudioParams.sampleRate = sampleRate
            val channels = it.optInt("channels", -1)
            if (channels != -1) ServerAudioParams.channels = channels
        }
        sessionId = root.optString("session_id")


        helloReceived.complete(true)
    }

    // Release resources.
    override fun dispose() {
        scope.cancel()
        closeAudioChannel()
        client.dispatcher.executorService.shutdown()
    }

}