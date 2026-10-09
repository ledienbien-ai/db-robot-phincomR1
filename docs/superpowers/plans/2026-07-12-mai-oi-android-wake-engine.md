# "Mai ơi" Android Wake Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add "Mai ơi" as a third selectable wake engine in the app (alongside Snowboy "Alexa" and the vendored "OK Nabu"), running the trained `mai_oi.tflite` model on-device via a native feature-extraction JNI wrapper (vendoring `pymicro-features`' real C++ source) plus Android's standard TensorFlow Lite Kotlin library for classification.

**Architecture:** Two new pieces implementing/using the existing `WakeWordDetector` port: `MicroFeaturesEngine` (native JNI wrapper around vendored `pymicro-features` C++ source, producing 40-dim spectrogram feature frames from raw PCM16) and `MaiOiWakeWordDetector` (Kotlin, buffers 3 feature frames, runs them through a standard `org.tensorflow.lite.Interpreter` loaded from the bundled `mai_oi.tflite` asset, dequantizes the score). Wired into `VoiceModule.kt`, `Settings.kt`, `ControlServer.kt`, and `control.html` following the exact patterns those files already use for Snowboy/Nabu and `wakeSensitivity`.

**Tech Stack:** Kotlin, NDK/CMake (existing toolchain, already building Opus native code), vendored C++ from `pymicro-features` (github.com/rhasspy/pymicro-features, Apache license), `org.tensorflow:tensorflow-lite` (new Gradle dependency, standard Android TFLite runtime — no TFLite Micro needed).

## Global Constraints

- `WakeWordDetector` contract: PCM16 LE mono 16kHz frames in, `Boolean` wake-fired out (`domain/voice/WakeWordDetector.kt`) — `MaiOiWakeWordDetector` must satisfy this exactly, matching `SnowboyWakeWordDetector`'s existing implementation style.
- Model input tensor: `[1, 3, 40]` int8, quantization `scale≈0.10196078568696976, zero_point=-128`. Output tensor: `[1, 1]` uint8, quantization `scale≈0.00390625, zero_point=0`. These exact values were read directly from the real trained model — do not re-derive or guess them.
- Native feature extraction MUST use the real vendored `pymicro-features` C++ source, not a reimplementation — a subtle mismatch would silently degrade detection with no obvious symptom (spec, "Architecture").
- Model asset ships bundled in the APK (`assets/mai_oi/mai_oi.tflite`), copied to `filesDir` on first run — same pattern as `SnowboyWakeWordDetector`'s `copyAsset` helper.
- New Settings keys follow the exact `wakeSensitivity`/`wakeSensitivitySpeaking` pattern in `Settings.kt` (SharedPreferences-backed, default from `AppConfig`).
- Android build: `minSdk = 22`, `compileSdk = 35`, NDK via `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }` (no explicit `abiFilters` — whatever `.so` gets built for `armeabi-v7a`/`arm64-v8a` ships).
- Out of scope: retraining/tuning `mai_oi.tflite` itself, sourcing more real-hardware eval data, any changes to the `robot-esp32` repo (spec, "Explicitly out of scope").

---

## File Structure

```
app/src/main/assets/mai_oi/mai_oi.tflite                              # Task 1 (copied in, binary)
app/src/main/java/info/dourok/voicebot/data/AppConfig.kt              # Task 1 (modified)
app/src/main/java/info/dourok/voicebot/data/Settings.kt               # Task 1 (modified)
app/src/main/cpp/micro_features/                                      # Task 2 (new dir, vendored C++ source)
app/src/main/cpp/CMakeLists.txt                                       # Task 2 (modified)
app/src/main/cpp/micro_features_jni.cpp                                # Task 3 (new, JNI glue)
app/src/main/java/info/dourok/voicebot/data/voice/MicroFeaturesEngine.kt   # Task 3 (new, JNI wrapper)
app/build.gradle.kts                                                  # Task 4 (modified, +tensorflow-lite dep)
app/src/main/java/info/dourok/voicebot/data/voice/MaiOiWakeWordDetector.kt # Task 4 (new)
app/src/main/java/info/dourok/voicebot/data/voice/VoiceModule.kt      # Task 5 (modified)
app/src/main/java/info/dourok/voicebot/control/ControlServer.kt       # Task 5 (modified)
app/src/main/assets/control.html                                      # Task 6 (modified)
app/src/androidTest/java/info/dourok/voicebot/MicroFeaturesEngineParityTest.kt # Task 7 (new)
app/src/androidTest/assets/mai_oi_parity/                             # Task 7 (new, reference fixture)
```

