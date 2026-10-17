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
  | Vị trí & thời tiết | `/api/weather`, `/api/location/search?q=`, `/api/location/set` (POST body JSON), `/api/location/clear` (xem `weather/LocationManager.kt`; công cụ MCP cho trợ lý ở `mcp/DeviceTools.kt`) |
  | Radio | `/api/radio/stations`, `/api/radio/play?id=`, `/radio/stream?id=` (đọc luồng HLS của đài bằng `media/HlsAudioStream.java` rồi chuyển cho trình phát trên máy dưới dạng AAC/MP3 liên tục; danh sách kênh và địa chỉ dự phòng ở `media/RadioStations.java`) |
  | YouTube | `/api/yt/search?q=` — loa hỏi `youtubei/v1/search` của youtube.com (không cần key, như NewPipe), dự phòng đọc `ytInitialData` từ trang kết quả; trả `{id,title,channel,duration,live,thumbnail}`. Panel: Tìm → loa phát bài đầu (không phải LIVE) qua máy chủ nhạc + liệt kê phần còn lại; nút "xem" mới mở video trong **iframe của chính trang** (`youtube-nocookie.com/embed`, IFrame API gắn vào iframe có sẵn để bắt `ended`/`error`), loa không phát. ⚠️ Iframe **phải** giữ `referrerpolicy="strict-origin-when-cross-origin"`: trang có `<meta name="referrer" content="no-referrer">` (cho ảnh nền chat) và YouTube từ chối embed không có Referer (lỗi 153). Vì thế iframe do trang tự tạo, KHÔNG để `new YT.Player(div)` tạo. **Ra loa** (v1.5.13): `/api/yt/prepare?id=` → máy chủ nhạc `GET /youtube?id=` (yt-dlp lấy bestaudio, cache 3h) → panel gửi `/api/media/play` với `video_id` = `/stream_mp3?id=yt_<id>`, tức đi đúng đường bài hát của máy chủ nhạc (`musicStreamUrl`). `/stream_mp3?id=yt_*` tự lấy luồng nếu chưa có, nên các bài sau trong hàng đợi không cần prepare. Từ 1.5.13 máy chủ nhạc ms.dbrobot.vn là `youtube-music` (`E:\youtube-music`, nguồn YouTube; `soundcloud-music` thôi dùng). Container `youtube-music-server`, cổng `5000:5000` (chủ dự án tự đặt; ms.dbrobot.vn trỏ vào đó). `/stream_pcm` (tìm, bài khớp nhất — lệnh giọng nói và ô tìm của view `music`), `/youtube?id=` (chuẩn bị video chọn trên panel), `/stream_mp3?id=<id>|yt_<id>` tự lấy theo mã nếu chưa có trong cache. ⚠️ Docker tạo THƯ MỤC `cookies.txt` nếu tệp chưa có lúc chạy container — code chỉ dùng cookies khi là tệp (`os.path.isfile`). Máy chủ cần **Deno** cho yt-dlp ≥2025.11 và tự `pip install -U 'yt-dlp[default]'` mỗi lần khởi động; YouTube chặn "not a bot" thì đặt `cookies.txt` cạnh `app.py`. Parser kiểm trên response thật ghi sẵn của NewPipeExtractor (`mocks/v1/.../youtubesearchextractor`). Xem `media/YouTubeSearch.java`. |
  | Podcast | `/api/podcast/shows`, `/api/podcast/search?q=` (iTunes Search API, country=VN), `/api/podcast/episodes?show=<key>\|apple=<id>` (iTunes lookup ra `feedUrl` → đọc RSS bằng SAX, dừng ở 60 tập), `/podcast/stream?t=<token>` (relay **có Range**: bắt buộc cho .m4a có `moov` ở cuối tệp và cho tua). Tập được phát qua `/api/media/play` với `video_id` = `pod:<token>`; token = SHA-1 của địa chỉ, chỉ địa chỉ đã đọc từ feed mới relay được (không phải open proxy). Danh sách 15 chương trình (tin tức VOV/VnExpress/VietNamNet đứng đầu; RFI đã bỏ vì chưa được cấp phép ở VN) + feed dự phòng ở `media/Podcasts.java`. **Chưa đo trên loa thật**: tua, tạm dừng lâu rồi phát tiếp (kết nối upstream có thể bị đóng). |
  | Báo thức | `GET /api/alarm/state` (`alarms[]`, `sounds[]`, `volume`, `next{at,text}`, `ringing`, `clock_ok`), `POST /api/alarm/save` (body `{alarms:[{id,enabled,time:"HH:mm",days:[0..6 = T2..CN],sound:"default"\|tên tệp,label}],volume}` — cả danh sách), `/api/alarm/stop`, `/api/alarm/test?sound=` (12 giây), `PUT /api/alarm/sound/put?name=` (tệp gửi nguyên làm body, ≤15 MB, tên được làm sạch về ASCII), `/api/alarm/sound/delete?name=`. `/api/state.alarm_ringing` = chữ hiện trên thanh "Tắt báo thức" ("" = không báo). Quy tắc thuần ở `alarm/AlarmBook.java` (có test JDK); `AlarmScheduler` giữ **một** AlarmManager alarm cho lần báo kế, hẹn lại sau mỗi lần báo, lúc khởi động server, `BOOT_COMPLETED`, `TIME_SET`; `AlarmReceiver` chỉ rung khi được giao trong ±3 phút quanh phút hẹn (đồng hồ R1 nhảy từ 2018 khi NTP về). `AlarmRinger`: MediaPlayer lặp, `STREAM_MUSIC`, đặt `alarm_volume` qua `DeviceActions` rồi trả lại, to dần 20s, tự tắt 10 phút, giữ nhạc như phiên thoại. Nút cứng (`VoiceAssistant.onButtonPress`) tắt chuông trước mọi việc khác; wake word bị bỏ qua khi đang rung. Tiếng mặc định `assets/alarm_default.wav` mở bằng `openFd` → `noCompress += "wav"` trong `build.gradle.kts` là bắt buộc. **Chưa đo trên loa thật.** |
  | Nhóm loa / màn hình phụ | `GET /api/group/state` (`name`, `sync`, `members[{id,name,ip}]`, `following`, `displays[{name,ip}]`), `/api/group/scan` (= state + `found[]` từ `SpeakerLink.discover`, cũng sửa IP thành viên theo `id`), `POST /api/group/save` body `{name,sync,members}` (≤8, sameOrigin), `/api/group/volume?ip=&v=` (chỉ IP thành viên → `/api/set?key=volume` của loa đó). Giữa các loa: `POST /api/group/play` (body `{token,from,track}` — chuẩn bị + giữ, trả lời khi prepared, ≤9s), `/api/group/go` `{token,delay}`, `/api/group/cmd` `{c,arg}` — cả ba đòi header `X-DBR-Group` (trang web lạ không gửi được nếu không preflight). `/api/set?key=speaker_name`. `/api/state` có `speaker_name`, `displays`. Xem mục "Nhóm loa & SpeakerLink" dưới. **Chưa đo trên loa thật.** |
  | Giọng nói | `/api/voice/pause?on=1\|0` (tắt/bật từ đánh thức, không lưu qua lần khởi động lại), `/api/voice/wake` (gọi loa bằng tay) — xem `domain/voice/VoiceGate.kt`; `pause_on_music` đặt qua `/api/set` |
  | Wi-Fi | `/api/wifi/state`, `/api/wifi/scan`, `/api/wifi/connect` (POST body JSON `{ssid,password,security,hidden}`), `/api/wifi/forget` (POST body JSON), `GET /api/wifi/list` (text thuần, mỗi dòng `bảo-mật TAB số-vạch TAB tên` — cho bộ cài, vốn không có JSON parser) — xem `net/WifiSetup.java`. Đổi mạng có đường lui: từ một mạng sang mạng khác, không vào được sau 30 giây thì quay lại mạng cũ; từ chế độ cài đặt mạng (loa đang phát "Phicomm R1", `ap:true`), không vào được thì bật lại đúng hotspot đó. Mạng đang dùng không sửa/xoá được. `wifi_hotspot` trong `/api/state` cho panel biết đang ở chế độ này. Các lệnh ghi bị từ chối nếu `Origin` khác `Host`. **Body JSON gửi từ script phải kèm `charset=utf-8` trong Content-Type**: NanoHTTPD 2.3.1 đọc body theo US-ASCII khi thiếu charset, tên mạng có dấu sẽ hỏng (trình duyệt tự thêm `text/plain;charset=UTF-8`). |
  | Nền chat | `GET /api/chat/bg?v=<stamp>`, `POST /api/chat/bg/set` (ảnh tĩnh, body = data URL base64, panel đã thu nhỏ), `PUT /api/chat/bg/put` (GIF/WebP gửi nguyên tệp làm body, tối đa 8 MB — vẽ lại qua canvas sẽ mất chuyển động; NanoHTTPD ghi body của PUT ra tệp tạm nên không nằm trong RAM), `POST /api/chat/bg/style` (body JSON `{"style":…}`), `/api/chat/bg/clear`. Trong `/api/state`: `chat_bg` = mốc thời gian của ảnh đã tải (0 = không có), `chat_bg_style` = `none` \| `file` \| `holo:<id>` \| `url:<địa chỉ>`. Nền `holo:*` do `control.html` tự vẽ bằng canvas (`HOLO`, chỉ chạy khi khung chat đang mở, ~30 fps); ảnh `url:` do trình duyệt tự tải, loa không bao giờ tải — địa chỉ được lọc ở `ChatBackground.clean()` vì nó nằm trong CSS `url("…")`. Xem `control/ChatBackground.java`. |
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
- Tab Media có 3 "view" (`mediaView`): `music` (nhạc của loa + hàng đợi), `yt`, `pod`. Ô tìm kiếm tìm
  theo view, mỗi view giữ query riêng. Poll `/api/media/state` **chỉ thay danh sách khi view là
  `music`** — bỏ guard này là danh sách YouTube/podcast bị hàng đợi đè mỗi giây.
  `applyMediaButtonStates()` chỉ đụng `[data-vid]` (hàng YouTube không có) vì cùng lý do.
