# "Mai ơi" Wake Engine — Android Integration (Phase 2)

## Context

Phase 1 (a separate project, in the `robot-esp32` repo at
`services/wakeword_training/`) built and trained a custom Vietnamese wake-word
model detecting "Mai ơi", based on [microWakeWord](https://github.com/OHF-Voice/micro-wake-word).
A real trained model (`mai_oi.tflite`) exists and has been validated on real
R1 hardware audio: 4/4 real "Mai ơi" utterances correctly recognized (score
~0.95-1.0), 4/4 real hard-negative phrases (including the competing "OK Nabu"
wake word) correctly rejected (score ~0.00-0.02) — a clean, wide separation.

This spec covers Phase 2: getting that model actually running as a selectable
wake engine in the `xiaozhi-android` app, alongside the existing Snowboy
("Alexa") and vendored black-box ("OK Nabu") engines.

## Why not reuse the existing "OK Nabu" engine's native plumbing

`libmicro_wake_word_jni.so` (the existing "OK Nabu" engine) is a vendored
black-box binary with its model compiled directly into the `.so` — there is
no source to rebuild it with a different model, and no mechanism to load an
external model file. It cannot be repurposed for "Mai ơi" in place; a new
engine implementation is required.

## Architecture

Two new pieces, both implementing/extending the existing
`domain/voice/WakeWordDetector` interface pattern (`isReady`, `process(pcm):
Boolean`, `reset()`, `setStrict(strict: Boolean)` — PCM16 LE mono 16kHz
frames):

1. **`libmicro_features_jni.so`** (new native library) — feature extraction.
2. **`MaiOiWakeWordDetector.kt`** (new Kotlin class) — streaming classifier
   inference via Android's standard TensorFlow Lite library.

The model expects pre-computed spectrogram features as input (`[1, 3, 40]`
int8 — 3 frames of 40-dim features, quantized `scale≈0.102, zero_point=-128`),
not raw audio, and outputs a single quantized score (`[1, 1]` uint8,
`scale≈1/256, zero_point=0`) — confirmed by directly inspecting the trained
`.tflite` file with a standard TFLite interpreter (94 tensors total,
including internal streaming-state variable tensors). This means:

- The classifier itself runs on Android's **official, standard**
  `org.tensorflow:tensorflow-lite` Kotlin/Java library — no custom native
  code needed for inference, unlike Snowboy and "OK Nabu".
- Feature extraction (raw 16kHz PCM → 40-dim spectrogram frames) must
  exactly replicate the training-time algorithm
  (`pymicro-features`, github.com/rhasspy/pymicro-features, Apache
  license — a C++ wrapper around the standard "TFLite Micro audio
  frontend"). This is the one piece that genuinely needs native code, and
  it needs to be the *real* vendored source, not a reimplementation — a
  subtle mismatch here would silently degrade or break detection with no
  obvious symptom.

Model ships as `assets/mai_oi/mai_oi.tflite` (62KB), copied to `filesDir` on
first run — identical pattern to Snowboy's `alexa2.umdl`.

## Components

### 1. Native feature extractor (`app/src/main/cpp/micro_features/`)

- Vendor `pymicro-features`' C++ source (the actual upstream source, not a
  reimplementation) into the project.
- Extend the existing `CMakeLists.txt` (already building Snowboy + Opus
  native code) with a new target producing `libmicro_features_jni.so` for
  both `armeabi-v7a` and `arm64-v8a` — matching the existing pattern of no
  explicit `abiFilters`, just whatever `.so` files get built/placed under
  `jniLibs`.
- JNI wrapper class `MicroFeaturesEngine` (Java/Kotlin) exposing:
  - `processSamples(pcm: ShortArray): FloatArray` — feeds PCM16 samples to
    the native frontend. Since the underlying `process_samples` call may not
    consume all provided audio in one invocation (the real Python API
    returns a `samples_read` count alongside the output features), the
    wrapper loops internally until all input is consumed, accumulating any
    produced 40-dim feature frames. Returns whatever frames were produced
    this call (zero or more).
  - `reset()` — resets the native frontend's internal state.

### 2. Classifier (`MaiOiWakeWordDetector.kt`)

- Wraps `org.tensorflow.lite.Interpreter`, loaded from the bundled model
  asset (copied to `filesDir` on first run, matching Snowboy's pattern).
- Maintains a buffer of incoming 40-dim feature frames (from
  `MicroFeaturesEngine`); once 3 accumulate (matching the model's `[1,3,40]`
  input), runs `interpreter.run()`, dequantizes the `[1,1]` uint8 output to
  a float score.
- `process(pcm: ByteArray): Boolean` — converts the incoming PCM16 LE byte
  frame to `ShortArray`, feeds it through `MicroFeaturesEngine`, feeds any
  resulting feature frames through the buffered classifier step above,
  returns `true` if the resulting score crosses the active threshold.
- `reset()` — resets **both** the native frontend's state (via
  `MicroFeaturesEngine.reset()`) **and** the TFLite interpreter's internal
  streaming-state tensors (`interpreter.resetVariableTensors()`) — both
  halves of the pipeline carry state across calls and both need clearing
  between wake sessions, matching how `SnowboyWakeWordDetector.reset()`
  already works for its own native state.
- `setStrict(strict: Boolean)` — toggles between two threshold values
  (`Settings.maiOiThreshold` / `Settings.maiOiThresholdSpeaking`), mirroring
  `wakeSensitivity`/`wakeSensitivitySpeaking`'s existing pattern exactly.
  Default both to `0.5`: the real-hardware evaluation showed a wide, clean
  separation (positives scored ~0.95-1.0, hard negatives ~0.00-0.02), so
  there's substantial margin and no need for a precariously-tuned default.

### 3. Wiring

- **`Settings.kt`**: add `"mai_oi"` as a third valid `wakeEngine` value
  (alongside existing `"alexa"`/`"nabu"`), plus `mai_oi_threshold` /
  `mai_oi_threshold_speaking` preference keys (defaults `"0.5"`), following
  the existing `wakeSensitivity`/`wakeSensitivitySpeaking` pattern.
- **`VoiceModule.kt`**: extend `provideWakeWordDetector`'s branch to return
  `MaiOiWakeWordDetector` when `Settings.wakeEngine == "mai_oi"`.
- **`assets/control.html`**: add a third engine button "Mai ơi" next to the
  existing `weAlexa`/`weNabu` buttons, posting to the same
  `/api/setup/wake?engine=mai_oi` endpoint, plus a sensitivity slider for
  the new threshold matching the existing `ws`/`wss` slider pattern.

## Testing

- **Native feature-extractor parity check**: a test verifying the JNI
  wrapper's output matches Python `pymicro_features`' output (byte-for-byte
  or within float tolerance) for a fixed reference audio input. This is the
  single most important correctness check — the strongest guarantee the
  on-device features actually match what the model was trained on.
- **On-device manual verification**: install on the R1, select "Mai ơi" in
  the control panel, confirm real wake behavior (say "Mai ơi" → wakes; say
  other things, including the competing "OK Nabu" phrase → doesn't wake).
  Same verification approach already used for Snowboy/Nabu — there's no
  practical way to unit-test the full real-audio wake pipeline on-device,
  and no existing JVM-level test infrastructure covers native JNI code in
  this project (`MicroWakeWordEngine.java` has no tests either), so this
  matches existing project conventions rather than introducing a new gap.

## Explicitly out of scope

- Retraining or further tuning `mai_oi.tflite` itself (that's Phase 1's
  domain, in `robot-esp32`).
- Sourcing additional real-hardware evaluation data beyond what Phase 1
  already captured.
- Any changes to the `robot-esp32` repo.