---

### Task 1: Model asset + Settings

**Files:**
- Create: `app/src/main/assets/mai_oi/mai_oi.tflite` (copy from `robot-esp32/services/wakeword_training/models/mai_oi.tflite`)
- Modify: `app/src/main/java/info/dourok/voicebot/data/AppConfig.kt`
- Modify: `app/src/main/java/info/dourok/voicebot/data/Settings.kt`

**Interfaces:**
- Produces: `Settings.maiOiThreshold: String`, `Settings.maiOiThresholdSpeaking: String` (SharedPreferences-backed, same style as `wakeSensitivity`) — consumed by Task 4's `MaiOiWakeWordDetector`.

- [ ] **Step 1: Copy the model asset**

```bash
mkdir -p app/src/main/assets/mai_oi
cp /Users/lucnguyen/Documents/git/robot-esp32/services/wakeword_training/models/mai_oi.tflite \
   app/src/main/assets/mai_oi/mai_oi.tflite
```

Expected: `app/src/main/assets/mai_oi/mai_oi.tflite` exists, 62304 bytes. If the source file is missing, that's a real blocker — report NEEDS_CONTEXT rather than fabricating a placeholder model file (a fake/empty `.tflite` would fail silently or crash at load time, not at build time).

- [ ] **Step 2: Add AppConfig defaults**

In `app/src/main/java/info/dourok/voicebot/data/AppConfig.kt`, add near the existing `WAKE_SENSITIVITY`/`WAKE_SENSITIVITY_SPEAKING` constants (around line 26/71):

```kotlin
    const val MAI_OI_THRESHOLD = "0.5"
    const val MAI_OI_THRESHOLD_SPEAKING = "0.5"
```

