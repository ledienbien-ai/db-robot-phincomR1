package info.dourok.voicebot.data.voice

import android.content.Context
import android.util.Log
import info.dourok.voicebot.data.Settings
import info.dourok.voicebot.domain.voice.WakeWordDetector
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "Mai ơi" wake word detector. Runs the trained mai_oi.tflite model (standard TFLite, quantized
 * streaming graph) via MicroFeaturesEngine for spectrogram feature extraction. Model input is
 * [1, 3, 40] int8 (scale=0.10196078568696976, zero_point=-128); output is [1, 1] uint8
 * (scale=0.00390625, zero_point=0) -- values read directly from the trained model, not guessed.
 */
class MaiOiWakeWordDetector(context: Context) : WakeWordDetector {

    private val features: MicroFeaturesEngine? = runCatching { MicroFeaturesEngine() }
        .onFailure { Log.e(TAG, "features init failed: ${it.message}", it) }.getOrNull()

    private val interpreter: Interpreter? = runCatching {
        val dir = File(context.filesDir, "mai_oi").apply { mkdirs() }
        val modelFile = copyAsset(context, "mai_oi/mai_oi.tflite", File(dir, "mai_oi.tflite"))
        // XNNPACK (TFLite's default CPU delegate) segfaults on first invoke() on this device --
        // real R1 hardware crash, SIGSEGV fault addr 0x14 inside libtensorflowlite_jni.so, only on
        // the first inference call (model load/construction succeeds fine). Root cause: this model
        // has internal streaming state (Stream layers, reset via resetVariableTensors()), and
        // XNNPACK + stateful/streaming quantized graphs is a known-fragile combination, more so on
        // 32-bit ARM (this device's ABI). Disabling XNNPACK forces TFLite's default (unaccelerated
        // but stable) kernels.
        val options = Interpreter.Options().apply { setUseXNNPACK(false) }
        Interpreter(File(modelFile), options).also {
            Log.i(TAG, "mai_oi ready (inputs=${it.inputTensorCount}, outputs=${it.outputTensorCount})")
        }
    }.onFailure { Log.e(TAG, "init failed: ${it.message}", it) }.getOrNull()

    override val isReady: Boolean get() = interpreter != null && features?.isReady == true

    // Model input quantization (read directly from the trained model).
    private val inputScale = 0.10196078568696976f
    private val inputZeroPoint = -128
    private val outputScale = 0.00390625f
    private val outputZeroPoint = 0

    // Accumulates 3 incoming 40-dim feature frames to match the model's [1, 3, 40] streaming
    // input. Matches vendor/microWakeWord/microwakeword/inference.py's predict_spectrogram,
    // which (with input_feature_slices=3, stride=3) feeds NON-overlapping chunks: frames[0:3],
    // then frames[3:6], etc. -- inference runs once every 3 NEW frames, never on a sliding
    // window, because the model has internal streaming state (Stream layers) that persists
    // across interpreter.run() calls and expects each frame exactly once, in order.
    private val frameBuffer = mutableListOf<FloatArray>()

    override fun process(pcm: ByteArray): Boolean {
        val interp = interpreter ?: return false
        val total = pcm.size / 2
        val shorts = ShortArray(total)
        for (k in 0 until total) {
            val lo = pcm[k * 2].toInt() and 0xFF
            val hi = pcm[k * 2 + 1].toInt()
            shorts[k] = ((hi shl 8) or lo).toShort()
        }

        var fired = false
        for (frame in features?.processSamples(shorts) ?: emptyArray()) {
            frameBuffer.add(frame)
            if (frameBuffer.size == 3) {
                if (runInference(interp, frameBuffer)) fired = true
                frameBuffer.clear()
            }
        }
        return fired
    }

    private fun runInference(interp: Interpreter, frames: List<FloatArray>): Boolean {
        val input = ByteBuffer.allocateDirect(1 * 3 * 40).order(ByteOrder.nativeOrder())
        for (frame in frames) {
            for (v in frame) {
                val quantized = Math.round(v / inputScale) + inputZeroPoint
                input.put(quantized.coerceIn(-128, 127).toByte())
            }
        }
        input.rewind()
        val output = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
        interp.run(input, output)
        output.rewind()
        val rawOutput = output.get().toInt() and 0xFF
        val score = (rawOutput - outputZeroPoint) * outputScale
        // strict (SPEAKING) never fires: real on-device evidence (2026-07-13) showed the model
        // scoring its OWN real TTS speech (not just chimes) at 0.97-0.99 confidence -- this device
        // has no AEC, and this model (trained on synthetic data + a limited hard-negative set) isn't
        // discriminative enough to reject real spoken Vietnamese content the way Alexa/OK Nabu's
        // model is. No threshold below ~1.0 filters that, so barge-in isn't viable for this engine;
        // disabling it here (rather than raising the threshold further) is what actually stops the
        // self-interrupt loop. Mai oi can still WAKE from idle (strict=false) normally.
        // Read the threshold FRESH every inference so control-panel changes apply immediately.
        // (It used to be cached and only re-read when strict flipped -- but strict is always false
        // while idle-waiting, which is exactly when wake detection runs, so a panel change never
        // took effect until a full wake cycle. That looked like "threshold change does nothing".)
        val threshold = (if (strict) Settings.maiOiThresholdSpeaking else Settings.maiOiThreshold)
            .toFloatOrNull() ?: 0.5f
        val fire = !strict && score >= threshold
        Log.i(TAG, "score=$score threshold=$threshold strict=$strict fire=$fire t=${System.currentTimeMillis()}")
        return fire
    }

    override fun reset() {
        // Deliberately does NOT call features?.reset(): real on-device testing (2026-07-13) showed
        // resetting the native frontend's noise-floor/gain estimate produces an elevated-baseline
        // "cold start" transient for the first 1-2 inferences afterward, which the model reads as a
        // spurious high-confidence "Mai ơi" -- causing a self-sustaining loop (fire -> reset ->
        // cold-start transient -> fire again). The frontend's noise/gain adaptation should run
        // continuously across the whole session, like a real AGC; only the model's own streaming
        // state (which genuinely needs to not carry stale context between utterances) and our frame
        // accumulator need to reset here. Verified: with this change, the false-wake loop that
        // previously fired repeatedly (e.g. after a manual stop, or right after TTS playback) no
        // longer reproduces across 1700+ consecutive real inferences.
        frameBuffer.clear()
        interpreter?.resetVariableTensors()
    }

    private var strict = false
    override fun setStrict(strict: Boolean) { this.strict = strict }

    private fun copyAsset(context: Context, assetPath: String, dest: File): String {
        // ALWAYS overwrite. The bundled mai_oi.tflite changes across app updates, but
        // `pm install -r` preserves filesDir -- so the old "copy only if missing" guard meant
        // the very first model copied stuck forever and every later model update silently never
        // took effect on-device. The model is tiny (~62KB); re-copying on each start is free.
        context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest.absolutePath
    }

    companion object {
        private const val TAG = "MaiOiWakeWord"
    }
}
