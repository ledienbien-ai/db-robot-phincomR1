// JNI glue for info.dourok.voicebot.data.voice.MicroFeaturesEngine.
//
// Wraps the vendored TFLite Micro audio frontend (app/src/main/cpp/micro_features/) with the
// exact FrontendConfig values and output scaling documented in
// app/src/main/cpp/micro_features/VENDOR_INFO.txt (copied verbatim from pymicro-features'
// CPython glue, which this file is a from-scratch Android/JNI replacement for). This is our own
// new code -- not a vendored binary with a fixed symbol contract -- so the JNI entry points are
// named to match Kotlin `external fun` declarations rather than a pre-existing ABI.
//
// Contract with the vendored C API (see VENDOR_INFO.txt / frontend.h / frontend_util.h):
//   - FrontendPopulateState(config, state, sample_rate) allocates internal buffers; returns 1 on
//     success, 0 on failure.
//   - FrontendProcessSamples(state, samples, num_samples, &samples_read) consumes AT MOST enough
//     samples to fill one 30ms window (num_samples_read may be less than num_samples passed in);
//     it only returns a non-empty FrontendOutput once every 10ms it has accumulated a full window.
//     Callers must loop, advancing by samples_read each time, until all input is consumed.
//   - FrontendOutput.values[i] are uint16_t fixed-point; multiply by FLOAT32_SCALE to get the
//     float32 feature values the model was trained on (this constant lives only in the excluded
//     CPython glue, not the vendored C headers -- see VENDOR_INFO.txt).
//   - FrontendFreeStateContents(state) + FrontendPopulateState(...) again is a full reset
//     (matches pymicro-features' own reset_frontend(), which does NOT use the narrower
//     FrontendReset() -- that one leaves pcan_gain_control/log_scale state untouched).

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <vector>

#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"
#include "tensorflow/lite/experimental/microfrontend/lib/frontend_util.h"

#define LOG_TAG "MicroFeaturesJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Sample rate this app's AudioRecorder captures at (16kHz mono PCM16) -- matches what the
// frontend expects (see VENDOR_INFO.txt).
constexpr int kSampleRateHz = 16000;

// Only present in pymicro-features' excluded CPython glue (src/micro_features.cpp); the vendored
// C frontend returns raw uint16_t fixed-point values that must be scaled by this to match the
// float32 features the "Mai ơi" model was trained on.
constexpr float kFloat32Scale = 0.0390625f;

// Exact FrontendConfig replicated from VENDOR_INFO.txt / pymicro-features' init_cfg(). Any
// deviation here silently produces features the model was not trained on -- no crash, just wrong
// predictions.
void FillFrontendConfig(FrontendConfig* config) {
    std::memset(config, 0, sizeof(*config));

    config->window.size_ms = 30;
    config->window.step_size_ms = 10;

    config->filterbank.num_channels = 40;
    config->filterbank.lower_band_limit = 125.0f;
    config->filterbank.upper_band_limit = 7500.0f;
    // filterbank.output_scale_shift is documented "unused" in filterbank_util.h; leave at 0.

    config->noise_reduction.smoothing_bits = 10;
    config->noise_reduction.even_smoothing = 0.025f;
    config->noise_reduction.odd_smoothing = 0.06f;
    config->noise_reduction.min_signal_remaining = 0.05f;

    config->pcan_gain_control.enable_pcan = 1;
    config->pcan_gain_control.strength = 0.95f;
    config->pcan_gain_control.offset = 80.0f;
    config->pcan_gain_control.gain_bits = 21;

    config->log_scale.enable_log = 1;
    config->log_scale.scale_shift = 6;
}

// Owns the vendored FrontendState plus the config used to (re)populate it, so reset() can do a
// full teardown/rebuild without needing to re-derive the config.
struct MicroFeaturesContext {
    FrontendConfig config{};
    FrontendState state{};
};

