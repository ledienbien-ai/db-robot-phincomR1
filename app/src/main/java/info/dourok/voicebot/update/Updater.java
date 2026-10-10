package info.dourok.voicebot.update;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Over-the-air update of the app itself: ask a small JSON manifest whether a newer build exists,
 * download its APK, check it, and install it without anybody touching the speaker.
 *
 * The manifest is one object, published next to the APK by the release workflow:
 * <pre>
 * {"version_code": 9, "version_name": "1.5.0", "apk_url": "https://.../DB-Robot-R1-v1.5.0.apk",
 *  "sha256": "&lt;64 hex&gt;", "size": 15012345, "notes": "what changed"}
 * </pre>
 * "Newer" is decided by {@code version_code} alone -- the same number Android compares, so the
 * panel never offers something {@code pm install -r} would then refuse as a downgrade.
 *
 * Why https is insisted on: the signing key of this app is committed to its public repository (so
 * that every build can install over every other), which means the signature proves nothing about
 * who made an APK. What does is where the manifest came from, and the SHA-256 it carries.
 *
 * The install goes through the device's own adb daemon ({@link AdbLoopback}); see there for why.
 * A successful install kills this very process, so the result is left in a log file that the next
 * start reads back ({@link #readLastInstallLog()}).
 *
 * Plain Java on the JDK plus org.json, so the whole flow can be run off the device.
 */
public final class Updater {

    /** Where things are and who we are; fixed for the life of the process. */
    public static final class Config {
        public String manifestUrl = "";
        public int currentCode;
        public String currentName = "";
        /** Downloaded APK. Must be readable by the adb shell user, so not app-private storage. */
        public File apkFile;
        /** Written by the install shell, read by us. Same constraint. */
        public File logFile;
        /** Where the shell copies the APK before installing; pm reads from here reliably. */
        public String stagingPath = "/data/local/tmp/dbrobot-update.apk";
        public String adbHost = "127.0.0.1";
        public int adbPort = 5555;
        public String packageName = "";
        /** {@code package/activity} handed to {@code am start} once the install is done. */
        public String launchComponent = "";
        public String userAgent = "db-robot-r1";
        public boolean httpsOnly = true;
        public long installTimeoutMs = 10 * 60 * 1000L;
    }

    public static final String IDLE = "idle";
    public static final String CHECKING = "checking";
    public static final String CURRENT = "current";
    public static final String AVAILABLE = "available";
    public static final String DOWNLOADING = "downloading";
    public static final String INSTALLING = "installing";
    public static final String INSTALLED = "installed";
    public static final String ERROR = "error";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MANIFEST_LIMIT = 64 * 1024;
    private static final long APK_LIMIT = 200L * 1024 * 1024;
    private static final int ADB_TIMEOUT_MS = 15_000;
    private static final long LOG_POLL_MS = 2_000;

    private final Config cfg;
    private final Object lock = new Object();

    private String state = IDLE;
    private String error = "";
    private int latestCode;
    private String latestName = "";
    private String notes = "";
    private String apkUrl = "";
    private String sha256 = "";
    private long size;
    private int progress;
    private long checkedMs;
    private String lastResult = "";
    private boolean busy;

    public Updater(Config cfg) {
        this.cfg = cfg;
    }

    // ── Reading the state ────────────────────────────────────────────────

    /** True once a manifest has been read that names a build newer than this one. */
    public boolean isUpdateAvailable() {
        synchronized (lock) {
            return latestCode > cfg.currentCode;
        }
    }

    public boolean isBusy() {
        synchronized (lock) {
            return busy;
        }
    }

    public long lastCheckMs() {
        synchronized (lock) {
            return checkedMs;
        }
    }

    public String latestName() {
        synchronized (lock) {
            return latestName;
        }
    }

    public String toJson() {
        synchronized (lock) {
            try {
                JSONObject o = new JSONObject();
                o.put("state", state);
                o.put("busy", busy);
                o.put("error", error);
                o.put("current_code", cfg.currentCode);
                o.put("current_name", cfg.currentName);
                o.put("latest_code", latestCode);
                o.put("latest_name", latestName);
                o.put("available", latestCode > cfg.currentCode);
                o.put("notes", notes);
                o.put("size", size);
                o.put("progress", progress);
                o.put("checked_ms", checkedMs);
                o.put("last_result", lastResult);
                return o.toString();
            } catch (Exception e) {
                return "{\"state\":\"error\",\"error\":\"json\"}";
            }
        }
    }

    // ── Operations (each blocks; the *Async twins run them on a thread) ──

    /** @return false if another operation is already running. */
    public boolean checkAsync() {
        return runAsync("update-check", new Runnable() {
            @Override
            public void run() {
                doCheck();
            }
        });
    }

    /** Check (again), then download and install if that still says there is something newer. */
    public boolean installAsync() {
        return runAsync("update-install", new Runnable() {
            @Override
            public void run() {
                if (doCheck()) {
                    doInstall();
                }
            }
        });
    }

    /** Blocking twin of {@link #checkAsync()}. @return true if a newer build is on offer. */
    public boolean check() {
        if (!begin()) {
            return false;
        }
        try {
            return doCheck();
        } finally {
            end();
        }
    }

    /** Blocking twin of {@link #installAsync()}. */
    public void install() {
        if (!begin()) {
            return;
        }
        try {
            if (doCheck()) {
                doInstall();
            }
        } finally {
            end();
        }
    }

    /**
     * What the last install shell left behind, if anything: call once at start-up. A successful
     * install ends with this process being replaced, so the log is the only witness.
     */
    public void readLastInstallLog() {
        String log = readSmallFile(cfg.logFile);
        if (log == null) {
            return;
        }
        if (!log.contains("exit=")) {
            return;   // an install is still writing it; leave it alone
        }
        cfg.logFile.delete();
        synchronized (lock) {
            lastResult = log.contains("Success") ? "ok" : failureLine(log);
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private boolean runAsync(String name, final Runnable work) {
        if (!begin()) {
            return false;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    fail("Lỗi không mong đợi: " + e);
                } finally {
                    end();
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return true;
    }

    private boolean begin() {
        synchronized (lock) {
            if (busy) {
                return false;
            }
            busy = true;
            return true;
        }
    }

    private void end() {
        synchronized (lock) {
            busy = false;
        }
    }

    private void set(String newState) {
        synchronized (lock) {
            state = newState;
            if (!ERROR.equals(newState)) {
                error = "";
            }
        }
    }

    private void fail(String message) {
        synchronized (lock) {
            state = ERROR;
            error = message;
        }
    }

    private boolean doCheck() {
        set(CHECKING);
        try {
            requireHttps(cfg.manifestUrl);
            HttpURLConnection c = open(cfg.manifestUrl);
            try {
                int code = c.getResponseCode();
                if (code == 404) {
                    // No release published yet: that is "nothing newer", not a fault.
                    synchronized (lock) {
                        checkedMs = System.currentTimeMillis();
                        state = CURRENT;
                        error = "";
                    }
                    return false;
                }
                if (code != 200) {
                    throw new IOException("máy chủ cập nhật trả về HTTP " + code);
                }
                String body = new String(readAll(c.getInputStream(), MANIFEST_LIMIT),
                        StandardCharsets.UTF_8);
                JSONObject m = new JSONObject(body);
                int newCode = m.optInt("version_code", 0);
                String url = m.optString("apk_url", "");
                String sum = m.optString("sha256", "").trim().toLowerCase(Locale.US);
                if (newCode <= 0 || url.isEmpty() || !sum.matches("[0-9a-f]{64}")) {
                    throw new IOException("tệp update.json thiếu version_code / apk_url / sha256");
                }
                requireHttps(url);
                synchronized (lock) {
                    latestCode = newCode;
                    latestName = m.optString("version_name", String.valueOf(newCode));
                    notes = m.optString("notes", "");
                    apkUrl = url;
                    sha256 = sum;
                    size = m.optLong("size", 0L);
                    checkedMs = System.currentTimeMillis();
                    state = newCode > cfg.currentCode ? AVAILABLE : CURRENT;
                    error = "";
                    return newCode > cfg.currentCode;
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            fail("Không kiểm tra được bản mới: " + describe(e));
            return false;
        }
    }

    private void doInstall() {
        final String url;
        final String expected;
        final long expectedSize;
        synchronized (lock) {
            url = apkUrl;
            expected = sha256;
            expectedSize = size;
            progress = 0;
            state = DOWNLOADING;
            error = "";
        }
        try {
            download(url, expected, expectedSize);
        } catch (Exception e) {
            fail("Tải bản cập nhật thất bại: " + describe(e));
            return;
        }

        set(INSTALLING);
        cfg.logFile.delete();
        try {
            AdbLoopback.shell(cfg.adbHost, cfg.adbPort, installCommand(), ADB_TIMEOUT_MS);
        } catch (IOException e) {
            cfg.apkFile.delete();
            fail("Không gọi được trình cài đặt trên loa (adb " + cfg.adbHost + ":" + cfg.adbPort
                    + "): " + describe(e) + ". Hãy cài bằng bộ cài trên máy tính/điện thoại.");
            return;
        }

        // From here the shell works on its own. If it succeeds this process is killed before the
        // loop below sees anything; what the loop is for is the failure, which leaves us running.
        long deadline = System.currentTimeMillis() + cfg.installTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(LOG_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            String log = readSmallFile(cfg.logFile);
            if (log == null || !log.contains("exit=")) {
                continue;
            }
            cfg.logFile.delete();
            if (log.contains("Success")) {
                set(INSTALLED);
            } else {
                fail("Cài đặt thất bại: " + failureLine(log));
            }
            return;
        }
        fail("Cài đặt chưa xong sau " + (cfg.installTimeoutMs / 60000) + " phút.");
    }

    /**
     * The whole install as one detached shell line.
     *
     * {@code trap '' HUP} and the trailing {@code &}: adbd hangs up on the shell when the
     * connection goes, and ours goes the moment pm kills this app. {@code echo exit=} is the end
     * marker the reader waits for -- pm's own output is not one, it prints nothing until it is
     * done. The copy is made world-readable because it is the package manager, not the shell, that
     * opens it. The app is started again when the install succeeded, and also when it did not but the
     * process is gone anyway (pm can fail after it has already killed the old one).
     */
    String installCommand() {
        String apk = cfg.apkFile.getPath();
        String log = cfg.logFile.getPath();
        String tmp = cfg.stagingPath;
        String start = "am start -n " + cfg.launchComponent;
        return "trap '' HUP; ("
                + "{ cat " + apk + " > " + tmp + " && chmod 644 " + tmp
                + " && pm install -r " + tmp + "; echo \"exit=$?\"; }"
                + " > " + log + " 2>&1; "
                + "rm -f " + tmp + " " + apk + "; "
                + "case \"$(cat " + log + ")\" in "
                + "*Success*) " + start + " ;; "
                + "*) case \"$(ps)\" in *" + cfg.packageName + "*) ;; *) " + start + " ;; esac ;; "
                + "esac"
                + ") < /dev/null > /dev/null 2>&1 &";
    }

    private void download(String url, String expectedSha, long expectedSize) throws Exception {
        File part = new File(cfg.apkFile.getPath() + ".part");
        cfg.apkFile.delete();
        part.delete();
        MessageDigest digest = sha256Digest();
        HttpURLConnection c = open(url);
        long done = 0;
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            long total = expectedSize > 0 ? expectedSize : c.getContentLength();
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(part);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    done += n;
                    if (done > APK_LIMIT) {
                        throw new IOException("tệp quá lớn");
                    }
                    if (total > 0) {
                        int pct = (int) Math.min(100, done * 100 / total);
                        synchronized (lock) {
                            progress = pct;
                        }
                    }
                }
            } finally {
                out.close();
                in.close();
            }
        } catch (Exception e) {
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (expectedSize > 0 && done != expectedSize) {
            part.delete();
            throw new IOException("tải thiếu dữ liệu (" + done + "/" + expectedSize + " byte)");
        }
        String actual = hex(digest.digest());
        if (!actual.equals(expectedSha)) {
            part.delete();
            throw new IOException("tệp tải về không khớp mã kiểm tra SHA-256");
        }
        if (!part.renameTo(cfg.apkFile)) {
            part.delete();
            throw new IOException("không ghi được " + cfg.apkFile);
        }
        // The installer runs as another user (shell); make sure it may read what we wrote.
        cfg.apkFile.setReadable(true, false);
        synchronized (lock) {
            progress = 100;
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);   // release downloads redirect to a storage host
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", cfg.userAgent);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Accept", "*/*");
        return c;
    }

    private void requireHttps(String url) throws IOException {
        if (url == null || url.isEmpty()) {
            throw new IOException("chưa cấu hình địa chỉ cập nhật");
        }
        if (cfg.httpsOnly && !url.toLowerCase(Locale.US).startsWith("https://")) {
            throw new IOException("địa chỉ cập nhật phải là https");
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) {
                    throw new IOException("phản hồi quá lớn");
                }
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** A file expected to hold a few lines; null if it is absent or unreadable. */
    private static String readSmallFile(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        try {
            return new String(readAll(new FileInputStream(f), MANIFEST_LIMIT),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** The line of an install log worth showing: pm's "Failure [...]", else the first line. */
    static String failureLine(String log) {
        String first = "";
        for (String line : log.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Failure")) {
                return t;
            }
            if (first.isEmpty() && !t.isEmpty() && !t.startsWith("exit=") && !t.startsWith("pkg:")) {
                first = t;
            }
        }
        return first.isEmpty() ? "không rõ nguyên nhân" : first;
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        String name = e.getClass().getSimpleName();
        if (e instanceof java.net.UnknownHostException) {
            return "loa chưa vào được Internet (" + m + ")";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "lỗi bảo mật kết nối (" + name + ": " + m + ")";
        }
        return m == null || m.isEmpty() ? name : m;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
