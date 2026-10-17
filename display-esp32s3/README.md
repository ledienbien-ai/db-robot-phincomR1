# Màn hình phụ DB-Robot (ESP32-S3)

Loa Phicomm R1 không có màn hình. Màn hình phụ là một mạch **ESP32-S3-DevKitC-1 N16R8** gắn màn
**2.8" SPI TFT 240×320 có cảm ứng** (chip ST7789 hoặc ILI9341, cảm ứng XPT2046). Nó vào cùng mạng
Wi-Fi với loa, tự tìm loa và hiển thị:

- **Trò chuyện**: khuôn mặt theo trạng thái (đang nghe / đang nói) và cảm xúc của trợ lý, câu bạn
  vừa nói và câu trả lời của loa. Nút **Tắt nghe** kết thúc cuộc nói chuyện, quay về màn hình chính
  và loa thôi nghe từ đánh thức.
- **Nhạc**: tên bài, ca sĩ, nguồn (Nhạc / YouTube / Radio / Podcast), phổ âm thanh 32 cột, thời gian;
  nút giảm âm, tạm dừng/tiếp, bài kế, dừng, tăng âm.
- **Lúc rảnh**: đồng hồ lớn, thứ ngày, thời tiết, báo thức sắp tới, nút **Tắt nghe / Bật nghe** và **Gọi loa**.
- **Báo thức đang reo**: toàn màn hình, nút **TẮT BÁO THỨC**.
- Thanh trên cùng: tên loa, số loa trong nhóm, âm lượng, giờ, và nút **mặt trời / mặt trăng** để
  đổi giao diện.
- **Giao diện sáng / tối** giống trang điều khiển của loa (tối đỏ, hoặc sáng xanh dbrobot.vn); màn
  hình nhớ lựa chọn sau khi tắt nguồn.
- **Đồng hồ lật (flip)** ở trang chính: bốn thẻ số, số nào đổi thì lật xuống như đồng hồ lật thật.
- **Menu Ứng dụng**: **vuốt ngang** màn hình chính để mở, như màn hình điện thoại. 8 ứng dụng:
  *Tìm loa* (chọn loa khác), *Wifi* (quét, chọn mạng, nhập mật khẩu rồi màn hình khởi động lại để
  vào mạng mới; sai mật khẩu thì sau chừng 25 giây màn hình phát Wi-Fi `DBR1-Display-xxxx` để cài
  lại), *Báo thức* (danh sách, bật/tắt, thêm, sửa giờ + ngày lặp, xoá), *Radio*, *Nhạc* (gõ tên
  bài hát không dấu, loa tìm và phát bài khớp nhất), *Podcast* (chương trình → tập), *Cài đặt*
  (âm lượng loa, độ sáng, giao diện sáng/tối, xoay 180°, đảo màu, giảm sáng ban đêm, chỉnh lại cảm
  ứng) và *Thông tin* (loa, Wi-Fi, IP, khởi động lại). Không chạm 45 giây, hoặc khi có người nói
  chuyện với loa, màn hình tự quay về trang chính.

Cần loa chạy DB-Robot **1.5.15** trở lên (**1.5.16** để dùng nút Tắt nghe / Bật nghe; **1.5.18** để dùng Radio, Podcast, Nhạc và Báo thức). Một loa có thể có nhiều màn hình; trong trang điều khiển
của loa, thẻ **Nhóm loa & màn hình phụ** (tab System) liệt kê các màn hình đang nối.

## Nối dây

Màn hình và cảm ứng dùng chung một đường SPI.

| Chân màn hình | ESP32-S3 | Ghi chú |
|---|---|---|
| VCC | 3V3 | |
| GND | GND | |
| CS | GPIO 10 | |
| RESET | GPIO 14 | |
| DC | GPIO 9 | |
| SDI (MOSI) | GPIO 11 | |
| SCK | GPIO 12 | |
| LED | GPIO 21 | đèn nền, chỉnh độ sáng được |
| SDO (MISO) | *để trống* | nối vào thì nhiều màn làm hỏng tín hiệu cảm ứng |
| T_CLK | GPIO 12 | chung với SCK |
| T_CS | GPIO 1 | |
| T_DIN | GPIO 11 | chung với SDI |
| T_DO | GPIO 13 | |
| T_IRQ | GPIO 2 | không bắt buộc |

Không dùng GPIO 35, 36, 37: trên bản N16R8 các chân này đã nối vào PSRAM.

