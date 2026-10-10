#!/usr/bin/env bash
# DB-Robot R1 -- bộ cài cho điện thoại Android (Termux), macOS và Linux.
#
#   Cách dùng:   bash install.sh [IP-của-loa]
#   Hoặc:        curl -fsSL https://github.com/ledienbien-ai/db-robot-phincomR1/releases/latest/download/install.sh | bash
#
# Script tải bản DB-Robot mới nhất, kết nối tới loa Phicomm R1 qua adb trên Wi-Fi và cài vào loa.
# Chỉ cần làm một lần: các bản sau cập nhật ngay trong trang điều khiển của loa (http://IP-loa:8088).
#
# Loa mới, chưa vào Wi-Fi nào: bộ cài hướng dẫn nối máy này vào mạng do chính loa phát, cài qua
# địa chỉ 192.168.43.1, rồi hỏi tên và mật khẩu Wi-Fi nhà để chuyển loa sang.

REPO="ledienbien-ai/db-robot-phincomR1"
APK_URL="https://github.com/$REPO/releases/latest/download/DB-Robot-R1.apk"
PKG="vn.dbrobot.r1"
ACTIVITY="$PKG/info.dourok.voicebot.MainActivity"
PORT=5555
PANEL_PORT=8088
# Địa chỉ của loa khi chính nó phát Wi-Fi (chế độ cài đặt mạng: giữ nút trên đỉnh loa 5 giây,
# loa phát mạng "Phicomm R1"). Android 5.1 luôn dùng địa chỉ này.
AP_IP="${LOA_AP_IP:-192.168.43.1}"
DEV_APK="/data/local/tmp/dbrobot.apk"
DEV_LOG="/data/local/tmp/dbrobot-cmd.log"
# Ứng dụng trợ lý khác dùng chung micro với DB-Robot (AI Box Plus và bản xiaozhi gốc).
RIVALS="info.dourok.voicebot info.dourok.voicebot.dev"

WORK="${TMPDIR:-/tmp}/db-robot-r1"
ADB="adb"
SERIAL=""
OUT=""

say()  { printf '%s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
die()  { release; printf '\n[LỖI] %s\n' "$*" >&2; exit 1; }

# Ngắt kết nối adb tới loa. Bắt buộc phải làm khi xong: loa chỉ phục vụ được một máy qua adb tại
# một thời điểm, nên nếu máy này cứ giữ kết nối thì loa không gọi được trình cài đặt của chính nó
# -- nút "Cập nhật ngay" trên trang điều khiển sẽ báo lỗi cho tới khi máy này tắt.
release() {
  [ -n "$SERIAL" ] && "$ADB" disconnect "$SERIAL" >/dev/null 2>&1
  return 0
}

# Hỏi người dùng. Đọc từ /dev/tty để vẫn hỏi được khi script chạy qua "curl ... | bash".
ask() {
  REPLY=""
  if { : < /dev/tty; } 2>/dev/null; then
    printf '%s' "$1" > /dev/tty
    IFS= read -r REPLY < /dev/tty || REPLY=""
  else
    printf '%s' "$1"
    IFS= read -r REPLY || REPLY=""
  fi
}
# Câu hỏi có/không; $2 là câu trả lời khi chỉ bấm Enter (c hoặc k).
yes_no() {
  ask "$1"
  [ -z "$REPLY" ] && REPLY="$2"
  case "$REPLY" in c*|C*|y*|Y*) return 0 ;; *) return 1 ;; esac
}

fetch() { # url, tệp đích
  if command -v curl >/dev/null 2>&1; then curl -fL --retry 2 --connect-timeout 20 -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then wget -O "$2" "$1"
  else die "Máy chưa có curl hoặc wget để tải tệp."
  fi
}

# Máy đang nối vào mạng của loa thì không có Internet: nói rõ thay vì chỉ báo "tải thất bại".
offline_hint() {
  if [ "$(local_prefix)" = "${AP_IP%.*}" ]; then
    printf '%s' " Máy này đang nối vào mạng của loa nên không có Internet: hãy nối lại Wi-Fi nhà rồi chạy lại -- bộ cài sẽ báo khi nào cần chuyển sang mạng của loa."
  fi
}

is_termux() { [ -n "${TERMUX_VERSION:-}" ] || case "${PREFIX:-}" in *com.termux*) return 0 ;; *) return 1 ;; esac; }

