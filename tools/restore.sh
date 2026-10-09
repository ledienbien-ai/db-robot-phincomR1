#!/bin/bash
# Khôi phục R1 về app chính (aiboxplus) + bật lại watchdog. Chạy khi test xong.
DIR="$(cd "$(dirname "$0")" && pwd)"
PY=/opt/homebrew/anaconda3/envs/xiaozhi/bin/python
$PY "$DIR/r1sh.py" "am force-stop vn.dbrobot.r1; am start -n info.dourok.voicebot/.java.activities.MainActivity 2>&1 | tail -1" 10 2>&1 | grep -aE "Starting|Error"
sleep 6
launchctl load -w ~/Library/LaunchAgents/com.user.robot-r1watchdog.plist 2>/dev/null
echo "đã khôi phục aiboxplus + bật lại watchdog (R1 dùng bình thường lại)"
