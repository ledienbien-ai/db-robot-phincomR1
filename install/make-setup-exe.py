#!/usr/bin/env python3
"""Glue the release's files onto the launcher to make the one-file Windows installer.

    make-setup-exe.py <launcher.exe> <output.exe> <name>=<path> [<name>=<path> ...]

<name> is what the file is called once unpacked on the user's machine. The launcher
(win/setup-stub.exe, built from win/setup-stub.c) reads this layout from its own end:

    "DBR1PAY1"  u32 count  { u16 nameLen, name, u32 size, bytes } * count
    "DBR1END1"  u64 offset of "DBR1PAY1" from the start of the file

Nothing is compressed: the APK, which is nearly all of it, is already a zip.
"""
import re
import struct
import sys

MAX_FILES = 32          # the launcher refuses more
NAME = re.compile(r"^[A-Za-z0-9_-][A-Za-z0-9._-]{0,119}$")   # and any name but a plain one


def main(argv):
    if len(argv) < 4:
        sys.exit(__doc__)
    stub_path, out_path, specs = argv[1], argv[2], argv[3:]
    if len(specs) > MAX_FILES:
        sys.exit("too many files: %d (the launcher takes %d)" % (len(specs), MAX_FILES))

    with open(stub_path, "rb") as f:
        stub = f.read()
    if stub[:2] != b"MZ" or len(stub) < 1024:
        sys.exit("%s is not a Windows program" % stub_path)
    if b"DBR1END1" in stub[-16:]:
        sys.exit("%s already has files glued to it; use the bare launcher" % stub_path)

    body = [b"DBR1PAY1", struct.pack("<I", len(specs))]
    seen = set()
    for spec in specs:
        name, sep, path = spec.partition("=")
        if not sep or not NAME.match(name):
            sys.exit("bad file spec %r: want <plain-name>=<path>" % spec)
        if name.lower() in seen:
            sys.exit("file name given twice: %s" % name)
        seen.add(name.lower())
        with open(path, "rb") as f:
            data = f.read()
        if len(data) >= 2 ** 32:
            sys.exit("%s is too large" % path)
        encoded = name.encode("ascii")
        body += [struct.pack("<H", len(encoded)), encoded, struct.pack("<I", len(data)), data]

    with open(out_path, "wb") as f:
        f.write(stub)
        for part in body:
            f.write(part)
        f.write(b"DBR1END1" + struct.pack("<Q", len(stub)))
    total = len(stub) + sum(len(p) for p in body) + 16
    print("%s: %d files, %.1f MB" % (out_path, len(specs), total / 1e6))


if __name__ == "__main__":
    main(sys.argv)