# ── 1. adb ───────────────────────────────────────────────────────────────
find_adb() {
  step "Kiểm tra công cụ adb"
  if command -v adb >/dev/null 2>&1; then ADB="adb"; say "Đã có adb."; return; fi
  if [ -x "$HOME/.db-robot-r1/platform-tools/adb" ]; then
    ADB="$HOME/.db-robot-r1/platform-tools/adb"; say "Dùng adb đã tải lần trước."; return
  fi
  if is_termux; then
    say "Đang cài android-tools trong Termux..."
    pkg install -y android-tools || die "Không cài được android-tools. Hãy chạy: pkg update && pkg install android-tools"
    command -v adb >/dev/null 2>&1 || die "Cài xong vẫn không thấy adb."
    return
  fi
  local os zip
  case "$(uname -s)" in
    Darwin) os="darwin" ;;
    Linux)  os="linux" ;;
    *) die "Chưa có adb. Hãy cài Android platform-tools rồi chạy lại." ;;
  esac
  if [ "$os" = "linux" ] && [ "$(uname -m)" != "x86_64" ]; then
    die "Chưa có adb. Hãy cài bằng lệnh: sudo apt install adb   rồi chạy lại."
  fi
  command -v unzip >/dev/null 2>&1 || die "Chưa có adb và cũng không có unzip để tự tải. Hãy cài Android platform-tools rồi chạy lại."
  say "Chưa có adb -- đang tải Android platform-tools từ Google (khoảng 10 MB)..."
  mkdir -p "$HOME/.db-robot-r1"
  zip="$HOME/.db-robot-r1/platform-tools.zip"
  fetch "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" "$zip" || die "Tải platform-tools thất bại.$(offline_hint)"
  unzip -oq "$zip" -d "$HOME/.db-robot-r1" || die "Giải nén platform-tools thất bại."
  rm -f "$zip"
  ADB="$HOME/.db-robot-r1/platform-tools/adb"
  [ -x "$ADB" ] || die "Không tìm thấy adb sau khi giải nén."
}

# ── 2. Tệp cài đặt ───────────────────────────────────────────────────────
APK=""
find_apk() {
  step "Chuẩn bị tệp cài đặt DB-Robot"
  local here f
  here="$(cd "$(dirname "${BASH_SOURCE[0]:-.}")" 2>/dev/null && pwd)"
  for f in "$here"/DB-Robot-R1*.apk ./DB-Robot-R1*.apk; do
    if [ -f "$f" ]; then APK="$f"; say "Dùng tệp có sẵn: $APK"; return; fi
  done
  mkdir -p "$WORK"
  APK="$WORK/DB-Robot-R1.apk"
  say "Đang tải bản mới nhất..."
  fetch "$APK_URL" "$APK" || die "Tải tệp cài đặt thất bại. Kiểm tra Internet rồi chạy lại.$(offline_hint)"
  # Một tệp APK là tệp zip: bắt đầu bằng "PK" và nặng nhiều MB. Trang báo lỗi thì không.
  if [ "$(head -c 2 "$APK" 2>/dev/null)" != "PK" ] || [ "$(wc -c < "$APK")" -lt 1000000 ]; then
    rm -f "$APK"; die "Tệp tải về không phải tệp cài đặt hợp lệ."
  fi
  say "Đã tải xong."
}

# ── 3. Tìm loa ───────────────────────────────────────────────────────────
local_prefix() {
  local ip=""
  if command -v ip >/dev/null 2>&1; then
    ip="$(ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -n 1)"
  fi
  if [ -z "$ip" ] && command -v ifconfig >/dev/null 2>&1; then
    # Điện thoại: hỏi thẳng card Wi-Fi trước, kẻo lấy nhầm địa chỉ của mạng di động.
    ip="$(ifconfig wlan0 2>/dev/null | sed -n 's/.*inet \(addr:\)\{0,1\}\([0-9][0-9.]*\).*/\2/p' | head -n 1)"
  fi
  if [ -z "$ip" ] && command -v ifconfig >/dev/null 2>&1; then
    ip="$(ifconfig 2>/dev/null | sed -n 's/.*inet \(addr:\)\{0,1\}\([0-9][0-9.]*\).*/\2/p' | grep -v '^127\.' | head -n 1)"
  fi
  [ -n "$ip" ] && printf '%s' "${ip%.*}"
}