- Màn hình thấp (`max-height:940px`): cả tab Media cuộn như một trang, không ghim phần đầu nữa
  (đầu tab đã cao ~730px trên điện thoại, danh sách bị ép còn vài chục px).
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

### Chế độ cài đặt mạng (hotspot "Phicomm R1") — v1.5.7, CHƯA đo trên loa thật

Giữ nút trên đỉnh loa 5 giây → firmware phát hotspot "Phicomm R1", loa ở `192.168.43.1`. Đó là
việc của firmware; **DB-Robot không tự phát Wi-Fi** (bản nháp có luồng tự phát khi mất mạng +
công tắc + nút bật tay; chủ dự án bảo bỏ — đừng thêm lại). Phần DB-Robot làm:
- `WifiSetup` nhận ra chế độ này (`isWifiApEnabled` qua reflection) và cho thẻ Wi-Fi chạy trong đó:
  `connect()` = tắt hotspot → bật client → add/select; thất bại → bật lại đúng hotspot cũ
  (`stopAp()` giữ `getWifiApConfiguration()` trong `lastAp`, `restoreAp()` gọi
  `setWifiApEnabled(lastAp, true)`). Bật lại không được thì để client bật — không bao giờ tắt cả hai.
- Android 5.1 không vừa phát vừa làm client: `scan()` ở chế độ này = tắt hotspot ~6 giây, quét, bật
  lại; trả `{"ok":true,"interrupts":true}`. Panel hỏi trước khi quét. Trước lần quét đầu danh sách rỗng.
