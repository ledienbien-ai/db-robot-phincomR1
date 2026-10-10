package info.dourok.voicebot.data

import android.content.Context

/**
 * Runtime, persisted settings the on-device control panel can change without rebuilding.
 * Defaults come from [AppConfig]. Call [init] once (VApplication). Read with the same static
 * style as AppConfig (e.g. Settings.micGain). Some changes apply live (eq/volume), others need an
 * app restart (sample rate / mic source).
 */
object Settings {
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("voicebot_settings", Context.MODE_PRIVATE)
    }

    var wakeSensitivity: String
        get() = prefs.getString("wake_sensitivity", AppConfig.WAKE_SENSITIVITY)!!
        set(v) = prefs.edit().putString("wake_sensitivity", v).apply()

    /** Wake nhạy khi đang nói/nghe nhạc (thấp hơn để TTS/nhạc không tự kích wake). */
    var wakeSensitivitySpeaking: String
        get() = prefs.getString("wake_sensitivity_speaking", AppConfig.WAKE_SENSITIVITY_SPEAKING)!!
        set(v) = prefs.edit().putString("wake_sensitivity_speaking", v).apply()

    /** "Mai ơi" detection threshold, 0..1. Wider real-world margin than Snowboy's sensitivity. */
    var maiOiThreshold: String
        get() = prefs.getString("mai_oi_threshold", AppConfig.MAI_OI_THRESHOLD)!!
        set(v) = prefs.edit().putString("mai_oi_threshold", v).apply()

    /** "Mai ơi" threshold khi đang nói/nghe nhạc (stricter, tương tự wakeSensitivitySpeaking). */
    var maiOiThresholdSpeaking: String
        get() = prefs.getString("mai_oi_threshold_speaking", AppConfig.MAI_OI_THRESHOLD_SPEAKING)!!
        set(v) = prefs.edit().putString("mai_oi_threshold_speaking", v).apply()

    var micGain: Float
        get() = prefs.getFloat("mic_gain", AppConfig.MIC_GAIN)
        set(v) = prefs.edit().putFloat("mic_gain", v).apply()

    /** AudioRecord input source (MediaRecorder.AudioSource int). Thử far-field; cần restart capture. */
    var micSource: Int
        get() = prefs.getInt("mic_source", AppConfig.MIC_SOURCE)
        set(v) = prefs.edit().putInt("mic_source", v).apply()

    /** AGC luồng STT (trước opus, không đụng wake). Bật/tắt + target/maxGain chỉnh live, áp từ lần wake sau. */
    var agcEnabled: Boolean
        get() = prefs.getBoolean("agc_enabled", AppConfig.AGC_ENABLED)
        set(v) = prefs.edit().putBoolean("agc_enabled", v).apply()
    var agcTarget: Float
        get() = prefs.getFloat("agc_target", AppConfig.AGC_TARGET)
        set(v) = prefs.edit().putFloat("agc_target", v).apply()
    var agcMaxGain: Float
        get() = prefs.getFloat("agc_max_gain", AppConfig.AGC_MAX_GAIN)
        set(v) = prefs.edit().putFloat("agc_max_gain", v).apply()

    /** Mã LED theo trạng thái (CSV code sendMsg 4096). Chỉnh live qua panel để sweep màu/hiệu ứng. */
    var ledIdle: String
        get() = prefs.getString("led_idle", AppConfig.LED_IDLE)!!
        set(v) = prefs.edit().putString("led_idle", v).apply()
    var ledListening: String
        get() = prefs.getString("led_listening", AppConfig.LED_LISTENING)!!
        set(v) = prefs.edit().putString("led_listening", v).apply()
    var ledSpeaking: String
        get() = prefs.getString("led_speaking", AppConfig.LED_SPEAKING)!!
        set(v) = prefs.edit().putString("led_speaking", v).apply()
    var ledMusic: String
        get() = prefs.getString("led_music", AppConfig.LED_MUSIC)!!
        set(v) = prefs.edit().putString("led_music", v).apply()

    /** System STREAM_MUSIC volume, 0..100 (-1 = don't override). */
    var volume: Int
        get() = prefs.getInt("volume", -1)
        set(v) = prefs.edit().putInt("volume", v).apply()

    /** Equalizer on/off. */
    var eqEnabled: Boolean
        get() = prefs.getBoolean("eq_enabled", false)
        set(v) = prefs.edit().putBoolean("eq_enabled", v).apply()

    /** Per-band gain in millibels (one entry per equalizer band), CSV-encoded. */
    var eqBands: IntArray
        get() = prefs.getString("eq_bands", "")!!
            .split(",").mapNotNull { it.trim().toIntOrNull() }.toIntArray()
        set(v) = prefs.edit().putString("eq_bands", v.joinToString(",")).apply()

    /** LoudnessEnhancer target gain in millibels; 0 = off. This is what answers "nghe quá nhỏ". */
    var loudnessMb: Int
        get() = prefs.getInt("loudness_mb", AppConfig.LOUDNESS_MB)
        set(v) = prefs.edit().putInt("loudness_mb", v.coerceIn(0, 2000)).apply()

    /** Playback sample rate (16000 / 24000 / 48000). Applied on app restart; must match the server. */
    var playbackSampleRate: Int
        get() = prefs.getInt("playback_sr", AppConfig.PLAYBACK_SAMPLE_RATE)
        set(v) = prefs.edit().putInt("playback_sr", v).apply()

    // ── Generic client config (Setup tab) ──────────────────────────────────
    var llmProvider: String
        get() = prefs.getString("llm_provider", "")!!
        set(v) = prefs.edit().putString("llm_provider", v).apply()
    var llmBaseUrl: String
        get() = prefs.getString("llm_base_url", "")!!
        set(v) = prefs.edit().putString("llm_base_url", v).apply()
    var llmApiKey: String
        get() = prefs.getString("llm_api_key", "")!!
        set(v) = prefs.edit().putString("llm_api_key", v).apply()
    var llmModel: String
        get() = prefs.getString("llm_model", "")!!
        set(v) = prefs.edit().putString("llm_model", v).apply()
    var llmTransport: String
        get() = prefs.getString("llm_transport", "openai")!!
        set(v) = prefs.edit().putString("llm_transport", v).apply()

    var otaUrl: String
        get() = prefs.getString("ota_url", AppConfig.OTA_URL)!!
        set(v) = prefs.edit().putString("ota_url", v).apply()
    /** Base url of the music server behind the Media tab; "" = upstream server-driven media. */
    var musicUrl: String
        get() = prefs.getString("music_url", AppConfig.MUSIC_URL)!!
        set(v) = prefs.edit().putString("music_url", v.trim()).apply()
    /** Install a newer build by itself once the speaker is idle (off = only offer it in the panel). */
    var autoUpdate: Boolean
        get() = prefs.getBoolean("auto_update", false)
        set(v) = prefs.edit().putBoolean("auto_update", v).apply()
    /** "versionCode:attempts" of the automatic installs already tried; see UpdateManager. */
    var autoUpdateTried: String
        get() = prefs.getString("auto_update_tried", "")!!
        set(v) = prefs.edit().putString("auto_update_tried", v).apply()
    var wsUrl: String
        get() = prefs.getString("ws_url", "")!!
        set(v) = prefs.edit().putString("ws_url", v).apply()
    var wsToken: String
        get() = prefs.getString("ws_token", "")!!
        set(v) = prefs.edit().putString("ws_token", v).apply()

    /** Wake engine: "alexa" (snowboy), "nabu" (microWakeWord), or "mai_oi". Changing needs an app restart. */
    var wakeEngine: String
        get() = prefs.getString("wake_engine", AppConfig.WAKE_ENGINE)!!
        set(v) = prefs.edit().putString("wake_engine", v).apply()

    /** Playback channels (1 / 2). Applied on app restart; must match the server. */
    var playbackChannels: Int
        get() = prefs.getInt("playback_ch", AppConfig.PLAYBACK_CHANNELS)
        set(v) = prefs.edit().putInt("playback_ch", if (v == 2) 2 else 1).apply()

    // ── Bluetooth audio out (Setup tab) ─────────────────────────────────────
    /** Address of the last speaker/headphones connected on purpose. "" = none remembered. */
    var btLastDevice: String
        get() = prefs.getString("bt_last_device", "")!!
        set(v) = prefs.edit().putString("bt_last_device", v).apply()

    /**
     * Reach for [btLastDevice] again at app start, when the adapter comes on, and when the speaker
     * itself reconnects. On by default: the daily bulletin reads at a fixed time, and after a power
     * cut nobody is standing at the panel to press Connect first.
     */
    var btAutoReconnect: Boolean
        get() = prefs.getBoolean("bt_auto_reconnect", true)
        set(v) = prefs.edit().putBoolean("bt_auto_reconnect", v).apply()

    // ── Home Assistant (Setup tab) ──────────────────────────────────────────
    var haUrl: String
        get() = prefs.getString("ha_url", "")!!
        set(v) = prefs.edit().putString("ha_url", v).apply()
    var haToken: String
        get() = prefs.getString("ha_token", "")!!
        set(v) = prefs.edit().putString("ha_token", v).apply()
    /** Device list: newline of `location,name,entity_id` lines (fed to the prompt server-side). */
    var haDevices: String
        get() = prefs.getString("ha_devices", "")!!
        set(v) = prefs.edit().putString("ha_devices", v).apply()

    /** Persona ghi đè server cho phiên này (Assistant card). Trống = server dùng prompt: mặc định. */
    var customPrompt: String
        get() = prefs.getString("custom_prompt", "")!!
        set(v) = prefs.edit().putString("custom_prompt", v).apply()

    // ── News bulletin (Setting tab) ──────────────────────────────────────────
    // The server (xiaozhi-esp32-server) holds the durable copy + does the actual fetch/LLM/TTS
    // work (core/news/*.py) -- these fields are just what NewsAlarmScheduler needs on-device to
    // know WHEN to wake up, plus what gets POSTed to /news/config on every save.
    var newsEnabled: Boolean
        get() = prefs.getBoolean("news_enabled", false)
        set(v) = prefs.edit().putBoolean("news_enabled", v).apply()

    /** "HH:mm", 24h, local time. */
    var newsTime: String
        get() = prefs.getString("news_time", "07:00")!!
        set(v) = prefs.edit().putString("news_time", v).apply()

    /** Ordered "key:0|1" pairs, comma-separated -- string order IS playback order (reorder ⇒
     * rewrite this whole string). Keys: society(trong nước)/world(nước ngoài)/tech(công nghệ)/
     * weather(thời tiết)/power(cúp điện). */
    var newsCategories: String
        get() = prefs.getString(
            "news_categories", "society:1,world:1,tech:1,weather:1,power:1"
        )!!
        set(v) = prefs.edit().putString("news_categories", v).apply()

    /** Dedicated VieNeu voice for the bulletin, independent of the normal conversation voice. */
    var newsVoice: String
        get() = prefs.getString("news_voice", "")!!
        set(v) = prefs.edit().putString("news_voice", v).apply()
}

/** Mask an API key for read-back: empty stays empty; <=4 chars fully masked; else ••••<last4>. */
fun maskApiKey(key: String?): String {
    if (key.isNullOrEmpty()) return ""
    if (key.length <= 4) return "••••"
    return "••••" + key.takeLast(4)
}
