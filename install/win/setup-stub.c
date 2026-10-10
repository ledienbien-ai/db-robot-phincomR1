/*
 * DB-Robot R1 - launcher of the one-file Windows installer (DB-Robot-R1-Setup.exe).
 *
 * The installer is this program with a few files glued to its end (see ../make-setup-exe.py):
 * the install script, the APK and the guide. Double-clicked, it writes those files to
 * %LOCALAPPDATA%\DB-Robot-R1 and runs install.ps1 there in its own console window -- the same
 * script the zip's "Cai-dat-DB-Robot.bat" runs, so both ways of installing behave identically.
 *
 * It does nothing else: no registry, no service, no administrator rights (the manifest asks
 * for "asInvoker", which is also what stops Windows guessing that a file called "...Setup.exe"
 * needs elevation). The folder is kept so adb, which install.ps1 downloads there on first use,
 * is not downloaded again next time.
 *
 * Layout of the glued-on part, all integers little-endian:
 *     "DBR1PAY1"  u32 count  { u16 nameLen, name, u32 size, bytes } * count
 *     "DBR1END1"  u64 offset of "DBR1PAY1" from the start of the file          (last 16 bytes)
 *
 * Messages are plain ASCII on purpose, like install.ps1's: a console's code page cannot be
 * relied on to show Vietnamese diacritics.
 *
 * Build: see build-stub.sh. Only Win32 calls are used so the same source can be exercised on
 * other systems through a small shim (SETUP_STUB_SHIM) in the test.
 */
#ifdef SETUP_STUB_SHIM
#include SETUP_STUB_SHIM
#else
#define WIN32_LEAN_AND_MEAN
#ifndef UNICODE
#define UNICODE
#endif
#include <windows.h>
#endif

#define PATH_CAP   32768            /* the longest path Windows can hand back */
#define NAME_CAP   120
#define MAX_FILES  32
#define CHUNK      (64 * 1024)

static WCHAR selfPath[PATH_CAP];
static WCHAR outDir[PATH_CAP];
static WCHAR filePath[PATH_CAP];
static WCHAR command[PATH_CAP];
static unsigned char chunk[CHUNK];

static void say(const char *text) {
    DWORD written;
    WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), text, (DWORD) lstrlenA(text), &written, NULL);
}

/* Keep the window open until the owner has read what is on it. */
static void waitForEnter(void) {
    HANDLE in = GetStdHandle(STD_INPUT_HANDLE);
    char c;
    DWORD got;
    say("\r\nBam Enter de dong cua so nay...");
    FlushConsoleInputBuffer(in);
    ReadFile(in, &c, 1, &got, NULL);
}

static int fail(const char *what) {
    say("\r\n[LOI] ");
    say(what);
    say("\r\nHay tai ban zip (DB-Robot-R1-Windows.zip) va bam dup Cai-dat-DB-Robot.bat.\r\n");
    waitForEnter();
    return 1;
}

static BOOL readExact(HANDLE file, void *buffer, DWORD count) {
    DWORD done = 0, got;
    while (done < count) {
        if (!ReadFile(file, (char *) buffer + done, count - done, &got, NULL) || got == 0) return FALSE;
        done += got;
    }
    return TRUE;
}

static BOOL writeExact(HANDLE file, const void *buffer, DWORD count) {
    DWORD done = 0, put;
    while (done < count) {
        if (!WriteFile(file, (const char *) buffer + done, count - done, &put, NULL) || put == 0) return FALSE;
        done += put;
    }
    return TRUE;
}

static BOOL sameBytes(const unsigned char *a, const char *b, int n) {
    int i;
    for (i = 0; i < n; i++) if (a[i] != (unsigned char) b[i]) return FALSE;
    return TRUE;
}

static DWORD u32(const unsigned char *p) {
    return (DWORD) p[0] | ((DWORD) p[1] << 8) | ((DWORD) p[2] << 16) | ((DWORD) p[3] << 24);
}

/* dst += src, refusing to run past PATH_CAP. */
static BOOL append(WCHAR *dst, const WCHAR *src) {
    int have = lstrlenW(dst), add = lstrlenW(src);
    if (have + add + 1 > PATH_CAP) return FALSE;
    lstrcpyW(dst + have, src);
    return TRUE;
}

/* A file name from the package may only be a plain name: letters, digits, dot, dash, underscore.
   Nothing that could climb out of the folder or name a device. */
static BOOL plainName(const unsigned char *name, int length) {
    int i;
    if (length < 1 || length > NAME_CAP || name[0] == '.') return FALSE;
    for (i = 0; i < length; i++) {
        unsigned char c = name[i];
        BOOL ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                  || c == '.' || c == '-' || c == '_';
        if (!ok) return FALSE;
    }
    return TRUE;
}

