package info.dourok.voicebot.domain.voice

import kotlinx.coroutines.flow.Flow

/** Captures microphone audio as a stream of PCM16 mono 16 kHz frames. */
interface AudioCapture {
    /** Start recording and return a hot flow of PCM frames. */
    fun start(): Flow<ByteArray>

    /** Stop recording and release the microphone. */
    fun stop()

    /** Discard whatever's already buffered (e.g. audio captured while a connect was in flight). */
    fun drainBuffered()
}