// Frees any buffers FrontendPopulateState allocated. Safe to call on a context whose state was
// never successfully populated (FrontendState is zero-initialized by FillFrontendConfig's
// memset-equivalent default member initializer, and FrontendFreeStateContents on an all-zero
// FrontendState is a no-op per the individual *FreeStateContents implementations).
void FreeContextState(MicroFeaturesContext* ctx) {
    FrontendFreeStateContents(&ctx->state);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_info_dourok_voicebot_data_voice_MicroFeaturesEngine_nativeCreate(JNIEnv* /*env*/,
                                                                       jobject /*thiz*/) {
    auto* ctx = new MicroFeaturesContext();
    FillFrontendConfig(&ctx->config);
    if (!FrontendPopulateState(&ctx->config, &ctx->state, kSampleRateHz)) {
        LOGE("FrontendPopulateState failed");
        // FrontendPopulateState may have partially populated some sub-states (e.g. window
        // succeeded, fft failed) before returning 0; free whatever was allocated. Safe on
        // still-null members since Free*StateContents only ever calls free() on them.
        FreeContextState(ctx);
        delete ctx;
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_info_dourok_voicebot_data_voice_MicroFeaturesEngine_nativeDestroy(JNIEnv* /*env*/,
                                                                        jobject /*thiz*/,
                                                                        jlong handle) {
    if (handle == 0) return;
    auto* ctx = reinterpret_cast<MicroFeaturesContext*>(handle);
    FreeContextState(ctx);
    delete ctx;
}

JNIEXPORT void JNICALL
Java_info_dourok_voicebot_data_voice_MicroFeaturesEngine_nativeReset(JNIEnv* /*env*/,
                                                                      jobject /*thiz*/,
                                                                      jlong handle) {
    if (handle == 0) return;
    auto* ctx = reinterpret_cast<MicroFeaturesContext*>(handle);
    FreeContextState(ctx);
    if (!FrontendPopulateState(&ctx->config, &ctx->state, kSampleRateHz)) {
        // Deterministic config + fixed sample rate means this should never happen once creation
        // has already succeeded once; log loudly if it somehow does; leave state zeroed so
        // subsequent calls no-op instead of touching an uninitialized FrontendState.
        LOGE("FrontendPopulateState failed during reset");
    }
}

JNIEXPORT jobjectArray JNICALL
Java_info_dourok_voicebot_data_voice_MicroFeaturesEngine_nativeProcessSamples(JNIEnv* env,
                                                                               jobject /*thiz*/,
                                                                               jlong handle,
                                                                               jshortArray pcm) {
    jclass floatArrayClass = env->FindClass("[F");
    if (handle == 0 || pcm == nullptr) {
        return env->NewObjectArray(0, floatArrayClass, nullptr);
    }
    auto* ctx = reinterpret_cast<MicroFeaturesContext*>(handle);

    const jsize total = env->GetArrayLength(pcm);
    jshort* raw = env->GetShortArrayElements(pcm, nullptr);
    const auto* samples = reinterpret_cast<const int16_t*>(raw);

    std::vector<std::vector<float>> frames;
    size_t offset = 0;
    while (offset < static_cast<size_t>(total)) {
        size_t samples_read = 0;
        FrontendOutput output = FrontendProcessSamples(
            &ctx->state, samples + offset, static_cast<size_t>(total) - offset, &samples_read);

        if (output.size > 0 && output.values != nullptr) {
            std::vector<float> frame(output.size);
            for (size_t i = 0; i < output.size; ++i) {
                frame[i] = static_cast<float>(output.values[i]) * kFloat32Scale;
            }
            frames.push_back(std::move(frame));
        }

        if (samples_read == 0) {
            // Defensive: the vendored frontend should always consume at least one sample when
            // more are available (see WindowProcessSamples), but never spin forever if it can't
            // make progress.
            break;
        }
        offset += samples_read;
    }

    env->ReleaseShortArrayElements(pcm, raw, JNI_ABORT);

    auto result = env->NewObjectArray(static_cast<jsize>(frames.size()), floatArrayClass, nullptr);
    for (size_t i = 0; i < frames.size(); ++i) {
        jfloatArray frame = env->NewFloatArray(static_cast<jsize>(frames[i].size()));
        env->SetFloatArrayRegion(frame, 0, static_cast<jsize>(frames[i].size()), frames[i].data());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), frame);
        env->DeleteLocalRef(frame);
    }
    return result;
}

}  // extern "C"
