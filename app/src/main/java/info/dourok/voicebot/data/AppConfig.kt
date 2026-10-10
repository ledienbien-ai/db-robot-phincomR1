package info.dourok.voicebot.data

/**
 * Central place for tweakable settings. Change a value here, then rebuild.
 * (Kept in one object so the knobs aren't scattered across the codebase.)
 */
object AppConfig {

    /** Server WebSocket URL. Empty by default (generic build): set at runtime via the Setup tab
     *  (OTA URL → provisioned ws url/token). No deployment-specific endpoint is baked in. */
    const val WS_URL = ""

    /**
     * OTA endpoint asked at start-up for the WebSocket url/token and the activation code (see
     * [ServerProvisioner]). This is the DB-Robot server; a different one can be set at runtime
     * from the control panel's Setup tab without rebuilding.
     */
    const val OTA_URL = "https://sv1.dbrobot.vn/xiaozhi/ota/"

    /**
     * Music server the Media tab searches and plays from (`/stream_pcm?song=` + the MP3 stream it
     * points at). This is the DB-Robot one; the panel's Setup tab takes a custom address instead,
     * and a blank one falls back to the upstream server-driven media path.
     */
    const val MUSIC_URL = "https://ms.dbrobot.vn"

    /**
     * Manifest the app reads to learn that a newer build exists (see update/Updater). It is the
     * `update.json` attached to the newest GitHub release of the DB-Robot repository, which the
     * release workflow writes together with the APK it describes. Must be https.
     */
    const val UPDATE_URL =
        "https://github.com/ledienbien-ai/db-robot-phincomR1/releases/latest/download/update.json"

    /**
     * Time zone assumed until the owner picks a city in the panel's Setup tab (see
     * weather/LocationManager). The R1's ROM is set to China and the server answers with its own
     * clock; this product is used in Vietnam.
     */
    const val TIME_ZONE = "Asia/Ho_Chi_Minh"

    /** Wake engine used until one is picked in the panel: "nabu" = microWakeWord "OK Nabu". */
    const val WAKE_ENGINE = "nabu"

    /**
     * Playback (server -> device TTS/music) sample rate. MUST match the server's
     * xiaozhi.audio_params.sample_rate. Higher = better music/voice quality (16k=8kHz band,
     * 24k=12kHz, 48k=full). Mic stays 16k (STT). Lower if the device stutters at 48k.
     */
    const val PLAYBACK_SAMPLE_RATE = 16000   // sv1.dbrobot.vn announces opus 16000 Hz in its hello

    /**
     * Playback channels (1 = mono, 2 = stereo). Must match the server's
     * xiaozhi.audio_params.channels: the two ends cannot negotiate it, so a mismatch is not a
     * quality loss but mis-framed audio. Stereo since 26/09/2026 -- music was downmixed to mono on
     * the server before Opus saw it, and no bitrate buys the other channel back.
     */
    const val PLAYBACK_CHANNELS = 1          // ...and mono; measured 2026-10-09, whatever the client asks for

    /**
     * Snowboy "Alexa" sensitivity while idle, 0..1 (higher = easier to trigger).
     * 2026-06-28: 0.8 -> 0.5 — 0.8 + boost gain 3x làm TV/tiếng ồn tự kích wake. Gain không cải
     * thiện SNR (khuếch đại cả giọng lẫn ồn) nên hạ gain + hạ sensitivity. Far Alexa khó thì
     * tăng SnowboyDetect.SetAudioGain (chỉ khuếch đại TRONG snowboy, không đụng STT), đừng tăng cái này.
     */
    const val WAKE_SENSITIVITY = "0.5"

    /**
     * "Mai ơi" detection threshold, 0..1 (score >= threshold triggers wake — LOWER threshold
     * means MORE sensitive, opposite of Snowboy's SetSensitivity direction). 2026-07-13: the
     * original 0.5/0.5 pair (equal normal/speaking values) was wrong — it was based on
     * real-hardware eval against isolated clips (real "Mai ơi" ~0.95-1.0, hard negatives
     * ~0.00-0.02), which never tested the actual self-hearing scenario: this device has no
     * AEC (confirmed in logcat), so during TTS playback the mic genuinely picks up the
     * speaker's own output, and an equal "strict" threshold gave that zero extra protection
     * -> self-triggered wake right after the assistant finished speaking. 0.8 while speaking
     * (vs 0.5 normally) gives real strictness margin, mirroring why Snowboy's own 0.5/0.4
     * split exists at all — same purpose, opposite direction because the underlying value is
     * a threshold, not a sensitivity.
     */
    const val MAI_OI_THRESHOLD = "0.5"
    const val MAI_OI_THRESHOLD_SPEAKING = "0.8"

