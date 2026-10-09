package info.dourok.voicebot.data.voice

import ai.kitt.snowboy.SnowboyDetect
import android.content.Context
import android.util.Log
import info.dourok.voicebot.data.AppConfig
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.WakeWordDetector
import java.io.File

/**
 * "Alexa" wake word detector backed by snowboy (alexa2.umdl).
 * Snowboy loads its model from files, so the bundled assets are copied to filesDir on first use.
 */
class SnowboyWakeWordDetector(context: Context) : WakeWordDetector {

    private val detector: SnowboyDetect? = runCatching {
        val dir = File(context.filesDir, "snowboy").apply { mkdirs() }
        val res = copyAsset(context, "snowboy/common.res", File(dir, "common.res"))
        val model = copyAsset(context, "snowboy/alexa2.umdl", File(dir, "alexa2.umdl"))
        SnowboyDetect(res, model).apply {
            SetSensitivity(Settings.wakeSensitivity)   // 0..1, higher = more sensitive (runtime via panel)
            SetAudioGain(1.0f)
            ApplyFrontend(true)           // required for universal (.umdl) models
            Log.i(TAG, "snowboy Alexa ready (sr=${SampleRate()}, hotwords=${NumHotwords()})")
        }
    }.onFailure { Log.e(TAG, "init failed: ${it.message}", it) }.getOrNull()

    override val isReady: Boolean get() = detector != null

    override fun process(pcm: ByteArray): Boolean {
        val d = detector ?: return false
        val total = pcm.size / 2
        val shorts = ShortArray(total)
        for (k in 0 until total) {
            val lo = pcm[k * 2].toInt() and 0xFF
            val hi = pcm[k * 2 + 1].toInt()
            shorts[k] = ((hi shl 8) or lo).toShort()
        }
        return d.RunDetection(shorts, total) > 0   // >0 = a hotword fired
    }

    override fun reset() {
        detector?.Reset()
    }

    private var strict = false
    override fun setStrict(strict: Boolean) {
        if (this.strict == strict) return   // only touch native when it actually changes
        this.strict = strict
        detector?.SetSensitivity(
            if (strict) Settings.wakeSensitivitySpeaking else Settings.wakeSensitivity,
        )
    }

    private fun copyAsset(context: Context, assetPath: String, dest: File): String {
        if (!dest.exists() || dest.length() == 0L) {
            context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
        return dest.absolutePath
    }

    companion object {
        private const val TAG = "SnowboyWakeWord"
    }
}
