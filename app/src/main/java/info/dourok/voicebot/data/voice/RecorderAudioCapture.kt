package info.dourok.voicebot.data.voice

import info.dourok.voicebot.AudioRecorder
import info.dourok.voicebot.domain.voice.AudioCapture
import kotlinx.coroutines.flow.Flow

/** Microphone capture backed by [AudioRecorder] (16 kHz mono PCM16, AEC + noise suppression). */
class RecorderAudioCapture(
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
    private val frameSizeMs: Int = 60,
) : AudioCapture {

    private var recorder: AudioRecorder? = null

    override fun start(): Flow<ByteArray> {
        val r = AudioRecorder(sampleRate, channels, frameSizeMs)
        recorder = r
        return r.startRecording()
    }

    override fun stop() {
        recorder?.stopRecording()
        recorder = null
    }

    override fun drainBuffered() {
        recorder?.drainBuffered()
    }
}
