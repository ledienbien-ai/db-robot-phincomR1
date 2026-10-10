## Cài DB-Robot vào loa Phicomm R1

**Cần có:** loa R1 đang bật; một máy tính hoặc điện thoại Android có Wi-Fi. Loa đã vào Wi-Fi nhà bạn thì máy cài phải **cùng mạng Wi-Fi** với loa; loa mới, chưa vào Wi-Fi nào thì xem mục [Loa mới, chưa vào Wi-Fi](#loa-mới-chưa-vào-wi-fi). Chỉ phải cài bằng cách này **một lần** — các bản sau cập nhật ngay trên trang điều khiển của loa.

### Máy tính Windows

1. Tải [DB-Robot-R1-Setup.exe](https://github.com/ledienbien-ai/db-robot-phincomR1/releases/latest/download/DB-Robot-R1-Setup.exe).
2. Bấm đúp tệp vừa tải. Nếu Windows hiện "Windows protected your PC", bấm *More info* → *Run anyway*.
3. Bấm Enter để bộ cài tự tìm loa trong mạng (hoặc gõ địa chỉ IP của loa), rồi làm theo các câu hỏi trên màn hình. Cài mất khoảng 2–3 phút.

Lần chạy đầu, bộ cài tải thêm công cụ adb của Google (khoảng 7 MB), nên máy tính cần có Internet.

**Tệp .exe bị trình duyệt hoặc phần mềm diệt virus chặn?** Tải [DB-Robot-R1-Windows.zip](https://github.com/ledienbien-ai/db-robot-phincomR1/releases/latest/download/DB-Robot-R1-Windows.zip), giải nén ra một thư mục rồi bấm đúp **Cai-dat-DB-Robot.bat** — đây là cùng một bộ cài. Hoặc mở **PowerShell** và dán dòng này:

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

### Loa mới, chưa vào Wi-Fi

Loa mới nguyên hộp chưa biết Wi-Fi nhà bạn nên chưa có địa chỉ IP. Bộ cài cài thẳng qua mạng do chính loa phát ra:

1. Chạy bộ cài như trên **khi máy còn Internet** (để nó tải xong công cụ và tệp cài đặt). Tới câu hỏi địa chỉ loa, gõ **M** rồi Enter.
2. Cắm điện loa, chờ khoảng 1 phút. Giữ nút trên đỉnh loa (nút nguồn) khoảng 5 giây: loa phát một mạng Wi-Fi tên **Phicomm R1**.
3. Trên máy đang chạy bộ cài, nối Wi-Fi vào mạng **Phicomm R1**. Máy báo "không có Internet" là bình thường. Quay lại bộ cài, bấm Enter.
4. Bộ cài cài DB-Robot qua địa chỉ `192.168.43.1`, rồi hỏi **tên và mật khẩu Wi-Fi nhà bạn** và chuyển loa sang mạng đó.
5. Nối máy trở lại Wi-Fi nhà. Bộ cài tự tìm loa và in ra địa chỉ trang điều khiển.

Nhập sai mật khẩu thì loa phát lại mạng **Phicomm R1**: nối máy vào mạng đó, gõ **M** trong bộ cài để nhập lại — hoặc mở `http://192.168.43.1:8088`, vào tab **System** → **Wi-Fi** và chọn mạng nhà ngay trên trang.

### iPhone, iPad

Chưa cài trực tiếp từ iPhone được. Hãy mượn một máy tính hoặc điện thoại Android để cài lần đầu; sau đó dùng iPhone điều khiển và cập nhật loa bình thường.

### Sau khi cài

- Mở trình duyệt, vào `http://<IP-của-loa>:8088` (bộ cài in sẵn địa chỉ này ở dòng cuối).
- Tab **System** → mục **Server** → bấm **DB-Robot**. Nếu loa chưa được kích hoạt, mã kích hoạt hiện ngay bên dưới — nhập mã đó trên trang quản lý của máy chủ.
- Gọi **"OK Nabu"** để nói chuyện với loa.

### Cập nhật bản mới

Loa tự kiểm tra bản mới. Khi có, trang điều khiển hiện thông báo màu đỏ ở đầu trang: vào tab **System** → **Cập nhật phần mềm** → **Cập nhật ngay**, chờ 2–3 phút. Mọi cài đặt được giữ nguyên. Muốn loa tự cài khi đang rảnh thì bật **Tự động cập nhật**.

### Loa mất Wi-Fi hoặc đổi bộ phát Wi-Fi

Đổi bộ phát, đổi mật khẩu Wi-Fi hay mang loa đi nơi khác thì loa không còn vào mạng được, và trang điều khiển ở địa chỉ cũ cũng không mở được. Khi đó:

1. Giữ nút trên đỉnh loa (nút nguồn) khoảng 5 giây: loa phát mạng Wi-Fi **Phicomm R1**.
2. Nối điện thoại hoặc máy tính vào mạng **Phicomm R1**.
3. Mở `http://192.168.43.1:8088`, vào tab **System** → **Wi-Fi**. Bấm **Quét mạng** (mạng của loa tắt khoảng 15 giây rồi hiện lại) hoặc **Mạng ẩn…** để gõ tên mạng, nhập mật khẩu, bấm **Kết nối**.
4. Nối thiết bị trở lại Wi-Fi nhà. Trang đang mở tự tìm loa và hiện địa chỉ mới.

Sai mật khẩu thì sau khoảng một phút loa phát lại mạng **Phicomm R1** để bạn thử lại.

### Gặp trục trặc

| Hiện tượng | Cách xử lý |
|---|---|
| Tệp `DB-Robot-R1-Setup.exe` bị chặn hoặc bị xoá ngay sau khi tải | Tệp chưa có chữ ký số nên một số máy cảnh báo. Dùng bản zip: tải `DB-Robot-R1-Windows.zip`, giải nén, bấm đúp **Cai-dat-DB-Robot.bat**. |
| Bộ cài không tìm thấy loa | Kiểm tra loa và máy cùng một mạng Wi-Fi. Xem IP của loa trong trang quản lý modem/router rồi gõ trực tiếp. Loa mới chưa vào Wi-Fi: trả lời **C** khi bộ cài hỏi "loa mới?", hoặc gõ **M** ở câu hỏi địa chỉ loa. |
| Giữ nút 5 giây mà không thấy mạng "Phicomm R1" | Chờ loa khởi động xong (khoảng 1 phút sau khi cắm điện) rồi giữ lại; đứng gần loa và làm mới danh sách Wi-Fi của máy. |
| Loa đã chuyển mạng nhưng không biết địa chỉ mới | Trang điều khiển (đang mở qua mạng của loa) tự tìm và hiện địa chỉ mới sau khi bạn nối lại Wi-Fi nhà. Nếu không thấy: xem danh sách thiết bị trong trang quản lý modem/router. |
| "Không kết nối được" hoặc cài mãi không xong | Rút điện loa 10 giây, cắm lại, chờ 1 phút rồi chạy lại bộ cài. |
| Loa không nghe "OK Nabu" | Trên loa còn ứng dụng trợ lý khác (ví dụ AI Box) đang giữ micro. Chạy lại bộ cài và trả lời **C** khi được hỏi tắt ứng dụng đó. |
| Muốn dùng lại ứng dụng trợ lý cũ | `adb connect <IP-loa>:5555` rồi `adb shell pm enable info.dourok.voicebot` |
| Nút Cập nhật báo "loa đang được một máy tính điều khiển qua adb" | Trên máy tính đã dùng adb với loa, chạy `adb disconnect` (hoặc tắt máy đó), rồi bấm **Cập nhật ngay** lần nữa. |
| Nút Cập nhật báo lỗi khác | Cài lại bằng bộ cài ở trên — bộ cài luôn tải bản mới nhất và giữ nguyên cài đặt. |
