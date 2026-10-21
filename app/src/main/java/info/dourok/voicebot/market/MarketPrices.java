package info.dourok.voicebot.market;

import info.dourok.voicebot.net.Https;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gold and fuel prices in Vietnam, in words, for the assistant's market tools.
 *
 * Gold comes from PNJ's own price API (the one pnj.com.vn reads): JSON, no key, prices in thousand
 * dong per chi. SJC's site sits behind a Cloudflare challenge and DOJI's API is offline, so PNJ is
 * the source a box with no browser can still read; it lists SJC bars too.
 *
 * Fuel comes from giaxanghomnay.com, which republishes the Petrolimex (zone 1 and 2) and PVOIL
 * tables as plain HTML. Petrolimex's own API signs every request in the browser, and PVOIL answers
 * 403 to anything that is not one. webgia.com was considered and dropped: on the adjustment day
 * (21/10/2026) it still showed the table of 15/10.
 *
 * Answers are cached for a few minutes: a question and its follow-up ("còn vàng nhẫn thì sao?")
 * should not each cost a 250 kB download on a slow box.
 *
 * Parsing is kept apart from fetching and is plain Java, so it runs on the JDK in tests.
 */
public final class MarketPrices {
    static final String GOLD_URL = "https://edge-api.pnj.io/ecom-frontend/v1/get-gold-price?zone=00";
    static final String FUEL_URL = "https://giaxanghomnay.com/";

    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static final int BODY_LIMIT = 2 * 1024 * 1024;

    private static final Object LOCK = new Object();
    private static String goldCache;
    private static long goldAt;
    private static String fuelCache;
    private static long fuelAt;

    private MarketPrices() {
    }