- Cần `CHANGE_NETWORK_STATE` ngoài hai quyền Wi-Fi để gọi `setWifiApEnabled` trên API 22.
- Panel (`control.html`): `wifi_hotspot` true → banner + tự nhảy tới thẻ Wi-Fi; sau khi loa rời
  hotspot, panel dò `http://<prefix>.<1-254>:8088/api/state` trên các dải mạng nhà phổ biến
  (`SEEK_NETS`) và so `device_id` để tìm địa chỉ mới.
- Bộ cài (`install.ps1`, `install.sh`): gõ `M` (hoặc quét không thấy loa → trả lời "loa mới") →
  hướng dẫn giữ nút + nối vào "Phicomm R1" → cài qua `192.168.43.1:5555` → `GET /api/wifi/list`,
  `POST /api/wifi/connect` → dò lại loa trong mạng nhà bằng cổng 8088 + `device_id`. Bộ cài không
  gọi `scan` (máy tính sẽ nhảy về Wi-Fi nhà khi hotspot tắt), nên lần đầu người dùng gõ tên mạng.
  Biến môi trường để thử: `LOA_AP_IP`, `LOA_PREFIX` (ps1 nhận danh sách cách nhau bằng dấu phẩy).
- Đã kiểm ngoài loa: mô phỏng `WifiManager` (8 nhóm kịch bản) và chạy hai bộ cài với adb giả +
  máy chủ giả. **Chưa kiểm trên loa thật**: tên hotspot và việc nút bấm còn hoạt động sau khi cài
  DB-Robot (nhất là khi bộ cài đã tắt AI Box Plus); firmware có để yên khi app khác tắt/bật lại
  hotspot của nó không; firmware gốc có mở adb 5555 ở `192.168.43.1` không; app `com.phicomm.*`
  có giữ micro không (bộ cài chỉ in danh sách, không tắt gì).

