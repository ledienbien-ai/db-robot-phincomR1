package info.dourok.voicebot.net;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The speaker's Wi-Fi, for the control panel: what it is on, what is in range, and moving it to
 * another network.
 *
 * The speaker has no screen and the panel reaches it over the very connection being changed, so a
 * move that goes wrong must undo itself: {@link #connect} remembers the network it started on, and
 * when the new one has not come up within {@link #JOIN_TIMEOUT_MS} it goes back and waits for the
 * old one. For the same reason the network in use can neither be re-keyed nor forgotten from here
 * -- a wrong password typed for it would leave nothing to go back to.
 *
 * Everything is plain {@link WifiManager} as an ordinary app may use it on Android 5.1 (API 22),
 * where any app holding CHANGE_WIFI_STATE may add, change and select networks.
 */
@SuppressLint("MissingPermission")
public final class WifiSetup {

    /** Where progress lines go (the panel's Log drawer). Never given a password. */
    public interface Log {
        void line(String message);
    }

    /** How long a network is given to associate and get an address before the move is undone. */
    static final long JOIN_TIMEOUT_MS = 30_000;
    /** How long the old network is then given to come back. */
    static final long RETURN_TIMEOUT_MS = 25_000;
    private static final long POLL_MS = 500;
    /** Head start given to the "started" reply before the connection it travels on is dropped. */
    private static final long REPLY_GRACE_MS = 800;
    /** A scan is considered running this long after it was asked for; the platform reports no end. */
    private static final long SCAN_WINDOW_MS = 6_000;
    private static final int MAX_NETWORKS = 40;

    private final WifiManager wifi;
    private final Log log;

    private final Object jobLock = new Object();
    private boolean jobRunning;
    private volatile String jobSsid = "";
    /** "", "connecting", "connected" or "failed". */
    private volatile String jobState = "";
    private volatile String jobMessage = "";
    private volatile long jobAtMs;
    private volatile long scanAtMs;

    public WifiSetup(Context context, Log log) {
        this.wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.log = log;
    }

    // ── What the panel shows ─────────────────────────────────────────────────────────────────

    public String stateJson() {
        JSONObject o = new JSONObject();
        try {
            if (wifi == null) return o.put("ok", false).put("error", "Máy này không có Wi-Fi.").toString();
            o.put("ok", true);
            o.put("enabled", wifi.isWifiEnabled());

            WifiInfo info = wifi.getConnectionInfo();
            String current = connectedSsid(info);
            o.put("connected", !current.isEmpty());
            o.put("ssid", current);
            if (!current.isEmpty()) {
                o.put("ip", ipText(info.getIpAddress()));
                o.put("rssi", info.getRssi());
                o.put("bars", bars(info.getRssi()));
                o.put("band", band(info.getFrequency()));
                o.put("link_mbps", info.getLinkSpeed());
            }

            Map<String, WifiConfiguration> saved = savedBySsid();
            long sinceScan = System.currentTimeMillis() - scanAtMs;
            o.put("scanning", scanAtMs != 0 && sinceScan < SCAN_WINDOW_MS);

            // One row per name: the same network is usually heard from several access points and
            // on both bands, and the owner chooses a name, not a radio.
            Map<String, ScanResult> best = new HashMap<String, ScanResult>();
            List<ScanResult> heard = wifi.getScanResults();
            if (heard != null) {
                for (ScanResult r : heard) {
                    if (r == null || r.SSID == null || r.SSID.isEmpty()) continue;
                    ScanResult have = best.get(r.SSID);
                    if (have == null || r.level > have.level) best.put(r.SSID, r);
                }
            }
            List<ScanResult> rows = new ArrayList<ScanResult>(best.values());
            final String cur = current;
            Collections.sort(rows, new Comparator<ScanResult>() {
                @Override public int compare(ScanResult a, ScanResult b) {
                    boolean ca = a.SSID.equals(cur), cb = b.SSID.equals(cur);
                    if (ca != cb) return ca ? -1 : 1;
                    return b.level - a.level;
                }
            });
            JSONArray networks = new JSONArray();
            Set<String> inRange = new HashSet<String>();
            for (ScanResult r : rows) {
                if (networks.length() >= MAX_NETWORKS) break;
                inRange.add(r.SSID);
                networks.put(new JSONObject()
                        .put("ssid", r.SSID)
                        .put("rssi", r.level)
                        .put("bars", bars(r.level))
                        .put("band", band(r.frequency))
                        .put("security", security(r.capabilities))
                        .put("saved", saved.containsKey(r.SSID))
                        .put("current", r.SSID.equals(current)));
            }
            o.put("networks", networks);

            // Remembered networks that are not in range right now, so they can still be forgotten.
            JSONArray away = new JSONArray();
            for (String ssid : saved.keySet()) {
                if (!inRange.contains(ssid) && !ssid.equals(current)) away.put(ssid);
            }
            o.put("saved_away", away);

            o.put("job", new JSONObject()
                    .put("ssid", jobSsid)
                    .put("state", jobState)
                    .put("message", jobMessage)
                    // Seconds since it ended, by this clock -- the panel's may be set differently.
                    .put("age_s", jobAtMs == 0 ? -1 : (System.currentTimeMillis() - jobAtMs) / 1000));
            return o.toString();
        } catch (Exception e) {
            return error("Không đọc được trạng thái Wi-Fi: " + e.getMessage());
        }
    }

    public String scan() {
        if (wifi == null || !wifi.isWifiEnabled()) return error("Wi-Fi đang tắt.");
        try {
            boolean started = wifi.startScan();
            scanAtMs = System.currentTimeMillis();
            return started ? ok() : error("Loa chưa quét được, hãy thử lại sau vài giây.");
        } catch (Exception e) {
            return error("Không quét được: " + e.getMessage());
        }
    }

    // ── Moving to another network ────────────────────────────────────────────────────────────

    /**
     * Body: {@code {"ssid":…,"password":…,"security":"psk"|"wep"|"open","hidden":bool}}. The
     * password may be left empty for a network the speaker already remembers.
     *
     * Only starts the move; the panel reads how it went from {@link #stateJson} ("job"), if it can
     * still reach the speaker afterwards.
     */
    public String connect(String body) {
        final String ssid, password, security;
        final boolean hidden;
        try {
            JSONObject j = new JSONObject(body == null ? "" : body);
            ssid = j.optString("ssid", "");
            password = j.optString("password", "");
            security = j.optString("security", "psk");
            hidden = j.optBoolean("hidden", false);
        } catch (Exception e) {
            return error("Yêu cầu không hợp lệ.");
        }
        if (wifi == null || !wifi.isWifiEnabled()) return error("Wi-Fi đang tắt.");
        String problem = validate(ssid, password, security);
        if (problem != null) return error(problem);

        final WifiConfiguration known = savedBySsid().get(ssid);
        if (ssid.equals(connectedSsid(wifi.getConnectionInfo()))) {
            return error("Loa đang dùng mạng này rồi.");
        }
        if (password.isEmpty() && !"open".equals(security) && known == null) {
            return error("Hãy nhập mật khẩu của mạng này.");
        }
        synchronized (jobLock) {
            if (jobRunning) return error("Loa đang đổi mạng, hãy chờ xong đã.");
            jobRunning = true;
        }
        setJob(ssid, "connecting", "Đang kết nối…");
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // The reply to the panel travels over the connection about to be dropped.
                    Thread.sleep(REPLY_GRACE_MS);
                    join(ssid, password, security, hidden, known);
                } catch (Exception e) {
                    setJob(ssid, "failed", "Lỗi khi đổi mạng: " + e.getMessage());
                } finally {
                    synchronized (jobLock) { jobRunning = false; }
                }
            }
        }, "wifi-join");
        t.setDaemon(true);
        t.start();
        return ok();
    }

    private void join(String ssid, String password, String security, boolean hidden,
                      WifiConfiguration known) throws InterruptedException {
        WifiInfo before = wifi.getConnectionInfo();
        final int oldId = before == null ? -1 : before.getNetworkId();
        final String oldSsid = connectedSsid(before);
        log.line("Wi-Fi: chuyển sang mạng \"" + ssid + "\"" + (oldSsid.isEmpty() ? "" : " (đang ở \"" + oldSsid + "\")"));

        final boolean added;
        int id;
        if (known != null && password.isEmpty()) {
            id = known.networkId;          // remembered network, remembered key
            added = false;
        } else {
            WifiConfiguration c = configuration(ssid, password, security, hidden);
            c.priority = highestPriority() + 1;      // preferred from now on, also after a restart
            if (known != null) {
                c.networkId = known.networkId;
                id = wifi.updateNetwork(c);
                added = false;
            } else {
                id = wifi.addNetwork(c);
                added = true;
            }
        }
        if (id < 0) {
            setJob(ssid, "failed", "Loa không lưu được mạng này (tên hoặc mật khẩu không hợp lệ).");
            return;
        }

        // enableNetwork(…, true) selects this network and switches every other one off; they are
        // switched back on below, whichever way this ends.
        wifi.disconnect();
        boolean selected = wifi.enableNetwork(id, true);
        wifi.reconnect();
        boolean up = selected && waitFor(id, JOIN_TIMEOUT_MS);

        if (up) {
            enableAll();
            wifi.saveConfiguration();
            WifiInfo now = wifi.getConnectionInfo();
            String ip = now == null ? "" : ipText(now.getIpAddress());
            log.line("Wi-Fi: đã vào mạng \"" + ssid + "\", địa chỉ " + ip);
            setJob(ssid, "connected", "Đã kết nối. Địa chỉ của loa: " + ip);
            return;
        }

        log.line("Wi-Fi: không vào được mạng \"" + ssid + "\", quay lại mạng cũ");
        if (added) wifi.removeNetwork(id);
        boolean back = false;
        if (oldId >= 0) {
            wifi.disconnect();
            wifi.enableNetwork(oldId, true);
            wifi.reconnect();
            back = waitFor(oldId, RETURN_TIMEOUT_MS);
        }
        enableAll();
        wifi.saveConfiguration();
        if (!back) wifi.reconnect();
        setJob(ssid, "failed", "Không kết nối được (sai mật khẩu hoặc sóng quá yếu)."
                + (oldSsid.isEmpty() ? "" : " Loa đã quay lại mạng \"" + oldSsid + "\"."));
    }

    /** Body: {@code {"ssid":…}}. Not the network in use: that would cut the speaker off. */
    public String forget(String body) {
        String ssid;
        try {
            ssid = new JSONObject(body == null ? "" : body).optString("ssid", "");
        } catch (Exception e) {
            return error("Yêu cầu không hợp lệ.");
        }
        if (wifi == null) return error("Máy này không có Wi-Fi.");
        if (ssid.isEmpty()) return error("Thiếu tên mạng.");
        if (ssid.equals(connectedSsid(wifi.getConnectionInfo()))) {
            return error("Không xoá được mạng loa đang dùng.");
        }
        synchronized (jobLock) {
            if (jobRunning) return error("Loa đang đổi mạng, hãy chờ xong đã.");
        }
        WifiConfiguration c = savedBySsid().get(ssid);
        if (c == null) return error("Loa không lưu mạng này.");
        boolean removed = wifi.removeNetwork(c.networkId);
        wifi.saveConfiguration();
        if (removed) log.line("Wi-Fi: đã xoá mạng \"" + ssid + "\"");
        return removed ? ok() : error("Loa không xoá được mạng này.");
    }

    // ── Pieces ───────────────────────────────────────────────────────────────────────────────

    /** Null when the three fit together, otherwise what is wrong, for the owner to read. */
    static String validate(String ssid, String password, String security) {
        if (ssid == null || ssid.isEmpty()) return "Thiếu tên mạng.";
        int bytes;
        try {
            bytes = ssid.getBytes("UTF-8").length;
        } catch (java.io.UnsupportedEncodingException e) {
            bytes = ssid.length();
        }
        if (bytes > 32) return "Tên mạng dài quá 32 ký tự.";
        if (ssid.indexOf('"') >= 0) return "Tên mạng có dấu ngoặc kép, loa không nhập được.";
        if ("eap".equals(security)) return "Loa chưa hỗ trợ mạng doanh nghiệp (802.1X).";
        if (password == null || password.isEmpty()) return null;   // remembered key, or open
        if ("psk".equals(security)) {
            int n = password.length();
            boolean rawKey = n == 64 && isHex(password);
            if (!rawKey && (n < 8 || n > 63)) return "Mật khẩu Wi-Fi phải dài từ 8 đến 63 ký tự.";
            if (password.indexOf('"') >= 0) return "Mật khẩu có dấu ngoặc kép, loa không nhập được.";
        } else if ("wep".equals(security)) {
            int n = password.length();
            boolean ascii = n == 5 || n == 13;
            boolean hex = (n == 10 || n == 26) && isHex(password);
            if (!ascii && !hex) return "Khoá WEP phải dài 5 hoặc 13 ký tự (hoặc 10/26 ký tự hex).";
        } else if (!"open".equals(security)) {
            return "Kiểu bảo mật không hợp lệ.";
        }
        return null;
    }

    static WifiConfiguration configuration(String ssid, String password, String security, boolean hidden) {
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = "\"" + ssid + "\"";
        c.hiddenSSID = hidden;
        c.status = WifiConfiguration.Status.ENABLED;
        if ("open".equals(security)) {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else if ("wep".equals(security)) {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
            int n = password.length();
            c.wepKeys[0] = ((n == 10 || n == 26) && isHex(password)) ? password : "\"" + password + "\"";
            c.wepTxKeyIndex = 0;
        } else {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            // 64 hex digits are the key itself and go in bare; anything else is a passphrase.
            c.preSharedKey = (password.length() == 64 && isHex(password)) ? password : "\"" + password + "\"";
        }
        return c;
    }

    /** "open", "wep", "psk" or "eap", from what an access point advertises. */
    static String security(String capabilities) {
        String c = capabilities == null ? "" : capabilities;
        if (c.contains("EAP")) return "eap";
        if (c.contains("PSK")) return "psk";
        if (c.contains("WEP")) return "wep";
        return "open";
    }

    static int bars(int rssi) {
        return WifiManager.calculateSignalLevel(rssi, 5);     // 0..4
    }

    static String band(int mhz) {
        return mhz >= 4900 ? "5" : mhz > 0 ? "2.4" : "";
    }

    static String ipText(int ip) {
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
    }

    /** A name as the platform writes it ({@code "name"} in quotes) without the quotes. */
    static String unquote(String ssid) {
        if (ssid == null) return "";
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return !s.isEmpty();
    }

    /** The name of the network the speaker is on and has an address in; "" when on none. */
    private static String connectedSsid(WifiInfo info) {
        if (info == null || info.getNetworkId() < 0) return "";
        if (info.getSupplicantState() != SupplicantState.COMPLETED) return "";
        String ssid = unquote(info.getSSID());
        return "<unknown ssid>".equals(ssid) ? "" : ssid;
    }

    private Map<String, WifiConfiguration> savedBySsid() {
        Map<String, WifiConfiguration> out = new HashMap<String, WifiConfiguration>();
        List<WifiConfiguration> all = wifi.getConfiguredNetworks();
        if (all != null) {
            for (WifiConfiguration c : all) {
                if (c != null && c.SSID != null) out.put(unquote(c.SSID), c);
            }
        }
        return out;
    }

    private int highestPriority() {
        int top = 0;
        List<WifiConfiguration> all = wifi.getConfiguredNetworks();
        if (all != null) {
            for (WifiConfiguration c : all) {
                if (c != null && c.priority > top) top = c.priority;
            }
        }
        return top;
    }

    /** Switch every remembered network back on, so the speaker can fall back to any of them. */
    private void enableAll() {
        List<WifiConfiguration> all = wifi.getConfiguredNetworks();
        if (all == null) return;
        for (WifiConfiguration c : all) {
            if (c != null) wifi.enableNetwork(c.networkId, false);
        }
    }

    /** True once the speaker is on network {@code id} with an address, within {@code timeoutMs}. */
    private boolean waitFor(int id, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS);
            WifiInfo info = wifi.getConnectionInfo();
            if (info != null && info.getNetworkId() == id
                    && info.getSupplicantState() == SupplicantState.COMPLETED
                    && info.getIpAddress() != 0) {
                return true;
            }
        }
        return false;
    }

    private void setJob(String ssid, String state, String message) {
        jobSsid = ssid;
        jobState = state;
        jobMessage = message;
        jobAtMs = System.currentTimeMillis();
    }

    private static String ok() {
        return "{\"ok\":true}";
    }

    private static String error(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message).toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }
}
