package ai.kitt.snowboy;

/**
 * JNI binding for libsnowboy-detect-android.so (reused from aiboxplus 5.1.3 — standard SWIG wrapper).
 * Native names MUST match the .so symbols: Java_ai_kitt_snowboy_snowboyJNI_<...>.
 * The short[] RunDetection overload is __SWIG_5 (mangled symbol ...SWIG_15). Only the methods
 * actually used are declared here.
 */
public class snowboyJNI {
    static {
        System.loadLibrary("snowboy-detect-android");
    }

    public final static native long new_SnowboyDetect(String resource_filename, String model_str);
    public final static native void delete_SnowboyDetect(long cptr);
    public final static native boolean SnowboyDetect_Reset(long cptr, SnowboyDetect self);
    // RunDetection(short[] data, int array_length) -> hotword index (>0 = detected), 0 = none, -1/-2 = error/silence
    public final static native int SnowboyDetect_RunDetection__SWIG_5(long cptr, SnowboyDetect self, short[] data, int array_length);
    public final static native void SnowboyDetect_SetSensitivity(long cptr, SnowboyDetect self, String sensitivity_str);
    public final static native void SnowboyDetect_SetAudioGain(long cptr, SnowboyDetect self, float audio_gain);
    public final static native void SnowboyDetect_ApplyFrontend(long cptr, SnowboyDetect self, boolean apply_frontend);
    public final static native int SnowboyDetect_NumHotwords(long cptr, SnowboyDetect self);
    public final static native int SnowboyDetect_SampleRate(long cptr, SnowboyDetect self);
}