### Loa đọc địa chỉ IP — v1.5.10, CHƯA đo trên loa thật

Loa không có TTS trên máy: mọi câu nói đều do máy chủ đọc. Nên "đọc IP" có hai đường, cả hai đi
qua trợ lý:
- **Hỏi thì trả lời**: công cụ MCP `self.network.get_ip_address` (`mcp/DeviceTools.kt`) trả JSON có
  trường `speak` đã viết sẵn thành lời. `self.get_device_status` cũng có `network.ip`.
- **Tự đọc khi địa chỉ đổi**: `ControlServer.watchAddress()` (luồng `address-watch`, 10 giây một
  lần) so địa chỉ hiện tại với `Settings.lastAnnouncedIp`; khác thì bắn `AddressAnnouncement.flow`.
  `VoiceAssistant.announceAddress()` gửi một text query nhờ trợ lý đọc nguyên văn.
- **Tự đọc sau khi kích hoạt lại** (v1.5.11; bản 1.5.10 thiếu, đo trên loa thật: xoá thiết bị, nhập
  mã mới, loa im vì IP không đổi và app không khởi động lại). Trạng thái kích hoạt trong
  `ServerProvisioner` chỉ mới bằng lần hỏi OTA gần nhất, nên luồng trên hỏi lại OTA **sau mỗi phiên
  nói chuyện** (`AddressAnnouncement.sessions`, tăng trong setter của `isAwake`): loa bị xoá khỏi
  tài khoản chỉ biết điều đó khi có người nói với nó. Thấy mã kích hoạt → hỏi OTA 10 giây một lần
  (`quiet = true` để khỏi ngập Log) → hết mã thì xoá `lastAnnouncedIp` và đọc địa chỉ. Thất bại thì **không chuông** và lùi dần (2, 4, 6 phút, tối đa 3 lần cho mỗi địa chỉ).
  Không đọc khi đang nói chuyện, đang phát nhạc, 22h–7h, hay ở chế độ cài đặt mạng (`192.168.43.1`).
