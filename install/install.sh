#!/usr/bin/env bash
# DB-Robot R1 -- bộ cài cho điện thoại Android (Termux), macOS và Linux.
#
#   Cách dùng:   bash install.sh [IP-của-loa]
#   Hoặc:        curl -fsSL https://github.com/ledienbien-ai/db-robot-phincomR1/releases/latest/download/install.sh | bash
#
# Script tải bản DB-Robot mới nhất, kết nối tới loa Phicomm R1 qua adb trên Wi-Fi và cài vào loa.
# Chỉ cần làm một lần: các bản sau cập nhật ngay trong trang điều khiển của loa (http://IP-loa:8088).

REPO="ledienbien-ai/db-robot-phincomR1"
APK_URL="https://github.com/$REPO/releases/latest/download/DB-Robot-R1.apk"
PKG="vn.dbrobot.r1"
ACTIVITY="$PKG/info.dourok.voicebot.MainActivity"
PORT=5555
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
  fetch "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" "$zip" || die "Tải platform-tools thất bại."
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
  fetch "$APK_URL" "$APK" || die "Tải tệp cài đặt thất bại. Kiểm tra Internet rồi chạy lại."
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
probe() { # ip -> 0 nếu cổng adb mở
  case "$PROBE" in
    timeout) timeout 1 bash -c "exec 3<>/dev/tcp/$1/$PORT" >/dev/null 2>&1 ;;
    nc) if [ "$(uname -s)" = "Darwin" ]; then nc -z -G 1 -w 1 "$1" "$PORT" >/dev/null 2>&1
        else nc -z -w 1 "$1" "$PORT" >/dev/null 2>&1; fi ;;
    *) return 1 ;;
  esac
}

# Quét x.y.z.1-254 tìm máy mở cổng adb; in ra mỗi dòng một địa chỉ.
scan_network() { # prefix
  local prefix="$1" i dir="$WORK/scan"
  rm -rf "$dir"; mkdir -p "$dir"
  for i in $(seq 1 254); do
    ( probe "$prefix.$i" && : > "$dir/$i" ) &
  done
  wait
  for i in $(ls "$dir" 2>/dev/null | sort -n); do printf '%s\n' "$prefix.$i"; done
  rm -rf "$dir"
}

choose_speaker() {
  step "Tìm loa trong mạng"
  local ip="${1:-${LOA_IP:-}}" prefix found count n model
  if [ -z "$ip" ]; then
    ask "Nhập địa chỉ IP của loa (bấm Enter để tự tìm trong mạng Wi-Fi): "
    ip="$REPLY"
  fi
  if [ -z "$ip" ]; then
    pick_probe
    prefix="${LOA_PREFIX:-$(local_prefix)}"
    if [ -z "$PROBE" ] || [ -z "$prefix" ]; then
      die "Không tự tìm được trên máy này. Hãy xem IP của loa trong trang quản lý modem Wi-Fi rồi chạy lại: bash install.sh <IP>"
    fi
    say "Đang quét mạng $prefix.x (vài giây)..."
    found="$(scan_network "$prefix")"
    count="$(printf '%s\n' "$found" | grep -c .)"
    if [ "$count" -eq 0 ]; then
      die "Không thấy thiết bị nào mở cổng adb ($PORT) trong mạng $prefix.x. Kiểm tra loa đã bật và cùng mạng Wi-Fi với máy này, hoặc nhập IP trực tiếp: bash install.sh <IP>"
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
      yes_no "Cài DB-Robot vào thiết bị $ip? [C/k]: " c || die "Đã huỷ."
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
  local list p found=""
  dev_run "pm list packages" 45 || return 0
  say ""
  list="$OUT"
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

main() {
  say "=============================================="
  say "  DB-Robot R1 -- cài đặt vào loa Phicomm R1"
  say "=============================================="
  find_adb
  find_apk
  choose_speaker "${1:-}"
  install_apk
  handle_rivals
  start_app
  adbs shell "rm -f $DEV_APK $DEV_LOG" >/dev/null 2>&1
  local host="${SERIAL%:*}"
  release
  say ""
  say "=============================================="
  say "  XONG. Mở trang điều khiển của loa:"
  say "      http://$host:8088"
  say "  Tab System -> bấm máy chủ DB-Robot để kết nối."
  say "  Các bản mới về sau: cập nhật ngay trong tab System."
  say "=============================================="
}

main "$@"
