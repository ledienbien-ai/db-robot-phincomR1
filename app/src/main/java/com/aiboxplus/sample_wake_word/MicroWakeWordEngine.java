package com.aiboxplus.sample_wake_word;

/**
 * JNI wrapper for libmicro_wake_word_jni.so (reused from aiboxplus 5.1.3).
 * The "OK Nabu" wake-word model is statically embedded in the .so (TFLite). Do NOT change the
 * package/class name (JNI binds by symbol Java_com_aiboxplus_sample_1wake_1word_MicroWakeWordEngine_*).
 *
 * Usage: new MicroWakeWordEngine(false) -> initialize() -> processAudioSamples(short[512], n)
 *   returns true when the wake word is heard. Audio: 16 kHz mono PCM16, fed in 512-sample chunks.
 *
 * Currently unused (the app uses snowboy "Alexa"); kept so OK Nabu can be restored if desired.
 */
public class MicroWakeWordEngine {
    private long nativeHandle;

    public interface DetectionListener {
        void onWakeWordDetected(String word);
    }

    static {
        System.loadLibrary("micro_wake_word_jni");
    }

    public MicroWakeWordEngine(boolean premium) {
        this.nativeHandle = 0L;
        this.nativeHandle = nativeCreate(premium);
    }

    private native long nativeCreate(boolean premium);
    private native void nativeDestroy(long handle);
    private native boolean nativeInitialize(long handle);
    private native boolean nativeProcessAudioSamples(long handle, short[] samples, int length);
    private native void nativeReset(long handle);
    private native void nativeSetDetectionListener(long handle, DetectionListener listener);
    private native void nativeSetProbabilityCutoff(long handle, float cutoff);

    public boolean initialize() {
        if (nativeHandle == 0L) return false;
        return nativeInitialize(nativeHandle);
    }

    public boolean processAudioSamples(short[] samples, int length) {
        if (nativeHandle == 0L) return false;
        return nativeProcessAudioSamples(nativeHandle, samples, length);
    }

    public void reset() {
        if (nativeHandle != 0L) nativeReset(nativeHandle);
    }

    public void setDetectionListener(DetectionListener listener) {
        if (nativeHandle != 0L) nativeSetDetectionListener(nativeHandle, listener);
    }

    public void setProbabilityCutoff(float cutoff) {
        if (nativeHandle != 0L) nativeSetProbabilityCutoff(nativeHandle, cutoff);
    }

    public void destroy() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0L;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            destroy();
        } finally {
            super.finalize();
        }
    }
}