int main(void) {
    HANDLE self, out;
    LARGE_INTEGER size, pos;
    unsigned char head[16], name[NAME_CAP + 1];
    WCHAR wideName[NAME_CAP + 1];
    DWORD count, i, length, got;
    STARTUPINFOW si;
    PROCESS_INFORMATION pi;

    SetConsoleTitleW(L"DB-Robot R1 - Cai dat");
    say("DB-Robot R1 - dang chuan bi bo cai...\r\n");

    /* 1. Find the files glued to the end of this program. */
    length = GetModuleFileNameW(NULL, selfPath, PATH_CAP);
    if (length == 0 || length >= PATH_CAP) return fail("Khong xac dinh duoc vi tri tep cai dat.");
    self = CreateFileW(selfPath, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (self == INVALID_HANDLE_VALUE) return fail("Khong mo duoc tep cai dat.");
    if (!GetFileSizeEx(self, &size) || size.QuadPart < 64) return fail("Tep cai dat bi hong (qua ngan).");
    pos.QuadPart = size.QuadPart - 16;
    if (!SetFilePointerEx(self, pos, NULL, FILE_BEGIN) || !readExact(self, head, 16)
            || !sameBytes(head, "DBR1END1", 8)) {
        return fail("Tep cai dat khong day du - hay tai lai.");
    }
    pos.QuadPart = (LONGLONG) u32(head + 8) | ((LONGLONG) u32(head + 12) << 32);
    if (pos.QuadPart < 1024 || pos.QuadPart > size.QuadPart - 16 - 12) return fail("Tep cai dat bi hong.");
    if (!SetFilePointerEx(self, pos, NULL, FILE_BEGIN) || !readExact(self, head, 12)
            || !sameBytes(head, "DBR1PAY1", 8)) {
        return fail("Tep cai dat bi hong.");
    }
    count = u32(head + 8);
    if (count < 1 || count > MAX_FILES) return fail("Tep cai dat bi hong.");

    /* 2. The folder the files go to. */
    length = GetEnvironmentVariableW(L"LOCALAPPDATA", outDir, PATH_CAP);
    if (length == 0 || length >= PATH_CAP) {
        length = GetTempPathW(PATH_CAP, outDir);
        if (length == 0 || length >= PATH_CAP) return fail("Khong tim duoc thu muc de giai nen.");
        if (outDir[length - 1] == L'\\') outDir[length - 1] = 0;
    }
    if (!append(outDir, L"\\DB-Robot-R1")) return fail("Duong dan thu muc qua dai.");
    if (!CreateDirectoryW(outDir, NULL) && GetLastError() != ERROR_ALREADY_EXISTS) {
        return fail("Khong tao duoc thu muc %LOCALAPPDATA%\\DB-Robot-R1.");
    }

    /* 3. Write them out, replacing what an earlier run left. */
    for (i = 0; i < count; i++) {
        DWORD left, k;
        if (!readExact(self, head, 2)) return fail("Tep cai dat bi hong.");
        length = (DWORD) head[0] | ((DWORD) head[1] << 8);
        if (length < 1 || length > NAME_CAP || !readExact(self, name, length) || !plainName(name, (int) length)) {
            return fail("Tep cai dat bi hong.");
        }
        for (k = 0; k < length; k++) wideName[k] = (WCHAR) name[k];
        wideName[length] = 0;
        if (!readExact(self, head, 4)) return fail("Tep cai dat bi hong.");
        left = u32(head);

        filePath[0] = 0;
        if (!append(filePath, outDir) || !append(filePath, L"\\") || !append(filePath, wideName)) {
            return fail("Duong dan thu muc qua dai.");
        }
        out = CreateFileW(filePath, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
        if (out == INVALID_HANDLE_VALUE) {
            return fail("Khong ghi duoc tep vao thu muc cai dat (mot bo cai khac dang chay?).");
        }
        while (left > 0) {
            got = left < CHUNK ? left : CHUNK;
            if (!readExact(self, chunk, got) || !writeExact(out, chunk, got)) {
                CloseHandle(out);
                return fail("Giai nen that bai - o dia day hoac tep cai dat bi hong.");
            }
            left -= got;
        }
        CloseHandle(out);
    }
    CloseHandle(self);

    /* 4. Run the install script, in this window. Windows PowerShell by its full path: it is part
          of every Windows 10 and 11, and a "powershell.exe" lying next to the download must not
          be the one that runs. */
    command[0] = 0;
    length = GetSystemDirectoryW(filePath, PATH_CAP);
    if (length == 0 || length >= PATH_CAP) return fail("Khong tim thay thu muc he thong cua Windows.");
    if (!append(command, L"\"") || !append(command, filePath)
            || !append(command, L"\\WindowsPowerShell\\v1.0\\powershell.exe\" -NoProfile -ExecutionPolicy Bypass -File \"")
            || !append(command, outDir) || !append(command, L"\\install.ps1\"")) {
        return fail("Duong dan thu muc qua dai.");
    }
    ZeroMemory(&si, sizeof si);
    si.cb = sizeof si;
    ZeroMemory(&pi, sizeof pi);
    if (!CreateProcessW(NULL, command, NULL, NULL, TRUE, 0, NULL, outDir, &si, &pi)) {
        return fail("Khong chay duoc Windows PowerShell.");
    }
    WaitForSingleObject(pi.hProcess, INFINITE);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);

    waitForEnter();
    return 0;
}
