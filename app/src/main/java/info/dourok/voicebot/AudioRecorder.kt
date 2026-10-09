package info.dourok.voicebot

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import info.dourok.voicebot.data.Settings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class AudioRecorder(
    private val sampleRate: Int,
    private val channels: Int,
    private val frameSizeMs: Int
) {
    companion object {
        private const val TAG = "AudioRecorder"
    }

    private val channelConfig = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        channelConfig,
        AudioFormat.ENCODING_PCM_16BIT
    ) * 2
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private val frameSize = (sampleRate * frameSizeMs) / 1000
    private val frameBytes = frameSize * channels * 2 // 16-bit PCM
    private val audioChannel = Channel<ByteArray>(capacity = 50)


    @SuppressLint("MissingPermission")
    fun startRecording(): Flow<ByteArray> {
        // AudioSource đổi qua panel (Settings.micSource) để thử far-field — mỗi source HAL gain/tuning
        // khác nhau (xem AppConfig.MIC_SOURCE). Mặc định 6=VOICE_RECOGNITION.
        val src = Settings.micSource
        Log.i(TAG, "AudioRecord source=$src")
        audioRecord = AudioRecord(
            src,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        ).apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                // Initialize the acoustic echo canceler.
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(audioSessionId).apply {
                        enabled = true
                        Log.i(TAG, "AEC initialized")
                    }
                } else {
                    Log.w(TAG, "AEC not available on this device")
                }

                if(NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(audioSessionId).apply {
                        enabled = true
                        Log.i(TAG, "NS initialized")
                    }
                } else {
                    Log.w(TAG, "NS not available on this device")
                }

                // AGC: tự khuếch đại giọng nhỏ/xa (far-field). Không có thì dựa boost phần mềm dưới.
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(audioSessionId).apply {
                        enabled = true
                        Log.i(TAG, "AGC initialized")
                    }
                } else {
                    Log.w(TAG, "AGC not available on this device")
                }

                startRecording()
                Log.i(TAG, "Started recording")
            } else {
                throw IllegalStateException("Failed to initialize AudioRecord")
            }
        }

        Thread {
            val buffer = ByteArray(frameBytes)
            var winPeak = 0
            var frames = 0
            while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord?.read(buffer, 0, frameBytes) ?: 0
                if (read > 0) {
                    // Monitor mức THU THÔ (trước gain/opus) -> so sánh AudioSource khi test far-field.
                    var i = 0
                    while (i + 1 < read) {
                        val s = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                        val a = if (s >= 0) s else -s
                        if (a > winPeak) winPeak = a
                        i += 2
                    }
                    if (++frames >= 25) { // ~1.5s (frame 60ms)
                        Log.i(TAG, "capture peak=%.3f (src=%d)".format(winPeak / 32768f, Settings.micSource))
                        winPeak = 0; frames = 0
                    }
                    // Thu THÔ -> wake-detect nhận tín hiệu sạch. Gain cho STT làm ở SttAgc (sau khi
                    // tách nhánh STT trong VoiceAssistant), không boost ở đây nữa.
                    audioChannel.trySend(buffer.copyOf(read)).isSuccess
                }
            }
        }.start()

        return audioChannel.receiveAsFlow()
    }

    /** Discard whatever's queued (e.g. audio buffered while a connect was blocking the collector). */
    fun drainBuffered() {
        var dropped = 0
        while (audioChannel.tryReceive().isSuccess) dropped++
        if (dropped > 0) Log.i(TAG, "drainBuffered: dropped $dropped stale frame(s)")
    }

    fun stopRecording() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        aec?.enabled = false
        aec?.release()
        aec = null
        ns?.enabled = false
        ns?.release()
        ns = null
        agc?.enabled = false
        agc?.release()
        agc = null
        audioChannel.close()
        Log.i(TAG, "Stopped recording")
    }
}