Nếu màn hình quá tối, nối chân LED thẳng vào 3V3 (khi đó độ sáng không chỉnh được nữa).

## Nạp firmware

Tệp `firmware/DBR1-Display-ESP32S3.bin` là bản đã gộp sẵn, nạp **một lần ở địa chỉ 0x0**. Tệp này
cũng có trong mỗi bản phát hành trên GitHub.

**Cách dễ nhất, bằng trình duyệt** (Chrome hoặc Edge trên máy tính):

1. Cắm cáp USB vào cổng **UART** (hoặc cổng USB) của mạch.
2. Mở <https://espressif.github.io/esptool-js/> → **Connect** → chọn cổng của mạch.
   Nếu không kết nối được: giữ nút **BOOT**, bấm **RST**, nhả **BOOT**, rồi bấm Connect lại.
3. Ở dòng Flash Address để **0x0**, chọn tệp `DBR1-Display-ESP32S3.bin` → **Program**.
4. Xong thì bấm **RST** trên mạch.

**Bằng esptool** (Windows / macOS / Linux, cần Python):

```
pip install esptool
esptool.py --chip esp32s3 -b 921600 write_flash 0x0 DBR1-Display-ESP32S3.bin
```

## Cài đặt lần đầu

1. **Hiệu chỉnh cảm ứng**: lần đầu khởi động, màn hình hiện lần lượt 3 dấu **+**; chạm vào giữa
   từng dấu. Không chạm gì thì sau 40 giây tự bỏ qua.
2. **Wi-Fi**: màn hình phát Wi-Fi **DBR1-Display-XXXX** (không mật khẩu). Dùng điện thoại vào mạng
   đó; trang cài đặt thường tự mở, nếu không thì mở `http://192.168.4.1`. Chọn Wi-Fi nhà bạn,
   nhập mật khẩu, bấm **Lưu & khởi động lại**. Wi-Fi phải là mạng 2.4 GHz và là mạng loa đang dùng.
3. Màn hình tự tìm loa. Có một loa thì tự nối; có nhiều loa thì màn hình hiện danh sách để chạm chọn.
   **Giữ ngón tay lên thanh trên cùng** khoảng 1 giây để chọn lại loa khác.

Khi đã vào Wi-Fi nhà, trang cài đặt vẫn mở được ở địa chỉ IP của màn hình (hiện trên màn hình khi
đang tìm loa, hoặc xem trong router):

- Tên màn hình, loa cần theo, **IP loa cố định** (khi router chặn gói tin tìm kiếm).
- **Chip màn hình** ST7789 / ILI9341, **Đảo màu**, **Đổi đỏ ↔ xanh dương**, **Xoay 180°**,
  **Lật ngang**, **Độ sáng**, **Giảm sáng ban đêm** (22:00–6:00).
- **Hiệu chỉnh cảm ứng** lại.

Nếu Wi-Fi đã lưu không vào được trong khoảng 25 giây, màn hình mở lại Wi-Fi cài đặt
DBR1-Display-XXXX (vẫn thử Wi-Fi cũ ở nền) để bạn sửa.

## Khi hình hiển thị không đúng

