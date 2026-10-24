package info.dourok.voicebot.ha

import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import info.dourok.voicebot.OpusDecoder
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.AppLog
import info.dourok.voicebot.protocol.AudioState
import info.dourok.voicebot.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Home Assistant Assist as the speaker's voice server, in place of a xiaozhi server.
 *
 * On a wake word the speaker opens Home Assistant's WebSocket API and runs an Assist pipeline
 * (`assist_pipeline/run`, stt → intent → tts): the microphone goes up as raw 16 kHz PCM, Home
 * Assistant's own speech-to-text and voice activity detection find the sentence, its conversation
 * agent (its own Assist, or an LLM agent such as Google Generative AI) answers and controls the
 * house, and its text-to-speech comes back as a file the speaker plays.
 *
 * [info.dourok.voicebot.domain.voice.VoiceAssistant] does not know: it gets the same messages a
 * xiaozhi server sends ("stt", "tts" start / sentence_start / stop) and sends the same ones
 * ("listen" start, typed text, "abort"), so wake word, LEDs, the chat in the panel, continued
 * conversation and the display all work unchanged. Its microphone frames arrive Opus-encoded (the
 * xiaozhi format) and are decoded back to PCM here, which a slow box does easily at 16 kHz mono.
 */
class HaAssistProtocol : Protocol() {

    private val main = Handler(Looper.getMainLooper())
    private var socket: HaSocket? = null
    private var decoder: OpusDecoder? = null

    @Volatile private var runId = -1
    @Volatile private var sttHandler = -1      // binary handler id while the pipeline listens
    @Volatile private var audioEnded = false
    @Volatile private var heardSpeech = false
    @Volatile private var conversationId: String? = null
    @Volatile private var continueConversation = true
    @Volatile private var speechStarted = false
    @Volatile private var ttsPending = false
    private var silenceJob: Job? = null
    private var player: MediaPlayer? = null

    override suspend fun start() {}

    override suspend fun openAudioChannel(): Boolean = withContext(Dispatchers.IO) {
        closeAudioChannel()
        val s = HaSocket(object : HaSocket.Listener {
            override fun onMessage(message: JSONObject) = handle(message)
            override fun onClosed(reason: String) {
                scope.launch { audioChannelStateFlow.emit(AudioState.CLOSED) }
            }
        })
        try {
            AppLog.i("Đang nối Home Assistant Assist…")
            s.connect()
        } catch (e: Exception) {
            AppLog.e("Không nối được Home Assistant: ${e.message}")
            networkErrorFlow.emit(e.message ?: "Home Assistant")
            return@withContext false
        }
        socket = s
        conversationId = null
        sessionId = "ha"
        if (decoder == null) decoder = runCatching { OpusDecoder(16000, 1, 60) }.getOrNull()
        AppLog.i("Đã nối Home Assistant Assist${if (Settings.haPipeline.isNotBlank()) " (${Settings.haPipeline})" else ""}")
        audioChannelStateFlow.emit(AudioState.OPENED)
        true
    }

    override fun isAudioChannelOpened(): Boolean = socket?.connected == true

    override fun closeAudioChannel() {
        silenceJob?.cancel()
        stopPlayback()
        runId = -1
        sttHandler = -1
        val s = socket ?: return
        socket = null
        s.close()
        scope.launch { audioChannelStateFlow.emit(AudioState.CLOSED) }
    }

    override fun dispose() {
        closeAudioChannel()
        scope.cancel()
    }

    /** Microphone frames (Opus, 60 ms) while the pipeline listens: decoded and passed on as PCM. */
    override suspend fun sendAudio(data: ByteArray) {
        val h = sttHandler
        val s = socket ?: return
        if (h < 0 || audioEnded) return
        val pcm = decoder?.decode(data) ?: return
        val frame = ByteArray(pcm.size + 1)
        frame[0] = h.toByte()
        System.arraycopy(pcm, 0, frame, 1, pcm.size)
        s.sendBinary(frame)
    }

    /** The xiaozhi messages VoiceAssistant sends, turned into pipeline runs. */
    override suspend fun sendText(text: String): Boolean {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (socket == null) return false
        when (o.optString("type")) {
            "listen" -> when (o.optString("state")) {
                "start" -> startRun(null)
                "stop" -> endAudio()
                "detect" -> o.optString("text").takeIf { it.isNotBlank() && o.optString("mode") == "manual" }?.let { startRun(it) }
            }
            "abort" -> stopPlayback()
        }
        return true
    }

    /** A run from the microphone ([typed] null) or from typed text. */
    private fun startRun(typed: String?) {
        val s = socket ?: return
        stopPlayback()
        silenceJob?.cancel()
        audioEnded = false
        heardSpeech = false
        speechStarted = false
        ttsPending = false
        sttHandler = -1
        val cmd = JSONObject().put("type", "assist_pipeline/run")
            .put("start_stage", if (typed == null) "stt" else "intent")
            .put("end_stage", "tts")
            .put("input", if (typed == null) JSONObject().put("sample_rate", 16000) else JSONObject().put("text", typed))
        if (Settings.haPipeline.isNotBlank()) cmd.put("pipeline", Settings.haPipeline)
        conversationId?.let { cmd.put("conversation_id", it) }
        val id = s.nextId()
        runId = id
        s.send(cmd, id)
        if (typed == null) {
            // nobody speaks: end the audio so Home Assistant answers "nothing heard" and the session ends
            silenceJob = scope.launch {
                delay(8000)
                if (!heardSpeech && runId == id) {
                    AppLog.i("Home Assistant: không nghe thấy gì, kết thúc")
                    endAudio()
                }
            }
        }
    }

