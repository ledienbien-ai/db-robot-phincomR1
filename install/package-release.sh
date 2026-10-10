#!/usr/bin/env bash
# Gom các tệp của một bản phát hành vào thư mục release/ -- chạy bởi .github/workflows/build-apk.yml.
#
#   Cần:  NAME (versionName), CODE (versionCode), TAG (v<NAME>) và tệp DB-Robot-R1-v<NAME>.apk
#   Ra:   release/DB-Robot-R1-v<NAME>.apk   bản có số phiên bản
#         release/DB-Robot-R1.apk           cùng tệp, tên cố định để bộ cài tải "bản mới nhất"
#         release/update.json               loa đọc tệp này để biết có bản mới (xem update/Updater.java)
#         release/install.sh, install.ps1   bộ cài cho Termux/macOS/Linux và Windows
#         release/DB-Robot-R1-Setup.exe     bộ cài Windows một tệp: bấm đúp là chạy
#         release/DB-Robot-R1-Windows.zip   cùng bộ cài ở dạng zip (dự phòng khi tệp .exe bị chặn)
#         release-body.md                   nội dung trang Release
set -euo pipefail

: "${NAME:?thiếu NAME}" "${CODE:?thiếu CODE}" "${TAG:?thiếu TAG}"
REPO="${GITHUB_REPOSITORY:-ledienbien-ai/db-robot-phincomR1}"
APK="DB-Robot-R1-v${NAME}.apk"
[ -f "$APK" ] || { echo "::error::Không thấy $APK"; exit 1; }

# versionCode phải lớn hơn bản đang phát hành: loa chỉ so con số này. Quên tăng nó thì bản mới ra
# mà không loa nào thấy. (Phát hành lại đúng phiên bản cũ thì được -- chỉ thay tệp.)
PREV="$(curl -fsSL --max-time 30 "https://github.com/$REPO/releases/latest/download/update.json" 2>/dev/null || true)"
if [ -n "$PREV" ]; then
  PREV="$PREV" python3 - <<'PY'
import json, os, sys
try:
    prev = json.loads(os.environ["PREV"])
except ValueError:
    sys.exit(0)          # không đọc được bản cũ: không có gì để so
code, name = int(os.environ["CODE"]), os.environ["NAME"]
if prev.get("version_name") != name and int(prev.get("version_code", 0)) >= code:
    print("::error::versionCode %d không lớn hơn bản đang phát hành (v%s = %s). "
          "Hãy tăng versionCode trong app/build.gradle.kts."
          % (code, prev.get("version_name"), prev.get("version_code")))
    sys.exit(1)
PY
fi

rm -rf release win
mkdir -p release win

# Ghi chú của bản này: đoạn nằm dưới dòng "## v<NAME>" trong RELEASE_NOTES.md.
NOTES=""
if [ -f RELEASE_NOTES.md ]; then
  NOTES="$(awk -v head="## v$NAME" '$0 == head {on = 1; next} /^## / {on = 0} on' RELEASE_NOTES.md \
    | sed -e 's/[[:space:]]*$//' | sed -e '/./,$!d')"
fi
[ -n "$NOTES" ] || NOTES="Bản cập nhật v$NAME."

cp "$APK" "release/$APK"
cp "$APK" "release/DB-Robot-R1.apk"

NOTES="$NOTES" python3 - <<'PY'
import hashlib, json, os
name, code, tag = os.environ["NAME"], int(os.environ["CODE"]), os.environ["TAG"]
repo = os.environ.get("GITHUB_REPOSITORY", "ledienbien-ai/db-robot-phincomR1")
apk = "DB-Robot-R1-v%s.apk" % name
data = open(os.path.join("release", apk), "rb").read()
manifest = {
    "version_code": code,
    "version_name": name,
    "apk_url": "https://github.com/%s/releases/download/%s/%s" % (repo, tag, apk),
    "sha256": hashlib.sha256(data).hexdigest(),
    "size": len(data),
    "notes": os.environ["NOTES"].strip()[:800],
}
with open("release/update.json", "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(json.dumps(manifest, ensure_ascii=False, indent=2))
PY

# Bộ cài. Git trên Windows có thể đã đổi kiểu xuống dòng, nên ép lại ở đây: script cho bash phải
# là LF (CRLF làm bash báo lỗi ngay dòng đầu), còn .bat/.ps1 theo kiểu Windows là CRLF.
sed -e 's/\r$//' install/install.sh > release/install.sh
sed -e 's/\r$//' -e 's/$/\r/' install/install.ps1 > release/install.ps1

cp "$APK" win/
cp release/install.ps1 win/install.ps1
sed -e 's/\r$//' -e 's/$/\r/' install/Cai-dat-DB-Robot.bat > win/Cai-dat-DB-Robot.bat
# BOM ở đầu để Notepad cũ hiển thị đúng tiếng Việt có dấu.
{ printf '\xEF\xBB\xBF'; sed -e 's/\r$//' -e 's/$/\r/' install/HUONG_DAN_CAI_DAT.md; } > win/HUONG-DAN.txt
( cd win && zip -q -9 ../release/DB-Robot-R1-Windows.zip ./* )

# Bộ cài một tệp: chương trình khởi chạy dựng sẵn (install/win/setup-stub.exe, mã nguồn nằm cạnh
# nó) với các tệp của bản này dán vào cuối. Khi chạy, nó chép các tệp đó ra
# %LOCALAPPDATA%\DB-Robot-R1 rồi chạy install.ps1 -- đúng script mà tệp .bat trong bản zip chạy.
# APK mang tên cố định để mỗi lần chạy ghi đè bản cũ trong thư mục đó.
python3 install/make-setup-exe.py install/win/setup-stub.exe release/DB-Robot-R1-Setup.exe \
  "install.ps1=release/install.ps1" "DB-Robot-R1.apk=$APK" "HUONG-DAN.txt=win/HUONG-DAN.txt"

{
  echo "## Có gì mới"
  echo
  echo "$NOTES"
  echo
  echo "---"
  echo
  sed -e 's/\r$//' install/HUONG_DAN_CAI_DAT.md
} > release-body.md

ls -l release
