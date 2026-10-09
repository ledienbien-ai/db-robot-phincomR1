package info.dourok.voicebot.domain.voice

import kotlinx.coroutines.flow.Flow

/** Equalizer band layout (center frequencies in Hz + the device's gain range in millibels). */
data class EqInfo(val freqsHz: IntArray, val minMb: Int, val maxMb: Int)

/** Plays audio (TTS / music) streamed from the server as a flow of Opus frames. */
interface AudioPlayback {
    /**
     * Start consuming [opusFrames], decoding and playing them.
     * @param onPlaying invoked for each frame received (e.g. to mark the SPEAKING state).
     */
    fun start(opusFrames: Flow<ByteArray>, onPlaying: () -> Unit)

    /** Suspend until the currently buffered audio has finished playing. */
    suspend fun awaitCompletion()

    /** Discard any buffered audio immediately (used when the user interrupts playback). */
    fun flush()

    /** Re-read the playback tuning (equalizer + loudness) from Settings and apply it live. */
    fun applyAudioSettings()

    /** Equalizer band layout for the control panel, or null if the device has no equalizer. */
    fun eqInfo(): EqInfo?

    /** Stop playback and release native resources. */
    fun release()
}
