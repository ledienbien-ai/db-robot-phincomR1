package info.dourok.voicebot.weather;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Where the speaker is, what time it is there, and what the weather is doing -- from Open-Meteo,
 * which needs no API key (so there is none to leak from a public repository or an APK).
 *
 * Two calls: the geocoder turns a typed city name into coordinates plus an IANA time zone, and the
 * forecast endpoint returns current conditions and three days ahead for those coordinates.
 *
 * The same answer is rendered twice: as compact JSON for the control panel, and as a Vietnamese
 * sentence for the assistant to read out (see mcp/DeviceTools).
 *
 * Plain Java on the JDK plus org.json, so it can be run off the device.
 */
public final class Weather {

    /** A place the geocoder knows. */
    public static final class Place {
        public String name = "";
        public String admin1 = "";
        public String country = "";
        public String tz = "";
        public double lat;
        public double lon;

        /** "Đà Nẵng, Việt Nam", or with the province when it differs from the name. */
        public String label() {
            StringBuilder sb = new StringBuilder(name);
            if (!admin1.isEmpty() && !admin1.equalsIgnoreCase(name)) {
                sb.append(", ").append(admin1);
            }
            if (!country.isEmpty()) {
                sb.append(", ").append(country);
            }
            return sb.toString();
        }
    }

    // Not final: the tests point these at a local server.
    static String geocodeHost = "geocoding-api.open-meteo.com";
    static String forecastHost = "api.open-meteo.com";
    static boolean httpsFirst = true;

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int BODY_LIMIT = 256 * 1024;
    private static final String[] WEEKDAYS = {
        "Chủ nhật", "thứ Hai", "thứ Ba", "thứ Tư", "thứ Năm", "thứ Sáu", "thứ Bảy"
    };

    private Weather() {
    }

    /** Places matching a typed name, best match first; empty if there are none. */
    public static List<Place> search(String query, int count) throws IOException {
        String path = "/v1/search?name=" + URLEncoder.encode(query.trim(), "UTF-8")
                + "&count=" + count + "&language=vi&format=json";
        JSONObject root = parse(get(geocodeHost, path));
        List<Place> out = new ArrayList<Place>();
        JSONArray results = root.optJSONArray("results");
        if (results == null) {
            return out;
        }
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) {
                continue;
            }
            Place p = new Place();
            p.name = r.optString("name", "");
            p.admin1 = r.optString("admin1", "");
            p.country = r.optString("country", "");
            p.tz = r.optString("timezone", "");
            p.lat = r.optDouble("latitude", Double.NaN);
            p.lon = r.optDouble("longitude", Double.NaN);
            if (!p.name.isEmpty() && !Double.isNaN(p.lat) && !Double.isNaN(p.lon)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Current conditions and a three-day outlook, as Open-Meteo returns them. */
    public static JSONObject forecast(double lat, double lon) throws IOException {
        String path = "/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,"
                + "precipitation,weather_code,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                + "precipitation_probability_max"
                + "&timezone=auto&forecast_days=3";
        JSONObject root = parse(get(forecastHost, path));
        if (root.optJSONObject("current") == null) {
            throw new IOException("máy chủ thời tiết trả về dữ liệu không đầy đủ");
        }
        return root;
    }

    /** What the panel draws: numbers already rounded, conditions already in words. */
    public static JSONObject panel(JSONObject forecast) throws JSONException {
        JSONObject cur = forecast.optJSONObject("current");
        JSONObject out = new JSONObject();
        out.put("temp", round(cur.optDouble("temperature_2m", Double.NaN)));
        out.put("feels", round(cur.optDouble("apparent_temperature", Double.NaN)));
        out.put("humidity", cur.optInt("relative_humidity_2m", -1));
        out.put("wind", round(cur.optDouble("wind_speed_10m", Double.NaN)));
        out.put("code", cur.optInt("weather_code", -1));
        out.put("desc", describe(cur.optInt("weather_code", -1)));
        out.put("is_day", cur.optInt("is_day", 1) == 1);
        out.put("time", cur.optString("time", ""));
        JSONArray days = new JSONArray();
        JSONObject daily = forecast.optJSONObject("daily");
        JSONArray dates = daily == null ? null : daily.optJSONArray("time");
        if (dates != null) {
            for (int i = 0; i < dates.length(); i++) {
                JSONObject d = new JSONObject();
                d.put("date", dates.optString(i));
                d.put("label", dayLabel(i, dates.optString(i)));
                d.put("min", round(at(daily, "temperature_2m_min", i)));
                d.put("max", round(at(daily, "temperature_2m_max", i)));
                d.put("rain", (int) at(daily, "precipitation_probability_max", i));
                d.put("code", (int) at(daily, "weather_code", i));
                d.put("desc", describe((int) at(daily, "weather_code", i)));
                days.put(d);
            }
        }
        out.put("days", days);
        return out;
    }

    /** The forecast as something to say aloud: no symbols a speech engine would stumble over. */
    public static String speak(JSONObject forecast, String place) {
        JSONObject cur = forecast.optJSONObject("current");
        StringBuilder sb = new StringBuilder();
        sb.append("Thời tiết tại ").append(place).append(" hiện tại: ")
                .append(describe(cur.optInt("weather_code", -1)))
                .append(", nhiệt độ ").append(num(cur.optDouble("temperature_2m", Double.NaN)))
                .append(" độ C");
        double feels = cur.optDouble("apparent_temperature", Double.NaN);
        if (!Double.isNaN(feels)) {
            sb.append(", cảm giác như ").append(num(feels)).append(" độ");
        }
        int humidity = cur.optInt("relative_humidity_2m", -1);
        if (humidity >= 0) {
            sb.append(", độ ẩm ").append(humidity).append(" phần trăm");
        }
        double wind = cur.optDouble("wind_speed_10m", Double.NaN);
        if (!Double.isNaN(wind)) {
            sb.append(", gió ").append(num(wind)).append(" km/giờ");
        }
        sb.append(".");
        JSONObject daily = forecast.optJSONObject("daily");
        JSONArray dates = daily == null ? null : daily.optJSONArray("time");
        if (dates != null) {
            for (int i = 0; i < dates.length(); i++) {
                sb.append(" ").append(capitalise(dayLabel(i, dates.optString(i)))).append(": ")
                        .append(describe((int) at(daily, "weather_code", i)))
                        .append(", từ ").append(num(at(daily, "temperature_2m_min", i)))
                        .append(" đến ").append(num(at(daily, "temperature_2m_max", i)))
                        .append(" độ");
                double rain = at(daily, "precipitation_probability_max", i);
                if (!Double.isNaN(rain)) {
                    sb.append(", khả năng mưa ").append((int) rain).append(" phần trăm");
                }
                sb.append(".");
            }
        }
        return sb.toString();
    }

    /** "10:35, thứ Bảy ngày 10 tháng 10 năm 2026" in the given zone. */
    public static String localTime(TimeZone zone, long nowMillis) {
        Calendar c = Calendar.getInstance(zone, Locale.US);
        c.setTimeInMillis(nowMillis);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        hm.setTimeZone(zone);
        return hm.format(new Date(nowMillis)) + ", " + WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1]
                + " ngày " + c.get(Calendar.DAY_OF_MONTH) + " tháng " + (c.get(Calendar.MONTH) + 1)
                + " năm " + c.get(Calendar.YEAR);
    }

