# Ghi chú phát hành DB-Robot R1

Mỗi bản một mục `## v<versionName>`. Khi phát hành, đoạn văn dưới mục đó được đưa vào trang Release
và vào ô "Bản mới nhất" trong trang điều khiển của loa -- viết ngắn, cho người dùng đọc.

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