    public static String goldText() throws IOException, JSONException {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (goldCache == null || now - goldAt > CACHE_MS) {
                goldCache = parseGold(fetch(GOLD_URL, "application/json"));
                goldAt = now;
            }
            return goldCache;
        }
    }

    public static String fuelText() throws IOException {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (fuelCache == null || now - fuelAt > CACHE_MS) {
                fuelCache = parseFuel(fetch(FUEL_URL, "text/html"));
                fuelAt = now;
            }
            return fuelCache;
        }
    }

    private static String fetch(String url, String accept) throws IOException {
        Map<String, String> headers = new HashMap<String, String>();
        // Both sites turn away clients that do not look like a browser.
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 5.1.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        headers.put("Accept", accept);
        headers.put("Accept-Language", "vi-VN,vi");
        return Https.readText(Https.get(url, headers), BODY_LIMIT);
    }

    /** PNJ's price list in words. Prices arrive in thousand dong per chi (10 chi = 1 luong). */
    static String parseGold(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray data = root.getJSONArray("data");
        StringBuilder out = new StringBuilder("Giá vàng theo bảng giá PNJ");
        String updated = root.optString("updateDate", "").trim();
        if (!updated.isEmpty()) {
            out.append(", cập nhật ").append(updated);
        }
        out.append(". Đơn vị: triệu đồng một lượng, trong ngoặc là một chỉ.\n");
        int listed = 0;
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            String name = item.optString("tensp", "").trim();
            long buy = thousands(item.opt("giamua"));
            long sell = thousands(item.opt("giaban"));
            if (name.isEmpty() || (buy <= 0 && sell <= 0)) {
                continue;
            }
            out.append("- ").append(name).append(":");
            if (buy > 0) {
                out.append(" mua vào ").append(perLuong(buy)).append(" (").append(perChi(buy)).append(")");
            }
            if (sell > 0) {
                out.append(buy > 0 ? "," : "").append(" bán ra ").append(perLuong(sell))
                    .append(" (").append(perChi(sell)).append(")");
            }
            out.append(".\n");
            listed++;
        }
        if (listed == 0) {
            throw new JSONException("bảng giá vàng trống");
        }
        return out.toString().trim();
    }

    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[hd][^>]*>(.*?)</t[hd]>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern UPDATED = Pattern.compile("Petrolimex\\s+cập nhật\\s+(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})");

    /** The Petrolimex and PVOIL tables of giaxanghomnay.com in words. */
    static String parseFuel(String html) throws IOException {
        List<String[]> petro = table(html, "table-petro");
        List<String[]> pvoil = table(html, "table-pvoil");
        if (petro.isEmpty()) {
            throw new IOException("không đọc được bảng giá xăng dầu");
        }
        StringBuilder out = new StringBuilder("Giá bán lẻ xăng dầu Petrolimex");
        String plain = TAG.matcher(html).replaceAll(" ").replaceAll("\\s+", " ");
        Matcher m = UPDATED.matcher(plain);
        if (m.find()) {
            out.append(", cập nhật ").append(m.group(4)).append(":").append(m.group(5)).append(" ngày ")
                .append(m.group(3)).append("/").append(m.group(2)).append("/").append(m.group(1));
        }
        out.append(". Đơn vị: đồng một lít. Vùng 1 là các thành phố lớn, vùng 2 là vùng xa kho, đắt hơn một chút.\n");
        for (String[] r : petro) {
            // Xăng/dầu | tăng giảm hôm nay | tăng giảm kỳ trước | vùng 1 | vùng 2
            if (r.length < 5) {
                continue;
            }
            out.append("- ").append(r[0]).append(": vùng 1 ").append(r[3]).append(", vùng 2 ").append(r[4])
                .append(change(r[2])).append(".\n");
        }
        if (!pvoil.isEmpty()) {
            out.append("PVOIL:");
            String sep = " ";
            for (String[] r : pvoil) {
                if (r.length < 4) {
                    continue;
                }
                out.append(sep).append(r[0]).append(" ").append(r[3]);
                sep = "; ";
            }
            out.append(".\n");
        }
        out.append("Giá xăng dầu được điều chỉnh vào các ngày 1, 11 và 21 hằng tháng.");
        return out.toString().trim();
    }

    /** Data rows of the table whose class starts with {@code cls}, header row left out. */
    private static List<String[]> table(String html, String cls) {
        List<String[]> rows = new ArrayList<String[]>();
        int start = html.indexOf("<table class=\"" + cls);
        if (start < 0) {
            return rows;
        }
        int end = html.indexOf("</table>", start);
        if (end < 0) {
            return rows;
        }
        Matcher r = ROW.matcher(html.substring(start, end));
        boolean header = true;
        while (r.find()) {
            if (header) {
                header = false;
                continue;
            }
            List<String> cells = new ArrayList<String>();
            Matcher c = CELL.matcher(r.group(1));
            while (c.find()) {
                cells.add(TAG.matcher(c.group(1)).replaceAll(" ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim());
            }
            if (!cells.isEmpty() && !cells.get(0).isEmpty()) {
                rows.add(cells.toArray(new String[0]));
            }
        }
        return rows;
    }

    private static String change(String previous) {
        String p = previous.trim();
        if (p.isEmpty() || p.equals("—") || p.equals("-") || p.equals("0")) {
            return "";
        }
        return p.startsWith("-") ? " (kỳ điều chỉnh gần nhất giảm " + p.substring(1) + ")"
            : " (kỳ điều chỉnh gần nhất tăng " + p.replace("+", "") + ")";
    }

    /** A PNJ price field: a number, a numeric string, or "" when the shop does not quote it. */
    private static long thousands(Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            String digits = ((String) v).replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0 : Long.parseLong(digits);
        }
        return 0;
    }

    /** 14230 thousand per chi -> "142,3 triệu". */
    static String perLuong(long thousandPerChi) {
        return decimal(thousandPerChi * 10, 1000, 2) + " triệu";
    }

    /** 14230 thousand per chi -> "14,23 triệu". */
    static String perChi(long thousandPerChi) {
        return decimal(thousandPerChi, 1000, 2) + " triệu";
    }

    /** value/divisor with at most {@code places} decimals, Vietnamese comma, no trailing zeros. */
    private static String decimal(long value, long divisor, int places) {
        long scale = 1;
        for (int i = 0; i < places; i++) {
            scale *= 10;
        }
        long scaled = Math.round((double) value * scale / divisor);
        long whole = scaled / scale;
        long frac = scaled % scale;
        if (frac == 0) {
            return Long.toString(whole);
        }
        String f = Long.toString(frac);
        while (f.length() < places) {
            f = "0" + f;
        }
        while (f.endsWith("0")) {
            f = f.substring(0, f.length() - 1);
        }
        return whole + "," + f;
    }
}