PROBE=""
pick_probe() {
  if command -v timeout >/dev/null 2>&1; then PROBE="timeout"
  elif command -v nc >/dev/null 2>&1; then PROBE="nc"
  fi
}
probe() { # ip, [cổng] -> 0 nếu cổng đó mở (mặc định: cổng adb)
  local port="${2:-$PORT}"
  case "$PROBE" in
    timeout) timeout 1 bash -c "exec 3<>/dev/tcp/$1/$port" >/dev/null 2>&1 ;;
    nc) if [ "$(uname -s)" = "Darwin" ]; then nc -z -G 1 -w 1 "$1" "$port" >/dev/null 2>&1
        else nc -z -w 1 "$1" "$port" >/dev/null 2>&1; fi ;;
    *) return 1 ;;
  esac
}

# Quét x.y.z.1-254 tìm máy mở một cổng (mặc định: cổng adb); in ra mỗi dòng một địa chỉ.
scan_network() { # prefix, [cổng]
  local prefix="$1" port="${2:-$PORT}" i dir="$WORK/scan"
  rm -rf "$dir"; mkdir -p "$dir"
  for i in $(seq 1 254); do
    ( probe "$prefix.$i" "$port" && : > "$dir/$i" ) &
  done
  wait
  for i in $(ls "$dir" 2>/dev/null | sort -n); do printf '%s\n' "$prefix.$i"; done
  rm -rf "$dir"
}

# Tên mạng Wi-Fi máy này đang nối, nếu hỏi được hệ điều hành; rỗng nếu không rõ.
current_ssid() {
  local name=""
  if command -v iwgetid >/dev/null 2>&1; then name="$(iwgetid -r 2>/dev/null)"; fi
  if [ -z "$name" ] && command -v nmcli >/dev/null 2>&1; then
    name="$(nmcli -t -f active,ssid dev wifi 2>/dev/null | sed -n 's/^yes://p' | head -n 1)"
  fi
  if [ -z "$name" ] && [ "$(uname -s)" = "Darwin" ]; then
    name="$(networksetup -getairportnetwork en0 2>/dev/null | sed -n 's/^Current Wi-Fi Network: //p')"
  fi
  printf '%s' "$name"
}
# Mạng do loa phát ra, không phải Wi-Fi nhà.
is_speaker_ssid() { case "$1" in Phicomm[\ _-]R1*|PhicommR1*) return 0 ;; *) return 1 ;; esac; }

# Loa mới chưa vào Wi-Fi nào: đưa máy này sang mạng do chính loa phát, rồi chờ tới khi thấy loa.
join_speaker_hotspot() {
  local try
  step "Loa mới, chưa vào Wi-Fi: cài qua mạng do chính loa phát"
  say "  1. Cắm điện loa và chờ loa khởi động xong (khoảng 1 phút)."
  say "  2. Giữ nút trên đỉnh loa (nút nguồn) khoảng 5 giây: loa phát một mạng Wi-Fi"
  say "     tên \"Phicomm R1\"."
  say "  3. Trên máy này, mở danh sách Wi-Fi và nối vào mạng \"Phicomm R1\"."
  say "     Máy báo \"không có Internet\" là bình thường -- cứ giữ kết nối đó"
  say "     (điện thoại hỏi có giữ mạng này không thì chọn Có)."
  pick_probe
  while :; do
    ask "Nối xong thì bấm Enter (gõ K để thoát): "
    case "$REPLY" in k*|K*) die "Đã huỷ." ;; esac
    say "Đang tìm loa ở địa chỉ $AP_IP ..."
    for try in 1 2 3 4 5 6; do
      # Không có công cụ dò cổng thì hỏi thẳng adb.
      if [ -n "$PROBE" ]; then probe "$AP_IP" && { say "Đã thấy loa."; return 0; }
      elif "$ADB" connect "$AP_IP:$PORT" 2>/dev/null | grep -q "connected to"; then
        "$ADB" disconnect "$AP_IP:$PORT" >/dev/null 2>&1; say "Đã thấy loa."; return 0
      fi
      sleep 2
    done
    say "Chưa thấy loa. Kiểm tra máy này đã nối đúng vào mạng \"Phicomm R1\" của loa chưa,"
    say "và tắt VPN nếu đang bật."
  done
}

