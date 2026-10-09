package info.dourok.voicebot.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject

// Enums and constants for the Protocol base class.
enum class AbortReason { WAKE_WORD_DETECTED, NONE }
enum class ListeningMode { ALWAYS_ON, AUTO_STOP, MANUAL }
enum class AudioState { OPENED, CLOSED }

// Protocol abstract base (mirrors the C++ Protocol).
abstract class Protocol {
    protected var sessionId: String = "" // session ID
    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Callbacks are exposed as Flow.
    val incomingJsonFlow = MutableSharedFlow<JSONObject>()
    val incomingAudioFlow = MutableSharedFlow<ByteArray>()
    val audioChannelStateFlow = MutableSharedFlow<AudioState>()
    val networkErrorFlow = MutableSharedFlow<String>()

    abstract suspend fun start()
    abstract suspend fun sendAudio(data: ByteArray)
    abstract suspend fun openAudioChannel(): Boolean
    abstract fun closeAudioChannel()
    abstract fun isAudioChannelOpened(): Boolean
    /** Returns false when the frame could not be sent (socket closed/closing). */
    abstract suspend fun sendText(text: String): Boolean

    suspend fun sendAbortSpeaking(reason: AbortReason) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "abort")
            if (reason == AbortReason.WAKE_WORD_DETECTED) put("reason", "wake_word_detected")
        }
        sendText(json.toString())
    }

    suspend fun sendWakeWordDetected(wakeWord: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "detect")
            put("text", wakeWord)
        }
        sendText(json.toString())
    }

    /** Inject a typed query (from the control panel chat) as if the user had spoken it.
     *  Returns false if the frame was dropped -- see VoiceAssistant.onTextCommand, which retries. */
    suspend fun sendTextQuery(text: String): Boolean {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "detect")
            put("mode", "manual")
            put("text", text)
        }
        return sendText(json.toString())
    }

    suspend fun sendStartListening(mode: ListeningMode) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "start")
            put("mode", when (mode) {
                ListeningMode.ALWAYS_ON -> "realtime"
                ListeningMode.AUTO_STOP -> "auto"
                ListeningMode.MANUAL -> "manual"
            })
        }
        sendText(json.toString())
    }

    suspend fun sendStopListening() {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "stop")
        }
        sendText(json.toString())
    }

    suspend fun sendIotDescriptors(descriptors: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "iot")
            put("descriptors", JSONObject(descriptors))
        }
        sendText(json.toString())
    }

    suspend fun sendIotStates(states: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "iot")
            put("states", JSONObject(states))
        }
        sendText(json.toString())
    }

    // Media Player tab (web control panel) commands -- routed through the same play_youtube
    // session a voice command would use (see xiaozhi-server's core/handle/mediaHandle.py).

    /** [itemsJson] is a JSON array of {video_id,title,artist,thumbnail,duration}: the WHOLE list the
     *  panel is showing, with [startIndex] marking the tapped song. The server plays from there in
     *  order (so "next" follows the panel, not YouTube's related-song radio) while keeping the full
     *  list on screen, so the songs above the tapped one don't vanish. */
    suspend fun sendMediaPlay(itemsJson: String, startIndex: Int) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "media")
            put("action", "play")
            put("items", org.json.JSONArray(itemsJson))
            put("start_index", startIndex)
        }
        sendText(json.toString())
    }

    suspend fun sendMediaNext() {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "media")
            put("action", "next")
        }
        sendText(json.toString())
    }

    suspend fun sendMediaSeek(positionS: Int) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "media")
            put("action", "seek")
            put("position_s", positionS)
        }
        sendText(json.toString())
    }

    suspend fun sendMediaPause() {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "media")
            put("action", "pause")
        }
        sendText(json.toString())
    }

    suspend fun sendMediaResume() {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "media")
            put("action", "resume")
        }
        sendText(json.toString())
    }

    suspend fun sendMediaStop() {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "media")
            put("action", "stop")
        }
        sendText(json.toString())
    }

    abstract fun dispose()
}

