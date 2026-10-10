# DB-Robot — trợ lý giọng nói cho loa PHICOMM R1

Bản DB-Robot của [kuteo-git/xiaozhi-android](https://github.com/kuteo-git/xiaozhi-android)
(fork từ [douo/xiaozhi-android](https://github.com/douo/xiaozhi-android)). Khác bản gốc ở các điểm:

- **Thương hiệu**: tên app, icon và trang điều khiển (cổng `8088`) mang tên DB-Robot.
- **Máy chủ**: thẻ **Server** của tab Setup có hai máy chủ cố định, bấm là kết nối: DB-Robot
  (`https://sv1.dbrobot.vn/xiaozhi/ota/`, mặc định, `AppConfig.OTA_URL`) và Xiaozhi
  (`https://api.tenclass.net/xiaozhi/ota/`), kèm ô Tuỳ chỉnh cho OTA URL khác. App tự hỏi OTA khi
  khởi động để lấy địa chỉ WebSocket và mã kích hoạt; mã hiện trong thẻ đó cho tới khi thiết bị
  được thêm vào tài khoản trên máy chủ. Chọn máy chủ cố định cũng đặt luôn định dạng âm thanh của
  máy chủ đó (16 kHz cho DB-Robot, 24 kHz cho Xiaozhi) và app tự khởi động lại nếu định dạng đổi.
- **Âm thanh**: mặc định 16 kHz mono, khớp với định dạng máy chủ DB-Robot gửi về.
- **Từ đánh thức**: mặc định "OK Nabu".
- **`applicationId`**: `vn.dbrobot.r1`.

- **Nhạc**: tab Media tìm và phát nhạc từ máy chủ nhạc DB-Robot (`/stream_pcm?song=` rồi luồng MP3
  nó trả về), phát ngay trên loa bằng `media/LocalMusicPlayer.kt`. Thẻ **Máy chủ nhạc** của tab Setup
  có máy chủ cố định `https://ms.dbrobot.vn` (mặc định, `AppConfig.MUSIC_URL`) và ô Tuỳ chỉnh cho
  máy chủ nhạc riêng. Nhạc tự tạm dừng khi trợ lý nghe/nói và
  phát tiếp khi phiên thoại kết thúc; bấm nút trên loa lúc đang phát nhạc là dừng nhạc.
  Tab Media là một trình phát đầy đủ: đĩa xoay, quang phổ, thanh âm lượng, nút phát/dừng/bài kế.
  Quang phổ là FFT thật của bài đang phát trên loa (`android.media.audiofx.Visualizer` gắn vào
  phiên âm thanh của trình phát, trang điều khiển đọc qua `/api/media/spectrum`).

Các tính năng cần máy chủ riêng của bản gốc ([kuteo-git/robot-esp32](https://github.com/kuteo-git/robot-esp32))
không hoạt động với máy chủ xiaozhi thông thường. Bốn thẻ của chúng — Giọng đọc, Bản tin,
Assistant (persona), AI Model — được ẩn trên trang điều khiển (thuộc tính `hidden` trong
`control.html`); thẻ Home Assistant vẫn hiện nhưng cũng cần máy chủ đó.

Phần dưới đây là tài liệu của bản gốc.

---

# Xiaozhi Android — R1 thin client

An Android voice-assistant client for a self-hosted [xiaozhi-esp32-server](https://github.com/78/xiaozhi-esp32),
built as a **thin client**: the device only captures audio, detects the wake word, streams to the
server and plays back the response — all speech recognition, LLM and TTS happen on the server.

It is tuned to run on the **PHICOMM R1** smart speaker (Android 5.1.1 / API 22) as a clean
replacement for its stock firmware app, but it is a standard Android app and runs on newer devices too.

> **⚠️ Requires a server.** This app is a thin client only — it does not work standalone. You need a
> running instance of the companion server, [kuteo-git/robot-esp32](https://github.com/kuteo-git/robot-esp32),
> which handles STT, LLM and TTS. Point the app at your server instance from the control panel's
> Setup tab (see [Server endpoint](#server-endpoint)) before use.

> Forked from [douo/xiaozhi-android](https://github.com/douo/xiaozhi-android). Rewritten around a
> clean-architecture core, with a selectable wake word ("Alexa" / "OK Nabu" / a custom "Na Bi ơi"), hardware-button
> control, LED feedback, and an on-device web control panel for setup.

https://github.com/user-attachments/assets/1ee53869-1987-4e1f-a64a-26c7c0ec032f

## Features

- **Wake word** — on-device detection, selectable between three engines: [Snowboy](https://github.com/Kitt-AI/snowboy)
  "Alexa" (`alexa2.umdl`), [microWakeWord](https://github.com/kahrendt/microWakeWord) "OK Nabu", and a
  custom microWakeWord model **"Na Bi ơi"** (`assets/mai_oi/mai_oi.tflite`, trained under
  `services/wakeword_training` in the server repo). Alexa exposes an adjustable sensitivity (plus a
  stricter one while speaking); "Na Bi ơi" exposes a score threshold; "OK Nabu" has a fixed cutoff
  compiled into its `.so` and is not tunable.
- **Connect-on-wake** — no server connection until the wake word fires (avoids idle timeouts).
- **Continuous conversation** — stays in the session across turns; the server ends it after silence, and
  a client-side backstop sleeps the device after several replies with no real user speech (a runaway
  false-wake guard, reset whenever the user actually speaks).
- **Interrupt** — pressing the hardware button while the assistant is speaking or playing music stops it.
  The R1 has no working acoustic echo cancellation in the app's capture path (the 4-mic array's AEC
  lives in the vendor DSP, which the app bypasses by reading the raw Android mic), so *voice* barge-in
  while audio is playing is unreliable and is disabled for the "Na Bi ơi" engine — the button is the
  reliable interrupt.
- **No self-hearing** — while the assistant's own speaker is emitting audio, the mic is not streamed to
  the STT server (a playback-gated mute), so the robot never transcribes its own TTS as a user turn.
- **Far-field mic AGC** — a software automatic-gain-control stage applied only while actively
  listening for speech (before Opus encoding), so quiet/far speech reaches the STT server at a
  usable level without amplifying idle background noise.
- **Hardware button** (R1 `KEYCODE_PHICOMM_OK`): idle → wake · awake (listening or speaking) → sleep.
- **LED feedback** — the R1 LED ring lights up in different colors for listening / speaking / music,
  via the `msgcenter` system service.
- **Boot start** — launches automatically on device boot.
- **Music** — the server streams music (e.g. YouTube via a pytube service) back as normal audio, in
  stereo at 48 kHz.
- **Bluetooth audio out** — pair a speaker or headphones from the control panel and every sound the
  box makes goes there instead: replies, music and the bulletin. Scan, pair, connect, disconnect,
  forget, and an auto-reconnect that reaches for the last speaker at startup and when it comes back
  on. Pressing disconnect suspends that until you connect again, so it does not reconnect behind
  you. The R1's own speaker is silent while a Bluetooth one is connected.
- **On-device web control panel** (port `8088`) — configure the server, wake engine, LLM and Home
  Assistant integration, run a live A/B mic test, follow the app's activity log and view chat
  history, all from a browser — no rebuild required. See [Control panel](#control-panel-port-8088) below.
- **Daily news bulletin** — a scheduled time, a checklist of categories in the order they should be
  read, and a reading voice, all set from the control panel. An on-device alarm fires at the
  configured time (the WS connection is connect-on-wake, so the clock has to live here rather than
  on the server) and asks for the bulletin the same way saying "đọc bản tin" does.
- **Assistant persona** — an optional custom system prompt (`custom_prompt`) sent to the server on
  connect, editable from the control panel's Setup tab.
- **Home Assistant integration** — fetch/search devices from a Home Assistant instance, annotate and
  save a device list that gets sent to the server as `ha_config` so the LLM can reference them.
- **Pluggable LLM config** — server URL, API key, model and transport (OpenAI-compatible / SSE) are
  configurable at runtime and sent to the server as `llm_config`; includes a one-click connectivity
  test.

## Architecture

The voice runtime follows clean architecture so the platform/device details stay out of the logic:

```
presentation (ui/)
  ChatViewModel ──────────── builds the Protocol, starts the runtime, exposes state to Compose
  control/ControlServer ──── on-device HTTP control panel (NanoHTTPD), reads/writes Settings
        │
domain (domain/voice/)
  VoiceAssistant ─────────── the wake → listen → speak state machine (no Android dependencies)
  SttAgc ─────────────────── far-field gain-control applied only while LISTENING, before Opus
  MicTest ────────────────── A/B mic capture (raw vs. +AGC) for the control panel's mic test
  ports: WakeWordDetector · AudioCapture · AudioPlayback · SoundEffects · LedIndicator
        │
data (data/voice/)
  SnowboyWakeWordDetector · MicroWakeWordDetector · MaiOiWakeWordDetector
  RecorderAudioCapture ───── owns the single AudioRecord (mic is held exclusively, continuously)
  OpusAudioPlayback · AudioTrackSoundEffects
  MsgCenterLedIndicator ──── LED ring via the msgcenter system service (reflection, not sysfs)
        │
protocol/
  WebsocketProtocol · MqttProtocol ─ transport to the xiaozhi server (ws://<server>:8000/xiaozhi/v1/)
        │
data/
  Settings (SharedPreferences, live/runtime) · AppConfig (compile-time defaults)
```

- **domain** depends on nothing Android-specific; the ports are plain interfaces.
- **data** implements the ports over the device infrastructure (snowboy/microWakeWord, `AudioRecorder`,
  Opus codec, `AudioTrack`, the `msgcenter` LED service).
- **presentation** is a thin `ViewModel` that wires a `Protocol` (WebSocket/MQTT) into the
  `VoiceAssistant` and surfaces its state flows; `ControlServer` is a parallel, independent entry
  point that reads and writes the same `Settings`.
- Dependencies are wired with Hilt (`data/voice/VoiceModule.kt`).

## Build

Requirements: JDK 17, Android SDK (platform 35, build-tools 35), NDK 27, CMake 3.22.1, `minSdk 22`.

**Build release — that is what runs on the device.** The R1 is a slow single-board device; the debug
build is measurably worse there (no R8 optimisation, and logging left in on the audio hot path).

```bash
JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleRelease
# debug build -- only when you actually need logcat output:
JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleDebug
```

The release build uses R8 minification; JNI classes (Opus, snowboy, microWakeWord) are kept via
`app/proguard-rules.pro`. Native libraries (Opus, Snowboy JNI) need the NDK, so the first build is
noticeably slower than incremental ones.

**Release strips logging.** `proguard-rules.pro` marks every `android.util.Log` call
(`v/d/i/w/e/wtf`) as side-effect-free, so R8 deletes them along with the string building that fed
them. Expect an empty logcat from a release build — that is intended, not a broken install. Crash
reporting still works: `VApplication` writes stack traces to `/sdcard/voicebot-crash.log` with real
file I/O rather than through `Log`. Build debug when you need to watch logcat.

`applicationId` is `vn.dbrobot.r1` for **every** build type, so this app coexists with the
stock aiboxplus app rather than replacing it. Debug and release therefore share one package and one
data directory: installing either replaces the other in place (both are signed with the debug key, so
`pm install -r` works without uninstalling).

## Installing on the device

### Standard `adb install`

If the device is reachable over USB or a stable network, a normal install works:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### R1 over Wi-Fi (no USB) — push + `pm install`

The R1 has no USB debugging cable in normal operation, so ADB is connected over TCP
(`adb connect <device-ip>:5555`). Streaming `adb install` directly over 2.4 GHz Wi-Fi can drop mid-transfer
and hang on "waiting for device" indefinitely. The reliable path is to push the APK to local storage,
then install it from a shell:

```bash
adb connect <device-ip>:5555
adb -s <device-ip>:5555 push app/build/outputs/apk/release/app-release.apk /data/local/tmp/rc.apk
adb -s <device-ip>:5555 shell pm install -r /data/local/tmp/rc.apk
adb -s <device-ip>:5555 shell rm -f /data/local/tmp/rc.apk
```

- `pm install -r` kills the running app process; the control panel (port 8088) is unreachable until
  the app relaunches (a watchdog process or `am start` brings it back).
- Verify the install: `adb shell dumpsys package <applicationId> | grep lastUpdateTime`.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (e.g. after regenerating the debug keystore) requires
  `adb shell pm uninstall <applicationId>` first — this wipes the app's `SharedPreferences`, so every
  control-panel setting reverts to its default afterward.

### Server endpoint

The DB-Robot build asks `https://sv1.dbrobot.vn/xiaozhi/ota/` at start-up and takes the WebSocket
URL and token from the reply. To use another `xiaozhi-esp32-server` instance, enter its OTA URL in
the control panel's Setup tab and press Connect.

## Control panel (port 8088)

The app runs a small on-device HTTP server (`control/ControlServer.kt`, NanoHTTPD) that serves a
single-page control panel (`assets/control.html`) for configuring and debugging the client without
rebuilding it. Open `http://<device-ip>:8088` from any browser on the same network.

### What you can do from it

- **Setup tab** — server/OTA URL; a single **Wake word** card that groups the engine selector
  (Alexa / OK Nabu / Na Bi ơi) with the selected engine's own control (Alexa sensitivity, "Na Bi ơi"
  threshold, or a note that "OK Nabu" is not tunable); LLM provider/model/API key with a connectivity
  test; Home Assistant URL/token with device fetch + search + annotate; the Assistant persona (custom
  system prompt); and **Restart app** as the last card. All live-editable, some require an app restart
  to take effect (notably mic source and sample rate, since `AudioRecord` is opened once at start).
- **Mic test (A/B)** — since the wake-word detector holds the microphone exclusively, the test taps
  into the same audio loop instead of opening a second `AudioRecord`. Two modes:
  - **Raw** (`agc=0`): buffers the mic signal *before* any gain processing — hear the mic as-is.
  - **+AGC** (`agc=1`): runs a separate `SttAgc` instance over a copy of the buffer using the
    currently configured target/max-gain, so you can preview far-field gain settings live, even
    while the app is idle. Produces a downloadable 16 kHz mono WAV (auto-stops after 30s).
- **Bluetooth card (Setup tab)** — the adapter switch, a Quét button that runs one 12-second scan
  per press, the device list (audio devices only unless you ask for all), and a Phát thử button.
  One scan per press, because the AP6335 shares its radio with Wi-Fi and the panel itself arrives
  over that Wi-Fi.
- **LED control** — trigger LED states directly for testing.
- **Chat log** — recent conversation turns with real timestamps (from when the message actually
  happened, not from when the browser polled for it).
- **Bản tin (news bulletin)** — a Settings-tab card: on/off, the time of the daily reading, the five
  categories (drag ⠿ to set the reading order), a dedicated reading voice, and a test button. Saving
  it re-arms the on-device alarm and pushes the checklist to the server, which does the actual
  fetching/editing/synthesis (see the `news` service in the robot-esp32 repo). All three triggers —
  saying "đọc bản tin", the test button, the daily alarm — send the same typed query, so they share
  one code path.
- **Activity log** — a drawer behind the floating **Log** button (above Chat) showing the app's own
  event journal: connects, config changes, conversation lifecycle, errors. Colour-coded by level with
  an all/errors filter. Deliberately *not* logcat: release builds strip every `android.util.Log`
  call, and on the R1 logcat is flooded by the mic driver and evicts app lines within seconds.
  Entries are also appended to `/sdcard/voicebot-app.log` (rotating at 256KB) by a background
  thread, so what happened before a crash or restart is still readable. Credentials are masked.
- **Restart** — restart the app process from the panel (the last card in the Setup tab).

### HTTP API

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/state` | GET | Full JSON snapshot of settings + recent chat log |
| `/api/set?key=&value=` | GET/POST | Set a single setting by key |
| `/api/say?text=` | GET | Speak arbitrary text via TTS |
| `/api/led?state=` | GET | Force an LED state |
| `/api/restart` | GET | Restart the app |
| `/api/mic/start[?agc=1]` | GET | Start the A/B mic test (raw or +AGC) |
| `/api/mic/stop` | GET | Stop the mic test |
| `/api/mic/rec.wav` | GET | Download the last mic-test recording |
| `/api/setup/server` | GET/POST | Server/OTA setup helper |
| `/api/llm/models` | GET | List models for the configured LLM provider |
| `/api/llm/test` | GET/POST | Test the configured LLM connection |
| `/api/ha/devices` | GET/POST | Fetch/search Home Assistant devices |
| `/api/ha/test` | GET | Test the Home Assistant connection |
| `/api/bt/state` | GET | Adapter state, scan results and the connected speaker (polled only while the card is open) |
| `/api/bt/enable?on=` | POST | Turn the Bluetooth adapter on or off |
| `/api/bt/scan/start`, `/api/bt/scan/stop` | POST | Run or cancel one discovery |
| `/api/bt/pair?addr=` | POST | Bond with a device, auto-answering its PIN prompt for 30 seconds |
| `/api/bt/connect?addr=`, `/api/bt/disconnect?addr=` | POST | Route audio to a bonded device, or stop |
| `/api/bt/forget?addr=` | POST | Remove the bond |
| `/api/bt/auto?on=` | POST | Reconnect the last speaker automatically |
| `/api/logs?since=<seq>` | GET | Activity-log entries newer than `seq` (incremental; the drawer polls only while open) |
| `/api/logs/clear` | POST | Clear the in-memory activity log |
| `/api/news/save` | POST | Save the whole Bản tin card (JSON body); re-arms the alarm and forwards the checklist to the server |
| `/api/news/test` | POST | Play the bulletin now (sends the same typed query the alarm does) |

Secrets (API keys, HA tokens) are never echoed back in full — `/api/state` reports only whether a
value `*_set` is present, and the panel masks them in the UI.

### Gotchas

- **`/sdcard/control.html` shadows the bundled UI.** `serveAsset()` checks for this file first, so
  it can be used to iterate on the UI without rebuilding the app — but if you forget to remove it
  after building a new APK, the control panel will keep showing the *old* UI even though the new
  one is bundled inside. Always `rm /sdcard/control.html` after a build that changes `control.html`.
- **The audio format is not negotiated.** The server encodes from its own config and this client
  decodes from `Settings`, so `playbackSampleRate` and `playbackChannels` have to match
  `xiaozhi.audio_params` on the server. When they differ the frames are cut in the wrong places and
  the audio comes out as noise. The panel shows the server's announced format beside the two
  controls and warns in red when they differ, which is as far as it can go. Reaching that state
  costs one tap, and both ends need a restart after the change.
- **`volume` on a Bluetooth speaker is a different index from the one the slider used to move.**
  The box keeps one volume index per output device, and on this ROM `getDeviceForStream` answers
  SPDIF while the audio goes out over A2DP, so `setStreamVolume` wrote a number the speaker never
  read and the panel showed 100% over an output sitting at 6 of 15. The app now writes the A2DP
  index directly when a speaker connects and when it starts up with one already connected.
- Settings changed via `/api/set` are **not clamped** server-side even if the corresponding slider in
  the UI has a max — a value outside the slider's range can still be set directly through the API.
- **`volume` is quantized to the hardware's step count**, reported as `volume_steps` in `/api/state`
  (15 on the R1, so one step is ~6.7%). A percent you send is rounded to the nearest step, and
  `/api/state` reports the step actually in effect — so reading back a value you just wrote can
  differ by up to half a step. The panel's slider is driven by step index for this reason and can
  only land on reachable values; anything talking to `/api/set` directly should expect the rounding.

## Settings reference

All runtime-configurable settings live in `data/Settings.kt`, backed by `SharedPreferences`, and are
readable/writable through the control panel or its HTTP API.

| Category | Keys |
|---|---|
| Wake word | `wakeEngine`, `wakeSensitivity`, `wakeSensitivitySpeaking`, `maiOiThreshold`, `maiOiThresholdSpeaking` |
| Mic / AGC | `micSource`, `micGain`, `agcEnabled`, `agcTarget`, `agcMaxGain` |
| LED | `ledIdle`, `ledListening`, `ledSpeaking`, `ledMusic` |
| Audio playback | `volume`, `eqEnabled`, `eqBands`, `loudnessMb`, `playbackSampleRate`, `playbackChannels` |
| Bluetooth out | `btLastDevice`, `btAutoReconnect` |
| Server / transport | `otaUrl`, `wsUrl`, `wsToken` |
| LLM | `llmProvider`, `llmBaseUrl`, `llmApiKey`, `llmModel`, `llmTransport` |
| Home Assistant | `haUrl`, `haToken`, `haDevices` |
| Assistant persona | `customPrompt` |
| News bulletin | `newsEnabled`, `newsTime`, `newsCategories` (ordered `key:0\|1` CSV — order *is* reading order), `newsVoice` |

## Notes for Android 5.1.1 (R1)

- `AudioTrack.Builder` is API 23+, so the legacy `AudioTrack` constructor is used.
- `pm install` is slow (dex2oat) — install in the background and poll for completion.
- Java/Kotlin crashes are written to `/sdcard/voicebot-crash.log`.

## Native libraries

Reused from the stock R1 app (placed under `app/src/main/jniLibs/`):

- `libsnowboy-detect-android.so` + `assets/snowboy/{alexa2.umdl,common.res}` — "Alexa" wake word.
- `libmicro_wake_word_jni.so` — "OK Nabu" wake word (microWakeWord). Selectable at runtime from the
  control panel's Setup tab.

## Credits

- Upstream client: [douo/xiaozhi-android](https://github.com/douo/xiaozhi-android)
- Server: [kuteo-git/robot-esp32](https://github.com/kuteo-git/robot-esp32) (self-hosted, based on
  [xiaozhi-esp32](https://github.com/78/xiaozhi-esp32) / xiaozhi-esp32-server)
- Wake word: [snowboy](https://github.com/Kitt-AI/snowboy) · [microWakeWord](https://github.com/kahrendt/microWakeWord)