choose_speaker() {
  step "Tìm loa trong mạng"
  local ip="${1:-${LOA_IP:-}}" prefix found="" count=0 n model
  if [ -z "$ip" ]; then
    say "Loa đã vào Wi-Fi nhà: bấm Enter để tự tìm, hoặc gõ địa chỉ IP của loa."
    say "Loa mới, chưa vào Wi-Fi nào: gõ M."
    ask "IP của loa / Enter / M: "
    ip="$REPLY"
  fi
  case "$ip" in m|M) join_speaker_hotspot; ip="$AP_IP" ;; esac
  if [ -z "$ip" ]; then
    pick_probe
    prefix="${LOA_PREFIX:-$(local_prefix)}"
    if [ -n "$PROBE" ] && [ -n "$prefix" ]; then
      say "Đang quét mạng $prefix.x (vài giây)..."
      found="$(scan_network "$prefix")"
      count="$(printf '%s\n' "$found" | grep -c .)"
    fi
    if [ "$count" -eq 0 ]; then
      if [ -z "$PROBE" ] || [ -z "$prefix" ]; then say "Không tự tìm được trên máy này."
      else say "Không thấy thiết bị nào mở cổng adb ($PORT) trong mạng $prefix.x."; fi
      say "Nếu loa đã vào Wi-Fi nhà: kiểm tra loa đã bật và cùng mạng với máy này, hoặc xem IP"
      say "của loa trong trang quản lý modem Wi-Fi rồi chạy lại: bash install.sh <IP>"
      yes_no "Hay đây là loa mới, chưa vào Wi-Fi nào? [C/k]: " c || die "Không tìm thấy loa."
      join_speaker_hotspot
      found="$AP_IP"; count=1
    fi
    n=0
    for ip in $found; do
      n=$((n + 1))
      "$ADB" connect "$ip:$PORT" >/dev/null 2>&1
      model="$("$ADB" -s "$ip:$PORT" shell getprop ro.product.model 2>/dev/null | tr -d '\r' | head -n 1)"
      "$ADB" disconnect "$ip:$PORT" >/dev/null 2>&1   # chỉ hỏi tên; không giữ kết nối với máy không được chọn
      say "  $n) $ip   ${model:-(chưa rõ thiết bị)}"
    done
    if [ "$count" -eq 1 ]; then
      ip="$found"
      [ "$ip" = "$AP_IP" ] || yes_no "Cài DB-Robot vào thiết bị $ip? [C/k]: " c || die "Đã huỷ."
    else
      ask "Chọn số thứ tự của loa (1-$count): "
      ip="$(printf '%s\n' "$found" | sed -n "${REPLY:-0}p")"
      [ -n "$ip" ] || die "Lựa chọn không hợp lệ."
    fi
  fi
  case "$ip" in *:*) SERIAL="$ip" ;; *) SERIAL="$ip:$PORT" ;; esac
}

# ── 4. Nói chuyện với loa ────────────────────────────────────────────────
adbs() { "$ADB" -s "$SERIAL" "$@"; }

connect() { # thử vài lần; adb qua Wi-Fi của loa này hay rớt
  local try state
  for try in 1 2 3 4 5; do
    "$ADB" connect "$SERIAL" >/dev/null 2>&1
    state="$(adbs get-state 2>/dev/null | tr -d '\r')"
    [ "$state" = "device" ] && return 0
    "$ADB" disconnect "$SERIAL" >/dev/null 2>&1
    sleep 2
  done
  return 1
}

# Chạy một lệnh trên loa và chờ nó xong; kết quả nằm trong $OUT.
# Không gọi thẳng "adb shell <lệnh>": các lệnh chạy lâu (pm install mất 1-2 phút) làm kết nối adb
# của loa rớt giữa chừng ("error: closed") và lệnh chết theo. Thay vào đó lệnh được chạy nền trên
# loa, ghi kết quả ra tệp, còn ở đây chỉ đọc tệp cho tới khi thấy dòng kết thúc.
# Mỗi lần gọi có một mã riêng ghi ở dòng đầu và dòng cuối của tệp, nhờ vậy phân biệt được ba
# trường hợp: lệnh chưa hề chạy (gửi lại), đang chạy (chờ tiếp), và tệp cũ của lần gọi trước.
dev_run() { # lệnh, số giây chờ tối đa
  local cmd="$1" limit="${2:-60}" waited=0 launched=0 relaunch=1 token="t$$x$RANDOM"
  OUT=""
  while [ "$waited" -lt "$limit" ]; do
    if [ "$relaunch" -eq 1 ] && [ "$launched" -lt 3 ]; then
      connect
      adbs shell "trap '' HUP; ( echo begin $token; $cmd; echo exit=\$? end $token ) > $DEV_LOG 2>&1 &" >/dev/null 2>&1
      launched=$((launched + 1)); relaunch=0
    fi
    sleep 3; waited=$((waited + 3))
    OUT="$(adbs shell "cat $DEV_LOG 2>/dev/null || echo NOLOG" 2>/dev/null | tr -d '\r')"
    case "$OUT" in
      *"end $token"*) return 0 ;;
      *"begin $token"*) ;;                    # đang chạy trên loa: chờ tiếp
      NOLOG*|*"begin t"*) relaunch=1 ;;       # chưa có tệp, hoặc tệp của lần gọi trước: lệnh chưa chạy
      *) connect ;;                           # không đọc được gì: kết nối rớt, nối lại rồi đọc tiếp
    esac
    printf '.'
  done
  return 1
}

