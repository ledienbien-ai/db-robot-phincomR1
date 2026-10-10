#!/usr/bin/env bash
# Rebuild setup-stub.exe, the launcher inside DB-Robot-R1-Setup.exe (see setup-stub.c).
#
# The built file is kept in the repository, so a release never needs a compiler: packaging only
# glues the release's files to it (../make-setup-exe.py). Run this only after changing
# setup-stub.c, the .rc, the manifest or the icon, then commit the new setup-stub.exe.
#
# Needs the Zig compiler, which cross-compiles to Windows from any system:
#     pip install ziglang        (then ZIG="python3 -m ziglang")
# or a zig binary from https://ziglang.org/download/ on PATH.
set -euo pipefail
cd "$(dirname "$0")"
ZIG="${ZIG:-zig}"
command -v ${ZIG%% *} >/dev/null 2>&1 || ZIG="python3 -m ziglang"
$ZIG cc -target x86_64-windows-gnu -Os -s -Wall -Wextra -o setup-stub.exe setup-stub.c setup-stub.rc
rm -f setup-stub.pdb setup-stub.lib
ls -l setup-stub.exe
