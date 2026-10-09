package info.dourok.voicebot.data.voice

import android.content.Context
import android.util.Log
import com.aiboxplus.sample_wake_word.MicroWakeWordEngine
import info.dourok.voicebot.domain.voice.WakeWordDetector

/** "OK Nabu" wake detector backed by libmicro_wake_word_jni.so (model embedded in the .so). */
class MicroWakeWordDetector(context: Context) : WakeWordDetector {
    private val engine: MicroWakeWordEngine? = runCatching {
        MicroWakeWordEngine(false).apply {
            if (!initialize()) { Log.e(TAG, "init returned false"); return@runCatching null }
            Log.i(TAG, "microWakeWord (OK Nabu) ready")
        }
    }.onFailure { Log.e(TAG, "init failed: ${it.message}", it) }.getOrNull()

    override val isReady: Boolean get() = engine != null

    // microWakeWord wants 512-sample chunks; feed the frame in 512-sample slices.
    private val CHUNK = 512
    override fun process(pcm: ByteArray): Boolean {
        val e = engine ?: return false
        val total = pcm.size / 2
        val shorts = ShortArray(total)
        for (k in 0 until total) {
            val lo = pcm[k * 2].toInt() and 0xFF
            val hi = pcm[k * 2 + 1].toInt()
            shorts[k] = ((hi shl 8) or lo).toShort()
        }
        var i = 0
        var fired = false
        while (i < total) {
            val n = minOf(CHUNK, total - i)
            if (e.processAudioSamples(shorts.copyOfRange(i, i + n), n)) fired = true
            i += n
        }
        // This engine's cutoff is baked into its .so, not adjustable, so unlike Snowboy/Mai-ơi
        // there's no threshold to raise while our own speaker is playing (no AEC on this device --
        // see MaiOiWakeWordDetector). Keep feeding the engine so its internal streaming state stays
        // warm (avoids a cold-start transient when strict drops back to false), but suppress firing.
        return fired && !strict
    }

    override fun reset() { engine?.reset() }

    private var strict = false
    override fun setStrict(strict: Boolean) { this.strict = strict }

    companion object { private const val TAG = "MicroWakeWord" }
}
