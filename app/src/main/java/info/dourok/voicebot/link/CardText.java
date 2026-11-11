package info.dourok.voicebot.link;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pictures the display can draw for a conversation turn, found in its words alone (no tool ran):
 * a sum ("12 nhân 7 bằng mấy?") and a country ("quốc kỳ Nhật Bản"). Plain Java, so it is tested
 * on the JDK.
 */
public final class CardText {
    private CardText() {
    }

    // ── sums ──────────────────────────────────────────────────────────────────

    /** A sum and its result, ready to draw. {@code a}/{@code b}/{@code op} only for one small whole-number operation. */
    public static final class Sum {
        public final String expr;
        public final String result;
        public final int a;
        public final int b;
        public final char op;

        Sum(String expr, String result, int a, int b, char op) {
            this.expr = expr;
            this.result = result;
            this.a = a;
            this.b = b;
            this.op = op;
        }
    }

    private static final String NUM = "(?:\\d{1,3}(?:\\.\\d{3})+|\\d+)(?:,\\d+)?";
    private static final String OP = "(?:\\+|-|−|–|×|x|\\*|:|÷|/|cộng(?: với)?|trừ(?: đi)?|nhân(?: với)?|chia(?: cho)?)";
    private static final Pattern EXPR = Pattern.compile(
        "(" + NUM + ")((?:\\s*" + OP + "\\s*" + NUM + "){1,3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAIR = Pattern.compile("\\s*(" + OP + ")\\s*(" + NUM + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROOT = Pattern.compile("căn(?: bậc hai)?(?: của)?\\s+(" + NUM + ")", Pattern.CASE_INSENSITIVE);

    /** The first sum in {@code text} (a question or a reply), worked out here; null when there is none. */
    public static Sum findSum(String text) {
        if (text == null) {
            return null;
        }
        Matcher r = ROOT.matcher(text);
        if (r.find()) {
            double v = number(r.group(1));
            if (v >= 0) {
                return new Sum("√" + r.group(1), format(Math.sqrt(v)), -1, -1, 'r');
            }
        }
        Matcher m = EXPR.matcher(text);
        while (m.find()) {
            List<Double> nums = new ArrayList<Double>();
            List<Character> ops = new ArrayList<Character>();
            nums.add(number(m.group(1)));
            StringBuilder shown = new StringBuilder(m.group(1));
            Matcher p = PAIR.matcher(m.group(2));
            boolean ok = !leadingZero(m.group(1));
            // only "-", "/" and ":" between the numbers: a date, a time, a range, a phone number...
            boolean ambiguous = true;
            while (ok && p.find()) {
                char op = op(p.group(1));
                String sym = p.group(1).trim();
                if (!(sym.equals("-") || sym.equals("–") || sym.equals("/") || sym.equals(":"))) {
                    ambiguous = false;
                }
                if (leadingZero(p.group(2))) {
                    ok = false;
                    break;
                }
                if (op == 0) {
                    ok = false;
                    break;
                }
                ops.add(op);
                nums.add(number(p.group(2)));
                shown.append(' ').append(op).append(' ').append(p.group(2));
            }
            // "5 - 3" alone could be a range or a score: a sum needs a sign word or "bằng"/"="
            if (!ok || ops.isEmpty() || (ops.size() == 1 && ops.get(0) == '−' && !looksLikeSum(text))) {
                continue;
            }
            // dates ("21/10", "11/11/2026"), times ("10:35") and the like are not sums unless the
            // words say so
            if (ambiguous && !saysSum(text)) {
                continue;
            }
            Double v = eval(nums, ops);
            if (v == null) {
                continue;
            }
            int a = -1;
            int b = -1;
            char op1 = ops.get(0);
            if (ops.size() == 1 && whole(nums.get(0)) && whole(nums.get(1))) {
                a = (int) Math.round(nums.get(0));
                b = (int) Math.round(nums.get(1));
            }
            return new Sum(shown.toString(), format(v), a, b, op1);
        }
        return null;
    }

    /** "0912": a phone number or a code, not a quantity. */
    private static boolean leadingZero(String n) {
        return n.length() > 1 && n.charAt(0) == '0' && n.charAt(1) != ',';
    }

    private static boolean saysSum(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("bằng") || t.contains("=") || t.contains("phép tính") || t.contains("tính giúp")
            || t.contains("kết quả");
    }

    private static boolean looksLikeSum(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("bằng") || t.contains("=") || t.contains("trừ") || t.contains("chia") || t.contains("tính")
            || t.contains("phép") || t.contains("kết quả") || t.contains("là mấy") || t.contains("bao nhiêu");
    }

    private static char op(String w) {
        String s = w.toLowerCase(Locale.ROOT).trim();
        if (s.equals("+") || s.startsWith("cộng")) return '+';
        if (s.equals("-") || s.equals("−") || s.equals("–") || s.startsWith("trừ")) return '−';
        if (s.equals("×") || s.equals("x") || s.equals("*") || s.startsWith("nhân")) return '×';
        if (s.equals(":") || s.equals("÷") || s.equals("/") || s.startsWith("chia")) return '÷';
        return 0;
    }

    private static Double eval(List<Double> nums, List<Character> ops) {
        // × and ÷ first, then + and −, left to right
        List<Double> n = new ArrayList<Double>(nums);
        List<Character> o = new ArrayList<Character>(ops);
        for (int i = 0; i < o.size(); ) {
            char c = o.get(i);
            if (c == '×' || c == '÷') {
                if (c == '÷' && n.get(i + 1) == 0) {
                    return null;
                }
                double v = c == '×' ? n.get(i) * n.get(i + 1) : n.get(i) / n.get(i + 1);
                n.set(i, v);
                n.remove(i + 1);
                o.remove(i);
            } else {
                i++;
            }
        }
        double v = n.get(0);
        for (int i = 0; i < o.size(); i++) {
            v = o.get(i) == '+' ? v + n.get(i + 1) : v - n.get(i + 1);
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return null;
        }
        return v;
    }

    /** "1.000,5" -> 1000.5 (Vietnamese: dot groups thousands, comma marks decimals). */
    static double number(String s) {
        return Double.parseDouble(s.replace(".", "").replace(',', '.'));
    }

    private static boolean whole(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9;
    }

    /** 84 -> "84", 1234567 -> "1.234.567", 3.14159 -> "3,1416". */
    static String format(double v) {
        if (whole(v) && Math.abs(v) < 1e15) {
            long l = Math.round(v);
            String s = Long.toString(Math.abs(l));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                if (i > 0 && (s.length() - i) % 3 == 0) {
                    out.append('.');
                }
                out.append(s.charAt(i));
            }
            return (l < 0 ? "−" : "") + out;
        }
        String t = String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        int dot = t.indexOf('.');
        String whole = dot < 0 ? t : t.substring(0, dot);
        String frac = dot < 0 ? "" : t.substring(dot + 1);
        String w = format(Double.parseDouble(whole.replace("-", "")));
        return (v < 0 ? "−" : "") + w + (frac.isEmpty() ? "" : "," + frac);
    }

    // ── countries ─────────────────────────────────────────────────────────────

    /** A country and its ISO 3166 code (lower case), the name of its flag picture on the display. */
    public static final class Country {
        public final String code;
        public final String name;

        Country(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    // code, the name as written, then other spellings. Names marked * are also everyday words
    // ("anh", "ý", "pháp", "đức", "nga", "mỹ", "úc"...) and count only after "nước", "cờ", "người"...
    private static final String[][] COUNTRIES = {
        {"vn", "Việt Nam"}, {"jp", "Nhật Bản", "Nhật*"}, {"kr", "Hàn Quốc", "Nam Hàn"}, {"kp", "Triều Tiên", "Bắc Hàn"},
        {"cn", "Trung Quốc"}, {"tw", "Đài Loan"}, {"hk", "Hồng Kông"}, {"th", "Thái Lan"}, {"la", "Lào*"},
        {"kh", "Campuchia", "Cam-pu-chia", "Cambodia"}, {"mm", "Myanmar", "Miến Điện"}, {"my", "Malaysia", "Mã Lai"},
        {"sg", "Singapore", "Xin-ga-po"}, {"id", "Indonesia", "In-đô-nê-xi-a"}, {"ph", "Philippines", "Phi-líp-pin"},
        {"bn", "Brunei"}, {"tl", "Đông Timor"}, {"in", "Ấn Độ"}, {"pk", "Pakistan"}, {"bd", "Bangladesh"},
        {"np", "Nepal"}, {"lk", "Sri Lanka"}, {"mn", "Mông Cổ"}, {"kz", "Kazakhstan"}, {"af", "Afghanistan"},
        {"ir", "Iran"}, {"iq", "Iraq"}, {"sa", "Ả Rập Xê Út", "Saudi Arabia", "Ả-rập Xê-út"},
        {"ae", "Các Tiểu vương quốc Ả Rập Thống nhất", "UAE", "Dubai"}, {"qa", "Qatar"}, {"il", "Israel"},
        {"ps", "Palestine"}, {"tr", "Thổ Nhĩ Kỳ"}, {"sy", "Syria"}, {"jo", "Jordan"},
        {"us", "Hoa Kỳ", "Mỹ*"}, {"ca", "Canada"}, {"mx", "Mexico", "Mê-hi-cô"}, {"cu", "Cuba"},
        {"br", "Brazil", "Bra-xin"}, {"ar", "Argentina", "Ác-hen-ti-na"}, {"cl", "Chile", "Chi-lê"}, {"co", "Colombia"},
        {"pe", "Peru"}, {"ve", "Venezuela"}, {"uy", "Uruguay"},
        {"gb", "Vương quốc Anh", "Anh Quốc", "Anh*"}, {"fr", "Pháp*"}, {"de", "Đức*"}, {"it", "Italia", "Italy", "Ý*"},
        {"es", "Tây Ban Nha"}, {"pt", "Bồ Đào Nha"}, {"nl", "Hà Lan"}, {"be", "Bỉ*"}, {"ch", "Thụy Sĩ", "Thuỵ Sĩ"},
        {"at", "Áo*"}, {"se", "Thụy Điển", "Thuỵ Điển"}, {"no", "Na Uy"}, {"dk", "Đan Mạch"}, {"fi", "Phần Lan"},
        {"is", "Iceland", "Ai-xơ-len"}, {"ie", "Ireland", "Ai-len"}, {"pl", "Ba Lan"}, {"cz", "Séc*", "Cộng hòa Séc"},
        {"hu", "Hungary"}, {"ro", "Romania", "Rumani"}, {"bg", "Bulgaria"}, {"gr", "Hy Lạp"}, {"hr", "Croatia"},
        {"rs", "Serbia"}, {"ua", "Ukraine", "U-crai-na"}, {"ru", "Nga*", "Liên bang Nga"}, {"by", "Belarus"},
        {"eg", "Ai Cập"}, {"za", "Nam Phi"}, {"ng", "Nigeria"}, {"ke", "Kenya"}, {"et", "Ethiopia"}, {"ma", "Ma-rốc", "Morocco", "Maroc"},
        {"dz", "Algeria"}, {"gh", "Ghana"}, {"sn", "Senegal"}, {"cm", "Cameroon"}, {"ao", "Angola"}, {"mz", "Mozambique"},
        {"au", "Úc*", "Australia", "Ô-xtrây-li-a"}, {"nz", "New Zealand", "Niu Di-lân"},
    };
    private static final String[] BEFORE = {"nước", "cờ", "quốc kỳ", "người", "tiếng", "đất nước", "thủ đô", "đội tuyển", "vương quốc", "đến", "ở", "sang", "tới", "của", "đi", "du lịch"};
    private static final String[] TOPIC = {"nước", "quốc gia", "quốc kỳ", "lá cờ", "cờ", "thủ đô", "đất nước", "dân số", "diện tích", "biên giới", "châu"};

    /**
     * The country the turn is about: one named in the question, or -- when the question is about
     * countries ("nước nào", "thủ đô", "lá cờ"...) -- the first one the reply names. Null otherwise.
     */
    private static final String[] NOT_COUNTRY = {
        "nhạc", "bài hát", "video", "phim", "radio", "podcast", "món", "đồ ăn", "nấu",
    };

    public static Country findCountry(String question, String reply) {
        // "mở nhạc Hàn Quốc", "phim Mỹ": a song or a film, not the country
        String ql = question == null ? "" : question.toLowerCase(Locale.ROOT);
        for (String w : NOT_COUNTRY) {
            if (ql.contains(w)) {
                return null;
            }
        }
        Country c = first(question, false);
        if (c != null) {
            return c;
        }
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean topic = false;
        for (String t : TOPIC) {
            if (q.contains(t)) {
                topic = true;
                break;
            }
        }
        return topic ? first(reply, true) : null;
    }

    private static Country first(String text, boolean reply) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String low = text.toLowerCase(Locale.ROOT);
        String flat = plain(low);
        int bestAt = Integer.MAX_VALUE;
        Country best = null;
        for (String[] row : COUNTRIES) {
            for (int i = 1; i < row.length; i++) {
                String name = row[i];
                boolean everyday = name.endsWith("*");
                if (everyday) {
                    name = name.substring(0, name.length() - 1);
                }
                int at = find(text, low, flat, name, everyday, reply);
                if (at >= 0 && at < bestAt) {
                    bestAt = at;
                    best = new Country(row[0], row[1].replace("*", ""));
                }
            }
        }
        return best;
    }

    private static int find(String text, String low, String flat, String name, boolean everyday, boolean reply) {
        String n = name.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int at = low.indexOf(n, from);
            if (at < 0) {
                // a question from speech recognition may come without its accents
                if (!everyday && flat.equals(low)) {
                    int f = wordAt(flat, plain(n), 0);
                    if (f >= 0) return f;
                }
                return -1;
            }
            from = at + 1;
            if (!wordBoundary(low, at, n.length())) {
                continue;
            }
            if (everyday) {
                // a reply names it with a capital; either way it must follow "nước", "cờ", "người"...
                if (reply && !Character.isUpperCase(text.charAt(at))) {
                    continue;
                }
                if (!after(low, at)) {
                    continue;
                }
            }
            return at;
        }
    }

    private static int wordAt(String hay, String needle, int from) {
        int at = hay.indexOf(needle, from);
        while (at >= 0) {
            if (wordBoundary(hay, at, needle.length())) {
                return at;
            }
            at = hay.indexOf(needle, at + 1);
        }
        return -1;
    }

    private static boolean after(String low, int at) {
        String before = low.substring(0, at).trim();
        for (String b : BEFORE) {
            if (before.endsWith(b)) {
                return true;
            }
        }
        return false;
    }

    private static boolean wordBoundary(String s, int at, int len) {
        boolean l = at == 0 || !Character.isLetterOrDigit(s.charAt(at - 1));
        int end = at + len;
        boolean r = end >= s.length() || !Character.isLetterOrDigit(s.charAt(end));
        return l && r;
    }

    /** Lower case without accents, "đ" as "d". */
    static String plain(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }
}
