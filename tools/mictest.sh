#!/bin/bash
# Test mic app tự build (.dev) trên R1 (Phicomm R1, 10.25.113.209).
# Chạy: bash tools/mictest.sh   -> rồi NÓI vào loa R1 khi nó báo.
# (App rớt kết nối sau mỗi lượt thoại -> chạy lại script cho mỗi lần test.)
DIR="$(cd "$(dirname "$0")" && pwd)"
PY=/opt/homebrew/anaconda3/envs/xiaozhi/bin/python
SH="$DIR/r1sh.py"
# The two logs this reads live in the SERVER repo, not this one. Both are overridable, and both
# are checked below -- a stale path here used to cost a full 90-second run that then blamed the
# microphone (see the check under [2]).
#   STT: whisper was replaced by moonshine (com.user.robot-moonshine.plist writes this path).
WLOG="${MICTEST_STT_LOG:-/tmp/robot-moonshine.log}"
#   Server: sibling checkout of robot-esp32. Derived rather than hardcoded -- the absolute path
#   that used to be here broke twice, once when the repo moved to /Volumes/Data2 and once because
#   the directory is "robot-esp32", not "robot ESP32".
SRV="${MICTEST_SERVER_LOG:-$DIR/../../robot-esp32/xiaozhi-esp32-server/main/xiaozhi-server/tmp/server.log}"

echo "[1] tạm dừng watchdog R1 (khỏi nó tự restart aiboxplus giữa chừng)"
launchctl unload ~/Library/LaunchAgents/com.user.robot-r1watchdog.plist 2>/dev/null

echo "[2] tắt aiboxplus + mở app test .dev (nhường mic)"
$PY "$SH" "am force-stop info.dourok.voicebot; am force-stop vn.dbrobot.r1; am start -n vn.dbrobot.r1/info.dourok.voicebot.MainActivity 2>&1 | tail -1" 10 2>&1 | grep -aE "Starting|Error"

# Fail here, not after 90 seconds of silence. `wc -l` on a missing file falls back to 0, so a
# wrong path reads exactly like a wake word that never landed -- which is how the paths above went
# stale unnoticed for months.
for f in "$WLOG" "$SRV"; do
  [ -r "$f" ] || { echo "✗ không đọc được log: $f"; echo "  (đặt MICTEST_STT_LOG / MICTEST_SERVER_LOG nếu máy mày để chỗ khác)"; exit 1; }
done
WB=$(wc -l < "$WLOG")
SB=$(wc -l < "$SRV")
sleep 4
echo "[3] >>> Nói 'OK NABU' (chờ ~1 giây) RỒI nói câu hỏi rõ ràng <<<  (canh 90s)"
GOTW=0
for i in $(seq 1 18); do
  sleep 5
  lis=$(tail -n +$((SB+1)) "$SRV" 2>/dev/null | grep -aic "Received listen")
  [ "$lis" -gt 0 ] && [ "$GOTW" = 0 ] && { echo "  ✅ WAKE 'OK Nabu' ăn — server đang nghe, NÓI CÂU HỎI"; GOTW=1; }
  w=$(tail -n +$((WB+1)) "$WLOG" 2>/dev/null | grep -aiE "STT OK" | tail -2)
  if [ -n "$w" ]; then echo "=== STT (câu hỏi nghe được) ==="; echo "$w"; exit 0; fi
done
[ "$GOTW" = 1 ] && echo "(wake ăn nhưng câu hỏi rỗng — nói rõ hơn SAU khi wake ~1s)" || echo "(không bắt được wake — nói 'OK Nabu' to/rõ; chạy lại script)"
