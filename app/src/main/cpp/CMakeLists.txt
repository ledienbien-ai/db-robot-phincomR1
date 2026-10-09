cmake_minimum_required(VERSION 3.18.1)

project(app CXX)

add_library(app SHARED opus_recorder.cpp opus_decoder.cpp)

find_package(opus REQUIRED CONFIG)
target_link_libraries(app opus::libopus.so)
target_link_libraries(app log)

# --- micro_features_jni ---------------------------------------------------
# Portable C/C++ "TFLite Micro audio frontend" feature-extraction algorithm,
# vendored from https://github.com/rhasspy/pymicro-features (Apache-2.0),
# commit 96bd69cfad79aa67697e176570d3dd87052c3def. This is the exact
# algorithm used to compute the 40-dim spectrogram features the "Mai ơi"
# wake-word model was trained on. See micro_features/VENDOR_INFO.txt for
# full provenance, what was intentionally left out (pymicro-features'
# CPython binding layer, which does not build for Android), and the exact
# FrontendConfig values the JNI glue below replicates.
#
# micro_features_jni.cpp (in MICRO_FEATURES_SOURCES below) is the JNI glue that calls into
# this vendored code from info.dourok.voicebot.data.voice.MicroFeaturesEngine.
set(MICRO_FEATURES_DIR ${CMAKE_CURRENT_SOURCE_DIR}/micro_features)
set(MICRO_FEATURES_TF_DIR ${MICRO_FEATURES_DIR}/tensorflow/lite/experimental/microfrontend/lib)
set(MICRO_FEATURES_KISSFFT_DIR ${MICRO_FEATURES_DIR}/kissfft)

set(MICRO_FEATURES_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/micro_features_jni.cpp
    ${MICRO_FEATURES_TF_DIR}/fft.cc
    ${MICRO_FEATURES_TF_DIR}/fft_util.cc
    ${MICRO_FEATURES_TF_DIR}/filterbank.cc
    ${MICRO_FEATURES_TF_DIR}/filterbank_util.cc
    ${MICRO_FEATURES_TF_DIR}/frontend.cc
    ${MICRO_FEATURES_TF_DIR}/frontend_util.cc
    ${MICRO_FEATURES_TF_DIR}/kiss_fft_int16.cc
    ${MICRO_FEATURES_TF_DIR}/log_lut.cc
    ${MICRO_FEATURES_TF_DIR}/log_scale.cc
    ${MICRO_FEATURES_TF_DIR}/log_scale_util.cc
    ${MICRO_FEATURES_TF_DIR}/noise_reduction.cc
    ${MICRO_FEATURES_TF_DIR}/noise_reduction_util.cc
    ${MICRO_FEATURES_TF_DIR}/pcan_gain_control.cc
    ${MICRO_FEATURES_TF_DIR}/pcan_gain_control_util.cc
    ${MICRO_FEATURES_TF_DIR}/window.cc
    ${MICRO_FEATURES_TF_DIR}/window_util.cc
    ${MICRO_FEATURES_KISSFFT_DIR}/kiss_fft.cc
    ${MICRO_FEATURES_KISSFFT_DIR}/tools/kiss_fftr.cc
)
# NOTE: kiss_fft.cc / tools/kiss_fftr.cc above ARE compiled twice on purpose
# (once here in the global namespace, once again textually #include'd inside
# kiss_fft_int16.cc under namespace kissfft_fixed16) - this exactly matches
# upstream's own setup.py `sources` list, which does the same. Do not "fix"
# this by removing them; the two copies live in different C++ namespaces so
# there is no duplicate-symbol conflict, and only the namespaced copy is
# actually reachable from FrontendProcessSamples().

add_library(micro_features_jni SHARED ${MICRO_FEATURES_SOURCES})

target_include_directories(micro_features_jni PRIVATE
    ${MICRO_FEATURES_DIR}
    ${MICRO_FEATURES_KISSFFT_DIR}
)
# Matches upstream setup.py: flags = ["-DFIXED_POINT=16"]
target_compile_definitions(micro_features_jni PRIVATE FIXED_POINT=16)
target_link_libraries(micro_features_jni log)