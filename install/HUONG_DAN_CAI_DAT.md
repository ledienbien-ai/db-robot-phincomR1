## Cài DB-Robot vào loa Phicomm R1

**Cần có:** loa R1 đang bật và đã vào Wi-Fi nhà bạn; một máy tính hoặc điện thoại Android **cùng mạng Wi-Fi** với loa. Chỉ phải cài bằng cách này **một lần** — các bản sau cập nhật ngay trên trang điều khiển của loa.

### Máy tính Windows

1. Tải [DB-Robot-R1-Windows.zip](https://github.com/ledienbien-ai/db-robot-phincomR1/releases/latest/download/DB-Robot-R1-Windows.zip) rồi giải nén ra một thư mục.
2. Bấm đúp **Cai-dat-DB-Robot.bat**. Nếu Windows hiện "Windows protected your PC", bấm *More info* → *Run anyway*.
3. Bấm Enter để bộ cài tự tìm loa trong mạng (hoặc gõ địa chỉ IP của loa), rồi làm theo các câu hỏi trên màn hình. Cài mất khoảng 2–3 phút.

Không muốn tải tệp zip thì mở **PowerShell** và dán dòng này:

```
irm https://github.com/ledienbien-ai/db-robot-phincomR1/releases/latest/download/install.ps1 | iex
```

### Điện thoại Android

1. Cài ứng dụng **Termux** (tải từ [F-Droid](https://f-droid.org/packages/com.termux/)).
2. Mở Termux, dán dòng sau rồi bấm Enter:

```
curl -fsSL https://github.com/ledienbien-ai/db-robot-phincomR1/releases/latest/download/install.sh | bash
```

3. Bấm Enter để tự tìm loa (hoặc gõ IP của loa) và làm theo hướng dẫn.

### macOS, Linux

Mở Terminal và dán cùng dòng lệnh `curl … | bash` như trên.

### iPhone, iPad

Chưa cài trực tiếp từ iPhone được. Hãy mượn một máy tính hoặc điện thoại Android để cài lần đầu; sau đó dùng iPhone điều khiển và cập nhật loa bình thường.

### Sau khi cài

- Mở trình duyệt, vào `http://<IP-của-loa>:8088` (bộ cài in sẵn địa chỉ này ở dòng cuối).
- Tab **Setup** → mục **Server** → bấm **DB-Robot**. Nếu loa chưa được kích hoạt, mã kích hoạt hiện ngay bên dưới — nhập mã đó trên trang quản lý của máy chủ.
- Gọi **"OK Nabu"** để nói chuyện với loa.

### Cập nhật bản mới

Loa tự kiểm tra bản mới. Khi có, trang điều khiển hiện thông báo màu đỏ ở đầu trang: vào tab **Setup** → **Cập nhật phần mềm** → **Cập nhật ngay**, chờ 2–3 phút. Mọi cài đặt được giữ nguyên. Muốn loa tự cài khi đang rảnh thì bật **Tự động cập nhật**.

### Gặp trục trặc

| Hiện tượng | Cách xử lý |
|---|---|
| Bộ cài không tìm thấy loa | Kiểm tra loa và máy cùng một mạng Wi-Fi. Xem IP của loa trong trang quản lý modem/router rồi gõ trực tiếp. |
| "Không kết nối được" hoặc cài mãi không xong | Rút điện loa 10 giây, cắm lại, chờ 1 phút rồi chạy lại bộ cài. |
| Loa không nghe "OK Nabu" | Trên loa còn ứng dụng trợ lý khác (ví dụ AI Box) đang giữ micro. Chạy lại bộ cài và trả lời **C** khi được hỏi tắt ứng dụng đó. |
| Muốn dùng lại ứng dụng trợ lý cũ | `adb connect <IP-loa>:5555` rồi `adb shell pm enable info.dourok.voicebot` |
| Nút Cập nhật báo lỗi | Cài lại bằng bộ cài ở trên — bộ cài luôn tải bản mới nhất và giữ nguyên cài đặt. |
