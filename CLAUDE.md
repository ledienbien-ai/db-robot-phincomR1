# CLAUDE.md — Xiaozhi Android (R1 thin client)

Onboarding cho AI. Đọc cùng `README.md` (human-facing, features/architecture) và repo server
[kuteo-git/robot-esp32](https://github.com/kuteo-git/robot-esp32), trên máy này ở
`/Volumes/Data2/git/robot-esp32/` (server + services + toàn hệ thống) — xem `README.md` và
`SETUP.md` bên đó; repo đó KHÔNG có `CLAUDE.md` riêng.

## Là gì
Android voice client cho self-hosted **xiaozhi-esp32-server**. **Thin client**: máy chỉ thu audio,
phát hiện wake word, stream lên server; STT/LLM/TTS chạy hết trên server (Mac mini). Fork từ
`douo/xiaozhi-android`, viết lại theo clean-architecture, wake word (3 engine, xem bảng dưới),
nút cứng + LED.
Chạy chính trên **PHICOMM R1** (Android 5.1.1 / API 22) thay firmware gốc.

- Nhánh làm việc: **`main`**. Repo hiện chỉ có đúng 1 nhánh (`main` ↔ `origin/main`); nhánh
  `refactor/clean-architecture` doc cũ nhắc tới **không còn tồn tại** ở cả local lẫn remote.
- `applicationId = vn.dbrobot.r1` (bản DB-Robot → cài **song song** app gốc aiboxplus, không đụng package `info.dourok.voicebot`). OTA mặc định `https://sv1.dbrobot.vn/xiaozhi/ota/`, audio 16 kHz mono, wake mặc định `nabu` — xem `data/AppConfig.kt` + `data/ServerProvisioner.kt`.
- Toolchain: **JDK 17** (`/opt/homebrew/opt/openjdk@17`), compileSdk 35, minSdk 22, NDK (Snowboy + Opus native).

## Build + Install (đã verify release end-to-end 2026-07-28)
```bash
# BUILD
cd /Volumes/Data2/git/xiaozhi-android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleRelease        # -> app/build/outputs/apk/release/app-release.apk (~15 MB)  <-- BẢN ĐỂ CHẠY
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk   (~25 MB)

# INSTALL lên R1 (10.25.113.209) — KHÔNG có adb USB, dùng adb TCP 5555
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB connect 10.25.113.209:5555
$ADB -s 10.25.113.209:5555 push app/build/outputs/apk/release/app-release.apk /data/local/tmp/rc.apk   # 15MB ~36s
# rồi pm install QUA SHELL 8080 (xem "R1 shell"):  pm install -r /data/local/tmp/rc.apk   -> "Success" (~30-90s)
# xong: rm -f /data/local/tmp/rc.apk  VÀ  rm -f /sdcard/control.html  (nếu không panel vẫn hiện UI cũ)
```
- **Release là bản để chạy trên máy**, không phải debug: R8 minify + xoá sạch `android.util.Log`
  (`-assumenosideeffects`), 15 MB thay vì 25 MB. Logging nằm trên hot path (audio từng frame, điểm wake).
  - Release **ký bằng debug key** (cố ý, xem `app/build.gradle.kts`) → `pm install -r` đè thẳng lên bản
    đang cài, **KHÔNG cần uninstall, KHÔNG mất settings**. Đã verify: 43 thiết bị HA + ota_url còn nguyên.
  - Drawer Nhật ký vẫn chạy trong release vì `AppLog` cố tình KHÔNG đi qua `android.util.Log`.
  - Sửa native/JNI mà thêm class → nhớ thêm keep rule vào `proguard-rules.pro` (Snowboy/Opus/microWakeWord
    đã có; R8 đổi tên class là JNI bind hụt, chỉ lộ ra lúc chạy chứ build vẫn xanh).
- ⚠️ **CẢ HAI đường adb đều hay rớt qua wifi 2.4GHz**: `adb install` (stream) treo ở "waiting for device";
  `adb shell pm install` chết giữa chừng với `error: closed`. `adb push` thì ổn. → push bằng adb, **cài
  bằng shell 8080** (độc lập kết nối adb).
- ⚠️ Shell 8080 chạy uid=**system**, không đọc được `/data/local/tmp` (SELinux): `ls`/`rm` trên
  `/data/local/tmp/rc.apk` trả `Permission denied`. **Nhưng `pm install -r` từ đúng đường dẫn đó vẫn chạy**
  (pm là service đặc quyền, tự đọc file). Đừng thấy `ls` fail mà tưởng phải đổi chỗ đặt APK. Muốn xoá APK
  tạm thì dùng `adb shell rm`, không phải shell 8080.
- `pm install -r` KILL process app → ControlServer :8088 tắt tới khi app relaunch (watchdog kéo dậy ~4s;
  đo thực tế panel trả 200 ngay lần curl đầu sau khi cài).
- Verify cài xong: `dumpsys package vn.dbrobot.r1 | grep lastUpdateTime`.
- Verify panel đúng bản: `curl -s http://10.25.113.209:8088/ | md5` so với
  `unzip -p app-release.apk assets/control.html | md5` và file trong repo — 3 cái phải bằng nhau.
- INSTALL_FAILED_UPDATE_INCOMPATIBLE (đổi debug keystore) → `pm uninstall vn.dbrobot.r1` trước (mất SharedPreferences = mọi setting panel về default).

## Kiến trúc (clean architecture)
`domain/` = logic thuần (không Android); `data/` = impl platform; `ui/` = Compose; `protocol/` = WS/MQTT.

| Vùng | File chính | Vai trò |
|---|---|---|
| Runtime | `domain/voice/VoiceAssistant.kt` | Vòng đời **wake→listen→speak**. `runAudioLoop()` (dòng ~90) collect 1 flow mic DUY NHẤT, rẽ nhánh theo state. |
| Capture | `AudioRecorder.kt` + `data/voice/RecorderAudioCapture.kt` | 1 `AudioRecord` (16kHz mono PCM16) **giữ mic độc quyền liên tục** (bật AEC+NoiseSuppressor+AGC phần cứng theo AudioSource). |
| Wake | `data/voice/{Snowboy,MicroWakeWord,MaiOi}WakeWordDetector.kt` | **3 engine**, chọn bằng `wake_engine` (cần restart): `alexa` = Snowboy `alexa2.umdl` (+`ai/kitt/snowboy`), `nabu` = microWakeWord "OK Nabu" (ngưỡng compile cứng trong `libmicro_wake_word_jni.so` → KHÔNG chỉnh được), `mai_oi` = "Nabi ơi" (`mai_oi/mai_oi.tflite`, chỉnh bằng `mai_oi_threshold`). Snowboy `setStrict()` khi đang SPEAKING để TTS không tự kích. ⚠️ `wake_sensitivity` (Snowboy, cao = nhạy hơn) NGƯỢC hướng với `mai_oi_threshold` (điểm số, **thấp** = nhạy hơn). |
| AGC STT | `domain/voice/SttAgc.kt` | AGC phần mềm **CHỈ áp lúc LISTENING, TRƯỚC Opus** (kéo giọng xa lên `target`, trần `maxGain`, sàn `floor`). `gain = target/max(env,floor)`, clamp `[1,maxGain]`. Trần thật = `target/floor` (0.35/0.004 ≈ 87.5x) — maxGain > mức đó vô nghĩa. |
| Codec | `OpusEncoder/Decoder/StreamPlayer.kt` | Opus 16kHz. |
| Playback | `data/voice/OpusAudioPlayback.kt` | AudioTrack + Equalizer (`domain/voice/AudioPlayback.kt`). |
| LED | `data/voice/MsgCenterLedIndicator.kt` | LED ring QUA system service **msgcenter** (`sendMsg(4096,code,0)` reflection) — KHÔNG ghi sysfs. Không bật được cả 2 vòng đèn cùng lúc. |
| Bluetooth | `domain/bluetooth/BtController.kt` + `data/bluetooth/{AndroidBtController,BtHidden}.kt` | **A2DP source**: ghép đôi loa/tai nghe rồi phát tiếng RA đó. Chiều ngược (điện thoại stream VÀO R1) là `A2dpSinkService` của platform, cố ý không điều khiển từ đây. Xem Gotchas. |
| Config | `data/AppConfig.kt` (default) + `data/Settings.kt` (SharedPreferences, runtime) | Settings đổi live qua control panel; một số cần restart app (mic_source, sample rate). |
| Protocol | `protocol/WebsocketProtocol.kt` | WS tới server `ws://<mac>:8000/xiaozhi/v1/`. Connect-on-wake. |
| Media | `domain/voice/MediaSessionState.kt` + `MediaCommands.kt` | Nhạc đi CHUNG pipeline voice (không có player riêng — ExoPlayer đã gỡ). State `IDLE/DOWNLOADING/PLAYING/PAUSED/STOPPED` + queue, server đẩy xuống qua WS. |
| Logs | `domain/voice/AppLog.kt` | Ring buffer in-app cho drawer Nhật ký. **Tồn tại vì logcat trên R1 vô dụng**: driver 4 mic (UNI_4MIC) spam ngập buffer, log app bị đẩy ra sau vài giây. |
| Bản tin | `news/NewsAlarmScheduler.kt` + `NewsAlarmReceiver.kt` | Hẹn giờ đọc bản tin trên máy. Nội dung do **server** soạn (`core/news/*` bên robot-esp32); panel chỉ sửa config rồi push. |
| Debug | `domain/voice/VoiceDebugState.kt` | Snapshot `voiceState`/`awake` lộ qua `/api/state` (lý do: xem mục Logs). Panel dùng để vẽ voice orb ở header. |

## Control panel :8088 (`control/ControlServer.kt` + `assets/control.html`)
Web control on-device (NanoHTTPD) như control center của aiboxplus. Mở `http://10.25.113.209:8088`.
- ⚠️ **BẪY QUAN TRỌNG**: `serveAsset()` ưu tiên **`/sdcard/control.html`** nếu tồn tại → che asset trong APK.
  Dùng để sửa UI khỏi build. **Sau khi build lại app phải `rm /sdcard/control.html`** nếu không sẽ thấy UI cũ.
  (Đã dính bug này: build có card mới nhưng trang vẫn cũ vì file /sdcard 06-30 còn đó.)
- API (đầy đủ, khớp `ControlServer.serve()`):

  | Nhóm | Endpoint |
  |---|---|
  | Core | `GET/POST /api/state`, `/api/set?key=&value=`, `/api/say?text=`, `/api/led?state=`, `/api/restart` |
  | Mic test | `/api/mic/start[?agc=1]`, `/api/mic/stop`, `/api/mic/rec.wav` |
  | Cập nhật | `/api/update/state`, `/api/update/check`, `/api/update/install` (xem `update/Updater.java`; `auto_update` đặt qua `/api/set`) |
  | Log | `/api/logs?since=<seq>` (chỉ trả entry mới hơn `seq`), `/api/logs/clear` |
  | Bản tin | `/api/news/save` (POST body JSON), `/api/news/test` |
  | Media | `/api/media/search?q=`, `/api/media/play` (POST body), `/api/media/{pause,resume,next,stop}`, `/api/media/seek?position_s=`, `/api/media/state` |
  | Setup | `/api/setup/server?ota=`, `/api/setup/wake?engine=`, `/api/setup/llm` |
  | LLM | `/api/llm/models`, `/api/llm/test` |
  | Home Assistant | `/api/ha/test`, `/api/ha/devices` |
  | Bluetooth | `/api/bt/state`, `/api/bt/enable?on=`, `/api/bt/scan/{start,stop}`, `/api/bt/{pair,connect,disconnect,forget}?addr=`, `/api/bt/auto?on=` |

  `/api/state.chat[]` gồm `sender`,`text`,`time` — epoch-ms THẬT lấy từ `ConversationLog.Entry.time`
  lúc tin nhắn xảy ra, KHÔNG phải giờ client poll thấy.
- Giá trị dài (persona, danh sách nhạc, config bản tin) gửi qua **POST body**, không qua query string
  — query string có trần độ dài và **cắt âm thầm** chứ không báo lỗi.
- `serveAsset()` gửi `Cache-Control: no-store` → không cần hard-refresh sau khi đổi UI nữa.
- `volume` bị lượng tử hoá theo số nấc phần cứng (`volume_steps` trong `/api/state`, R1 = 15 → 1 nấc
  ≈ 6,7%): % gửi xuống được làm tròn tới nấc gần nhất, `/api/state` trả về nấc đang thực sự áp dụng.
  Slider chạy theo **chỉ số nấc**, không phải %, nên không đặt được giá trị máy không giữ nổi.

### control.html — cấu trúc & bẫy
1 file duy nhất (~1550 dòng, không build step, không framework). "Component" = helper JS + class CSS:
- `createDrawer({drawerId,backdropId,fabId,bodyClass,onOpen,onClose})` — dựng Chat/Log drawer, tự lo
  `bindFab` + `attachSwipeClose`. Class CSS dùng chung: `.drawer`, `.fab`.
- `activateSeg(ids, activeId)` — mọi nhóm segmented (sample rate, wake engine, filter log).
- `bindSlider(rangeId, valId, key, fmt, sendFmt)` — mọi slider có nhãn giá trị.
- ⚠️ **`.btn-ico` phải gắn `.trail` khi icon đứng SAU chữ.** Trước dùng `:last-child` — sai, vì nhãn là
  text node, không phải element, nên icon đứng TRƯỚC vẫn khớp `:last-child` và mất margin.
- ⚠️ **iOS nuốt cú chạm đầu sau swipe-close.** Safari có fast path cho trang KHÔNG đăng ký touch/pointer
  listener cấp document; gesture tự chế để lại state thừa và bị tính vào tap kế tiếp. Cách trị: 4
  listener **rỗng** ở cuối file (`touchstart/touchend/pointerdown/pointerup`, capture) — chính việc
  đăng ký mới là bản sửa, không phải nội dung handler. **Đừng xoá.** Đã loại trừ: pointer capture, blur.
- Debug trên máy không có devtools: mở `?trace=1` → overlay ghi mọi event tầng thấp. Chính nó tìm ra
  bug iOS ở trên (bật tracer thì hết lỗi → bisect ra thủ phạm).
- Polling: `/api/state` mỗi 1.5s (luôn chạy), `/api/media/state` mỗi 1s (chỉ khi ở tab Media),
  `/api/logs` mỗi 1.5s (chỉ khi drawer Log mở). Field người dùng đang gõ được **guard** khỏi bị poll
  ghi đè (`setupInit`/`newsInit`/`document.activeElement`) — bỏ guard là mất chữ đang gõ mỗi 1.5s.

### Test mic (A/B) — `domain/voice/MicTest.kt`
Mic bị wake-detect giữ độc quyền → **KHÔNG mở AudioRecord thứ 2**. Cách làm: `VoiceAssistant.runAudioLoop`
bơm mỗi frame vào `MicTest.feed()` khi đang recording (tap ở ĐẦU loop = raw). 2 chế độ:
- **Thô** (`agc=0`): buffer PCM raw (trước SttAgc) → nghe mic thật.
- **+AGC** (`agc=1`): `MicTest` chạy 1 `SttAgc` RIÊNG (target/maxGain từ Settings hiện tại) trên bản COPY
  (không mutate buffer của loop), hoạt động cả khi app idle (luồng STT thật chỉ áp AGC lúc LISTENING).
Xuất WAV 16kHz mono, trần 30s tự dừng. Verify: raw peak ~0.01 ambient, +AGC peak chạm đúng target 0.35.

## R1 shell (cổng 8080) — chạy lệnh trên máy
WebSocket subprotocol `v1`, uid=**system**. Gửi `{"type":"shell","type_id":"myshell","shell":"<cmd>"}`,
nhận frames `{"data":...}`. Độc lập app (sống cả khi app crash). **Reboot máy bị chặn** (SELinux), nhưng
`am`/`pm` chạy được. R1 KHÔNG có wget/curl/busybox/toybox — chỉ `/system/bin/pm`. Helper mẫu:
`services/.venv/bin/python` + script gửi WS — xem `robot-esp32/services/r1_watchdog.py::_shell`.
Watchdog `com.user.robot-r1watchdog` tự `am force-stop; am start` khi app chết (~4s), mode `selfbuilt`.

## Gotchas
- **Máy kẹt "Đang trả lời" (panel) = `voice_awake && voice_state==SPEAKING` không bao giờ reset.**
  Đã trị 2026-09-24, xem `domain/voice/SessionEnd.kt`. Ba điều cần nhớ khi đụng lại vùng này:
  - Socket chết **chỉ lộ ra khi GHI**. Websocket của OkHttp bỏ qua `readTimeout` (SO_TIMEOUT=0), và
    lúc SPEAKING thì app không stream mic → không ghi gì → không phát hiện gì. `pingInterval(20s)`
    là cái ghi hộ; bỏ nó đi là bug quay lại. Server pong ~9ms, đã đo.
  - `onFailure` **phải** emit `AudioState.CLOSED` như `onClosed`. Trên wifi nhà, EPIPE mới là cách
    phiên chết phổ biến; chỉ emit `networkErrorFlow` là `MediaSessionState` không ai dọn.
  - `MediaSessionState` là state do **server đẩy**, sống dai hơn phiên. Đừng đọc "đang paused" thành
    "frame này là pause flush" — phải kèm mốc thời gian (`msSincePause`), không thì một bài nhạc
    pause hôm qua nuốt luôn `tts stop` của câu tạm biệt hôm nay.
- Sửa code Kotlin → **phải build+cài lại** (release, xem trên). Sửa chỉ `control.html` → đẩy thẳng
  `/sdcard/control.html` để lặp nhanh khỏi build — nhưng **chép ngược về `app/src/main/assets/` rồi
  `rm /sdcard/control.html`** trước khi build, không thì bản trên máy và bản trong repo lệch nhau âm thầm.
- `mic_source` / sample rate / `wake_engine` đổi cần **Restart app** (AudioRecord + detector mở 1 lần lúc start).
- Đo layout panel mà không có thiết bị: Chrome headless **bỏ qua `--window-size`** (kẹt ở viewport 500px).
  Muốn ép đúng khổ thì nhúng trang vào `<iframe width=390>` trong 1 file harness rồi
  `--headless=new --allow-file-access-from-files --dump-dom`, đọc số đo từ script chèn vào trang.
- Đổi `AGC_MAX_GAIN` slider max ở HTML; server `/api/set` KHÔNG clamp → set >slider được qua curl.
- Native (Snowboy/Opus) cần NDK; build đầu chậm.

### Bluetooth: phát tiếng ra loa ngoài (A2DP source) — đo 26/09/2026

Máy **có** Bluetooth và có cả hai chiều: chip **AP6335** trên UART `/dev/ttyS1`, addr
`98:BB:99:3F:2E:2C`, tên `Phicomm_R1_2E2B`; chạy sẵn `A2dpService` (source), `A2dpSinkService`,
`HeadsetService`, `AvrcpControllerService`; `audio_policy.conf` có cả `AUDIO_DEVICE_OUT_ALL_A2DP` và
`AUDIO_DEVICE_IN_BLUETOOTH_A2DP`. App chỉ làm chiều **source**.

- **Ba method phải gọi bằng reflection**, cả ba đã xác nhận CÓ trên ROM này:
  `BluetoothDevice.removeBond()` (= "forget"), `BluetoothA2dp.connect()/disconnect()`. Còn
  `createBond()`, `getBondState()`, `setPin()`, `setPairingConfirmation()`, `getProfileProxy()`,
  `startDiscovery()` đều public — kiểm bằng `javap` trên `android-35/android.jar`. R8 không đổi tên
  class framework nên **không cần keep rule**, nhưng reflection hụt chỉ lộ ra lúc chạy → mỗi lỗi
  mang tên method về tận panel thay vì log rồi im.
- **`BluetoothA2dp.connect()` trả `false` KHÔNG có nghĩa là thất bại.** Đo được: trả false rồi 6
  giây sau loa vẫn nối; nó trả false cả cho địa chỉ chưa ghép đôi (chỉ nghĩa là "đã nhận lệnh" hoặc
  "đang connecting"). Vì thế `bt_last_device` **chỉ ghi khi broadcast `STATE_CONNECTED` bắn** — bằng
  chứng duy nhất loa thật sự nhận tiếng. Bản đầu ghi ngay trong `connect()`, và một địa chỉ bịa gõ
  tay đã thành "thiết bị được nhớ".
- **Ghép đôi tự đồng ý, nhưng chỉ trong 30 giây sau khi người bấm Pair.** Máy không màn hình nên
  không ai trả lời được hộp thoại PIN; auto-accept vô điều kiện thì thiết bị lạ trong nhà ghép được
  vào loa. Cửa sổ đo bằng **`elapsedRealtime`, không phải wall clock** — đồng hồ máy nhảy khi NTP về
  sau boot, và một cửa sổ đo bằng đồng hồ nhảy là cửa sổ không bao giờ đóng hoặc không bao giờ mở.
- **Huỷ quét trước mọi Pair/Connect.** Radio đang nhảy tần không đáp ứng được page — ghép đôi lúc
  đang quét là cách làm nó "lúc được lúc không".
- **Quét một lần 12 giây mỗi lần bấm, không chạy liên tục.** AP6335 dùng chung radio với wifi, và
  panel đi qua wifi. **Đo được: không rớt** — panel trả lời 0.06–1.15s suốt lúc quét, 8 thiết bị
  trong 14s rồi tự dừng.
- **Auto-reconnect tạm dừng khi bấm Disconnect, và trạng thái đó CỐ Ý không persist.** "Ngừng phát
  ra loa đó" là ý định của phiên này; giữ nó qua restart thì một lần ngắt hôm nay làm bản tin sáng
  mai không ra loa — đúng cái bẫy `MediaSessionState` ở trên. Đo được: ngắt → 24 giây không tự nối
  lại; ngắt rồi restart app → `tự nối lại (profile ready) -> ok`.
- **Kết nối A2DP nằm trong stack chứ không trong app**, nên nó sống qua `pm install -r` — và khi đó
  không có broadcast `CONNECTION_STATE_CHANGED` nào bắn. Mọi thứ cần làm "lúc nối" cũng phải làm
  lúc profile proxy sẵn sàng mà đã có thiết bị nối sẵn.
- Danh sách quét mang cờ `audio` chứ không lọc ở server, nên switch "hiện tất cả" không tốn round
  trip. Loa rẻ khai báo sai device class là chuyện có thật.

### Âm thanh qua Bluetooth: nén là thật, và âm lượng đi vào bucket sai

Đo với một loa thật (`Kitchen speaker`) 26/09/2026.

- **Nén là thật và không sửa được từ app.** Stack có ký hiệu `sbc` và **0 hit** cho
  `aac`/`aptx`/`ldac` → **SBC only**. Module `a2dp` chỉ khai báo **44100** trong khi app phát
  **48000** → resample mọi frame, và **không đổi `playback_sr` để tránh được**: Opus chỉ định nghĩa
  48/24/16/12/8 kHz, không có 44.1. Chỉnh bitpool hay thêm AAC đều phải thay
  `bluetooth.default.so` trong `/system` trên một máy đã mod — ghi lại là đã cân nhắc và xếp cuối.
- **Ba output, và track của app nằm trên cái GỘP hai thiết bị** (`dumpsys media.audio_policy`):

  | output | SR | Devices mask | là gì |
  |---|---|---|---|
  | 2 | 48000 | `00080000` | SPDIF |
  | 304 | 44100 | `00000080` | A2DP thuần |
  | **305** | 44100 | `00080080` | **SPDIF + A2DP** ← track + effect chain của app |

- **Máy giữ một chỉ số âm lượng cho MỖI thiết bị ra, và `setStreamVolume` ghi vào chỉ số sai.**
  `dumpsys audio` trước khi sửa: `STREAM_MUSIC  (default): 6, (spdif): 15` — **không có entry nào
  cho A2DP**, nên nó rơi về `default` = 6/15. `getDeviceForStream` trên ROM này trả về SPDIF
  (`0x80000`, lớn hơn A2DP `0x80` về số) nên mọi lần kéo slider đều ghi vào bucket mà loa không
  đọc: panel báo 100% trên một đường ra đang ở 6/15.
  - Sửa bằng `AudioSystem.setStreamVolumeIndex(stream, index, device)` (hidden) cho cả ba hằng A2DP
    `0x80/0x100/0x200`. Kiểm ở tầng native, `dumpsys media.audio_policy` bảng Streams: stream 03
    giờ là `0080 : 15, 0100 : 15, 0200 : 15` ở chỗ trước đó không có gì. **+9 nấc trên 15.**
  - **`dumpsys audio` KHÔNG thấy thay đổi này, và điều đó không chứng minh gì**: nó in map của
    AudioService (Java), còn `setStreamVolumeIndex` ghi thẳng policy manager tầng native. Phải nhìn
    `dumpsys media.audio_policy`.
- **`LoudnessEnhancer` là thứ chữa "nghe quá nhỏ", không phải kéo dải EQ lên** — nó là effect duy
  nhất trên máy có limiter đi kèm (`audio_effects.conf` → `libldnhncr.so`). Nó **không** phụ thuộc
  `eq_enabled`: mức to và âm sắc là hai câu hỏi khác nhau, và ca đẻ ra nó là một loa Bluetooth quá
  nhỏ *trong lúc EQ đã tắt* — một switch dùng chung thì đúng ca đó không với tới được.
  Mặc định `LOUDNESS_MB = 0`: ghi chú bên `robot-esp32/run_vieneu.sh` từ 25/07/2026 đã đo +3 dB
  boost làm loa trong máy rè trong khi file gốc sạch, nên trần ở đó là trần analog. Loa Bluetooth có
  ampli riêng, nên con số này là con số của **cái loa đang nghe**, không phải của mọi máy.
- **EQ và loudness đều đi theo sang đường Bluetooth**: `dumpsys media.audio_flinger` cho
  `2 effects for session <id>` trên **io=305**, tức output có A2DP trong mask.
- **Đừng đo âm lượng bằng mic nội bộ với `/api/say`.** Đã thử và nó vô dụng: `/api/say` đi qua LLM
  nên mỗi lần nói một câu khác, và bậc thang đo ra **không đơn điệu** (vol=0 → −39.4 dBFS, vol=50 →
  −42.8, vol=100 → −27.3). Muốn đo mức thì phải phát cùng một file mỗi lần.

### Audio stereo đầu-cuối, và trần thật nằm ở đâu (26/09/2026)

Báo cáo ban đầu là "nhạc nghe kém, hình như bị nén". Đo lại toàn chuỗi thì **transport chưa bao
giờ là trần** — nhạc bị **trộn xuống mono trên server** trước cả khi vào Opus.

- **Nguồn không phải vấn đề.** Hỏi thẳng VieNeu-TTS (`POST :8002/v1/audio/speech`): trả về
  **48 kHz mono**, và có nội dung thật tới 20 kHz (500 Hz 0 dB · 4k −37 · 9k −47 · 15k −48 ·
  20k −56). Encoder Opus của server lúc đó đã là **96 kbps, complexity 10, APPLICATION_AUDIO**.
- **Đếm byte trên dây mới là thước.** `/proc/net/dev` của R1 trong lúc phát nhạc, và bậc thang:

  | | wire | payload |
  |---|---|---|
  | mono 96k | 13.7 kB/s | 110 kbps |
  | stereo 120k | 16.5 kB/s | 132 kbps |
  | stereo 160k | 21.6 kB/s | 173 kbps |

  Frame 60 ms ở 96k là **719 byte** (đo bằng chính `opuslib_next` của server); nếu còn ở 24k thì
  chỉ ~165 byte → ~3.7 kB/s, tức thấp hơn 3.7 lần.
- **Server: số kênh do chính encoder quyết.** `audio_to_data_stream`
  và `audio_bytes_to_data_stream` hỏi `opus_encoder.channels`; encoder được dựng một lần từ
  `conn.channels`, đọc từ `xiaozhi.audio_params` như `sample_rate`. Trước đó `set_channels(1)` nằm
  rải rác. Và `pcm_to_data_stream` dùng stride `frame_size * 2`, đúng cho mono và **cắt đôi mọi
  frame stereo**.
- **JNI decoder của app vốn chỉ đúng cho mono**, và đây là loại vỡ im lặng:
  `frame_size = max_output_size / 2` trong khi `opus_decode` muốn **sample MỖI KÊNH** (stereo được
  khai gấp đôi dung lượng thật, sai về phía nguy hiểm), và `return result * 2` thiếu số kênh nên
  mỗi frame stereo trả về **nửa độ dài**. Cả hai đã sửa, `channels` truyền từ Kotlin xuống.
- **`frame_duration` mới là thứ chặn bitrate, không phải ý muốn.** Trần một gói Opus là **1275
  byte**; đo với frame 60 ms stereo: 120k → 899 B, **160k → 1199 B**, 170k → 1274 B, 200k → 1499 B.
  Nên 160k là mức cao nhất còn chỗ thở; muốn cao hơn phải **rút `frame_size_ms` xuống**.
- **Ba lần nâng bitrate khác loại nhau**: 24k→96k mua **trần
  tần số** (Opus tự cắt ngọn ở bitrate thấp); 96k mono→120k stereo mua lại **một kênh bị vứt**;
  120k→160k chỉ mua **độ mịn**, thứ phải ABX mới nghe ra, và nó nằm **trước** chặng SBC.
- **Cái không sửa được từ đây**: stack Bluetooth là **SBC only**, và module a2dp chỉ khai 44100
  trong khi app phát 48000 → resample mọi frame mà **đổi `playback_sr` không tránh được**, vì Opus
  không định nghĩa 44.1 kHz.

### Client và server không thương lượng được định dạng, nên panel phải nói ra

Server mã hoá theo config của nó, app giải mã theo `Settings`. Lệch nhau thì **tiếng bị chia khung
sai và nghe ra rác**, chứ nó không suy giảm dần. Panel lại cho đổi Mono/Stereo và 24/48 kHz bằng
hai cú chạm, nên trạng thái hỏng chỉ cách một lần bấm.

- `ServerAudioParams` giữ những gì server khai trong hello và đẩy ra `/api/state` thành
  `server_sr` / `server_ch`; panel hiện cảnh báo đỏ ngay cạnh hai hàng đó khi lệch. Plain object
  theo khuôn `VoiceDebugState`, vì ControlServer không giữ tham chiếu tới protocol.
- Nó thay một **field chết**: `serverSampleRate` trước đây được ghi vào mỗi lần hello rồi **không
  ai đọc**, tức đúng một dữ kiện có thể bắt được lỗi này đang bị vứt đi.
- **`-1` là trạng thái thứ ba, không phải mono.** Máy vừa khởi động chưa được phép kết tội server
  là lệch khi server chưa nói gì.
- **Proven to fail**: đặt Mono → hiện; đặt thêm 24 kHz → hiện; trả về 48000/2 → ẩn.
- `data/.config.yaml` bị gitignore (có key + token), nên số kênh của server **sống ngoài git**:
  khôi phục config từ backup có thể cho server mono trong khi app stereo, và cảnh báo trên là thứ
  bắt được trường hợp đó.

### adb rớt giữa một lần push để lại adbd chết mà cổng vẫn "open"

Một lần `adb push` 15 MB bị đứt (`failed to read copy response: EOF`) làm **adbd trên R1 treo**:
`nc -z 5555` vẫn báo open, `adb connect` báo `device offline`, và một gói CNXN tự dựng gửi qua `nc`
**không nhận lại byte nào**. Đổi cổng relay, restart adb server, `kill-server` — không cái nào ăn
thua, vì lỗi nằm ở máy chứ không ở Mac.

Chữa bằng shell 8080: **`setprop ctl.restart adbd`** (pid 214 → 13669). `init.svc.adbd` báo
`running` suốt cả lúc treo lẫn sau khi chữa, nên đừng dùng nó để chẩn đoán. Shell 8080 là uid=system
còn adbd chạy uid=shell nên **không kill trực tiếp được**, và khi adb chết thì 8080 là đường duy
nhất còn lại.


## Suggestions
- Commit trên `main`, đừng để lẫn `.idea/*` (đang bị track — cân nhắc gitignore).
- Muốn nghe hiệu ứng gain bằng tai: dùng nút **+AGC** trong Test mic, chỉnh slider rồi ghi lại.
- Nếu far-field vẫn yếu ở maxGain ~80: đòn thật là hạ `SttAgc.floor` (0.004→0.002) hoặc nâng `target`, KHÔNG phải maxGain (bị floor chặn ~87x). Đánh đổi: khuếch đại ồn nền → STT dễ bịa chữ.