install_apk() {
  step "Chép tệp cài đặt sang loa"
  local try ok=1
  for try in 1 2 3; do
    connect || die "Không kết nối được tới $SERIAL. Kiểm tra IP, loa đã bật và cùng mạng Wi-Fi. Nếu vẫn không được, rút điện loa 10 giây rồi cắm lại."
    if adbs push "$APK" "$DEV_APK"; then ok=0; break; fi
    say "Chép bị gián đoạn, thử lại ($try/3)..."
    sleep 2
  done
  [ "$ok" -eq 0 ] || die "Không chép được tệp sang loa."

  step "Cài đặt (loa cũ nên mất 1-3 phút, đừng tắt cửa sổ này)"
  dev_run "pm install -r $DEV_APK" 420 || die "Chờ quá lâu mà loa chưa cài xong. Rút điện loa 10 giây, cắm lại rồi chạy lại bộ cài."
  say ""
  case "$OUT" in
    *Success*) say "Cài đặt thành công." ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
      say "Trên loa đang có một bản DB-Robot cũ ký bằng khoá khác nên không cài đè được."
      say "Cần gỡ bản cũ trước -- các cài đặt của bản cũ sẽ mất và loa phải kích hoạt lại với máy chủ."
      yes_no "Gỡ bản cũ rồi cài lại? [c/K]: " k || die "Đã dừng theo yêu cầu. Loa vẫn giữ nguyên bản cũ."
      dev_run "pm uninstall $PKG" 120 || die "Không gỡ được bản cũ."
      say ""
      dev_run "pm install -r $DEV_APK" 420 || die "Chờ quá lâu mà loa chưa cài xong."
      say ""
      case "$OUT" in *Success*) say "Cài đặt thành công." ;; *) die "Cài đặt thất bại: $(printf '%s' "$OUT" | grep -m1 Failure)" ;; esac ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*) die "Loa đang chạy bản DB-Robot mới hơn tệp này -- không cần cài." ;;
    *INSTALL_FAILED_INSUFFICIENT_STORAGE*) die "Bộ nhớ của loa đã đầy. Hãy gỡ bớt ứng dụng trên loa rồi chạy lại." ;;
    *) die "Cài đặt thất bại: $(printf '%s' "$OUT" | grep -v -e '^exit=' -e '^begin ' | tail -n 2 | tr '\n' ' ')" ;;
  esac
}