(Real-hardware evaluation showed a wide, clean separation — positives scored ~0.95-1.0, hard negatives ~0.00-0.02 — so `0.5` is a safe, well-centered default with substantial margin on both sides; no need for asymmetric normal/speaking defaults the way Snowboy's `0.5`/`0.4` split needed tuning.)

- [ ] **Step 3: Add Settings properties**

In `app/src/main/java/info/dourok/voicebot/data/Settings.kt`, add directly after the existing `wakeSensitivitySpeaking` property (after line 25):

```kotlin
    /** "Mai ơi" detection threshold, 0..1. Wider real-world margin than Snowboy's sensitivity. */
    var maiOiThreshold: String
        get() = prefs.getString("mai_oi_threshold", AppConfig.MAI_OI_THRESHOLD)!!
        set(v) = prefs.edit().putString("mai_oi_threshold", v).apply()

    /** "Mai ơi" threshold khi đang nói/nghe nhạc (stricter, tương tự wakeSensitivitySpeaking). */
    var maiOiThresholdSpeaking: String
        get() = prefs.getString("mai_oi_threshold_speaking", AppConfig.MAI_OI_THRESHOLD_SPEAKING)!!
        set(v) = prefs.edit().putString("mai_oi_threshold_speaking", v).apply()
```

Also update the `wakeEngine` doc comment (line 109) to mention the third value:

```kotlin
    /** Wake engine: "alexa" (snowboy), "nabu" (microWakeWord), or "mai_oi". Changing needs an app restart. */
```

- [ ] **Step 4: Verify it compiles**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. This only compiles Kotlin (no native build yet, since Tasks 2-3 haven't added the new C++ target), so it's fast and isolates any typos in this task's changes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/mai_oi/mai_oi.tflite app/src/main/java/info/dourok/voicebot/data/AppConfig.kt app/src/main/java/info/dourok/voicebot/data/Settings.kt
git commit -m "mai_oi: add model asset and threshold settings"
```

---

### Task 2: Vendor pymicro-features C++ source + CMake build target

**Files:**
- Create: `app/src/main/cpp/micro_features/` (vendored C++ source — exact files determined by research below)
- Modify: `app/src/main/cpp/CMakeLists.txt`

**Interfaces:**
- Produces: a new CMake target building `libmicro_features_jni.so` (name chosen for consistency with `libmicro_wake_word_jni.so`'s naming, but this is OUR OWN new library, not vendored — free to adjust if the real source structure suggests a clearer name) for `armeabi-v7a` and `arm64-v8a`. Consumed by Task 3's JNI glue code, which needs to know the C++ namespace/functions this exposes.

**Real research required — do not guess:**

The current `CMakeLists.txt` (full file) is:
```cmake
cmake_minimum_required(VERSION 3.18.1)

project(app CXX)

add_library(app SHARED opus_recorder.cpp opus_decoder.cpp)

find_package(opus REQUIRED CONFIG)
target_link_libraries(app opus::libopus.so)
target_link_libraries(app log)
```

Note: neither Snowboy's nor "OK Nabu"'s native libraries are built by this file — both are prebuilt `.so` files sitting directly in `jniLibs/`. This task's library is different: it's built from source by this project's own CMake, alongside the existing `app` (Opus) target.

1. Fetch the real source of `github.com/rhasspy/pymicro-features` (confirmed via `pip show pymicro-features`: Apache license, `Required-by: microwakeword`). Find:
   - The actual C/C++ source files (the package's compiled extension is imported in Python as `micro_features_cpp` — find what builds that, e.g. `pybind11`/`setuptools`/CMake, and locate the underlying portable C/C++ implementation of the "TFLite Micro audio frontend" it wraps, since that's the part to vendor for Android — not necessarily the Python-binding layer itself, which is CPython-specific and not directly reusable).
   - Confirm exact function signatures: `create_frontend()`, `process_samples(frontend, audio: bytes) -> (features: List[float], samples_read: int)`, `reset_frontend(frontend)` (from `services/wakeword_training/.venv-train/lib/python3.12/site-packages/pymicro_features/__init__.py` in the `robot-esp32` repo, already read — the Python layer's contract; the underlying C++ layer's real signatures need to be found in the actual repo).
   - Confirm the expected output feature dimension is 40 (matching the trained model's `[1, 3, 40]` input) and sample format (16-bit PCM, 16kHz — matching this app's `AudioRecorder`).
   - Check for an Apache-2.0 LICENSE file to vendor alongside the copied source (attribution requirement).
2. Copy the relevant portable C/C++ source files into `app/src/main/cpp/micro_features/` (not the CPython-binding-specific files, which won't compile/link on Android without CPython headers — Android needs a plain C++ API you can call from your own JNI glue in Task 3).
3. Add a new `add_library(micro_features_jni SHARED ...)` target to `CMakeLists.txt`, listing the vendored source files. Task 3's JNI glue file (`micro_features_jni.cpp`) will also be part of this same target (added in Task 3 — coordinate the target's source list so it's additive, not duplicated).

**Your Job:**

1. Do the research above.
2. Vendor the source, write the CMake target.
3. Verify it builds: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && ./gradlew :app:externalNativeBuildDebug` — this will fail until Task 3 adds `micro_features_jni.cpp` (the target has no JNI entry points yet), so at this task's end, a reasonable verification is confirming the *vendored library code itself* compiles as a static/object step (e.g., temporarily add a trivial `add_library(micro_features_jni STATIC ...)` without JNI symbols just to prove the vendored C++ compiles cleanly under the NDK toolchain, OR coordinate with Task 3 so both land together if splitting truly isn't practical — use your judgment and report which you did and why).
4. Commit.
5. Self-review.
6. Report back with your specific findings (quote the real repo structure/build system, what you vendored and why, what you left out and why).

**When You're in Over Your Head:** If the real `pymicro-features` source turns out to be tightly CPython-coupled with no cleanly separable portable C++ layer (i.e., vendoring it for a non-Python target is genuinely impractical), report BLOCKED with specifics — this would be a real architectural problem worth escalating rather than a guessable workaround (e.g., a from-scratch reimplementation of the TFLite Micro audio frontend algorithm would be a much bigger, riskier undertaking than this plan assumes, and should be a human decision, not something to silently substitute).

**Report Format:** Write your full report to `docs/superpowers/sdd/task-2-report.md` (create the dir if absent). Include: real repo findings with quotes, what you vendored (file list) and why, build verification output, self-review findings, concerns.

Then report back with ONLY (under 15 lines): Status (DONE/DONE_WITH_CONCERNS/BLOCKED/NEEDS_CONTEXT), commits, build verification summary, concerns, report file path.

---

### Task 3: JNI wrapper (native glue + Kotlin class)

**Files:**
- Create: `app/src/main/cpp/micro_features_jni.cpp`
- Create: `app/src/main/java/info/dourok/voicebot/data/voice/MicroFeaturesEngine.kt`
- Modify: `app/src/main/cpp/CMakeLists.txt` (add `micro_features_jni.cpp` to the target Task 2 created)

**Interfaces:**
- Consumes: the vendored C++ API from Task 2 (exact function names TBD by Task 2's findings — read Task 2's report first).
- Produces:
  ```kotlin
  class MicroFeaturesEngine {
      val isReady: Boolean
      fun processSamples(pcm: ShortArray): Array<FloatArray>  // zero or more 40-dim feature frames
      fun reset()
      fun destroy()
  }
  ```
  Consumed by Task 4's `MaiOiWakeWordDetector`.

**Style reference** — this project's existing JNI wrapper pattern (`MicroWakeWordEngine.java`, full file, already read):
```java
package com.aiboxplus.sample_wake_word;

public class MicroWakeWordEngine {
    private long nativeHandle;
    static { System.loadLibrary("micro_wake_word_jni"); }
    public MicroWakeWordEngine(boolean premium) {
        this.nativeHandle = 0L;
        this.nativeHandle = nativeCreate(premium);
    }
    private native long nativeCreate(boolean premium);
    private native void nativeDestroy(long handle);
    private native boolean nativeInitialize(long handle);
    private native boolean nativeProcessAudioSamples(long handle, short[] samples, int length);
    private native void nativeReset(long handle);
    public boolean initialize() { if (nativeHandle == 0L) return false; return nativeInitialize(nativeHandle); }
    // ... (guard every native call on nativeHandle != 0L; destroy() zeroes it after freeing)
}
```

Unlike `MicroWakeWordEngine`, this is OUR OWN new code (not a vendored binary with a fixed symbol-binding contract), so use Kotlin with `external fun` and a natural Kotlin API shape — but keep the same defensive pattern (guard native calls on a valid handle, `reset()`/`destroy()` mirroring the native lifecycle).

**Your Job:**

1. Read Task 2's report (`docs/superpowers/sdd/task-2-report.md`) for the real vendored C++ API to bind against.
2. Write `micro_features_jni.cpp`: JNI entry points (`Java_info_dourok_voicebot_data_voice_MicroFeaturesEngine_nativeXxx`, matching the package `info.dourok.voicebot.data.voice` + class `MicroFeaturesEngine` — JNI symbol names are mechanically derived from these, get them exactly right or linking fails at runtime with `UnsatisfiedLinkError`) wrapping the vendored C++ create/process/reset/destroy calls. Handle the `samples_read`-may-be-partial contract from Task 2's research: loop internally until all input samples are consumed, accumulating output feature frames, so `processSamples` always processes everything it's given in one call.
3. Write `MicroFeaturesEngine.kt` per the interface above.
4. Add `micro_features_jni.cpp` to the CMake target.
5. Verify the full native build succeeds: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && ./gradlew :app:externalNativeBuildDebug`. Expected: BUILD SUCCESSFUL, `libmicro_features_jni.so` produced for both ABIs under `app/build/intermediates/cxx/.../obj/{armeabi-v7a,arm64-v8a}/`.
6. Commit.
7. Self-review.
8. Report back.

**Report Format:** Write to `docs/superpowers/sdd/task-3-report.md`. Include: JNI signature list, build verification output (confirm both ABIs produced the `.so`), self-review findings, concerns.

Then report back with ONLY (under 15 lines): Status, commits, build verification summary, concerns, report file path.

---

### Task 4: Classifier (`MaiOiWakeWordDetector`)

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/info/dourok/voicebot/data/voice/MaiOiWakeWordDetector.kt`

**Interfaces:**
- Consumes: `MicroFeaturesEngine` (Task 3), `Settings.maiOiThreshold`/`maiOiThresholdSpeaking` (Task 1), `WakeWordDetector` interface (existing).
- Produces: `class MaiOiWakeWordDetector(context: Context) : WakeWordDetector` — consumed by Task 5's `VoiceModule.kt`.

- [ ] **Step 1: Add the TensorFlow Lite dependency**

In `app/build.gradle.kts`, in the `dependencies { }` block, add near the existing `nanohttpd` direct-string dependency (around line 93):

```kotlin
    // Standard TFLite runtime for the "Mai ơi" wake engine's classifier (no TFLite Micro needed —
    // the trained model runs fine as a standard streaming/stateful TFLite graph).
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
```

- [ ] **Step 2: Write `MaiOiWakeWordDetector.kt`**

```kotlin
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

    private val features = MicroFeaturesEngine()

    private val interpreter: Interpreter? = runCatching {
        val dir = File(context.filesDir, "mai_oi").apply { mkdirs() }
        val modelFile = copyAsset(context, "mai_oi/mai_oi.tflite", File(dir, "mai_oi.tflite"))
        Interpreter(File(modelFile)).also {
            Log.i(TAG, "mai_oi ready (inputs=${it.inputTensorCount}, outputs=${it.outputTensorCount})")
        }
    }.onFailure { Log.e(TAG, "init failed: ${it.message}", it) }.getOrNull()

    override val isReady: Boolean get() = interpreter != null && features.isReady

    // Model input quantization (read directly from the trained model).
    private val inputScale = 0.10196078568696976f
    private val inputZeroPoint = -128
    private val outputScale = 0.00390625f
    private val outputZeroPoint = 0

    // Buffers 3 incoming 40-dim feature frames to match the model's [1, 3, 40] input.
    private val frameBuffer = ArrayDeque<FloatArray>()

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
        for (frame in features.processSamples(shorts)) {
            frameBuffer.addLast(frame)
            if (frameBuffer.size > 3) frameBuffer.removeFirst()
            if (frameBuffer.size == 3 && runInference(interp, frameBuffer)) fired = true
        }
        return fired
    }

    private fun runInference(interp: Interpreter, frames: ArrayDeque<FloatArray>): Boolean {
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
        return score >= threshold
    }

    override fun reset() {
        features.reset()
        frameBuffer.clear()
        interpreter?.resetVariableTensors()
    }

    private var strict = false
    private var threshold: Float = Settings.maiOiThreshold.toFloatOrNull() ?: 0.5f
    override fun setStrict(strict: Boolean) {
        if (this.strict == strict) return
        this.strict = strict
        threshold = (if (strict) Settings.maiOiThresholdSpeaking else Settings.maiOiThreshold)
            .toFloatOrNull() ?: 0.5f
    }

    private fun copyAsset(context: Context, assetPath: String, dest: File): String {
        if (!dest.exists() || dest.length() == 0L) {
            context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
        return dest.absolutePath
    }

    companion object {
        private const val TAG = "MaiOiWakeWord"
    }
}
```

- [ ] **Step 3: Verify it compiles**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. Note: this depends on Task 3's `MicroFeaturesEngine.kt` already existing with the exact `processSamples(pcm: ShortArray): Array<FloatArray>` / `reset()` / `isReady` signatures specified in Task 3's interface — if Task 3 landed with a different real signature (e.g. it turned out cleaner to return `List<FloatArray>`), adjust this file to match rather than changing Task 3's already-verified code.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/info/dourok/voicebot/data/voice/MaiOiWakeWordDetector.kt
git commit -m "mai_oi: add MaiOiWakeWordDetector (TFLite classifier)"
```

---

### Task 5: Wiring (DI + control server API)

**Files:**
- Modify: `app/src/main/java/info/dourok/voicebot/data/voice/VoiceModule.kt`
- Modify: `app/src/main/java/info/dourok/voicebot/control/ControlServer.kt`

**Interfaces:**
- Consumes: `MaiOiWakeWordDetector` (Task 4), `Settings.wakeEngine`/`maiOiThreshold`/`maiOiThresholdSpeaking` (Task 1).

- [ ] **Step 1: Wire `VoiceModule.kt`**

Replace the existing `provideWakeWordDetector` (lines 22-26):

```kotlin
    @Provides
    @Singleton
    fun provideWakeWordDetector(@ApplicationContext context: Context): WakeWordDetector =
        when (Settings.wakeEngine) {
            "nabu" -> MicroWakeWordDetector(context)
            "mai_oi" -> MaiOiWakeWordDetector(context)
            else -> SnowboyWakeWordDetector(context)
        }
```

- [ ] **Step 2: Wire `ControlServer.kt`'s `handleSet`**

`/api/setup/wake` (line 75-78) and the `"wake_engine"` case in `handleSet` (line 144) already write `Settings.wakeEngine` generically for any string value — no change needed there. Add the two new threshold keys to `handleSet`'s `when` block, directly after the existing `wake_sensitivity_speaking` case (after line 122):

```kotlin
            "mai_oi_threshold" -> Settings.maiOiThreshold = v
            "mai_oi_threshold_speaking" -> Settings.maiOiThresholdSpeaking = v
```

- [ ] **Step 3: Wire `ControlServer.kt`'s `buildState`**

Add to `buildState()`, directly after the existing `wake_sensitivity_speaking` line (after line 173):

```kotlin
        o.put("mai_oi_threshold", Settings.maiOiThreshold)
        o.put("mai_oi_threshold_speaking", Settings.maiOiThresholdSpeaking)
```

- [ ] **Step 4: Verify it compiles**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/info/dourok/voicebot/data/voice/VoiceModule.kt app/src/main/java/info/dourok/voicebot/control/ControlServer.kt
git commit -m "mai_oi: wire DI and control-server settings API"
```

---

### Task 6: Control panel UI

**Files:**
- Modify: `app/src/main/assets/control.html`

**Interfaces:** none (leaf UI change, consumes the `/api/set`, `/api/setup/wake`, `/api/state` endpoints Task 5 already wired).

- [ ] **Step 1: Add the third engine button**

Replace line 368 (the Wake engine row):

```html
      <div class="row"><label>Engine</label><div class="segsel" style="flex:1"><button id="weAlexa" onclick="setWake('alexa')">Alexa</button><button id="weNabu" onclick="setWake('nabu')">OK Nabu</button><button id="weMaiOi" onclick="setWake('mai_oi')">Mai ơi</button></div></div>
```

- [ ] **Step 2: Add a dedicated "Mai ơi" threshold card**

Insert a new card directly after the existing "Wake engine" card (after line 370, before the "Log" card at line 372) — deliberately separate from the Snowboy-specific "Wake word" card (lines 275-280 in the other tab), which is hardcoded to Snowboy's `wake_sensitivity` semantics and wording ("không nghe thấy Alexa"):

```html
    <div class="card">
      <div class="ch"><span class="icon-badge ib-wake"><svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><circle cx="4" cy="16" r="1.3" fill="currentColor" stroke="none"/><path d="M7 13a6 6 0 018 0"/><path d="M9.3 15.3a3 3 0 014 0"/></svg></span><h2>Mai ơi threshold</h2></div>
      <div class="row"><label>Độ nhạy</label><input type="range" id="moT" min="0" max="1" step="0.05"><span class="val" id="moTv">–</span></div>
      <div class="hint">Mức 0.5 là cân bằng, có biên độ rộng (thật đo được: dương ~0.95-1.0, âm khó ~0.00-0.02).</div>
      <div class="row"><label>Khi phát âm</label><input type="range" id="moTs" min="0" max="1" step="0.05"><span class="val" id="moTsv">–</span></div>
      <div class="hint">Ngưỡng riêng khi robot đang nói hoặc phát nhạc.</div>
    </div>
```

- [ ] **Step 3: Wire the state-sync function**

In the function containing lines 477-479 (the `weAlexa`/`weNabu` sync — read the surrounding function first to confirm you're editing the same function, not just matching line numbers after Step 1/2 shift them), add:

```javascript
  $('weMaiOi').classList.toggle('on',s.wake_engine==='mai_oi');
  $('moT').value=parseFloat(s.mai_oi_threshold||'0.5');$('moTv').textContent=s.mai_oi_threshold||'0.5';
  $('moTs').value=parseFloat(s.mai_oi_threshold_speaking||'0.5');$('moTsv').textContent=s.mai_oi_threshold_speaking||'0.5';
```

- [ ] **Step 4: Wire the slider oninput handlers**

Directly after the existing `$('wss').oninput=...` line (originally line 486), add:

```javascript
$('moT').oninput=e=>{$('moTv').textContent=(+e.target.value).toFixed(2);set('mai_oi_threshold',(+e.target.value).toFixed(2));};
$('moTs').oninput=e=>{$('moTsv').textContent=(+e.target.value).toFixed(2);set('mai_oi_threshold_speaking',(+e.target.value).toFixed(2));};
```

- [ ] **Step 5: Wire `setWake()`**

Replace the existing `setWake` function (originally lines 564-565):

```javascript
function setWake(e){$('weAlexa').classList.toggle('on',e==='alexa');$('weNabu').classList.toggle('on',e==='nabu');$('weMaiOi').classList.toggle('on',e==='mai_oi');
  fetch('/api/setup/wake?engine='+e,{method:'POST'});logSetup('wake engine -> '+e);}
```

- [ ] **Step 6: Manual verification**

Since this is a static HTML/JS asset with no build step or test harness, verify by eye: open the modified `control.html` in a browser (or push to `/sdcard/control.html` on the device per this project's existing dev-iteration trick, documented in `CLAUDE.md`) and confirm the three buttons render, clicking each toggles the `on` class correctly, and the two new sliders show/update values. Note in your report that this was a manual check, not an automated one — there is no existing test harness for this file.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/assets/control.html
git commit -m "mai_oi: add third engine option and threshold sliders to control panel"
```

---

### Task 7: Native feature-extractor parity test

**Files:**
- Create: `app/src/androidTest/java/info/dourok/voicebot/MicroFeaturesEngineParityTest.kt`
- Create: `app/src/androidTest/assets/mai_oi_parity/reference.wav` (copy of a real fixed test clip)
- Create: `app/src/androidTest/assets/mai_oi_parity/expected_features.json` (generated reference output)

**Interfaces:** none (leaf test, consumes `MicroFeaturesEngine` from Task 3).

**Purpose:** the single most important correctness check per the design spec — verifying the on-device native feature extractor produces the same output as the real Python `pymicro_features` reference for the same input audio. A silent mismatch here would make the classifier scores meaningless without any obvious symptom (it would still run, just never detect correctly).

- [ ] **Step 1: Generate the reference fixture**

Using one of the real "Mai ơi" recordings already captured during Phase 1's real-hardware evaluation (`/Users/lucnguyen/Documents/mai_oi_speech/rec_nomal.wav`, 16kHz mono PCM16 — already confirmed correct format), generate the expected feature output using the real Python reference implementation:

```bash
cd /Users/lucnguyen/Documents/git/robot-esp32/services/wakeword_training
.venv-train/bin/python -c "
import json
import soundfile as sf
from pymicro_features import MicroFrontend

audio, sr = sf.read('/Users/lucnguyen/Documents/mai_oi_speech/rec_nomal.wav', dtype='int16')
assert sr == 16000
frontend = MicroFrontend()
result = frontend.process_samples(audio.tobytes())
print(f'produced {len(result.features)} frames, samples_read={result.samples_read}')
with open('/tmp/expected_features.json', 'w') as f:
    json.dump({'samples_read': result.samples_read, 'features': result.features}, f)
"
cp /tmp/expected_features.json /Users/lucnguyen/Documents/git/xiaozhi-android/app/src/androidTest/assets/mai_oi_parity/expected_features.json
cp /Users/lucnguyen/Documents/mai_oi_speech/rec_nomal.wav /Users/lucnguyen/Documents/git/xiaozhi-android/app/src/androidTest/assets/mai_oi_parity/reference.wav
```

If `result.features`' actual shape differs from a flat list (e.g. it's a list of per-frame lists), adjust the JSON structure accordingly and note the real shape in your report — don't force it into an assumed shape.

- [ ] **Step 2: Write the instrumented test**

```kotlin
package info.dourok.voicebot

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import info.dourok.voicebot.data.voice.MicroFeaturesEngine
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class MicroFeaturesEngineParityTest {

    @Test
    fun nativeFeaturesMatchPythonReference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wavBytes = context.assets.open("mai_oi_parity/reference.wav").readBytes()
        val pcm = extractPcm16Mono(wavBytes)   // strip the WAV header, see helper below

        val expectedJson = JSONObject(
            context.assets.open("mai_oi_parity/expected_features.json").bufferedReader().readText()
        )
        val expectedFeatures = expectedJson.getJSONArray("features")

        val engine = MicroFeaturesEngine()
        assertTrue("engine failed to initialize", engine.isReady)
        val produced = engine.processSamples(pcm)
        engine.destroy()

        val producedFlat = produced.flatMap { it.toList() }
        assertEquals(
            "frame count / flattened feature length mismatch — check expected_features.json's real shape from Step 1",
            expectedFeatures.length(), producedFlat.size,
        )
        for (i in 0 until expectedFeatures.length()) {
            val expected = expectedFeatures.getDouble(i)
            val actual = producedFlat[i].toDouble()
            assertTrue(
                "feature[$i]: expected=$expected actual=$actual (tolerance 1e-3)",
                Math.abs(expected - actual) < 1e-3,
            )
        }
    }

    /** Strips a standard 44-byte PCM WAV header, returns 16-bit mono samples. */
    private fun extractPcm16Mono(wavBytes: ByteArray): ShortArray {
        val dataStart = 44   // standard WAV header size for uncompressed PCM without extra chunks
        val shorts = ShortArray((wavBytes.size - dataStart) / 2)
        for (i in shorts.indices) {
            val lo = wavBytes[dataStart + i * 2].toInt() and 0xFF
            val hi = wavBytes[dataStart + i * 2 + 1].toInt()
            shorts[i] = ((hi shl 8) or lo).toShort()
        }
        return shorts
    }
}
```

If the real WAV file has a non-standard header (extra chunks before `data`), the hardcoded `dataStart = 44` will be wrong — verify against the actual file (e.g. via a hex dump or Python's `soundfile`/`wave` module reporting the data chunk offset) rather than assuming, and adjust.

- [ ] **Step 3: Run the test on the connected R1 device**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB connect 10.25.113.209:5555
./gradlew :app:connectedDebugAndroidTest --tests "info.dourok.voicebot.MicroFeaturesEngineParityTest"
```

Expected: test passes, confirming on-device native features match the real Python reference within tolerance. If it fails, this is a genuine, important finding — do not loosen the tolerance to force a pass; report the actual mismatch (e.g. off-by-a-scale-factor, off-by-one-frame, or a real algorithmic difference) so it can be diagnosed, since this test exists specifically to catch exactly this class of bug before it silently ships.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/java/info/dourok/voicebot/MicroFeaturesEngineParityTest.kt app/src/androidTest/assets/mai_oi_parity/
git commit -m "mai_oi: add native feature-extractor parity test against Python reference"
```

---

## Manual end-to-end verification (after all 7 tasks)

Not a task with automated tests — real on-device behavioral verification, per the design spec's testing section:

1. Build and install per `CLAUDE.md`'s documented process (`./gradlew :app:assembleDebug`, push + `pm install -r` via shell 8080 — NOT `adb install`, which drops over this device's wifi).
2. Open the control panel (`http://10.25.113.209:8088`), Setup tab, select "Mai ơi" as the wake engine.
3. Restart the app (wake engine changes need a restart, matching Snowboy/Nabu's existing behavior).
4. Say "Mai ơi" — confirm the app wakes (LED/sound cue, matching existing wake behavior).
5. Say things that shouldn't wake it (normal conversation, the competing "OK Nabu" phrase) — confirm it stays idle.
6. Adjust the new threshold sliders in the control panel, confirm behavior changes accordingly (e.g. lowering threshold makes it more trigger-happy).
