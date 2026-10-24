package info.dourok.voicebot.ha;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the address of a ".local" name (homeassistant.local) with one multicast DNS question.
 *
 * Android 5.1 resolves only through the router's DNS, and most home routers do not know the names
 * devices announce over mDNS, so "http://homeassistant.local:8123" -- the address Home Assistant
 * itself tells people to use -- failed on the speaker. The question goes to 224.0.0.251:5353 from
 * an ordinary port: RFC 6762 §6.7 "legacy unicast", which Home Assistant (python-zeroconf) and
 * Avahi answer straight back to that port, so no multicast lock is needed to hear the reply.
 *
 * Plain Java, so that the packet code runs off the device.
 */
public final class MdnsResolver {
    private MdnsResolver() {}

    private static final int TYPE_A = 1;
    private static final int CLASS_IN = 1;
    private static final long CACHE_MS = 5 * 60 * 1000L;

    private static final Map<String, Object[]> cache = new HashMap<>();   // name -> {InetAddress, until}

    public static boolean isLocalName(String host) {
        return host != null && host.toLowerCase(Locale.ROOT).endsWith(".local");
    }

    /** The IPv4 address of {@code host} (a ".local" name), or null when nobody answers in time. */
    public static InetAddress resolve(String host, int timeoutMs) {
        String name = host.toLowerCase(Locale.ROOT);
        synchronized (cache) {
            Object[] hit = cache.get(name);
            if (hit != null && (Long) hit[1] > System.currentTimeMillis()) return (InetAddress) hit[0];
        }
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            InetAddress group = InetAddress.getByName("224.0.0.251");
            int id = (int) (System.nanoTime() & 0xFFFF);
            byte[] q = query(name, id);
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[1500];
            for (int attempt = 0; attempt < 3 && System.currentTimeMillis() < end; attempt++) {
                s.send(new DatagramPacket(q, q.length, group, 5353));
                long waitUntil = Math.min(end, System.currentTimeMillis() + timeoutMs / 3 + 1);
                while (System.currentTimeMillis() < waitUntil) {
                    s.setSoTimeout((int) Math.max(1, waitUntil - System.currentTimeMillis()));
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    byte[] ip = answer(buf, p.getLength(), name);
                    if (ip != null) {
                        InetAddress a = InetAddress.getByAddress(host, ip);
                        synchronized (cache) { cache.put(name, new Object[] { a, System.currentTimeMillis() + CACHE_MS }); }
                        return a;
                    }
                }
            }
        } catch (Exception ignored) {
            // no network, or multicast refused: the caller says it could not find the name
        } finally {
            if (s != null) s.close();
        }
        return null;
    }

    /** A standard query for the A record of {@code name}, asking for a unicast reply (QU bit). */
    static byte[] query(String name, int id) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(id >> 8); o.write(id);
        o.write(0); o.write(0);              // flags: standard query
        o.write(0); o.write(1);              // one question
        for (int i = 0; i < 6; i++) o.write(0);
        for (String label : name.split("\\.")) {
            byte[] b = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            o.write(b.length);
            o.write(b, 0, b.length);
        }
        o.write(0);
        o.write(0); o.write(TYPE_A);
        o.write(0x80); o.write(CLASS_IN);    // QU: "unicast response wanted"
        return o.toByteArray();
    }

    /** The 4 address bytes of an A record for {@code name} in a response, or null. */
    static byte[] answer(byte[] m, int len, String name) {
        try {
            if (len < 12 || (m[2] & 0x80) == 0) return null;          // not a response
            int qd = u16(m, 4), an = u16(m, 6), ns = u16(m, 8), ar = u16(m, 10);
            int[] pos = { 12 };
            for (int i = 0; i < qd; i++) { readName(m, len, pos); pos[0] += 4; }
            int records = an + ns + ar;
            for (int i = 0; i < records && pos[0] < len; i++) {
                String rn = readName(m, len, pos);
                int p = pos[0];
                if (p + 10 > len) return null;
                int type = u16(m, p), rdlen = u16(m, p + 8);
                p += 10;
                if (p + rdlen > len) return null;
                if (type == TYPE_A && rdlen == 4 && rn.equalsIgnoreCase(name)) {
                    return new byte[] { m[p], m[p + 1], m[p + 2], m[p + 3] };
                }
                pos[0] = p + rdlen;
            }
        } catch (RuntimeException e) {
            // a malformed packet from someone else on the network: not ours
        }
        return null;
    }

    private static int u16(byte[] m, int p) { return ((m[p] & 0xFF) << 8) | (m[p + 1] & 0xFF); }

    /** A (possibly compressed) domain name at pos[0]; pos[0] moves past it. */
    private static String readName(byte[] m, int len, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int p = pos[0], jumps = 0;
        boolean jumped = false;
        while (true) {
            if (p >= len) throw new IllegalArgumentException("name");
            int l = m[p] & 0xFF;
            if (l == 0) { p++; break; }
            if ((l & 0xC0) == 0xC0) {
                if (p + 1 >= len || ++jumps > 16) throw new IllegalArgumentException("ptr");
                int target = ((l & 0x3F) << 8) | (m[p + 1] & 0xFF);
                if (!jumped) pos[0] = p + 2;
                jumped = true;
                p = target;
                continue;
            }
            if (p + 1 + l > len) throw new IllegalArgumentException("label");
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(m, p + 1, l, java.nio.charset.StandardCharsets.UTF_8));
            p += 1 + l;
        }
        if (!jumped) pos[0] = p;
        return sb.toString();
    }
}