# AI Box Plus (và bản xiaozhi gốc) giữ micro liên tục; để nguyên thì DB-Robot không nghe được gì.
handle_rivals() {
  local list p found="" stock
  dev_run "pm list packages" 45 || return 0
  say ""
  list="$OUT"
  # Phần mềm gốc của Phicomm: chỉ kể tên, không động tới. Danh sách này giúp tìm nguyên nhân nếu
  # trên loa nguyên bản DB-Robot không nghe được lệnh.
  stock="$(printf '%s\n' "$list" | sed -n 's/^package:\(com\.phicomm[^ ]*\)$/\1/p' | tr '\n' ' ')"
  if [ -n "$stock" ]; then
    say "Phần mềm gốc Phicomm trên loa (bộ cài để nguyên): $stock"
    say "Nếu DB-Robot không nghe được lệnh, hãy gửi dòng trên cho nhóm hỗ trợ (dbrobot.vn)."
  fi
  for p in $RIVALS; do
    if printf '%s\n' "$list" | grep -qx "package:$p"; then found="$found $p"; fi
  done
  [ -n "$found" ] || return 0
  step "Ứng dụng trợ lý khác trên loa"
  say "Loa đang có ứng dụng trợ lý khác:$found"
  say "Nó giữ micro nên DB-Robot sẽ không nghe được nếu cả hai cùng chạy."
  say "Tắt nó không xoá gì cả; muốn dùng lại chỉ cần chạy: adb shell pm enable <tên-gói>"
  yes_no "Tắt ứng dụng đó để DB-Robot dùng micro? [C/k]: " c || { say "Giữ nguyên. Lưu ý DB-Robot có thể không nghe được lệnh."; return 0; }
  for p in $found; do
    dev_run "pm disable-user $p" 45
    say ""
    case "$OUT" in
      *disabled*) say "Đã tắt $p." ;;
      *) adbs shell "am force-stop $p" >/dev/null 2>&1
         say "Không tắt hẳn được $p (đã tạm dừng; nó sẽ chạy lại khi loa khởi động lại)." ;;
    esac
  done
}

start_app() {
  step "Khởi động DB-Robot"
  local try
  for try in 1 2 3; do
    connect
    if adbs shell "am force-stop $PKG; am start -n $ACTIVITY" 2>/dev/null | grep -q "Starting"; then
      say "DB-Robot đang chạy."; return 0
    fi
    sleep 2
  done
  say "Chưa khởi động được ứng dụng -- hãy rút điện loa rồi cắm lại, DB-Robot sẽ tự chạy."
}

# ── 5. Loa mới: chuyển loa từ mạng riêng sang Wi-Fi nhà ──────────────────
# Gọi trang điều khiển của loa; in ra nội dung trả lời. Không đi qua proxy của máy: đây là địa
# chỉ trong nhà.
http_get() { # url, [giây chờ]
  if command -v curl >/dev/null 2>&1; then curl -fsS --noproxy '*' --max-time "${2:-6}" "$1" 2>/dev/null
  else wget -q --no-proxy -T "${2:-6}" -t 1 -O - "$1" 2>/dev/null
  fi
}
# Phải ghi rõ charset: thiếu nó máy chủ trên loa đọc nội dung theo ASCII, tên mạng có dấu sẽ hỏng.
http_post() { # url, nội dung JSON
  local type="Content-Type: application/json; charset=utf-8"
  if command -v curl >/dev/null 2>&1; then curl -fsS --noproxy '*' --max-time 10 -H "$type" --data-binary "$2" "$1" 2>/dev/null
  else wget -q --no-proxy -T 10 -t 1 -O - --header="$type" --post-data="$2" "$1" 2>/dev/null
  fi
}
json_text() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

# Hỏi tên và mật khẩu Wi-Fi nhà; kết quả trong WIFI_SSID, WIFI_PASS, WIFI_SEC. Trả về 1 nếu bỏ qua.
# Loa trả danh sách mạng nó nghe thấy, mỗi dòng "bảo-mật TAB số-vạch TAB tên", mạnh nhất trước;
# danh sách rỗng khi loa đang phát Wi-Fi bằng phần mềm gốc (không vừa phát vừa quét được).
WIFI_SSID=""; WIFI_PASS=""; WIFI_SEC=""
read_home_wifi() { # địa chỉ gốc của trang điều khiển
  local list count=0 n=0 sec bars name hint lock tab
  tab="$(printf '\t')"
  # Bỏ dòng không có tên (mạng ẩn) và mạng cài đặt của một loa R1 khác.
  list="$(http_get "$1/api/wifi/list" | tr -d '\r' | grep "^[^$tab]*$tab[^$tab]*$tab." | grep -v "${tab}Phicomm[ _-]\{0,1\}R1" | head -n 12)"
  if [ -n "$list" ]; then
    say "Các mạng Wi-Fi loa nghe thấy:"
    while IFS="$tab" read -r sec bars name; do
      [ -n "$name" ] || continue
      n=$((n + 1))
      lock="có mật khẩu"; [ "$sec" = "open" ] && lock="không mật khẩu"
      say "  $n) $name   [sóng $bars/4, $lock]"
    done <<EOF
$list
EOF
    count="$n"
  fi
  hint="Tên Wi-Fi nhà bạn"
  [ "$count" -gt 0 ] && hint="Chọn số thứ tự, hoặc gõ tên Wi-Fi nhà bạn"
  [ -n "$HOME_SSID" ] && hint="$hint (Enter = \"$HOME_SSID\")"
  WIFI_SSID=""; WIFI_PASS=""; WIFI_SEC=""
  while [ -z "$WIFI_SSID" ]; do
    ask "$hint; gõ K để bỏ qua: "
    case "$REPLY" in k|K) return 1 ;; esac
    [ -z "$REPLY" ] && REPLY="$HOME_SSID"
    [ -z "$REPLY" ] && continue
    case "$REPLY" in
      *[!0-9]*) WIFI_SSID="$REPLY" ;;
      *) if [ "$REPLY" -ge 1 ] 2>/dev/null && [ "$REPLY" -le "$count" ] 2>/dev/null; then
           WIFI_SSID="$(printf '%s\n' "$list" | sed -n "${REPLY}p" | cut -f 3-)"
         else WIFI_SSID="$REPLY"; fi ;;
    esac
  done
  # Mạng có trong danh sách thì loa đã biết kiểu bảo mật của nó.
  while IFS="$tab" read -r sec bars name; do
    [ "$name" = "$WIFI_SSID" ] && WIFI_SEC="$sec"
  done <<EOF
