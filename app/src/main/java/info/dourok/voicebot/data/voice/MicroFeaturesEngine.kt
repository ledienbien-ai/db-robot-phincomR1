package info.dourok.voicebot.data.voice

/**
 * JNI wrapper for libmicro_features_jni.so -- the vendored TFLite Micro audio frontend, which
 * turns 16kHz mono PCM16 audio into the 40-dim feature vectors the "Mai ơi" wake-word model was
 * trained on. See app/src/main/cpp/micro_features/VENDOR_INFO.txt for the exact FrontendConfig
 * fields and FLOAT32_SCALE contract the native side replicates, and
 * app/src/main/cpp/micro_features_jni.cpp for the JNI glue itself.
 *
 * Mirrors the defensive native-handle pattern used elsewhere in this package
 * (MicroWakeWordEngine): a native handle stored as a `long`, every native call guarded on
 * `!= 0L`, `destroy()` frees native state and zeroes the handle. This is our own new code (not a
 * vendored binary with a fixed symbol contract), so it's plain Kotlin with `external fun` rather
 * than Java.
 *
 * Not thread-safe -- intended to be driven from a single audio-processing thread, same as the
 * other WakeWordDetector implementations in this package.
 */
class MicroFeaturesEngine {
    private var nativeHandle: Long = 0L

    init {
        nativeHandle = nativeCreate()
    }

    /** True if the native frontend state was successfully allocated. */
    val isReady: Boolean get() = nativeHandle != 0L

    /**
     * Feeds 16kHz mono PCM16 samples into the frontend. The frontend accumulates internally
     * across its 10ms/160-sample window, so a single call may yield zero, one, or more 40-dim
     * feature frames depending on how many samples are passed in. Returns an empty array if the
     * engine failed to initialize.
     */
    fun processSamples(pcm: ShortArray): Array<FloatArray> {
        if (nativeHandle == 0L) return emptyArray()
        return nativeProcessSamples(nativeHandle, pcm)
    }

    /** Clears internal window/noise-reduction/PCAN/log-scale state (full teardown + rebuild). */
    fun reset() {
        if (nativeHandle != 0L) nativeReset(nativeHandle)
    }

    /** Frees native state. Safe to call multiple times. */
    fun destroy() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    protected fun finalize() {
        destroy()
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcessSamples(handle: Long, pcm: ShortArray): Array<FloatArray>
    private external fun nativeReset(handle: Long)

    companion object {
        init {
            System.loadLibrary("micro_features_jni")
        }
    }
}