| Hiện tượng | Sửa |
|---|---|
| Màu bị âm bản (nền trắng, chữ đen) | bật/tắt **Đảo màu** |
| Màu xanh dương thành đỏ | bật **Đổi đỏ ↔ xanh dương** |
| Hình bị lộn ngược | bật **Xoay 180°** |
| Chữ bị ngược như trong gương | bật **Lật ngang** |
| Hình nhiễu, lệch, chỉ một phần màn hình | thử chip **ILI9341** (nhiều màn 2.8" bán là ST7789 nhưng thật ra là ILI9341) |
| Chạm sai chỗ | **Hiệu chỉnh cảm ứng** |
| Chạm không ăn | kiểm tra T_CS, T_DO, T_DIN, T_CLK; để trống chân SDO(MISO) của màn hình |
| "Đang tìm loa…" mãi | loa phải chạy bản 1.5.15+, chung mạng; nhập **IP loa cố định** |

Màn hình chưa vào được Wi-Fi thì không mở được trang cài đặt qua mạng nhà: cài lại từ Wi-Fi
DBR1-Display-XXXX. Muốn xoá hết cài đặt, nạp lại firmware với lệnh `esptool.py erase_flash` trước.

## Cách hoạt động

Màn hình nói chuyện với loa qua UDP cổng **8089** (`link/SpeakerLink.kt` trong ứng dụng):

- Gửi broadcast `{"t":"find"}` → loa trả `{"t":"here","id","name","ip","ver",…}`.
- Gửi `{"t":"sub","name":"…"}` mỗi 8 giây → loa đẩy trạng thái `{"t":"st",…}` mỗi khi có thay đổi
  (và 3 giây một lần), kèm giờ của loa, nên màn hình không cần Internet để biết giờ.
- Trong lúc phát nhạc, loa gửi phổ âm thanh 15 lần mỗi giây: 2 byte `SP` + 32 byte.
- Nút trên màn hình gửi `{"t":"cmd","c":"pause|resume|next|stop|vol+|vol-|wake|sleep|mute|unmute|alarm_stop"}`.
  `mute` / `unmute` (1.5.16) là công tắc **Tắt nghe** của trang điều khiển: loa bỏ qua / nghe lại từ đánh thức.
- Menu Ứng dụng (1.5.17), loa trả lời cho đúng máy hỏi: `{"t":"req","k":"radio"|"podcast"|"alarms"}`,
  `{"t":"req","k":"episodes","id":<chương trình>}` → `{"t":"list","k","id","items":[{id,n}]}` /
  `{"t":"alarms","alarms":[{id,on,time,days,label}],"next"}`; `{"t":"play","k":"radio","id"}`,
  `{"k":"episode","n"}`, `{"k":"music","q"}` → `{"t":"res","ok","msg"}`;
  `{"t":"al","op":"toggle"|"save"|"del",...}` → `alarms`. Loa gọi cùng API của trang điều khiển
  (127.0.0.1:8088), không có đường tắt riêng.

## Build từ mã nguồn

Cần ESP-IDF **5.3** trở lên.

```
cd display-esp32s3
idf.py set-target esp32s3
idf.py build
idf.py -p COM5 flash monitor
```

Màn 2.8" ST7789 dùng đúng thông số của nhà sản xuất, ở `main/board.h`: 240×320 dọc, không lật, không
đổi trục, đảo màu, thứ tự RGB, không lệch, SPI mode 0, đèn nền không đảo. Hình ngang 320×240 do LVGL
xoay bằng phần mềm (`display.c`).

Lần build đầu, ESP-IDF tự tải LVGL 9.2.2 và driver ILI9341 (xem `main/idf_component.yml`). Font
tiếng Việt (Be Vietnam Pro, kèm biểu tượng Font Awesome) đã được chuyển sẵn thành mã C trong
`main/fonts/` bằng `lv_font_conv`.

Tạo tệp gộp để nạp ở 0x0:

```
cd build
esptool.py --chip esp32s3 merge_bin -o DBR1-Display-ESP32S3.bin --flash_mode dio --flash_freq 80m --flash_size 16MB 0x0 bootloader/bootloader.bin 0x8000 partition_table/partition-table.bin 0x10000 dbr1-display.bin
```

| Tệp | Nội dung |
|---|---|
| `main/board.h` | chân GPIO, tốc độ SPI |
| `main/display.c` | màn hình (ST7789/ILI9341), đèn nền, LVGL; xoay hình bằng phần mềm |
| `main/touch.c` | cảm ứng XPT2046 |
| `main/net.c` | Wi-Fi, Wi-Fi cài đặt, DNS cho trang cài đặt tự mở |
| `main/portal.c` | trang cài đặt |
| `main/link.c` | tìm loa và nhận trạng thái qua UDP 8089 |
| `main/ui.c` | các màn hình, giao diện sáng/tối, menu Ứng dụng |
| `main/cfg.c` | cài đặt lưu trong NVS |

## Hình bài hát (1.5.19)

Gói `st` của loa có thêm `m.vid` (mã video 11 ký tự) và `mu` (địa chỉ máy chủ nhạc) khi bài đang phát đến từ máy chủ nhạc. Màn hình (`video.c`) tải
`<mu>/video_mjpeg?id=<vid>&ss=<giây>` — multipart/x-mixed-replace các ảnh JPEG baseline 4:2:0 320×240 — bằng `esp_http_client` (HTTPS dùng bộ chứng chỉ có sẵn),
giải mã bằng TJpgDec trong ROM của ESP32-S3 ra RGB565 (3 bộ đệm trong PSRAM). Nút "Hình" ở trang nhạc mở lớp phủ toàn màn hình. Nếu `main/CMakeLists.txt` có `REQUIRES`,
thêm `esp_http_client esp-tls mbedtls`.
