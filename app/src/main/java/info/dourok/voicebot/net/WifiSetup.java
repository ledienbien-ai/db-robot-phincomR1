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
 * The speaker's Wi-Fi, for the control panel and the installer: what it is on, what is in range,
 * and moving it to another network.
 *
 * The speaker has no screen and is reached over the very connection being changed, so nothing here
 * may leave it unreachable:
 *
 * <ul>
 * <li>A move to another network that has not come up within {@link Timing#joinMs} is undone: back
 *     to the network it started on. The network in use can neither be re-keyed nor forgotten.</li>
 * <li>A speaker on no network at all (fresh from the box, a new router, a changed password) is put
 *     into setup mode by its owner: holding the button on top for five seconds makes the speaker's
 *     firmware broadcast a Wi-Fi network of its own ("Phicomm R1"), on which the speaker is
 *     {@value #HOTSPOT_IP}. The panel opened there shows this same Wi-Fi card. A move from that
 *     state that fails brings the same hotspot back, so the owner can try again without touching
 *     the speaker.</li>
 * </ul>
 *
 * On this Android (5.1, API 22) the radio is either a client or an access point, never both. That
 * is why, in setup mode, looking for networks interrupts the hotspot for a few seconds, and why
 * the list shown is the one taken during that pause.
 *
 * Client mode is plain {@link WifiManager}; any app holding CHANGE_WIFI_STATE may add and select
 * networks on API 22. The access-point switch is the platform's tethering switch, which the SDK
 * hides, so it is called by name (see {@link #setAp}). This class never starts a hotspot that was
 * not on before: it only stops the one it finds and, when a move fails, puts that one back.
 */
@SuppressLint("MissingPermission")
public final class WifiSetup {

    /** What this needs from the app around it. */
    public interface Host {
        /** A progress line for the panel's Log drawer. Never given a password. */
        void line(String message);
    }

    /** Every wait in one place; a test shortens them. */
    public static final class Timing {
        /** A network gets this long to associate and get an address before the move is undone. */
        public long joinMs = 30_000;
        /** The old network then gets this long to come back. */
        public long returnMs = 25_000;
        public long pollMs = 500;
        /** Head start for the "started" reply before the connection it travels on is dropped. */
        public long replyGraceMs = 800;
        /** A scan is shown as running this long; the platform reports no end. */
        public long scanWindowMs = 6_000;
        /** How long a scan is given before its results are read. */
        public long scanWaitMs = 6_000;
        /** Switching the radio between off, client and access point. */
        public long radioMs = 12_000;
    }

    public final Timing timing = new Timing();

    /** The address every Android of this generation gives itself as an access point. */
    public static final String HOTSPOT_IP = "192.168.43.1";
    private static final int MAX_NETWORKS = 40;

    /** One network as heard in a scan, kept for when the radio can no longer listen. */
    private static final class Heard {
        final String ssid, capabilities;
        final int level, frequency;

        Heard(String ssid, int level, int frequency, String capabilities) {
            this.ssid = ssid;
            this.level = level;
            this.frequency = frequency;
            this.capabilities = capabilities;
        }
    }

    private final WifiManager wifi;
    private final Host host;

    /** One radio operation at a time: a move, or a look around from setup mode. */
    private final Object jobLock = new Object();
    private boolean jobRunning;

    private volatile String jobSsid = "";
    /** "", "connecting", "connected" or "failed". */
    private volatile String jobState = "";
    private volatile String jobMessage = "";
    private volatile long jobAt;
    private volatile long scanAt;

    private volatile List<Heard> heard = new ArrayList<Heard>();
    private volatile long heardAt;
    private volatile Set<String> savedNames = new HashSet<String>();

    /** The hotspot as it was when this class last switched it off, to switch the same one back on. */
    private volatile WifiConfiguration lastAp;
    /** Why the hotspot is on again after a move away from it; "" otherwise. */
    private volatile String apNote = "";

    public WifiSetup(Context context, Host host) {
        this.wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.host = host;
    }

    /** Monotonic milliseconds. The wall clock jumps when the speaker gets the time after booting. */
    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    public boolean isHotspotOn() {
        return wifi != null && apOn();
    }

    // ── What the panel shows ─────────────────────────────────────────────────────────────────

    public String stateJson() {
        JSONObject o = new JSONObject();
        try {
            if (wifi == null) return o.put("ok", false).put("error", "Máy này không có Wi-Fi.").toString();
            boolean ap = apOn();
            o.put("ok", true);
            o.put("enabled", wifi.isWifiEnabled());
            o.put("ap", ap);
            o.put("ap_ssid", ap ? apName() : "");
            o.put("ap_ip", HOTSPOT_IP);
            o.put("ap_note", ap ? apNote : "");
            o.put("busy", isBusy());

            WifiInfo info = ap ? null : wifi.getConnectionInfo();
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

            Set<String> saved;
            List<Heard> rows;
            if (ap) {
                // The radio is busy being an access point: show what was heard the last time it
                // could listen (a look around from this mode, or a move that failed).
                saved = savedNames;
                rows = new ArrayList<Heard>(heard);
                o.put("scanning", false);
                o.put("scan_age_s", heardAt == 0 ? -1 : (now() - heardAt) / 1000);
            } else {
                saved = savedBySsid().keySet();
                rows = listen();
                o.put("scanning", scanAt != 0 && now() - scanAt < timing.scanWindowMs);
            }
            final String cur = current;
            Collections.sort(rows, new Comparator<Heard>() {
                @Override public int compare(Heard a, Heard b) {
                    boolean ca = a.ssid.equals(cur), cb = b.ssid.equals(cur);
                    if (ca != cb) return ca ? -1 : 1;
                    return b.level - a.level;
                }
            });
            JSONArray networks = new JSONArray();
            Set<String> inRange = new HashSet<String>();
            for (Heard r : rows) {
                if (networks.length() >= MAX_NETWORKS) break;
                inRange.add(r.ssid);
                networks.put(new JSONObject()
                        .put("ssid", r.ssid)
                        .put("rssi", r.level)
                        .put("bars", bars(r.level))
                        .put("band", band(r.frequency))
                        .put("security", security(r.capabilities))
                        .put("saved", saved.contains(r.ssid))
                        .put("current", r.ssid.equals(current)));
            }
            o.put("networks", networks);

            // Remembered networks that are not in range right now, so they can still be forgotten.
            JSONArray away = new JSONArray();
            if (!ap) {
                for (String ssid : saved) {
                    if (!inRange.contains(ssid) && !ssid.equals(current)) away.put(ssid);
                }
            }
            o.put("saved_away", away);

            o.put("job", new JSONObject()
                    .put("ssid", jobSsid)
                    .put("state", jobState)
                    .put("message", jobMessage)
                    // Seconds since it ended, by this clock -- the panel's may be set differently.
                    .put("age_s", jobAt == 0 ? -1 : (now() - jobAt) / 1000));
            return o.toString();
        } catch (Exception e) {
            return error("Không đọc được trạng thái Wi-Fi: " + e.getMessage());
        }
    }

    /**
     * The networks in range as plain lines "security TAB bars TAB name", strongest first -- for
     * the installer scripts, which have no JSON parser to hand.
     */
    public String listText() {
        if (wifi == null) return "";
        List<Heard> rows = apOn() ? new ArrayList<Heard>(heard) : listen();
        Collections.sort(rows, new Comparator<Heard>() {
            @Override public int compare(Heard a, Heard b) {
                return b.level - a.level;
            }
        });
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (Heard r : rows) {
            if (n++ >= MAX_NETWORKS) break;
            if (r.ssid.indexOf('\n') >= 0 || r.ssid.indexOf('\t') >= 0) continue;
            out.append(security(r.capabilities)).append('\t').append(bars(r.level)).append('\t')
                    .append(r.ssid).append('\n');
        }
        return out.toString();
    }

    public String scan() {
        if (wifi == null) return error("Máy này không có Wi-Fi.");
        if (apOn()) {
            // Listening means not broadcasting for a few seconds; whoever is on the hotspot is
            // dropped and has to come back. The panel says so before asking.
            return runJob("wifi-rescan", new Runnable() {
                @Override public void run() {
                    host.line("Wi-Fi: tạm ngừng phát để tìm các mạng xung quanh");
                    stopAp();
                    if (enableClient()) {
                        wifi.startScan();
                        pause(timing.scanWaitMs);
                        remember();
                    }
                    restoreAp("");
                }
            }, "{\"ok\":true,\"interrupts\":true}");
        }
        if (!wifi.isWifiEnabled()) return error("Wi-Fi đang tắt.");
        try {
            boolean started = wifi.startScan();
            scanAt = now();
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
        if (wifi == null) return error("Máy này không có Wi-Fi.");
        String problem = validate(ssid, password, security);
        if (problem != null) return error(problem);

        final boolean fromHotspot = apOn();
        final WifiConfiguration known;
        if (fromHotspot) {
            known = null;                       // remembered networks cannot be read in this mode
            if (password.isEmpty() && !"open".equals(security) && !savedNames.contains(ssid)) {
                return error("Hãy nhập mật khẩu của mạng này.");
            }
        } else {
            if (!wifi.isWifiEnabled()) return error("Wi-Fi đang tắt.");
            known = savedBySsid().get(ssid);
            if (ssid.equals(connectedSsid(wifi.getConnectionInfo()))) {
                return error("Loa đang dùng mạng này rồi.");
            }
            if (password.isEmpty() && !"open".equals(security) && known == null) {
                return error("Hãy nhập mật khẩu của mạng này.");
            }
        }
        String refused = runJob("wifi-join", new Runnable() {
            @Override public void run() {
                try {
                    if (fromHotspot) joinFromHotspot(ssid, password, security, hidden);
                    else join(ssid, password, security, hidden, known);
                } catch (Exception e) {
                    setJob(ssid, "failed", "Lỗi khi đổi mạng: " + e.getMessage());
                }
            }
        }, null);
        if (refused != null) return refused;
        setJob(ssid, "connecting", "Đang kết nối…");
        return ok();
    }

    /** From one network to another: on failure, back to the first. */
    private void join(String ssid, String password, String security, boolean hidden,
                      WifiConfiguration known) {
        WifiInfo before = wifi.getConnectionInfo();
        final int oldId = before == null ? -1 : before.getNetworkId();
        final String oldSsid = connectedSsid(before);
        host.line("Wi-Fi: chuyển sang mạng \"" + ssid + "\"" + (oldSsid.isEmpty() ? "" : " (đang ở \"" + oldSsid + "\")"));

        boolean[] added = new boolean[1];
        int id = prepare(ssid, password, security, hidden, known, added);
        if (id < 0) {
            setJob(ssid, "failed", "Loa không lưu được mạng này (tên hoặc mật khẩu không hợp lệ).");
            return;
        }
        if (select(id)) {
            arrived(ssid);
            return;
        }

        host.line("Wi-Fi: không vào được mạng \"" + ssid + "\", quay lại mạng cũ");
        if (added[0]) wifi.removeNetwork(id);
        boolean back = false;
        if (oldId >= 0) {
            wifi.disconnect();
            wifi.enableNetwork(oldId, true);
            wifi.reconnect();
            back = waitFor(oldId, timing.returnMs);
        }
        enableAll();
        wifi.saveConfiguration();
        if (!back) wifi.reconnect();
        setJob(ssid, "failed", "Không kết nối được (sai mật khẩu hoặc sóng quá yếu)."
                + (oldSsid.isEmpty() ? "" : " Loa đã quay lại mạng \"" + oldSsid + "\"."));
    }

    /** From setup mode (the speaker's hotspot) to a network: on failure, the same hotspot again. */
    private void joinFromHotspot(String ssid, String password, String security, boolean hidden) {
        String hotspot = apName();
        host.line("Wi-Fi: ngừng phát" + (hotspot.isEmpty() ? "" : " \"" + hotspot + "\"") + " để vào mạng \"" + ssid + "\"");
        stopAp();
        if (!enableClient()) {
            setJob(ssid, "failed", "Loa không bật lại được Wi-Fi.");
            restoreAp("không bật được Wi-Fi");
            return;
        }
        // Listen while the radio can: if this fails, the hotspot that comes back has a list to show.
        wifi.startScan();
        boolean[] added = new boolean[1];
        int id = prepare(ssid, password, security, hidden, savedBySsid().get(ssid), added);
        if (id < 0) {
            setJob(ssid, "failed", password.isEmpty()
                    ? "Loa không còn nhớ mật khẩu của mạng này, hãy nhập lại."
                    : "Loa không lưu được mạng này (tên hoặc mật khẩu không hợp lệ).");
            pause(timing.scanWaitMs);
            remember();
            restoreAp("không lưu được mạng");
            return;
        }
        if (select(id)) {
            arrived(ssid);
            return;
        }
        host.line("Wi-Fi: không vào được mạng \"" + ssid + "\", phát lại Wi-Fi của loa");
        if (added[0]) wifi.removeNetwork(id);
        enableAll();
        wifi.saveConfiguration();
        remember();
        boolean back = restoreAp("không vào được mạng \"" + ssid + "\"");
        setJob(ssid, "failed", "Không kết nối được (sai mật khẩu hoặc sóng quá yếu). " + (back
                ? "Loa phát lại mạng" + (hotspot.isEmpty() ? " của nó" : " \"" + hotspot + "\"") + " để bạn thử lại."
                : "Hãy giữ nút trên đỉnh loa 5 giây để loa phát lại Wi-Fi rồi thử lại."));
    }

    /** Store the network; its id, or -1. {@code added[0]} says it was not known before. */
    private int prepare(String ssid, String password, String security, boolean hidden,
                        WifiConfiguration known, boolean[] added) {
        added[0] = false;
        if (known != null && password.isEmpty()) return known.networkId;   // remembered key
        if (known == null && password.isEmpty() && !"open".equals(security)) return -1;
        WifiConfiguration c = configuration(ssid, password, security, hidden);
        c.priority = highestPriority() + 1;      // preferred from now on, also after a restart
        if (known != null) {
            c.networkId = known.networkId;
            return wifi.updateNetwork(c);
        }
        added[0] = true;
        return wifi.addNetwork(c);
    }

    /** Switch to network {@code id} and wait for it. Every other one is switched off meanwhile. */
    private boolean select(int id) {
        wifi.disconnect();
        boolean selected = wifi.enableNetwork(id, true);
        wifi.reconnect();
        return selected && waitFor(id, timing.joinMs);
    }

    private void arrived(String ssid) {
        enableAll();
        wifi.saveConfiguration();
        WifiInfo here = wifi.getConnectionInfo();
        String ip = here == null ? "" : ipText(here.getIpAddress());
        apNote = "";
        host.line("Wi-Fi: đã vào mạng \"" + ssid + "\", địa chỉ " + ip);
        setJob(ssid, "connected", "Đã kết nối. Địa chỉ của loa: " + ip);
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
        if (apOn()) return error("Chỉ xoá được mạng đã lưu khi loa đang ở trong một mạng Wi-Fi.");
        if (ssid.equals(connectedSsid(wifi.getConnectionInfo()))) {
            return error("Không xoá được mạng loa đang dùng.");
        }
        if (isBusy()) return error("Loa đang đổi mạng, hãy chờ xong đã.");
        WifiConfiguration c = savedBySsid().get(ssid);
        if (c == null) return error("Loa không lưu mạng này.");
        boolean removed = wifi.removeNetwork(c.networkId);
        wifi.saveConfiguration();
        if (removed) host.line("Wi-Fi: đã xoá mạng \"" + ssid + "\"");
        return removed ? ok() : error("Loa không xoá được mạng này.");
    }

    // ── Setup mode: the hotspot the speaker's firmware starts ────────────────────────────────

    /** Switch the hotspot off, keeping what it was so that {@link #restoreAp} can bring it back. */
    private void stopAp() {
        WifiConfiguration was = apConfiguration();
        if (was != null) lastAp = was;
        setAp(null, false);
        long deadline = now() + timing.radioMs;
        while (apOn() && now() < deadline) pause(timing.pollMs);
    }

    /**
     * Switch back on the hotspot {@link #stopAp} switched off. With no record of it the platform
     * uses the one it has stored, which is the one that was on. If it will not start, the client
     * radio is left on: never neither.
     */
    private boolean restoreAp(String why) {
        if (wifi.isWifiEnabled()) {
            wifi.setWifiEnabled(false);
            waitRadio(false);
        }
        boolean asked = setAp(lastAp, true);
        long deadline = now() + timing.radioMs;
        while (asked && !apOn() && now() < deadline) pause(timing.pollMs);
        if (asked && apOn()) {
            apNote = why;
            String name = apName();
            host.line("Wi-Fi: đang phát lại mạng" + (name.isEmpty() ? " của loa" : " \"" + name + "\"")
                    + (why.isEmpty() ? "" : " (" + why + ")") + ". Trang điều khiển: http://" + HOTSPOT_IP + ":8088");
            return true;
        }
        host.line("Wi-Fi: không phát lại được Wi-Fi của loa, bật lại Wi-Fi thường");
        setAp(null, false);
        enableClient();
        return false;
    }

    /** Turn the client radio on and wait for it; true once it is. */
    private boolean enableClient() {
        if (!wifi.isWifiEnabled()) wifi.setWifiEnabled(true);
        return waitRadio(true);
    }

    private boolean waitRadio(boolean on) {
        long deadline = now() + timing.radioMs;
        while (wifi.isWifiEnabled() != on && now() < deadline) pause(timing.pollMs);
        return wifi.isWifiEnabled() == on;
    }

    /** Keep what the radio can tell now, for when it can no longer listen. */
    private void remember() {
        try {
            if (!wifi.isWifiEnabled()) return;
            List<Heard> now = listen();
            if (!now.isEmpty() || heardAt == 0) {
                heard = now;
                heardAt = now();
            }
            Set<String> names = new HashSet<String>(savedBySsid().keySet());
            if (!names.isEmpty() || savedNames.isEmpty()) savedNames = names;
        } catch (Exception ignored) {
        }
    }

    /** One row per name: the strongest access point of each network in range. */
    private List<Heard> listen() {
        Map<String, Heard> best = new HashMap<String, Heard>();
        List<ScanResult> results = wifi.getScanResults();
        if (results != null) {
            for (ScanResult r : results) {
                if (r == null || r.SSID == null || r.SSID.isEmpty()) continue;
                Heard have = best.get(r.SSID);
                if (have == null || r.level > have.level) {
                    best.put(r.SSID, new Heard(r.SSID, r.level, r.frequency, r.capabilities));
                }
            }
        }
        return new ArrayList<Heard>(best.values());
    }

    // The access-point switch is not in the SDK. These are its names on API 22.

    private boolean apOn() {
        try {
            Object on = wifi.getClass().getMethod("isWifiApEnabled").invoke(wifi);
            return Boolean.TRUE.equals(on);
        } catch (Throwable t) {
            return false;
        }
    }

    private WifiConfiguration apConfiguration() {
        try {
            Object c = wifi.getClass().getMethod("getWifiApConfiguration").invoke(wifi);
            return c instanceof WifiConfiguration ? (WifiConfiguration) c : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The hotspot's name; "" when the platform will not say. */
    private String apName() {
        WifiConfiguration c = apConfiguration();
        return c == null || c.SSID == null ? "" : unquote(c.SSID);
    }

    private boolean setAp(WifiConfiguration config, boolean on) {
        try {
            Object done = wifi.getClass()
                    .getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class)
                    .invoke(wifi, config, on);
            return Boolean.TRUE.equals(done);
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            host.line("Wi-Fi: máy không cho " + (on ? "bật" : "tắt") + " chế độ phát Wi-Fi (" + cause + ")");
            return false;
        }
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
    private boolean waitFor(int id, long timeoutMs) {
        long deadline = now() + timeoutMs;
        while (now() < deadline) {
            pause(timing.pollMs);
            WifiInfo info = wifi.getConnectionInfo();
            if (info != null && info.getNetworkId() == id
                    && info.getSupplicantState() == SupplicantState.COMPLETED
                    && info.getIpAddress() != 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isBusy() {
        synchronized (jobLock) {
            return jobRunning;
        }
    }

    /**
     * Run one radio operation in the background, unless another is running.
     *
     * @return {@code accepted} when it was started, otherwise the refusal as JSON
     */
    private String runJob(String name, final Runnable work, String accepted) {
        synchronized (jobLock) {
            if (jobRunning) return error("Loa đang đổi mạng, hãy chờ xong đã.");
            jobRunning = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // The reply to whoever asked travels over the connection about to be dropped.
                    pause(timing.replyGraceMs);
                    work.run();
                } catch (Throwable e) {
                    host.line("Wi-Fi: lỗi (" + e + ")");
                } finally {
                    synchronized (jobLock) {
                        jobRunning = false;
                    }
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return accepted;
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void setJob(String ssid, String state, String message) {
        jobSsid = ssid;
        jobState = state;
        jobMessage = message;
        jobAt = now();
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
