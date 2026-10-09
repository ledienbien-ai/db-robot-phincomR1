package info.dourok.voicebot.data.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import info.dourok.voicebot.R
import info.dourok.voicebot.domain.voice.SoundEffects

/**
 * Âm báo phát qua [AudioTrack] trên STREAM_MUSIC (cùng đường TTS nên chắc chắn nghe rõ).
 *  - playWake() = "start of request" = res/raw/start_of_request.wav  (mọi sự kiện "bắt đầu")
 *  - playStop() = "end of request"   = res/raw/end_of_request.wav    (mọi sự kiện "kết thúc")
 * Đổi âm: thay 2 file wav trong res/raw (PCM16; sample-rate đọc từ header nên rate nào cũng được).
 */
class AudioTrackSoundEffects(context: Context) : SoundEffects {

    private val startPcm: Pcm? = loadWav(context, R.raw.start_of_request)
    private val endPcm: Pcm? = loadWav(context, R.raw.end_of_request)

    override fun playWake(): Long = play(startPcm)
    override fun playStop(): Long = play(endPcm)

    private class Pcm(val data: ShortArray, val sr: Int)

    /** Đọc WAV PCM16 từ res/raw: lấy channels + sampleRate + chunk 'data' (không giả định header 44 byte). */
    private fun loadWav(context: Context, resId: Int): Pcm? = runCatching {
        val b = context.resources.openRawResource(resId).readBytes()
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        val channels = u16(22)
        val sampleRate = u32(24)
        var i = 12
        var dataOff = 44
        var dataLen = b.size - 44
        while (i + 8 <= b.size) {
            val sz = u32(i + 4)
            if (b[i] == 'd'.code.toByte() && b[i + 1] == 'a'.code.toByte() &&
                b[i + 2] == 't'.code.toByte() && b[i + 3] == 'a'.code.toByte()) {
                dataOff = i + 8; dataLen = sz; break
            }
            i += 8 + sz + (sz and 1)
        }
        val end = minOf(dataOff + dataLen, b.size)
        val raw = ShortArray((end - dataOff) / 2) { k ->
            ((b[dataOff + k * 2].toInt() and 0xFF) or (b[dataOff + k * 2 + 1].toInt() shl 8)).toShort()
        }
        val mono = if (channels >= 2) {
            ShortArray(raw.size / 2) { j -> ((raw[j * 2].toInt() + raw[j * 2 + 1].toInt()) / 2).toShort() }
        } else raw
        // To thêm GAIN (AudioTrack đã max volume rồi) — nhân biên độ, clamp chống vỡ tiếng.
        val out = ShortArray(mono.size) { (mono[it] * GAIN).toInt().coerceIn(-32768, 32767).toShort() }
        Pcm(out, sampleRate)
    }.onFailure { Log.e(TAG, "wav load failed: ${it.message}") }.getOrNull()

    @Suppress("DEPRECATION")
    private fun play(pcm: Pcm?): Long {
        val p = pcm ?: return 0L
        val durationMs = p.data.size * 1000L / p.sr
        try {
            val minBuf = AudioTrack.getMinBufferSize(p.sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC, p.sr, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, p.data.size * 2), AudioTrack.MODE_STREAM,
            )
            track.setStereoVolume(1f, 1f)
            track.play()
            track.write(p.data, 0, p.data.size)
            Thread {
                try {
                    Thread.sleep(durationMs + 250)
                    track.stop(); track.release()
                } catch (_: Exception) {
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "play failed: ${e.message}")
        }
        return durationMs
    }

    companion object {
        private const val TAG = "SoundEffects"
        private const val GAIN = 1.25f   // to thêm ~25% so với file gốc
    }
}
