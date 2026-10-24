# Ghi chú phát hành DB-Robot R1

Mỗi bản một mục `## v<versionName>`. Khi phát hành, đoạn văn dưới mục đó được đưa vào trang Release
và vào ô "Bản mới nhất" trong trang điều khiển của loa -- viết ngắn, cho người dùng đọc.

## v1.5.34

- Màn hình phụ có thêm ứng dụng "Nhà" trong menu Ứng dụng: danh sách thiết bị Home Assistant đã chọn trên trang loa, mỗi thiết bị một công tắc bật/tắt theo đúng trạng thái; chạm để bật/tắt, cảm biến hiện giá trị (nhiệt độ, độ ẩm…). Menu Ứng dụng xếp 9 ô: 5 + 4 khi nằm ngang, 3 × 3 khi dựng dọc. Cần cập nhật cả ứng dụng trên loa và firmware màn hình.

## v1.5.33

- Dùng trợ lý của chính Home Assistant: thẻ Home Assistant có thêm Agent ID (ví dụ `conversation.google_ai_conversation`) và Assist (nút Quét Assist lấy danh sách, nút Thử Assist gửi câu thử). Khi trò chuyện qua máy chủ DB-Robot, lệnh nhà thông minh được chuyển nguyên câu cho agent đó xử lý.
- Thẻ Server có thêm lựa chọn "Home Assistant": loa dùng thẳng Assist của Home Assistant làm máy chủ (nhận giọng nói, trả lời, điều khiển nhà, đọc câu trả lời), không cần máy chủ xiaozhi. Assist cần có STT và TTS tiếng Việt. Bấm DB-Robot hoặc Xiaozhi để quay lại.

## v1.5.32

