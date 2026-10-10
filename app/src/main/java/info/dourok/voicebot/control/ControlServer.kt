package info.dourok.voicebot.control

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import info.dourok.voicebot.data.ServerProvisioner
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.data.maskApiKey
import info.dourok.voicebot.data.model.DeviceInfo
import info.dourok.voicebot.domain.bluetooth.BtController
import info.dourok.voicebot.domain.bluetooth.BtResult
import info.dourok.voicebot.domain.voice.AudioPlayback
import info.dourok.voicebot.domain.voice.AppLog
import info.dourok.voicebot.domain.voice.ConversationLog
import info.dourok.voicebot.domain.voice.LedIndicator
import info.dourok.voicebot.domain.voice.LedState
import info.dourok.voicebot.domain.voice.MediaCommands
import info.dourok.voicebot.domain.voice.MediaPlaybackState
import info.dourok.voicebot.domain.voice.MediaSessionState
import info.dourok.voicebot.domain.voice.MicTest
import info.dourok.voicebot.domain.voice.ServerAudioParams
import info.dourok.voicebot.domain.voice.TextCommands
import info.dourok.voicebot.media.LocalMusicPlayer
import info.dourok.voicebot.update.UpdateManager
import info.dourok.voicebot.weather.LocationManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device web control panel (like aiboxplus's control center). Serves a single page + a small
 * JSON API on [PORT] so a phone/PC on the same wifi can tweak EQ / volume / wake sensitivity / mic
 * gain, view the chat transcript and restart the app — without rebuilding.
 *   http://<r1-ip>:8088
 */
@Singleton
class ControlServer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playback: AudioPlayback,
    private val led: LedIndicator,
    private val bt: BtController,
    private val deviceInfo: DeviceInfo,
) : NanoHTTPD(PORT) {

    /** versionName of the installed build, reported to the OTA endpoint and shown in the panel. */
    val appVersion: String by lazy {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }
    }

    fun startServer() {
        // Lend the volume control to the assistant's tools (see DeviceActions for why).
        info.dourok.voicebot.mcp.DeviceActions.setVolume = { setVolume(it) }
        info.dourok.voicebot.mcp.DeviceActions.getVolume = { currentVolumePercent() }
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "control panel running on :$PORT")
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
        }
    }

    override fun serve(session: IHTTPSession): Response = try {
        when (session.uri) {
            "/", "/index.html" -> serveAsset()
            "/api/state" -> json(buildState())
            "/api/set" -> {
                val ok = handleSet(session)
                json(if (ok) """{"ok":true}""" else """{"ok":false,"error":"missing key or value"}""")
            }
            "/api/say" -> {
                session.parameters["text"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { TextCommands.flow.tryEmit(it.trim()) }
                json("""{"ok":true}""")
            }
            "/api/led" -> {
                session.parameters["state"]?.firstOrNull()?.let { previewLed(it) }
                json("""{"ok":true}""")
            }
            "/api/restart" -> json("""{"ok":true}""").also { AppLog.i("Khởi động lại app theo yêu cầu"); scheduleRestart() }
            "/api/mic/start" -> { MicTest.start(session.parameters["agc"]?.firstOrNull() == "1"); json("""{"ok":true}""") }
            "/api/mic/stop" -> { MicTest.stop(); json("""{"ok":true,"bytes":${MicTest.sizeBytes()}}""") }
            "/api/mic/rec.wav" -> serveWav()
            "/api/setup/llm" -> { handleSet(session); json("""{"ok":true}""") }  // legacy alias, unused by control.html
            "/api/setup/wake" -> {
                session.parameters["engine"]?.firstOrNull()?.let { Settings.wakeEngine = it }
                json("""{"ok":true}""")
            }
            "/api/setup/server" -> json(handleSetupServer(session))
            "/api/llm/models" -> json(handleLlmModels(session))
            "/api/llm/test" -> json(handleLlmTest(session))
            "/api/ha/devices" -> json(handleHaDevices(session))
            "/api/ha/test" -> json(handleHaTest(session))
            "/api/media/search" -> json(handleMediaSearch(param(session, "q")))
            "/api/media/play" -> json(handleMediaPlay(session))
            // With a music server configured the song plays on this device (LocalMusicPlayer);
            // without one these go to the xiaozhi server over the voice channel, as upstream.
            "/api/media/seek" -> {
                param(session, "position_s").toIntOrNull()?.let {
                    if (localMusic()) LocalMusicPlayer.seekTo(it)
                    else MediaCommands.flow.tryEmit(MediaCommands.Command.Seek(it))
                }
                json("""{"ok":true}""")
            }
            "/api/media/next" -> {
                if (localMusic()) LocalMusicPlayer.next() else MediaCommands.flow.tryEmit(MediaCommands.Command.Next)
                json("""{"ok":true}""")
            }
            "/api/media/pause" -> {
                if (localMusic()) LocalMusicPlayer.pause() else MediaCommands.flow.tryEmit(MediaCommands.Command.Pause)
                json("""{"ok":true}""")
            }
            "/api/media/resume" -> {
                if (localMusic()) LocalMusicPlayer.resume() else MediaCommands.flow.tryEmit(MediaCommands.Command.Resume)
                json("""{"ok":true}""")
            }
            "/api/media/stop" -> {
                if (localMusic()) LocalMusicPlayer.stop() else MediaCommands.flow.tryEmit(MediaCommands.Command.Stop)
                json("""{"ok":true}""")
            }
            "/api/media/state" -> json(buildMediaState())
            // The player's spectrum bars. Polled several times a second while a song plays and
            // the Media tab is open, so it is its own tiny reply rather than part of the state.
            "/api/media/spectrum" -> json(if (localMusic()) LocalMusicPlayer.spectrumJson() else """{"ok":false}""")
            // Stop / resume listening for the wake word, and call the assistant by hand.
            "/api/voice/pause" -> {
                val on = param(session, "on") == "1"
                info.dourok.voicebot.domain.voice.VoiceGate.paused = on
                if (on) {
                    info.dourok.voicebot.domain.voice.VoiceCommands.flow.tryEmit(
                        info.dourok.voicebot.domain.voice.VoiceCommands.Command.SLEEP
                    )
                }
                AppLog.i(if (on) "Tắt nghe: loa bỏ qua từ đánh thức" else "Nghe lại từ đánh thức")
                json("""{"ok":true,"paused":$on}""")
            }
            "/api/voice/wake" -> {
                info.dourok.voicebot.domain.voice.VoiceCommands.flow.tryEmit(
                    info.dourok.voicebot.domain.voice.VoiceCommands.Command.WAKE
                )
                json("""{"ok":true}""")
            }
            // Internet radio: the station list for the panel, play by key, and the relay the
            // on-device player reads the stream through (see MusicService.radioTrack).
            "/api/radio/stations" -> json(buildRadioStations())
            "/api/radio/play" -> json(handleRadioPlay(param(session, "id")))
            "/radio/stream" -> serveRadioStream(param(session, "id"))
            // Where the speaker is: city search, the chosen place, local time and weather there.
            "/api/location/search" -> json(LocationManager.search(param(session, "q")))
            "/api/location/set" -> json(handleLocationSet(session))
            "/api/location/clear" -> { LocationManager.clearPlace(); json(LocationManager.stateJson()) }
            "/api/weather" -> json(LocationManager.stateJson())
            // Over-the-air update of the app itself. check/install only start the work and
            // answer with the state as it is now; the panel polls /api/update/state for the rest.
            "/api/update/state" -> json(UpdateManager.stateJson())
            "/api/update/check" -> { UpdateManager.check(); json(UpdateManager.stateJson()) }
            "/api/update/install" -> { UpdateManager.install(); json(UpdateManager.stateJson()) }
            "/api/logs" -> json(buildLogs(param(session, "since").toLongOrNull() ?: 0L))
            "/api/logs/clear" -> { AppLog.clear(); AppLog.i("Đã xoá log"); json("""{"ok":true}""") }
            // Bluetooth audio out. Its own endpoint, polled only while the card is open: a scan's
            // results change every second and /api/state is polled 1.5s forever by every browser in
            // the house. Same split as /api/media/state.
            "/api/bt/state" -> json(buildBtState())
            "/api/bt/enable" -> json(bt.setEnabled(param(session, "on") == "1"))
            "/api/bt/scan/start" -> json(bt.startScan())
            "/api/bt/scan/stop" -> { bt.stopScan(); json("""{"ok":true}""") }
            "/api/bt/pair" -> json(bt.pair(param(session, "addr")))
            "/api/bt/connect" -> json(bt.connect(param(session, "addr")))
            "/api/bt/disconnect" -> json(bt.disconnect(param(session, "addr")))
            "/api/bt/forget" -> json(bt.forget(param(session, "addr")))
            "/api/bt/auto" -> {
                bt.setAutoReconnect(param(session, "on") == "1")
                json("""{"ok":true}""")
            }
            "/api/news/save" -> json(handleNewsSave(session))
            // "Phát thử" is just the spoken request, typed: the server's get_news_bulletin tool
            // does the rest. Same path as /api/say and as the daily alarm, so all three triggers
            // share one proven route instead of a bespoke connection mode.
            "/api/news/test" -> {
                TextCommands.flow.tryEmit(NEWS_PHRASE)
                json("""{"ok":true}""")
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
    } catch (e: Exception) {
        Log.e(TAG, "serve ${session.uri}: ${e.message}")
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
    }

    /**
     * Returns false when key/value is missing so the caller can report a real failure instead of
     * the blind {"ok":true} this used to send regardless of outcome.
     *
     * Long values (e.g. the Assistant persona, which can run several KB) are sent in the POST body
     * instead of the query string — a query string carrying a whole system prompt can exceed the
     * request-line/URL length ceiling and silently truncate, while still returning 200. A caller
     * using body-based transport (no `value` in the query string, only `key`) is identified by that
     * absence: for such a POST, an empty/zero-length body legitimately means "save empty" (e.g.
     * clearing the Assistant persona back to the server default), not "value missing" — so it
     * defaults to "" rather than failing. Callers still using the query-string `value` param (the
     * short-field `set()` JS helper) are unaffected.
     */
    private fun handleSet(session: IHTTPSession): Boolean {
        val key = session.parameters["key"]?.firstOrNull() ?: return false
        val queryValue = session.parameters["value"]?.firstOrNull()
        val v: String = if (queryValue != null) {
            queryValue
        } else if (session.method == NanoHTTPD.Method.POST) {
            val body = HashMap<String, String>()
            try {
                session.parseBody(body)
            } catch (e: Exception) {
                Log.e(TAG, "parseBody for /api/set: ${e.message}")
            }
            body["postData"] ?: ""
        } else {
            return false
        }
        when (key) {
            "wake_sensitivity" -> Settings.wakeSensitivity = v
            "wake_sensitivity_speaking" -> Settings.wakeSensitivitySpeaking = v
            "mai_oi_threshold" -> Settings.maiOiThreshold = v
            "mai_oi_threshold_speaking" -> Settings.maiOiThresholdSpeaking = v
            "mic_gain" -> v.toFloatOrNull()?.let { Settings.micGain = it }
            "mic_source" -> v.toIntOrNull()?.let { Settings.micSource = it }
            "agc_enabled" -> Settings.agcEnabled = v == "true"
            "agc_target" -> v.toFloatOrNull()?.let { Settings.agcTarget = it }
            "agc_max_gain" -> v.toFloatOrNull()?.let { Settings.agcMaxGain = it }
            "led_idle" -> Settings.ledIdle = v
            "led_listening" -> Settings.ledListening = v
            "led_speaking" -> Settings.ledSpeaking = v
            "led_music" -> Settings.ledMusic = v
            "playback_sr" -> v.toIntOrNull()?.let { Settings.playbackSampleRate = it }
            "playback_ch" -> v.toIntOrNull()?.let { Settings.playbackChannels = it }
            "eq_enabled" -> { Settings.eqEnabled = v == "true"; playback.applyAudioSettings() }
            "eq_bands" -> {
                Settings.eqBands = v.split(",").mapNotNull { it.trim().toIntOrNull() }.toIntArray()
                playback.applyAudioSettings()
            }
            "loudness_mb" -> v.toIntOrNull()?.let {
                Settings.loudnessMb = it; playback.applyAudioSettings()
            }
            "volume" -> v.toIntOrNull()?.let { setVolume(it) }
            "llm_provider" -> Settings.llmProvider = v
            "llm_base_url" -> Settings.llmBaseUrl = v
            "llm_api_key" -> Settings.llmApiKey = v
            "llm_model" -> Settings.llmModel = v
            "llm_transport" -> Settings.llmTransport = v
            "wake_engine" -> Settings.wakeEngine = v
            "ota_url" -> Settings.otaUrl = v
            "music_url" -> Settings.musicUrl = v
            "auto_update" -> Settings.autoUpdate = v == "true"
            "pause_on_music" -> Settings.pauseOnMusic = v == "true"
            "ha_url" -> Settings.haUrl = v
            "ha_token" -> Settings.haToken = v
            "ha_devices" -> Settings.haDevices = v
            "custom_prompt" -> Settings.customPrompt = v
        }
        return true
    }

    /** Counts previews, so that only the latest one puts the ring back afterwards. */
    private val ledPreviews = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Show one state's effect on the ring because somebody is choosing it in the panel -- and put
     * the ring back a few seconds later, or it would go on showing "listening" over a speaker that
     * is doing nothing until the next real change of state.
     */
    private fun previewLed(name: String) {
        val shown = when (name) {
            "listening" -> LedState.LISTENING
            "speaking" -> LedState.SPEAKING
            "music" -> LedState.MUSIC
            else -> LedState.IDLE
        }
        led.preview(shown)
        val ticket = ledPreviews.incrementAndGet()
        Thread {
            try {
                Thread.sleep(LED_PREVIEW_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (ticket != ledPreviews.get()) return@Thread   // a newer preview owns the ring now
            val voice = info.dourok.voicebot.domain.voice.VoiceDebugState
            val real = when {
                !voice.awake -> LedState.IDLE
                voice.voiceState == "LISTENING" -> LedState.LISTENING
                voice.voiceState == "SPEAKING" -> LedState.SPEAKING
                else -> LedState.IDLE
            }
            if (real != shown) led.preview(real)
        }.start()
    }

    /**
     * The hardware scale is coarse — STREAM_MUSIC on the R1 has 15 steps, so one step is ~6.7%.
     * Both this and [buildState]'s read-back round to the NEAREST step: integer division truncates,
     * which always lands low and by up to a full step, and the two truncations compounded — 39%
     * became step 5, which then reported back as 33%.
     */
    private fun setVolume(percent: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val step = Math.round(percent.coerceIn(0, 100) * max / 100f).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
        // And again for the Bluetooth output, which the call above does not reach -- see
        // BtController.applyOutputVolume. Harmless when nothing is connected: it refuses.
        bt.applyOutputVolume(step)
        Settings.volume = percent
    }

    /** Volume as the listener hears it, 0..100. */
    private fun currentVolumePercent(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // While a speaker is connected the index that decides loudness is the Bluetooth one.
        val btIndex = bt.outputVolumeIndex()
        val cur = if (btIndex >= 0) btIndex else am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) Math.round(cur * 100f / max) else 0
    }

    private fun buildState(): String {
        val o = JSONObject()
        o.put("wake_sensitivity", Settings.wakeSensitivity)
        o.put("wake_sensitivity_speaking", Settings.wakeSensitivitySpeaking)
        o.put("mai_oi_threshold", Settings.maiOiThreshold)
        o.put("mai_oi_threshold_speaking", Settings.maiOiThresholdSpeaking)
        o.put("mic_gain", Settings.micGain.toDouble())
        o.put("mic_source", Settings.micSource)
        o.put("agc_enabled", Settings.agcEnabled)
        o.put("agc_target", Settings.agcTarget.toDouble())
        o.put("agc_max_gain", Settings.agcMaxGain.toDouble())
        o.put("led_idle", Settings.ledIdle)
        o.put("led_listening", Settings.ledListening)
        o.put("led_speaking", Settings.ledSpeaking)
        o.put("led_music", Settings.ledMusic)
        o.put("playback_sr", Settings.playbackSampleRate)
        o.put("playback_ch", Settings.playbackChannels)
        // What the server said it would send, so the panel can show a mismatch rather than let
        // somebody hear one. -1 until a hello has arrived -- see ServerAudioParams.
        o.put("server_sr", info.dourok.voicebot.domain.voice.ServerAudioParams.sampleRate)
        o.put("server_ch", info.dourok.voicebot.domain.voice.ServerAudioParams.channels)
        o.put("eq_enabled", Settings.eqEnabled)
        o.put("eq_bands", JSONArray(Settings.eqBands.toList()))
        o.put("loudness_mb", Settings.loudnessMb)
        o.put("mic_recording", MicTest.recording)   // để UI biết bản ghi 30s tự dừng

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // While a speaker is connected the index that decides loudness is the Bluetooth one, not the
        // one getStreamVolume answers with -- reporting the latter is how the panel came to show
        // 100% over an output sitting at 6 of 15.
        val btIndex = bt.outputVolumeIndex()
        val cur = if (btIndex >= 0) btIndex else am.getStreamVolume(AudioManager.STREAM_MUSIC)
        o.put("volume", if (max > 0) Math.round(cur * 100f / max) else 0)
        // Lets the UI build a slider that can only express values the hardware can actually hold.
        o.put("volume_steps", max)

        playback.eqInfo()?.let { eq ->
            o.put("eq", JSONObject().apply {
                put("freqs", JSONArray(eq.freqsHz.toList()))
                put("min", eq.minMb)
                put("max", eq.maxMb)
            })
        }

        val chat = JSONArray()
        ConversationLog.recent().forEach {
            chat.put(JSONObject().put("sender", it.sender).put("text", it.text).put("time", it.time))
        }
        o.put("chat", chat)

        // ── Generic client config (Setup tab). The API key is NEVER returned raw — masked only. ──
        o.put("llm_provider", Settings.llmProvider)
        o.put("llm_base_url", Settings.llmBaseUrl)
        o.put("llm_api_key_masked", maskApiKey(Settings.llmApiKey))
        o.put("llm_api_key_set", Settings.llmApiKey.isNotEmpty())
        o.put("llm_model", Settings.llmModel)
        o.put("llm_transport", Settings.llmTransport)
        o.put("wake_engine", Settings.wakeEngine)
        o.put("ota_url", Settings.otaUrl)
        o.put("music_url", Settings.musicUrl)
        o.put("ws_url", Settings.wsUrl)
        // Identity the server knows this device by, and the code its owner types into the server
        // console while it is still unbound ("" once bound). See ServerProvisioner.
        o.put("device_id", deviceInfo.mac_address)
        o.put("app_version", appVersion)
        o.put("activation_code", ServerProvisioner.activationCode)
        o.put("activation_msg", ServerProvisioner.activationMessage)
        o.put("ota_checked_ms", ServerProvisioner.lastCheckMs)
        // Enough for the panel to light its "bản mới" notice without a second poll.
        o.put("update_available", UpdateManager.isUpdateAvailable)
        o.put("update_version", UpdateManager.latestName)
        // TTS host derived from the configured WS host (de-hardcode); empty until a server is set.
        o.put("tts_host", ttsHostFromWs(Settings.wsUrl))
        // Home Assistant — token NEVER returned raw, masked only.
        o.put("ha_url", Settings.haUrl)
        o.put("ha_token_masked", maskApiKey(Settings.haToken))
        o.put("ha_token_set", Settings.haToken.isNotEmpty())
        o.put("ha_devices", Settings.haDevices)
        // Assistant persona override — plain text, no secret to mask.
        o.put("custom_prompt", Settings.customPrompt)

        // News bulletin (server does the actual work — core/news/*.py; this is just what the
        // panel needs to render the section + what NewsAlarmScheduler needs on-device).
        o.put("news_enabled", Settings.newsEnabled)
        o.put("news_time", Settings.newsTime)
        o.put("news_voice", Settings.newsVoice)
        val newsCategories = JSONArray()
        Settings.newsCategories.split(",").forEach { part ->
            val kv = part.split(":")
            if (kv.size == 2) {
                newsCategories.put(JSONObject().put("key", kv[0]).put("enabled", kv[1] == "1"))
            }
        }
        o.put("news_categories", newsCategories)
        // Live assistant state -- see VoiceDebugState for why this is exposed over HTTP rather
        // than only logged (the R1's logcat is drowned by its mic driver).
        o.put("voice_state", info.dourok.voicebot.domain.voice.VoiceDebugState.voiceState)
        o.put("voice_awake", info.dourok.voicebot.domain.voice.VoiceDebugState.awake)
        o.put("voice_paused", info.dourok.voicebot.domain.voice.VoiceGate.paused)
        o.put("pause_on_music", Settings.pauseOnMusic)
        // True whenever the wake word is being ignored, whichever of the two reasons applies, so
        // the panel can say why the speaker is not answering to its name.
        o.put("wake_muted", !info.dourok.voicebot.domain.voice.VoiceGate.wakeWordAllowed)
        return o.toString()
    }

    // ── Generic client config helpers ──────────────────────────────────────
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * pytube_api runs alongside xiaozhi-server on a fixed port, and the server already points at it
     * (PYTUBE_BASE_URL in play_youtube.py). Deriving it from the configured server host the same way
     * [ttsHostFromWs] does keeps one source of truth instead of asking the user to type a host the
     * app can work out for itself.
     */
    private fun pytubeBase(): String {
        val ws = Settings.wsUrl
        if (ws.isBlank()) return ""
        return try {
            "http://${java.net.URI(ws).host}:$PYTUBE_PORT"
        } catch (e: Exception) { "" }
    }

    /** Base url of the DB-Robot music server, without a trailing slash; "" = not configured. */
    private fun musicBase(): String = Settings.musicUrl.trim().trimEnd('/')

    /** True when the Media tab should search and play through the music server on this device. */
    private fun localMusic(): Boolean = Settings.musicUrl.isNotBlank()

    // The music server searches and resolves the stream before it answers, which takes far longer
    // than the 15s the shared client allows.
    private val musicHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /**
     * GET {musicBase()}/stream_pcm?song= → reshaped as {"ok":true,"results":[...]}. The server
     * returns its single best match, so the list holds one song or none.
     */
    private fun handleMusicServerSearch(query: String): String {
        if (query.isBlank()) return """{"ok":true,"results":[]}"""
        return try {
            val url = "${musicBase()}/stream_pcm?song=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val req = Request.Builder().url(url).get().build()
            val body = musicHttp.newCall(req).execute().use { it.body?.string().orEmpty() }
            val arr = JSONArray()
            info.dourok.voicebot.media.parseMusicServerSong(body)?.let {
                arr.put(
                    JSONObject()
                        .put("video_id", it.videoId).put("title", it.title)
                        .put("artist", it.artist).put("duration", it.duration)
                        .put("thumbnail", it.thumbnailUrl)
                )
            }
            """{"ok":true,"results":$arr}"""
        } catch (e: Exception) {
            AppLog.w("Không tìm được nhạc: ${e.message}")
            """{"ok":false,"error":${JSONObject.quote(e.message ?: "network error")}}"""
        }
    }

    private fun ttsHostFromWs(wsUrl: String): String {
        if (wsUrl.isBlank()) return ""
        return try {
            val u = java.net.URI(wsUrl)
            "http://${u.host}:8002"
        } catch (e: Exception) { "" }
    }

    private fun param(session: IHTTPSession, name: String): String =
        session.parameters[name]?.firstOrNull().orEmpty()

    private fun deviceMac(): String =
        android.provider.Settings.Secure.getString(context.contentResolver, "android_id") ?: "r1-client"

    /**
     * Provision the WS endpoint from an OTA URL (the panel's Connect button). Flexible about
     * input: the trailing slash is optional, and if the OTA endpoint does not answer with a
     * websocket block the url is derived from the OTA host so Connect always yields something to
     * try. The request itself -- and the device identity it must carry -- lives in
     * [ServerProvisioner], shared with the automatic check at start-up.
     */
    private fun handleSetupServer(session: IHTTPSession): String {
        val ota = param(session, "ota").ifBlank { Settings.otaUrl }.trim()
        if (ota.isBlank()) return """{"ok":false,"error":"no ota url"}"""
        Settings.otaUrl = ota
        val r = ServerProvisioner.provision(deviceInfo, ota, deriveOnFailure = true)
        if (!r.ok) return """{"ok":false,"error":${JSONObject.quote(r.error)}}"""
        // What the previous server announced says nothing about this one; until the new one has
        // said hello the panel must not warn about (or vouch for) a format mismatch.
        ServerAudioParams.sampleRate = -1
        ServerAudioParams.channels = -1
        // The panel's fixed servers send along the audio format their server uses, because the two
        // ends cannot negotiate it and a wrong one plays as noise. Playback is opened once at
        // start-up, so a change only takes effect after a restart -- done here, so that picking a
        // server is one tap rather than a tap, two more settings and a restart.
        val sr = param(session, "sr").toIntOrNull()
        val ch = param(session, "ch").toIntOrNull()
        var restart = false
        if (sr != null && sr != Settings.playbackSampleRate) {
            Settings.playbackSampleRate = sr
            restart = true
        }
        if (ch != null && ch != Settings.playbackChannels) {
            Settings.playbackChannels = ch
            restart = true
        }
        if (restart) {
            AppLog.i("Đổi định dạng âm thanh theo máy chủ (${Settings.playbackSampleRate} Hz, ${Settings.playbackChannels} kênh) -> khởi động lại app")
            scheduleRestart()
        }
        return JSONObject()
            .put("ok", true)
            .put("restart", restart)
            .put("ws_url", r.wsUrl)
            .put("src", r.source)
            .put("activation_code", ServerProvisioner.activationCode)
            .put("activation_msg", ServerProvisioner.activationMessage)
            .toString()
    }

    /** GET <base_url>/models with the given key → return the model id list. */
    private fun handleLlmModels(session: IHTTPSession): String {
        val base = param(session, "base_url").ifBlank { Settings.llmBaseUrl }.trimEnd('/')
        val key = param(session, "api_key").ifBlank { Settings.llmApiKey }
        if (base.isBlank()) return """{"ok":false,"error":"no base_url"}"""
        return try {
            val req = Request.Builder().url("$base/models")
                .header("Authorization", "Bearer $key").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return """{"ok":false,"error":"HTTP ${resp.code}"}"""
                val data = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data")
                val ids = JSONArray()
                if (data != null) for (i in 0 until data.length())
                    data.optJSONObject(i)?.optString("id")?.let { ids.put(it) }
                """{"ok":true,"models":$ids}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message ?: "error")}}"""
        }
    }

    /** Validate creds with a tiny /chat/completions ping. Accepts plain-JSON OR SSE replies. */
    private fun handleLlmTest(session: IHTTPSession): String {
        val base = param(session, "base_url").ifBlank { Settings.llmBaseUrl }.trimEnd('/')
        val key = param(session, "api_key").ifBlank { Settings.llmApiKey }
        val model = param(session, "model")
        if (base.isBlank() || model.isBlank()) return """{"ok":false,"error":"base_url+model required"}"""
        val t0 = System.currentTimeMillis()
        return try {
            // NOTE: do NOT send stream:false — some gateways (e.g. OmniRoute combos) 503 with
            // "combo retry limit" when asked for non-streaming, since their pooled providers only
            // stream. We let the provider stream and parse plain-JSON OR SSE below.
            val payload = JSONObject()
                .put("model", model)
                .put("max_tokens", 16)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                .toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$base/chat/completions")
                .header("Authorization", "Bearer $key").post(payload).build()
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    AppLog.e("Test AI model lỗi HTTP ${resp.code}")
                    return """{"ok":false,"error":${JSONObject.quote("HTTP ${resp.code}: ${txt.take(160)}")}}"""
                }
                // Reply may be a plain JSON object or an SSE stream ("data: {chunk}"). Accept both.
                val echoed = extractModelId(txt) ?: model
                val dt = System.currentTimeMillis() - t0
                AppLog.i("Test AI model OK: $echoed (${dt}ms)")
                """{"ok":true,"latency_ms":$dt,"model":${JSONObject.quote(echoed)}}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message ?: "error")}}"""
        }
    }

    /** Extract the "model" field from an OpenAI reply that may be plain JSON or an SSE stream. */
    private fun extractModelId(body: String): String? {
        val t = body.trim()
        if (t.startsWith("{")) {
            return runCatching { JSONObject(t).optString("model", "") }.getOrNull()?.ifBlank { null }
        }
        for (raw in t.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val m = runCatching { JSONObject(payload).optString("model", "") }.getOrNull()
            if (!m.isNullOrBlank()) return m
        }
        return null
    }

    /** GET {ha_url}/api/states → controllable entities: [{entity_id, name, domain}]. */
    private fun handleHaDevices(session: IHTTPSession): String {
        val base = param(session, "ha_url").ifBlank { Settings.haUrl }.trimEnd('/')
        val token = param(session, "token").ifBlank { Settings.haToken }
        if (base.isBlank()) return """{"ok":false,"error":"no ha_url"}"""
        return try {
            val req = Request.Builder().url("$base/api/states")
                .header("Authorization", "Bearer $token").get().build()
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return """{"ok":false,"error":${JSONObject.quote("HTTP ${resp.code}: ${txt.take(120)}")}}"""
                val arr = JSONArray(txt)
                val out = JSONArray()
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val id = e.optString("entity_id", "")
                    if (id.isBlank()) continue
                    val name = e.optJSONObject("attributes")?.optString("friendly_name", "") ?: ""
                    out.put(JSONObject().put("entity_id", id).put("name", name).put("domain", id.substringBefore(".")))
                }
                """{"ok":true,"devices":$out}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message ?: "error")}}"""
        }
    }

    // ── Media Player ──────────────────────────────────────────────────────
    // Proxies to pytube_api (search/download-on-demand); playback itself is delegated to
    // MediaPlayerController. Follows the same OkHttp-proxy pattern as handleLlmModels/handleHaDevices.

    /** GET {pytubeBase()}/v3/search?q=&limit= → reshaped as {"ok":true,"results":[...]}. */
    private fun handleMediaSearch(query: String): String {
        if (localMusic()) return handleMusicServerSearch(query)
        val base = pytubeBase()
        if (base.isBlank()) return """{"ok":false,"error":"no server configured yet (Setup tab)"}"""
        if (query.isBlank()) return """{"ok":true,"results":[]}"""
        return try {
            val url = "$base/v3/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=15"
            val req = Request.Builder().url(url).get().build()
            val body = http.newCall(req).execute().use { it.body?.string().orEmpty() }
            val results = info.dourok.voicebot.media.parseSearchResults(body)
            val arr = JSONArray()
            results.forEach {
                arr.put(
                    JSONObject()
                        .put("video_id", it.videoId).put("title", it.title)
                        .put("artist", it.artist).put("duration", it.duration)
                        .put("thumbnail", it.thumbnailUrl)
                )
            }
            """{"ok":true,"results":$arr}"""
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message ?: "network error")}}"""
        }
    }

    /**
     * Best-effort download-percentage watcher: polls pytube_api's /v3/download_progress/<id>
     * whenever MediaSessionState's now-playing snapshot says a track is downloading. The download
     * itself is now triggered server-side (play_youtube.py's _download), not by this app -- this
     * just observes pytube_api's progress_hooks state, same endpoint as before, different trigger.
     * Runs for the lifetime of the app (started once from init{} below), not per-play.
     */
    @Volatile private var downloadPercent = -1
    @Volatile private var downloadPercentVideoId: String? = null

    init {
        Thread {
            while (true) {
                try {
                    Thread.sleep(700)
                    val np = MediaSessionState.nowPlaying.value
                    if (np.state == MediaPlaybackState.DOWNLOADING && np.videoId != null) {
                        if (np.videoId != downloadPercentVideoId) {
                            downloadPercentVideoId = np.videoId
                            downloadPercent = -1
                        }
                        val base = pytubeBase()
                        if (base.isNotBlank()) {
                            val req = Request.Builder().url("$base/v3/download_progress/${np.videoId}").get().build()
                            http.newCall(req).execute().use { resp ->
                                val percent = try {
                                    JSONObject(resp.body?.string().orEmpty()).optInt("percent", -1)
                                } catch (e: Exception) { -1 }
                                if (percent >= 0) downloadPercent = percent
                            }
                        }
                    } else {
                        downloadPercentVideoId = null
                        downloadPercent = -1
                    }
                } catch (e: Exception) {
                    // best-effort watcher -- ignore and let the next tick retry
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * The panel POSTs the list it is displaying (JSON array, starting at the tapped song) in the
     * BODY, not the query string: 15 songs with titles + thumbnail URLs runs to several KB, and a
     * query string that long silently truncates while still returning 200 (the same trap the
     * Assistant persona hit in [handleSet]).
     */
    private fun handleMediaPlay(session: IHTTPSession): String {
        val body = HashMap<String, String>()
        try {
            session.parseBody(body)
        } catch (e: Exception) {
            Log.e(TAG, "parseBody for /api/media/play: ${e.message}")
            return """{"ok":false,"error":"could not read body"}"""
        }
        val root = try {
            JSONObject(body["postData"].orEmpty())
        } catch (e: Exception) {
            return """{"ok":false,"error":"body must be {items:[...],start_index:N}"}"""
        }
        val items = root.optJSONArray("items")
            ?: return """{"ok":false,"error":"items must be a JSON array"}"""
        if (items.length() == 0) return """{"ok":false,"error":"empty items"}"""
        if (localMusic()) {
            val base = musicBase()
            val tracks = ArrayList<LocalMusicPlayer.Track>()
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val id = it.optString("video_id", "")
                if (id.isEmpty()) continue
                // A radio station in the queue is replayed as a station, not looked up as a song.
                val station = info.dourok.voicebot.media.MusicService.stationOf(id)
                if (station != null) {
                    tracks.add(info.dourok.voicebot.media.MusicService.radioTrack(station))
                    continue
                }
                tracks.add(
                    LocalMusicPlayer.Track(
                        id = id,
                        title = it.optString("title", ""),
                        artist = it.optString("artist", ""),
                        thumbnail = it.optString("thumbnail", ""),
                        duration = it.optString("duration", ""),
                        url = info.dourok.voicebot.media.musicStreamUrl(base, id),
                    )
                )
            }
            if (tracks.isEmpty()) return """{"ok":false,"error":"no playable items"}"""
            LocalMusicPlayer.play(tracks, root.optInt("start_index", 0))
            return """{"ok":true}"""
        }
        MediaCommands.flow.tryEmit(
            MediaCommands.Command.Play(items.toString(), root.optInt("start_index", 0))
        )
        return """{"ok":true}"""
    }

    /**
     * Saves the whole News section atomically (unlike the per-field `set()` used elsewhere): the
     * checklist order + enabled flags only make sense together, not as independent key/value
     * writes. Body: {"enabled":bool,"time":"HH:mm","voice":"...","categories":[{"key":...,
     * "enabled":bool},...]} (array order = playback order). Persists locally (Settings, read by
     * NewsAlarmScheduler), re-derives the daily alarms, and best-effort pushes the same payload to
     * the server's durable copy (core/news/store.py) so a server restart doesn't lose it -- a
     * failed push isn't fatal, the device is still the source of truth for the next save.
     */
    private fun handleNewsSave(session: IHTTPSession): String {
        val body = HashMap<String, String>()
        try {
            session.parseBody(body)
        } catch (e: Exception) {
            Log.e(TAG, "parseBody for /api/news/save: ${e.message}")
            return """{"ok":false,"error":"could not read body"}"""
        }
        val root = try {
            JSONObject(body["postData"].orEmpty())
        } catch (e: Exception) {
            return """{"ok":false,"error":"body must be JSON"}"""
        }

        val enabled = root.optBoolean("enabled", false)
        val time = root.optString("time", "07:00")
        val voice = root.optString("voice", "")
        val categoriesArr = root.optJSONArray("categories") ?: JSONArray()
        val csvParts = mutableListOf<String>()
        for (i in 0 until categoriesArr.length()) {
            val c = categoriesArr.optJSONObject(i) ?: continue
            val key = c.optString("key", "")
            if (key.isBlank()) continue
            csvParts.add("$key:${if (c.optBoolean("enabled", true)) 1 else 0}")
        }

        Settings.newsEnabled = enabled
        Settings.newsTime = time
        Settings.newsVoice = voice
        if (csvParts.isNotEmpty()) Settings.newsCategories = csvParts.joinToString(",")

        AppLog.i("Lưu Bản tin: ${if (enabled) "bật $time" else "tắt"}, mục=[${csvParts.joinToString(",")}], giọng=$voice")
        info.dourok.voicebot.news.NewsAlarmScheduler.reschedule(context)

        val base = info.dourok.voicebot.news.NewsAlarmScheduler.serverHttpBase()
        if (base.isNotBlank()) {
            try {
                val deviceId = info.dourok.voicebot.news.NewsAlarmScheduler.deviceId(context)
                val payload = JSONObject()
                    .put("enabled", enabled)
                    .put("time", time)
                    .put("voice", voice)
                    .put("categories", categoriesArr)
                    .toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$base/news/config?device_id=${java.net.URLEncoder.encode(deviceId, "UTF-8")}")
                    .post(payload).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "news config push: HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "news config push failed: ${e.message}")
            }
        }
        return """{"ok":true}"""
    }

    /** Incremental: only entries newer than [since], so polling doesn't resend the whole buffer. */
    private fun buildLogs(since: Long): String {
        val arr = JSONArray()
        AppLog.since(since).forEach {
            arr.put(JSONObject()
                .put("seq", it.seq).put("lvl", it.level.name)
                .put("t", it.time).put("m", it.msg))
        }
        return JSONObject().put("last", AppLog.lastSeq()).put("items", arr).toString()
    }

    private fun buildMediaState(): String {
        if (localMusic()) return LocalMusicPlayer.snapshot()
        val np = MediaSessionState.nowPlaying.value
        val queueArr = JSONArray()
        MediaSessionState.queue.value.forEach {
            queueArr.put(
                JSONObject()
                    .put("video_id", it.videoId).put("title", it.title)
                    .put("artist", it.artist).put("thumbnail", it.thumbnail)
                    .put("duration", it.duration)
            )
        }
        return JSONObject()
            .put("state", np.state.name.lowercase())
            .put("video_id", np.videoId)
            .put("title", np.title)
            .put("artist", np.artist)
            .put("cover_url", np.thumbnail)
            .put("duration_s", np.durationS)
            .put("position_s", np.positionS)
            .put("download_percent", if (np.state == MediaPlaybackState.DOWNLOADING) downloadPercent else -1)
            .put("queue", queueArr)
            .toString()
    }

    /** GET {ha_url}/api/ → HA ping (validates URL + token). */
    private fun handleHaTest(session: IHTTPSession): String {
        val base = param(session, "ha_url").ifBlank { Settings.haUrl }.trimEnd('/')
        val token = param(session, "token").ifBlank { Settings.haToken }
        if (base.isBlank()) return """{"ok":false,"error":"no ha_url"}"""
        return try {
            val req = Request.Builder().url("$base/api/").header("Authorization", "Bearer $token").get().build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) """{"ok":true}"""
                else """{"ok":false,"error":${JSONObject.quote("HTTP ${resp.code}")}}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message ?: "error")}}"""
        }
    }

    private fun buildRadioStations(): String {
        val arr = JSONArray()
        info.dourok.voicebot.media.RadioStations.all().forEach {
            arr.put(JSONObject().put("id", it.key).put("name", it.name).put("short", it.shortName))
        }
        return JSONObject().put("ok", true).put("stations", arr).toString()
    }

    private fun handleRadioPlay(id: String): String {
        val station = info.dourok.voicebot.media.RadioStations.byKey(id)
            ?: return """{"ok":false,"error":"unknown station"}"""
        LocalMusicPlayer.play(listOf(info.dourok.voicebot.media.MusicService.radioTrack(station)), 0)
        return """{"ok":true}"""
    }

    /**
     * One radio station as a continuous audio stream, for the on-device player. The station
     * broadcasts HLS over https; [info.dourok.voicebot.media.HlsAudioStream] follows it (with the
     * app's own, current root certificates) and this hands the audio on over plain local http.
     * Only stations from the built-in list can be asked for, so this is not an open proxy.
     */
    private fun serveRadioStream(id: String): Response {
        val station = info.dourok.voicebot.media.RadioStations.byKey(id)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "unknown station")
        return try {
            val stream = info.dourok.voicebot.media.HlsAudioStream.open(station.urls, "db-robot-r1/$appVersion")
            NanoHTTPD.newChunkedResponse(Response.Status.OK, stream.contentType(), stream)
        } catch (e: Exception) {
            AppLog.e("Radio ${station.name}: không mở được luồng phát (${e.message})")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "stream error")
        }
    }

    /** Body: the place the owner picked from /api/location/search, as that call returned it. */
    private fun handleLocationSet(session: IHTTPSession): String {
        val body = HashMap<String, String>()
        try {
            session.parseBody(body)
        } catch (e: Exception) {
            return """{"ok":false,"error":"bad request"}"""
        }
        return try {
            val j = JSONObject(body["postData"] ?: "")
            val label = j.optString("label").trim()
            val lat = j.optDouble("lat", Double.NaN)
            val lon = j.optDouble("lon", Double.NaN)
            if (label.isEmpty() || lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                """{"ok":false,"error":"thiếu tên hoặc toạ độ"}"""
            } else {
                LocationManager.setPlace(label, lat, lon, j.optString("tz").trim())
                LocationManager.stateJson()
            }
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message ?: "error")}}"""
        }
    }

    private fun scheduleRestart() {
        Thread {
            try {
                Thread.sleep(400) // let the HTTP response flush first
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(intent)
                Runtime.getRuntime().exit(0)
            } catch (e: Exception) {
                Log.e(TAG, "restart: ${e.message}")
            }
        }.start()
    }

    private fun serveAsset(): Response {
        // /sdcard/control.html overrides the bundled page -> tweak UI without rebuilding.
        val override = java.io.File("/sdcard/control.html")
        val bytes = if (override.exists() && override.length() > 0) override.readBytes()
        else context.assets.open("control.html").readBytes()
        return newFixedLengthResponse(
            Response.Status.OK, "text/html", ByteArrayInputStream(bytes), bytes.size.toLong(),
        ).apply {
            // The page is edited in place (often straight onto /sdcard) and re-read constantly, so
            // a cached copy is never what anyone wants. Safari in particular held onto an old
            // build hard enough that a fix looked broken while the same file loaded fine under a
            // different query string -- an hour of chasing a bug that was not in the code.
            addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
            addHeader("Pragma", "no-cache")
            addHeader("Expires", "0")
        }
    }

    private fun serveWav(): Response {
        val wav = MicTest.wav()
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no recording")
        return newFixedLengthResponse(
            Response.Status.OK, "audio/wav", ByteArrayInputStream(wav), wav.size.toLong(),
        ).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Cache-Control", "no-store")
        }
    }

    /**
     * Every Bluetooth call can fail for a reason worth reading -- three of them reach hidden
     * framework methods by reflection, which misses only at runtime -- so the reason is carried to
     * the panel instead of being logged where nobody looks.
     */
    private fun json(r: BtResult): Response = json(
        JSONObject().put("ok", r.ok).put("error", r.error).toString()
    )

    private fun buildBtState(): String {
        val st = bt.state()
        val devices = JSONArray()
        st.devices.forEach { d ->
            devices.put(JSONObject().apply {
                put("address", d.address)
                put("name", d.name)
                put("bonded", d.bonded)
                // Carried rather than filtered on, so the panel's "show everything" toggle costs no
                // round trip -- cheap speakers do misdeclare their class.
                put("audio", d.audio)
                put("connected", d.connected)
                d.rssi?.let { put("rssi", it) }
            })
        }
        return JSONObject().apply {
            put("supported", st.supported)
            put("enabled", st.enabled)
            put("scanning", st.scanning)
            put("auto_reconnect", st.autoReconnect)
            put("connected", st.connectedAddress)
            put("busy", st.busy)
            put("error", st.error)
            put("devices", devices)
        }.toString()
    }

    private fun json(s: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", s).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }

    companion object {
        private const val TAG = "ControlServer"
        private const val PYTUBE_PORT = 114   // services/pytube_api.py binds this
        const val PORT = 8088
        /** How long an effect chosen in the panel stays on the ring before the real state returns. */
        private const val LED_PREVIEW_MS = 6_000L
        /** Phrase that makes the server's get_news_bulletin tool fire. Shared with
         * NewsAlarmReceiver so the button and the daily alarm cannot drift apart. */
        const val NEWS_PHRASE = "đọc bản tin"
    }
}
