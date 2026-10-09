package info.dourok.voicebot.domain.voice

/** Detects a wake word in a stream of PCM16 mono 16 kHz audio frames. */
interface WakeWordDetector {
    /** Whether the detector initialized successfully and is usable. */
    val isReady: Boolean

    /**
     * Feed one PCM16 (little-endian) mono 16 kHz frame.
     * @return true if the wake word was detected in this frame.
     */
    fun process(pcm: ByteArray): Boolean

    /** Reset internal detection state (call after a detection or when resuming). */
    fun reset()

    /**
     * Use a stricter threshold while audio is playing back (TTS / music) so the speaker output
     * does not false-trigger a wake. Implementations should be cheap when the value is unchanged.
     */
    fun setStrict(strict: Boolean)
}
