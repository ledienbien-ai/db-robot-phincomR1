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
 * Voice of Vietnam's channels. Every address is an HLS playlist, read through
 * {@link HlsAudioStream}; a station may list several, tried in order, because VOV serves the same
 * channel from more than one system and they do not fail together. The addresses were checked
 * against the live servers on 10/10/2026 -- the host the ESP32 firmwares still carry
 * (stream.vovmedia.vn) answered 503 to everything by then.
 *
 * Plain Java, so the matching can be tested off the device.
 */
public final class RadioStations {

    public static final class Station {
        public final String key;
        /** A few characters for a button in the panel. */
        public final String shortName;
        public final String name;
        /** HLS playlists carrying this station, best first. */
        public final String[] urls;
        /** Ways people ask for it, already normalised (see {@link #normalise}). */
        final String[] aliases;

        Station(String key, String shortName, String name, String[] urls, String... aliases) {
            this.key = key;
            this.shortName = shortName;
            this.name = name;
            this.urls = urls;
            this.aliases = aliases;
        }
    }

    // VOV's three delivery systems: its Wowza origin (256 kbit/s AAC), the traffic channels' own
    // player, and the low-bitrate web player.
    private static final String WOWZA = "https://str.vov.gov.vn/vovlive/";
    private static final String TRAFFIC = "https://play.vovgiaothong.vn/live/";
    private static final String LIGHT = "https://audio-lss.vov.vn/live/";
    private static final List<Station> ALL;

    private static String[] urls(String... u) {
        return u;
    }

    static {
        List<Station> s = new ArrayList<Station>();
        s.add(new Station("VOV1", "VOV1", "VOV 1 - Thời sự",
                urls(WOWZA + "vov1vov5Vietnamese.sdp_aac/playlist.m3u8", LIGHT + "vov1.m3u8"),
                "vov1", "thoisu"));
        s.add(new Station("VOV2", "VOV2", "VOV 2 - Văn hóa & Giáo dục",
                urls(WOWZA + "vov2.sdp_aac/playlist.m3u8", LIGHT + "vov2.m3u8"),
                "vov2", "vanhoa", "giaoduc"));
        s.add(new Station("VOV3", "VOV3", "VOV 3 - Âm nhạc & Giải trí",
                urls(WOWZA + "vov3.sdp_aac/playlist.m3u8", LIGHT + "vov3.m3u8"),
                "vov3", "amnhac", "giaitri"));
        s.add(new Station("VOV4", "VOV4", "VOV 4 - Dân tộc",
                urls(LIGHT + "vov4.m3u8"),
                "vov4", "dantoc"));
        s.add(new Station("VOV5", "VOV5", "VOV 5 - Đối ngoại",
                urls(WOWZA + "vov5.sdp_aac/playlist.m3u8", LIGHT + "vov5.m3u8"),
                "vov5", "doingoai"));
        s.add(new Station("VOV_GT_HN", "Giao thông HN", "VOV Giao thông Hà Nội",
                urls(WOWZA + "vovGTHN.sdp_aac/playlist.m3u8", TRAFFIC + "gthn/playlist.m3u8",
                        LIGHT + "giao_thong_ha_noi.m3u8"),
                "giaothong", "vovgt", "giaothonghanoi", "vovgthn", "vovgthanoi", "giaothonghn"));
        s.add(new Station("VOV_GT_HCM", "Giao thông HCM", "VOV Giao thông TP.HCM",
                urls(WOWZA + "vovGTHCM.sdp_aac/playlist.m3u8", TRAFFIC + "gthcm/playlist.m3u8"),
                "giaothonghochiminh", "giaothongtphochiminh", "giaothongthanhphohochiminh",
                "giaothongsaigon", "giaothonghcm", "giaothongtphcm", "vovgthcm", "vovgtsaigon",
                "vovgthochiminh", "vovgttphcm"));
        s.add(new Station("VOV_MEKONG", "Mekong", "VOV Mekong FM",
                urls(TRAFFIC + "mekong/playlist.m3u8"),
                "mekong", "mientay"));
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
