package info.dourok.voicebot.media;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The internet radio stations the speaker can play, and the matching of what somebody said
 * ("VOV giao thông Sài Gòn", "vov một") to one of them.
 *
 * The list -- Voice of Vietnam's channels and their stream addresses -- is the one the Vietnamese
 * xiaozhi ESP32 firmware carries (TienHuyIoT/xiaozhi-esp32_vietnam, MIT), so a speaker and a robot
 * on the same server answer to the same station names. The streams are plain AAC over https.
 *
 * Plain Java, so the matching can be tested off the device.
 */
public final class RadioStations {

    public static final class Station {
        public final String key;
        /** A few characters for a button in the panel. */
        public final String shortName;
        public final String name;
        public final String url;
        /** Ways people ask for it, already normalised (see {@link #normalise}). */
        final String[] aliases;

        Station(String key, String shortName, String name, String url, String... aliases) {
            this.key = key;
            this.shortName = shortName;
            this.name = name;
            this.url = url;
            this.aliases = aliases;
        }
    }

    private static final String BASE = "https://stream.vovmedia.vn/";
    private static final List<Station> ALL;

    static {
        List<Station> s = new ArrayList<Station>();
        s.add(new Station("VOV1", "VOV1", "VOV 1 - Thời sự", BASE + "vov-1", "vov1", "thoisu"));
        s.add(new Station("VOV2", "VOV2", "VOV 2 - Văn hóa & Giáo dục", BASE + "vov-2", "vov2", "vanhoa", "giaoduc"));
        s.add(new Station("VOV3", "VOV3", "VOV 3 - Âm nhạc & Giải trí", BASE + "vov-3", "vov3", "amnhac", "giaitri"));
        s.add(new Station("VOV5", "VOV5", "VOV 5 - Đối ngoại", BASE + "vov5", "vov5", "doingoai"));
        s.add(new Station("VOV_GT_HN", "Giao thông HN", "VOV Giao thông Hà Nội", BASE + "vovgt-hn",
                "giaothong", "vovgt", "giaothonghanoi", "vovgthn", "vovgthanoi", "giaothonghn"));
        s.add(new Station("VOV_GT_HCM", "Giao thông HCM", "VOV Giao thông TP.HCM", BASE + "vovgt-hcm",
                "giaothonghochiminh", "giaothongtphochiminh", "giaothongthanhphohochiminh",
                "giaothongsaigon", "giaothonghcm", "giaothongtphcm", "vovgthcm", "vovgtsaigon",
                "vovgthochiminh", "vovgttphcm"));
        s.add(new Station("VOV_MEKONG", "Mekong", "VOV Mekong FM", BASE + "vovmekong", "mekong", "mientay"));
        s.add(new Station("VOV4_MIENTRUNG", "VOV4 Miền Trung", "VOV4 Miền Trung", BASE + "vov4mt", "vov4mientrung", "mientrung"));
        s.add(new Station("VOV4_TAYBAC", "VOV4 Tây Bắc", "VOV4 Tây Bắc", BASE + "vov4tb", "vov4taybac", "taybac"));
        s.add(new Station("VOV4_DONGBAC", "VOV4 Đông Bắc", "VOV4 Đông Bắc", BASE + "vov4db", "vov4dongbac", "dongbac"));
        s.add(new Station("VOV4_TAYNGUYEN", "VOV4 Tây Nguyên", "VOV4 Tây Nguyên", BASE + "vov4tn", "vov4taynguyen", "taynguyen"));
        s.add(new Station("VOV4_DBSCL", "VOV4 ĐBSCL", "VOV4 ĐBSCL", BASE + "vov4dbscl",
                "vov4dbscl", "dbscl", "dongbangsongcuulong"));
        s.add(new Station("VOV4_HCM", "VOV4 TP.HCM", "VOV4 TP.HCM", BASE + "vov4hcm",
                "vov4hcm", "vov4tphcm", "vov4hochiminh", "vov4tphochiminh", "vov4saigon"));
        s.add(new Station("VOV5_ENGLISH", "English", "VOV 5 - English 24/7", BASE + "vov247",
                "english", "tienganh", "vov247", "vov5english"));
        ALL = Collections.unmodifiableList(s);
    }

    private RadioStations() {
    }

    public static List<Station> all() {
        return ALL;
    }

    public static Station byKey(String key) {
        for (Station s : ALL) {
            if (s.key.equalsIgnoreCase(key)) {
                return s;
            }
        }
        return null;
    }

    /**
     * The station somebody most likely meant, or null. A key or a full name wins outright;
     * otherwise the station with the longest alias found in what was said -- so "giao thông Sài
     * Gòn" is the Ho Chi Minh City traffic channel and bare "giao thông" the Hanoi one.
     */
    public static Station find(String said) {
        if (said == null) {
            return null;
        }
        Station exact = byKey(said.trim());
        if (exact != null) {
            return exact;
        }
        String n = normalise(spokenNumbers(said));
        if (n.isEmpty()) {
            return null;
        }
        Station best = null;
        int bestLength = 0;
        for (Station s : ALL) {
            if (normalise(s.name).equals(n) || normalise(s.key).equals(n)) {
                return s;
            }
            for (String alias : s.aliases) {
                if (alias.length() > bestLength && n.contains(alias)) {
                    best = s;
                    bestLength = alias.length();
                }
            }
        }
        return best;
    }

    /** Lower case, no Vietnamese tone or vowel marks, letters and digits only. */
    static String normalise(String s) {
        String lower = s.toLowerCase(new Locale("vi")).replace('đ', 'd');
        String bare = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return bare.replaceAll("[^a-z0-9]+", "");
    }

    /**
     * "VOV một" is how the channel number comes out of speech recognition as often as "VOV1"
     * does, and recognition also mishears it: mộc, mốt, mốc. Only a number right after "VOV" is
     * rewritten, so the "năm" or "ba" of an ordinary phrase is left alone.
     */
    static String spokenNumbers(String s) {
        String t = Normalizer.normalize(s.toLowerCase(new Locale("vi")).replace('đ', 'd'),
                Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        t = t.replaceAll("\\bvov\\s*(mot|moc|mut|nhat)\\b", "vov1");
        t = t.replaceAll("\\bvov\\s*hai\\b", "vov2");
        t = t.replaceAll("\\bvov\\s*ba\\b", "vov3");
        t = t.replaceAll("\\bvov\\s*(bon|tu)\\b", "vov4");
        t = t.replaceAll("\\bvov\\s*nam\\b", "vov5");
        return t;
    }
}
