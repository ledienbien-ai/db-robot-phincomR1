package info.dourok.voicebot.domain.voice.model

/** High-level state of the voice assistant runtime. */
enum class VoiceState {
    /** Not awake — waiting for the wake word, no server connection. */
    IDLE,

    /** Awake and listening to the user's speech (mic is streamed to the server). */
    LISTENING,

    /** Assistant is speaking (TTS) or playing music (audio is streamed from the server). */
    SPEAKING,
}