    /** "UTC+07:00" for the zone at that instant. */
    public static String utcOffset(TimeZone zone, long nowMillis) {
        int minutes = zone.getOffset(nowMillis) / 60000;
        int abs = Math.abs(minutes);
        return String.format(Locale.US, "UTC%s%02d:%02d", minutes < 0 ? "-" : "+", abs / 60, abs % 60);
    }

    /** WMO weather interpretation code, as Open-Meteo reports it, in Vietnamese. */
    public static String describe(int code) {
        switch (code) {
            case 0: return "trời quang";
            case 1: return "ít mây";
            case 2: return "có mây";
            case 3: return "nhiều mây";
            case 45: case 48: return "sương mù";
            case 51: return "mưa phùn nhẹ";
            case 53: return "mưa phùn";
            case 55: return "mưa phùn dày";
            case 56: case 57: return "mưa phùn lạnh giá";
            case 61: return "mưa nhẹ";
            case 63: return "mưa vừa";
            case 65: return "mưa to";
            case 66: case 67: return "mưa lạnh giá";
            case 71: return "tuyết rơi nhẹ";
            case 73: return "tuyết rơi";
            case 75: return "tuyết rơi dày";
            case 77: return "tuyết hạt";
            case 80: return "mưa rào nhẹ";
            case 81: return "mưa rào";
            case 82: return "mưa rào rất to";
            case 85: case 86: return "mưa tuyết";
            case 95: return "dông";
            case 96: case 99: return "dông kèm mưa đá";
            default: return "không rõ";
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private static String dayLabel(int index, String isoDate) {
        if (index == 0) {
            return "hôm nay";
        }
        if (index == 1) {
            return "ngày mai";
        }
        if (index == 2) {
            return "ngày kia";
        }
        return isoDate;
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(new Locale("vi")) + s.substring(1);
    }

    private static double at(JSONObject daily, String key, int i) {
        JSONArray a = daily.optJSONArray(key);
        return a == null ? Double.NaN : a.optDouble(i, Double.NaN);
    }

    /** One decimal for the panel; NaN becomes JSON null rather than an invalid number. */
    private static Object round(double v) {
        if (Double.isNaN(v)) {
            return JSONObject.NULL;
        }
        return Math.round(v * 10.0) / 10.0;
    }

    /** Whole degrees for speech: "26 độ" reads better than "26.4 độ". */
    private static String num(double v) {
        return Double.isNaN(v) ? "không rõ" : String.valueOf(Math.round(v));
    }

    private static JSONObject parse(String body) throws IOException {
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new IOException("máy chủ thời tiết trả về dữ liệu không đọc được");
        }
    }

    /**
     * GET over https, and over plain http if the TLS handshake itself fails. The R1 runs Android
     * 5.1, whose certificate store predates some of today's authorities; the request carries no
     * key and no personal data beyond a pair of coordinates, so reading a public forecast in the
     * clear is the better trade than having no forecast.
     */
    private static String get(String host, String path) throws IOException {
        if (!httpsFirst) {
            return getOnce("http://" + host + path);
        }
        try {
            return getOnce("https://" + host + path);
        } catch (javax.net.ssl.SSLException e) {
            return getOnce("http://" + host + path);
        }
    }

    private static String getOnce(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "db-robot-r1");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("máy chủ thời tiết trả về HTTP " + code);
            }
            InputStream in = c.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > BODY_LIMIT) {
                        throw new IOException("phản hồi quá lớn");
                    }
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }
}
