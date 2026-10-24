package info.dourok.voicebot.ha;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The user's Home Assistant devices as saved on the panel (one {@code location,name,entity_id} line
 * each), and finding the one(s) a spoken request means: "đèn phòng khách", "quạt", "tất cả đèn",
 * "phòng ngủ", or an entity_id the assistant copied from the tool description.
 *
 * Matching works on words with the Vietnamese marks taken off, so "Đèn Phòng Khách", "den phong
 * khach" and "đèn phòng khách" are the same thing. Plain Java, so it runs off the device.
 */
public final class HaDevices {
    private HaDevices() {}

    public static final class Device {
        public final String location, name, entityId;

        public Device(String location, String name, String entityId) {
            this.location = location == null ? "" : location.trim();
            this.name = name == null ? "" : name.trim();
            this.entityId = entityId == null ? "" : entityId.trim();
        }

        public String domain() {
            int i = entityId.indexOf('.');
            return i > 0 ? entityId.substring(0, i) : "";
        }

        /** "Đèn trần (phòng khách)", for what the speaker says back. */
        public String label() {
            String n = name.isEmpty() ? entityId : name;
            return location.isEmpty() || norm(n).contains(norm(location)) ? n : n + " (" + location + ")";
        }
    }

    /** The saved list: {@code location,name,entity_id} per line (a name may hold commas). */
    public static List<Device> parse(String lines) {
        List<Device> out = new ArrayList<>();
        if (lines == null) return out;
        for (String raw : lines.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            String[] p = l.split(",");
            if (p.length < 2) continue;
            String id = p[p.length - 1].trim();
            if (!id.contains(".")) continue;
            String loc = p.length >= 3 ? p[0] : "";
            StringBuilder name = new StringBuilder();
            for (int i = p.length >= 3 ? 1 : 0; i < p.length - 1; i++) {
                if (name.length() > 0) name.append(',');
                name.append(p[i]);
            }
            out.add(new Device(loc, name.toString(), id));
        }
        return out;
    }