- **Địa chỉ luôn được viết thành chữ trước khi rời loa** (`SpeakerAddress.spoken`: "một chín hai,
  chấm, một sáu tám…"). Đưa "192.168.1.22" cho TTS thì mỗi engine đọc một kiểu.
- Chỗ dễ sai: câu thông báo phụ thuộc LLM chịu nhắc lại nguyên văn; và `awaitServerFrame()` coi
  BẤT KỲ frame nào là "máy chủ đã nhận", kể cả frame MCP ngay sau hello.

### Nhóm loa & SpeakerLink (UDP 8089) — v1.5.15 (màn hình phụ 1.5.16), CHƯA đo trên loa thật

- `link/SpeakerLink.kt` bind UDP **8089** (reuseAddress, broadcast). JSON một datagram/tin:
  `{"t":"find"}` → `{"t":"here",id,name,ip,ver,model,vol,playing,leader}`; `{"t":"sub","name"}` (lease 20s,
  màn hình gửi lại mỗi 8s) → đẩy `{"t":"st",name,ip,ver,vs,awake,emo,mute,vol,user,bot,m:{s,ti,ar,src,pos,dur},wx:{t,c,d,day,city},al:{next,ring},group,tm,tzo}`
  mỗi khi khác (so chuỗi, poll 200ms) + keepalive 3s; `tm`/`tzo` gắn lúc gửi để đồng hồ không tính là thay đổi.
  Phổ: nhị phân `'S','P'` + 32 byte, 66ms, chỉ khi có subscriber; im lặng gửi một khung 0 rồi thôi. `{"t":"bye"}`,
  `{"t":"cmd","c":wake|sleep|mute|unmute|pause|resume|next|stop|vol+|vol-|alarm_stop}` (`mute`/`unmute` = `VoiceGate.paused`, từ 1.5.16). Datagram st có thể >1500 byte
  (user/bot 280 ký tự tiếng Việt) → bên nhận phải ghép mảnh IP (firmware bật `LWIP_IP4_REASSEMBLY`).
- `link/GroupSync.kt` (object, cài vào `LocalMusicPlayer.groupLeader` khi `groupSync && members`):
  `startAt` trên loa chính tạo token → `onTrack` → POST `/api/group/play` song song tới từng loa (follower
  `playForGroup` = prepare rồi **giữ**), chờ chính nó `isReady` + các follower trả lời (≤9s), rồi gửi `/go`
  `delay=400` và `goHeld` chính nó. Follower không nghe "go" thì tự chạy sau `HOLD_MAX_MS` 12s.
  URL podcast `http://127.0.0.1:` đổi sang IP loa chính (relay chỉ loa đó có token); radio để nguyên (mỗi loa tự relay).
  pause/resume/stop/seek/hết bài đi qua `leaderCommand` → `/api/group/cmd`. Follower đổi bài tự tay (`play()`) là rời nhóm (`groupFollower=false`).
- Độ lệch còn lại: mạng vài ms + độ trễ khởi động MediaPlayer mỗi máy (vài chục ms) — đủ cho nhiều phòng, không đủ cho cặp stereo.
- Màn hình phụ: `display-esp32s3/` (ESP-IDF 5.3, LVGL 9.2.2, font Be Vietnam Pro chuyển bằng `lv_font_conv` vào
  `main/fonts/`). Build ở sandbox: registry ESP bị chặn → LVGL/esp_lcd_ili9341 vendored trong `components/` (không
  commit, `.gitignore`), build với `IDF_COMPONENT_MANAGER=0`; LVGL cần thư mục rỗng `examples/` `demos/`. Có preview
  desktop của `ui.c` (stub link/net, render PPM) và đã boot thử trên QEMU esp32s3 không LCD (bắt được lỗi link_state
  trước link_init). QEMU không giả lập SPI LCD → `display_init` thật treo ở đó; đó là giới hạn QEMU, không phải lỗi.
  **1.5.16**: `ui.c` viết lại — hai bảng màu `pal_t` (tối đỏ / sáng xanh dbrobot.vn, như control.html) chọn bằng `g_cfg.light`,
  đổi giao diện = dựng lại cả màn hình (`request_rebuild` → `lv_async_call`, vì widget đang bắn sự kiện không tự xoá được mình);
  `lv_tileview` hai ô (trang chính | menu Ứng dụng) + "sheet" cài đặt phủ lên; tự về trang chính sau 45 giây không chạm
  hoặc khi có người nói. Nút "Tắt nghe" ở trang trò chuyện gửi `sleep` rồi `mute`, khoá chat (`chat_locked`) đến khi loa im 1.5 s (tối đa 20 s, lặp `sleep` mỗi 2 s). Hình bài hát (1.5.19): `st.m.vid` + `st.mu` → màn hình tải `/video_mjpeg` của máy chủ nhạc (`video.c`). Menu Ứng dụng: Màn hình / Âm lượng / Báo thức / Radio / Podcast / Nhạc / Gọi loa / Tìm loa / Thông tin; Radio, Podcast, Nhạc, Báo thức đi qua `req`/`play`/`al` trong `SpeakerLink.handleApi` (worker thread, HTTP loopback tới ControlServer + AlarmScheduler).  Màn ST7789: panel giữ 240×320 dọc đúng thông
  số hãng (`board.h`), `display.c` xoay hình ngang bằng `lv_display_set_rotation` + `lv_draw_sw_rotate` (270 = chiều cũ của
  swap+mirror_x, nên calibration đã lưu vẫn khớp; "Xoay 180°" = 90). ⚠️ LVGL 9.2 muốn điểm chạm ở khung DỌC của panel
  rồi tự xoay — `touch_cb` đổi ngược từ toạ độ ngang đã hiệu chỉnh. `cfg_t.light` thêm ở cuối; blob NVS cũ ngắn hơn vẫn đọc được.
  Mô phỏng trên máy (`sim`, stub link/net/display, cùng đường flush xoay + chạm giả) dựng đủ các trang sáng/tối.

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
