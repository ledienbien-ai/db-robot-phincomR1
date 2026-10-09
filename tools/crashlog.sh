#!/bin/bash
# Trace crash app .dev trên R1.
#  - /sdcard/voicebot-crash.log: crash Java/Kotlin (do UncaughtExceptionHandler ghi) — CHÍNH.
#  - logcat crash buffer: gồm cả native FATAL (SIGSEGV trong .so).
# Chạy: bash tools/crashlog.sh        (xem)
#       bash tools/crashlog.sh clear  (xoá crash log cũ trước khi test lại)
DIR="$(cd "$(dirname "$0")" && pwd)"
PY=/opt/homebrew/anaconda3/envs/xiaozhi/bin/python
SH="$DIR/r1sh.py"

if [ "$1" = "clear" ]; then
  $PY "$SH" "rm -f /sdcard/voicebot-crash.log; logcat -c; echo cleared" 8 2>&1 | grep -a cleared
  echo "đã xoá crash log + clear logcat."
  exit 0
fi

echo "=== /sdcard/voicebot-crash.log (crash Java/Kotlin của app — đọc cái này trước) ==="
$PY "$SH" "echo SZ=\$(busybox wc -c < /sdcard/voicebot-crash.log 2>/dev/null); cat /sdcard/voicebot-crash.log 2>&1 | busybox tail -70" 16 2>&1 \
  | grep -avE "Warning|Deprecat|^echo SZ|^cat /sdcard"

echo
echo "=== logcat crash buffer (gồm native FATAL nếu có) ==="
$PY "$SH" "logcat -d -b crash -v time -t 80 2>&1" 14 2>&1 | grep -avE "Warning|Deprecat|^logcat -d" | tail -40
echo "--- (trống = chưa crash, hoặc native crash -> xem tombstone cần root) ---"