- Home Assistant từ xa: thêm ô "Từ xa" cho địa chỉ Internet của Home Assistant (Nabu Casa `https://xxxx.ui.nabu.casa`, DuckDNS, Cloudflare Tunnel). Loa dùng địa chỉ trong nhà trước, không nối được thì tự chuyển sang địa chỉ Internet, nên vẫn điều khiển được nhà thông minh khi loa đặt ở nơi khác. HTTPS được kiểm bằng danh sách chứng chỉ gốc mới của ứng dụng (Let's Encrypt dùng được). Nút Kiểm tra kiểm cả hai địa chỉ. Loa không còn gửi cấu hình Home Assistant cho máy chủ trò chuyện.

## v1.5.31

- Home Assistant: loa tự điều khiển nhà thông minh trong mạng nhà bằng token truy cập dài hạn, dùng được cả địa chỉ `http://homeassistant.local:8123` (loa tự tìm qua mDNS) và IP. Chọn thiết bị, đặt tên phòng và tên gọi rồi nói "bật đèn phòng khách", "tắt tất cả đèn", "đặt điều hoà 26 độ", "mở rèm", "phòng ngủ đang bao nhiêu độ". Trang loa có nút Kiểm tra (hiện phiên bản và tên nhà), hướng dẫn lấy token, và mục Điều khiển thử bật/tắt từng thiết bị. Token của Home Assistant trong mạng nhà không còn được gửi cho máy chủ.

## v1.5.30

- Cảm xúc của trợ lý (emoji trên màn hình phụ) giờ đổi theo từng câu trả lời: loa đọc trường `emotion` máy chủ gửi kèm (như firmware xiaozhi), nếu máy chủ chỉ gửi emoji thì đọc emoji, còn nếu máy chủ không gửi gì thì loa tự đoán từ câu trả lời (emoji trong câu, hoặc các từ như "haha", "xin lỗi", "chúc mừng"…). Mặt suy nghĩ hiện sau khi bạn nói xong, mặt trung tính khi bắt đầu phiên. Nhật ký của loa ghi lần đầu nhận cảm xúc từ máy chủ để kiểm tra. Cần cập nhật ứng dụng trên loa (màn hình phụ giữ bản 1.5.29).

## v1.5.29

- Màn hình phụ, trang trò chuyện: khuôn mặt vẽ tay được thay bằng bộ biểu cảm emoji mặc định của xiaozhi (21 mặt Twemoji: vui, cười, buồn, giận, khóc, yêu, ngạc nhiên, suy nghĩ, buồn ngủ…), hiện theo cảm xúc máy chủ gửi kèm câu trả lời. Viền xanh nhấp nháy khi loa đang nghe, emoji nhún nhẹ khi loa đang nói.

## v1.5.28

- Màn hình phụ xoay dọc: đồng hồ lật nằm một hàng, tự co theo chiều ngang màn hình; thời tiết xếp thành cột giữa màn hình (biểu tượng + nhiệt độ, rồi mô tả và thành phố) nên không còn bị tràn mất biểu tượng.

## v1.5.27

- Màn hình phụ: xoay đủ 4 hướng (Cài đặt › "Xoay 90°": ngang → dọc → ngang ngược → dọc ngược, mỗi lần chạm xoay thêm 90°). Các trang (đồng hồ, nhạc, trò chuyện, menu, cài đặt, báo thức, Wi-Fi…) tự sắp xếp lại cho màn dọc 240×320.
- Sửa lỗi chạm bị ngược khi xoay: hiệu chỉnh cảm ứng giờ luôn làm ở một hướng chuẩn rồi tự quy đổi cho mọi hướng, nên xoay thế nào ngón tay vẫn đúng chỗ. Nếu chạm vẫn lệch, vào Cài đặt › "Hiệu chỉnh cảm ứng" làm lại một lần.
- Sửa nút "Hình" trên trang nhạc đôi khi đổi chữ thành "Tắt nghe" / "Gọi loa".

## v1.5.26

- Nút "Hình" qua HTTPS (ms.dbrobot.vn): sửa lỗi luồng đứt sau vài giây do mã hoá AES bằng phần cứng thiếu bộ nhớ trong (`esp-aes: Failed to allocate memory`). Màn hình giờ giải mã TLS bằng phần mềm, chậm hơn chút nhưng ổn định. Cần xoá file `sdkconfig` cũ trước khi build lại firmware màn hình.

## v1.5.25

- Nút "Hình": nếu luồng hình đứt giữa chừng (hay gặp qua HTTPS/proxy), màn hình tự nối lại từ đúng giây đang xem (tối đa 8 lần), giữ nguyên khung hình cuối trong lúc nối; chỉ báo lỗi khi luồng chưa bao giờ chạy. Nhật ký serial ghi `read -1 errno … after … bytes` để tìm nguyên nhân đứt.

## v1.5.24

- Nút "Hình" qua HTTPS (ms.dbrobot.vn): TLS đã nối được; màn hình giờ chờ tối đa ~60 giây nếu máy chủ/proxy chưa gửi khung đầu tiên (trước đó chỉ chờ 10 giây rồi báo "Mất kết nối"), và thông báo lỗi ghi rõ mã đọc. Máy chủ nhạc thêm tiêu đề `X-Accel-Buffering: no` để nginx không giữ đệm luồng hình. Nếu dùng Nginx Proxy Manager, thêm `proxy_buffering off;` cho máy chủ ms.dbrobot.vn.

## v1.5.23

- Nút "Hình" qua HTTPS: khi bắt tay TLS thất bại, màn hình hiện mã lỗi mbedTLS và cờ chứng chỉ (hết hạn / không tin cậy / sai tên) để biết lý do.

## v1.5.22

- Nút "Hình": khi không nối được máy chủ nhạc, màn hình hiện rõ tên máy chủ và mã lỗi (để tìm nguyên nhân: DNS, TLS/chứng chỉ, hay máy chủ không mở cổng).

## v1.5.21

- **Sửa nút "Hình" báo "Hết bộ nhớ"**: bảng giải mã JPEG xin 8 KB RAM trong liền khối, nhưng RAM trong của ESP32-S3 đang bị chia nhỏ; nay thử RAM trong rồi chuyển sang PSRAM, các bộ đệm được cấp một lần và giữ lại. Thông báo lỗi cho biết bộ đệm nào thiếu và còn bao nhiêu KB.

## v1.5.20

- **Sửa nút "Hình" báo "Không tạo được luồng"**: luồng tải hình dùng một tác vụ lâu dài có ngăn xếp nằm trong PSRAM (trước đó cần 10 KB RAM trong, đang thiếu), bộ đệm TLS cũng chuyển sang PSRAM. Cần **xoá file `sdkconfig` cũ rồi build lại** để nhận `CONFIG_MBEDTLS_EXTERNAL_MEM_ALLOC`.

## v1.5.19

- **Màn hình phụ xem hình bài hát**: khi loa phát nhạc từ máy chủ nhạc (YouTube), trang nhạc có nút **Hình**; bấm vào để xem video nhỏ (320×240, ~10 khung/giây, không tiếng — tiếng vẫn phát ở loa), chạm màn hình để đóng. Hình tự đóng khi đổi bài.
- Máy chủ nhạc (`youtube-music/app.py`) có thêm `/video_mjpeg?id=&ss=` và `/cover?id=`; **phải cập nhật và dựng lại container máy chủ nhạc**. Loa 1.5.19 báo mã video và địa chỉ máy chủ cho màn hình trong gói trạng thái.

## v1.5.18

- **Sửa màn hình phụ không nhận được Radio, Podcast, Nhạc, Báo thức từ loa**: loa trả lời bằng gói UDP nhỏ gọn (dưới ~1,2 KB, ESP32 bỏ gói lớn hơn), bắt cả lỗi nặng để luôn có phản hồi và ghi vào Nhật ký ("Màn hình phụ hỏi: …"). Cần cài APK 1.5.18 lên loa cùng firmware màn hình 1.5.18.

## v1.5.17

- **Màn hình phụ — menu Ứng dụng** (vuốt ngang để mở) có 8 ứng dụng: **Tìm loa**, **Wifi** (quét và nối sang Wi-Fi khác, nhập mật khẩu bằng bàn phím trên màn hình), **Báo thức** (thêm, bật/tắt, sửa giờ và ngày lặp, xoá), **Radio**, **Nhạc** (gõ tên bài hát, loa tìm và phát), **Podcast** (chọn chương trình rồi chọn tập), **Cài đặt** (âm lượng loa, độ sáng, giao diện sáng/tối, xoay, đảo màu, giảm sáng ban đêm, chỉnh cảm ứng) và **Thông tin**. Radio, Nhạc, Podcast và Báo thức cần loa 1.5.17.
- **Màn hình phụ — đồng hồ lật (flip)** ở trang chính, hai nút Tắt nghe / Gọi loa nhỏ gọn hơn, quang phổ nhạc nhiều màu cầu vồng.
- **Màn hình phụ — Tắt nghe** khoá màn hình trò chuyện cho đến khi loa im hẳn, không bật lại giao diện chat giữa chừng.
- **Trang cài đặt Wi-Fi của màn hình (192.168.4.1)** hiện danh sách mạng Wi-Fi để chạm chọn (kèm ổ khoá và vạch sóng), có nút "Dò lại"; trước đây phải gõ tay tên mạng.

## v1.5.16

- **Màn hình phụ — giao diện sáng/tối** giống trang điều khiển của loa: nền tối đỏ hoặc nền sáng xanh dbrobot.vn, đổi bằng nút mặt trời/trăng ở góc trên bên phải; màn hình nhớ lựa chọn.
- **Màn hình phụ — menu Ứng dụng**: vuốt ngang như điện thoại để mở, chạm biểu tượng để chỉnh độ sáng, âm lượng loa, gọi loa, chọn loa khác, cài đặt màn hình, xem thông tin và chỉnh lại cảm ứng.
- **Màn hình phụ — nút "Tắt nghe"** ngay trong màn hình trò chuyện: bấm để kết thúc cuộc nói chuyện, quay về màn hình chính và loa thôi nghe từ đánh thức (màn hình chính có nút **Bật nghe**). Cần loa 1.5.16 để tắt/bật nghe; bản cũ chỉ kết thúc cuộc nói chuyện.
- Màn hình phụ dùng đúng thông số của màn ST7789 2.8" (240×320, đảo màu, RGB) để màu hiển thị chuẩn.

## v1.5.15

- **Nhóm loa — phát cùng lúc trên nhiều loa**: thẻ mới **Nhóm loa & màn hình phụ** (tab System). Bấm **Tìm loa trong mạng**, đánh dấu các loa DB-Robot khác rồi bật **Phát cùng các loa trong nhóm**: bài hát, radio, podcast phát ở loa này sẽ phát đồng thời trên các loa đã chọn; tạm dừng, tiếp tục, bài kế, dừng, tua cũng đi theo. Chỉnh âm lượng từng loa ngay trong thẻ. Mọi loa trong nhóm cần chạy bản 1.5.15.
- **Đặt tên cho loa** (ví dụ "Phòng khách") để dễ nhận ra khi có nhiều loa.
- **Màn hình phụ ESP32-S3**: gắn màn 2.8" cảm ứng vào mạch ESP32-S3 để thấy loa đang làm gì — lời trò chuyện, khuôn mặt cảm xúc, bài đang phát kèm phổ âm thanh, đồng hồ, thời tiết, báo thức — và bấm nút tạm dừng, bài kế, âm lượng, tắt báo thức. Firmware `DBR1-Display-ESP32S3.bin` có trong bản phát hành này; cách nối dây và nạp xem thư mục `display-esp32s3` trên GitHub.

## v1.5.14

- **Báo thức** (tab System): đặt nhiều báo thức, chọn ngày lặp lại (hoặc báo một lần), âm lượng báo, tiếng mặc định hoặc tệp âm thanh tự tải lên (MP3, M4A, OGG, WAV…). Bấm nút trên đỉnh loa hoặc nút "Tắt báo thức" trên trang điều khiển để tắt; tự tắt sau 10 phút.
- Hàng Podcast xuống dòng gọn trong khung, không còn bị cắt; Thời sự VOV, VnExpress, VietNamNet lên đầu.

## v1.5.13

- **Máy chủ nhạc DB-Robot chuyển sang nguồn YouTube**: lệnh "mở bài …", ô tìm nhạc và YouTube Music cùng lấy nhạc từ YouTube.
- **YouTube Music phát ra loa**: bấm Tìm là loa phát ngay bài khớp nhất, như khi ra lệnh bằng giọng nói; các kết quả khác hiện bên dưới để chọn và phát tiếp. Nút màn hình để xem video ngay trên điện thoại.
- **Podcast tin tức trong nước**: bỏ "Thời sự RFI", thêm **Thời sự VOV**, **VnExpress Hôm nay** và **Podcast VietNamNet**.

## v1.5.12

- **YouTube Music trong tab Media**: tìm bài hát, video trên YouTube hoặc dán link video, rồi xem và nghe ngay trên trang điều khiển. Không cần máy chủ nhạc. Video phát trên điện thoại hoặc máy tính đang mở trang, không phát ra loa.
- **Podcast**: hàng Podcast dưới Radio có 13 chương trình miễn phí (Have A Sip, Giang ơi Radio, Tri Kỷ Cảm Xúc, Đọc truyện đêm khuya, Thời sự RFI, 6 Minute English…). Chạm là loa phát tập mới nhất, kèm danh sách các tập khác. Nút "Tìm podcast" tìm thêm chương trình trong danh bạ Apple Podcasts.
- Thẻ Server trong tab System có thêm link "Hướng dẫn kết nối máy chủ DB-Robot".
- Trên điện thoại, cả tab Media cuộn được, danh sách bài không còn bị ép nhỏ.

## v1.5.11

- Loa tự đọc địa chỉ IP ngay sau khi được kích hoạt lại trên máy chủ (xoá thiết bị rồi thêm lại), kể cả khi địa chỉ không đổi. Bản trước chỉ đọc khi địa chỉ thay đổi.
- Mã kích hoạt trên trang điều khiển tự cập nhật, không cần khởi động lại ứng dụng.

## v1.5.10

- **Hỏi loa địa chỉ IP**: nói "địa chỉ IP của bạn là gì?" — loa đọc từng số của địa chỉ và cách mở trang điều khiển.
- **Loa tự đọc địa chỉ IP khi địa chỉ thay đổi** (đổi mạng, bộ phát cấp địa chỉ khác, hoặc sau khi kích hoạt lại với máy chủ), để bạn không bị mất đường vào trang điều khiển. Không đọc trong khoảng 22 giờ đến 7 giờ; tắt được trong thẻ Wi-Fi.

## v1.5.9

- Giao diện trên điện thoại: đầu trang xếp lại thành hai hàng (logo, tên và số phiên bản ở trên; trạng thái và nút đổi giao diện ở dưới).
- Sửa các thẻ bị tràn ra ngoài khung trên màn hình hẹp: Âm thanh, Nguồn mic, Đèn LED, AI Model. Ô chọn tự xuống dòng riêng khi không đủ chỗ.

## v1.5.8

- **Nền động cho khung Chat**: 6 nền hologram do trang tự vẽ (Lõi AI, Robot, Địa cầu, Sóng âm, Lưới neon, Mưa số), chuyển động theo lúc loa nghe và trả lời. Bấm nút hình ảnh ở đầu khung Chat để chọn.
- Ảnh nền từ máy nay nhận cả **GIF và WebP động** (tối đa 8 MB), và có thêm ô **dán link ảnh** trên Internet.
- Phổ nhạc trong tab Media cao hơn và mịn hơn: 64 cột thay cho 40.

## v1.5.7

- **Cài được cho loa mới chưa có Wi-Fi**: gõ `M` khi bộ cài hỏi địa chỉ loa. Bộ cài hướng dẫn giữ nút trên đỉnh loa 5 giây để loa phát mạng "Phicomm R1", cài qua `192.168.43.1`, rồi hỏi tên và mật khẩu Wi-Fi nhà để đưa loa vào mạng.
- **Đổi Wi-Fi khi loa mất mạng**: giữ nút trên đỉnh loa 5 giây, nối điện thoại vào mạng "Phicomm R1", mở `http://192.168.43.1:8088` → tab System → Wi-Fi và chọn mạng nhà. Sai mật khẩu thì loa phát lại mạng "Phicomm R1" để bạn nhập lại.
- Sau khi loa vào mạng nhà, trang điều khiển tự tìm và hiện địa chỉ mới của loa.

## v1.5.6

- Thẻ **Wi-Fi** mới: xem loa đang dùng mạng nào, quét và chuyển loa sang mạng khác. Nếu không vào được mạng mới, loa tự quay lại mạng cũ.
- Khung Chat có **ảnh nền**: chọn một ảnh của bạn, ảnh được lưu trên loa nên thiết bị nào mở trang cũng thấy.
- Tab "Setup" đổi tên thành **System**.
- Bộ cài cho Windows nay là một tệp `DB-Robot-R1-Setup.exe`: tải về, bấm đúp là cài. Bản zip vẫn có để dự phòng.
- Thông tin liên hệ và các kênh của DB-ROBOT ở cuối trang điều khiển.

## v1.5.5

- Radio phát được trở lại: 8 kênh VOV lấy từ nguồn phát mới của đài.
- Nút **Tắt nghe** và **Gọi loa** ở góc dưới bên trái trang điều khiển: tắt từ đánh thức khi không muốn loa xen vào, và gọi loa bằng tay khi cần.
- Tuỳ chọn "Tắt khi phát nhạc" (Setup → Wake word): loa không nghe từ đánh thức trong lúc phát nhạc hoặc radio.
- Cập nhật ổn định hơn: bộ cài tự ngắt kết nối với loa khi xong; nếu loa đang bị một máy tính giữ qua adb, trang điều khiển nói rõ cách xử lý thay vì đứng ở "Đang cài đặt".

## v1.5.4

- Đèn LED: chọn hiệu ứng theo tên cho từng trạng thái thay vì nhập mã; đèn đổi ngay để xem thử.

## v1.5.3

- Sửa lỗi không thấy bản cập nhật: loa nay tự mang danh sách chứng chỉ mới nên kết nối được tới GitHub.
- Điều khiển bằng giọng nói: "mở bài …", "tăng/giảm âm lượng", "mở VOV1", "dừng nhạc".
- Radio: 14 kênh VOV, chọn bằng giọng nói hoặc ngay trong tab Media.
- Sửa tên loa thành Phicomm R1.

## v1.5.2

- Thẻ Vị trí & thời tiết: nhập thành phố của bạn, loa biết múi giờ và thời tiết tại đó.
- Hỏi "mấy giờ rồi" hay "thời tiết hôm nay thế nào", trợ lý lấy câu trả lời từ loa (cần máy chủ hỗ trợ MCP).

## v1.5.1

- Giao diện sáng màu xanh, đồng bộ với trang dbrobot.vn.
- Mở lại thẻ AI Model: dùng model và API key của riêng bạn (OpenAI, Gemini, OpenRouter, DeepSeek, Groq…).
- Tiêu đề mới "DB-Robot Phicomm R1"; phần Chi tiết của từng mục viết lại ngắn gọn, dễ hiểu.

## v1.5.0

- Tự cập nhật qua mạng: loa tự kiểm tra bản mới; cập nhật bằng một nút bấm trong tab Setup, hoặc bật "Tự động cập nhật".
- Bộ cài cho Windows, điện thoại Android (Termux), macOS và Linux -- tự tìm loa trong mạng Wi-Fi.

## v1.4.0

- Tab Media có trình phát nhạc, quang phổ và nút âm lượng.