$list
EOF
  if [ "$WIFI_SEC" != "open" ]; then
    ask "Mật khẩu của \"$WIFI_SSID\" (để trống nếu mạng không đặt mật khẩu): "
    WIFI_PASS="$REPLY"
    if [ -z "$WIFI_SEC" ]; then
      if [ -n "$WIFI_PASS" ]; then WIFI_SEC="psk"; else WIFI_SEC="open"; fi
    fi
  fi
  return 0
}

# Sau khi loa rời mạng riêng: tìm nó trong mạng nhà bằng mã thiết bị đọc được lúc trước.
# Trả về 0 và đặt FOUND_IP khi thấy; 2 khi loa đã quay lại phát Wi-Fi (không vào được); 1 khi hết giờ.
FOUND_IP=""
wait_speaker_at_home() { # mã thiết bị, tên mạng, số giây chờ tối đa
  local id="$1" ssid="$2" limit="$3" waited=0 prefix ip st
  FOUND_IP=""
  pick_probe
  while [ "$waited" -lt "$limit" ]; do
    sleep 4; waited=$((waited + 4)); printf '.'
    prefix="${LOA_PREFIX:-$(local_prefix)}"
    [ -n "$prefix" ] || continue
    if [ "$prefix" = "${AP_IP%.*}" ]; then
      # Máy này vẫn (hoặc lại) ở mạng của loa: loa đã thử xong và không vào được?
      st="$(http_get "http://$AP_IP:$PANEL_PORT/api/wifi/state" 3)"
      case "$st" in *'"ap":true'*) case "$st" in *'"state":"failed"'*) say ""; return 2 ;; esac ;; esac
      continue
    fi
    [ -n "$PROBE" ] || continue
    waited=$((waited + 2))
    for ip in $(scan_network "$prefix" "$PANEL_PORT"); do
      st="$(http_get "http://$ip:$PANEL_PORT/api/state" 4)"
      case "$st" in *'"device_id":"'*) ;; *) continue ;; esac
      if [ -n "$id" ]; then case "$st" in *"\"device_id\":\"$id\""*) ;; *) continue ;; esac; fi
      FOUND_IP="$ip"; say ""; return 0
    done
  done
  say ""
  return 1
}