    /** Lower case, Vietnamese marks off, đ → d, anything else than letters and digits → one space. */
    public static String norm(String s) {
        if (s == null) return "";
        String d = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('đ', 'd');
        return d.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Words that carry no meaning for which device is meant. */
    private static final Set<String> FILLER = new HashSet<>(Arrays.asList(
            "cai", "chiec", "con", "bong", "o", "tai", "trong", "giup", "minh", "toi", "em",
            "nhe", "nha", "di", "voi", "va", "thiet", "bi", "the", "please"));
    /** Words that help when they fit but are no reason to drop a device ("của" and "cửa" are both "cua"). */
    private static final Set<String> SOFT = new HashSet<>(Arrays.asList("cua", "may"));
    /** Words that ask for every device that matches the rest. */
    private static final Set<String> ALL_WORDS = new HashSet<>(Arrays.asList("tat", "ca", "het", "toan", "bo", "moi", "all", "every", "cac", "nhung"));

    /** A word for a kind of device → the Home Assistant domain it usually is. */
    private static final Map<String, String> KIND = new HashMap<>();
    static {
        KIND.put("den", "light");
        KIND.put("light", "light");
        KIND.put("quat", "fan");
        KIND.put("fan", "fan");
        KIND.put("rem", "cover");
        KIND.put("cua", "cover");
        KIND.put("dieu", "climate");
        KIND.put("lanh", "climate");
        KIND.put("tivi", "media_player");
        KIND.put("tv", "media_player");
        KIND.put("loa", "media_player");
        KIND.put("khoa", "lock");
        KIND.put("cam", "switch");
        KIND.put("cong", "switch");
    }

    /** The outcome of a search: the devices, or why none could be picked. */
    public static final class Found {
        public final List<Device> devices;
        /** true when several devices fit equally and the request did not say "all". */
        public final boolean ambiguous;

        Found(List<Device> devices, boolean ambiguous) {
            this.devices = devices;
            this.ambiguous = ambiguous;
        }
    }

    public static Found find(String query, List<Device> all) {
        List<Device> none = new ArrayList<>();
        if (query == null || query.trim().isEmpty() || all.isEmpty()) return new Found(none, false);

        // entity_ids, perhaps several, as the assistant may copy them from the tool description
        List<Device> byId = new ArrayList<>();
        for (String part : query.split("[,;\\s]+")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z_]+\\.[a-z0-9_]+")) continue;
            Device d = byEntity(id, all);
            byId.add(d != null ? d : new Device("", "", id));
        }
        if (!byId.isEmpty()) return new Found(byId, false);

        String q = norm(query);
        Set<String> words = new HashSet<>(Arrays.asList(q.split(" ")));
        boolean wantAll = false;
        for (String w : words) if (ALL_WORDS.contains(w)) wantAll = true;
        if (q.contains("tat ca") || q.contains("toan bo")) wantAll = true;
        Set<String> qw = new HashSet<>();
        for (String w : words) if (!FILLER.contains(w) && !ALL_WORDS.contains(w) && !w.isEmpty()) qw.add(w);
        if (qw.isEmpty()) return new Found(wantAll ? new ArrayList<>(all) : none, false);

        // the exact name (with or without its room)
        List<Device> exact = new ArrayList<>();
        for (Device d : all) {
            String n = norm(d.name), loc = norm(d.location);
            if (q.equals(n) || q.equals((loc + " " + n).trim()) || q.equals((n + " " + loc).trim())) exact.add(d);
        }
        if (exact.size() == 1 || (!exact.isEmpty() && wantAll)) return new Found(exact, false);

        // a room on its own ("phòng ngủ") means everything in it
        List<Device> room = new ArrayList<>();
        for (Device d : all) {
            Set<String> lw = new HashSet<>(Arrays.asList(norm(d.location).split(" ")));
            if (!d.location.isEmpty() && lw.equals(qw)) room.add(d);
        }
        if (!room.isEmpty()) return new Found(room, false);

        // score: every word the user said must belong to the device (its name, room, or kind)
        int best = 0;
        List<Device> top = new ArrayList<>();
        for (Device d : all) {
            Set<String> dw = new HashSet<>(Arrays.asList(norm(d.location + " " + d.name + " " + d.entityId.replace('_', ' ').replace('.', ' ')).split(" ")));
            int hit = 0;
            boolean ok = true;
            for (String w : qw) {
                if (dw.contains(w)) hit += 3;
                else if (KIND.containsKey(w) && KIND.get(w).equals(d.domain())) hit += 2;
                else if (!SOFT.contains(w)) { ok = false; break; }
            }
            if (!ok) continue;
            // the more of the device's own name was said, the surer
            Set<String> nameWords = new HashSet<>(Arrays.asList(norm(d.name).split(" ")));
            int named = 0;
            for (String w : nameWords) if (qw.contains(w)) named++;
            int score = hit * 10 + named * 3 - Math.max(0, nameWords.size() - named);
            if (score > best) { best = score; top.clear(); top.add(d); }
            else if (score == best) top.add(d);
        }
        if (top.isEmpty()) return new Found(none, false);
        if (wantAll) {
            // every device the words fit, not only the best scored
            List<Device> group = new ArrayList<>();
            for (Device d : all) {
                Set<String> dw = new HashSet<>(Arrays.asList(norm(d.location + " " + d.name).split(" ")));
                boolean ok = true;
                for (String w : qw) {
                    if (!dw.contains(w) && !(KIND.containsKey(w) && KIND.get(w).equals(d.domain())) && !SOFT.contains(w)) { ok = false; break; }
                }
                if (ok) group.add(d);
            }
            return new Found(group, false);
        }
        // a room on its own ("phòng ngủ") means everything in it
        if (top.size() > 1) {
            boolean allRoom = true;
            for (Device d : top) {
                Set<String> lw = new HashSet<>(Arrays.asList(norm(d.location).split(" ")));
                Set<String> need = new HashSet<>(qw);
                need.removeAll(SOFT);
                if (!lw.containsAll(need)) { allRoom = false; break; }
            }
            if (allRoom) return new Found(top, false);
        }
        return new Found(top, top.size() > 1);
    }

    public static Device byEntity(String id, List<Device> all) {
        for (Device d : all) if (d.entityId.equalsIgnoreCase(id)) return d;
        return null;
    }
}