    /**
     * Software mic gain applied to captured PCM. 2026-06-28: 3.0 -> 1.0 (TẮT) — 3.0 + tanh làm
     * MÉO sóng (clip/nén) khiến PhoWhisper đọc sai, và khuếch đại ồn gây false-wake. Để AGC (HAL +
     * AutomaticGainControl) lo far-field thay vì boost cứng. 1.0 = transparent (applyGain bỏ qua).
     * Far-field còn yếu thì nhích ~1.5 và CÂN NHẮC tách wake-detect khỏi luồng đã gain.
     */
    const val MIC_GAIN = 1.0f

    /**
     * AudioRecord input source — đổi để thử far-field (mỗi source HAL tuning/gain khác nhau):
     * 6=VOICE_RECOGNITION (mic-array, sạch — đang dùng), 7=VOICE_COMMUNICATION (call path: AEC+AGC,
     * thường THU TO hơn), 1=MIC (thô), 0=DEFAULT, 5=CAMCORDER. Đổi runtime qua panel key `mic_source`
     * + restart (không cần build lại).
     */
    const val MIC_SOURCE = 6

    /**
     * AGC luồng STT (áp TRƯỚC opus, KHÔNG đụng wake): kéo giọng xa/nhỏ lên mức chuẩn cho opus+Whisper.
     * AGC_TARGET = đỉnh mục tiêu (0..1); AGC_MAX_GAIN = trần khuếch đại. Bật/tắt + chỉnh runtime qua
     * panel (agc_enabled / agc_target / agc_max_gain) khỏi build lại. Tắt -> STT nhận audio thô.
     */
    const val AGC_ENABLED = true
    const val AGC_TARGET = 0.35f
    const val AGC_MAX_GAIN = 30f

    /**
     * Mã LED theo trạng thái — sendMsg(4096, code). Mỗi trạng thái 1 DÃY code CSV (gửi tuần tự, làm
     * hiệu ứng nhiều bước). Mặc định từ aiboxplus (501=listening, 204=speaking, 504=idle). Vì không
     * nhìn thấy đèn từ máy build -> sweep code qua panel (led_*) rồi quan sát. Hiệu ứng có sẵn aiboxplus:
     * breathing "505,309,503", lỗi "309,210". Áp dụng từ lần đổi trạng thái sau.
     */
    const val LED_IDLE = "504"
    const val LED_LISTENING = "501"
    const val LED_SPEAKING = "204"
    const val LED_MUSIC = "309"
    /**
     * Target gain for the platform's LoudnessEnhancer, in millibels. It is the one effect registered
     * on this box that raises level with a limiter under it (`audio_effects.conf` -> libldnhncr.so),
     * so it -- not the equalizer -- is what answers "nghe quá nhỏ".
     *
     * Defaults to **off**, unlike the tone curves. This household has already measured what raising
     * level blind costs: run_vieneu.sh carries the note from 2026-07-25 that +3 dB of TTS boost
     * crackled on the internal speaker while the raw WAV was clean, so the ceiling there is analog.
     * The case this exists for is a Bluetooth speaker, which has its own amplifier and its own
     * ceiling -- a number that belongs to the room, not to every install.
     */
    const val LOUDNESS_MB = 0


    /**
     * Sensitivity used while the assistant is speaking / playing music. Kept LOW so the assistant's
     * own TTS voice / music doesn't echo back into the mic and false-trigger a wake — that would
     * cause an abort-resume feedback loop. Barge-in still works with a clear, loud "Alexa" (or just
     * use the hardware button, which always interrupts). Raise carefully if barge-in is too hard.
     * 2026-06-28: 0.6 -> 0.4 cho khớp việc hạ sensitivity idle.
     */
    const val WAKE_SENSITIVITY_SPEAKING = "0.4"
}