# Đặt HOST thành địa chỉ mới của loa trong mạng nhà; trả về 1 nếu chưa chuyển được.
move_speaker_to_home() {
  local base="http://$AP_IP:$PANEL_PORT" try st="" id reply body limit rc
  step "Đưa loa vào Wi-Fi nhà bạn"
  say "Đang chờ DB-Robot trên loa sẵn sàng..."
  for try in $(seq 1 30); do
    st="$(http_get "$base/api/wifi/state")"
    case "$st" in *'"ok":true'*) break ;; esac
    st=""; sleep 3
  done
  if [ -z "$st" ]; then
    say "Chưa gọi được trang điều khiển của loa. Hãy mở $base bằng trình duyệt trên máy này,"
    say "vào tab System -> Wi-Fi để chọn mạng nhà."
    return 1
  fi
  case "$st" in *'"ap":true'*) ;; *) HOST="$AP_IP"; return 0 ;; esac   # loa không phát Wi-Fi: đã ở trong một mạng rồi
  id="$(http_get "$base/api/state" | sed -n 's/.*"device_id":"\([^"]*\)".*/\1/p' | head -n 1)"

  while :; do
    read_home_wifi "$base" || return 1
    body="{\"ssid\":\"$(json_text "$WIFI_SSID")\",\"password\":\"$(json_text "$WIFI_PASS")\",\"security\":\"$WIFI_SEC\"}"
    reply="$(http_post "$base/api/wifi/connect" "$body")"
    case "$reply" in
      *'"ok":true'*) ;;
      "") say "Loa không trả lời. Kiểm tra máy này còn nối vào mạng của loa không, rồi thử lại."; continue ;;
      *) say "Loa từ chối: $(printf '%s' "$reply" | sed -n 's/.*"error":"\([^"]*\)".*/\1/p')"; continue ;;
    esac
    say "Loa đang rời chế độ cài đặt để vào \"$WIFI_SSID\" (mất khoảng nửa phút)."
    say "Bây giờ hãy nối máy này trở lại Wi-Fi \"$WIFI_SSID\"."
    limit=90
    while :; do
      wait_speaker_at_home "$id" "$WIFI_SSID" "$limit"; rc=$?
      if [ "$rc" -eq 0 ]; then say "Đã thấy loa trong mạng nhà: $FOUND_IP"; HOST="$FOUND_IP"; return 0; fi
      if [ "$rc" -eq 2 ]; then
        say "Loa không vào được mạng \"$WIFI_SSID\" (sai mật khẩu hoặc sóng quá yếu)."
        break
      fi
      say "Chưa thấy loa trong mạng của máy này."
      say " - Máy này đã nối lại Wi-Fi nhà chưa? Nối xong bấm Enter để tìm tiếp."
      say " - Nếu mạng \"Phicomm R1\" của loa hiện lại trong danh sách Wi-Fi: loa chưa vào được"
      say "   mạng nhà (thường do sai mật khẩu). Nối máy này vào mạng đó rồi gõ M để nhập lại."
      ask "Enter = tìm tiếp, M = nhập lại Wi-Fi, K = kết thúc: "
      case "$REPLY" in
        k*|K*) return 1 ;;
        m*|M*) if [ -n "$(http_get "$base/api/wifi/state" 4)" ]; then break; fi
               say "Máy này chưa nối vào mạng của loa (không gọi được $AP_IP)." ;;
      esac
      limit=40
    done
  done
}

HOME_SSID=""
HOST=""
main() {
  say "=============================================="
  say "  DB-Robot R1 -- cài đặt vào loa Phicomm R1"
  say "=============================================="
  HOME_SSID="$(current_ssid)"
  is_speaker_ssid "$HOME_SSID" && HOME_SSID=""
  # adb và tệp cài đặt trước, tìm loa sau: với loa mới, đến bước tìm loa máy này mới phải rời
  # Wi-Fi nhà (và mất Internet) để sang mạng của loa.
  find_adb
  find_apk
  choose_speaker "${1:-}"
  install_apk
  handle_rivals
  start_app
  adbs shell "rm -f $DEV_APK $DEV_LOG" >/dev/null 2>&1
  HOST="${SERIAL%:*}"
  release
  if [ "$HOST" = "$AP_IP" ] && ! move_speaker_to_home; then
    say ""
    say "=============================================="
    say "  ĐÃ CÀI XONG DB-Robot, nhưng chưa thấy loa trong Wi-Fi nhà."
    say "  - Loa đã vào mạng: xem IP của loa trong trang quản lý modem,"
    say "    rồi mở http://IP-của-loa:$PANEL_PORT"
    say "  - Loa chưa vào mạng: nối điện thoại hoặc máy tính vào mạng"
    say "    \"Phicomm R1\" của loa (chưa thấy mạng đó thì giữ nút trên đỉnh"
    say "    loa 5 giây), mở http://$AP_IP:$PANEL_PORT -> tab System -> Wi-Fi."
    say "=============================================="
    return 0
  fi
  say ""
  say "=============================================="
  say "  XONG. Mở trang điều khiển của loa:"
  say "      http://$HOST:$PANEL_PORT"
  say "  Tab System -> bấm máy chủ DB-Robot để kết nối."
  say "  Các bản mới về sau: cập nhật ngay trong tab System."
  say "=============================================="
}

main "$@"