    private fun endAudio() {
        val h = sttHandler
        if (h < 0 || audioEnded) return
        audioEnded = true
        socket?.sendBinary(byteArrayOf(h.toByte()))
    }

    private fun emit(json: JSONObject) {
        scope.launch { incomingJsonFlow.emit(json) }
    }

    private fun tts(state: String, text: String? = null) {
        val o = JSONObject().put("type", "tts").put("state", state)
        if (text != null) o.put("text", text)
        emit(o)
    }

    private fun handle(m: JSONObject) {
        if (m.optInt("id") != runId) return
        if (m.optString("type") == "result") {
            if (!m.optBoolean("success")) {
                val err = m.optJSONObject("error")?.optString("message") ?: "lỗi"
                AppLog.e("Home Assistant không chạy được Assist: $err")
                finishSession()
            }
            return
        }
        val ev = m.optJSONObject("event") ?: return
        val data = ev.optJSONObject("data") ?: JSONObject()
        when (ev.optString("type")) {
            "run-start" -> {
                sttHandler = data.optJSONObject("runner_data")?.optInt("stt_binary_handler_id", -1) ?: -1
            }
            "stt-vad-start" -> heardSpeech = true
            "stt-vad-end" -> endAudio()
            "stt-end" -> {
                endAudio()
                sttHandler = -1
                val t = data.optJSONObject("stt_output")?.optString("text").orEmpty()
                if (t.isNotBlank()) {
                    heardSpeech = true
                    emit(JSONObject().put("type", "stt").put("text", t))
                }
            }
            "intent-end" -> {
                val out = data.optJSONObject("intent_output") ?: JSONObject()
                out.optString("conversation_id").takeIf { it.isNotBlank() }?.let { conversationId = it }
                continueConversation = out.optBoolean("continue_conversation", true)
                val speech = out.optJSONObject("response")?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech").orEmpty()
                speechStarted = true
                ttsPending = true
                tts("start")
                if (speech.isNotBlank()) tts("sentence_start", speech)
            }
            "tts-end" -> {
                val url = data.optJSONObject("tts_output")?.optString("url").orEmpty()
                if (url.isBlank()) { ttsPending = false; replyDone() } else play(url)
            }
            "run-end" -> {
                // no speech output (TTS off, or an error already handled): close the reply here
                if (!ttsPending) replyDone()
            }
            "error" -> {
                val code = data.optString("code")
                AppLog.w("Home Assistant Assist: $code ${data.optString("message")}")
                ttsPending = false
                if (code == "stt-no-text-recognized" || code == "stt-stream-failed" || !speechStarted) {
                    finishSession()
                } else {
                    replyDone()
                }
            }
        }
    }

    /** The answer has been spoken: listen on (continued conversation) or end the session. */
    private fun replyDone() {
        if (runId < 0) return
        runId = -1
        if (!speechStarted) { finishSession(); return }
        if (continueConversation) {
            tts("stop")   // VoiceAssistant answers with "listen start" → the next run
        } else {
            finishSession()
        }
    }

    private fun finishSession() {
        runId = -1
        closeAudioChannel()
    }

    /** Download the TTS file through our own trust settings (Let's Encrypt on Android 5.1) and play it. */
    private fun play(url: String) {
        val myRun = runId
        scope.launch(Dispatchers.IO) {
            val file = try {
                fetch(url)
            } catch (e: Exception) {
                AppLog.w("Không tải được tiếng của Home Assistant: ${e.message}")
                null
            }
            if (file == null || runId != myRun) {
                ttsPending = false
                replyDone()
                return@launch
            }
            main.post {
                try {
                    stopPlayback()
                    val p = MediaPlayer()
                    @Suppress("DEPRECATION")
                    p.setAudioStreamType(AudioManager.STREAM_MUSIC)
                    p.setDataSource(file.absolutePath)
                    p.setOnCompletionListener {
                        it.release(); if (player === it) player = null
                        file.delete()
                        ttsPending = false
                        replyDone()
                    }
                    p.setOnErrorListener { mp, _, _ ->
                        mp.release(); if (player === mp) player = null
                        file.delete()
                        ttsPending = false
                        replyDone()
                        true
                    }
                    p.prepare()
                    player = p
                    p.start()
                } catch (e: Exception) {
                    AppLog.w("Không phát được tiếng của Home Assistant: ${e.message}")
                    file.delete()
                    ttsPending = false
                    replyDone()
                }
            }
        }
    }

    private fun fetch(url: String): File {
        val absolute = if (url.startsWith("http")) url else {
            val base = HomeAssistant.lastUsed.ifBlank { HomeAssistant.bases().firstOrNull().orEmpty() }
            base.trimEnd('/') + url
        }
        val u = HomeAssistant.target(URL(absolute))
        val c = u.openConnection() as HttpURLConnection
        try {
            if (c is javax.net.ssl.HttpsURLConnection) info.dourok.voicebot.net.Https.socketFactory()?.let { c.sslSocketFactory = it }
            c.connectTimeout = 8000
            c.readTimeout = 20000
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val ext = if (absolute.contains(".wav")) ".wav" else if (absolute.contains(".ogg")) ".ogg" else ".mp3"
            val f = File.createTempFile("ha_tts", ext)
            c.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
            return f
        } finally {
            c.disconnect()
        }
    }

    private fun stopPlayback() {
        val p = player ?: return
        player = null
        main.post { runCatching { p.stop() }; p.release() }
    }
}
