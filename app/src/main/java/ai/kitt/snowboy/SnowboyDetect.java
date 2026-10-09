package ai.kitt.snowboy;

/** Standard SWIG wrapper for snowboy's SnowboyDetect. Construct from model files: SnowboyDetect(common.res, model.umdl). */
public class SnowboyDetect {
    private transient long swigCPtr;
    protected transient boolean swigCMemOwn;

    public SnowboyDetect(String resource_filename, String model_str) {
        swigCPtr = snowboyJNI.new_SnowboyDetect(resource_filename, model_str);
        swigCMemOwn = true;
    }

    public synchronized void delete() {
        if (swigCPtr != 0) {
            if (swigCMemOwn) {
                swigCMemOwn = false;
                snowboyJNI.delete_SnowboyDetect(swigCPtr);
            }
            swigCPtr = 0;
        }
    }

    @Override
    protected void finalize() {
        delete();
    }

    /** Returns the hotword index (>0 = detected), 0 = none, -1 = error, -2 = silence. */
    public int RunDetection(short[] data, int array_length) {
        return snowboyJNI.SnowboyDetect_RunDetection__SWIG_5(swigCPtr, this, data, array_length);
    }

    public boolean Reset() {
        return snowboyJNI.SnowboyDetect_Reset(swigCPtr, this);
    }

    public void SetSensitivity(String sensitivity_str) {
        snowboyJNI.SnowboyDetect_SetSensitivity(swigCPtr, this, sensitivity_str);
    }

    public void SetAudioGain(float audio_gain) {
        snowboyJNI.SnowboyDetect_SetAudioGain(swigCPtr, this, audio_gain);
    }

    public void ApplyFrontend(boolean apply_frontend) {
        snowboyJNI.SnowboyDetect_ApplyFrontend(swigCPtr, this, apply_frontend);
    }

    public int NumHotwords() {
        return snowboyJNI.SnowboyDetect_NumHotwords(swigCPtr, this);
    }

    public int SampleRate() {
        return snowboyJNI.SnowboyDetect_SampleRate(swigCPtr, this);
    }
}
